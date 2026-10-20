"""Strict syntax checks, plus correction rules not expressible in the schema."""

import json
import os
from pathlib import Path

from scripts.validate_tool_contracts import parse, validate
from .native_tools import START, parse_marked_calls, render_marked_calls

ROOT = Path(os.environ.get("ZEBRA_HARNESS_ROOT", Path(__file__).resolve().parents[1]))
SCHEMA = parse((ROOT / "contracts/model_output.schema.json").read_text())
READ_TOOLS = {"inventory_lookup", "task_lookup"}
WRITE_TOOLS = {"report_general_concern", "report_product_damage", "request_replenishment"}


def decode(raw):
    if isinstance(raw, str) and raw.lstrip().startswith(START):
        envelope = parse_marked_calls(raw)
    else:
        envelope = parse(raw) if isinstance(raw, str) else parse(json.dumps(raw))
    validate(envelope, SCHEMA, SCHEMA)
    if envelope["action"] == "correct":
        if len(envelope["calls"]) != 1:
            raise ValueError("A correction must target one focused request")
        call = envelope["calls"][0]
        if not call["arguments"] and not call.get("clear_fields"):
            raise ValueError("Empty correction")
        for field in call.get("clear_fields", []):
            head, _, tail = field.partition(".")
            supplied = call["arguments"].get(head)
            if head in call["arguments"] and (not tail or tail in supplied):
                raise ValueError("A correction cannot set and clear the same field")
    return envelope


def encode_proposal(envelope):
    """Render a validated proposal; never infer a workflow action from a tool call."""
    validated = decode(envelope)
    if validated["action"] != "propose":
        raise ValueError("Only propose envelopes have a marked-call representation")
    return render_marked_calls(validated["calls"])
