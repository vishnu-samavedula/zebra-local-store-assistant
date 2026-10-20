"""Application-owned workflow state for the five warehouse tools."""

from copy import deepcopy
from dataclasses import dataclass, field
import hashlib
import json
import re
import sqlite3
from uuid import uuid4

from .contracts import READ_TOOLS, WRITE_TOOLS, decode
from .grounding import check_fields, normalize, product_names, withdrawn_fields, contains, NUMBERS
from .inventory import lookup, public, resolve
from .controls import is_confirmation

QUESTIONS = {
    "operation_or_product": "Which product or inventory lookup do you need?",
    "product_name_or_category": "Which product or category should I check?",
    "product_name": "Which product do you mean?", "variant": "Which variant do you mean?",
    "variant.color": "Which color?", "variant.size": "Which size?",
    "section": "Which section?", "bin": "Which bin in that section?",
    "quantity": "What exact quantity do you need?", "observed_count": "What exact count did you observe?",
    "location": "Where did you notice this? A description is enough.",
    "observation": "What did you notice?", "damage_description": "What damage can you see?",
}

SCOPE_REPLIES = {"I can help with warehouse tasks.",
                 "I can look up inventory, record concerns or requests, and show assigned tasks."}
APPROVED_MODEL_TEXT = set(QUESTIONS.values()) | SCOPE_REPLIES | {
    "Which product?", "Which warehouse task do you need?", "What would you like to do?",
    "Do you want the stock count or the product location?", "Do you want a stock count or location?",
    "Please specify stock count or location.", "Where did this happen?",
}


@dataclass
class Request:
    tool: str
    arguments: dict
    key: str = field(default_factory=lambda: uuid4().hex)
    turns: list = field(default_factory=list)
    questions: int = 0
    waiting: bool = False
    missing: list = field(default_factory=list)
    preview: dict | None = None
    revision: int = 0
    last_question: str | None = None
    repeats: int = 0


