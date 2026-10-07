#!/usr/bin/env python3
"""Integrate a small, bucket-matched correction set into the balanced 500-row train split."""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


SOURCE = Path("datasets/warehouse_tool_agent_v5_compact_train_v4/data.parquet")
SUPPLEMENT = Path("datasets/warehouse_tool_agent_v5_correction_supplement/data.parquet")
EVAL = Path("datasets/warehouse_tool_agent_v5_compact_eval_v4/data.parquet")
OUTPUT = Path("datasets/warehouse_tool_agent_v5_compact_train_v5_integrated")

PLACEMENTS = {
    "task_id_final_correction": ("correction", "get_task_status", None),
    "three_independent_reads": ("multi_read", "inventory_search", None),
    "target_level_enum": ("single_tool", "request_replenishment", "replenishment_target_level"),
    "explicit_general_issue": ("single_tool", "report_issue", "report_general"),
    "task_status_field": ("single_tool", "get_task_status", None),
    "grounded_location_confirmation": ("clarification", "inventory_search", None),
}


def normalized_user(raw_messages: str) -> str:
    messages = json.loads(raw_messages)
    user = next(message["content"] for message in messages if message["role"] == "user")
    return re.sub(r"[^a-z0-9]+", " ", user.casefold()).strip()


def main() -> None:
    if OUTPUT.exists():
        raise FileExistsError(f"refusing to overwrite immutable dataset: {OUTPUT}")

    rows = pq.read_table(SOURCE).to_pylist()
    supplement = pq.read_table(SUPPLEMENT).to_pylist()
    held_out = {normalized_user(row["messages"]) for row in pq.read_table(EVAL).to_pylist()}

    selected: list[dict] = []
    removed: set[int] = set()
    for correction_family, (bucket, tool, target_family) in PLACEMENTS.items():
        family_rows = [row.copy() for row in supplement if row["scenario_family"] == correction_family]
        family_selected = 0
        for replacement in family_rows:
            candidates = [
                index
                for index, row in enumerate(rows)
                if index not in removed
                and row["scenario_bucket"] == bucket
                and row["primary_tool"] == tool
                and row["language_variant"] == replacement["language_variant"]
                and (target_family is None or row["scenario_family"] == target_family)
            ]
            if not candidates:
                continue
            removed.add(candidates[0])
            replacement["scenario_bucket"] = bucket
            replacement["primary_tool"] = tool
            replacement["sample_id"] = f"v5_integrated_{len(selected) + 1:03d}"
            selected.append(replacement)
            family_selected += 1
            if family_selected == 3:
                break
        if family_selected != 3:
            raise AssertionError(f"could not place three balanced rows for {correction_family}")

    integrated = [row for index, row in enumerate(rows) if index not in removed] + selected
    if len(integrated) != 500:
        raise AssertionError(f"expected 500 rows, found {len(integrated)}")
    prompts = [normalized_user(row["messages"]) for row in integrated]
    if len(prompts) != len(set(prompts)):
        raise AssertionError("duplicate normalized training prompts")
    overlap = held_out.intersection(prompts)
    if overlap:
        raise AssertionError(f"training/eval prompt overlap: {sorted(overlap)[:3]}")

    before = Counter((row["scenario_bucket"], row["primary_tool"], row["language_variant"]) for row in rows)
    after = Counter((row["scenario_bucket"], row["primary_tool"], row["language_variant"]) for row in integrated)
    if before != after:
        raise AssertionError("bucket/tool/language balance changed")

    OUTPUT.mkdir(parents=True, exist_ok=False)
    pq.write_table(pa.Table.from_pylist(integrated), OUTPUT / "data.parquet")
    print(f"wrote {OUTPUT / 'data.parquet'} ({len(integrated)} rows; {len(selected)} matched corrections)")


if __name__ == "__main__":
    main()
