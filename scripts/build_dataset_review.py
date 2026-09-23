#!/usr/bin/env python3
"""Build a compact, self-contained HTML review surface from an LQH dataset."""

import argparse
import json
from pathlib import Path

import pyarrow.parquet as pq


FAMILIES = [
    "Inventory lookup",
    "Parallel inventory reads",
    "Location contents",
    "Task status",
    "Damage report",
    "Blocked-location report",
    "Direct replenishment",
    "Dependent inventory → location",
    "Conditional replenishment",
    "Safety / clarification",
]


def compact_row(index: int, raw: dict) -> dict:
    messages = json.loads(raw["messages"])
    calls = []
    results = []
    final = None
    for message in messages:
        for call in message.get("tool_calls") or []:
            calls.append({
                "id": call["id"],
                "name": call["function"]["name"],
                "arguments": json.loads(call["function"]["arguments"]),
            })
        if message["role"] == "tool":
            results.append({
                "call_id": message["tool_call_id"],
                "name": message.get("name"),
                "result": json.loads(message["content"]),
            })
        elif message["role"] == "assistant" and message.get("content"):
            final = message["content"]
    user = next(message["content"] for message in messages if message["role"] == "user")
    return {
        "id": index + 1,
        "family": FAMILIES[min(index // 10, len(FAMILIES) - 1)],
        "user": user,
        "calls": calls,
        "results": results,
        "final": final,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("template", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    rows = pq.read_table(args.dataset).to_pylist()
    review_rows = [compact_row(index, row) for index, row in enumerate(rows)]
    template = args.template.read_text(encoding="utf-8")
    if "__REVIEW_DATA__" not in template:
        raise ValueError("template is missing __REVIEW_DATA__")
    output = template.replace("__REVIEW_DATA__", json.dumps(review_rows, ensure_ascii=False).replace("</", "<\\/"))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")
    print(f"wrote {args.output} ({len(review_rows)} examples)")


if __name__ == "__main__":
    main()
