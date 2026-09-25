# Project Scope

## Objective

Build an offline Android warehouse assistant on a Zebra TC501 using local Liquid LFM models,
microphone input, bounded model decisions and application-owned tools. The project progresses from
runtime proof to a trained generative tool caller, semantic catalog resolution, audio adaptation and later
multimodal workflows.

The authoritative implementation status and phase roadmap are in
[CURRENT_STATE_AND_ROADMAP.md](CURRENT_STATE_AND_ROADMAP.md).

## Target device

| Property | Target |
|---|---|
| Device | Zebra TC501 / TC501W |
| SoC | Qualcomm QCM6690, platform `volcano` |
| ABI | `arm64-v8a` |
| CPU | 8 cores |
| Usable RAM | Approximately 11.3 GB |
| Storage | Approximately 221 GB |
| Display | 1080 × 2160, 480 dpi |
| OS | Android 15 / API 35 |

CPU inference is the correctness baseline. QNN/NPU work is a later measured optimization, not a
dependency for the current Store Assistant.

## Audio foundation — complete

Prove local audio-model plumbing:

```text
push-to-talk microphone
  -> temporary 16 kHz mono WAV
  -> persistent local LFM2.5-Audio Q4 runtime
  -> streamed text
  -> TTFS/decode/total metrics
```

The app does not produce output audio, use cloud services or retain microphone clips.

## Deterministic foundation — complete

Prove the deterministic application and tool foundation:

- Single-purpose Store Assistant UI.
- LFM Audio ASR.
- Five-SKU JSON seed catalog and app-private SQLite database.
- Deterministic product search.
- `inventory_search` read tool.
- Confirmation-gated `report_issue` write tool.
- Persistent issue IDs, policy fields, transcripts and JSON audit payloads.
- Native validation, parameterized data access and read-back verification.
- Strict application-owned parsing, policy and tool adapters.

The worker sees a normal transcript, answer or report-review card—not the internal action schema.
The passive common-task tiles communicate capability but do not execute tools.

## P1B — integrated pilot

The instruction-tuned generative `LiquidAI/LFM2.5-350M` was fine-tuned using LQH. It receives the ASR
transcript and supports five tools: `inventory_search`, `location_contents`, `get_task_status`,
`report_issue` and `request_replenishment`. It may emit up to three independent reads, perform
dependent read chains through returned tool context, or propose one confirmation-gated write.
Native code parses and validates the output, resolves catalog candidates, creates typed requests
and executes parameterized SQLite operations. The model never emits or executes SQL.

See [P1B_TRAINING_PLAN.md](P1B_TRAINING_PLAN.md).

## P1C

Add measured, grounded semantic product/entity resolution while keeping catalog facts external.
The generative model produces semantic queries and attributes; retrieval supplies valid candidate
IDs. Embeddings represent the catalog's descriptive character—use cases, materials, style,
synonyms and product similarity—while SQLite remains authoritative for stock, location and status.
Start with SQLite FTS or hybrid retrieval. Add an encoder, embeddings or LFM-ColBERT only if scale
and ambiguity justify the extra model and runtime cost.

## P1D

Adapt LFM Audio only if real TC501 evaluation shows material SKU, quantity, location or noisy-zone
ASR errors. Prefer deterministic correction first, then adapters/LoRA. Direct audio intent heads
are a later experiment and do not remove the audit transcript.

## Later phases

- Reasoning and multi-step planning only for workflows that genuinely require dependent calls.
- Still-image VLM evidence before any continuous video or omni workflow.
- Barcode/DataWedge context, sync simulation and tools beyond the frozen P1B five after the trained
  story is measured and stable.
- QNN/NPU, battery, thermal and fleet-management work after functional acceptance.

## Safety and ownership

- Model output is untrusted.
- Kotlin owns the allowlist, typed request creation, SQL, authorization and execution.
- Read operations may execute immediately; every report write requires confirmation.
- At most one state-changing operation is permitted per confirmation.
- Missing, unknown, ambiguous or inconsistent inputs clarify or fail closed.
- Inventory and issue truth comes from SQLite, never model weights.
- No generated code, reflection, shell command, raw generated SQL or arbitrary Android intent is
  executed.

## Foundation acceptance record

- Debug APK installs and launches on the physical TC501.
- Four-file matched LFM Audio Q4 bundle loads from app-private storage.
- Persistent server is reused for warm inference.
- Known-WAV on-device ASR instrumentation test passes.
- JSON-seeded SQLite catalog and persistent issue round-trip instrumentation test passes.
- Unit tests cover search, reports, missing fields, spoken locations, verification and unsupported
  requests.
- Temporary audio cleanup is implemented.
- Final build exposes one Store Assistant experience with performance metrics.

## Explicit current non-goals

- Always-listening or wake-word microphone.
- Model-generated speech.
- Autonomous multi-write plans.
- General chatbot behavior.
- Catalog facts memorized in weights.
- RAG/ColBERT for the current small structured catalog.
- Camera/video inference.
- Cloud inference or remote telemetry.
- Production MDM distribution.
