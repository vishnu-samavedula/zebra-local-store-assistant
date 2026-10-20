#!/usr/bin/env python3
"""Local Liquid ColBERT SOP encoding and exact MaxSim candidate retrieval."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import re
import time

import sop_embeddings as shared

MODEL_ID = "LiquidAI/LFM2.5-ColBERT-350M"


def fetch(args):
    from huggingface_hub import HfApi, snapshot_download
    revision = HfApi().model_info(MODEL_ID, revision=args.revision).sha
    shared.require(bool(re.fullmatch(r"[0-9a-f]{40}", revision)), "Expected an immutable model revision")
    args.model_dir.mkdir(parents=True, exist_ok=True)
    shared.require(not any(args.model_dir.iterdir()), "Fetch requires an empty model directory")
    snapshot_download(MODEL_ID, revision=revision, local_dir=str(args.model_dir),
                      cache_dir=str(args.cache_dir),
                      allow_patterns=["*.json", "*.txt", "*.safetensors", "*.py", "*.model"])
    files = {p.relative_to(args.model_dir).as_posix(): shared.sha256(p)
             for p in sorted(args.model_dir.rglob("*"))
             if p.is_file() and ".cache" not in p.relative_to(args.model_dir).parts}
    shared.require(any(name.endswith(".safetensors") for name in files), "Weights were not downloaded")
    shared.write_json(args.model_dir / shared.IDENTITY_FILE,
                      {"model_id": MODEL_ID, "revision": revision, "files": files})
    print(json.dumps({"model_id": MODEL_ID, "revision": revision, "model_dir": str(args.model_dir),
                      "python_files_to_review": [name for name in files if name.endswith(".py")]}))


def load_model(directory, threads):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    os.environ.setdefault("HF_HOME", "/private/tmp/zebra-sop-colbert-hf")
    os.environ.setdefault("HF_MODULES_CACHE", "/private/tmp/zebra-sop-colbert-modules")
    import torch
    from sentence_transformers import MultiVectorEncoder
    shared.require(threads > 0, "Thread count must be positive")
    torch.set_num_threads(threads)
    identity = shared.read_json(directory / shared.IDENTITY_FILE)
    shared.require(identity["model_id"] == MODEL_ID, "Wrong embedding model")
    for name, expected in identity["files"].items():
        shared.require(shared.sha256(directory / name) == expected, f"Model file changed: {name}")
    config = shared.read_json(directory / "config_sentence_transformers.json")
    start = time.perf_counter()
    # Official bidirectional attention/short-convolution code reviewed before first execution.
    # It patches Transformers process-wide: keep this encoder in its own process.
    model = MultiVectorEncoder(str(directory), device="cpu", local_files_only=True,
                               trust_remote_code=True,
                               model_kwargs={"attn_implementation": "sdpa", "dtype": torch.float32})
    load_ms = (time.perf_counter() - start) * 1000
    shared.require(model.prompts == {"query": "[Q] ", "document": "[D] "}, "Unexpected token prefixes")
    shared.require(model[0].query_expansion == {"strategy": "fixed", "attend": False, "token": None, "length": 32},
                   "Unexpected query expansion")
    shared.require(model[0].document_length == 512, "Unexpected document token limit")
    return model, identity, config, load_ms


def encode(model, texts, query=False):
    import numpy as np
    task = "query" if query else "document"
    prepared = [model.prompts[task] + text for text in texts]
    lengths = [len(ids) for ids in model[0].processor(prepared, truncation=False, padding=False)["input_ids"]]
    limit = 32 if query else 512
    shared.require(all(length <= limit for length in lengths),
                   f"{task} input exceeds {limit} tokens: {lengths}; split context explicitly")
    method = model.encode_query if query else model.encode_document
    # Single-document batches avoid additional batch-dependent padding around non-causal convolutions.
    outputs = method(texts, batch_size=1, show_progress_bar=False)
    vectors = [np.asarray(output.detach().cpu().float().numpy(), dtype="<f4") for output in outputs]
    for vector in vectors:
        validate_vectors(vector)
        if query:
            shared.require(vector.shape[0] == 32, "Query expansion vectors are missing")
    return vectors, lengths


def validate_vectors(vector):
    import numpy as np
    shared.require(vector.ndim == 2 and vector.shape[0] > 0 and vector.shape[1] == 128,
                   "Expected nonempty token vectors with 128 dimensions")
    shared.require(np.isfinite(vector).all(), "Non-finite embedding")
    shared.require(np.allclose(np.linalg.norm(vector, axis=1), 1, atol=1e-5), "Token vector is not normalized")


def maxsim(query_vectors, document_vectors):
    """Sum each query token's highest similarity to any retained document token."""
    return float((query_vectors @ document_vectors.T).max(axis=1).sum())


