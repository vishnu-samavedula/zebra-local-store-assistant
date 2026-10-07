#!/usr/bin/env python3
"""Deterministic release gate for the native-template warehouse corpus."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

import pyarrow.parquet as pq
from transformers import AutoTokenizer


BASE = "LiquidAI/LFM2.5-350M"
REVISION = "9e6c6ccf47cd318696e137d381a7ded8fe4df09f"
WRITE_TOOLS = {"report_issue", "request_replenishment"}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    prompt = (root / "contracts/warehouse_tool_agent_native_v1.md").read_text().strip()
    tools = json.loads((root / "contracts/warehouse_tools_v1.json").read_text())
    tools_by_name = {item["function"]["name"]: item["function"]["parameters"] for item in tools}
    rows = pq.read_table(args.dataset).to_pylist()
    tokenizer = AutoTokenizer.from_pretrained(BASE, revision=REVISION, local_files_only=True)
    errors: list[str] = []
    normalized_users: list[str] = []
    lengths: list[int] = []
    tool_counts = Counter()

    for index, row in enumerate(rows):
        label = row.get("sample_id") or f"row {index}"
        messages = json.loads(row["messages"])
        row_tools = json.loads(row["tools"])
        if row_tools != tools:
            errors.append(f"{label}: tool contract differs from canonical JSON")
        systems = [message for message in messages if message.get("role") == "system"]
        if len(systems) != 1 or systems[0].get("content") != prompt:
            errors.append(f"{label}: production system prompt mismatch")
        if any("tools" in message for message in messages):
            errors.append(f"{label}: tools must be top-level, not nested in a message")
        users = [message.get("content", "") for message in messages if message.get("role") == "user"]
        if not users:
            errors.append(f"{label}: missing worker turn")
            continue
        normalized_users.append(re.sub(r"[^a-z0-9]+", " ", users[0].casefold()).strip())

        for message in messages:
            calls = message.get("tool_calls") or []
            names = [call.get("function", {}).get("name") for call in calls]
            if len(calls) > 3:
                errors.append(f"{label}: more than three calls in one block")
            if any(name in WRITE_TOOLS for name in names) and len(calls) != 1:
                errors.append(f"{label}: write is not isolated")
            for call in calls:
                function = call.get("function") or {}
                name = function.get("name")
                arguments = function.get("arguments")
                tool_counts[name] += 1
                if name not in tools_by_name:
                    errors.append(f"{label}: unknown tool {name}")
                    continue
                if not isinstance(arguments, dict):
                    errors.append(f"{label}: arguments are not a mapping")
                    continue
                allowed = set(tools_by_name[name]["properties"])
                if set(arguments) - allowed:
                    errors.append(f"{label}: unknown {name} arguments {sorted(set(arguments) - allowed)}")
                if any(value is None or value == "" for value in arguments.values()):
                    errors.append(f"{label}: empty optional argument")
                if name == "location_contents" and not arguments.get("location"):
                    errors.append(f"{label}: location_contents missing location")
                if name == "request_replenishment":
                    if not (arguments.get("sku") or arguments.get("semantic_query")):
                        errors.append(f"{label}: replenishment missing product identity")
                    if not isinstance(arguments.get("quantity"), int) or arguments["quantity"] <= 0:
                        errors.append(f"{label}: replenishment has invalid quantity")
                    if arguments.get("quantity_mode") not in {"add", "target_level"}:
                        errors.append(f"{label}: replenishment has invalid quantity_mode")
                    if not arguments.get("destination_location"):
                        errors.append(f"{label}: replenishment missing destination")
                    if "reason" in arguments:
                        errors.append(f"{label}: model target contains derived reason")
                if name == "report_issue":
                    category = arguments.get("category")
                    if not arguments.get("description") or category not in {"damage", "discrepancy", "blocked_location", "general"}:
                        errors.append(f"{label}: invalid issue core arguments")
                    if not arguments.get("location"):
                        errors.append(f"{label}: issue missing location")
                    if category in {"damage", "discrepancy"}:
                        if not (arguments.get("sku") or arguments.get("semantic_query")):
                            errors.append(f"{label}: product issue missing identity")
                        if not isinstance(arguments.get("quantity"), int) or arguments["quantity"] <= 0:
                            errors.append(f"{label}: product issue missing quantity")

        encoded = tokenizer.apply_chat_template(messages, tools=row_tools, tokenize=True, return_dict=True)
        length = len(encoded["input_ids"])
        lengths.append(length)
        if length > 2048:
            errors.append(f"{label}: rendered row is {length} tokens (>2048)")

    if len(normalized_users) != len(set(normalized_users)):
        errors.append("duplicate normalized worker prompts")
    summary = {
        "dataset": str(args.dataset),
        "rows": len(rows),
        "passed": not errors,
        "split_counts": Counter(row.get("split") for row in rows),
        "bucket_counts": Counter(
            f"{row.get('split')} / {row.get('scenario_bucket')}" for row in rows
        ),
        "tool_call_counts": tool_counts,
        "token_length": {
            "min": min(lengths, default=0),
            "median": sorted(lengths)[len(lengths) // 2] if lengths else 0,
            "max": max(lengths, default=0),
        },
        "errors": errors,
    }
    print(json.dumps(summary, indent=2))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
