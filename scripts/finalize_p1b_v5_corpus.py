#!/usr/bin/env python3
"""Join LQH output to its ordered v5 blueprint metadata and remove schemas."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


METADATA = (
    "sample_id", "split", "scenario_bucket", "scenario_family", "route",
    "primary_tool", "argument_shape", "call_count", "issue_category",
    "quantity_mode", "language_variant",
)


def canonical_turn(turn: dict) -> dict:
    return {key: value for key, value in turn.items() if value is not None and key != "tools"}


def normalized(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.casefold()).strip()


def lost_primary_intent(blueprint: dict, utterance: str) -> bool:
    if blueprint.get("scenario_bucket") != "single_tool":
        return False
    patterns = {
        "report_issue": r"\b(report|log|file|record|create|submit)\b",
        "request_replenishment": r"\b(replenish|restock|add|bring|move|target|refill)\b",
        "inventory_search": r"\b(check|find|look|stock|inventory|available|count|how many|search)\b",
        "location_contents": r"\b(list|show|contents|contains|stored|inventory|what(?:'s| is))\b",
        "get_task_status": r"\b(task|tasks|assignment|assignments|status)\b",
    }
    pattern = patterns.get(str(blueprint.get("primary_tool")))
    return bool(pattern and not re.search(pattern, utterance, flags=re.IGNORECASE))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_dataset", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--blueprints", type=Path, default=Path("seed_data/p1b_v5_scenarios.jsonl"))
    args = parser.parse_args()
    source_path = args.raw_dataset / "data.parquet" if args.raw_dataset.is_dir() else args.raw_dataset
    table = pq.read_table(source_path)
    blueprints = [json.loads(line) for line in args.blueprints.read_text().splitlines() if line.strip()]
    if len(table) != len(blueprints):
        raise ValueError(f"LQH returned {len(table)} rows for {len(blueprints)} blueprints")
    output_rows = []
    reserved_seeds = {
        normalized(blueprint["user_seed"]): blueprint["sample_id"] for blueprint in blueprints
    }
    used_utterances: set[str] = set()
    grounded_fallbacks = 0
    intent_fallbacks = 0
    for index, (raw, blueprint) in enumerate(zip(table.to_pylist(), blueprints, strict=True)):
        messages = json.loads(raw["messages"])
        expected_turns = blueprint["turns"]
        if [canonical_turn(turn) for turn in messages[2:]] != [canonical_turn(turn) for turn in expected_turns]:
            raise ValueError(f"row {index} assistant/tool turns do not match blueprint")
        if messages[0].get("content") != blueprint["system_prompt"]:
            raise ValueError(f"row {index} has the wrong system prompt")
        generated = str(messages[1].get("content", ""))
        generated_key = normalized(generated)
        reserved_for = reserved_seeds.get(generated_key)
        if lost_primary_intent(blueprint, generated):
            messages[1]["content"] = blueprint["user_seed"]
            generated_key = normalized(blueprint["user_seed"])
            intent_fallbacks += 1
        elif generated_key in used_utterances or (reserved_for and reserved_for != blueprint["sample_id"]):
            messages[1]["content"] = blueprint["user_seed"]
            generated_key = normalized(blueprint["user_seed"])
            grounded_fallbacks += 1
        if generated_key in used_utterances:
            raise ValueError(f"row {index} still duplicates a worker request after grounded fallback")
        used_utterances.add(generated_key)
        schema_free_messages = [canonical_turn(message) for message in messages]
        output_rows.append({
            "messages": json.dumps(schema_free_messages, ensure_ascii=False),
            "audio": None,
            "behavior_class": "warehouse_tool_calling_v5",
            **{field: blueprint[field] for field in METADATA},
        })
    args.output_dir.mkdir(parents=True, exist_ok=False)
    pq.write_table(pa.Table.from_pylist(output_rows), args.output_dir / "data.parquet")
    print(
        f"wrote {args.output_dir / 'data.parquet'} "
        f"({len(output_rows)} rows, schemas removed, {grounded_fallbacks} grounded deduplications, "
        f"{intent_fallbacks} intent restorations)"
    )


if __name__ == "__main__":
    main()
