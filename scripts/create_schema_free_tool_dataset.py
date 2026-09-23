#!/usr/bin/env python3
"""Create immutable schema-free derivatives of reviewed tool-call datasets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def transform(source_dir: Path, output_dir: Path, prompt_path: Path) -> None:
    source_path = source_dir / "data.parquet"
    output_path = output_dir / "data.parquet"
    table = pq.read_table(source_path)
    if "messages" not in table.column_names or "tools" not in table.column_names:
        raise ValueError(f"{source_path} must contain messages and tools columns")

    system_prompt = prompt_path.read_text(encoding="utf-8").strip()
    transformed_messages: list[str] = []
    removed_message_schemas = 0
    for row_index in range(len(table)):
        raw_messages = table["messages"][row_index].as_py()
        messages = json.loads(raw_messages) if isinstance(raw_messages, str) else raw_messages
        system_messages = [message for message in messages if message.get("role") == "system"]
        if len(system_messages) != 1:
            raise ValueError(f"row {row_index} must have exactly one system message")
        for message in messages:
            if message.pop("tools", None) is not None:
                removed_message_schemas += 1
        system_messages[0]["content"] = system_prompt
        transformed_messages.append(json.dumps(messages, ensure_ascii=False))

    messages_index = table.schema.get_field_index("messages")
    transformed = table.set_column(
        messages_index,
        pa.field("messages", pa.string()),
        pa.array(transformed_messages, type=pa.string()),
    ).drop(["tools"])

    if "tools" in transformed.column_names:
        raise ValueError("request-level tools column was not removed")
    for row_index, raw_messages in enumerate(transformed["messages"].to_pylist()):
        messages = json.loads(raw_messages)
        if any("tools" in message for message in messages):
            raise ValueError(f"row {row_index} still embeds a tool schema")
        if messages[0].get("content") != system_prompt:
            raise ValueError(f"row {row_index} does not use the frozen system contract")

    output_dir.mkdir(parents=True, exist_ok=False)
    pq.write_table(transformed, output_path)
    for filename in ("scores.parquet", "summary.json"):
        source_metadata = source_dir / filename
        if source_metadata.exists():
            shutil.copy2(source_metadata, output_dir / filename)

    now = datetime.now(timezone.utc).isoformat()
    data_sha = hashlib.sha256(output_path.read_bytes()).hexdigest()
    prompt_sha = hashlib.sha256(prompt_path.read_bytes()).hexdigest()
    manifest = {
        "schema_version": 2,
        "name": output_dir.name,
        "channel": "main",
        "current": {
            "object_sha": data_sha,
            "purpose": "training",
            "rows": len(transformed),
            "created_at": now,
        },
        "history": [],
        "updated_at": now,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    provenance = {
        "schema_version": 1,
        "transform": "remove_all_tool_schemas_and_freeze_system_contract",
        "source": str(source_path.resolve()),
        "source_rows": len(table),
        "output_rows": len(transformed),
        "removed_request_tools_column": True,
        "removed_message_tool_fields": removed_message_schemas,
        "system_prompt": str(prompt_path.resolve()),
        "system_prompt_sha256": prompt_sha,
        "output_sha256": data_sha,
        "created_at": now,
    }
    (output_dir / "schema_free_transform.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--prompt", required=True, type=Path)
    args = parser.parse_args()
    transform(args.source_dir, args.output_dir, args.prompt)


if __name__ == "__main__":
    main()
