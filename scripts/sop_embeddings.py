#!/usr/bin/env python3
"""Build and inspect local BGE SOP candidates; does not authorize guidance or actions."""
import argparse
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import re
import statistics
import time

ROOT = Path(__file__).resolve().parents[1]
MODEL_ID = "BAAI/bge-small-en-v1.5"
QUERY_PREFIX = "Represent this sentence for searching relevant passages: "
IDENTITY_FILE = "sop_model_identity.json"


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def json_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_cards(cards):
    require(bool(cards), "SOP source is empty")
    ids = []
    for card in cards:
        for key in ("id", "title", "type", "status", "version", "embedding_text", "guidance", "escalation"):
            require(bool(card.get(key)), f"Missing {key} in {card.get('id', 'unknown card')}")
        require(isinstance(card["embedding_text"], str), "embedding_text must be text")
        require(card["type"] in {"incident", "worker_care", "assistance"}, "Unknown SOP type")
        ids.append(card["id"])
    require(len(ids) == len(set(ids)), "Duplicate SOP IDs")


def fetch(args):
    # This is the only command allowed to access the network.
    from huggingface_hub import HfApi, snapshot_download
    revision = HfApi().model_info(MODEL_ID, revision=args.revision).sha
    require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "Expected an immutable model revision")
    args.model_dir.mkdir(parents=True, exist_ok=True)
    require(not any(args.model_dir.iterdir()), "Fetch requires an empty model directory")
    snapshot_download(
        MODEL_ID, revision=revision, local_dir=str(args.model_dir),
        cache_dir=str(args.cache_dir),
        allow_patterns=["*.json", "*.txt", "model.safetensors", "1_Pooling/*.json"],
    )
    files = {
        p.relative_to(args.model_dir).as_posix(): sha256(p)
        for p in sorted(args.model_dir.rglob("*"))
        if p.is_file() and ".cache" not in p.relative_to(args.model_dir).parts
    }
    require("model.safetensors" in files, "Model weights were not downloaded")
    write_json(args.model_dir / IDENTITY_FILE, {"model_id": MODEL_ID, "revision": revision, "files": files})
    print(json.dumps({"model_id": MODEL_ID, "revision": revision, "model_dir": str(args.model_dir)}))


def load_model(directory, threads):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    import torch
    from sentence_transformers import SentenceTransformer
    require(threads > 0, "Thread count must be positive")
    torch.set_num_threads(threads)
    identity = read_json(directory / IDENTITY_FILE)
    require(identity["model_id"] == MODEL_ID, "Wrong embedding model")
    for filename, expected in identity["files"].items():
        require(sha256(directory / filename) == expected, f"Model file changed: {filename}")
    start = time.perf_counter()
    model = SentenceTransformer(str(directory), device="cpu", local_files_only=True, trust_remote_code=False)
    require(model.get_sentence_embedding_dimension() == 384, "Unexpected embedding dimension")
    require(model.max_seq_length == 512, "Unexpected token limit")
    return model, identity, (time.perf_counter() - start) * 1000


def token_lengths(model, texts):
    return [len(ids) for ids in model.tokenizer(texts, truncation=False, padding=False)["input_ids"]]


def encode(model, texts, query=False):
    import numpy as np
    prepared = [QUERY_PREFIX + text if query else text for text in texts]
    lengths = token_lengths(model, prepared)
    require(all(length <= model.max_seq_length for length in lengths), f"Input exceeds {model.max_seq_length} tokens: {lengths}")
    vectors = model.encode(prepared, normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)
    vectors = np.asarray(vectors, dtype="<f4")
    require(np.isfinite(vectors).all(), "Non-finite embedding")
    require(np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5), "Embedding is not unit normalized")
    return vectors, lengths


