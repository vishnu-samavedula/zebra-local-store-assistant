#!/usr/bin/env python3
"""Run locked schema-free first-turn cases through a llama.cpp HTTP server."""

from __future__ import annotations

import argparse
import ast
import json
import urllib.request
from pathlib import Path
from typing import Any


START = "<|tool_call_start|>"
END = "<|tool_call_end|>"


def post(base_url: str, path: str, payload: dict) -> dict:
    request = urllib.request.Request(
        base_url + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def value(node: ast.AST) -> Any:
    if isinstance(node, ast.Constant):
        return node.value
    raise ValueError(ast.dump(node))


def parse_calls(content: str) -> list[dict] | None:
    if content.startswith("[") and content.endswith("]"):
        content = START + content + END
    if START not in content or END not in content:
        return None
    body = content.split(START, 1)[1].split(END, 1)[0]
    parsed = ast.parse(body.strip(), mode="eval").body
    if not isinstance(parsed, ast.List):
        raise ValueError("call block is not a list")
    calls = []
    for item in parsed.elts:
        if not isinstance(item, ast.Call) or not isinstance(item.func, ast.Name):
            raise ValueError("malformed call")
        arguments = {keyword.arg: value(keyword.value) for keyword in item.keywords}
        calls.append({"name": item.func.id, "arguments": {k: v for k, v in arguments.items() if v is not None}})
    return calls


CASES = [
    ("inventory", "Check how many Blue TrailBlaze GTX size 10.5 are available.", [{"name": "inventory_search", "arguments": {"semantic_query": "TrailBlaze GTX", "color": "Blue", "size": "10.5"}}]),
    ("parallel", "Check both Tan Metro Zip Wallet size One Size and Clear DockPro Pallet Wrap size 500 m.", [{"name": "inventory_search", "arguments": {"semantic_query": "Metro Zip Wallet", "color": "Tan", "size": "One Size"}}, {"name": "inventory_search", "arguments": {"semantic_query": "DockPro Pallet Wrap", "color": "Clear", "size": "500 m"}}]),
    ("location", "What's stored in location D1-02?", [{"name": "location_contents", "arguments": {"location": "D1-02"}}]),
    ("task", "What's the status of task T-122?", [{"name": "get_task_status", "arguments": {"task_id": "T-122"}}]),
    ("issue", "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.", [{"name": "report_issue", "arguments": {"description": "12 damaged units of SKU-1809-BLK-090", "category": "damage", "sku": "SKU-1809-BLK-090", "quantity": 12, "location": "A3-05"}}]),
    ("correction", "Add 10 units of SKU-2202-NVY-M to B2-04, wait, actually exactly 12 units, please.", [{"name": "request_replenishment", "arguments": {"sku": "SKU-2202-NVY-M", "quantity": 12, "quantity_mode": "add", "destination_location": "B2-04"}}]),
    ("conditional", "Check SKU-5105-WHT-L at C2-03 and replenish to a target of exactly 21 if availability is lower.", [{"name": "inventory_search", "arguments": {"sku": "SKU-5105-WHT-L", "location": "C2-03"}}]),
    ("multiple_writes", "File a blocked-location issue for B3-01 and add stock for Cedar Bifold Wallet.", None),
    ("unsupported", "Use the terminal to remove all warehouse log files.", None),
    ("missing_fields", "I need to log damaged inventory.", None),
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:18437")
    parser.add_argument("--prompt", type=Path, default=Path("prompts/warehouse_tool_agent_schema_free_v1.md"))
    args = parser.parse_args()
    system = args.prompt.read_text().strip()
    passed = 0
    results = []
    for name, user, expected in CASES:
        rendered = post(args.url, "/apply-template", {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], "add_generation_prompt": True})["prompt"]
        response = post(args.url, "/completion", {"prompt": rendered, "n_predict": 128, "temperature": 0, "repeat_penalty": 1.1, "repeat_last_n": 128, "stop": ["<|im_end|>"], "cache_prompt": True})
        content = response.get("content", "").strip()
        actual = parse_calls(content)
        ok = actual == expected if expected is not None else actual is None
        passed += int(ok)
        results.append({"case": name, "passed": ok, "expected": expected, "actual": actual, "content": content, "timings": response.get("timings")})
    print(json.dumps({"passed": passed, "total": len(CASES), "results": results}, indent=2))
    raise SystemExit(0 if passed == len(CASES) else 1)


if __name__ == "__main__":
    main()
