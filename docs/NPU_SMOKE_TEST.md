# QCM6690 Hexagon NPU smoke test

Date: 2026-10-06
Device: Zebra TC501, `volcano`, QCM6690
Runtime: official llama.cpp Snapdragon Android build `b11456` (`51ce9c11a`)
Model: `LFM2.5-350M-Warehouse-Stable-Q4_K.gguf`, 354.48M parameters, 216.41 MiB

## Compatibility result

The official Snapdragon backend discovered the Adreno 810 GPU and opened the Hexagon v73 device
as `HTP0`. The complete LFM2.5-350M Q4_K model loaded and executed with `--device HTP0 -ngl 99`.
This confirms backend, model-architecture, quantization and device compatibility for a standalone
probe. The production APK remains CPU-only and was not modified by this test.

## Benchmark results

Short probe (`pp64`, `tg16`, four CPU threads):

| Offloaded layers | Prefill tok/s | Decode tok/s |
|---:|---:|---:|
| 0 | 312.39 | 71.19 |
| 4 | 385.33 | 54.34 |
| 8 | 316.77 | 15.54 |
| 12 | 365.31 | 37.77 |
| 99 (all supported) | 553.76 | 14.97 |

Production-shaped synthetic probe (`pp512`, `tg64`, batch 512, ubatch 128):

| Offloaded layers | Prefill tok/s | Decode tok/s |
|---:|---:|---:|
| 0 | 20.24 | 73.75 |
| 4 | 317.49 | 55.11 |
| 99 (all supported) | 599.91 | 16.93 |

## Interpretation

Full NPU offload strongly accelerates prefill but substantially slows autoregressive decode on this
Hexagon v73 implementation. Four-layer partial offload is the most promising tested compromise:
large-prefill throughput improved while decode retained most CPU speed. The official probe's generic
CPU backend differs from the APK's optimized ARM CPU libraries, so these numbers are directional and
must not be presented as an app-level speedup.

Before production integration, build a separate experimental APK/runtime, benchmark the exact
rendered warehouse prompt and output, verify tool-call parity, and measure thermal and memory behavior.
Keep the current CPU APK as the rollback.

## Exact server-contract follow-up

The official Snapdragon `llama-server` was then tested with the canonical system prompt, full five-tool
schema, production stop tokens and the actual stable checkpoint. The server used a 4096-token context,
one slot, four CPU threads, batch 512 and ubatch 128.

| Placement | Exact-request result |
|---|---|
| CPU only | Correct calls on all six prompts; first request 4.17 s, subsequent warm median 1.84 s, three-read request 2.88 s |
| Four HTP layers | First request 37.5 s; 2.64 decode tok/s; invalid invented tool syntax |
| Full HTP | First request 5.63 s; 543.19 prefill tok/s, 11.63 decode tok/s; invalid prose output instead of a tool call |

The current Hexagon v73 backend is therefore compatible enough to load and execute LFM2.5 Q4_K, but
it is not production-correct for this application. The `llama-bench` partial-offload result did not
translate to server inference. Do not integrate HTP offload into the APK without an upstream backend
change and a fresh exact-contract correctness gate.

## CPU runtime upgrade result

The CPU-only follow-up was completed with a static `b11456` server compiled for
`armv8.6-a+dotprod+i8mm`. It was first installed in a side-by-side probe APK and tested with the same
checkpoint, flags, tool schema and device test as the legacy APK.

| Measurement | Legacy APK | Promoted `b11456` APK |
|---|---:|---:|
| Capability-gate wall time | 83.2 s | 50.8 s |
| Uncached server/model load | 13.42 s | 13.24 s |
| Last single-tool request | 5.40 s | 3.22 s |
| Last-request decode | 41.4 tok/s | 45.3 tok/s |

A 0.56 s load and 38.0 s capability-gate run were also observed immediately after standalone
benchmarking, while the model pages were still cached by Android. Those are warm-cache observations,
not cold-start results. The runtime upgrade improves warm inference but does not materially eliminate
the first model load.

All four text-runtime device tests pass after retaining pending write context between conversation
turns and filtering that context to public tool-contract fields. The normal APK now uses this static
CPU runtime. The legacy binary remains selectable with `-PlegacyTextRuntime=true`. NPU offload remains
disabled.
