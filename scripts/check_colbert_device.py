"""Evaluate an existing device ColBERT endpoint against the local procedure index."""
import argparse
import json
import statistics
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
import sop_colbert


def post(url, path, payload):
    request = urllib.request.Request(url + path, data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:18088")
    parser.add_argument("--output", type=Path, default=Path("sop_retrieval/generated/device-check.json"))
    args = parser.parse_args()
    _, cards, vectors = sop_colbert.load_index(
        Path("sop_retrieval/generated/colbert-baseline"),
        Path("seed_data/sop_cards.jsonl"), Path("seed_data/sop_policy.json"))
    cases = [json.loads(line) for line in Path("sop_retrieval/eval_cases.jsonl").read_text().splitlines()]
    results = []
    for case in cases:
        started = time.perf_counter()
        tokens = post(args.url, "/tokenize", {"content": "[Q] " + case["query"], "add_special": True})["tokens"]
        if len(tokens) > 32:
            raise ValueError("Query exceeds 32 tokens; do not silently truncate")
        tokens += [7] * (32 - len(tokens))
        result = post(args.url, "/embedding", {"content": tokens})
        embedding = np.asarray(result[0]["embedding"], dtype=np.float32)
        assert embedding.shape == (32, 128) and np.isfinite(embedding).all()
        embedding /= np.linalg.norm(embedding, axis=1, keepdims=True)
        ranked = sop_colbert.rank(cards, vectors, embedding, 3)
        ids = [item["id"] for item in ranked]
        results.append({"id": case["id"], "expected": case["expected_ids"], "candidate_ids": ids,
                        "positive_pass": set(case["expected_ids"]) <= set(ids) if case["expected_ids"] else None,
                        "elapsed_ms": (time.perf_counter() - started) * 1000})
    positives = [r for r in results if r["expected"]]
    report = {"positive_passes": sum(r["positive_pass"] for r in positives), "positive_cases": len(positives),
              "negative_cases": len(results) - len(positives), "candidate_only": True,
              "median_ms": statistics.median(r["elapsed_ms"] for r in results), "cases": results}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "cases"}))


if __name__ == "__main__":
    main()