def rank(cards, document_vectors, query_vectors, k):
    shared.require(k > 0, "k must be positive")
    candidates = [{"id": card["id"], "score": maxsim(query_vectors, vectors), "card": card}
                  for card, vectors in zip(cards, document_vectors)]
    return sorted(candidates, key=lambda row: -row["score"])[:k]


def build(args):
    import numpy as np
    cards = shared.json_lines(args.cards)
    shared.validate_cards(cards)
    source_hash, policy_hash = shared.sha256(args.cards), shared.sha256(args.policy)
    policy = shared.read_json(args.policy)
    model, identity, config, load_ms = load_model(args.model_dir, args.threads)
    start = time.perf_counter()
    vectors, lengths = encode(model, [card["embedding_text"] for card in cards])
    encode_ms = (time.perf_counter() - start) * 1000
    shared.require(shared.sha256(args.cards) == source_hash and shared.sha256(args.policy) == policy_hash,
                   "Source changed during build; retry")
    shared.require(not args.output.exists(), "Output exists; use a new directory to preserve the previous build")
    args.output.mkdir(parents=True)
    np.concatenate(vectors).tofile(args.output / "token_vectors.f32")
    shared.write_json(args.output / "cards.json", cards)
    shared.write_json(args.output / "policy.json", policy)
    offset, metadata = 0, []
    for card, vector, length in zip(cards, vectors, lengths):
        metadata.append({"id": card["id"], "version": card["version"], "input_tokens": length,
                         "vector_offset": offset, "vector_count": len(vector),
                         "embedding_text_sha256": hashlib.sha256(card["embedding_text"].encode()).hexdigest()})
        offset += len(vector)
    manifest = {
        "schema_version": 1, "retriever": "colbert", "model": identity,
        "embedding_field": "embedding_text", "encoding_config": config,
        "dimension": 128, "normalized": True, "similarity": "sum_maxsim",
        "dtype": "float32", "byte_order": "little", "layout": "concatenated_row_major_token_vectors",
        "model_dtype": "float32", "attention": "sdpa", "document_batch_size": 1,
        "document_token_limit": 512, "query_token_limit": 32,
        "cards": metadata,
        "source": {"cards_sha256": source_hash, "policy_sha256": policy_hash},
        "files": {name: shared.sha256(args.output / name) for name in ("token_vectors.f32", "cards.json", "policy.json")},
        "packages": {name: importlib.metadata.version(name) for name in
                     ("sentence-transformers", "transformers", "torch", "numpy", "huggingface-hub")},
        "timing": {"device": "development_host_cpu", "threads": args.threads,
                   "model_load_ms": load_ms, "document_encode_ms": encode_ms},
        "status": "retrieval_candidates_only_not_integrated_or_site_approved", "similarity_threshold": None,
    }
    shared.write_json(args.output / "manifest.json", manifest)
    print(json.dumps({"output": str(args.output), "cards": len(cards), "max_input_tokens": max(lengths),
                      "token_vectors": offset, "vector_bytes": (args.output / "token_vectors.f32").stat().st_size}))


