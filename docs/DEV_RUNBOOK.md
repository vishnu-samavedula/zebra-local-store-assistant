# Developer runbook

Updated 2026-10-20. Run commands from the repository root unless shown otherwise.

## Prerequisites

Use Android Studio with Android SDK 36, Android platform tools, the project Gradle wrapper, and a compatible Java toolchain. The app targets Android 35, requires Android 31 or newer, and packages arm64 native libraries. Python 3 is required for source-only harness checks; the embedded Android Python version is 3.11.

Full inference also requires separately provisioned artifacts:

- Compatible llama.cpp audio runtime libraries under `android/app/src/audioCpu/jniLibs/arm64-v8a/`.
- The private checkpoint/projector matching the hashes and app-private location in `CpuAudioRuntime.kt`.
- The ColBERT model matching `DeviceColbertRetriever.kt` and generated procedure vectors under `sop_retrieval/generated/colbert-baseline/`.

Model artifacts, recordings, runtime binaries and training materials are not distributed in this public repository. Provision them through the approved private workflow. Do not change pinned hashes just to load an incompatible artifact.

## Source checks

```sh
python3 scripts/validate_tool_contracts.py
python3 -m unittest discover -s tests
cd android
./gradlew testDebugUnitTest assembleDebug
```

The Python suite in a public checkout contains app/harness tests. Additional local private training tests are intentionally excluded. A full Android build expects the local native/retrieval artifacts above; successful local builds do not prove that those artifacts are distributed with a clone.

## Device checks

```sh
adb devices
export ANDROID_SERIAL=YOUR_DEVICE_SERIAL
adb -s "$ANDROID_SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" shell am start -n com.example.zebralocalai/.MainActivity
```

Installing is a deliberate device action. Use the existing app data and provisioned models; avoid uninstalling, clearing data, or starting a second generation server during a comparison. Some instrumented tests require private audio fixtures and artifacts, so they are not source-only acceptance checks.

## Smoke journeys

1. General: ask stock for one catalog product without variants, then a variant-specific request. Check that unsaid filters are absent.
2. General: locate a product; look up two product names without variant/category filters.
3. Replenishment: supply a product, required variant dimensions, and positive additional quantity. Preview alone changes nothing. Confirm once: one ticket and one matching stock increment. Repeat confirmation: no duplicate effect.
4. SOP Advisor: request supported guidance. Show only applicable approved cards and the report offer. Decline: no ticket. Accept: preview, then a separate Submit.
5. Check assigned-task count/first two/freshness, missing-field clarification, Cancel, Clear, Stop, and mode isolation.

Check recognized wording separately from raw model tools and grounded harness results. A syntactically valid call can still have wrong arguments. A supplied-call harness test does not measure model accuracy.

## Diagnostics and privacy

Model Lab distinguishes generation timing from full post-recording processing. Reset starts a new latency window; it does not erase diagnostic evidence. Private turn traces and saved worker feeds can contain worker text. Do not commit logs, recordings, databases, credentials or device-private dumps.

The October 20 source check passed 179 local Python tests (including private training tests), 56 Android unit tests, and the debug build. These are recorded checks of that local snapshot, not a promise that every device or fresh unprovisioned checkout will run inference.

See [architecture](ARCHITECTURE.md) and [profiling](../audio600m_profiling_runbook.md).
