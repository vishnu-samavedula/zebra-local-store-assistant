#!/usr/bin/env python3
"""Build deterministic schema-free correction examples from the canonical catalog."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def _assistant_call(name: str, arguments: dict) -> dict:
    return {
        "role": "assistant",
        "tool_calls": [
            {
                "id": "call_1",
                "type": "function",
                "function": {
                    "name": name,
                    "arguments": json.dumps(arguments, separators=(",", ":")),
                },
            }
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--catalog", type=Path, default=Path("seed_data/warehouse_catalog.jsonl"))
    parser.add_argument(
        "--prompt", type=Path, default=Path("prompts/warehouse_tool_agent_schema_free_v1.md")
    )
    args = parser.parse_args()

    products = [json.loads(line) for line in args.catalog.read_text().splitlines() if line.strip()]
    system_prompt = args.prompt.read_text(encoding="utf-8").strip()
    rows: list[dict] = []
    replenishment_templates = [
        "Add {old} units of {sku} to {location}—sorry, make that {new} units.",
        "Put {old} more {sku} in {location}. Wait, correction: exactly {new}.",
        "Request {old} units for {location}, SKU {sku}; no, the final quantity is {new}.",
        "Replenish {sku} at {location} by {old}. Actually use {new}, not {old}.",
        "For {sku} going to {location}, add {old}—I mean add exactly {new} units.",
    ]
    for index in range(20):
        product = products[(index * 2 + 3) % len(products)]
        old = index % 9 + 2
        new = old + index % 4 + 2
        user = replenishment_templates[index % len(replenishment_templates)].format(
            old=old, new=new, sku=product["sku"], location=product["location"]
        )
        call = _assistant_call(
            "request_replenishment",
            {
                "sku": product["sku"],
                "quantity": new,
                "quantity_mode": "add",
                "destination_location": product["location"],
            },
        )
        rows.append({"messages": json.dumps([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user},
            call,
        ], ensure_ascii=False), "audio": None, "behavior_class": "warehouse_tool_calling"})

    report_templates = [
        "Report {old} damaged units of {sku} at {location}; correction, it is {new} units.",
        "File damage for {old} of {sku} in {location}. Wait, make the quantity {new}.",
        "Damage report: {sku}, {location}, {old} units—no, final count {new}.",
    ]
    for index in range(10):
        product = products[(index * 3 + 1) % len(products)]
        old = index % 7 + 2
        new = old + index % 3 + 1
        user = report_templates[index % len(report_templates)].format(
            old=old, new=new, sku=product["sku"], location=product["location"]
        )
        call = _assistant_call(
            "report_issue",
            {
                "description": f"{new} damaged units of {product['sku']}",
                "category": "damage",
                "sku": product["sku"],
                "quantity": new,
                "location": product["location"],
            },
        )
        rows.append({"messages": json.dumps([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user},
            call,
        ], ensure_ascii=False), "audio": None, "behavior_class": "warehouse_tool_calling"})

    for index in range(5):
        old_product = products[index]
        final_product = products[index + 5]
        user = (
            f"Check {old_product['sku']}—sorry, wrong item. Check {final_product['sku']} instead."
        )
        call = _assistant_call("inventory_search", {"sku": final_product["sku"]})
        rows.append({"messages": json.dumps([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user},
            call,
        ], ensure_ascii=False), "audio": None, "behavior_class": "warehouse_tool_calling"})

    locations = sorted({product["location"] for product in products})
    for index in range(5):
        old_location = locations[index]
        final_location = locations[index + 5]
        user = f"What's in {old_location}? Sorry, I meant {final_location}."
        call = _assistant_call("location_contents", {"location": final_location})
        rows.append({"messages": json.dumps([
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user},
            call,
        ], ensure_ascii=False), "audio": None, "behavior_class": "warehouse_tool_calling"})

    table = pa.Table.from_pylist(
        rows,
        schema=pa.schema([
            pa.field("messages", pa.string()),
            pa.field("audio", pa.string()),
            pa.field("behavior_class", pa.string()),
        ]),
    )
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
            "catalog": str(args.catalog.resolve()),
            "system_prompt": str(args.prompt.resolve()),
            "generator": "scripts/build_correction_supplement.py",
            "families": {
                "replenishment_quantity_correction": 20,
                "damage_quantity_correction": 10,
                "sku_correction": 5,
                "location_correction": 5,
            },
        },
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
