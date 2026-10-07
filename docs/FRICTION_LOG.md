# Friction and Decision Log

Last updated: 2026-10-07.

This is the short operational history of problems that materially changed the Store Assistant.
Raw experiment detail remains in `NOTES.md`, the training plans, and individual test reports.

## Model and prompt path

| Friction | What we learned | Current decision |
|---|---|---|
| The first audio plumbing test produced huge or irrelevant responses. | ASR, response generation, and tool inference need separate bounded contracts and token limits. | Audio produces text only; the 350M model performs tool inference. Both processes remain warm. |
| The stable cascade proved audio input, but it assumed transcription had to feed a second model. | The local Liquid voice-assistant example proves direct audio-to-function-call training, while the excavator omni work shows how to pair voiced user turns with text twins and grade them through the same behavioral probes. Text-model training does not have to precede audio training or transfer its LoRA. | Keep the cascade as the control. Build direct audio-to-native-call as a separate P1D candidate from the shared deterministic contract and corpus; promote only after on-device quality, safety, latency, and memory comparison. |
| Deterministic routing proved the UI and SQLite adapters but did not prove model inference. | Recipe, typed, and voice requests must enter the same model path. | No keyword router or regex selects tools. Kotlin only parses, validates, authorizes, and executes inferred calls. |
| The initial P1B corpus and evaluations did not evenly stress every tool, negatives, missing fields, and multi-read requests. | Aggregate judge scores can hide action-level failures. | Use balanced per-tool coverage, grounded catalog/task fixtures, explicit negatives, and exact tool/argument gates. |
| Schema-free checkpoints were faster but unreliable on actionable requests. | Removing schemas was a train/serve distribution shift, not a free prompt optimization. | The stable checkpoint uses the canonical short system message plus five tool definitions through Liquid `apply_chat_template`. |
| The OpenAI-compatible structured endpoint rewrote or corrupted otherwise valid Liquid-native calls. | LFM native tool tokens must survive end to end. | Use `/apply-template`, then raw `/completion`, and strictly parse Liquid-native call blocks. |
| Some training examples contained inferred or unsaid write arguments. | A good-looking tool call can still be unsafe. | Writes require worker-authored product, quantity, and location/destination fields; incomplete writes clarify and execute nothing. |
| Multi-read responses exceeded the original 128-token generation cap. | Valid independent call blocks can be longer than a single call. | Allow up to 256 generated tokens and reject any `stop_type=limit` response as truncated. |
| Checkpoint names became ambiguous across v2/v4/v5/native experiments. | Experiment labels are poor deployment identifiers. | The only active text artifact is `LFM2.5-350M-Warehouse-Stable-Q4_K.gguf`; numbered names remain historical lineage only. |
| The stable checkpoint emits every known argument with `None`, producing 63–82 tokens for one call and 188 for three reads. | The 540 training rows contain 635 compact calls, zero null/empty arguments, and median 29-token rendered call blocks; assistant masks fully cover every audited tool block. LQH checkpoint predictions already show the verbose union at step 50, while the untouched base has no null arguments. This is a free-decoding/overfitting regression, not corpus, GGUF, Android, or prompt verbosity. | Preserve the correct stable checkpoint. For the next candidate, reduce LoRA strength/duration, add a strict generated-output compactness gate, and compare early checkpoints. Never post-process generation to claim a latency improvement. |
| Three compact-preservation LoRA experiments exposed an accuracy/verbosity tradeoff. Rank 8 with attention-only targets removed explicit `None` fields, but one epoch underlearned (23/112 exact calls) and three epochs did not recover accuracy (19/112). Rank 16 with attention plus `w1/w2/w3` improved exact calls to 33/112 and eliminated parse failures, but all 97 native-call outputs again emitted explicit `None` fields. Judge macro scores of 3.56, 3.34, and 3.20 agreed that none was promotable. | Teacher-forced loss and token accuracy are not deployment gates. Lower adapter capacity can preserve stopping behavior while losing task accuracy; restoring MLP capacity can restore routing while reintroducing schema-union generation. The deterministic free-generation suite—exact calls, tool sequence, parse failures, unsupported/empty fields, and output length—must be primary; the LLM judge is secondary and occasionally misreads valid tool results. | Pause text-model retraining. Keep `LFM2.5-350M-Warehouse-Stable-Q4_K.gguf` on the device; do not merge, quantize, or deploy any compact-preservation candidate. If resumed, change one variable at a time, beginning with rank 16 and attention-only targets. |

## Runtime and device path

| Friction | What we learned | Current decision |
|---|---|---|
| Cold-load numbers were sometimes confused with warm inference. | Android page cache and persistent processes materially change observed latency. | Report cold preparation separately; TTFS, decode rate, and total time describe each request. Clear does not unload models. |
| The older Android text runtime left substantial performance on the table. | Runtime build flags matter even for the same GGUF. | Pin static llama.cpp `b11456`, built for `armv8.6-a+dotprod+i8mm`; retain the legacy build only as a build-time rollback. |
| Hexagon/NPU benchmarks showed excellent prefill throughput but poor decode and invalid tool output. | Backend compatibility does not imply application correctness or lower end-to-end latency. | Production remains CPU-only. Revisit NPU only after upstream backend changes and repeat the exact-contract correctness gate. |
| Two local models increase cold-start time and resident memory. | The cost is mostly model loading and generation, not SQLite. | Keep both servers resident; do not replace authoritative SQLite reads with embeddings for speed. |
| Temporary microphone captures could have accumulated. | Audio lifecycle must be explicit on every exit path. | WAV files are deleted after success, error, or cancellation; no output audio is generated or retained. |

## Safety and orchestration

| Friction | What we learned | Current decision |
|---|---|---|
| Model-generated SQL would couple language generation to authoritative state. | Tool intent and database execution are separate trust boundaries. | The model emits typed tool calls; Kotlin constructs parameterized SQLite operations. |
| A single worker request can require several reads, but multiple writes are unsafe. | Parallel reads and mutations need different policies. | Execute at most three independent reads. A write must be the sole call and requires explicit confirmation plus read-back verification. |
| Automatic dependent chaining was tempting before independent calls were stable. | Each additional model/tool turn expands error and mutation risk. | Worker-driven continuation is implemented; automatic dependent model → tool-result → model chaining remains deferred. |

## Current evidence and remaining friction

- Stable text artifact: 229,314,496 bytes; SHA-256
  `79863ccf4cd66b5d7532d4ac3f2cb9e8de65164ebd2b8251574fadf363ed1a15`.
- Stable device gate: four physical TC501 tests passed, covering all five tools, three reads,
  continuation, and incomplete-write safety.
- Current warm single-tool observation after the runtime upgrade: about 3.22 seconds, with roughly
  45.3 decode tokens/second. Cold model load remains about 13.24 seconds.
- The main remaining latency cost is prompt prefill plus verbose generated calls. SQLite is not a
  meaningful bottleneck.
- The 2026-10-06 exact-device compactness test kept all six tool selections correct. Normal
  single-call requests took 4.9–5.5 seconds and the three-read request took 7.9 seconds. Tool-level
  omit-null instructions did not change output; decoder suppression generated malformed calls.
- Next engineering gate: release-build latency, memory, thermal, and battery measurement using a
  fixed prompt suite. Audio adaptation should begin only if warehouse-audio evaluation proves ASR
  is the bottleneck.
