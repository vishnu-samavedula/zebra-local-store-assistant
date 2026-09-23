# Current State and Roadmap

Last updated: 2026-09-22.

This document is the authoritative summary of implemented behavior and planned phases. Older
exploration in `WAREHOUSE_DEMO.md` is retained for future demo ideas but does not override this
roadmap.

## Product boundary

The TC501 application is an offline, push-to-talk warehouse assistant. Application code owns
catalog data, SQL, policy, tool execution, confirmation and verification. Model output is always
treated as untrusted input.

The application does not continuously listen, retain microphone recordings, generate speech,
execute model-generated SQL or use cloud inference.

## Models

### Current audio model

LFM2.5-Audio-1.5B Q4 performs local audio-to-text inference through a persistent Liquid Android
arm64 server. Four matched GGUF components are required by the current runner. The process stays
warm while the application ViewModel is alive. Captured WAV files are temporary and deleted on
success, error and cancellation.

### P1B generative action model

The live P1 model is a fine-tuned, autoregressive `LiquidAI/LFM2.5-350M`, not the separate
bidirectional `LFM2.5-Encoder-350M`. It consumes the ASR transcript and generates allowlisted
warehouse calls plus normalized arguments, or a clarification/rejection/final grounded response.
The frozen P1B contract contains five tools: `inventory_search`, `location_contents`,
`get_task_status`, `report_issue` and `request_replenishment`.

This choice preserves variable argument extraction, semantic-query construction and a path toward
grounded semantic-ID selection and later multi-step workflows. The encoder remains an optional
future specialist for retrieval, reranking or a fast routing gate.

The model never generates executable SQL, code or arbitrary Android actions. Kotlin parses and
validates its output, resolves catalog candidates, derives and enforces write policy, constructs
typed requests and owns confirmation, execution and verification.

## Implemented phases

### P0: audio plumbing — complete

- Compose Android application installed and exercised on the physical TC501.
- Push-to-talk 16 kHz mono capture.
- Persistent local LFM audio server with warm-model reuse.
- Audio input and text response.
- TTFS in milliseconds, decode tokens/second and total latency.
- Clear and cancellation behavior.
- Temporary audio cleanup and no output-audio generation.

### P1A: deterministic store-agent foundation — complete

- P0 and P1 application tabs.
- Store Assistant UI with voice recipes and passive capability tiles.
- LFM audio ASR using `Perform ASR.`.
- Five-SKU JSON seed catalog imported into app-private SQLite.
- Exact SKU and deterministic lexical catalog matching; no RAG, embeddings or ColBERT.
- `inventory_search`: read-only and immediately executable.
- `report_issue`: always previewed and confirmation-gated.
- Persistent issues with `ISS-######`, description, category, status, priority, source transcript,
  timestamps and a complete JSON audit/export payload.
- Read-back verification from SQLite before reporting write success.
- Contract-simulator router behind a replaceable encoder interface.
- Worker-facing result cards; internal sense/understand/decide stages are not shown as message
  schema.
- Unit tests plus physical-device ASR and SQLite round-trip instrumentation tests.

P1A remains in the source tree as a tested rollback/reference implementation. It is no longer in
the live P1 request path, which is now:

```text
ASR transcript
  -> generative LFM2.5-350M constrained action
  -> native parse/schema validation
  -> optional catalog candidate resolution
  -> policy and typed tool request
  -> parameterized SQLite repository
  -> deterministic response/confirmation/verification
```

### P1B: trained generative tool caller — pilot integrated

Use sibling repositories `../lqh` and `../lqh_backend` as the authoritative training-tool
references.

Completed pilot work:

- Generated/reviewed the synthetic pilot and trained the LFM2.5-350M LoRA through LQH 0.23.
- Trained and promoted the balanced schema-free v4 checkpoint. It internalizes the five fixed tool
  contracts and runs without request-level JSON schemas or compact signatures.
- Schema-free v4 scored 9.20 on the original 100-row held-out set and 7.85 on the separate 20-row
  first-turn judge suite. Android-normalized exact checks passed 17/17 actionable first turns,
  3/3 no-call cases, and 40/40 execution-critical held-out tool targets.
