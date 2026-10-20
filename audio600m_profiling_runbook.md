# Device testing and profiling runbook

Updated 2026-10-20. The active app uses one app-owned llama.cpp CPU generation server for voice and typed requests, plus a separate ColBERT embedding server for procedure retrieval. Private checkpoint development and detailed training evidence are maintained outside the public tree.

## Current configuration

| Setting | App value |
|---|---|
| Generation backend | CPU; accelerator offload disabled |
| Inference / batch threads | 4 / 4 |
| Context capacity | 4,096 positions |
| Output ceiling | 96 tokens |
| Temperature | 0 |
| Prompt caching | Enabled |
| Model-facing context | Pending tool/slots and latest request; no completed-feed replay |

The implementation and artifact identities are in `CpuAudioRuntime.kt` and `CpuModelServer.kt`. Do not substitute older checkpoint or experimental runner settings into this baseline.

## Measurement boundaries

- **Audio encoding:** original waveform features processed by the model encoder.
- **Language prefill:** process model input and pending-state positions.
- **First token/text:** distinguish the native first token from the server's first visible text measurement.
- **Decode:** subsequent generated tokens; record output count and finish reason.
- **Generation total:** model/server request interval; it excludes separate Android recognition and harness retrieval.
- **Processing time under the reply:** post-recording transcription plus generation when used and harness/retrieval work. Recording duration and UI rendering are excluded.
- **Memory:** name the metric explicitly. Host process peak resident memory does not include every shared/accelerator allocation.

Model Lab displays generation latency in milliseconds, voice/text filters, percentile curves, Refresh and Reset. Reset starts a fresh reporting window while retaining diagnostic logs. The reply's grey processing time is not the same metric as the generation chart.

## Paired comparison procedure

1. Record checkpoint/projector hashes, native runtime identity, configuration, device/app state, and input fixture identity.
2. Freeze the cases before inference. Include product-only and variant reads, two-product reads, replenishment, pending-slot replies, report/task requests, and a no-write negative.
3. Run conditions sequentially with equal output limits. Preserve all completed generations and errors. Record initialization and first request separately from warm requests.
4. Score raw tools and arguments before harness repair. Compare latency on correct equivalent outputs; a faster wrong/shorter response is not a speed improvement.
5. Capture input/output counts, encoding, prefill, state-transfer cost where relevant, first token, decode, total, host peak memory and available memory.
6. Preserve app/private-data state. Experimental scripts do not create tickets or execute inventory tools. Stop the experiment if the app state changes unexpectedly or memory headroom is insufficient.

A few repeats do not establish reliable p95 latency. Synthetic speech and synthetic noise do not establish real-worker recognition or warehouse reliability. Independent transcription quality, raw model interpretation, harness behavior and end-to-end latency are separate measurements.

## Acceleration status

The isolated Qualcomm Neural Network audio encoder and language prefill → complete state transfer → llama.cpp CPU decode path was tested on Zebra. The latest small screen used twelve clean synthetic voice requests, three repeats and three runtime conditions. Outputs matched across runtimes; the same two cases failed across them. The handoff checks passed. No reliable speed benefit was established for these short requests, and host memory increased. The app continues to use CPU.

The earlier Qualcomm Neural Network-only runner used neural processing unit encoding/prefill and its CPU backend for decode; it was not all-neural-processing-unit inference. Its smaller fixture study is not directly comparable with the latest screen. No long-context speed advantage or fully accelerated end-to-end generation has been demonstrated. Experimental compiled graphs, selected audio and detailed evidence are local/private artifacts.

## Worker acceptance

Check short clean speech, real worker speech/noise, transcript completeness, correct raw calls/arguments, confirmation-bound writes, local retrieval, and visible response formatting. Keep model evaluation results separate from supplied-call harness checks and cloud measurements. Device smoke success does not establish broad model acceptance.

See [developer operations](docs/DEV_RUNBOOK.md), [runtime architecture](docs/ARCHITECTURE.md), and [worker contract](contracts/worker_experience_v2.md).
