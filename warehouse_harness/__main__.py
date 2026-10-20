"""JSON-lines runner: transcript + marked calls or model envelope in, outcome out."""

import argparse
import json
from pathlib import Path
import sys

from .colbert import ColbertRetriever
from .contracts import ROOT, parse
from .engine import Harness
from .procedures import Procedures
from .store import Store


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default=":memory:", help="Explicit local ticket database, or ephemeral memory")
    parser.add_argument("--tasks", type=Path, default=ROOT / "warehouse_harness/demo_tasks.jsonl")
    parser.add_argument("--worker", default="demo-worker")
    parser.add_argument("--colbert-python", type=Path, help="Python environment containing the installed ColBERT dependencies")
    parser.add_argument("--colbert-model", type=Path, default=ROOT / "sop_retrieval/models/LFM2.5-ColBERT-350M")
    parser.add_argument("--colbert-index", type=Path, default=ROOT / "sop_retrieval/generated/colbert-baseline")
    args = parser.parse_args()
    retriever = ColbertRetriever(args.colbert_python, args.colbert_model, args.colbert_index) if args.colbert_python else None
    store = Store(ROOT / "seed_data/warehouse_catalog.jsonl", args.database, args.tasks)
    snapshot_time = min((t["updated_at"] for t in store.tasks), default=None)
    harness = Harness(store, Procedures(ROOT / "seed_data/sop_cards.jsonl", retriever), args.worker, snapshot_time)
    try:
        for line in sys.stdin:
            try:
                request = parse(line)
                if request.get("new_request"):
                    harness.new_request()
                result = harness.handle(request["transcript"], request["model_output"],
                                        input_mode=request.get("input_mode", "text"),
                                        finish_reason=request.get("finish_reason", "stop"),
                                        event_id=request.get("event_id"),
                                        confirmed_preview=request.get("confirmed_preview"))
                if request.get("include_context"):
                    result["model_context"] = harness.context()
                print(json.dumps(result), flush=True)
            except (ValueError, KeyError, TypeError) as error:
                print(json.dumps({"kind": "failed", "error": str(error), "saved": False}), flush=True)
    finally:
        store.close()
        if retriever:
            retriever.close()


if __name__ == "__main__":
    main()
