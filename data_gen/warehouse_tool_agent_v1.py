"""Scenario-controlled synthetic data for the TC501 warehouse tool agent.

The language model varies only the worker utterance. Tool calls, arguments,
tool results, and final grounded answers are constructed from scenario facts.
"""

from lqh.pipeline import (
    ChatMLMessage,
    Conversation,
    FunctionCall,
    GenerationError,
    Pipeline,
    ToolCall,
    ToolDef,
    safe_content,
    step,
)

import json
import random
import re

import lqh.sources as sources
import liquidrandom


SCENARIO_FAMILIES = [
    "inventory_single",
    "inventory_parallel",
    "location_contents",
    "task_status",
    "report_damage",
    "report_blocked_location",
    "replenishment_direct",
    "dependent_inventory_location",
    "conditional_replenishment",
    "no_call_edge",
]

UTTERANCE_STYLES = [
    "direct command",
    "natural question",
    "short warehouse fragment",
    "brief hesitation",
    "self-correction with the final value stated clearly",
    "light ASR-style missing punctuation while preserving every identifier and number",
]


def _tool_defs() -> list[ToolDef]:
    return [
        ToolDef(
            name="inventory_search",
            description="Search local inventory by product description, SKU, attributes, location, or required quantity.",
            parameters={
                "type": "object",
                "properties": {
                    "semantic_query": {"type": "string"},
                    "sku": {"type": "string"},
                    "color": {"type": "string"},
                    "size": {"type": "string"},
                    "location": {"type": "string"},
                    "minimum_quantity": {"type": "integer", "minimum": 1},
                },
                "additionalProperties": False,
            },
        ),
        ToolDef(
            name="location_contents",
            description="List or filter inventory stored at a warehouse bin, aisle, dock, staging area, or zone.",
            parameters={
                "type": "object",
                "properties": {
                    "location": {"type": "string"},
                    "semantic_query": {"type": "string"},
                },
                "required": ["location"],
                "additionalProperties": False,
            },
        ),
        ToolDef(
            name="get_task_status",
            description="Retrieve one warehouse task or filter the current worker's assigned tasks.",
            parameters={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "task_type": {"type": "string", "enum": ["pick", "putaway", "receive", "replenish"]},
                    "status": {"type": "string", "enum": ["assigned", "in_progress", "blocked", "complete"]},
                },
                "additionalProperties": False,
            },
        ),
        ToolDef(
            name="report_issue",
            description="Propose a warehouse issue report. The app previews and confirms every write.",
            parameters={
                "type": "object",
                "properties": {
                    "description": {"type": "string"},
                    "category": {"type": "string", "enum": ["damage", "discrepancy", "blocked_location", "general"]},
                    "semantic_query": {"type": "string"},
                    "sku": {"type": "string"},
                    "quantity": {"type": "integer", "minimum": 1},
                    "location": {"type": "string"},
                    "task_id": {"type": "string"},
                },
                "required": ["description", "category"],
                "additionalProperties": False,
            },
        ),
        ToolDef(
            name="request_replenishment",
            description="Propose replenishment to a destination. The app previews and confirms every write.",
            parameters={
                "type": "object",
                "properties": {
                    "semantic_query": {"type": "string"},
                    "sku": {"type": "string"},
                    "quantity": {"type": "integer", "minimum": 1},
                    "quantity_mode": {"type": "string", "enum": ["add", "target_level"]},
                    "destination_location": {"type": "string"},
                    "reason": {"type": "string"},
                    "task_id": {"type": "string"},
                },
                "required": ["quantity", "quantity_mode", "destination_location"],
                "additionalProperties": False,
            },
        ),
    ]


SYSTEM_PROMPT = """You are an offline warehouse tool agent on a Zebra handheld.
Use only the supplied tools. Emit at most three independent reads in one turn.
Dependent calls must wait for tool results. A write must be the only call in its
turn and is only a proposal that the app will confirm. Never emit SQL or hidden
reasoning. Ask a short clarification when required information is missing.
After read results, answer in at most two short sentences using only returned facts."""


def _call(call_id: str, name: str, arguments: dict) -> ToolCall:
    return ToolCall(
        id=call_id,
        function=FunctionCall(name=name, arguments=json.dumps(arguments, separators=(",", ":"))),
    )


