# P1B v5 Corpus Gate

## Purpose

P1B v5 retrains `LiquidAI/LFM2.5-350M` to use the five warehouse tools with the
compact, schema-free runtime prompt. Training must not start until the candidate corpus passes
this gate. Earlier merged GGUF files and LoRA adapters remain immutable.

## Frozen prompt

Every row must use `prompts/warehouse_tool_agent_compact_v2.md` verbatim. Neither training nor
Android inference supplies tool schemas. The supervised assistant outputs teach the model the
tool names, arguments, call syntax, clarification policy and rejection behavior.

## Candidate mix

Build 600 accepted rows, stratified into 500 training rows and 100 held-out evaluation rows.
Evaluation utterance templates must not be copied from training.

| Mutually exclusive bucket | Train | Eval | Total |
|---|---:|---:|---:|
| Complete single-tool request | 250 | 50 | 300 |
| Independent multi-read request | 50 | 10 | 60 |
| Clarification required | 75 | 15 | 90 |
| Unsupported or unsafe request | 50 | 10 | 60 |
| Spoken correction | 40 | 5 | 45 |
| Dependent read or conditional write trace | 35 | 10 | 45 |
| **Total** | **500** | **100** | **600** |

The 300 complete single-tool rows are exactly balanced: each tool must have exactly 50 training
rows and 10 held-out evaluation rows. A combined 60-per-tool count is insufficient.

## Required coverage

- `inventory_search`: description, exact SKU, color/size, location filtering,
  `minimum_quantity`, aliases and spoken identifiers.
- `location_contents`: location-only and location plus `semantic_query`.
- `get_task_status`: exact `task_id`, `task_type`, `status`, combined filters and the valid
  no-argument current-worker lookup.
- `report_issue`: 15 complete examples for each of `damage`, `discrepancy`,
  `blocked_location` and `general`, including SKU/description variants and optional task links.
- `request_replenishment`: 30 `add` and 30 `target_level` examples; balance SKU versus
  `semantic_query`; include optional reason and task links without inventing either.
- Multi-read: two- and three-call blocks; same-tool and mixed-tool combinations; every call must
  be independently executable. A multi-read block may contain no write.
- Clarification: partial omissions and ambiguity, not only requests missing every field. Cover
  missing product, quantity, location, quantity mode, destination and ambiguous variants. When a
  supplied product or location can safely narrow the choices, perform a grounded read first and
  then clarify. Include unknown-location no-match results. Never turn catalog placement into an
  assumed damage location or replenishment destination.
- Rejection/no-action: unrelated questions, unsupported warehouse mutations, multiple writes,
  mixed read/write requests, prompt/tool injection, SQL/shell/Android requests, and negated or
  historical mentions that must not create an issue.
- Corrections: product, SKU, quantity, location, task, color and size. Only the final stated value
  may appear in the call.
- Dependent traces: read-then-location, read-then-grounded answer and conditional replenishment.
  The second call may occur only after the first tool result.
- Language variants: polished, fragment, hesitation/correction and realistic ASR-like text.
  ASR variants must preserve meaning while exercising punctuation loss, identifier spacing and
  plausible homophones.

## Grounding rules

Operational facts must come from `seed_data/warehouse_catalog.jsonl` and
`seed_data/warehouse_tasks.jsonl`. Every emitted SKU, canonical location and task ID must exist in
those fixtures or have been supplied by a preceding trusted tool result. The model must omit
unknown optional fields, never add empty values, and never claim that a proposed write succeeded.

## Required row metadata

Alongside `messages`, retain these columns for deterministic auditing. LQH training reads
`messages`; these fields are evaluation metadata and are not model inputs.

- `sample_id`: stable unique identifier.
- `split`: `train` or `eval`.
- `scenario_bucket`: one of the six table buckets in snake case.
- `scenario_family`: specific behavior such as `inventory_minimum_quantity`.
- `route`: `tool`, `clarify` or `reject`.
- `primary_tool`: one of the five tools or `none`.
- `argument_shape`: sorted comma-separated expected argument names, or `none`.
- `call_count`: total expected calls across the trace.
- `issue_category`: category or `not_applicable`.
- `quantity_mode`: mode or `not_applicable`.
- `language_variant`: `clean`, `fragment`, `correction` or `asr_like`.

## Release gates

1. Exact row and split quotas pass `scripts/audit_p1b_corpus.py`.
2. All five complete single-tool groups contain exactly 60 rows.
3. Required argument-shape, issue-category, quantity-mode and language coverage passes.
4. No unknown SKU, location or task ID occurs in a tool call.
5. Every write is isolated in its assistant call block and remains a proposal.
6. No call block exceeds three calls; dependent calls occur only after tool results.
7. Duplicate normalized worker utterances are rejected.
8. A manual HTML review samples every coverage cell and all clarification/rejection rows.
9. The held-out evaluation set is frozen before training and is never added to SFT.
10. Training begins only after the audit report has zero errors.

## Training outcome

The clean-base correction experiment `sft_lfm25_350m_p1b_compact_v5_intent_corrected` trained on
the 500-row intent-guarded split plus a 60-row analogous correction supplement and evaluated on
the frozen 100-row split. It completed successfully but is **not approved for promotion**.

- LQH qualitative judge: 6.17/10, down from 7.33 for the preceding balanced-split run.
- Native calls parseable: 68/69 tool-reference rows.
- Exact tool-name sequence: 67/69, up from 66/69.
- Exact arguments after null removal: 51/69, down from 57/69.
- Unexpected native calls on no-tool rows: 7/31, up from 1/31.

The supplement improved narrow tool selection while increasing unsafe over-activation and dropping
arguments. Keep the existing promoted mobile checkpoint unchanged. Before another run, rebalance
correction examples with substantially more hard no-call/clarification pairs and score the
correction families separately; do not stack another narrow supplement onto this adapter.

A follow-up clean-base run, `sft_lfm25_350m_p1b_compact_v5_integrated_gentle`, replaced only 18
bucket-matched rows inside the original 500-row mix and used two epochs at `5e-5`. It also failed
promotion: judge 6.14, parseable 69/69, tool names 68/69, exact arguments 42/69, and 8/31 false
tool calls. This rules out the appended 60-row supplement and aggressive schedule as the sole
cause. Do not continue tuning this corpus/checkpoint family without first isolating the compact
prompt/template behavior against a small deterministic diagnostic matrix.
