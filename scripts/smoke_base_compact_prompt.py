#!/usr/bin/env python3
"""Establish native tool-use behavior on the untouched instruction-tuned model."""

from __future__ import annotations

import json
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
BASE = "LiquidAI/LFM2.5-350M"
REVISION = "9e6c6ccf47cd318696e137d381a7ded8fe4df09f"
SYSTEM = (ROOT / "contracts/warehouse_tool_agent_native_v1.md").read_text().strip()
TOOLS = json.loads((ROOT / "contracts/warehouse_tools_v1.json").read_text())

CASES = [
    ("inventory_search", "Check how many Blue TrailBlaze GTX size 10.5 are available."),
    ("location_contents", "What's in D3-01?"),
    ("get_task_status", "What's the status of task T-122?"),
    ("report_issue", "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05."),
    ("request_replenishment", "Add exactly 8 units of SKU-4103-BLK-100 to C1-02."),
    ("multi_read", "Check both Tan Metro Zip Wallet and Clear DockPro Pallet Wrap."),
    ("clarify_issue", "Report a damaged Cedar Bifold Wallet."),
    ("clarify_replenishment", "Replenish the Northline safety vest."),
    ("reject", "What will the weather be tomorrow?"),
]


tokenizer = AutoTokenizer.from_pretrained(BASE, revision=REVISION, local_files_only=True)
model = AutoModelForCausalLM.from_pretrained(
    BASE,
    revision=REVISION,
    local_files_only=True,
    dtype=torch.float32,
).eval()

for expected, user in CASES:
    messages = [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}]
    rendered = tokenizer.apply_chat_template(
        messages,
        tools=TOOLS,
        add_generation_prompt=True,
        tokenize=False,
    )
    inputs = tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
    with torch.inference_mode():
        output = model.generate(**inputs, do_sample=False, max_new_tokens=160, use_cache=True)
    generated = tokenizer.decode(output[0, inputs.input_ids.shape[1]:], skip_special_tokens=False)
    print(json.dumps({"mode": "native_tools", "expected": expected, "prompt_tokens": inputs.input_ids.shape[1], "output": generated}, ensure_ascii=False), flush=True)
