# Developer Runbook

Last updated: 2026-09-23. This runbook covers the Store Assistant and its integrated P1B pilot.
Read [CURRENT_STATE_AND_ROADMAP.md](CURRENT_STATE_AND_ROADMAP.md) first.

## Repositories

- Android project: `android/` in this repository
- LQH CLI/source: sibling checkout `../lqh`
- LQH backend: sibling checkout `../lqh_backend`
- Local Store Companion reference: sibling checkout `../demos/demos/store-companion`

Before using another repository, read its local `AGENTS.md` and current documentation. Do not infer
current LQH commands or schemas from older examples.

## Host and device

Use Android Studio's bundled JBR:

```text
/Applications/Android Studio.app/Contents/jbr/Contents/Home
```

Use the checked-in Gradle wrapper. The verified target is a TC501 running Android 15/API 35,
`arm64-v8a`, platform `volcano`, QCM6690. See [SYSTEM_CHECK.md](SYSTEM_CHECK.md) for the
full host/device record and red flags.

Verify the device before work:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.soc.model
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.sdk
adb shell dumpsys battery
```

Keep the device unlocked and powered for sustained inference.

## Build and test

From `android/`:

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew testDebugUnitTest assembleDebug
```

APK:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Install without clearing existing model/database state:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.example.zebralocalai
adb shell am start -W -n com.example.zebralocalai/.MainActivity
```

Run physical-device tests only when the matched model bundle is staged. The connected-test runner
may uninstall the application afterward, which removes app-private models and SQLite data:

```bash
./gradlew connectedDebugAndroidTest
```

Current device tests cover:

- Persistent Liquid server and known-WAV ASR.
- JSON-seeded SQLite catalog lookup.
- Persistent issue insert/read-back and JSON payload.
- Trained P1B Q8_0 cold load plus real native inference for all five tools and an independent
  two-read request.
- Schema-free v4 correction handling (`10` corrected to `12`) and conditional-replenishment
  read-first routing.

After connected tests, reinstall the APK and reprovision the model bundle before handing the device
back for manual testing.

## Model bundle

The Store Assistant uses the matched official LFM2.5-Audio-1.5B Q4 bundle:

- `LFM2.5-Audio-1.5B-Q4_0.gguf`
- `mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf`
- `vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf`
- `tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf`

The current server expects all four even though the app requests text output only. The app verifies
the pinned length and SHA-256 values in `ModelBundle.kt` before marking the bundle runnable.

Production-shaped provisioning uses the Android Storage Access Framework. A development staging
copy also exists at `/data/local/tmp/zebra-lfm-models/`; do not treat unrelated debug WAV/log files
in that directory as application data.

App-private target:

```text
files/models/lfm25-audio-q4/
```

Never check model weights into Git or bundle them into the APK.

## P1B model and cold/warm lifecycle

The deployed demo model is `models/LFM2.5-350M-P1B-SchemaFree-v4-Q4_K.gguf` (229,314,496 bytes,
SHA-256 `4e422546677f4a7c4280625a787f66348d37029e047bee5708dc6930a2f45b84`). Its
app-private target is `files/models/lfm25-p1b/`. Android uses the frozen schema-free contract and
sends no tool definitions during inference. Opening the app starts one explicit cold preparation of the
audio and P1B servers. After it reports **both models warm and resident**, recording a request does
not reload either model. Clearing a result also preserves the warm processes. App process death,
an OS kill, or a server failure requires another cold preparation.

Run the focused physical-device model test without uninstalling app data:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.example.zebralocalai.inference.LiquidTextServerDeviceTest \
  com.example.zebralocalai.test/androidx.test.runner.AndroidJUnitRunner
```

## P1 store-agent manual check

Open **Store Assistant**. The microphone sends an ASR transcript to P1B. The recipe tiles
send their displayed text directly to the same P1B inference path for repeatable demos; neither
route hardcodes a tool choice.

For conversational continuation, start with `Request replenishment for SKU-2202-NVY-M`, tap
**Add missing details**, then say `Add exactly 12 units to B2-04`. The second turn must produce a
complete confirmation-gated proposal. **Change request by voice** can correct a pending proposal,
for example `Actually make that 14 units`. A request is limited to three worker turns; **New
request** clears its state. Confirming a write closes the session so inherited context cannot
repeat a completed mutation.

The P1B response budget is 256 tokens. This is intentionally bounded but large enough for three
Liquid-native read calls even when the checkpoint emits null optional arguments. Any response
ending with llama.cpp `stop_type=limit` is treated as truncated and executes nothing.

### Inventory read

Say:

```text
How many black GTX shoes do we have?
```

Expected application flow:

```text
ASR transcript
  -> trained P1B native tool inference
  -> strict parser and policy validation
  -> typed inventory_search request
  -> parameterized SQLite catalog lookup
  -> verified deterministic answer
```

Expected fixture result is the black TrailBlaze GTX size 10 at A3-05 with 8 pairs available.

### Confirmed report

Say:

```text
Report three damaged cartons of SKU 5520 in B7.
```

Expected behavior:

1. App displays a human-readable report review.
2. Review includes SKU, quantity, location, category, priority and `OPEN` status.
3. No row is written before confirmation.
4. Confirming creates an `ISS-######` record.
5. App reads the row back before displaying verification.
6. Metrics remain visible below the worker-facing response.

Test missing fields, ambiguity, cancellation and unsupported conversation. The application must
clarify or reject instead of guessing or silently selecting a state-changing action.

