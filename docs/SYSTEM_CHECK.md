# Development System Check

Checked on 2026-09-21 for the Zebra TC501 local-LFM prototype.

## Host snapshot

| Item | Status | Notes |
| --- | --- | --- |
| Host | Ready | Apple Silicon (`arm64`), macOS 26.6.1 |
| Free disk | Ready | About 1.6 TiB available |
| Xcode command-line tools | Ready | Installed at `/Library/Developer/CommandLineTools` |
| Git | Ready | 2.50.1 |
| Homebrew | Ready | `/opt/homebrew/bin/brew` |
| CMake | Present | 4.4.3 from Homebrew; use the project-pinned Android SDK CMake for reproducible builds |
| Python | Present | 3.14.7; optional helper only, not an Android runtime dependency |
| Java/JDK | Ready through Android Studio | Bundled OpenJDK/Javac 25.0.3; the bare `java` shell command is not configured |
| Android Studio | Ready | 2026.1, Apple Silicon build |
| Android SDK | Ready | Present at `~/Library/Android/sdk`; Platforms/Sources 35 and 37.0 are installed |
| Platform Tools / `adb` | Ready | 37.0.1; both the SDK copy and `/opt/homebrew/bin/adb` are present |
| Android NDK | Ready | Side-by-side NDK 30.0.16248370 with Clang 21; pin after compatibility testing |
| SDK Command-line Tools | Ready | Revision 23.0 / Android CLI 1.0.16261425; executables are not on the shell `PATH` |
| SDK CMake / Ninja / LLDB | Ready | SDK CMake 4.1.2, Ninja 1.12.1 and NDK LLDB are installed; executables are not on the shell `PATH` |
| Git LFS | Missing | Recommended for repositories that publish large runner/model artifacts |
| Global Gradle | Not installed; not needed | Use the project's Gradle wrapper |

## Repository snapshot

No Zebra SDK, EMDK artifact, product manual, BSP, `.aar`, `.jar`, sample app or
downloaded model is currently present in this repository. The repository contains
only project planning documents.

This is the preferred baseline. Do not commit SDK archives, BSPs or model weights.
Record links, versions and hashes in the repository and keep downloaded binaries in
an ignored local directory.

## Required workstation installation

Install these before scaffolding the Android app:

1. Android Studio for Apple Silicon, using its bundled JBR/JDK.
2. Android SDK Platform 35 and matching Build Tools.
3. Android SDK Platform Tools (`adb`).
4. Android SDK Command-line Tools.
5. One side-by-side Android NDK version, pinned later in `android.ndkVersion`.
6. A project-pinned CMake version plus Ninja and LLDB from SDK Manager.
7. Git LFS, recommended for fetching Liquid's published runner bundles. Model
   weights must remain outside Git.

Do not install a global Gradle distribution. The checked-in Gradle wrapper will
pin it for the project.

## Android app dependencies by milestone

### P0: Hello Zebra World

- Kotlin and Android Gradle Plugin.
- AndroidX Core KTX and Lifecycle.
- Jetpack Compose BOM, Activity Compose and Material 3.
- Kotlin coroutines for moving inference off the UI thread.
- Android NDK/CMake JNI bridge.
- A pinned `llama.cpp` source revision, built for `arm64-v8a` only.
- A small official LFM2.5 GGUF checkpoint and a checked-in hash manifest.

No Zebra SDK is required for P0. It is a normal APK installed with ADB.

### P1: Audio input and one tool call

Add:

- Android `AudioRecord`; this is a platform API and needs no third-party library.
- `android.permission.RECORD_AUDIO` and runtime permission handling.
- Kotlin serialization for strict parsing of the model's proposed tool call.
- The matched LFM2.5-Audio GGUF main model and audio projector. Only add the
  tokenizer/vocoder files if testing generated audio rather than Android TTS.
- Liquid's pinned LFM2.5-Audio Android arm64 runner for the first device proof.

The published Android runner is useful for an ADB smoke test, but it is not yet the
final app architecture. The production-shaped app needs the runner functionality
linked behind JNI or another in-process native interface; the app must not shell
out to an executable.

Android `TextToSpeech` can provide the first spoken "I am here" response without
loading the LFM vocoder. Keep text output as the acceptance criterion until audio
input and tool routing are stable.

### Warehouse demo additions

- DataWedge intent integration for barcode scans: no Zebra Maven dependency.
- Android SQLite APIs for the current 49-SKU catalog, task fixture and issue log; Room/KSP is unnecessary at
  this scale and may be reconsidered only if schema complexity grows.
- CameraX only when the VLM milestone begins.
- WorkManager only when deferred sync is implemented.

