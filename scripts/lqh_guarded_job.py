#!/usr/bin/env python3
"""Preflight, submit, and monitor one LQH cloud job.

The guard intentionally fixes only deterministic transport-shape problems.
It never changes training data semantics, hyperparameters, model choice, or a
budget after submission. Unknown, OOM, timeout, and learning-quality failures
stop for human review instead of entering a retry loop.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq

from normalize_tool_wire_format import normalize


SAFE_TOOLS = {"eval_hf_model", "start_training"}


def _dataset_dirs(value: Any) -> list[Path]:
    if value is None:
        return []
    values = value if isinstance(value, list) else [value]
    paths: list[Path] = []
    for item in values:
        raw = item["path"] if isinstance(item, dict) else item
        path = Path(raw)
        paths.append(path.parent if path.name == "data.parquet" else path)
    return paths


def _inspect_dataset(dataset_dir: Path) -> tuple[int, int]:
    data_path = dataset_dir / "data.parquet"
    if not data_path.is_file():
        raise ValueError(f"missing dataset: {data_path}")
    table = pq.read_table(data_path)
    if "messages" not in table.column_names:
        raise ValueError(f"{data_path} has no messages column")

    duplicate_rows = 0
    for index in range(len(table)):
        raw = table["messages"][index].as_py()
        messages = json.loads(raw) if isinstance(raw, str) else raw
        embedded = [m.get("tools") for m in messages if m.get("tools")]
        if "tools" in table.column_names:
            raw_tools = table["tools"][index].as_py()
            request_tools = json.loads(raw_tools) if isinstance(raw_tools, str) else raw_tools
        else:
            request_tools = None
        if embedded and request_tools:
            duplicate_rows += 1
        if request_tools:
            names = [tool["function"]["name"] for tool in request_tools]
            if len(names) != len(set(names)):
                raise ValueError(f"row {index} in {data_path} repeats a tool name")
    return len(table), duplicate_rows


def _replace_dataset_arg(value: Any, old: Path, new: Path) -> Any:
    if isinstance(value, list):
        return [_replace_dataset_arg(item, old, new) for item in value]
    if isinstance(value, dict):
        updated = dict(value)
        updated["path"] = str(new) if Path(value["path"]).name != "data.parquet" else str(new / "data.parquet")
        return updated if Path(value["path"]).parent == old or Path(value["path"]) == old else value
    path = Path(value)
    if path == old:
        return str(new)
    if path == old / "data.parquet":
        return str(new / "data.parquet")
    return value


def _preflight(recipe: dict[str, Any]) -> dict[str, Any]:
    tool = recipe.get("tool")
    if tool not in SAFE_TOOLS:
        raise ValueError(f"guard supports only {sorted(SAFE_TOOLS)}, got {tool!r}")
    args = dict(recipe.get("args") or {})
    run_name = args.get("run_name")
    if not run_name:
        raise ValueError("args.run_name is required for monitored jobs")
    if (Path("runs") / run_name).exists():
        raise ValueError(f"run name already exists: {run_name}")

    timeout = int(args.get("timeout_minutes", recipe.get("timeout_minutes", 0)))
    hourly = float(recipe.get("gpu_hourly_usd", 0))
    cap = float(recipe.get("max_cost_usd", 0))
    if timeout and hourly and cap:
        estimate = timeout * hourly / 60
        if estimate > cap + 1e-9:
            raise ValueError(f"estimated compute ${estimate:.2f} exceeds ${cap:.2f} job cap")

    keys = ["eval_dataset"] if tool == "eval_hf_model" else ["dataset", "eval_dataset"]
    for key in keys:
        value = args.get(key)
        for dataset_dir in _dataset_dirs(value):
            rows, duplicates = _inspect_dataset(dataset_dir)
            if duplicates:
                if not recipe.get("auto_normalize_tool_wire_format", False):
                    raise ValueError(
                        f"{dataset_dir}: {duplicates}/{rows} rows duplicate tool schemas"
                    )
                runtime_dir = dataset_dir.with_name(dataset_dir.name + "_runtime_v1")
                if not runtime_dir.exists():
                    normalize(dataset_dir, runtime_dir)
                runtime_rows, runtime_duplicates = _inspect_dataset(runtime_dir)
                if runtime_rows != rows or runtime_duplicates:
                    raise ValueError(f"normalization validation failed: {runtime_dir}")
                args[key] = _replace_dataset_arg(args[key], dataset_dir, runtime_dir)

    if tool == "start_training":
        train = {p.resolve() for p in _dataset_dirs(args.get("dataset"))}
        evaluation = {p.resolve() for p in _dataset_dirs(args.get("eval_dataset"))}
        if train & evaluation:
            raise ValueError("training and held-out eval datasets must be distinct")

    return {**recipe, "args": args}


def _call_lqh(tool: str, args: dict[str, Any], *, donate: bool = False, wait: bool = False) -> dict:
    command = ["lqh", "tool", "call", tool, "--args", json.dumps(args)]
    if donate:
        command.append("--allow-hf-donate")
    if wait:
        command.append("--wait")
    completed = subprocess.run(command, text=True, capture_output=True)
    if completed.stderr:
        print(completed.stderr, file=sys.stderr, end="")
    if completed.stdout:
        print(completed.stdout, end="", flush=True)
    if completed.returncode:
        raise RuntimeError(f"lqh {tool} exited {completed.returncode}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"lqh {tool} returned non-JSON output") from exc


def _terminal_failure(envelope: dict) -> str | None:
    text = json.dumps(envelope).lower()
    if any(marker in text for marker in ('"state": "failed"', "status: failed", "state failed")):
        if "tool names must be unique" in text:
            return "duplicate_tool_schema"
        if "out of memory" in text or "oom" in text:
            return "oom"
        if "timeout" in text or "timed out" in text:
            return "timeout"
        if "preempted" in text:
            return "preempted"
        if "orphaned" in text:
            return "orphaned"
        return "unknown"
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("recipe", type=Path)
    parser.add_argument("--execute", action="store_true")
    parsed = parser.parse_args()
    recipe = _preflight(json.loads(parsed.recipe.read_text()))
    print(json.dumps({"preflight": "passed", "tool": recipe["tool"], "args": recipe["args"]}, indent=2))
    if not parsed.execute:
        return

    _call_lqh(
        recipe["tool"], recipe["args"],
        donate=bool(recipe.get("allow_hf_donate", False)),
    )
    status = _call_lqh(
        "training_status", {"run_name": recipe["args"]["run_name"]}, wait=True
    )
    failure = _terminal_failure(status)
    if failure:
        print(json.dumps({"guard": "stopped", "failure_class": failure}))
        raise SystemExit(2)
    print(json.dumps({"guard": "completed", "run_name": recipe["args"]["run_name"]}))


if __name__ == "__main__":
    main()