- Converted v4 to Q8_0 and passed a 10/10 local llama.cpp smoke plus the physical TC501 test for
  all five adapters, parallel reads, correction handling, conditional read-first routing and
  safety behavior.
- Improved held-out qualitative evaluation from 7.28 to 8.90.
- Merged and exported Q4_K and Q8_0 GGUFs; the demo app now pins Q8_0 for the stronger quality margin.
- Bundled a portable ARM64 llama.cpp server and integrated Liquid native-call parsing.
- Removed the deterministic simulator from the live P1 route.
- The Android runner uses llama.cpp `/apply-template` plus raw `/completion`, preserving Liquid's
  trained native call grammar. The OpenAI-compatible structured-tool endpoint is intentionally not
  used because its additional grammar/parser rewrote otherwise valid Liquid-native generations.
- Passed a physical TC501 end-to-end test for all five tools, including model inference, strict
  native parsing, policy, SQLite reads, confirmation-gated writes and read-back verification.
- Passed a physical TC501 two-call independent inventory-read test.
- Strict parsing rejects duplicate keys, unknown non-null arguments, malformed calls and length
  truncation; none can reach an adapter.
- Measured the validation run at 446 ms server/model startup, 7.27 s first inference and 5.59 s
  warm inference, with 36.7 decode tokens/s on the warm request. These are debug/pilot numbers,
  not final release benchmarks.
- With the app open and both servers resident, the observed process RSS values were approximately
  200 MiB for the app, 1.69 GiB for audio and 329 MiB for P1B (about 2.21 GiB combined).

Remaining work is to measure release-build memory/thermal/battery behavior and implement the
bounded dependent model → tool result → model orchestration in the live app.

#### Observed TC501 regression: vague replenishment

The live utterance `We are missing the North Line safety vests. I think we need to order more.`
was transcribed correctly and passed unchanged from LFM Audio to P1B. The earlier structured-tool
runtime produced a repeated, truncated call; moving to the trained Liquid-native completion path
removed that runtime corruption. The current Q8 checkpoint still chooses `report_issue` instead
of asking for replenishment quantity and destination. Native grounding and required-field policy
produce a clarification with no proposal, confirmation or write. This remains a training-quality
regression, but it is locked as a zero-write on-device safety test.

Treat this as a locked regression case across the following pipeline:

1. Add vague replenishment, missing-field and ASR-variant examples such as `North Line` versus
   `Northline`, `order more`, `we are missing` and referential product mentions.
2. Require a short clarification with no tool call when quantity or destination is absent.
3. Score invented identifiers, repeated arguments, malformed JSON and length-truncated calls as
   hard failures.
4. Run the exact prompt against the training checkpoint, Q8_0 and Q4_K to isolate quantization
   regression before promoting another mobile artifact.
5. On device, continue detecting malformed or length-truncated output and show a safe
   retry/clarification message. Never execute or attempt to repair an untrusted partial call into
   a write.

#### P1B implementation queue

Completed in the demo build:

1. **Failure-safe response:** valid clarification, no-call output, malformed call and length
   truncation are distinguished; unsafe output executes nothing.
2. **Task adapter:** `get_task_status` uses parameterized SQLite task reads and deterministic
   result verification.
3. **Replenishment adapter:** `request_replenishment` uses a typed proposal, explicit confirmation,
   transactional local write/audit record and read-back verification.
4. **Independent multi-read execution:** every valid read in a block is executed and displayed,
   up to three.

Remaining:

5. **Dependent orchestration:** only after independent reads remain reliable, add the bounded
   model → tool results → model loop for dependent reads and conditional actions.

Current adapter boundary:

- `inventory_search`: verified deterministic read adapter.
- `location_contents`: verified deterministic read adapter.
- `get_task_status`: verified deterministic task read adapter.
- `report_issue`: verified confirmation-gated write adapter.
- `request_replenishment`: verified confirmation-gated write adapter.
- Multiple parsed calls: up to three independent reads are executed; mixed read/write blocks and
  multiple writes are rejected.

