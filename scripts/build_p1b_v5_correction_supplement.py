#!/usr/bin/env python3
"""Build analogous corrections for v5 errors without copying held-out rows."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def call(call_id: str, name: str, arguments: dict) -> dict:
    return {"id": call_id, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments, separators=(",", ":"))}}


def assistant_calls(*calls: dict) -> dict:
    return {"role": "assistant", "tool_calls": list(calls)}


def tool_result(tool_call: dict, result: dict) -> dict:
    return {"role": "tool", "content": json.dumps(result, separators=(",", ":")), "tool_call_id": tool_call["id"], "name": tool_call["function"]["name"]}


def inventory_row(product: dict) -> dict:
    keys = ("product_id", "sku", "name", "category", "color", "size", "unit", "location")
    return {key: product[key] for key in keys} | {"available": product["on_hand"] - product["reserved"]}


def main() -> None:
    products = read_jsonl(Path("seed_data/warehouse_catalog.jsonl"))
    tasks = read_jsonl(Path("seed_data/warehouse_tasks.jsonl"))
    system = Path("prompts/warehouse_tool_agent_compact_v2.md").read_text().strip()
    base_eval = pq.read_table("datasets/warehouse_tool_agent_v5_compact_eval_v4/data.parquet")
    held_out_prompts = {
        re.sub(r"[^a-z0-9]+", " ", next(m["content"] for m in json.loads(raw) if m["role"] == "user").casefold()).strip()
        for raw in base_eval["messages"].to_pylist()
    }
    rows: list[dict] = []

    def add(family: str, user: str, turns: list[dict], primary_tool: str) -> None:
        key = re.sub(r"[^a-z0-9]+", " ", user.casefold()).strip()
        if key in held_out_prompts:
            raise ValueError(f"supplement duplicates held-out prompt: {user}")
        calls = [c for turn in turns for c in (turn.get("tool_calls") or [])]
        fields = sorted({field for c in calls for field in json.loads(c["function"]["arguments"])})
        rows.append({
            "messages": json.dumps([{"role": "system", "content": system}, {"role": "user", "content": user}, *turns], ensure_ascii=False),
            "audio": None,
            "behavior_class": "warehouse_tool_calling_v5_correction",
            "sample_id": f"v5_correction_supplement_{len(rows) + 1:03d}",
            "split": "train",
            "scenario_bucket": "correction_supplement",
            "scenario_family": family,
            "route": "tool" if calls else "clarify",
            "primary_tool": primary_tool,
            "argument_shape": ",".join(fields) if fields else "none",
            "call_count": len(calls),
            "issue_category": "general" if primary_tool == "report_issue" else "not_applicable",
            "quantity_mode": "target_level" if primary_tool == "request_replenishment" else "not_applicable",
            "language_variant": ("clean", "fragment", "correction", "asr_like")[len(rows) % 4],
        })

    # Final-value corrections for task IDs.
    for i in range(10):
        old, final = tasks[i], tasks[i + 10]
        c = call("call_1", "get_task_status", {"task_id": final["task_id"]})
        add("task_id_final_correction", f"Check {old['task_id']}—correction, the final task is {final['task_id']}.", [assistant_calls(c)], "get_task_status")

    # Three independent reads must all survive one call block.
    for i in range(10):
        p = products[i]; q = products[i + 12]; task = tasks[i]
        c1 = call("call_1", "inventory_search", {"sku": p["sku"]})
        c2 = call("call_2", "location_contents", {"location": q["location"]})
        c3 = call("call_3", "get_task_status", {"task_id": task["task_id"]})
        add("three_independent_reads", f"In one check, get stock for {p['sku']}, list {q['location']}, and retrieve task {task['task_id']}.", [assistant_calls(c1, c2, c3)], "inventory_search")

    # Explicit target-level language and valid enum output.
    for i in range(10):
        p = products[i + 20]; target = i + 11
        identity = {"sku": p["sku"]} if i % 2 == 0 else {"semantic_query": p["name"]}
        args = identity | {"quantity": target, "quantity_mode": "target_level", "destination_location": p["location"]}
        c = call("call_1", "request_replenishment", args)
        spoken = p["sku"] if "sku" in identity else p["name"]
        add("target_level_enum", f"Set {spoken} at {p['location']} to a target level of {target} units.", [assistant_calls(c)], "request_replenishment")

    # Explicit issue verbs prevent observational text from becoming a write.
    verbs = ("Report", "Log", "File", "Record", "Create a report for")
    for i in range(10):
        p = products[i + 30]; task = tasks[i + 5]
        args = {"description": "Location label is unreadable", "category": "general", "location": p["location"], "task_id": task["task_id"]}
        c = call("call_1", "report_issue", args)
        add("explicit_general_issue", f"{verbs[i % len(verbs)]} an unreadable location label at {p['location']} for task {task['task_id']}.", [assistant_calls(c)], "report_issue")

    # Status is never a task_type.
    status_contexts = (
        "For my current queue", "Before the next pick", "For this shift", "For my floor review",
        "Before break", "For the handoff", "For my task board", "For the next update",
        "For today's queue", "For the shift summary",
    )
    for i in range(10):
        task = tasks[i + 8]
        c = call("call_1", "get_task_status", {"status": task["status"]})
        add("task_status_field", f"{status_contexts[i]}, show only my warehouse tasks with status {task['status']}.", [assistant_calls(c)], "get_task_status")

    # After a grounding result, ask for confirmation instead of issuing another read.
    for i in range(10):
        p = products[i + 39]; quantity = i % 5 + 1
        c = call("call_1", "inventory_search", {"sku": p["sku"]})
        turns = [assistant_calls(c), tool_result(c, {"matches": [inventory_row(p)]}), {"role": "assistant", "content": f"The catalog stores {p['sku']} at {p['location']}. Is that where the damage occurred?"}]
        add("grounded_location_confirmation", f"Prepare a damage report for {quantity} units of {p['sku']}; I have not confirmed the location yet.", turns, "inventory_search")

    if len(rows) != 60:
        raise AssertionError(f"expected 60 rows, found {len(rows)}")
    prompts = [re.sub(r"[^a-z0-9]+", " ", next(m["content"] for m in json.loads(row["messages"]) if m["role"] == "user").casefold()).strip() for row in rows]
    if len(set(prompts)) != len(prompts):
        raise AssertionError("duplicate supplement prompts")
    output = Path("datasets/warehouse_tool_agent_v5_correction_supplement")
    output.mkdir(parents=True, exist_ok=False)
    pq.write_table(pa.Table.from_pylist(rows), output / "data.parquet")
    print(f"wrote {output / 'data.parquet'} ({len(rows)} analogous correction rows)")


if __name__ == "__main__":
    main()
