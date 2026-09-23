#!/usr/bin/env python3
"""Compare schema-free native call text with structured held-out references."""

from __future__ import annotations

import argparse
import ast
import json
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq


START = "<|tool_call_start|>"
END = "<|tool_call_end|>"


def _value(node: ast.AST) -> Any:
    if isinstance(node, ast.Constant):
        return node.value
    if isinstance(node, (ast.List, ast.Tuple)):
        return [_value(item) for item in node.elts]
    raise ValueError(f"unsupported argument expression: {ast.dump(node)}")


def parse_native(content: str) -> list[dict[str, Any]] | None:
    if START not in content or END not in content:
        return None
    if content.count(START) != 1 or content.count(END) != 1:
        raise ValueError("expected one native call block")
    prefix, rest = content.split(START, 1)
    body, suffix = rest.split(END, 1)
    if prefix.strip() or suffix.strip():
        raise ValueError("prose outside native call block")
    expression = ast.parse(body.strip(), mode="eval").body
    if not isinstance(expression, (ast.List, ast.Tuple)):
        raise ValueError("native call block must contain a list")
    calls: list[dict[str, Any]] = []
    for item in expression.elts:
        if not isinstance(item, ast.Call) or not isinstance(item.func, ast.Name) or item.args:
            raise ValueError("native entries must be named calls with keyword arguments")
        arguments = {keyword.arg: _value(keyword.value) for keyword in item.keywords}
        arguments = {key: value for key, value in arguments.items() if value is not None}
        calls.append({"name": item.func.id, "arguments": arguments})
    return calls


def reference_calls(raw_reference: str) -> list[dict[str, Any]] | None:
    messages = json.loads(raw_reference)
    tool_calls = messages[-1].get("tool_calls")
    if not tool_calls:
        return None
    normalized = []
    for call in tool_calls:
        arguments = call["function"]["arguments"]
        if isinstance(arguments, str):
            arguments = json.loads(arguments)
        normalized.append({"name": call["function"]["name"], "arguments": arguments})
    return normalized


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("predictions", type=Path)
    parser.add_argument(
        "--references",
        type=Path,
        help="Dataset parquet whose final assistant turns are references when predictions omit a reference column",
    )
    parser.add_argument("--source", help="Evaluate only rows with this source label")
    args = parser.parse_args()
    rows = pq.read_table(args.predictions).to_pylist()
    if args.source:
        rows = [row for row in rows if row.get("source") == args.source]
    reference_rows = pq.read_table(args.references).to_pylist() if args.references else None
    if reference_rows is not None and len(reference_rows) != len(rows):
        raise ValueError("prediction and reference row counts differ")
    totals = {
        "rows": len(rows),
        "tool_reference_rows": 0,
        "native_parseable": 0,
        "tool_names_exact": 0,
        "arguments_exact_after_null_drop": 0,
        "no_tool_reference_rows": 0,
        "unexpected_native_call": 0,
    }
    mismatches: list[dict[str, Any]] = []
    for row_position, row in enumerate(rows):
        messages = json.loads(row["messages"])
        predicted_content = messages[-1].get("content", "")
        if "reference" in row:
            raw_reference = row["reference"]
        elif reference_rows is not None:
            raw_messages = reference_rows[row_position]["messages"]
            reference_messages = json.loads(raw_messages) if isinstance(raw_messages, str) else raw_messages
            raw_reference = json.dumps([reference_messages[-1]])
        else:
            raise ValueError("predictions have no reference column; pass --references")
        expected = reference_calls(raw_reference)
        try:
            actual = parse_native(predicted_content)
        except ValueError as exc:
            actual = None
            parse_error = str(exc)
        else:
            parse_error = None
        if expected is None:
            totals["no_tool_reference_rows"] += 1
            if actual:
                totals["unexpected_native_call"] += 1
            continue
        totals["tool_reference_rows"] += 1
        if actual is not None:
            totals["native_parseable"] += 1
        names_match = actual is not None and [c["name"] for c in actual] == [
            c["name"] for c in expected
        ]
        if names_match:
            totals["tool_names_exact"] += 1
        exact = names_match and actual == expected
        if exact:
            totals["arguments_exact_after_null_drop"] += 1
        else:
            user = next(
                (message.get("content") for message in messages if message.get("role") == "user"),
                None,
            )
            mismatches.append(
                {
                    "sample_index": row["sample_index"],
                    "user": user,
                    "expected": expected,
                    "actual": actual,
                    "parse_error": parse_error,
                }
            )
    print(json.dumps({"summary": totals, "mismatches": mismatches}, indent=2))


if __name__ == "__main__":
    main()