Promotion targets:

- Parse/valid-format rate at least 0.995.
- Tool macro F1 at least 0.97.
- `report_issue` precision at least 0.99.
- Required-argument recall at least 0.98.
- Exact executable-action match at least 0.93.
- Zero invented canonical IDs outside the supplied or allowed candidate set.
- Explicit evaluation of negation, unsupported requests and false write proposals.
- Zero execution of malformed, truncated or partially parsed calls.

LQH 0.23.0 supports the generative SFT path and lists `lfm2.5-350m` as the instruct checkpoint.
Use LQH for specification, data generation, filtering, baseline, SFT and evaluation. Its standard
cloud training path removes the need for a custom Modal trainer unless export or training support
fails. LQH Cloud jobs incur cost and require explicit spend authorization before submission.

## Planned phases

### P1C: grounded semantic product resolution

- Keep catalog and inventory facts external in SQLite.
- Use embeddings as the catalog's semantic discovery layer: product descriptions, use cases,
  style, material, synonyms and "similar to" relationships define the catalog's meaning or vibe.
- Use that layer for requests such as "a waterproof work shoe" or "something like this wallet,"
  not for authoritative quantities, locations or task status.
- Have the generative model emit semantic queries and structured attributes rather than inventing
  opaque product IDs.
- Retrieve valid candidates from the external catalog, then allow grounded selection only from
  that candidate set.
- Measure top-k candidate recall and canonical-ID accuracy.
- Try SQLite FTS/hybrid retrieval before adding an embedding model.
- Evaluate LFM2.5-Encoder/Embedding/ColBERT only if measured catalog scale or ambiguity justifies
  an additional retrieval runtime.

### P1D: warehouse audio adaptation

Only begin if TC501 audio evaluation shows that ASR is the bottleneck.

- Build a consented warehouse audio test set with noise, accents, PTT clipping and spoken IDs.
- Measure SKU, quantity and location error rates rather than relying only on generic WER.
- First try deterministic catalog/location correction.
- If required, adapt LFM Audio with LoRA/adapters while freezing most of the model.
- Later evaluate direct audio intent heads while retaining a transcript for audit and correction.

### Later: reasoning and multimodal workflows

Reasoning is deferred until requests require multi-step plans, dependencies or several tools.
Vision remains a separate still-image evidence milestone before any audio-plus-video workflow.

## Tool policy

- The model schema and parser allow all five frozen tools.
- The integrated demo exercises all five adapters and independent multi-read orchestration on the
  physical TC501. Dependent chaining remains a later promotion item.
- One call block may contain one to three independent reads.
- Dependent reads occur only after a preceding result and another model turn.
- At most three calls and three model/tool steps are permitted per worker request.
- A write is always the only call in its block, and at most one write proposal is permitted per
  request and explicit confirmation.
- The generative model emits a bounded action and arguments; it never emits executable SQL.
- Native code parses and validates the action, enforces policy and constructs parameterized
  repository operations.
- A linguistically complete request can still clarify after SQL if the product is absent or
  ambiguous.

## Data ownership

The checked-in catalog, tasks, and SQLite tables are demo/test fixtures used to exercise and verify
the tool boundary. They are not components of the LFM model harness or trained checkpoint. A host
application may replace the repository with its existing WMS, database, or local service and omit
the fixture assets and seeding code entirely.

- `seed_data/warehouse_catalog.jsonl`: canonical 49-SKU seed fixture shared by Android and LQH.
- `seed_data/warehouse_tasks.jsonl`: canonical 24-task seed fixture with catalog-valid SKUs.
- The Android build packages both fixtures as assets and imports them into SQLite.
- `warehouse-p1a.db`: runtime catalog and issue source of truth in app-private storage.
- JSON issue payload: immutable audit/export snapshot stored with the relational issue row.
- Model weights, generated datasets and training checkpoints remain outside Git unless represented
  by manifests or small approved fixtures.
