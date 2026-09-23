# P1B Generative Tool-Calling Training Plan

Last updated: 2026-09-22.

## Objective

Replace the provisional `ContractSimulatorEncoder` decision path with a fine-tuned generative
`LiquidAI/LFM2.5-350M` model. Given the LFM Audio ASR transcript, the model emits a complete,
bounded warehouse action: tool selection, normalized arguments and enough information for the
application to clarify, confirm or execute safely.

P1B supports single calls, up to three independent parallel reads, dependent read chains and one
conditional write proposal. It does not execute several mutations from one request. The
application continues to own the allowlist, validation, confirmation policy, SQL, execution and
verification.

## Model choice

Use the LQH catalog model `lfm2.5-350m`, corresponding to
`LiquidAI/LFM2.5-350M`, for the first baseline and SFT.

- It is the instruction-tuned, autoregressive 350M checkpoint with Liquid chat and native tool-use
  formatting.
- It can generate variable tool arguments, clarification text and future expanded action schemas.
- LQH's normal assistant-only SFT path expects an instruct/thinking chat template with generation
  markers. The separate `lfm2.5-350m-base` checkpoint does not currently provide that template and
  is not the first-run choice.
- `LFM2.5-Encoder-350M` is not the central P1B model. It remains a possible later specialist for
  retrieval, reranking, token classification or an ultra-fast routing gate.

The 350M size is a deployment constraint and an experiment, not an assumption that quality is
already sufficient. If high-quality data does not produce an acceptable checkpoint, record the
failure rather than silently increasing model size beyond the TC501 target.

## Runtime contract

Initial target flow:

```text
push-to-talk audio
  -> LFM2.5-Audio-1.5B ASR
  -> transcript
  -> fine-tuned LFM2.5-350M
  -> constrained single action or clarification/rejection
  -> native parse and schema validation
  -> catalog candidate resolution, policy and parameterized SQLite operation
  -> confirmation for writes
  -> read-back verification
  -> deterministic worker-facing response
```

The generated action should use Liquid's native tool-call format in training and inference unless
the export/runtime spike proves that a constrained JSON envelope is materially safer or easier.
The exact serialized grammar must be frozen in `SPEC.md` and reused byte-for-byte for baseline,
training, evaluation and Android inference.

P1B exposes five tools:

- `inventory_search`: read-only product, variant, quantity and location search.
- `location_contents`: read-only bin, aisle, dock or zone contents.
- `get_task_status`: read-only task lookup for the current worker or an explicit task.
- `report_issue`: confirmation-gated damage, discrepancy, blocked-location or general report.
- `request_replenishment`: confirmation-gated stock movement/replenishment request.

The exact arguments and category-dependent required fields are authoritative in root `SPEC.md`.
One call block may contain one to three independent reads. Dependent calls require the preceding
tool result and another model turn. A write must be the only call in its block, only one write may
be proposed per request, and every write requires application confirmation.

Clarification, rejection and grounded final read answers are concise assistant text, not executable
warehouse mutations.

## Semantic-ID grounding

The model may emit a canonical product ID only when it is grounded by an explicit spoken ID or by
a supplied candidate list. For a descriptive request it emits a `semantic_query` and extracted
attributes. Native retrieval returns valid catalog candidates; a later grounded selection step may
choose only from those candidates.

The model must never invent opaque IDs, inventory counts, locations or SQL. Catalog and inventory
facts remain external in SQLite.

Example conceptual action before catalog resolution:

```json
{
  "tool": "inventory_search",
  "arguments": {
    "semantic_query": "TrailBlaze GTX shoes",
    "product_id": null,
    "sku": null,
    "color": "black",
    "size": "10",
    "location": null
  }
}
```

This JSON illustrates semantics only; it is not yet the frozen wire format.

## LQH responsibility

Use LQH 0.23.0 as the project harness for:

1. Capturing and versioning `SPEC.md`.
2. Authoring the rubric and generation pipeline.
3. Generating scenario-controlled synthetic conversations and ASR-like variants.
4. Scoring, filtering and versioning train/evaluation datasets.
5. Running a prompt-complete zero-shot baseline.
6. Running generative SFT when the data has passed inspection.
7. Evaluating exact action correctness and qualitative behavior.
8. Tracking artifacts and provenance.

LQH Cloud performs data generation and judging. Its cloud training path uses remote GPU compute;
there is no need to write a separate custom Modal trainer for the generative P1B model. Use a
separate Modal job only if the standard LQH training/export path proves insufficient.

No paid LQH job, GPU training job, publication or deployment begins from this document alone.
Check authentication, remaining credit and an explicit user-approved spend ceiling first.

## Dataset record

Training rows are conversational SFT examples, not classifier labels. Each record must retain
machine-readable annotations alongside the rendered messages so deterministic evaluation can
inspect fields independently.

```json
{
  "id": "report_damage_00412",
  "scenario_family": "complete_damage_report",
  "product_family": "packaging",
  "variant": "asr_noisy",
  "messages": [
    {"role": "system", "content": "<frozen warehouse tool contract>"},
    {"role": "user", "content": "log three damaged cartons sku 5520 bay b seven"},
    {"role": "assistant", "content": "<native report_issue tool call>"}
  ],
  "expected": {
    "outcome": "tool_call",
    "tool": "report_issue",
    "arguments": {
      "semantic_query": "Titan shipping carton",
      "sku": "5520",
      "quantity": 3,
      "location": "B7",
      "category": "damage"
    },
    "requires_confirmation": true
  }
}
```

