# Model output and harness

Updated 2026-10-20. The active runtime uses a private audio checkpoint through llama.cpp CPU inference. See [architecture](ARCHITECTURE.md), [tool contract](../tool_contracts.md), and [worker experience](../contracts/worker_experience_v2.md).

## Public model proposals

The active worker profile exposes four tools: `inventory_lookup`, `request_replenishment`, `task_lookup`, and `create_report`. Native proposals use Liquid tool-start/end delimiters and named arguments. JSON-valued variant objects are allowed. The internal JSON envelope and legacy schema exist for compatibility; workflow controls belong to the harness.

```text
<|tool_call_start|>[inventory_lookup(operation="count_stock", product_name="Cedar")]<|tool_call_end|>
```

The parser validates the complete call block, types, tool/operation allowlists and argument shape. Truncated output and malformed proposals fail closed. The harness grounds proposed products/variants and requested quantity; it does not silently rewrite an unrelated operation into the desired one. Approved fixed scope text can produce a no-tool result. Arbitrary model prose is not approved procedure guidance.

## Requests and results

General reads accept one product with explicitly supplied variant fields, or up to two product names without variant/category filters. Unspecified read variants remain absent. Unknown products return grounded not-found results; ambiguous reads show bounded candidates or a catalog fallback without a model-led clarification conversation.

Replenishment requires a catalog product, positive additional quantity and a uniquely resolved variant. The harness asks only for fields that distinguish matching variants. The model must emit the correct accumulated proposal after a clarification; pending state is support for the model, not permission to invent its arguments. At most two follow-up questions follow the opening request, then a form is offered.

Reports use `create_report()` with no generated description, location or product fields. The harness preserves the worker's original words for the report preview. Guidance-only SOP requests use transcript/text retrieval directly. Accepting the current guidance report offer prepares a preview without another generation call. Submit remains separate.

Tasks are read-only: count, first two records, snapshot update time, and a prompt to open Task View. Task creation is excluded.

## Confirmation and storage

No proposed write saves automatically. The app confirms a specific request key and preview revision. Corrections invalidate an earlier preview. Confirmation without a valid current preview saves nothing. Retries/repeated confirmation cannot duplicate a ticket or stock update.

Confirmed replenishment atomically creates a ticket and increments the resolved local stock variant by the additional quantity. Report confirmation creates a local report ticket without inventory changes. My Tickets reads persisted harness records. Local creation does not imply upload, assignment or remote processing.

## Context and controls

Only pending tool state, grounded fields, missing fields, latest question and verified choices are model context. Completed requests are not replayed from the visible feeds. Confirmation, Cancel, Clear, Stop, mode changes and other recognized controls are harness-owned. Clear removes the selected mode's feed and pending context; Stop cancels processing/pending context and retains the feed. Persisted tickets survive both.

Generation uses no system prompt/tool list, temperature zero, a 96-token output ceiling, 4,096 context capacity and enabled prompt caching. The transport's defensive message-count cap is not a policy to replay 24 completed messages. See the [request-context policy](../contracts/conversation_context.policy.json).

## Guidance and presentation

The harness retrieves from approved bundled cards through ColBERT and applicability checks. Similarity alone never authorizes a ticket. Unsupported procedure/site facts are not invented. Guidance ends with: “For more safety procedures, open Operating Procedures on this device.” The current guidance turn can offer report creation; its offer is invalidated when the request is replaced or cancelled.

The app shows recognized worker wording, concise formatted outcomes, and trusted procedure content rather than raw code. Raw calls, finish reason and rejection details are available in private diagnostics. A small grey processing duration under replies includes post-recording processing; generation latency charts have their own narrower boundary.

## Verification boundaries

`contracts/reference_journeys.jsonl` includes legacy specification fixtures. Their schema/supplied-call tests validate harness behavior, not private-model accuracy. Run `python3 scripts/validate_tool_contracts.py` and the public Python harness suite. The latest local source snapshot passed 56 Android unit tests and the debug build; device inference requires separately provisioned artifacts.