## Zebra-specific dependency decision

Use the lightest supported interface for each feature:

| Need | Interface | Extra SDK dependency? |
| --- | --- | --- |
| Microphone, camera, files, TTS | Standard Android APIs | No |
| Barcode data in the warehouse demo | Zebra DataWedge intents | No |
| Programmatic DataWedge profile setup | DataWedge intent APIs | No |
| Fine-grained Zebra hardware/control APIs | EMDK for Android | Yes, `compileOnly` from Zebra Maven |
| Fleet policy/device configuration | MX through EMDK Profile Manager, StageNow or EMM | Depends on deployment path |
| Direct QCM6690 NPU execution | Qualcomm QNN SDK plus compatible backend/custom integration | Yes; explicitly deferred |

Zebra recommends DataWedge rather than EMDK's barcode APIs for scanning. Therefore
EMDK should not be added to P0 or P1. If a later requirement truly needs EMDK, pin
the artifact version from Zebra's Maven repository; never use a floating `+`
version. The EMDK runtime is built into modern Zebra OS images, so a separate
runtime should not be copied into this repository.

## Device-side checks after `adb` is installed

Connect and unlock the TC501, accept the USB-debugging fingerprint, then run:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.product.board
adb shell getprop ro.soc.model
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys media.audio_flinger
adb shell pm list packages | grep -E 'datawedge|emdk'
adb shell dumpsys media.camera
adb shell df -h /data
```

Also record the versions visible in the DataWedge and About screens. Zebra API
behavior is tied to the installed OS/BSP, MX, DataWedge and EMDK runtime versions,
not just the hardware model name.

Current connection result: the TC501 test device is connected and authorized
and reports normal ADB `device` state over USB.

## Verified TC501 snapshot

| Item | Observed value |
| --- | --- |
| Manufacturer/model | Zebra Technologies TC501 (`TC501W`, device `TCX01WU`) |
| Board / SoC | `volcano` / Qualcomm QCM6690 |
| ABI / CPU | `arm64-v8a`, 8 online Armv8 cores with dot-product, i8mm and BF16 CPU features |
| RAM | 11,866,300 kB total; about 6.7 GiB available at inspection time |
| Data storage | 221 GB total, 210 GB available |
| Display | 1080 x 2160 at 480 dpi |
| Android | Android 15, API 35, security patch 2026-02-05 |
| Zebra build | `15-16-02.03-VG-U00-STD-ERS-04` |
| Verified boot | Green; bootloader reports locked |
| DataWedge | 15.0.53 |
| EMDK | `com.symbol.emdk` system shared library and JAR are present |
| Cameras | Two Camera2 devices: rear and front; full/manual/RAW features advertised |
| Audio | Microphone, low-latency audio and pro-audio features advertised |
| GPU | Qualcomm Adreno 810; OpenGL ES 3.2 and Vulkan compute advertised |
| NPU/QNN | DSP RPC infrastructure is present, but no public `libQnn*` runtime was exposed in standard system/vendor library paths |
| Battery at inspection | 2%, USB charging at a reported maximum of 0.9 A |

## Gates and red flags

- **Host build gate passed:** the Java, Android SDK 35, ADB, command-line tools,
  NDK, SDK CMake, Ninja and LLDB components needed to scaffold and compile are
  present. Their command-line directories should be exported by the project
  environment script rather than relying on global shell configuration.
- **Battery:** charge the TC501 above 50% or use a powered cradle before sustained
  inference and thermal testing. The inspection found it at 2%.
- **Audio runner integration:** Liquid publishes an Android arm64 executable, but
  an app-embeddable API still needs to be validated before P1 is considered done.
- **Tool calling:** treat model output as untrusted. Kotlin owns the allowlist,
  schema validation, authorization and execution; no generated code is executed.
- **NPU:** `llama.cpp` CPU inference does not automatically use the Qualcomm NPU.
  The device exposes Qualcomm DSP RPC components but no public QNN runtime in the
  normal system/vendor library paths. QNN requires a separate SDK/backend path,
  app-shipped compatible libraries and redistribution/licensing review. It is not
  a plumbing-test dependency.
- **Thermals:** sustained CPU inference on a rugged handheld can throttle. Capture
  cold and warm latency, RSS, battery drain and temperature in release builds.
- **Android 14+ DataWedge latency:** Zebra documents a delay for ordinary broadcasts
  sent to DataWedge and recommends ordered broadcasts for faster API delivery.
- **Enterprise policy:** USB debugging, unknown-source installation, microphones or
  cameras may be disabled by MX/EMM policy even when the hardware supports them.
