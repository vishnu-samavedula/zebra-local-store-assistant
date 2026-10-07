#!/usr/bin/env python3
"""Build deterministic product-name multi-read coverage and a held-out device probe."""

from __future__ import annotations

import hashlib
import itertools
import json
import random
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq


ROOT = Path(__file__).resolve().parents[1]
TRAIN = ROOT / "datasets/warehouse_tool_agent_native_semantic_multiread_train_v1"
EVAL = ROOT / "datasets/warehouse_tool_agent_native_semantic_multiread_eval_v1"


def tool_call(index: int, product: str) -> dict[str, Any]:
    return {
        "id": f"call_{index}",
        "type": "function",
        "function": {"name": "inventory_search", "arguments": {"semantic_query": product}},
    }


def write_dataset(path: Path, rows: list[dict[str, Any]], purpose: str) -> None:
    if path.exists():
        raise FileExistsError(f"refusing to overwrite immutable dataset: {path}")
    path.mkdir(parents=True)
    data_path = path / "data.parquet"
    pq.write_table(pa.Table.from_pylist(rows), data_path)
    now = datetime.now(timezone.utc).isoformat()
    (path / "manifest.json").write_text(json.dumps({
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
        "derived_from": "seed_data/warehouse_catalog.jsonl",
    }, indent=2) + "\n")


def main() -> None:
    prompt = (ROOT / "contracts/warehouse_tool_agent_native_v1.md").read_text().strip()
    tools = json.loads((ROOT / "contracts/warehouse_tools_v1.json").read_text())
    tools_json = json.dumps(tools, ensure_ascii=False, separators=(",", ":"))
    products = sorted({
        json.loads(line)["name"]
        for line in (ROOT / "seed_data/warehouse_catalog.jsonl").read_text().splitlines()
        if line.strip()
    })
    groups = list(itertools.combinations(products, 2)) + list(itertools.combinations(products, 3))
    random.Random(42).shuffle(groups)
    groups = groups[:52]
    templates = (
        "Where can I find {items}?",
        "Check stock for {items}.",
        "Give me the locations of {items}.",
        "Look up {items} in inventory.",
        "I need inventory details for {items}.",
        "Find {items} for me.",
    )
    rows = []
    for index, group in enumerate(groups):
        joined = ", ".join(group[:-1]) + f", and {group[-1]}"
        user = templates[index % len(templates)].format(items=joined)
        messages = [
            {"role": "system", "content": prompt},
            {"role": "user", "content": user},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [tool_call(call_index, product) for call_index, product in enumerate(group, 1)],
            },
        ]
        split = "train" if index < 40 else "eval"
        rows.append({
            "messages": json.dumps(messages, ensure_ascii=False),
            "audio": None,
            "behavior_class": "warehouse_tool_calling_native_semantic_multiread",
            "sample_id": f"native_semantic_multiread_{index + 1:03d}",
            "split": split,
            "scenario_bucket": "multi_read",
            "scenario_family": f"semantic_{len(group)}",
            "route": "execute_read",
            "primary_tool": "inventory_search",
            "argument_shape": "+".join("semantic_query" for _ in group),
            "call_count": len(group),
            "issue_category": "not_applicable",
            "quantity_mode": "not_applicable",
            "language_variant": "clean",
            "tools": tools_json,
        })

    # Freeze the exact device regression in eval, never training.
    exact = rows[40]
    exact_messages = json.loads(exact["messages"])
    exact_products = ["Harbor Classic T-Shirt", "Metro Zip Wallet", "MetroRun Sneaker"]
    exact_messages[1]["content"] = "Give me the location of Harbor Classic T-shirts, Metro Zip Wallets, and Metro Run Sneaker."
    exact_messages[2]["tool_calls"] = [tool_call(i, name) for i, name in enumerate(exact_products, 1)]
    exact["messages"] = json.dumps(exact_messages, ensure_ascii=False)
    exact["call_count"] = 3
    exact["scenario_family"] = "semantic_3_device_regression"

    train = [row for row in rows if row["split"] == "train"]
    eval_rows = [row for row in rows if row["split"] == "eval"]
    write_dataset(TRAIN, train, "training")
    write_dataset(EVAL, eval_rows, "validation")
    print(json.dumps({"products": products, "train": len(train), "eval": len(eval_rows)}, indent=2))


if __name__ == "__main__":
    main()