## Deterministic data layer

Readable seed:

```text
seed_data/warehouse_catalog.jsonl
seed_data/warehouse_tasks.jsonl
```

Runtime source of truth:

```text
app-private databases/warehouse-p1a.db
```

The current catalog contains 49 SKU-level rows across shoes, T-shirts, wallets, slides, shirts,
pants, jeans and warehouse supplies. Each row has canonical product ID, brand, category, color,
size, unit, barcode, location, aliases, inventory and extra attributes. The task fixture contains
24 catalog-grounded pick, putaway, receive and replenish tasks. The Android build packages these
same LQH-readable JSONL files and imports them into SQLite.

Search currently performs exact four-digit SKU matching plus fixed lexical scoring over rows read
from SQLite. Given the same parsed request and database state, it is deterministic. The model does
not generate SQL. The integrated P1B order is:

```text
transcript
  -> generative LFM2.5-350M constrained action
  -> strict native parse and schema validation
  -> optional catalog candidate resolution
  -> native policy and typed Kotlin tool request
  -> parameterized SQLite repository operation
```

For a confirmed issue, SQLite stores the structured fields and a complete JSON audit/export
snapshot containing the generated issue ID. A loose JSON file is not used as mutable state.

Changing the asset after a database has already been created requires an explicit schema/data
migration or clearing the debug app's data. Do not rely on reinstall-with-`-r` to reseed an existing
database.

## Audio and privacy

- Recording is bounded by explicit user taps; there is no always-listening service.
- Input is 16 kHz mono PCM WAV.
- P1 uses the `Perform ASR.` prompt and text modality.
- Temporary `zebra-input-*.wav` files are deleted after success, error or cancellation.
- Stale recognized temp filenames are cleaned on recorder initialization after a crash.
- Server log tail is capped in memory and is not written by the app.
- The app does not create output audio.

## P1B preparation; do not start paid work implicitly

P1B uses the instruction-tuned generative `LiquidAI/LFM2.5-350M`. It produces one constrained
warehouse call block with variable arguments, or a clarification/rejection/grounded final answer.
The frozen tools are `inventory_search`, `location_contents`, `get_task_status`, `report_issue`
and `request_replenishment`. The current app supports up to three independent reads and one
confirmation-gated write proposal. Dependent/sequential chains remain planned. The bidirectional
`LFM2.5-Encoder-350M` is not the primary P1B model; retain it as a possible later retrieval,
reranking or fast-routing specialist.

Use the LQH catalog ID `lfm2.5-350m`, not `lfm2.5-350m-base`, for the first baseline and SFT. LQH's
assistant-only training path expects the instruct checkpoint's generation-aware chat template.
The exact system prompt, tool schemas and serialized output grammar must match across the baseline,
datasets, evaluation and Android runtime.

Follow [P1B_TRAINING_PLAN.md](P1B_TRAINING_PLAN.md). Before any job:

1. Read current LQH and LQH-backend repository instructions.
2. Record the exact LQH revisions.
3. Run `lqh hello`, inspect project summary/status and follow the relevant LQH stage skill.
4. Read and validate root `SPEC.md` before data generation.
5. Confirm where generated data and checkpoints will be written.
6. Review all 10 smoke samples and audit a 100-sample synthetic batch before scaling.
7. Validate the mobile runtime/export plan with a pilot artifact.
8. Check account credit and obtain an explicit spend ceiling before cloud generation, judging or
   training.

LQH 0.23.0 can own the generative spec → data → filter → baseline → SFT → eval pipeline. Its cloud
training path already uses remote GPU infrastructure, so do not create a separate custom Modal
trainer unless the standard path is proven insufficient. Publishing and production deployment are
separate approvals and are not part of P1B training.

Do not lower a checkpoint to the TC501 until locked evaluation and quantization regression pass.

## Metrics

Store Assistant UI metrics:

- Audio TTFS in milliseconds.
- Audio decode tokens/second.
- Audio total time.
- Tool-model TTFS, decode tokens/second and total generation time.
- Catalog search time.
- Tool time for writes.
- End-to-end total time.

The live P1 UI reports cold preparation separately from warm request work, plus ASR TTFS/decode,
P1B total generation time and prefill/decode rates, search/tool time and end-to-end latency.

Useful diagnostics:

```bash
adb shell dumpsys meminfo com.example.zebralocalai
adb shell dumpsys thermalservice
adb shell dumpsys battery
adb shell top -b -n 1
adb logcat -d -t 500
```

## Troubleshooting order

1. APK installation, process and microphone permission.
2. Four model filenames, sizes and hashes.
3. Native libraries and `arm64-v8a` packaging.
4. Persistent loopback server startup.
5. Known-WAV ASR.
6. Live AudioRecord/WAV capture.
7. Transcript normalization/entity extraction.
8. Generated action format, parser result and field validity.
9. Tool allowlist, policy and typed request validation.
10. SQLite candidate/result or issue transaction.
11. Confirmation and read-back verification.

Change one subsystem at a time. Preserve unrelated workspace changes and never delete app-private
data or staged models without resolving the exact target and obtaining appropriate authorization.

## Completion record

For each promoted phase, record:

- Application commit and APK SHA-256.
- Runtime/model repository revisions and file hashes.
- TC501 build fingerprint.
- Dataset manifest and LQH versions for trained models.
- Checkpoint, export and quantization identifiers.
- Accuracy and safety metrics.
- Cold/warm latency, memory, battery and thermal observations.
- Known limitations and rollback artifact.
