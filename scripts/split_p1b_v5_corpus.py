#!/usr/bin/env python3
"""Materialize P1B v5 train/eval datasets so held-out rows cannot enter SFT."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def write_split(table: pa.Table, name: str, output_dir: Path, expected: int, source: Path) -> None:
    values = table["split"].to_pylist()
    indices = [index for index, value in enumerate(values) if value == name]
    result = table.take(pa.array(indices, type=pa.int64()))
    if len(result) != expected:
        raise ValueError(f"expected {expected} {name} rows, found {len(result)}")
    output_dir.mkdir(parents=True, exist_ok=False)
    path = output_dir / "data.parquet"
    pq.write_table(result, path)
    now = datetime.now(timezone.utc).isoformat()
    manifest = {
        "schema_version": 2,
        "name": output_dir.name,
        "channel": "training/candidates" if name == "train" else "evaluation/held_out",
        "current": {
            "object_sha": hashlib.sha256(path.read_bytes()).hexdigest(),
            "purpose": "training" if name == "train" else "validation",
            "rows": len(result),
            "created_at": now,
            "is_sealed": name == "eval",
        },
        "history": [],
        "updated_at": now,
        "provenance": {
            "kind": "stratified_split",
            "source": str(source),
            "split": name,
        },
    }
    (output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"wrote {path} ({len(result)} {name} rows)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("train_dir", type=Path)
    parser.add_argument("eval_dir", type=Path)
    args = parser.parse_args()
    path = args.dataset / "data.parquet" if args.dataset.is_dir() else args.dataset
    table = pq.read_table(path)
    write_split(table, "train", args.train_dir, 500, path)
    write_split(table, "eval", args.eval_dir, 100, path)


if __name__ == "__main__":
    main()
