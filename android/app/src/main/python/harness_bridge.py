"""Android boundary around the existing, shared warehouse harness."""
import json
import os
import re
import threading
from pathlib import Path

_sessions = {}
_lock = threading.RLock()
_root = None
_database = None
_retriever = None
_one_shot = False


class Retriever:
    def retrieve(self, text, k):
        try:
            return json.loads(_retriever.retrieve(text, k))
        except Exception as error:
            raise RuntimeError("Local procedure retrieval failed") from error


def initialize(root, database, retriever, one_shot=False):
    global _root, _database, _retriever, _one_shot
    with _lock:
        _root, _database, _retriever = Path(root), database, retriever
        _one_shot = bool(one_shot)
        os.environ["ZEBRA_HARNESS_ROOT"] = root
        # Environment is set before importing contracts and loading its schema.
        from warehouse_harness import Harness
        return json.dumps({"ready": True})


def session(chat):
    if chat not in _sessions:
        from warehouse_harness import Harness
        from warehouse_harness.store import Store
        from warehouse_harness.procedures import Procedures
        snapshot = json.loads((_root / "seed_data/industrial_task_snapshot.json").read_text())
        tasks = [{"task_id": task["id"], "assigned_worker": task["worker_id"],
                  "description": task["description"], "status": task["status"],
                  "location": task["location"], "details": task["details"],
                  "updated_at": task["updated_at"]} for task in snapshot["tasks"]]
        store = Store(_root / "seed_data/warehouse_catalog.jsonl", _database,
                      tasks, check_same_thread=False)
        if _one_shot:
            from warehouse_harness.floor_workflow import FloorHarness
            Harness = FloorHarness
        _sessions[chat] = Harness(store, Procedures(_root / "seed_data/sop_cards.jsonl", Retriever()),
                                  worker=snapshot["worker_id"], snapshot_time=snapshot["updated_at"], report_text_owned=True)
    return _sessions[chat]


def context(chat, transcript=None):
    with _lock:
        return json.dumps(session(chat).context(transcript))


def set_mode(chat, mode):
    with _lock:
        session(chat).set_mode(mode)


def restart(chat):
    with _lock:
        session(chat).new_request()


def complete_form(chat, product_id, quantity, event_id):
    with _lock:
        return json.dumps(session(chat).complete_form(product_id, quantity, event_id))


def select_read(chat, product_id):
    with _lock:
        return json.dumps(session(chat).select_read(product_id))


def choose_report(chat, offer_id, accepted):
    with _lock:
        return json.dumps(session(chat).choose_report(offer_id, bool(accepted)))


def confirmation_preview(chat):
    with _lock:
        harness = session(chat)
        preview = harness.active.preview if harness.active else None
        if not preview or not harness.preview_visible:
            return 'null'
        return json.dumps({key: preview[key] for key in ('request_key', 'revision', 'tool')})


def confirm_preview(chat, request_key, revision, event_id):
    with _lock:
        harness = session(chat)
        result = harness.handle('Confirm the report', {'schema_version': '1.0', 'action': 'confirm', 'calls': []},
            confirmed_preview={'request_key': request_key, 'revision': revision}, event_id=event_id)
        return json.dumps(result)


