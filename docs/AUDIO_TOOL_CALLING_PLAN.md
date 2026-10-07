# Direct Audio Tool-Calling Plan

Last updated: 2026-10-07.

## Decision

Keep the currently deployed two-model route as the stable control:

```text
speech -> LFM2.5-Audio ASR -> trained LFM2.5-350M -> native tool calls
```

Build and evaluate a second route in parallel:

```text
speech -> fine-tuned LFM2.5-Audio/omni model -> native tool calls
```

The direct route is the target experiment. It removes the intermediate transcript-to-model hop,
but it is not promoted until it matches the existing path on tool selection, exact arguments,
clarification, write safety, latency, memory, and physical-device behavior. A transcript may still
be displayed or logged transiently for audit, but it is not required as an inference handoff.

## What the local references establish

### Liquid cookbook voice assistant

The local `cookbook` `origin/main` voice-assistant example is a direct audio-to-function-call
implementation using `LFM2.5-Audio-1.5B`:

- The base audio checkpoint performs ASR but does not emit the custom calls: its reported baseline
  is 0% format, function-name, and exact-argument accuracy.
- Fine-tuning uses paired audio and target-call text; no intermediate STT stage is used.
- Training and serving must use the same chat shape. In the example, omitting the runtime's
  required `Perform ASR.` system message during training produced correct PyTorch behavior but
  reverted to transcription after GGUF conversion and serving.
- Evaluation is layered: format compliance, function-name accuracy, and exact argument accuracy on
  a held-out split. The published example reports about 99% function-name and 90% exact-argument
  accuracy after 1,000 steps.
- Its Q4, Q8, and F16 results were effectively tied on the reported test subset, making Q4 the
  sensible first device candidate after correctness is established.
- The reference trainer uses full-model audio fine-tuning on Modal rather than LQH LoRA.

### `intents` excavator branch

The local `intents` `origin/excavator` work adds the data and safety discipline needed for a real
tool surface:

- One versioned contract is shared by data generation, validation, probes, and runtime policy.
- A deterministic simulator/state machine creates every tool label and result. A language model
  may paraphrase user language, but it never invents labels.
- Spoken variants explicitly cover numbers, units, identifiers, and ASR confusions while preserving
  exact meaning.
- The omni pilot vocalizes every user turn with multiple voices and speed jitter, then emits both an
  audio row and an all-text twin. The paired text rows anchor the already-learned tool behavior while
  the audio encoder learns the speech mapping.
- The spoken probe runs model output through the same parser, simulator, confirmation policy, and
  exact behavioral checks as the text model.
- Dataset mix matters more than raw row count. Distinct dialogues, held-out flow families, and
  failure-directed generators outperform duplicated surface mutations; any one tool family above
  roughly 15% deserves scrutiny.
- Runtime safety remains deterministic even after a strong model result. Model accuracy never
  authorizes a write by itself.

## Training sequence

We do **not** need to finish another 350M training run and then somehow merge that LoRA into the
audio checkpoint. They are different model packages. We reuse the behavior, not the weights:

1. Freeze the warehouse tool contract and exact Liquid-native output format.
2. Preserve the stable 350M route as the text baseline and fallback.
3. Turn the validated warehouse dialogues into paired audio/tool-call examples. Emit an all-text
   twin for each conversation when the selected audio trainer supports mixed text/audio rows.
4. Establish two untouched baselines on a held-out spoken probe set:
   - current ASR -> 350M route;
   - unmodified audio/omni model asked for direct calls.
5. Run a small direct audio-to-call pilot and prove that free generation emits parseable calls.
6. Expand only from named probe failures, then retrain from the clean audio/omni base on the full,
   balanced corpus.
7. Convert and quantize only a checkpoint that passes the behavioral gate; test F16/Q8 first for
   export correctness, then Q4 for the TC501.
8. Integrate it behind a development switch so direct and cascaded routes can be compared on the
   same utterance and database state.

Text-twin training is simultaneous anchoring within the audio run; it is not a required separate
text-model training stage. Initializing an omni backbone from a proven warehouse text checkpoint
may be tested later, but only as an explicit ablation against the clean audio/omni base.

## Dataset plan

Start from the existing deterministic five-tool contract and scenario generators, not from raw
catalog rows alone. Each scenario owns its expected call sequence, arguments, clarification, or
no-call outcome before any speech is generated.

The first pilot should include:

- all five tools with capped, visible per-family shares;
- zero-, one-, two-, and three-read cases;
- missing-field clarification and worker correction turns;
- confirmation, decline, stale confirmation, and mixed read/write rejection;
- unsupported/off-topic requests and catalog misses;
- exact SKUs, product names, locations, quantities, task IDs, and common spoken variants;
- several speakers, speaking rates, device distances, warehouse noise, and push-to-talk clipping;
- real TC501 recordings for evaluation, kept separate from synthetic voices and training speakers.

Synthetic TTS is useful for coverage, but promotion must depend on held-out human speech recorded
through the TC501 microphone. Split by scenario and speaker so paraphrases or audio renders of the
same underlying case cannot cross train/eval boundaries.

## Promotion gates

Use deterministic metrics as primary gates:

- native-call parse and format rate;
- exact ordered tool sequence;
- exact required arguments and zero invented identifiers;
- clarification/no-tool precision;
- zero unconfirmed or malformed writes reaching an adapter;
- SKU, quantity, location, and task-ID accuracy;
- cold/warm TTFS, total latency, decode rate, peak memory, thermal behavior, and battery impact;
- direct route versus ASR -> 350M on the identical spoken probe set.

Loss and an LLM judge are diagnostic only. The direct route is promoted only if it is safer and
materially faster or smaller than the existing cascade on the TC501.

## Immediate implementation gate

Before launching a paid training run:

1. Confirm the exact trainable base and export path that corresponds to the Android-compatible
   GGUF/runtime. The cookbook proves `LFM2.5-Audio-1.5B`; the excavator omni pilot uses an LFM text
   backbone plus a separate audio encoder. These are related patterns, not interchangeable files.
2. Add an audio-corpus builder that consumes the checked-in tool contract and validated dialogue
   records, not model-generated labels.
3. Add a small spoken behavioral probe suite covering every tool and safety outcome.
4. Run the unmodified checkpoint baseline before training so the improvement is measurable.
