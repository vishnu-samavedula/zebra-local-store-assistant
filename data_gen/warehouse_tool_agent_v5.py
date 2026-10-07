"""LQH v0.23 pipeline for the balanced, schema-free P1B v5 corpus.

The scenario file owns labels and expected calls. The generator model may only
rephrase the worker utterance; it cannot author the ground truth.
"""

from __future__ import annotations

import json

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
import lqh.sources as sources


PARAMS = {
    "revision": 4,
    "scenario_count": 600,
    "prompt": "warehouse_tool_agent_compact_v2",
    "schemas_in_training_messages": False,
}


def _tools() -> list[ToolDef]:
    string = {"type": "string"}
    integer = {"type": "integer", "minimum": 1}
    return [
        ToolDef("inventory_search", "Search local inventory.", {"type": "object", "properties": {"semantic_query": string, "sku": string, "color": string, "size": string, "location": string, "minimum_quantity": integer}, "additionalProperties": False}),
        ToolDef("location_contents", "List or filter inventory at a warehouse location.", {"type": "object", "properties": {"location": string, "semantic_query": string}, "required": ["location"], "additionalProperties": False}),
        ToolDef("get_task_status", "Retrieve or filter warehouse tasks.", {"type": "object", "properties": {"task_id": string, "task_type": {"type": "string", "enum": ["pick", "putaway", "receive", "replenish"]}, "status": {"type": "string", "enum": ["assigned", "in_progress", "blocked", "complete"]}}, "additionalProperties": False}),
        ToolDef("report_issue", "Propose a warehouse issue report.", {"type": "object", "properties": {"description": string, "category": {"type": "string", "enum": ["damage", "discrepancy", "blocked_location", "general"]}, "semantic_query": string, "sku": string, "quantity": integer, "location": string, "task_id": string}, "required": ["description", "category"], "additionalProperties": False}),
        ToolDef("request_replenishment", "Propose replenishment to a destination.", {"type": "object", "properties": {"semantic_query": string, "sku": string, "quantity": integer, "quantity_mode": {"type": "string", "enum": ["add", "target_level"]}, "destination_location": string, "reason": string, "task_id": string}, "required": ["quantity", "quantity_mode", "destination_location"], "additionalProperties": False}),
    ]


class WarehouseToolAgentV5(Pipeline):
    @classmethod
    def source(cls, project_dir):
        return sources.jsonl(project_dir / "seed_data/p1b_v5_scenarios.jsonl")

    async def generate(self, client, input=None) -> Conversation:
        if not isinstance(input, dict):
            raise ValueError("expected a scenario blueprint")
        self.blueprint = input
        try:
            self.user_request = await self._rephrase(client)
            await self._validate_meaning(client)
        except GenerationError:
            # Ground-truth seeds are human-authored and unique. A generator or
            # judge failure must not drop a required coverage cell.
            self.user_request = self.blueprint["user_seed"]
        return self._conversation()

    @step(retries=4)
    async def _rephrase(self, client) -> str:
        style = self.blueprint["language_variant"]
        style_instruction = {
            "clean": "Use a concise natural warehouse request.",
            "fragment": "Use a short field-worker fragment, while keeping the intent unambiguous.",
            "correction": "Use a brief hesitation or self-correction without changing any operational fact.",
            "asr_like": "Use plausible speech-transcript wording with light punctuation loss; preserve identifiers and numbers exactly.",
        }[style]
        anchors = self.blueprint.get("anchors") or []
        response = await client.chat.completions.create(
            model=f"random:small:{self.blueprint['sample_id']}",
            messages=[{
                "role": "user",
                "content": (
                    "Rewrite the warehouse worker request below. Preserve its exact intent, negation, "
                    "requested actions, corrections, quantities, identifiers and locations. Do not add "
                    "facts or actions. Keep it under 40 words. " + style_instruction + " "
                    f"Required literal strings: {json.dumps(anchors)}. "
                    f"Request: {self.blueprint['user_seed']} "
                    'Return JSON only: {"utterance":"..."}.'
                ),
            }],
            response_format={"type": "json_object"},
            max_tokens=100,
        )
        raw = safe_content(response).strip()
        if not raw:
            raise GenerationError("empty rephrase")
        try:
            utterance = json.loads(raw)["utterance"].strip()
        except (json.JSONDecodeError, KeyError, AttributeError, TypeError) as exc:
            raise GenerationError(f"invalid rephrase JSON: {exc}") from exc
        if not utterance or len(utterance.split()) > 40:
            raise GenerationError("rephrase is empty or exceeds 40 words")
        lowered = utterance.casefold()
        missing = [value for value in anchors if str(value).casefold() not in lowered]
        if missing:
            raise GenerationError(f"rephrase dropped required strings: {missing}")
        return " ".join(utterance.split())

    @step(retries=3)
    async def _validate_meaning(self, client) -> None:
        response = await client.chat.completions.create(
            model="small",
            messages=[{
                "role": "user",
                "content": (
                    "Determine whether the candidate expresses exactly the same warehouse request as "
                    "the source. It must preserve negation, historical/resolved status, ambiguity, "
                    "missing information, every correction, identifier, quantity, location and action. "
                    "It must not add a fact or make an incomplete request complete. "
                    f"Source: {self.blueprint['user_seed']} Candidate: {self.user_request} "
                    'Return JSON only: {"valid":true|false,"reason":"..."}.'
                ),
            }],
            response_format={"type": "json_object"},
            max_tokens=100,
        )
        raw = safe_content(response).strip()
        try:
            verdict = json.loads(raw)
        except (json.JSONDecodeError, TypeError) as exc:
            raise GenerationError(f"invalid validation JSON: {exc}") from exc
        if not isinstance(verdict, dict):
            raise GenerationError("validation JSON is not an object")
        if verdict.get("valid") is not True:
            raise GenerationError(f"meaning changed: {verdict.get('reason', 'unspecified')}")

    def _conversation(self) -> Conversation:
        messages = [
            ChatMLMessage("system", self.blueprint["system_prompt"], tools=_tools()),
            ChatMLMessage("user", self.user_request),
        ]
        for turn in self.blueprint["turns"]:
            calls = None
            if turn.get("tool_calls"):
                calls = [
                    ToolCall(
                        id=call["id"],
                        function=FunctionCall(
                            name=call["function"]["name"],
                            arguments=call["function"]["arguments"],
                        ),
                    )
                    for call in turn["tool_calls"]
                ]
            messages.append(ChatMLMessage(
                role=turn["role"],
                content=turn.get("content"),
                tool_calls=calls,
                tool_call_id=turn.get("tool_call_id"),
                name=turn.get("name"),
            ))
        return messages