def control(chat, text, mode, event_id):
    """Only the worker turn and trusted pending state select control operations."""
    from warehouse_harness.controls import is_confirmation
    with _lock:
        harness = session(chat)
        withdrawal = harness.withdrawal_output(text)
        if withdrawal is not None:
            return json.dumps(harness.handle(text, withdrawal, input_mode=mode, event_id=event_id))
        normalized = text.lower().strip()
        if _one_shot and harness.mode == 'sop' and harness.report_offer:
            if re.fullmatch(r'(?:yes|yes please|create (?:a |the )?report|no|no thanks|do not report)[.!]?', normalized):
                accepted = normalized.rstrip('.!') not in {'no', 'no thanks', 'do not report'}
                return json.dumps(harness.choose_report(harness.report_offer['id'], accepted))
            harness.report_offer = None
        if harness.report_text_owned and harness.active and harness.active.tool in {'report_general_concern', 'report_product_damage'} and re.fullmatch(
            r"(?:actually[, ]+)?(?:i (?:don't know|do not know|am not sure about)|i'm not sure about|(?:please )?(?:forget|remove|clear))\s+(?:the |that |my )?(?:quantity|count|observed count|location|observation|damage description)(?: after all| anymore| now)?[.!]?", normalized):
            # The original wording is the report. Append a literal worker correction;
            # do not ask the model to regenerate or invent a replacement field.
            args = {key: value for key, value in harness.active.arguments.items() if key in {'product_name', 'variant', 'quantity'}}
            if harness.active.tool == 'report_general_concern':
                args = {}
            return json.dumps(harness.handle(text, {'schema_version': '1.0', 'action': 'propose',
                'calls': [{'tool': harness.active.tool, 'arguments': args}]}, input_mode=mode, event_id=event_id))
        action = None
        if re.fullmatch(r"(?:cancel|stop|never mind|nevermind)(?: (?:it|that|the request|the report))?[.!]?", normalized):
            action = "cancel"
        elif harness.page_items and re.fullmatch(r"(?:show more|more|next|next page|show next page)[.!]?", normalized):
            action = "show_more"
        elif is_confirmation(text):
            action = "confirm"
        if action is None:
            if _one_shot and harness.mode == 'sop' and not harness.active:
                from warehouse_harness.floor_workflow import reporting_intent
                if not reporting_intent(text):
                    return json.dumps(harness.advise(text))
            if _one_shot and harness.active and not harness.independent_inventory_turn(text):
                # A new unresolved turn may be a correction. Failure during model
                # inference must not leave the older preview executable.
                harness.active.preview = None
                harness.preview_visible = False
            return "null"
        result = harness.handle(text, {"schema_version": "1.0", "action": action, "calls": []},
                                input_mode=mode, event_id=event_id)
        return json.dumps(result)


def handle(chat, transcript, output, mode, finish, event_id):
    with _lock:
        return json.dumps(session(chat).handle(transcript, output, input_mode=mode,
                                               finish_reason=finish, event_id=event_id))


def tickets():
    with _lock:
        # Open the persisted store even before the first chat after app restart.
        return json.dumps(session("__ticket_view__").store.tickets())


def catalog():
    with _lock:
        return json.dumps(session('__catalog_view__').store.catalog())


def forget_chat(chat):
    with _lock:
        harness = _sessions.pop(chat, None)
        if harness is not None:
            harness.store.close()


def review_reference_journeys():
    """Device integration review with supplied golden outputs, not model accuracy."""
    from warehouse_harness.engine import Request
    from warehouse_harness.contracts import encode_proposal
    with _lock:
        fixtures = [json.loads(line) for line in (_root / "contracts/reference_journeys.jsonl").read_text().splitlines()]
        outcomes = []
        for fixture in fixtures:
            harness = session("review-" + fixture["id"])
            # Frozen reference fixtures measure the original full-argument contract.
            harness.report_text_owned = False
            harness.new_request()
            initial = fixture["initial_context"]
            if initial.get("focused_call"):
                call = initial["focused_call"]
                harness.active = Request(call["tool"], call["arguments"].copy(),
                                         questions=initial.get("clarification_questions_asked", 0))
            for turn in fixture["turns"]:
                envelope = turn["expected_model_output"]
                output = harness.withdrawal_output(turn["worker_text"])
                if output is None:
                    output = encode_proposal(envelope) if envelope["action"] == "propose" else envelope
                result = harness.handle(turn["worker_text"], output)
                expected = turn["expected_harness"]
                wanted_kind = "batch" if expected.get("category_results") else expected["kind"]
                checks = [result["kind"] == wanted_kind]
                if "catalog_result" in expected:
                    checks.append(any(all(row.get(key) == value for key, value in expected["catalog_result"].items())
                                      for row in result.get("items", [])))
                if "saved_fields" in expected:
                    checks.append(expected["saved_fields"] == result.get("ticket", {}).get("arguments"))
                if expected.get("no_save") or expected.get("no_new_save"):
                    checks.append(not result["saved"])
                if "procedure_ids" in expected:
                    ids = {card["id"] for card in result.get("procedures", {}).get("cards", [])}
                    ids |= {card["id"] for card in result.get("safety", [])}
                    checks.append(set(expected["procedure_ids"]) <= ids)
                outcomes.append({"journey": fixture["id"], "kind": result["kind"], "passed": all(checks)})
        return json.dumps({"journeys": len(fixtures), "turns": len(outcomes),
                           "passed_turns": sum(item["passed"] for item in outcomes), "outcomes": outcomes,
                           "model_accuracy_test": False})


def close():
    with _lock:
        for harness in _sessions.values():
            harness.store.close()
        _sessions.clear()
