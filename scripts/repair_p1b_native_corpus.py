#!/usr/bin/env python3
"""Create an immutable semantic repair of the audited native-tool corpus."""

from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "datasets/warehouse_tool_agent_native_v1_final_v2/data.parquet"
FINAL = ROOT / "datasets/warehouse_tool_agent_native_v1_final_v3"
TRAIN = ROOT / "datasets/warehouse_tool_agent_native_v1_train_v3"
EVAL = ROOT / "datasets/warehouse_tool_agent_native_v1_eval_v3"

TARGET_REPLENISHMENTS = {
    "v5_clarification_007": ("SKU-1811-BLK-110", 7, "A3-06"),
    "v5_clarification_017": ("SKU-2104-WHT-M", 1, "B2-02"),
    "v5_clarification_027": ("SKU-4102-BLK-090", 3, "C1-01"),
    "v5_clarification_037": ("SKU-5106-WHT-XL", 5, "C2-03"),
    "v5_clarification_047": ("SKU-7104-BLK-3032", 7, "D3-02"),
    "v5_clarification_057": ("SKU-1812-BLU-090", 1, "A3-04"),
    "v5_clarification_067": ("SKU-2105-WHT-L", 3, "B2-03"),
    "v5_clarification_077": ("SKU-4103-BLK-100", 5, "C1-02"),
    "v5_clarification_087": ("SKU-6101-KHK-3032", 7, "D1-01"),
}

MIXED_READ_WRITES = {
    "v5_rejection_004": ("SKU-7311-YEL-L", 5, "C4"),
    "v5_rejection_014": ("SKU-2101-BLK-S", 5, "B2-01"),
    "v5_rejection_024": ("SKU-3201-TAN-OS", 5, "B3-02"),
    "v5_rejection_034": ("SKU-5103-BLU-XL", 5, "C2-02"),
    "v5_rejection_044": ("SKU-7101-IND-3032", 5, "D3-01"),
    "v5_rejection_054": ("SKU-4902-CLR-500", 5, "D2"),
}


def call(call_id: str, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": arguments},
    }


def inventory_row(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "product_id": item["product_id"],
        "sku": item["sku"],
        "name": item["name"],
        "category": item["category"],
        "color": item["color"],
        "size": item["size"],
        "unit": item["unit"],
        "location": item["location"],
        "available": item["on_hand"] - item["reserved"],
    }


def write_dataset(path: Path, rows: list[dict[str, Any]], purpose: str) -> None:
    if path.exists():
        raise FileExistsError(f"refusing to overwrite immutable dataset: {path}")
    path.mkdir(parents=True)
    data_path = path / "data.parquet"
    pq.write_table(pa.Table.from_pylist(rows), data_path)
    now = datetime.now(timezone.utc).isoformat()
    manifest = {
        "schema_version": 2,
        "name": path.name,
        "channel": "main",
        "current": {
            "object_sha": hashlib.sha256(data_path.read_bytes()).hexdigest(),
            "purpose": purpose,
            "rows": len(rows),
            "created_at": now,
        },
        "history": [],
        "updated_at": now,
        "derived_from": "warehouse_tool_agent_native_v1_final_v2",
        "repair": "semantic labels: corrections, explicit target levels, and sequential read/write",
    }
    (path / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main() -> None:
    catalog = {
        item["sku"]: item
        for item in (
            json.loads(line)
            for line in (ROOT / "seed_data/warehouse_catalog.jsonl").read_text().splitlines()
            if line.strip()
        )
    }
    rows = pq.read_table(SOURCE).to_pylist()
    changes: list[str] = []

    for row in rows:
        sample_id = row["sample_id"]
        messages = json.loads(row["messages"])

        if sample_id == "v5_correction_043":
            messages[-1]["tool_calls"][0]["function"]["arguments"]["task_id"] = "T-122"
            changes.append(f"{sample_id}: final corrected task is T-122")

        if sample_id in TARGET_REPLENISHMENTS:
            sku, quantity, destination = TARGET_REPLENISHMENTS[sample_id]
            messages[-1] = {
                "role": "assistant",
                "content": None,
                "tool_calls": [call("call_1", "request_replenishment", {
                    "sku": sku,
                    "quantity": quantity,
                    "quantity_mode": "target_level",
                    "destination_location": destination,
                })],
            }
            row.update({
                "scenario_bucket": "single_tool",
                "scenario_family": "replenishment_target_level",
                "route": "confirm_write",
                "primary_tool": "request_replenishment",
                "argument_shape": "sku+quantity+quantity_mode+destination_location",
                "call_count": 1,
                "quantity_mode": "target_level",
            })
            changes.append(f"{sample_id}: explicit make/set/ensure quantity is target_level")

        if sample_id in MIXED_READ_WRITES:
            sku, quantity, destination = MIXED_READ_WRITES[sample_id]
            item = catalog[sku]
            read = call("call_1", "inventory_search", {"sku": sku})
            write = call("call_2", "request_replenishment", {
                "sku": sku,
                "quantity": quantity,
                "quantity_mode": "add",
                "destination_location": destination,
            })
            messages = messages[:2] + [
                {"role": "assistant", "content": None, "tool_calls": [read]},
                {
                    "role": "tool",
                    "content": json.dumps({"matches": [inventory_row(item)]}, separators=(",", ":")),
                    "tool_call_id": "call_1",
                    "name": "inventory_search",
                },
                {"role": "assistant", "content": None, "tool_calls": [write]},
            ]
            row.update({
                "scenario_bucket": "dependent_trace",
                "scenario_family": "read_then_replenish_add",
                "route": "confirm_write",
                "primary_tool": "inventory_search",
                "argument_shape": "sku->sku+quantity+quantity_mode+destination_location",
                "call_count": 2,
                "quantity_mode": "add",
            })
            changes.append(f"{sample_id}: execute read, then isolate replenishment proposal")

        if sample_id == "v5_single_tool_059":
            arguments = messages[-1]["tool_calls"][0]["function"]["arguments"]
            arguments.pop("minimum_quantity", None)
            row["argument_shape"] = "semantic_query"
            changes.append(f"{sample_id}: exact count is not a minimum-quantity filter")

        row["messages"] = json.dumps(messages, ensure_ascii=False)

    if len(changes) != 17:
        raise AssertionError(f"expected 17 semantic repairs, found {len(changes)}")
    train = [row for row in rows if row["split"] == "train"]
    eval_rows = [row for row in rows if row["split"] == "eval"]
    if (len(train), len(eval_rows)) != (500, 100):
        raise AssertionError("split sizes changed")
    write_dataset(FINAL, rows, "review")
    write_dataset(TRAIN, train, "training")
    write_dataset(EVAL, eval_rows, "validation")
    print(json.dumps({"rows": len(rows), "train": len(train), "eval": len(eval_rows), "changes": changes}, indent=2))


if __name__ == "__main__":
    main()
