#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
archive="${TMPDIR:-/tmp}/llama-liquid-audio-android-arm64.zip"
unpack="${TMPDIR:-/tmp}/llama-liquid-audio-android-arm64-unpack"
destination="$repo_root/android/app/src/main/jniLibs/arm64-v8a"
url="https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/main/runners/llama-liquid-audio-android-arm64.zip"
expected="6af842e1c6a5366c8db25447f77a7a175dd956c09402930cf5eacf409a5b00f8"

curl -L --fail --show-error --output "$archive" "$url"
actual=$(shasum -a 256 "$archive" | awk '{print $1}')
if [ "$actual" != "$expected" ]; then
  echo "Runner SHA-256 mismatch: expected $expected, got $actual" >&2
  exit 1
fi

mkdir -p "$unpack" "$destination"
unzip -o "$archive" -d "$unpack" >/dev/null
source_dir="$unpack/llama-liquid-audio-android-arm64"

cp "$source_dir"/libggml-base.so "$destination"/
cp "$source_dir"/libggml-cpu-android_armv8.0_1.so "$destination"/
cp "$source_dir"/libggml-cpu-android_armv8.2_1.so "$destination"/
cp "$source_dir"/libggml-cpu-android_armv8.2_2.so "$destination"/
cp "$source_dir"/libggml-cpu-android_armv8.6_1.so "$destination"/
cp "$source_dir"/libggml.so "$destination"/
cp "$source_dir"/libliquid-audio.so "$destination"/
cp "$source_dir"/libllama.so "$destination"/
cp "$source_dir"/libmtmd.so "$destination"/
cp "$source_dir"/llama-liquid-audio-cli "$destination/libllama-liquid-audio-cli.so"
cp "$source_dir"/llama-liquid-audio-server "$destination/libllama-liquid-audio-server.so"

echo "Installed pinned Liquid Android runner in $destination"
