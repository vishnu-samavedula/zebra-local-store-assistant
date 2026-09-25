# Historical P1A Store Agent Contract

> Archived design record. The deterministic simulator described below has been removed from the
> application. The live Store Assistant contract and implementation status are documented in
> [CURRENT_STATE_AND_ROADMAP.md](CURRENT_STATE_AND_ROADMAP.md).

## Outcome

P1A proves an entirely local, observable agent loop on the TC501:

`sense → understand → decide → act → verify`

It supports two tools against synthetic warehouse data:

- `inventory_search`: read-only and safe to execute immediately.
- `report_issue`: local state mutation and always confirmation-gated.

The model may use any number of read-only lookups to understand one utterance.
Only one state-changing action may be executed per explicit confirmation.

## Runtime pipeline

1. The app captures a bounded utterance as 16 kHz mono PCM.
2. LFM2.5-Audio transcribes it with the `Perform ASR.` system prompt.
3. The temporary audio file is deleted after inference, including error and cancellation paths.
4. Native code normalizes conservative warehouse aliases and extracts high-precision entities.
5. SQLite-backed local catalog search returns at most three product candidates.
6. The provisional decision component classifies the request from the transcript, entities and
   candidates.
7. Kotlin validates the prediction and either executes a read, asks for missing information,
   proposes a confirmation-gated write or declines an unsupported request.
8. A write is read back from SQLite before the UI reports success.

The P1A simulator does not generate text or tool JSON. UI messages are deterministic templates.

This is the implemented P1A simulator order. P1B replaces the decision component with a
generative action model and moves catalog access behind strict parsing and validation. See
`CURRENT_STATE_AND_ROADMAP.md` for the target sequence.

## Current simulator input

```text
DecisionInput
  transcript: String
  entities:
    sku: String?
    quantity: Int?
    location: String?
    issueCategory: String?
  catalogCandidates[0..3]:
    productId: String
    sku: String
    name: String
    variant: String
    location: String
    onHand: Int
    reserved: Int
    score: Float
```

## Current simulator output

```text
DecisionPrediction
  tool: INVENTORY_SEARCH | REPORT_ISSUE | NONE
  intent: stock_check | issue_report | unsupported
  risk: SAFE | CONFIRM_REQUIRED | BLOCKED
  confidence: Float [0, 1]
  missingFields: List<String>
```

The initial app uses `ContractSimulatorEncoder`, a deterministic implementation retained from the
earlier architecture. P1B replaces this provisional output with a parsed generative action from
`LiquidAI/LFM2.5-350M`; the application adapter will preserve the surrounding tool boundary and
continue to fail closed on unknown or inconsistent output.

## Tool contracts

### inventory_search

Input is a resolved catalog query or canonical product ID. Output contains canonical product,
variant, location, on-hand, reserved and available quantities. Ambiguous variants are returned
as choices; the app must not silently select one.

### report_issue

Required fields are category, canonical product ID, quantity and location. The tool proposal is
shown before execution. Confirmation creates an issue ID, and verification reads the stored issue
back before success is displayed.

The source of truth is the private SQLite database, not a mutable JSON file. Each issue stores its
generated `ISS-######` ID, description, bounded category, status, priority, canonical product ID,
quantity, location, original transcript, timestamps and an immutable JSON snapshot of the exact
tool payload. The readable seed catalog lives in `seed_data/warehouse_catalog.jsonl`; the Android
build packages it as an asset and imports it when the database is first created.

P1A uses native high-precision entity parsing plus SQLite candidate search. RAG and ColBERT are not
part of this path; they become relevant only when unstructured procedures or a much larger,
semantically ambiguous catalog justify them.

## UI states

`MODEL_MISSING → READY → RECORDING → PROCESSING → COMPLETE`

An issue report uses:

`PROCESSING → AWAITING_CONFIRMATION → COMPLETE`

Error and cancellation paths return to a recoverable state. Clear removes the visible interaction
but does not unload the persistent audio model.

This was true for the original P1A screen. The current recipe tiles submit text to the trained P1B
inference path and are not hardcoded tool shortcuts.

## Metrics

- ASR TTFS in milliseconds
- ASR decode tokens/second
- Tool-model TTFS, decode tokens/second and total generation time
- Catalog search latency in milliseconds
- Tool latency in milliseconds when executed
- Total utterance-to-result latency

P1A's simulator timing is plumbing-only. P1B uses autoregressive generation and therefore reports
TTFS and decode rate as well as total action latency.

## P1B handoff

Tool schemas, generation grammar, dataset design, evaluation thresholds and lowering gates are
defined in `P1B_TRAINING_PLAN.md` and `CURRENT_STATE_AND_ROADMAP.md`. Synthetic splits isolate
paraphrase templates and product families. Negated, ambiguous, incomplete and unsupported cases
are required; overall judge score alone is not sufficient for promotion.