def build(args):
    cards = json_lines(args.cards)
    validate_cards(cards)
    source_hash = sha256(args.cards)
    policy_hash = sha256(args.policy)
    policy = read_json(args.policy)
    model, identity, load_ms = load_model(args.model_dir, args.threads)
    start = time.perf_counter()
    vectors, lengths = encode(model, [card["embedding_text"] for card in cards])
    encode_ms = (time.perf_counter() - start) * 1000
    require(sha256(args.cards) == source_hash and sha256(args.policy) == policy_hash, "Source changed during build; retry")
    require(not args.output.exists(), "Output exists; use a new directory to preserve the previous build")
    args.output.mkdir(parents=True)
    vectors.tofile(args.output / "vectors.f32")
    write_json(args.output / "cards.json", cards)
    # Policy is packaged separately and must be loaded independently of retrieval.
    write_json(args.output / "policy.json", policy)
    manifest = {
        "schema_version": 1, "model": identity, "embedding_field": "embedding_text",
        "query_prefix": QUERY_PREFIX, "document_prefix": "", "normalized": True,
        "dimension": 384, "dtype": "float32", "byte_order": "little", "layout": "row_major",
        "token_limit": model.max_seq_length, "pooling": "CLS",
        "cards": [{"id": c["id"], "version": c["version"], "tokens": n,
                   "embedding_text_sha256": hashlib.sha256(c["embedding_text"].encode()).hexdigest()}
                  for c, n in zip(cards, lengths)],
        "source": {"cards_sha256": source_hash, "policy_sha256": policy_hash},
        "files": {name: sha256(args.output / name) for name in ("vectors.f32", "cards.json", "policy.json")},
        "packages": {name: importlib.metadata.version(name) for name in
                     ("sentence-transformers", "transformers", "torch", "numpy", "huggingface-hub")},
        "timing": {"device": "development_host_cpu", "threads": args.threads,
                   "model_load_ms": load_ms, "document_encode_ms": encode_ms},
        "status": "retrieval_candidates_only_not_integrated_or_site_approved",
        "similarity_threshold": None,
    }
    write_json(args.output / "manifest.json", manifest)
    print(json.dumps({"output": str(args.output), "cards": len(cards), "max_tokens": max(lengths),
                      "vector_bytes": (args.output / "vectors.f32").stat().st_size}))


def load_index(directory, source_cards, source_policy):
    import numpy as np
    manifest = read_json(directory / "manifest.json")
    require(manifest["schema_version"] == 1 and manifest["dimension"] == 384, "Unsupported index format")
    require(manifest["query_prefix"] == QUERY_PREFIX and manifest["normalized"], "Incompatible encoding configuration")
    for name, expected in manifest["files"].items():
        require(sha256(directory / name) == expected, f"Index file changed: {name}")
    require(sha256(source_cards) == manifest["source"]["cards_sha256"], "SOP source changed; rebuild embeddings")
    require(sha256(source_policy) == manifest["source"]["policy_sha256"], "SOP policy changed; rebuild bundle")
    cards = read_json(directory / "cards.json")
    validate_cards(cards)
    require([c["id"] for c in cards] == [c["id"] for c in manifest["cards"]], "Card/vector order mismatch")
    vectors = np.fromfile(directory / "vectors.f32", dtype="<f4")
    require(vectors.size == len(cards) * 384, "Wrong vector count")
    vectors = vectors.reshape(len(cards), 384)
    require(np.isfinite(vectors).all(), "Non-finite index")
    require(np.allclose(np.linalg.norm(vectors, axis=1), 1, atol=1e-5), "Index is not normalized")
    return manifest, cards, vectors


def rank(cards, vectors, query_vector, k):
    import numpy as np
    require(k > 0, "k must be positive")
    scores = vectors @ query_vector
    order = np.argsort(-scores, kind="stable")[:k]
    return [{"id": cards[i]["id"], "score": float(scores[i]), "card": cards[i]} for i in order]


def open_retriever(args):
    manifest, cards, vectors = load_index(args.index, args.cards, args.policy)
    model, identity, load_ms = load_model(args.model_dir, args.threads)
    require(identity == manifest["model"], "Query model does not match the document embedding model")
    return model, cards, vectors, load_ms


def query(args):
    require(bool(args.text.strip()), "Query cannot be blank")
    model, cards, vectors, load_ms = open_retriever(args)
    start = time.perf_counter()
    query_vector, lengths = encode(model, [args.text], query=True)
    candidates = rank(cards, vectors, query_vector[0], args.k)
    print(json.dumps({"query": args.text, "tokens": lengths[0], "candidates": candidates,
                      "candidate_only": True, "applicability_verified": False,
                      "no_match_threshold": None, "model_load_ms": load_ms,
                      "encode_and_search_ms": (time.perf_counter() - start) * 1000}, indent=2))


def evaluate(args):
    model, cards, vectors, load_ms = open_retriever(args)
    evaluate_retriever(args, cards, lambda text: encode(model, [text], query=True),
                       lambda vector: rank(cards, vectors, vector[0], len(cards)), load_ms)


