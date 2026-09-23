#!/usr/bin/env python3
"""Run the five locked tool prompts against the unmerged P1B LoRA."""

import json
import os
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer


ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("LFM25_BASE_MODEL", "LiquidAI/LFM2.5-350M")
ADAPTER = ROOT / "models/lfm25-350m-p1b-lora"
SYSTEM = """You are an offline warehouse tool agent on a Zebra handheld.
Use only the supplied tools. Emit at most three independent reads in one turn.
Dependent calls must wait for tool results. A write must be the only call in its
turn and is only a proposal that the app will confirm. Never emit SQL or hidden
reasoning. Ask a short clarification when required information is missing.
After read results, answer in at most two short sentences using only returned facts."""


def string(enum=None):
    value = {"type": "string"}
    if enum:
        value["enum"] = enum
    return value


def integer():
    return {"type": "integer", "minimum": 1}


def tool(name, description, properties, required=None):
    parameters = {"type": "object", "properties": properties, "additionalProperties": False}
    if required:
        parameters["required"] = required
    return {"type": "function", "function": {"name": name, "description": description, "parameters": parameters}}


TOOLS = [
    tool("inventory_search", "Search local inventory by product description, SKU, attributes, location, or required quantity.", {"semantic_query": string(), "sku": string(), "color": string(), "size": string(), "location": string(), "minimum_quantity": integer()}),
    tool("location_contents", "List or filter inventory stored at a warehouse bin, aisle, dock, staging area, or zone.", {"location": string(), "semantic_query": string()}, ["location"]),
    tool("get_task_status", "Retrieve one warehouse task or filter the current worker's assigned tasks.", {"task_id": string(), "task_type": string(["pick", "putaway", "receive", "replenish"]), "status": string(["assigned", "in_progress", "blocked", "complete"])}),
    tool("report_issue", "Propose a warehouse issue report. The app previews and confirms every write.", {"description": string(), "category": string(["damage", "discrepancy", "blocked_location", "general"]), "semantic_query": string(), "sku": string(), "quantity": integer(), "location": string(), "task_id": string()}, ["description", "category"]),
    tool("request_replenishment", "Propose replenishment to a destination. The app previews and confirms every write.", {"semantic_query": string(), "sku": string(), "quantity": integer(), "quantity_mode": string(["add", "target_level"]), "destination_location": string(), "reason": string(), "task_id": string()}, ["quantity", "quantity_mode", "destination_location"]),
]

CASES = {
    "inventory_search": "Check how many Blue TrailBlaze GTX size 10.5 are available.",
    "location_contents": "What's in D3-01?",
    "get_task_status": "What's the status of task T-122?",
    "report_issue": "Generate a damage report for exactly 12 units of SKU-1809-BLK-090 at A3-05.",
    "request_replenishment": "Can you add exactly 8 units of SKU-4103-BLK-100 to C1-02?",
}


tokenizer = AutoTokenizer.from_pretrained(BASE)
base = AutoModelForCausalLM.from_pretrained(BASE, dtype=torch.float32)
model = PeftModel.from_pretrained(base, ADAPTER)
if os.environ.get("MERGE_LORA") == "1":
    model = model.merge_and_unload()
save_dir = os.environ.get("SAVE_MERGED_DIR")
if save_dir:
    model.save_pretrained(save_dir, safe_serialization=True)
    tokenizer.save_pretrained(save_dir)
    if os.environ.get("SAVE_ONLY") == "1":
        raise SystemExit(0)
model.eval()

for expected, prompt in CASES.items():
    rendered = tokenizer.apply_chat_template(
        [{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}],
        tools=TOOLS,
        add_generation_prompt=True,
        tokenize=False,
    )
    inputs = tokenizer(rendered, return_tensors="pt", add_special_tokens=False)
    with torch.inference_mode():
        output = model.generate(**inputs, do_sample=False, max_new_tokens=128, use_cache=True)
    generated = tokenizer.decode(output[0, inputs.input_ids.shape[1]:], skip_special_tokens=False)
    print(json.dumps({"expected": expected, "output": generated}, ensure_ascii=False), flush=True)