Scenario specifications own ground truth. LQH may generate linguistic variation, but the same
judge model must not be the sole author of both an example and its correctness label.

## Coverage

- Product names, SKUs, aliases, colors, sizes and spoken identifiers.
- Complete and incomplete inventory searches and issue reports.
- Damage, discrepancy, blocked-location and general issue categories.
- Optional versus required arguments for each tool.
- ASR substitutions, homophones, dropped punctuation, hesitations, corrections and clipped speech.
- Negation and historical/non-action mentions of issues.
- Ambiguous product descriptions and near-neighbor variants.
- Unknown products, invalid IDs and catalog no-match cases.
- Unsupported pick, notification, SOP and conversational requests.
- Multiple intents, conflicting instructions and attempts to bypass confirmation.
- Vague replenishment language such as `order more`, `we are missing` and `we are running low`,
  including absent quantity/destination and product-name ASR splits such as `North Line` for
  `Northline`.
- Degeneration cases: duplicate arguments, repeated tool fragments, invented identifiers and
  output that reaches the generation limit before closing a call.
- Tool-call injection and requests for SQL, shell commands or arbitrary Android actions.

Ground operational facts in the canonical 49-SKU catalog and 24-task fixture. For later
generalization evaluation, create separately identified held-out fixtures rather than silently
inventing products inside labeled training traces. Hold out scenario templates and product
families so evaluation measures generalization rather than memorization.

## Evaluation

Evaluate parsed behavior, not just judge preference or surface text.

- Valid-format rate.
- Tool selection precision, recall and macro F1.
- Exact argument match and per-field precision/recall.
- Required-field omission and unsupported-field hallucination.
- Clarification correctness for incomplete or ambiguous requests.
- Rejection accuracy for unsupported or unsafe requests.
- False `report_issue` proposal rate, especially on negation.
- Canonical-ID validity and candidate-grounding compliance.
- End-to-end typed-request exact match after native validation.
- Clean versus ASR-noisy results.
- Seen versus held-out product families and scenario templates.
- Training-checkpoint versus Q8_0 versus Q4_K parity on the same locked prompts.
- Finish-reason distribution, with every length-truncated tool call counted as invalid and unsafe
  to execute.

Initial promotion targets:

- Parse/valid-format rate at least 0.995.
- Tool macro F1 at least 0.97.
- `report_issue` precision at least 0.99.
- Required-argument recall at least 0.98.
- Exact executable-action match at least 0.93.
- Zero invented canonical IDs outside the supplied/allowed set.
- Zero unconfirmed writes in application integration tests.

Thresholds are provisional until the frozen evaluation set and error-cost rubric exist.

## Execution plan

### Stage 0: specification

1. Complete LQH specification capture and create project-root `SPEC.md`.
2. Freeze tool schemas, output grammar, language support, clarification behavior and the exact
   single-action boundary.
3. Create a short production-equivalent system prompt and protocol definition.

### Stage 1: data and scorer smoke

1. Read `lqh docs data` and author a versioned pipeline under `data_gen/`.
2. Generate 10 smoke samples, then a 100-sample synthetic review batch.
3. Inspect all smoke samples and audit the review batch's messages, expected actions, format
   validity and scenario balance.
4. Build a deterministic parser/field scorer plus an LQH qualitative rubric.
5. Generate separate pilot train and validation datasets and filter both.

### Stage 2: baseline

1. Evaluate `LiquidAI/LFM2.5-350M` on the held-out set using the same system prompt, tool schemas,
   decoding settings and parser planned for production.
2. Record exact metrics and failure clusters. A promptless baseline is not the headline baseline.

### Stage 3: pilot SFT

1. Train on approximately 200–500 high-quality filtered samples using LQH's shipped SFT defaults.
2. Compare the checkpoint with the baseline on the frozen validation set.
3. Continue only if the pilot shows genuine learning and does not increase unsafe activations.

### Stage 4: scale and lock evaluation

1. Scale toward several thousand reviewed examples before considering tens of thousands.
2. Preserve all accepted versioned datasets; do not overwrite prior datasets or runs.
3. Freeze a locked test set split by scenario template and product family.
4. Train/evaluate the scaled SFT and use failure analysis to add targeted data.
5. Consider DPO only after SFT quality plateaus; it is not part of the first run.
6. Keep the exact TC501 vague-replenishment failure as a locked regression: correct behavior is a
   concise request for quantity and destination with no executable tool call.

### Stage 5: export and lowering

1. Export/quantize the accepted checkpoint to a format supported by the Android runtime.
2. Run desktop parity tests between the training checkpoint and quantized artifact.
3. Implement a strict native parser and typed adapter in the Android app.
4. Benchmark cold/warm latency, decode speed, memory and thermal behavior on the TC501.
5. Promote only after accuracy and safety regression tests pass on the actual lowered artifact.
6. Reject malformed/partial calls at runtime and present an explicit safe clarification; never
   convert a truncated model fragment into a typed write request.

## Immediate next decisions for `SPEC.md`

- English-only versus multilingual v1.
- Native Liquid tool-call format versus a constrained JSON action envelope.
- Exact required/optional arguments for both tools.
- Whether incomplete inputs produce structured clarification or concise assistant text.
- Whether an exact spoken SKU may be passed through before database validation.
- Maximum generated tokens and whether P1B produces only actions or also final worker-facing text.
- Approved LQH Cloud/data-generation spend and training compute ceiling.
