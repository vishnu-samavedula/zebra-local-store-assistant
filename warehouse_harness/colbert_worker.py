"""Private local worker protocol; no network calls or model downloads."""

import contextlib
import json
from pathlib import Path
import re
import sys
from types import SimpleNamespace


def main():
    root = Path(__file__).resolve().parents[1]
    sys.path.insert(0, str(root / "scripts"))
    try:
        with contextlib.redirect_stdout(sys.stderr):
            import sop_colbert
            args = SimpleNamespace(model_dir=Path(sys.argv[1]), index=Path(sys.argv[2]),
                                   cards=root / "seed_data/sop_cards.jsonl",
                                   policy=root / "seed_data/sop_policy.json", threads=4)
            model, cards, vectors, load_ms = sop_colbert.open_retriever(args)
        print(json.dumps({"ready": True, "model_id": sop_colbert.MODEL_ID, "model_load_ms": load_ms}), flush=True)
    except Exception as error:
        print(json.dumps({"error": str(error)}), flush=True)
        return
    for line in sys.stdin:
        try:
            request = json.loads(line)
            text = request["text"].strip()
            if not text or not 1 <= request["k"] <= 3:
                raise ValueError("Expected a nonempty query and at most three candidates")
            # Full query first. For overlength text, split only at explicit sentence/clause
            # boundaries, keeping every clause; never truncate words or remove negation.
            try:
                with contextlib.redirect_stdout(sys.stderr):
                    query_vectors, _ = sop_colbert.encode(model, [text], query=True)
            except ValueError as error:
                if "exceeds 32 tokens" not in str(error):
                    raise
                clauses = [s.strip() for s in re.split(r"[.;!?]|\band\b", text) if s.strip()]
                if len(clauses) <= 1:
                    raise
                with contextlib.redirect_stdout(sys.stderr):
                    query_vectors, _ = sop_colbert.encode(model, clauses, query=True)
            rankings = [sop_colbert.rank(cards, vectors, query, request["k"]) for query in query_vectors]
            # Each clause gets its best candidate before extra candidates; a full-text
            # applicability check in the parent process rejects negated/resolved incidents.
            selected = []
            for rank_position in range(request["k"]):
                for ranking in rankings:
                    candidate = ranking[rank_position]
                    if candidate["id"] not in {c["id"] for c in selected}:
                        selected.append(candidate)
            reply = {"candidates": selected[:request["k"]]}
        except Exception as error:
            reply = {"error": str(error)}
        print(json.dumps(reply), flush=True)


if __name__ == "__main__":
    main()