def evaluate_retriever(args, cards, encode_query, rank_query, load_ms):
    """Use the same cases and measurements for single- and multi-vector retrieval."""
    cases = json_lines(args.cases)
    ids = {card["id"] for card in cards}
    require(bool(cases), "Evaluation cases are empty")
    require(len({c["id"] for c in cases}) == len(cases), "Duplicate evaluation IDs")
    for case in cases:
        require(bool(case["query"].strip()), "Blank evaluation query")
        require(set(case["expected_ids"]) <= ids, f"Unknown expected ID in {case['id']}")
    encode_query(cases[0]["query"])  # Warmup excluded from measured queries.
    rows = []
    for case in cases:
        start = time.perf_counter()
        query_vector, lengths = encode_query(case["query"])
        encode_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        ranked = rank_query(query_vector)
        search_ms = (time.perf_counter() - start) * 1000
        expected = set(case["expected_ids"])
        rows.append({**case, "tokens": lengths[0], "encode_ms": encode_ms, "search_ms": search_ms,
                     "ranking": [{"id": r["id"], "score": r["score"]} for r in ranked],
                     "recall": {str(k): len(expected & {r["id"] for r in ranked[:k]}) / len(expected)
                                if expected else None for k in (1, 3, 5)}})
    positives = [r for r in rows if r["expected_ids"]]
    negatives = [r for r in rows if not r["expected_ids"]]
    require(bool(positives), "Evaluation needs positive cases")
    latencies = sorted(r["encode_ms"] for r in rows)
    summary = {
        "cases": len(rows), "positive_cases": len(positives), "negative_cases": len(negatives),
        "mean_required_card_recall": {str(k): statistics.mean(r["recall"][str(k)] for r in positives) for k in (1, 3, 5)},
        "all_required_cards_retrieved": {str(k): sum(r["recall"][str(k)] == 1 for r in positives) for k in (1, 3, 5)},
        "misses_at_3": [r["id"] for r in positives if r["recall"]["3"] < 1],
        "negative_top_scores": [{"case_id": r["id"], "card_id": r["ranking"][0]["id"],
                                 "score": r["ranking"][0]["score"]} for r in negatives],
        "timing": {"device": "development_host_cpu", "threads": args.threads,
                   "model_load_ms": load_ms, "warm_query_encode_median_ms": statistics.median(latencies),
                   "warm_query_encode_p95_ms": latencies[math.ceil(len(latencies) * .95) - 1],
                   "search_median_ms": statistics.median(r["search_ms"] for r in rows)},
        "limitations": ["Synthetic development cases; labels are proposed, not retailer-reviewed.",
                        "Ranking evaluation only; no guidance generation, safety routing, or Android timing.",
                        "Negative cases have score diagnostics only; no abstention threshold is calibrated."],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    write_json(args.report, {"summary": summary, "cases_sha256": sha256(args.cases),
                             "index_manifest_sha256": sha256(args.index / "manifest.json"), "results": rows})
    print(json.dumps(summary, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    fetch_parser = commands.add_parser("fetch", help="Download official model and pin its resolved commit")
    fetch_parser.add_argument("--model-dir", type=Path, required=True)
    fetch_parser.add_argument("--cache-dir", type=Path, default=Path("/private/tmp/zebra-sop-hf-cache"))
    fetch_parser.add_argument("--revision", default="main")
    fetch_parser.set_defaults(run=fetch)
    for name, function in (("build", build), ("query", query), ("evaluate", evaluate)):
        sub = commands.add_parser(name)
        sub.add_argument("--model-dir", type=Path, required=True)
        sub.add_argument("--cards", type=Path, default=ROOT / "seed_data/sop_cards.jsonl")
        sub.add_argument("--policy", type=Path, default=ROOT / "seed_data/sop_policy.json")
        sub.add_argument("--threads", type=int, default=4)
        sub.set_defaults(run=function)
        if name == "build":
            sub.add_argument("--output", type=Path, required=True)
        else:
            sub.add_argument("--index", type=Path, required=True)
        if name == "query":
            sub.add_argument("--text", required=True)
            sub.add_argument("--k", type=int, default=3)
        if name == "evaluate":
            sub.add_argument("--cases", type=Path, default=ROOT / "sop_retrieval/eval_cases.jsonl")
            sub.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.run(args)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
