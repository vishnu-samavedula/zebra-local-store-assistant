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
            arguments = call["function"]["arguments"]
            if isinstance(arguments, str):
                arguments = json.loads(arguments)
            calls.append({
                "id": call["id"],
                "name": call["function"]["name"],
                "arguments": arguments,
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
        "id": raw.get("sample_id") or index + 1,
        "family": (
            f"{raw['scenario_bucket']} / {raw['scenario_family']}"
            if raw.get("scenario_bucket") and raw.get("scenario_family")
            else FAMILIES[min(index // 10, len(FAMILIES) - 1)]
        ),
        "split": raw.get("split"),
        "route": raw.get("route"),
        "language_variant": raw.get("language_variant"),
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
    output = output.replace(
        "100 grounded examples · 10 scenario families · no training started",
        f"{len(review_rows)} grounded examples · balanced P1B v5 candidate · no training started",
    ).replace("0 / 100", f"0 / {len(review_rows)}").replace(
        "warehouse-tool-review-v3", "warehouse-tool-review-v5"
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")
    print(f"wrote {args.output} ({len(review_rows)} examples)")


if __name__ == "__main__":
    main()
