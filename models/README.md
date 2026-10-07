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

The active deployment artifact is `LFM2.5-350M-Warehouse-Stable-Q4_K.gguf`. It is 229,314,496
bytes with SHA-256 `79863ccf4cd66b5d7532d4ac3f2cb9e8de65164ebd2b8251574fadf363ed1a15`.
It contains the accepted LoRA merged into the pinned LFM2.5-350M base. Android renders the
canonical prompt and five tool definitions through Liquid's chat template before inference.

The untouched official comparison model is retained only at
`downloads/LFM2.5-350M-Q4_K_M.gguf`; it is not the application's tool model. Historical P1B GGUFs
and the superseded local LoRA bundle were removed after the stable artifact passed the device gate.

Model files are local build artifacts and must not be committed. Verify the retained text models
with:

```bash
shasum -a 256 models/LFM2.5-350M-Warehouse-Stable-Q4_K.gguf \
  models/downloads/LFM2.5-350M-Q4_K_M.gguf
```

The Android action boundary accepts only the five frozen P1B tools. It parses Liquid-native
`<|tool_call_start|>...<|tool_call_end|>` output, removes null optional fields, then requires the
normal allowlist, schema, policy and confirmation checks before execution.
