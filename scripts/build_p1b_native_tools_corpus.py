#!/usr/bin/env python3
"""Rebuild the reviewed v5 corpus for LFM2.5's native tool-template contract."""

from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pyarrow as pa
import pyarrow.parquet as pq
from transformers import AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "datasets/warehouse_tool_agent_v5_compact_final_v5/data.parquet"
FINAL = ROOT / "datasets/warehouse_tool_agent_native_v1_final_v2"
TRAIN = ROOT / "datasets/warehouse_tool_agent_native_v1_train_v2"
EVAL = ROOT / "datasets/warehouse_tool_agent_native_v1_eval_v2"
PROMPT_PATH = ROOT / "contracts/warehouse_tool_agent_native_v1.md"
TOOLS_PATH = ROOT / "contracts/warehouse_tools_v1.json"
BASE = "LiquidAI/LFM2.5-350M"
REVISION = "9e6c6ccf47cd318696e137d381a7ded8fe4df09f"


def normalize_prompt(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.casefold()).strip()


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
    }
    (path / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main() -> None:
    prompt = PROMPT_PATH.read_text().strip()
    tools = json.loads(TOOLS_PATH.read_text())
    tools_json = json.dumps(tools, ensure_ascii=False, separators=(",", ":"))
    tool_contract = {
        item["function"]["name"]: item["function"]["parameters"]
        for item in tools
    }
    source_rows = pq.read_table(SOURCE).to_pylist()
    rebuilt: list[dict[str, Any]] = []
    removed_reasons = 0

    for row in source_rows:
        messages = json.loads(row["messages"])
        system = [message for message in messages if message.get("role") == "system"]
        if len(system) != 1:
            raise ValueError(f"{row['sample_id']}: expected exactly one system message")
        system[0]["content"] = prompt
        system[0].pop("tools", None)

        for message in messages:
            for call in message.get("tool_calls") or []:
                function = call.get("function") or {}
                name = function.get("name")
                if name not in tool_contract:
                    raise ValueError(f"{row['sample_id']}: unknown tool {name}")
                arguments = function.get("arguments", {})
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                if not isinstance(arguments, dict):
                    raise ValueError(f"{row['sample_id']}: arguments are not a mapping")
                arguments = {
                    key: value
                    for key, value in arguments.items()
                    if value is not None and value != ""
                }
                if name == "request_replenishment" and "reason" in arguments:
                    arguments.pop("reason")
                    removed_reasons += 1
                allowed = set(tool_contract[name]["properties"])
                unknown = set(arguments) - allowed
                if unknown:
                    raise ValueError(f"{row['sample_id']}: unknown arguments {sorted(unknown)}")
                function["arguments"] = arguments

        user = next(message["content"] for message in messages if message.get("role") == "user")
        rebuilt_row = dict(row)
        rebuilt_row["messages"] = json.dumps(messages, ensure_ascii=False)
        rebuilt_row["tools"] = tools_json
        rebuilt_row["normalized_user"] = normalize_prompt(user)
        rebuilt.append(rebuilt_row)

    if len(rebuilt) != 600:
        raise AssertionError(f"expected 600 rows, found {len(rebuilt)}")
    prompts = [row["normalized_user"] for row in rebuilt]
    if len(prompts) != len(set(prompts)):
        raise AssertionError("duplicate normalized worker prompts")

    tokenizer = AutoTokenizer.from_pretrained(
        BASE,
        revision=REVISION,
        local_files_only=True,
    )
    lengths: list[int] = []
    tool_rows = 0
    for row in rebuilt:
        messages = json.loads(row["messages"])
        encoded = tokenizer.apply_chat_template(
            messages,
            tools=tools,
            tokenize=True,
            return_dict=True,
        )
        lengths.append(len(encoded["input_ids"]))
        if any(message.get("tool_calls") for message in messages):
            tool_rows += 1
            rendered = tokenizer.apply_chat_template(messages, tools=tools, tokenize=False)
            if "<|tool_call_start|>" not in rendered or "<|tool_call_end|>" not in rendered:
                raise AssertionError(f"{row['sample_id']}: native call markers missing")

    train_rows = [{k: v for k, v in row.items() if k != "normalized_user"} for row in rebuilt if row["split"] == "train"]
    eval_rows = [{k: v for k, v in row.items() if k != "normalized_user"} for row in rebuilt if row["split"] == "eval"]
    final_rows = [{k: v for k, v in row.items() if k != "normalized_user"} for row in rebuilt]
    if (len(train_rows), len(eval_rows)) != (500, 100):
        raise AssertionError(f"unexpected split sizes: {len(train_rows)}, {len(eval_rows)}")

    write_dataset(FINAL, final_rows, "review")
    write_dataset(TRAIN, train_rows, "training")
    write_dataset(EVAL, eval_rows, "validation")
    print(json.dumps({
        "rows": len(rebuilt),
        "train": len(train_rows),
        "eval": len(eval_rows),
        "tool_rows": tool_rows,
        "removed_unsaid_reasons": removed_reasons,
        "token_length": {
            "min": min(lengths),
            "median": sorted(lengths)[len(lengths) // 2],
            "max": max(lengths),
        },
        "buckets": Counter(row["scenario_bucket"] for row in rebuilt),
    }, indent=2))


if __name__ == "__main__":
    main()
