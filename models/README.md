# Model files

Model weights are intentionally excluded from this repository. The Store Assistant uses the four
matched Q4_0 files listed in `LFM2.5-Audio-1.5B-Q4_0.manifest.json` from the
official `LiquidAI/LFM2.5-Audio-1.5B-GGUF` repository.

The app imports selected GGUF files into its private storage and verifies their
sizes and SHA-256 digests. The current Liquid runner requires the vocoder and
tokenizer arguments at initialization and only exposes conversational answering
through its interleaved text/audio mode. The app therefore requires all four matched
files for transcription, discards generated waveform data and displays only text/tool results.

Download and verify the text-output core first:

```bash
scripts/fetch_lfm_audio_models.sh --core-only
```

If the pinned runner requires its audio-output components even in text-only mode:

```bash
scripts/fetch_lfm_audio_models.sh --all
```

## P1B trained action model

The promoted schema-free build is `LFM2.5-350M-P1B-SchemaFree-v4-Q4_K.gguf`, quantized from the
promoted v4 Q8_0 checkpoint. It is 229,314,496 bytes with SHA-256
`4e422546677f4a7c4280625a787f66348d37029e047bee5708dc6930a2f45b84`. Android supplies only the
frozen short contract in `prompts/warehouse_tool_agent_schema_free_v1.md`; it does not send tool
schemas or signatures during inference.

The v4 Q8_0 file remains the quality-reference rollback. On the physical TC501, both variants
passed the strict functional suite. In the schema-free ablation both scored 8/10 tool matches,
5/5 checked arguments and two malformed outputs; Q4_K reduced warm median latency from 3,253 ms
to 2,772 ms.

`LFM2.5-350M-P1B-SchemaFree-v2-Q8_0.gguf` is retained as the schema-free rollback candidate. The
v3 correction-only checkpoint is rejected because it regressed parallel and conditional behavior.

`LFM2.5-350M-P1B.manifest.json` records the merged, smoke-tested outputs from
`sft_lfm25_350m_p1b_v1`. `Q4_K` is the TC501 deployment candidate; `Q8_0` is the
quality-reference build. Both contain the LoRA merged into the pinned base model.

The GGUF files are local build artifacts and should not be committed. Verify them with:

```bash
shasum -a 256 models/LFM2.5-350M-P1B-Q4_K.gguf \
  models/LFM2.5-350M-P1B-Q8_0.gguf \
  models/LFM2.5-350M-P1B-SchemaFree-v2-Q8_0.gguf \
  models/LFM2.5-350M-P1B-SchemaFree-v4-Q8_0.gguf \
  models/LFM2.5-350M-P1B-SchemaFree-v4-Q4_K.gguf
```

The Android action boundary accepts only the five frozen P1B tools. It parses Liquid-native
`<|tool_call_start|>...<|tool_call_end|>` output, removes null optional fields, then requires the
normal allowlist, schema, policy and confirmation checks before execution.
