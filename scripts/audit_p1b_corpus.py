#!/usr/bin/env python3
"""Fail closed when a P1B v5 corpus is unbalanced or violates tool safety rules."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import pyarrow.parquet as pq


TOOLS = {
    "inventory_search",
    "location_contents",
    "get_task_status",
    "report_issue",
    "request_replenishment",
}
READ_TOOLS = {"inventory_search", "location_contents", "get_task_status"}
WRITE_TOOLS = {"report_issue", "request_replenishment"}
BUCKET_QUOTAS = {
    "train": {
        "single_tool": 250,
        "multi_read": 50,
        "clarification": 75,
        "rejection": 50,
        "correction": 40,
        "dependent_trace": 35,
    },
    "eval": {
        "single_tool": 50,
        "multi_read": 10,
        "clarification": 15,
        "rejection": 10,
        "correction": 5,
        "dependent_trace": 10,
    },
}
REQUIRED_COLUMNS = {
    "messages",
    "sample_id",
    "split",
    "scenario_bucket",
    "scenario_family",
    "route",
    "primary_tool",
    "argument_shape",
    "call_count",
    "issue_category",
    "quantity_mode",
    "language_variant",
}


def load_messages(raw: object) -> list[dict]:
    value = json.loads(raw) if isinstance(raw, str) else raw
    if not isinstance(value, list):
        raise ValueError("messages must be a JSON list")
    return value


def arguments(tool_call: dict) -> dict:
    raw = tool_call.get("function", {}).get("arguments", {})
    value = json.loads(raw) if isinstance(raw, str) else raw
    return value if isinstance(value, dict) else {}


def normalized(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.casefold()).strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path, help="data.parquet or its containing directory")
    parser.add_argument(
        "--prompt",
        type=Path,
        default=Path("prompts/warehouse_tool_agent_compact_v2.md"),
    )
    parser.add_argument("--catalog", type=Path, default=Path("seed_data/warehouse_catalog.jsonl"))
    parser.add_argument("--tasks", type=Path, default=Path("seed_data/warehouse_tasks.jsonl"))
    args = parser.parse_args()

    path = args.dataset / "data.parquet" if args.dataset.is_dir() else args.dataset
    table = pq.read_table(path)
    errors: list[str] = []
    missing_columns = sorted(REQUIRED_COLUMNS - set(table.column_names))
    if missing_columns:
        print(json.dumps({
            "dataset": str(path),
            "rows": len(table),
            "passed": False,
            "errors": [f"missing required columns: {', '.join(missing_columns)}"],
        }, indent=2))
        return 1

    prompt = args.prompt.read_text(encoding="utf-8").strip()
    catalog = [json.loads(line) for line in args.catalog.read_text().splitlines() if line.strip()]
    tasks = [json.loads(line) for line in args.tasks.read_text().splitlines() if line.strip()]
    known_skus = {row["sku"] for row in catalog}
    known_locations = {
        str(row[key])
        for row in catalog + tasks
        for key in ("location", "source_location", "destination_location")
        if row.get(key)
    }
    known_task_ids = {row["task_id"] for row in tasks}

    bucket_counts: Counter[tuple[str, str]] = Counter()
    single_tools: Counter[str] = Counter()
    single_tools_by_split: Counter[tuple[str, str]] = Counter()
    issue_categories: Counter[str] = Counter()
    quantity_modes: Counter[str] = Counter()
    variants: Counter[str] = Counter()
    argument_fields: Counter[tuple[str, str]] = Counter()
    sample_ids: set[str] = set()
    utterances: set[str] = set()

    for index, record in enumerate(table.to_pylist()):
        label = f"row {index} ({record.get('sample_id')})"
        sample_id = str(record["sample_id"])
        if sample_id in sample_ids:
            errors.append(f"{label}: duplicate sample_id")
        sample_ids.add(sample_id)
        split = str(record["split"])
        bucket = str(record["scenario_bucket"])
        bucket_counts[(split, bucket)] += 1
        variants[str(record["language_variant"])] += 1
        if split not in BUCKET_QUOTAS or bucket not in BUCKET_QUOTAS.get(split, {}):
            errors.append(f"{label}: invalid split/bucket {split}/{bucket}")

        try:
            messages = load_messages(record["messages"])
        except (ValueError, json.JSONDecodeError) as exc:
            errors.append(f"{label}: {exc}")
            continue
        systems = [m for m in messages if m.get("role") == "system"]
        users = [m for m in messages if m.get("role") == "user"]
        if any(message.get("tools") for message in messages):
            errors.append(f"{label}: embedded tool schema is forbidden")
        if len(systems) != 1 or systems[0].get("content", "").strip() != prompt:
            errors.append(f"{label}: system prompt is not compact_v2 verbatim")
        if len(users) != 1:
            errors.append(f"{label}: expected exactly one worker request")
        else:
            utterance = normalized(str(users[0].get("content", "")))
            if not utterance:
                errors.append(f"{label}: empty worker request")
            elif utterance in utterances:
                errors.append(f"{label}: duplicate normalized worker request")
            utterances.add(utterance)

        all_calls: list[tuple[int, dict]] = []
        for message_index, message in enumerate(messages):
            calls = message.get("tool_calls") or []
            if calls and message.get("role") != "assistant":
                errors.append(f"{label}: non-assistant message contains tool calls")
            names = [call.get("function", {}).get("name") for call in calls]
            if len(calls) > 3:
                errors.append(f"{label}: call block exceeds three calls")
            if any(name in WRITE_TOOLS for name in names) and len(calls) != 1:
                errors.append(f"{label}: write call is not isolated")
            for call in calls:
                all_calls.append((message_index, call))

        metadata_count = int(record["call_count"])
        if metadata_count != len(all_calls):
            errors.append(f"{label}: call_count metadata {metadata_count} != {len(all_calls)}")
        route = str(record["route"])
        expected_route = {
            "clarification": "clarify",
            "rejection": "reject",
        }.get(bucket, "tool")
        if route != expected_route:
            errors.append(f"{label}: route {route} does not match bucket {bucket}")
        if route == "tool" and not all_calls:
            errors.append(f"{label}: tool route has no tool call")
        if route == "reject" and all_calls:
            errors.append(f"{label}: reject route contains a tool call")
        if route == "clarify" and any(
            call.get("function", {}).get("name") not in READ_TOOLS for _, call in all_calls
        ):
            errors.append(f"{label}: clarification may contain reads only")

        names = [call.get("function", {}).get("name") for _, call in all_calls]
        if not all_calls and str(record["primary_tool"]) != "none":
            errors.append(f"{label}: no-call row must use primary_tool=none")
        if all_calls and str(record["primary_tool"]) != names[0]:
            errors.append(f"{label}: primary_tool does not match first call")
        unknown_tools = sorted(set(names) - TOOLS)
        if unknown_tools:
            errors.append(f"{label}: unknown tools {unknown_tools}")
        if bucket == "single_tool":
            if len(all_calls) != 1:
                errors.append(f"{label}: single_tool row must contain exactly one call")
            primary = str(record["primary_tool"])
            single_tools[primary] += 1
            single_tools_by_split[(split, primary)] += 1
            if names and primary != names[0]:
                errors.append(f"{label}: primary_tool does not match call")
        if bucket == "multi_read":
            if len(all_calls) not in {2, 3} or any(name not in READ_TOOLS for name in names):
                errors.append(f"{label}: multi_read must contain two or three reads")

        previous_index = -1
        observed_fields: set[str] = set()
        for message_index, call in all_calls:
            if bucket == "dependent_trace" and previous_index >= 0:
                has_result = message_index > previous_index and any(
                    m.get("role") == "tool" for m in messages[previous_index + 1:message_index]
                )
                if not has_result:
                    errors.append(f"{label}: dependent call occurs before a tool result")
            previous_index = message_index
            name = call.get("function", {}).get("name")
            args_dict = arguments(call)
            for field in args_dict:
                argument_fields[(str(name), field)] += 1
                observed_fields.add(field)
            worker_text = " ".join(str(message.get("content", "")) for message in users).casefold()
            if args_dict.get("sku") and args_dict["sku"] not in known_skus and str(args_dict["sku"]).casefold() not in worker_text:
                errors.append(f"{label}: invented SKU {args_dict['sku']}")
            for field in ("location", "destination_location"):
                if args_dict.get(field) and args_dict[field] not in known_locations and str(args_dict[field]).casefold() not in worker_text:
                    errors.append(f"{label}: invented {field} {args_dict[field]}")
            if args_dict.get("task_id") and args_dict["task_id"] not in known_task_ids and str(args_dict["task_id"]).casefold() not in worker_text:
                errors.append(f"{label}: invented task_id {args_dict['task_id']}")
            if any(value in (None, "") for value in args_dict.values()):
                errors.append(f"{label}: null or empty tool argument")

        observed_shape = ",".join(sorted(observed_fields)) if observed_fields else "none"
        if str(record["argument_shape"]) != observed_shape:
            errors.append(
                f"{label}: argument_shape {record['argument_shape']} != observed {observed_shape}"
            )

        category = str(record["issue_category"])
        mode = str(record["quantity_mode"])
        if bucket == "single_tool" and record["primary_tool"] == "report_issue":
            issue_categories[category] += 1
        if bucket == "single_tool" and record["primary_tool"] == "request_replenishment":
            quantity_modes[mode] += 1

    if len(table) != 600:
        errors.append(f"expected 600 rows, found {len(table)}")
    for split, quotas in BUCKET_QUOTAS.items():
        for bucket, expected in quotas.items():
            actual = bucket_counts[(split, bucket)]
            if actual != expected:
                errors.append(f"{split}/{bucket}: expected {expected}, found {actual}")
    for tool in sorted(TOOLS):
        if single_tools[tool] != 60:
            errors.append(f"single_tool/{tool}: expected 60, found {single_tools[tool]}")
        if single_tools_by_split[("train", tool)] != 50:
            errors.append(
                f"train/single_tool/{tool}: expected 50, found {single_tools_by_split[('train', tool)]}"
            )
        if single_tools_by_split[("eval", tool)] != 10:
            errors.append(
                f"eval/single_tool/{tool}: expected 10, found {single_tools_by_split[('eval', tool)]}"
            )
    for category in ("damage", "discrepancy", "blocked_location", "general"):
        if issue_categories[category] != 15:
            errors.append(f"report_issue/{category}: expected 15, found {issue_categories[category]}")
    for mode in ("add", "target_level"):
        if quantity_modes[mode] != 30:
            errors.append(f"request_replenishment/{mode}: expected 30, found {quantity_modes[mode]}")
    for variant in ("clean", "fragment", "correction", "asr_like"):
        if variants[variant] < 60:
            errors.append(f"language_variant/{variant}: expected at least 60, found {variants[variant]}")
    required_fields = {
        ("inventory_search", "minimum_quantity"),
        ("location_contents", "semantic_query"),
        ("get_task_status", "task_type"),
        ("get_task_status", "status"),
        ("report_issue", "task_id"),
        ("request_replenishment", "semantic_query"),
        ("request_replenishment", "reason"),
        ("request_replenishment", "task_id"),
    }
    for key in sorted(required_fields):
        if argument_fields[key] < 5:
            errors.append(f"argument coverage {key[0]}.{key[1]}: expected at least 5, found {argument_fields[key]}")

    report = {
        "dataset": str(path),
        "rows": len(table),
        "passed": not errors,
        "bucket_counts": {f"{s}/{b}": n for (s, b), n in sorted(bucket_counts.items())},
        "single_tool_counts": dict(sorted(single_tools.items())),
        "single_tool_counts_by_split": {
            f"{split}/{tool}": count
            for (split, tool), count in sorted(single_tools_by_split.items())
        },
        "issue_categories": dict(sorted(issue_categories.items())),
        "quantity_modes": dict(sorted(quantity_modes.items())),
        "language_variants": dict(sorted(variants.items())),
        "errors": errors,
    }
    print(json.dumps(report, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
