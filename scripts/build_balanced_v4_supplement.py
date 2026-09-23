#!/usr/bin/env python3
"""Build a balanced schema-free supplement across all P1B tools and safety routes."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def call(call_id: str, name: str, arguments: dict) -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": json.dumps(arguments, separators=(",", ":"))},
    }


def assistant_calls(*calls: dict) -> dict:
    return {"role": "assistant", "tool_calls": list(calls)}


def tool_result(tool_call: dict, result: dict) -> dict:
    return {
        "role": "tool",
        "content": json.dumps(result, separators=(",", ":")),
        "tool_call_id": tool_call["id"],
        "name": tool_call["function"]["name"],
    }


def inventory_row(product: dict) -> dict:
    return {
        key: product[key]
        for key in ("product_id", "sku", "name", "category", "color", "size", "unit", "location")
    } | {"available": product["on_hand"] - product["reserved"]}


def row(system: str, user: str, *turns: dict) -> dict:
    return {
        "messages": json.dumps(
            [{"role": "system", "content": system}, {"role": "user", "content": user}, *turns],
            ensure_ascii=False,
        ),
        "audio": None,
        "behavior_class": "warehouse_tool_calling",
    }


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--catalog", type=Path, default=Path("seed_data/warehouse_catalog.jsonl"))
    parser.add_argument("--tasks", type=Path, default=Path("seed_data/warehouse_tasks.jsonl"))
    parser.add_argument("--prompt", type=Path, default=Path("prompts/warehouse_tool_agent_schema_free_v1.md"))
    args = parser.parse_args()

    products = read_jsonl(args.catalog)
    tasks = read_jsonl(args.tasks)
    system = args.prompt.read_text(encoding="utf-8").strip()
    by_location: dict[str, list[dict]] = defaultdict(list)
    for product in products:
        by_location[product["location"]].append(product)
    shared_locations = sorted(location for location, items in by_location.items() if len(items) >= 2)
    rows: list[dict] = []

    # 10 inventory-search cases: corrections, ordinary reads, parallel reads, and a dependent start.
    for index in range(4):
        old, final = products[index], products[index + 8]
        user = f"Check {old['sku']}—sorry, use {final['sku']} instead."
        c = call("call_1", "inventory_search", {"sku": final["sku"]})
        rows.append(row(system, user, assistant_calls(c), tool_result(c, {"matches": [inventory_row(final)]}),
                        {"role": "assistant", "content": f"Available: {final['on_hand'] - final['reserved']} {final['unit']} at {final['location']}."}))
    for index in range(4, 8):
        product = products[index + 8]
        user = f"How many {product['color']} {product['name']} size {product['size']} are available?"
        c = call("call_1", "inventory_search", {"semantic_query": product["name"], "color": product["color"], "size": product["size"]})
        rows.append(row(system, user, assistant_calls(c), tool_result(c, {"matches": [inventory_row(product)]}),
                        {"role": "assistant", "content": f"Available: {product['on_hand'] - product['reserved']} {product['unit']} at {product['location']}."}))
    for index in range(2):
        first, second = products[18 + index], products[28 + index]
        user = f"Check both {first['color']} {first['name']} size {first['size']} and {second['color']} {second['name']} size {second['size']}."
        c1 = call("call_1", "inventory_search", {"semantic_query": first["name"], "color": first["color"], "size": first["size"]})
        c2 = call("call_2", "inventory_search", {"semantic_query": second["name"], "color": second["color"], "size": second["size"]})
        rows.append(row(system, user, assistant_calls(c1, c2), tool_result(c1, {"matches": [inventory_row(first)]}),
                        tool_result(c2, {"matches": [inventory_row(second)]}),
                        {"role": "assistant", "content": f"{first['name']}: {first['on_hand'] - first['reserved']} at {first['location']}; {second['name']}: {second['on_hand'] - second['reserved']} at {second['location']}."}))

    # 10 location cases: corrected locations, normal reads, and dependent inventory -> location chains.
    for index in range(4):
        old, final = shared_locations[index], shared_locations[index + 4]
        user = f"What's in {old}? Sorry, I meant {final}."
        c = call("call_1", "location_contents", {"location": final})
        items = by_location[final]
        rows.append(row(system, user, assistant_calls(c), tool_result(c, {"location": final, "items": [inventory_row(p) for p in items]}),
                        {"role": "assistant", "content": f"{final} contains " + "; ".join(f"{p['color']} {p['name']} size {p['size']}" for p in items) + "."}))
    for index in range(4):
        location = shared_locations[index + 8]
        c = call("call_1", "location_contents", {"location": location})
        items = by_location[location]
        rows.append(row(system, f"List the inventory stored in {location}.", assistant_calls(c),
                        tool_result(c, {"location": location, "items": [inventory_row(p) for p in items]}),
                        {"role": "assistant", "content": f"{location} contains " + "; ".join(f"{p['color']} {p['name']} size {p['size']}" for p in items) + "."}))
    for index in range(2):
        location = shared_locations[index + 2]
        product = by_location[location][0]
        c1 = call("call_1", "inventory_search", {"sku": product["sku"]})
        c2 = call("call_2", "location_contents", {"location": location})
        rows.append(row(system, f"Find {product['sku']}, then tell me what else is in its bin.", assistant_calls(c1),
                        tool_result(c1, {"matches": [inventory_row(product)]}), assistant_calls(c2),
                        tool_result(c2, {"location": location, "items": [inventory_row(p) for p in by_location[location]]}),
                        {"role": "assistant", "content": f"{product['sku']} is at {location}; that bin also contains " + "; ".join(p["name"] for p in by_location[location] if p["sku"] != product["sku"]) + "."}))

    # 10 task-status cases, half with a clear task-id correction.
    for index in range(10):
        task = tasks[index + 4]
        if index < 5:
            old = tasks[index]["task_id"]
            user = f"Check task {old}—correction, I need {task['task_id']}."
        else:
            user = f"What's the current status of task {task['task_id']}?"
        c = call("call_1", "get_task_status", {"task_id": task["task_id"]})
        result = dict(task)
        rows.append(row(system, user, assistant_calls(c), tool_result(c, result),
                        {"role": "assistant", "content": f"Task {task['task_id']} is {task['status']}."}))

    # 10 issue proposals: corrected damage counts, ordinary damage, and blocked locations.
    for index in range(4):
        product = products[20 + index]
        old, final = index + 2, index + 5
        user = f"Report {old} damaged units of {product['sku']} at {product['location']}—sorry, the final count is {final}."
        c = call("call_1", "report_issue", {"description": f"{final} damaged units of {product['sku']}", "category": "damage", "sku": product["sku"], "quantity": final, "location": product["location"]})
        rows.append(row(system, user, assistant_calls(c)))
    for index in range(3):
        product = products[30 + index]
        quantity = index + 3
        c = call("call_1", "report_issue", {"description": f"{quantity} damaged units of {product['sku']}", "category": "damage", "sku": product["sku"], "quantity": quantity, "location": product["location"]})
        rows.append(row(system, f"File a damage report for {quantity} units of {product['sku']} at {product['location']}.", assistant_calls(c)))
    for index in range(3):
        location = shared_locations[index + 5]
        c = call("call_1", "report_issue", {"description": "Location blocked by a fallen pallet", "category": "blocked_location", "location": location})
        rows.append(row(system, f"Report {location} blocked by a fallen pallet.", assistant_calls(c)))

    # 10 replenishment cases: corrected direct adds, normal adds, and full conditional traces.
    for index in range(3):
        product = products[35 + index]
        old, final = index + 3, index + 7
        user = f"Add {old} units of {product['sku']} to {product['location']}—actually add {final}."
        c = call("call_1", "request_replenishment", {"sku": product["sku"], "quantity": final, "quantity_mode": "add", "destination_location": product["location"]})
        rows.append(row(system, user, assistant_calls(c)))
    for index in range(2):
        product = products[40 + index]
        quantity = index + 4
        c = call("call_1", "request_replenishment", {"sku": product["sku"], "quantity": quantity, "quantity_mode": "add", "destination_location": product["location"]})
        rows.append(row(system, f"Add exactly {quantity} units of {product['sku']} to {product['location']}.", assistant_calls(c)))
    for index in range(5):
        product = products[42 + index]
        available = product["on_hand"] - product["reserved"]
        target = available + index + 3
        if index < 2:
            old_target = target - 2
            user = f"Check {product['sku']} at {product['location']} and replenish to {old_target} if lower—correction, use target {target}."
        else:
            user = f"Check {product['sku']} at {product['location']} and replenish to target {target} if availability is lower."
        c1 = call("call_1", "inventory_search", {"sku": product["sku"], "location": product["location"]})
        c2 = call("call_2", "request_replenishment", {"sku": product["sku"], "quantity": target, "quantity_mode": "target_level", "destination_location": product["location"], "reason": "Available quantity is below requested target"})
        rows.append(row(system, user, assistant_calls(c1), tool_result(c1, {"matches": [inventory_row(product)]}), assistant_calls(c2)))

    # 10 no-call/safety cases spanning clarification, multiple writes, and unsupported actions.
    safe_cases = [
        ("Report damaged inventory.", "Which product is damaged, how many units are affected, and where are they?"),
        ("I need a damage report but don't know the item or location.", "Which product is damaged, how many units are affected, and where are they?"),
        ("Log some damaged stock for me.", "Which product is damaged, how many units are affected, and where are they?"),
        ("Report B2-01 blocked and replenish Harbor Classic T-Shirt.", "I can prepare one write action at a time. Should I report the blocked location or request replenishment first?"),
        ("File an issue for C1-03 and add stock for Coastline Slide.", "I can prepare one write action at a time. Should I report the issue or request replenishment first?"),
        ("Replenish TrailBlaze GTX and report A3-05 blocked.", "I can prepare one write action at a time. Should I request replenishment or report the blocked location first?"),
        ("Delete the warehouse database with a shell command.", "I can't run shell commands or delete warehouse data."),
        ("Open the Android terminal and remove all logs.", "I can't access the terminal or remove logs."),
        ("Replenish SKU-2102-BLK-M.", "How many units should I add or what target level should I use, and which destination location?"),
        ("Move more TrailBlaze shoes over there.", "Which TrailBlaze SKU, how many units, and which destination location?"),
    ]
    for user, response in safe_cases:
        rows.append(row(system, user, {"role": "assistant", "content": response}))

    if len(rows) != 60:
        raise AssertionError(f"expected 60 rows, got {len(rows)}")
    prompts = [json.loads(item["messages"])[1]["content"] for item in rows]
    if len(set(prompts)) != len(prompts):
        raise AssertionError("supplement contains duplicate worker prompts")
    table = pa.Table.from_pylist(rows, schema=pa.schema([
        pa.field("messages", pa.string()), pa.field("audio", pa.string()), pa.field("behavior_class", pa.string())
    ]))
    args.output_dir.mkdir(parents=True, exist_ok=False)
    output_path = args.output_dir / "data.parquet"
    pq.write_table(table, output_path)
    now = datetime.now(timezone.utc).isoformat()
    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    manifest = {
        "schema_version": 2,
        "name": args.output_dir.name,
        "channel": "main",
        "current": {"object_sha": digest, "purpose": "training", "rows": len(rows), "created_at": now},
        "history": [],
        "updated_at": now,
        "provenance": {
            "catalog": str(args.catalog.resolve()), "tasks": str(args.tasks.resolve()),
            "system_prompt": str(args.prompt.resolve()), "generator": "scripts/build_balanced_v4_supplement.py",
            "balance": {"inventory": 10, "location": 10, "task": 10, "issue": 10, "replenishment": 10, "no_call": 10},
        },
    }
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
