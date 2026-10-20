#!/usr/bin/env python3
"""Count supplied warehouse examples through an existing server; no generation."""

import argparse
import hashlib
import json
from pathlib import Path
import statistics
import sys
from urllib.request import Request as HttpRequest, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from warehouse_harness import Harness
from warehouse_harness.engine import Request
from warehouse_harness.native_tools import render_marked_calls
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    cache = {}

    def post(route, payload):
        request = HttpRequest(args.server.rstrip("/") + route,
                              json.dumps(payload).encode(),
                              {"Content-Type": "application/json"})
        with urlopen(request, timeout=20) as response:
            return json.load(response)

    def count(text):
        if text not in cache:
            cache[text] = len(post("/tokenize", {"content": text,
                                                "add_special": False})["tokens"])
        return cache[text]

    def prompt_count(messages):
        rendered = post("/apply-template", {"messages": messages,
                                            "add_generation_prompt": True})["prompt"]
        return count(rendered)

    source = ROOT / "contracts/reference_journeys.jsonl"
    fixtures = [json.loads(line) for line in source.read_text().splitlines() if line.strip()]
    outputs, inputs, gaps, controls = [], [], [], []
    for fixture in fixtures:
        store = Store(ROOT / "seed_data/warehouse_catalog.jsonl",
                      tasks=ROOT / "warehouse_harness/demo_tasks.jsonl")
        harness = Harness(store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))
        initial = fixture["initial_context"]
        if initial.get("focused_call"):
            call = initial["focused_call"]
            harness.active = Request(call["tool"], call["arguments"].copy(),
                                     questions=initial.get("clarification_questions_asked", 0))
        for index, turn in enumerate(fixture["turns"]):
            envelope = turn["expected_model_output"]
            label = {"journey": fixture["id"], "turn": index + 1}
            context = harness.context()
            history = context.pop("model_history")
            state = json.dumps({"harness_state": context}, separators=(",", ":"))
            # Match Android's final 24-message selection, including state.
            messages = (history + [{"role": "tool", "content": state}])[-24:]
            messages.append({"role": "user", "content": turn["worker_text"]})
            compact_context = dict(context)
            compact_context.pop("full_request_turns", None)
            compact_messages = (history + [{"role": "tool", "content": json.dumps(
                {"harness_state": compact_context}, separators=(",", ":"))}])[-24:]
            compact_messages.append(messages[-1])
            inputs.append({**label, "text_prompt_tokens": prompt_count(messages),
                           "state_content_tokens": count(state),
                           "without_full_request_turns_tokens": prompt_count(compact_messages),
                           "note": "Supplied reference history, including legacy control/correction envelopes; not model-generated."})
            if envelope["action"] == "propose":
                output = render_marked_calls(envelope["calls"])
                outputs.append({**label, "calls": len(envelope["calls"]),
                                "tokens": count(output), "output": output})
            else:
                output = envelope
                if any(call.get("clear_fields") for call in envelope["calls"]):
                    withdrawal = harness.withdrawal_output(turn["worker_text"])
                    if withdrawal is None:
                        gaps.append({**label, "issue": "Withdrawal is not recognized by the harness."})
                    else:
                        output = withdrawal
                        controls.append({**label, "operation": "harness_owned_field_withdrawal",
                                         "model_inference_required": False})
            harness.handle(turn["worker_text"], output)
        store.close()

    # Budget probe only: three copies of the longest valid single-call example.
    single = max((row for row in outputs if row["calls"] == 1), key=lambda row: row["tokens"])
    fixture = next(item for item in fixtures if item["id"] == single["journey"])
    call = fixture["turns"][single["turn"] - 1]["expected_model_output"]["calls"][0]
    triple = render_marked_calls([call] * 3)
    values = [row["tokens"] for row in outputs]
    report = {
        "kind": "token_budget_audit_not_generation_benchmark",
        "tokenizer": "Currently installed excavator reference server; recount with warehouse checkpoint tokenizer before deployment.",
        "fixture_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "limits": {"context_capacity": 4096, "current_decode_ceiling": 384,
                   "history_message_limit": 24, "prompt_cache_enabled": False},
        "native_proposal_summary": {"examples": len(values), "minimum": min(values),
                                    "median": statistics.median(values), "maximum": max(values)},
        "three_call_size_probe": {"tokens": count(triple),
                                  "note": "Synthetic repetition for size only, not a warehouse journey or worst-case bound."},
        "native_proposals": outputs, "reference_text_inputs": inputs, "contract_gaps": gaps,
        "harness_owned_withdrawals": controls,
        "limitations": ["No inference, accuracy or latency benchmark performed.",
                        "Audio token overhead is excluded.",
                        "Examples do not bound arbitrary free-text argument lengths.",
                        "Removing full_request_turns is measured only, not implemented: active evidence can outlive retained history."]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"output": str(args.output),
                      "native_proposal_summary": report["native_proposal_summary"],
                      "three_call_size_probe": report["three_call_size_probe"],
                      "text_prompt_range": [min(row["text_prompt_tokens"] for row in inputs),
                                            max(row["text_prompt_tokens"] for row in inputs)],
                      "maximum_duplicate_turn_saving": max(
                          row["text_prompt_tokens"] - row["without_full_request_turns_tokens"] for row in inputs),
                      "contract_gaps": gaps}, indent=2))


if __name__ == "__main__":
    main()
