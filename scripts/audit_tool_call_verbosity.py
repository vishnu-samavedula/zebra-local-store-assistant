#!/usr/bin/env python3
"""Audit raw and Liquid-rendered tool targets for accidental verbosity."""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

import pyarrow.parquet as pq
from transformers import AutoTokenizer


BASE = "LiquidAI/LFM2.5-350M"
REVISION = "9e6c6ccf47cd318696e137d381a7ded8fe4df09f"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("datasets", nargs="+", type=Path)
    args = parser.parse_args()
    tokenizer = AutoTokenizer.from_pretrained(BASE, revision=REVISION, local_files_only=True)

    rows = []
    for path in args.datasets:
        rows.extend(pq.read_table(path).to_pylist())

    calls = []
    rendered_targets = []
    verbose_rendered = []
    tool_rows_with_fully_labeled_block = 0
    tool_rows_with_partially_labeled_block = 0
    tool_rows_with_unlabeled_block = 0
    for row in rows:
        messages = json.loads(row["messages"])
        tools = json.loads(row["tools"])
        for message in messages:
            for call in message.get("tool_calls") or []:
                function = call["function"]
                arguments = function["arguments"]
                if isinstance(arguments, str):
                    arguments = json.loads(arguments)
                calls.append((row["sample_id"], function["name"], arguments))

        rendered = tokenizer.apply_chat_template(messages, tools=tools, tokenize=False)
        encoded = tokenizer.apply_chat_template(
            messages,
            tools=tools,
            tokenize=True,
            return_dict=True,
            return_assistant_tokens_mask=True,
        )
        input_ids = list(encoded["input_ids"])
        assistant_mask = list(encoded["assistant_masks"])
        cursor = 0
        row_tool_mask_state = []
        while True:
            start = rendered.find("<|tool_call_start|>", cursor)
            if start < 0:
                break
            end = rendered.find("<|tool_call_end|>", start)
            if end < 0:
                raise ValueError(f"{row['sample_id']}: unterminated rendered tool call")
            target = rendered[start : end + len("<|tool_call_end|>")]
            token_count = len(tokenizer(target, add_special_tokens=False)["input_ids"])
            rendered_targets.append((row["sample_id"], token_count, target))
            if "=None" in target or ": null" in target or ":null" in target:
                verbose_rendered.append((row["sample_id"], target))
            target_ids = tokenizer(target, add_special_tokens=False)["input_ids"]
            positions = [
                index
                for index in range(len(input_ids) - len(target_ids) + 1)
                if input_ids[index : index + len(target_ids)] == target_ids
            ]
            if len(positions) != 1:
                raise ValueError(
                    f"{row['sample_id']}: expected one tokenized tool block, found {len(positions)}"
                )
            block_mask = assistant_mask[positions[0] : positions[0] + len(target_ids)]
            row_tool_mask_state.append(sum(block_mask))
            cursor = end + 1

        if row_tool_mask_state:
            labeled = sum(row_tool_mask_state)
            total = sum(
                len(tokenizer(target, add_special_tokens=False)["input_ids"])
                for sample_id, _, target in rendered_targets
                if sample_id == row["sample_id"]
            )
            if labeled == total:
                tool_rows_with_fully_labeled_block += 1
            elif labeled == 0:
                tool_rows_with_unlabeled_block += 1
            else:
                tool_rows_with_partially_labeled_block += 1

    argument_counts = Counter(len(arguments) for _, _, arguments in calls)
    null_arguments = [
        (sample_id, name, key)
        for sample_id, name, arguments in calls
        for key, value in arguments.items()
        if value is None or value == ""
    ]
    target_lengths = sorted(tokens for _, tokens, _ in rendered_targets)
    summary = {
        "rows": len(rows),
        "tool_calls": len(calls),
        "tools": Counter(name for _, name, _ in calls),
        "arguments_per_call": argument_counts,
        "null_or_empty_arguments": len(null_arguments),
        "rendered_tool_blocks": len(rendered_targets),
        "rendered_blocks_with_nulls": len(verbose_rendered),
        "tool_row_assistant_mask": {
            "fully_labeled": tool_rows_with_fully_labeled_block,
            "partially_labeled": tool_rows_with_partially_labeled_block,
            "unlabeled": tool_rows_with_unlabeled_block,
        },
        "rendered_target_tokens": {
            "min": min(target_lengths, default=0),
            "median": target_lengths[len(target_lengths) // 2] if target_lengths else 0,
            "max": max(target_lengths, default=0),
        },
        "examples": [
            {"sample_id": sample_id, "tokens": tokens, "target": target}
            for sample_id, tokens, target in rendered_targets[:5]
        ],
    }
    print(json.dumps(summary, indent=2, ensure_ascii=False, default=dict))


if __name__ == "__main__":
    main()