def load_index(directory, source_cards, source_policy):
    import numpy as np
    manifest = shared.read_json(directory / "manifest.json")
    shared.require(manifest["schema_version"] == 1 and manifest["retriever"] == "colbert", "Unsupported index format")
    shared.require(manifest["dimension"] == 128 and manifest["normalized"] and manifest["similarity"] == "sum_maxsim",
                   "Incompatible scoring configuration")
    for name, expected in manifest["files"].items():
        shared.require(shared.sha256(directory / name) == expected, f"Index file changed: {name}")
    shared.require(shared.sha256(source_cards) == manifest["source"]["cards_sha256"], "SOP source changed; rebuild embeddings")
    shared.require(shared.sha256(source_policy) == manifest["source"]["policy_sha256"], "SOP policy changed; rebuild bundle")
    cards = shared.read_json(directory / "cards.json")
    shared.validate_cards(cards)
    shared.require([c["id"] for c in cards] == [c["id"] for c in manifest["cards"]], "Card/vector order mismatch")
    flat = np.fromfile(directory / "token_vectors.f32", dtype="<f4")
    shared.require(flat.size % 128 == 0, "Incomplete token vector")
    flat = flat.reshape(-1, 128)
    offset, vectors = 0, []
    for card in manifest["cards"]:
        shared.require(card["vector_offset"] == offset and card["vector_count"] > 0, "Invalid token-vector offsets")
        end = offset + card["vector_count"]
        shared.require(end <= len(flat), "Missing token vectors")
        vector = flat[offset:end]
        validate_vectors(vector)
        vectors.append(vector)
        offset = end
    shared.require(offset == len(flat), "Unreferenced token vectors")
    return manifest, cards, vectors


def open_retriever(args):
    manifest, cards, vectors = load_index(args.index, args.cards, args.policy)
    model, identity, config, load_ms = load_model(args.model_dir, args.threads)
    shared.require(identity == manifest["model"] and config == manifest["encoding_config"],
                   "Query encoder does not match the document embedding model")
    return model, cards, vectors, load_ms


def query(args):
    shared.require(bool(args.text.strip()), "Query cannot be blank")
    model, cards, vectors, load_ms = open_retriever(args)
    start = time.perf_counter()
    query_vectors, lengths = encode(model, [args.text], query=True)
    candidates = rank(cards, vectors, query_vectors[0], args.k)
    print(json.dumps({"query": args.text, "input_tokens": lengths[0], "query_vectors": len(query_vectors[0]),
                      "candidates": candidates, "candidate_only": True, "applicability_verified": False,
                      "no_match_threshold": None, "model_load_ms": load_ms,
                      "encode_and_search_ms": (time.perf_counter() - start) * 1000}, indent=2))


def evaluate(args):
    model, cards, vectors, load_ms = open_retriever(args)
    shared.evaluate_retriever(args, cards, lambda text: encode(model, [text], query=True),
                              lambda query: rank(cards, vectors, query[0], len(cards)), load_ms)


def verify(args):
    import numpy as np
    import torch
    model, cards, vectors, _ = open_retriever(args)
    texts = ["My teammate has been coughing a lot", "Order more perfume. There is no spill or damage."]
    query_vectors, _ = encode(model, texts, query=True)
    reference = model.similarity([torch.from_numpy(v) for v in query_vectors],
                                 [torch.from_numpy(v) for v in vectors]).detach().cpu().numpy()
    actual = np.array([[maxsim(query, document) for document in vectors] for query in query_vectors])
    shared.require(np.allclose(actual, reference, rtol=1e-5, atol=1e-4), "MaxSim disagrees with the official encoder's scorer")
    print(json.dumps({"reference_scorer": "MultiVectorEncoder.similarity", "comparisons": actual.size,
                      "max_absolute_error": float(np.abs(actual - reference).max()),
                      "query_top_cards": [{"query": text, "id": rank(cards, vectors, vector, 1)[0]["id"]}
                                          for text, vector in zip(texts, query_vectors)]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sub = commands.add_parser("fetch")
    sub.add_argument("--model-dir", type=Path, required=True)
    sub.add_argument("--revision", default="main")
    sub.add_argument("--cache-dir", type=Path, default=Path("/private/tmp/zebra-sop-hf-cache"))
    sub.set_defaults(run=fetch)
    for name, function in (("build", build), ("query", query), ("evaluate", evaluate), ("verify", verify)):
        sub = commands.add_parser(name)
        sub.add_argument("--model-dir", type=Path, required=True)
        sub.add_argument("--cards", type=Path, default=shared.ROOT / "seed_data/sop_cards.jsonl")
        sub.add_argument("--policy", type=Path, default=shared.ROOT / "seed_data/sop_policy.json")
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
            sub.add_argument("--cases", type=Path, default=shared.ROOT / "sop_retrieval/eval_cases.jsonl")
            sub.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.run(args)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
