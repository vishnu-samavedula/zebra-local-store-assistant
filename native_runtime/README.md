# Android native runtime

This directory distributes the existing app's llama.cpp b11565 Android arm64 runtime. No model weights, training files, Qualcomm runtime binaries, or device data are included.

```sh
python3 scripts/restore_native_runtime.py --check
python3 scripts/restore_native_runtime.py
```

The second command restores 11 shared libraries to `android/app/src/audioCpu/jniLibs/arm64-v8a/` for Android builds. Existing matching stripped or original libraries are preserved; different local libraries cause an error. Restored `.so` files remain ignored because the checked-in archive is their distribution source. No network access is needed to restore them.

`SHA256SUMS` covers the archive. `manifest.json` records individual hashes, original hashes, shared-library dependencies and provenance limits. The archive also includes the manifest and all accompanying notices. Copies were processed with `llvm-strip --strip-debug`; original local libraries were not modified. This is a binary distribution, not a claim of reproducible compilation or an unmodified upstream build.

## Platform and dependencies

- Android `arm64-v8a`; this app requires Android 12 or newer. Existing device testing was on Zebra hardware.
- The active app uses CPU inference. Other linked backend libraries are retained to satisfy the existing binary dependency graph; their presence is not evidence of acceleration support.
- Android system libraries and the device's `libOpenCL.so` implementation are external dependencies, not redistributed here. Generic Android compatibility has not been established.
- The private audio model/projector, retrieval model and generated retrieval assets are still provisioned separately. Restoring this archive alone does not provision a working model.

## Licensing and attribution

Preserve the entire [notices](notices/) directory when redistributing the runtime. Android source builds include these files as app assets. The application's MIT license does not replace component licenses.

| Component | Accompanying terms and source |
| --- | --- |
| llama.cpp and ggml | MIT; [pinned upstream source](https://github.com/ggml-org/llama.cpp/tree/f9a95aeb2096d0c0218e0cd6e6d901cd37be798a) |
| cpp-httplib, nlohmann/json, rotate-bits, stb_image, YaRN, llamafile | Upstream notices retained from that source revision; MIT terms where specified |
| xxHash | BSD 2-Clause notice retained |
| SHA-256 helper, subprocess.h | Upstream public-domain notices retained |
| miniaudio | Upstream alternative public-domain / MIT No Attribution text retained |
| BoringSSL | Apache 2.0 and accompanying upstream text; [license source](https://github.com/google/boringssl/blob/0.20260929.0/LICENSE) |
| LLVM C++ runtime, C++ ABI runtime, compiler runtime | Upstream licenses, including LLVM exceptions, from the compiler revision recorded in the manifest |

BoringSSL symbols are present in the existing binary. Its notice comes from the default dependency tag in the matching llama.cpp source; the original linked dependency tag was not independently recoverable. It supports HTTPS functionality compiled into the HTTP stack; offline inference does not require using HTTPS. No new network behavior is enabled by sharing this archive.
