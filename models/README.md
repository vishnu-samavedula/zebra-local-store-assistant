# Model files

Model weights are intentionally excluded from this repository. P0 uses the four
matched Q4_0 files listed in `LFM2.5-Audio-1.5B-Q4_0.manifest.json` from the
official `LiquidAI/LFM2.5-Audio-1.5B-GGUF` repository.

The app imports selected GGUF files into its private storage and verifies their
sizes and SHA-256 digests. The current Liquid runner requires the vocoder and
tokenizer arguments at initialization and only exposes conversational answering
through its interleaved text/audio mode. P0 therefore requires all four matched
files, discards the generated waveform and displays only the generated text.

Download and verify the text-output core first:

```bash
scripts/fetch_lfm_audio_models.sh --core-only
```

If the pinned runner requires its audio-output components even in text-only mode:

```bash
scripts/fetch_lfm_audio_models.sh --all
```

## P1B trained action model

The promoted schema-free build is `LFM2.5-350M-P1B-SchemaFree-v4-Q8_0.gguf`, produced from the
promoted LQH checkpoint. It is 379,219,904 bytes with SHA-256
`649d93199edcbddb1dffe5013aaac6b7ffeede602208194bba2c175579bcc947`. Android supplies only the
frozen short contract in `prompts/warehouse_tool_agent_schema_free_v1.md`; it does not send tool
schemas or signatures during inference.

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
  models/LFM2.5-350M-P1B-SchemaFree-v4-Q8_0.gguf
```

The Android action boundary accepts only the five frozen P1B tools. It parses Liquid-native
`<|tool_call_start|>...<|tool_call_end|>` output, removes null optional fields, then requires the
normal allowlist, schema, policy and confirmation checks before execution.