class Harness:
    def __init__(self, store, procedures, worker="demo-worker", snapshot_time=None, report_text_owned=False):
        self.store, self.procedures = store, procedures
        self.worker, self.snapshot_time = worker, snapshot_time
        self.report_text_owned = report_text_owned
        self.active = None
        self.interrupted_read = None
        self.interrupted_queue = []
        self.queue = []
        self.page_items = []
        self.page_offset = 0
        self.choices = []
        self.last_saved = None
        self.preview_visible = False
        self.events = {}
        self.model_history = []
        self.request_histories = {}
        self.unresolved_turns = []

    def new_request(self):
        self.model_history = []
        self.request_histories = {}
        self.unresolved_turns = []
        self.active = None
        self.interrupted_read = None
        self.interrupted_queue = []
        self.queue = []
        self.page_items = []
        self.page_offset = 0
        self.choices = []
        self.preview_visible = False
        self.last_saved = None

    def independent_inventory_turn(self, text):
        """Select fresh input for a clearly separate read, never authorize a tool."""
        if not (self.active and self.active.tool in WRITE_TOOLS) or self.interrupted_read:
            return False
        text = text.lower().strip().replace("’", "'")
        if re.search(r"\b(request|replenish|add|report|damaged|torn|leaking)\b", text):
            return False
        return bool(re.match(
            r"(?:how (?:many|much)\b.*\b(?:have|stock)\b|where(?: (?:is|are)|'(?:s|re))\b|"
            r"what(?: is|'s) in\b|(?:check|count) (?:the )?stock\b|list (?:section|bin)\b)", text))

    def context(self, transcript=None):
        request = self.interrupted_read or self.active
        fresh_read = transcript is not None and self.independent_inventory_turn(transcript)
        if fresh_read:
            request = None
        return {"phase": "awaiting_confirmation" if request and request.preview else "clarifying" if request else "idle",
                "focused_call": {"tool": request.tool, "arguments": deepcopy(request.arguments)} if request else None,
                "preview_revision": request.revision if request else None,
                "clarification_questions_asked": request.questions if request else 0,
                "latest_question": request.last_question if request else None,
                "verified_choices": list(self.choices) if request else [],
                "full_request_turns": list(request.turns) if request else [] if fresh_read else list(self.unresolved_turns),
                "model_history": deepcopy(self.request_histories.get(request.key, [])) if request else [] if fresh_read else deepcopy(self.model_history)}

    def handle(self, transcript, model_output, *, input_mode="text", finish_reason="stop", event_id=None, confirmed_preview=None):
        fingerprint = hashlib.sha256(json.dumps([transcript, model_output, input_mode, finish_reason, confirmed_preview], sort_keys=True).encode()).hexdigest()
        if event_id in self.events:
            previous_fingerprint, previous_result = self.events[event_id]
            if fingerprint != previous_fingerprint:
                return {"kind": "failed", "message": "This input event was already used for another turn.", "saved": False}
            return deepcopy(previous_result)
        if not isinstance(transcript, str) or not transcript.strip():
            return {"kind": "failed", "message": "No speech was recognized. Try again or type instead.", "saved": False}
        focused = self.interrupted_read or self.active
        previous_request = focused
        previous_choices = list(self.choices)
        fresh_read = self.independent_inventory_turn(transcript)
        if focused and withdrawn_fields(transcript, focused.arguments):
            # A malformed/truncated model response cannot leave the old preview
            # executable after the worker has explicitly withdrawn its evidence.
            focused.preview = None
            self.preview_visible = False
        incident_turns = [] if fresh_read else focused.turns if focused else self.unresolved_turns
        full_context = " ".join(incident_turns + [transcript])
        urgent = self.procedures.emergency(full_context)
        # A standalone observation can receive grounded guidance without proposing
        # a report. Pending requests and explicit reporting intent retain the
        # normal validation/preview/confirmation path.
        guidance = None
        explicit_report = re.search(r'\b(report(?:ing)?|log|file|submit)\b|\b(?:create|open|raise)\s+(?:a\s+)?ticket\b', transcript.lower())
        if self.report_text_owned and not hasattr(self, 'mode') and focused is None and not explicit_report and any(
            self.procedures.applicable(card_id, transcript, None) for card_id in self.procedures.cards
        ):
            retrieved = self.procedures.retrieve(transcript, None)
            if retrieved['cards']:
                guidance = retrieved
        envelope = None
        terminal_text = isinstance(model_output, str) and model_output.strip() in SCOPE_REPLIES
        try:
            if guidance is not None:
                result = {'kind': 'guidance', 'message': '', 'procedures': guidance,
                          'tools': [], 'saved': False}
            elif finish_reason == "length":
                raise ValueError("Model output was truncated")
            elif self._plain_text(model_output):
                if model_output.strip() not in APPROVED_MODEL_TEXT:
                    if focused and not fresh_read:
                        focused.preview = None
                        self.preview_visible = False
                    raise ValueError("Model prose is not an approved clarification or scope reply")
                # Text alone proposes no operation and cannot confirm, cancel or save.
                if terminal_text:
                    self.preview_visible = False
                elif focused and not fresh_read:
                    focused.turns.append(transcript)
                    # An uninterpreted follow-up may contain a correction; an older
                    # preview must not remain executable until the turn is resolved.
                    focused.preview = None
                    self.preview_visible = False
                elif not terminal_text:
                    self.unresolved_turns.append(transcript)
                result = {"kind": "conversation", "message": model_output.strip(),
                          "tools": [], "saved": False}
            else:
                envelope = decode(model_output)
                result = self._dispatch(transcript, envelope, input_mode, confirmed_preview)
                result["tools"] = list(dict.fromkeys(call["tool"] for call in envelope["calls"]))
        except (ValueError, OSError, RuntimeError, sqlite3.Error) as error:
            if focused and not fresh_read and focused.tool in WRITE_TOOLS and (envelope is None or envelope['action'] not in {'confirm', 'cancel', 'show_more'}):
                focused.preview = None
                self.preview_visible = False
            result = {"kind": "failed", "message": "I couldn't validate this request. Please restate it or type instead.", "error": str(error), "saved": False}
        if self.report_text_owned and 'procedures' not in result:
            # An invalid model call must not suppress approved report guidance.
            # This never authorizes a write or substitutes another generated tool.
            report_text = re.search(r'\b(report(?:ing)?|concern|damaged?|leak(?:ing)?|spill|smell|cough(?:ing)?)\b', transcript.lower())
            report_pending = focused and focused.tool in {'report_general_concern', 'report_product_damage'}
            if report_text or report_pending:
                result['procedures'] = self.procedures.retrieve(full_context, 'report_general_concern')
        # Safety records remain available even if parsing/retrieval failed or clarification stopped.
        if urgent:
            result["safety"] = urgent
            result["safety_guidance_before_report"] = True
        messages = [{"role": "user", "content": transcript}]
        if result["kind"] not in {"failed", "guidance"}:
            content = model_output if isinstance(model_output, str) else json.dumps(model_output)
            if envelope is None or envelope["action"] not in {"confirm", "cancel", "show_more"}:
                messages.append({"role": "assistant", "content": content})
        if result["kind"] != "conversation":
            # Policy/procedure prose is displayed by the app. The model only needs
            # bounded workflow state and verified read results for its next proposal.
            model_result = {key: result[key] for key in (
                "kind", "message", "missing", "known_arguments", "items", "totals",
                "preview", "saved", "has_more", "pending_report_preserved") if key in result}
            if "preview" in model_result:
                model_result["preview"] = {key: result["preview"][key] for key in
                                           ("tool", "arguments", "revision") if key in result["preview"]}
            messages.append({"role": "tool", "content": json.dumps(model_result)})
        request = self.interrupted_read or self.active
        if result["kind"] == "failed" and request is previous_request and request is not None and not fresh_read:
            if not request.turns or request.turns[-1] != transcript:
                request.turns.append(transcript)
        calls = envelope.get("calls", []) if envelope else []
        belongs_to_request = request and (
            (calls and calls[0]["tool"] == request.tool and request.turns and request.turns[-1] == transcript)
            or (not calls and request is previous_request and not fresh_read and result["kind"] != "unsupported"))
        if envelope and envelope["action"] in {"confirm", "cancel", "show_more"}:
            belongs_to_request = False
        if terminal_text:
            belongs_to_request = False
        if belongs_to_request:
            history = self.request_histories.setdefault(request.key, deepcopy(self.model_history) if previous_request is None else [])
            history.extend(messages)
            self.request_histories[request.key] = history[-24:]
        if request:
            self.model_history = []
            self.unresolved_turns = []
            if request is previous_request and (fresh_read or (envelope is not None and envelope["action"] == "show_more")):
                self.choices = previous_choices
        elif result["kind"] == "conversation" and not terminal_text:
            self.model_history = (self.model_history + messages)[-24:]
        elif result["kind"] != "failed":
            self.model_history = []
            self.unresolved_turns = []
            self.choices = []
        live = [self.active, self.interrupted_read, *self.queue, *self.interrupted_queue]
        live_keys = {r.key for r in live if r is not None}
        self.request_histories = {key: value for key, value in self.request_histories.items() if key in live_keys}
        if event_id is not None:
            self.events[event_id] = (fingerprint, deepcopy(result))
            if len(self.events) > 64:
                self.events.pop(next(iter(self.events)))
        return result

    def withdrawal_output(self, transcript):
        """App-owned control: no model inference needed for a named withdrawal."""
        from .native_tools import render_marked_calls
        focused = self.interrupted_read or self.active
        if not focused or not withdrawn_fields(transcript, focused.arguments):
            return None
        return render_marked_calls([{"tool": focused.tool, "arguments": {}}])

    @staticmethod
    def _plain_text(output):
        if not isinstance(output, str) or not output.strip():
            return False
        text = output.strip()
        if "<|tool_call_" in text or text.startswith(("{", "[", "```")):
            return False
        # A broken/unmarked function call must never become conversational prose.
        return not re.match(r"^[A-Za-z_][A-Za-z0-9_]*\s*\(", text)

    def _dispatch(self, text, envelope, mode, confirmed_preview):
        action = envelope["action"]
        if action == "confirm":
            return self._confirm(text, confirmed_preview)
        if action == "cancel":
            if not re.search(r"\b(cancel|stop|never mind|nevermind)\b", text.lower()):
                raise ValueError("Cancellation is not grounded in the worker turn")
            if self.interrupted_read:
                self.interrupted_read = None
                return {"kind": "cancelled", "message": "Pending lookup cancelled; report preserved.", "saved": False}
            if not self.active:
                return {"kind": "result", "message": "There is no pending request to cancel.", "saved": False}
            self.active = self.queue.pop(0) if self.queue else None
            self.preview_visible = False
            result = {"kind": "cancelled", "message": "Pending request cancelled.", "saved": False}
            if self.active:
                call = {"tool": self.active.tool, "arguments": deepcopy(self.active.arguments)}
                result["next_request"] = self._process(self.active, call, "", "propose", mode)
            return result
        if action == "show_more":
            if not re.search(r"\b(more|next)\b", text.lower()):
                raise ValueError("Pagination is not grounded")
            return self._page()
        if action == "unsupported":
            self.preview_visible = False
            return {"kind": "unsupported", "message": "I can look up inventory, record concerns or requests, and show assigned tasks.", "saved": False}
        calls = envelope["calls"]
        if self.report_text_owned and self.active is None and calls and calls[0]['tool'] == 'report_general_concern':
            explicit_damage = re.search(r'\breport(?:ing)?\s+(?:a\s+)?(?:product\s+)?damage\b', text.lower()) and not re.search(r'\bgeneral concern\b', text.lower())
            if explicit_damage and any(contains(normalize(text), normalize(value)) for row in self.store.products
                                      for value in [row['name'], row['category'], *row.get('aliases', [])]):
                self.unresolved_turns.append(text)
                return {'kind': 'conversation', 'message': 'Which product do you mean?',
                        'reason': 'specific_damage_requires_product_tool', 'saved': False}
        if self.report_text_owned and self.active is None and calls and any(
                call['tool'] in {'report_general_concern', 'report_product_damage'} for call in calls):
            if normalize(text) in {'too much', 'handle that', 'report it', 'report that', 'something', 'yes', 'okay', 'ok'}:
                self.unresolved_turns.append(text)
                return {'kind': 'conversation', 'message': 'What did you notice?',
                        'reason': 'report_observation_unclear', 'saved': False}
        if not calls:
            if not self.active:
                self.active = Request("inventory_lookup", {})
            self.active.turns.append(text)
            return self._ask(self.active, self.active.missing or ["operation_or_product"], mode)
        focused = self.interrupted_read or self.active
        if action == "correct" and (not focused or calls[0]["tool"] != focused.tool):
            return {"kind": "clarification", "message": "Which pending request do you want to change?", "saved": False}
        # Only inventory reads may interrupt a pending write without replacing its draft.
        if self.active and self.active.tool in WRITE_TOOLS and all(c["tool"] == "inventory_lookup" for c in calls):
            if len(calls) > 1 and self.interrupted_read:
                raise ValueError("Finish the focused inventory lookup first")
            results = []
            for index, call in enumerate(calls):
                read = self.interrupted_read if index == 0 and self.interrupted_read else Request(call["tool"], {})
                if index > 0 and self.interrupted_read:
                    self._merge(read, call, text, action)
                    trial = lookup(self.store, read.arguments)
                    if trial["kind"] == "clarification":
                        self.interrupted_queue.append(read)
                        results.append({"kind": "queued", "message": "Another lookup is waiting for separate clarification.", "saved": False})
                        continue
                result = self._process(read, call, text, action, mode)
                if result["kind"] in {"clarification", "paused"}:
                    self.interrupted_read = read
                elif self.interrupted_read is read:
                    self.interrupted_read = None
                results.append(result)
            if self.interrupted_read is None and self.interrupted_queue:
                self.interrupted_read = self.interrupted_queue.pop(0)
                call = {"tool": self.interrupted_read.tool, "arguments": self.interrupted_read.arguments.copy()}
                results[-1]["next_request"] = self._process(self.interrupted_read, call, "", "propose", mode)
            self.preview_visible = False
            return self._combine(results, pending_report_preserved=True)
        if self.active and self.active.tool != calls[0]["tool"]:
            return {"kind": "clarification", "message": "Finish or cancel the pending request, or start a new request.", "saved": False}
        if len(calls) > 1 and self.active:
            raise ValueError("Finish the focused request before proposing another batch")
        requests = [self.active or Request(calls[0]["tool"], {}, turns=list(self.unresolved_turns))]
        requests += [Request(c["tool"], {}) for c in calls[1:]]
        results = []
        for request, call in zip(requests, calls):
            if self.active and self.active is not request and self.active.waiting:
                self._merge(request, call, text, action)
                if request.tool in READ_TOOLS:
                    trial = lookup(self.store, request.arguments) if request.tool == "inventory_lookup" else None
                    if trial and trial["kind"] == "clarification":
                        self.queue.append(request)
                        results.append({"kind": "queued", "message": "Another lookup is waiting for separate clarification.", "saved": False})
                        continue
            if request.tool in WRITE_TOOLS and any(r.tool in WRITE_TOOLS for r in self.queue + ([self.active] if self.active else []) if r is not request):
                self._merge(request, call, text, action)
                self.queue.append(request)
                results.append({"kind": "queued", "message": "Another request is queued for a separate preview and confirmation.", "saved": False})
                continue
            result = self._process(request, call, text, action, mode)
            results.append(result)
            if result["kind"] in {"clarification", "preview", "paused"}:
                if self.active and self.active is not request:
                    self.queue.append(request)
                else:
                    self.active = request
            elif self.active is request:
                self.active = None
        if self.active is None and self.queue:
            self.active = self.queue.pop(0)
            call = {"tool": self.active.tool, "arguments": deepcopy(self.active.arguments)}
            results[-1]["next_request"] = self._process(self.active, call, "", "propose", mode)
        return self._combine(results)

    @staticmethod
    def _combine(results, **extra):
        result = results[0] if len(results) == 1 else {"kind": "batch", "results": results, "saved": False}
        result.update(extra)
        return result

    def _merge(self, request, call, text, action):
        if self.report_text_owned and request.tool in {"report_general_concern", "report_product_damage"}:
            call = deepcopy(call)
            allowed = set() if request.tool == "report_general_concern" else {"product_name", "variant", "quantity"}
            call["arguments"] = {key: value for key, value in call["arguments"].items() if key in allowed}
            # Legacy checkpoints may still emit free text. Never use it in a report.
            call["clear_fields"] = [field for field in call.get("clear_fields", []) if field.split('.')[0] in allowed]
        old = deepcopy(request.arguments)
        inferred_clear = withdrawn_fields(text, old)
        clear = list(dict.fromkeys(call.get("clear_fields", []) + inferred_clear))
        if clear:
            request.preview = None
            self.preview_visible = False
        for field_name in inferred_clear:
            head, _, tail = field_name.partition(".")
            if head in call["arguments"] and (not tail or tail in call["arguments"][head]):
                raise ValueError("Model repeated an explicitly withdrawn field")
        changed = {k: v for k, v in call["arguments"].items() if v != old.get(k)}
        number = r"(?:\d+|" + "|".join(NUMBERS) + r")"
        approximate_number = re.search(
            r"\b(?:about|around|roughly|approximately|close to|nearly|near|maybe|at least|up to)\s+" + number +
            r"\b|\b" + number + r"\s+(?:or so|ish)\b", text.lower())
        if approximate_number:
            for key in ("quantity", "observed_count"):
                if key in call["arguments"]:
                    changed[key] = call["arguments"][key]
        product_changed = "product_name" in changed and product_names(self.store, changed["product_name"]) != product_names(self.store, old.get("product_name", ""))
        if action == "correct" or (request.tool in WRITE_TOOLS and changed):
            request.preview = None
            self.preview_visible = False
        # Canonical repeated fields are already grounded; validate only newly supplied
        # variant attributes, not unchanged siblings carried by a multi-turn envelope.
        if "variant" in changed and not product_changed:
            changed["variant"] = {k: v for k, v in changed["variant"].items() if v != old.get("variant", {}).get(k)}
        evidence = " ".join(request.turns + [text]) if not old else text
        clarification_field = request.missing[0] if request.waiting and request.missing else None
        invalid = check_fields(changed, evidence, self.store, self.choices, tool=request.tool,
                               clarification_field=clarification_field)
        if invalid:
            raise ValueError("Ungrounded fields: " + ", ".join(invalid))
        if clear and not inferred_clear and not re.search(r"\b(don.t know|do not know|unknown|forget|remove|not sure|unsure|clear)\b", text.lower()):
            raise ValueError("Field withdrawal is not grounded")
        args = deepcopy(old)
        if product_changed:
            args.pop("variant", None)
        for field_name in clear:
            head, _, tail = field_name.partition(".")
            if tail:
                args.get(head, {}).pop(tail, None)
                if args.get(head) == {}:
                    args.pop(head, None)
            else:
                args.pop(head, None)
                if head == "product_name":
                    args.pop("variant", None)
        for key, value in call["arguments"].items():
            if key == "variant":
                args[key] = {**args.get(key, {}), **value}
            else:
                args[key] = value
        if request.tool in WRITE_TOOLS and args != old:
            request.preview = None
        request.arguments = args
        if text:
            request.turns.append(text)
        if self.report_text_owned and request.tool in {"report_general_concern", "report_product_damage"}:
            args["report_text"] = "\n".join(request.turns)
            if args != old:
                request.preview = None
                self.preview_visible = False
            request.arguments = args
        self.unresolved_turns = []
        request.waiting = False

    def _ask(self, request, missing, mode):
        request.missing = missing
        if request.questions >= 5:
            return {"kind": "paused", "message": "Type the remaining details or start a new request.", "missing": missing,
                    "known_arguments": deepcopy(request.arguments), "options": ["text_input", "new_request"], "saved": False}
        question = QUESTIONS.get(missing[0], "Please clarify " + missing[0] + ".")
        request.repeats = request.repeats + 1 if request.last_question == question else 0
        if request.repeats >= 2:
            request.questions = 5
            return self._ask(request, missing, mode)
        request.questions += 1
        request.last_question = question
        request.waiting = True
        return {"kind": "clarification", "missing": missing, "message": question,
                "clarification_questions_asked": request.questions, "known_arguments": deepcopy(request.arguments), "saved": False}

    def _process(self, request, call, text, action, mode):
        self._merge(request, call, text, action)
        args = request.arguments
        if request.tool == "inventory_lookup":
            result = lookup(self.store, args)
            if action == "clarify" or result["kind"] == "clarification":
                return self._ask(request, result.get("missing", ["operation_or_product"]), mode)
            return self._results(result)
        if request.tool == "task_lookup":
            if args.get("operation") != "list_assigned_tasks":
                return self._ask(request, ["operation"], mode)
            return {"kind": "result", "tasks": self.store.assigned(self.worker), "snapshot_time": self.snapshot_time,
                    "message": "Current assigned tasks from the local snapshot.", "saved": False}
        full = " ".join(request.turns)
        guidance = self.procedures.retrieve(full, request.tool)
        required = {"report_general_concern": ["observation", "location"],
                    "report_product_damage": ["product_name", "damage_description", "location"],
                    "request_replenishment": ["product_name", "quantity"]}[request.tool]
        if self.report_text_owned and request.tool in {"report_general_concern", "report_product_damage"}:
            required = ["report_text"] + (["product_name"] if request.tool == "report_product_damage" else [])
        missing = [k for k in required if k not in args]
        rows, variant_missing, reason = resolve(self.store, args, exact=True) if request.tool != "report_general_concern" else ([], [], None)
        if reason:
            result = self._ask(request, ["product_name" if reason == "product_not_in_catalog" else "variant"], mode)
            result.update(reason=reason, message="I couldn't resolve that product/variant. Choose another, or cancel and report a general concern.", procedures=guidance)
            return result
        missing += [k for k in variant_missing if k not in missing]
        if missing or action == "clarify":
            result = self._ask(request, missing or ["operation_or_product"], mode)
            result["procedures"] = guidance
            return result
        if rows:
            product = public(rows[0])
            unit_evidence = full
            for catalog_name in {p['name'] for p in self.store.products}:
                unit_evidence = re.sub(re.escape(catalog_name), '', unit_evidence, flags=re.IGNORECASE)
            supplied_unit = re.search(r"\b(pairs?|packs?|bottles?|units?|items?)\b", unit_evidence.lower())
            if supplied_unit and request.tool == "request_replenishment":
                spoken = supplied_unit.group().rstrip("s")
                if spoken not in {product["unit"], "unit", "item"}:
                    return self._ask(request, ["quantity"], mode)
        else:
            product = None
        request.revision += 1
        request.preview = {"request_key": request.key, "revision": request.revision, "tool": request.tool,
                           "arguments": deepcopy(args), "resolved_product": product}
        self.preview_visible = True
        return {"kind": "preview", "preview": deepcopy(request.preview), "procedures": guidance,
                "message": "Review these details. Confirm to create the ticket, or correct or cancel.", "saved": False}

    def _results(self, result):
        result["saved"] = False
        if result.get("items") and "totals" not in result:
            self.page_items = result["items"]
            self.page_offset = 0
            page = self._page()
            result.update(page)
        self.choices = [r.get("name", "") for r in result.get("items", [])]
        return result

    def _page(self):
        if not self.page_items or self.page_offset >= len(self.page_items):
            return {"kind": "result", "items": [], "has_more": False, "message": "No more results.", "saved": False}
        items = self.page_items[self.page_offset:self.page_offset + 3]
        self.page_offset += len(items)
        self.choices = [r.get("name", "") for r in items]
        return {"kind": "result", "items": deepcopy(items), "has_more": self.page_offset < len(self.page_items), "message": "Recorded catalog matches.", "saved": False}

    def _confirm(self, text, confirmed_preview):
        request = self.active
        if not request or not request.preview:
            if self.last_saved:
                return {"kind": "result", "reason": "already_saved", "ticket": deepcopy(self.last_saved), "message": "This ticket was already created.", "saved": False}
            return {"kind": "clarification", "reason": "no_active_preview", "message": "There is no report or request awaiting confirmation.", "saved": False}
        accepted = is_confirmation(text)
        explicit = re.search(r"\b(report|request|ticket|replenishment)\b", text.lower())
        current = {"request_key": request.key, "revision": request.revision}
        stale = confirmed_preview is not None and confirmed_preview != current
        if stale or not accepted or (not self.preview_visible and not explicit):
            return {"kind": "preview", "preview": deepcopy(request.preview), "message": "Please confirm this pending ticket explicitly, or correct or cancel it.", "saved": False}
        if request.preview["arguments"] != request.arguments or request.preview["revision"] != request.revision:
            raise ValueError("The preview no longer matches the active request")
        if request.tool != "report_general_concern":
            rows, missing, reason = resolve(self.store, request.arguments, exact=True)
            if missing or reason or rows[0]["product_id"] != request.preview["resolved_product"]["product_id"]:
                raise ValueError("The product no longer matches the preview")
        category = {"request_replenishment": "Replenishment", "report_product_damage": "Product damage"}.get(request.tool, "Discrepancy" if re.search(r"\b(discrepancy|wrong count|empty bin|missing stock)\b", " ".join(request.turns).lower()) else "General concern")
        stock_product = (request.preview['resolved_product']['product_id']
                         if request.tool == 'request_replenishment' and getattr(self, 'replenishment_updates_stock', False) else None)
        ticket = self.store.save(request.key, request.tool, deepcopy(request.preview["arguments"]), "\n".join(request.turns), category,
                                 replenishment_product_id=stock_product)
        verified = self.store.get(request.key)
        if not ticket or verified != ticket:
            raise RuntimeError("Saved record could not be verified")
        self.last_saved = ticket
        self.interrupted_read = None
        self.interrupted_queue = []
        self.active = self.queue.pop(0) if self.queue else None
        self.preview_visible = False
        if self.active:
            # It must get its own visible preview before its own confirmation.
            self.active.preview = None
        result = {"kind": "saved", "ticket": deepcopy(ticket), "message": f"{ticket['reference']} has been created locally. You can view it in My tickets.", "saved": True}
        if ticket.get('inventory_change'):
            change = ticket['inventory_change']
            result['inventory_change'] = deepcopy(change)
            result['message'] += f" Added {change['added']} {change['unit']} to local demo stock: {change['before']} → {change['after']} available."
        if self.active:
            next_call = {"tool": self.active.tool, "arguments": deepcopy(self.active.arguments)}
            result["next_request"] = self._process(self.active, next_call, "", "propose", "text")
        return result
