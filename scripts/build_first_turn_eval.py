#!/usr/bin/env python3
"""Build a locked first-turn release gate from the reviewed held-out set."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


LOCKED_INDICES = [0, 1, 10, 20, 21, 30, 31, 40, 46, 50, 60, 61, 70, 76, 80, 89, 90, 91, 95, 98]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()

    table = pq.read_table(args.source_dir / "data.parquet")
    rows = table.to_pylist()
    output_rows = []
    for source_index in LOCKED_INDICES:
        row = dict(rows[source_index])
        messages = json.loads(row["messages"])
        first_assistant = next(
            index for index, message in enumerate(messages) if message.get("role") == "assistant"
        )
        row["messages"] = json.dumps(messages[: first_assistant + 1], ensure_ascii=False)
        output_rows.append(row)

    output = pa.Table.from_pylist(output_rows, schema=table.schema)
    args.output_dir.mkdir(parents=True, exist_ok=False)
    output_path = args.output_dir / "data.parquet"
    pq.write_table(output, output_path)
    now = datetime.now(timezone.utc).isoformat()
    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    manifest = {
        "schema_version": 2,
        "name": args.output_dir.name,
        "channel": "release_gate",
        "current": {
            "object_sha": digest,
            "purpose": "evaluation",
            "rows": len(output),
            "created_at": now,
        },
        "history": [],
        "updated_at": now,
        "provenance": {
            "source": str((args.source_dir / "data.parquet").resolve()),
            "source_indices": LOCKED_INDICES,
            "transform": "truncate_each_conversation_after_first_assistant_target",
        },
    }
    (args.output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
