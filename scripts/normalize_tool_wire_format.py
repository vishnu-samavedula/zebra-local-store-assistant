#!/usr/bin/env python3
"""Create runtime-safe copies of LQH tool-calling parquet datasets.

LQH's generator stores tool definitions both on the system message and in a
dedicated top-level ``tools`` column. OpenAI-compatible inference servers
expect tools only at request level, and reject the duplicated message-level
definitions. This transform removes only the message-level copy and keeps the
canonical source dataset unchanged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq


def _tool_names(tools: list[dict]) -> list[str]:
    return [tool["function"]["name"] for tool in tools]


def normalize(source_dir: Path, output_dir: Path) -> None:
    source_path = source_dir / "data.parquet"
    output_path = output_dir / "data.parquet"
    table = pq.read_table(source_path)
    if "messages" not in table.column_names or "tools" not in table.column_names:
        raise ValueError(f"{source_path} must contain messages and tools columns")

    normalized_messages: list[str] = []
    stripped_tool_fields = 0
    for row_index in range(len(table)):
        raw_messages = table["messages"][row_index].as_py()
        raw_tools = table["tools"][row_index].as_py()
        messages = json.loads(raw_messages) if isinstance(raw_messages, str) else raw_messages
        request_tools = json.loads(raw_tools) if isinstance(raw_tools, str) else raw_tools
        if not request_tools:
            raise ValueError(f"row {row_index} has no request-level tools")

        for message in messages:
            message_tools = message.pop("tools", None)
            if message_tools is None:
                continue
            if _tool_names(message_tools) != _tool_names(request_tools):
                raise ValueError(
                    f"row {row_index} message/request tool definitions do not match"
                )
            stripped_tool_fields += 1
        normalized_messages.append(json.dumps(messages, ensure_ascii=False))

    if stripped_tool_fields != len(table):
        raise ValueError(
            f"expected one message-level tools field per row; found "
            f"{stripped_tool_fields} across {len(table)} rows"
        )

    messages_index = table.schema.get_field_index("messages")
    normalized = table.set_column(
        messages_index,
        pa.field("messages", pa.string()),
        pa.array(normalized_messages, type=pa.string()),
    )
    output_dir.mkdir(parents=True, exist_ok=False)
    pq.write_table(normalized, output_path)

    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    provenance = {
        "schema_version": 1,
        "transform": "strip_message_level_tools_keep_request_level_tools",
        "reason": "OpenAI-compatible requests require tool definitions at request level only",
        "source": str(source_path.resolve()),
        "source_rows": len(table),
        "output_rows": len(normalized),
        "stripped_message_tool_fields": stripped_tool_fields,
        "output_sha256": digest,
        "created_at": datetime.now(timezone.utc).isoformat(),
    }
    (output_dir / "normalization.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    normalize(args.source_dir, args.output_dir)


if __name__ == "__main__":
    main()