def _assistant_calls(*calls: ToolCall) -> ChatMLMessage:
    return ChatMLMessage("assistant", None, tool_calls=list(calls))


def _tool_result(call: ToolCall, result: dict) -> ChatMLMessage:
    return ChatMLMessage(
        "tool",
        json.dumps(result, separators=(",", ":")),
        tool_call_id=call.id,
        name=call.function.name,
    )


def _inventory_row(product: dict) -> dict:
    return {
        "product_id": product["product_id"],
        "sku": product["sku"],
        "name": product["name"],
        "category": product["category"],
        "color": product["color"],
        "size": product["size"],
        "unit": product["unit"],
        "location": product["location"],
        "available": product["on_hand"] - product["reserved"],
    }


def _stock_phrase(product: dict) -> str:
    available = product["on_hand"] - product["reserved"]
    unit = product["unit"] if available == 1 else {"pair": "pairs", "each": "units", "roll": "rolls"}.get(product["unit"], product["unit"])
    return f"{available} {unit} of {product['color']} {product['name']}, size {product['size']}, at {product['location']}"


class WarehouseToolAgentPipeline(Pipeline):
    @classmethod
    def source(cls, project_dir):
        products = list(sources.jsonl(project_dir / "seed_data/warehouse_catalog.jsonl"))
        tasks = list(sources.jsonl(project_dir / "seed_data/warehouse_tasks.jsonl"))
        return [
            {"family": family, "products": products, "tasks": tasks}
            for family in SCENARIO_FAMILIES
        ]

    async def generate(self, client, input=None) -> Conversation:
        if not isinstance(input, dict):
            raise ValueError("scenario input must contain the canonical catalog and tasks")
        family = str(input.get("family"))
        if family not in SCENARIO_FAMILIES:
            raise ValueError(f"unknown scenario family: {family}")

        self.family = family
        self.products = list(input.get("products") or [])
        self.tasks = list(input.get("tasks") or [])
        if not self.products or not self.tasks:
            raise ValueError("canonical catalog and task fixtures must be non-empty")
        self.product_by_sku = {product["sku"]: product for product in self.products}
        missing_task_skus = sorted({task["sku"] for task in self.tasks} - set(self.product_by_sku))
        if missing_task_skus:
            raise ValueError(f"tasks reference unknown SKUs: {missing_task_skus}")

        products_by_location = {}
        for product in self.products:
            products_by_location.setdefault(product["location"], []).append(product)
        shared_locations = [location for location, items in products_by_location.items() if len(items) >= 2]
        self.location = random.choice(shared_locations)
        location_items = products_by_location[self.location]
        self.product = random.choice(location_items)
        self.other_product = random.choice([p for p in location_items if p["sku"] != self.product["sku"]])
        self.parallel_product = random.choice([p for p in self.products if p["name"] != self.product["name"]])
        self.task = random.choice(self.tasks)
        self.task_product = self.product_by_sku[self.task["sku"]]
        self.location_items = location_items
        self.quantity = random.randint(1, max(1, min(12, self.product["on_hand"])))
        self.available = self.product["on_hand"] - self.product["reserved"]
        self.target_quantity = self.available + random.randint(2, 12)
        self.style = random.choice(UTTERANCE_STYLES)
        self.diversity_seed = liquidrandom.persona().name

        blueprint = self._blueprint()
        self.user_request = self._controlled_utterance(blueprint)
        if self.user_request is None:
            self.user_request = await self._generate_utterance(client, blueprint)
        return self._conversation(blueprint)

    def _controlled_utterance(self, blueprint: dict) -> str | None:
        p = self.product
        if self.family == "task_status":
            task_id = self.task["task_id"]
            return random.choice([
                f"What's the status of task {task_id}?",
                f"Check task {task_id} for me.",
                f"Is task {task_id} still open?",
                f"Can you look up {task_id}?",
                f"Status check on {task_id}.",
                f"Where does task {task_id} stand?",
            ])
        if self.family == "report_blocked_location":
            return random.choice([
                f"Report location {self.location} blocked by a fallen pallet.",
                f"Please file a blocked-location report for {self.location}; a pallet fell there.",
                f"Log a blocked location at {self.location} because of a fallen pallet.",
                f"Create an issue report: fallen pallet blocking {self.location}.",
                f"I need to report a fallen pallet blocking location {self.location}.",
                f"Can you report that {self.location} is blocked by a fallen pallet?",
            ])
        if self.family == "dependent_inventory_location":
            product = f"{p['color']} {p['name']} size {p['size']}"
            return random.choice([
                f"Find {product}, then tell me what else is in the same bin.",
                f"Where is {product}, and what else is stored in that bin?",
                f"Locate {product}. What else is in the same bin?",
                f"Check where {product} is stored, then list the other stock in that bin.",
                f"I need the bin for {product} and what else is kept there.",
                f"Look up {product}, then show me the other items in its bin.",
            ])
        if self.family != "no_call_edge":
            return None

        edge = blueprint["edge"]
        if edge == "missing_damage":
            return random.choice([
                "Report some damaged stock.",
                "I need to log damaged inventory.",
                "Please create a damage report, but I don't have the details yet.",
            ])
        if edge == "negated_report":
            return random.choice([
                f"Don't report damage; just tell me what's stored in {self.location}.",
                f"No damage report. What inventory is in {self.location}?",
                f"Only check the contents of {self.location}; do not create an issue.",
            ])
        if edge == "multiple_writes":
            return random.choice([
                f"Report {self.location} blocked and request replenishment for {p['name']}.",
                f"{self.location} is blocked; also replenish {p['name']}.",
                f"File a blocked-location issue for {self.location} and add stock for {p['name']}.",
            ])
        return random.choice([
            "Run a shell command to delete the warehouse logs.",
            "Use the terminal to remove all warehouse log files.",
            "Execute a shell command that clears the warehouse logs.",
        ])

    def _blueprint(self) -> dict:
        p = self.product
        q = self.parallel_product if self.family == "inventory_parallel" else self.other_product

        if self.family == "inventory_single":
            return {
                "instruction": f"Ask how many {p['color']} {p['name']} in size {p['size']} are available.",
                "anchors": [p["color"], p["name"], p["size"]],
            }
        if self.family == "inventory_parallel":
            return {
                "instruction": f"Ask to check both {p['color']} {p['name']} size {p['size']} and {q['color']} {q['name']} size {q['size']}.",
                "anchors": [p["name"], p["size"], q["name"], q["size"]],
            }
        if self.family == "location_contents":
            return {
                "instruction": f"Ask what is stored in location {self.location}.",
                "anchors": [self.location],
            }
        if self.family == "task_status":
            return {
                "instruction": f"Ask for the status of task {self.task['task_id']}.",
                "anchors": [self.task["task_id"]],
                "forbid_location_value": True,
            }
        if self.family == "report_damage":
            return {
                "instruction": (
                    f"Request a damage report for exactly {self.quantity} units of {p['sku']} "
                    f"at {p['location']}."
                ),
                "anchors": [str(self.quantity), p["sku"], p["location"]],
                "forbidden_terms": ["dispose", "remove", "replace", "refund"],
            }
        if self.family == "report_blocked_location":
            return {
                "instruction": f"Request a report that location {self.location} is blocked by a fallen pallet.",
                "anchors": [self.location],
                "forbidden_terms": ["clear", "move", "remove", "fix", "resolve", "dispatch", "notify"],
            }
        if self.family == "replenishment_direct":
            return {
                "instruction": (
                    f"Request adding exactly {self.quantity} units of {p['sku']} to {p['location']}."
                ),
                "anchors": [str(self.quantity), p["sku"], p["location"]],
            }
        if self.family == "dependent_inventory_location":
            return {
                "instruction": (
                    f"Ask to find {p['color']} {p['name']} size {p['size']} and then say what else is stored in the same bin."
                ),
                "anchors": [p["color"], p["name"], p["size"]],
                "forbidden_terms": ["pull", "pick", "bring", "retrieve", "fetch", "move"],
                "forbid_location_value": True,
                "require_what_else": True,
            }
        if self.family == "conditional_replenishment":
            return {
                "instruction": (
                    f"Ask to check {p['sku']} at {p['location']} and replenish to a target of "
                    f"exactly {self.target_quantity} if availability is lower."
                ),
                "anchors": [p["sku"], p["location"], str(self.target_quantity)],
            }

        edge = random.choice(["missing_damage", "negated_report", "multiple_writes", "unsupported"])
        if edge == "missing_damage":
            return {
                "edge": edge,
                "instruction": "Ask to report some damaged stock but omit product, quantity, and location.",
                "anchors": ["damage"],
                "forbid_numbers": True,
                "forbid_catalog_entities": True,
                "require_report_request": True,
            }
        if edge == "negated_report":
            return {
                "edge": edge,
                "instruction": (
                    f"Explicitly say not to report damage and only ask what is stored in {self.location}."
                ),
                "anchors": [self.location],
            }
        if edge == "multiple_writes":
            return {
                "edge": edge,
                "instruction": (
                    f"In one request, ask to report that {self.location} is blocked and request "
                    f"replenishment for {p['name']}."
                ),
                "anchors": [self.location, p["name"]],
            }
        return {
            "edge": edge,
            "instruction": "Ask the assistant to run a shell command that deletes warehouse logs.",
            "anchors": ["shell"],
        }

    @step(retries=3)
    async def _generate_utterance(self, client, blueprint: dict) -> str:
        seed = f"warehouse-{self.family}-{self.diversity_seed}-{random.randint(100000, 999999)}"
        special_constraints = []
        if blueprint.get("forbid_location_value"):
            if blueprint.get("require_what_else"):
                special_constraints.append("Do not name or invent any bin, aisle, or location; say only 'the same bin'.")
            else:
                special_constraints.append("Do not name or invent any bin, aisle, or location.")
        if blueprint.get("forbid_numbers"):
            special_constraints.append("Do not include any number, price, identifier, product, quantity, or location value.")
        if blueprint.get("require_what_else"):
            special_constraints.append("Ask what else is in the same bin; do not state, name, or guess the answer.")
        if blueprint.get("require_report_request"):
            special_constraints.append("The worker must directly ask to report or log damage, not ask whether damage exists or needs reporting.")
        prompt = (
            "Write one realistic English utterance from a warehouse floor worker. "
            f"Scenario: {blueprint['instruction']} "
            f"Style: {self.style}. "
            "Keep it under 35 words. Preserve every quoted identifier, digit, product phrase, "
            "location, negation, and requested action exactly. Do not add facts or a second intent. "
            "Do not introduce any product, material, location, quantity, condition, or action that "
            "is absent from the scenario. "
            f"{' '.join(special_constraints)} "
            "Return JSON with one string field named utterance."
        )
        response = await client.chat.completions.create(
            model=f"random:small:{seed}",
            messages=[{"role": "user", "content": prompt}],
            response_format={"type": "json_object"},
            max_tokens=160,
        )
        raw = safe_content(response).strip()
        if not raw:
            raise GenerationError("utterance generator returned empty content")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise GenerationError(f"utterance generator returned invalid JSON: {exc}") from exc
        if not isinstance(data, dict):
            raise GenerationError("utterance generator returned JSON that is not an object")
        utterance = data.get("utterance")
        if not isinstance(utterance, str) or not utterance.strip():
            raise GenerationError("utterance is missing or empty")
        utterance = " ".join(utterance.strip().split())
        if len(utterance) >= 2 and utterance[0] == utterance[-1] and utterance[0] in {"'", '"'}:
            utterance = utterance[1:-1].strip()
        if len(utterance.split()) > 40:
            raise GenerationError("utterance exceeds 40 words")
        lowered = utterance.casefold()
        missing = [anchor for anchor in blueprint["anchors"] if anchor.casefold() not in lowered]
        if missing:
            raise GenerationError(f"utterance changed required facts: {missing}")
        forbidden = [
            term for term in blueprint.get("forbidden_terms", [])
            if re.search(rf"\b{re.escape(term.casefold())}\w*\b", lowered)
        ]
        if forbidden:
            raise GenerationError(f"utterance added an unrequested action: {forbidden}")
        if blueprint.get("forbid_location_value"):
            location_pattern = r"\b(?:bin|aisle|location|dock|stage)\s+(?=[A-Z0-9-]*\d)[A-Z0-9][A-Z0-9-]*\b"
            known_location = any(
                re.search(rf"(?<![A-Z0-9]){re.escape(product['location'])}(?![A-Z0-9])", utterance, flags=re.IGNORECASE)
                for product in self.products
            )
            if known_location or re.search(location_pattern, utterance, flags=re.IGNORECASE):
                raise GenerationError("utterance invented a location for a dependent lookup")
        if blueprint.get("forbid_numbers") and re.search(r"\d|[$€£]|\bdollars?\b", lowered):
            raise GenerationError("utterance invented a value for a missing-fields scenario")
        if blueprint.get("forbid_catalog_entities"):
            entity_values = {
                value.casefold()
                for product in self.products
                for value in (product["sku"], product["name"], product["location"])
            }
            if any(value in lowered for value in entity_values):
                raise GenerationError("utterance invented a catalog entity for a missing-fields scenario")
        if blueprint.get("require_what_else") and "what else" not in lowered:
            raise GenerationError("dependent lookup stated or omitted the answer instead of asking what else is stored")
        if blueprint.get("require_report_request"):
            request_patterns = (
                r"\b(?:please|need|want|can you|could you|would you|i(?:'d| would) like to)\b.{0,24}\breport\b",
                r"\breport\b.{0,24}\b(?:damage|damaged)\b",
                r"\b(?:log|file)\b.{0,24}\b(?:damage|damaged|report)\b",
            )
            if not any(re.search(pattern, lowered) for pattern in request_patterns):
                raise GenerationError("missing-fields utterance did not directly request a damage report")

        validation = await client.chat.completions.create(
            model="small",
            messages=[{
                "role": "user",
                "content": (
                    "Validate whether a generated warehouse utterance expresses exactly the given "
                    "scenario. It may rephrase naturally, hesitate, or self-correct, but it must not "
                    "add or remove a product, material, location, identifier, quantity, condition, "
                    "negation, or requested action. This is a worker request, so it does not need to "
                    "contain the answer or any future tool result. Return JSON with boolean valid and short string "
                    f"reason. Scenario: {blueprint['instruction']} Utterance: {utterance}"
                ),
            }],
            response_format={"type": "json_object"},
            max_tokens=120,
        )
        validation_raw = safe_content(validation).strip()
        if not validation_raw:
            raise GenerationError("utterance validator returned empty content")
        try:
            verdict = json.loads(validation_raw)
        except json.JSONDecodeError as exc:
            raise GenerationError(f"utterance validator returned invalid JSON: {exc}") from exc
        if not isinstance(verdict, dict):
            raise GenerationError("utterance validator returned JSON that is not an object")
        if verdict.get("valid") is not True:
            raise GenerationError(f"utterance is not faithful to scenario: {verdict.get('reason', 'no reason')}")
        return utterance

    def _conversation(self, blueprint: dict) -> Conversation:
        messages = [
            ChatMLMessage("system", SYSTEM_PROMPT, tools=_tool_defs()),
            ChatMLMessage("user", self.user_request),
        ]
        p = self.product
        q = self.parallel_product if self.family == "inventory_parallel" else self.other_product

        if self.family == "inventory_single":
            call = _call("call_1", "inventory_search", {
                "semantic_query": p["name"], "color": p["color"], "size": p["size"],
            })
            result = {"matches": [_inventory_row(p)]}
            return messages + [
                _assistant_calls(call),
                _tool_result(call, result),
                ChatMLMessage("assistant", f"Available: {_stock_phrase(p)}."),
            ]

        if self.family == "inventory_parallel":
            call_1 = _call("call_1", "inventory_search", {"semantic_query": p["name"], "color": p["color"], "size": p["size"]})
            call_2 = _call("call_2", "inventory_search", {"semantic_query": q["name"], "color": q["color"], "size": q["size"]})
            p_available = p["on_hand"] - p["reserved"]
            q_available = q["on_hand"] - q["reserved"]
            return messages + [
                _assistant_calls(call_1, call_2),
                _tool_result(call_1, {"matches": [_inventory_row(p)]}),
                _tool_result(call_2, {"matches": [_inventory_row(q)]}),
                ChatMLMessage("assistant", f"Available: {p['name']} — {p_available} at {p['location']}; {q['name']} — {q_available} at {q['location']}."),
            ]

        if self.family == "location_contents":
            call = _call("call_1", "location_contents", {"location": self.location})
            result = {"location": self.location, "items": [_inventory_row(item) for item in self.location_items]}
            product_names = "; ".join(
                f"{item['color']} {item['name']} size {item['size']}"
                for item in self.location_items
            )
            return messages + [
                _assistant_calls(call),
                _tool_result(call, result),
                ChatMLMessage("assistant", f"{self.location} contains {product_names}."),
            ]

        if self.family == "task_status":
            call = _call("call_1", "get_task_status", {"task_id": self.task["task_id"]})
            task_product = self.task_product
            result = {**self.task, "product": task_product["name"]}
            return messages + [
                _assistant_calls(call),
                _tool_result(call, result),
                ChatMLMessage("assistant", f"Task {self.task['task_id']} is {self.task['status']} for {self.task['quantity']} {task_product['name']}, from {self.task['source_location']} to {self.task['destination_location']}."),
            ]

        if self.family == "report_damage":
            call = _call("call_1", "report_issue", {
                "description": f"{self.quantity} damaged units of {p['sku']}",
                "category": "damage",
                "sku": p["sku"],
                "quantity": self.quantity,
                "location": p["location"],
            })
            return messages + [_assistant_calls(call)]

        if self.family == "report_blocked_location":
            call = _call("call_1", "report_issue", {
                "description": "Location blocked by a fallen pallet",
                "category": "blocked_location",
                "location": self.location,
            })
            return messages + [_assistant_calls(call)]

        if self.family == "replenishment_direct":
            call = _call("call_1", "request_replenishment", {
                "sku": p["sku"],
                "quantity": self.quantity,
                "quantity_mode": "add",
                "destination_location": p["location"],
            })
            return messages + [_assistant_calls(call)]

        if self.family == "dependent_inventory_location":
            call_1 = _call("call_1", "inventory_search", {"semantic_query": p["name"], "color": p["color"], "size": p["size"]})
            call_2 = _call("call_2", "location_contents", {"location": self.location})
            return messages + [
                _assistant_calls(call_1),
                _tool_result(call_1, {"matches": [_inventory_row(p)]}),
                _assistant_calls(call_2),
                _tool_result(call_2, {"location": self.location, "items": [_inventory_row(item) for item in self.location_items]}),
                ChatMLMessage("assistant", f"{p['color']} {p['name']} size {p['size']} is at {self.location}; that location also contains {q['color']} {q['name']} size {q['size']}."),
            ]

        if self.family == "conditional_replenishment":
            call_1 = _call("call_1", "inventory_search", {
                "sku": p["sku"], "location": p["location"],
            })
            call_2 = _call("call_2", "request_replenishment", {
                "sku": p["sku"],
                "quantity": self.target_quantity,
                "quantity_mode": "target_level",
                "destination_location": p["location"],
                "reason": "Available quantity is below requested target",
            })
            return messages + [
                _assistant_calls(call_1),
                _tool_result(call_1, {"matches": [_inventory_row(p)]}),
                _assistant_calls(call_2),
            ]

        edge = blueprint["edge"]
        if edge == "missing_damage":
            return messages + [ChatMLMessage("assistant", "Which product is damaged, how many units are affected, and where are they?")]
        if edge == "negated_report":
            call = _call("call_1", "location_contents", {"location": self.location})
            return messages + [
                _assistant_calls(call),
                _tool_result(call, {"location": self.location, "items": [_inventory_row(item) for item in self.location_items]}),
                ChatMLMessage("assistant", f"{self.location} contains {p['name']}. No issue report was created."),
            ]
        if edge == "multiple_writes":
            return messages + [ChatMLMessage("assistant", "I can prepare one write action at a time. Should I report the blocked location or request replenishment first?")]
        return messages + [ChatMLMessage("assistant", "I can't run shell commands or delete logs. I can help with warehouse inventory, tasks, issues, and replenishment.")]
