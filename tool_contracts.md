# Warehouse tool contract

Updated 2026-10-20. The active profile is [worker experience v2](contracts/worker_experience_v2.md). The shared harness validates proposals, grounds arguments, executes reads and owns confirmation/persistence. See [architecture](docs/ARCHITECTURE.md) and [output/harness behavior](docs/MODEL_OUTPUT_AND_HARNESS.md).

| Tool | Mode | Model arguments | Harness result |
|---|---|---|---|
| `inventory_lookup` | General | `operation`: `count_stock` or `find_location`; grounded `product_name`; optional explicitly supplied `variant` | Formatted stock/location facts. Up to two product-name-only proposals may share a read request. |
| `request_replenishment` | General | Grounded `product_name`, positive additional `quantity`, supplied/resolved `variant` | Missing-field question or preview; confirmed save creates one ticket and increments the exact local stock variant. |
| `task_lookup` | General | `operation="list_assigned_tasks"` | Count, first two configured tasks, snapshot freshness and Task View prompt. |
| `create_report` | SOP Advisor | No generated arguments | Original-worker-wording report preview; saves only on separate confirmation. |

Model proposals use Liquid tool-start/end delimiters. Example:

```text
<|tool_call_start|>[inventory_lookup(operation="count_stock", product_name="Cedar")]<|tool_call_end|>
```

A single-product read can include supplied color/size/volume fields. If the worker does not specify variants, do not invent them or narrow the SQL query. Two-product reads use names only, with no variant/category filters. Reads do not start clarification conversations; return grounded candidates, not-found or catalog fallback.

Replenishment can be completed in one to three worker turns: the initial request plus at most two field questions, then form fallback. Required dimensions depend on the catalog product and remaining matches. A unique variant needs no unnecessary question. Quantities are additional stock requested, not existing inventory. Model follow-ups must preserve grounded pending arguments and supply the intended updated proposal.

SOP guidance uses direct transcript/text retrieval and only approved applicable cards. Guidance alone is not a report. Explicit reporting requires the report proposal; accepting the current guidance report offer is a harness-owned exception that prepares a preview without another model call. Both require Submit before saving.

Confirmation, Cancel, Clear, Stop, mode changes, pagination and field-withdrawal controls are harness-owned. Confirmations bind to the current request and revision; corrections invalidate old previews. Repeated confirmation cannot create duplicate tickets or stock changes. No task creation is supported.

The legacy schema and `reference_journeys.jsonl` remain compatibility/specification fixtures. They do not expand the narrowed public worker journeys or establish model quality. Public source-only checks:

```sh
python3 scripts/validate_tool_contracts.py
python3 -m unittest discover -s tests
```
