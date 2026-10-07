#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /absolute/path/to/android-ndk"
  exit 2
fi

project_root="$(cd "$(dirname "$0")/.." && pwd)"
ndk_root="$1"
runtime_tag="b11456"
archive_sha256="bfd95c10deebae3562b4bbcddeac7b399b4e5372ca425dd8751cbf58ec9e5a75"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/zebra-llama-runtime.XXXXXX")"
archive_path="$work_dir/llama.cpp.tar.gz"
source_dir="$work_dir/llama.cpp-$runtime_tag"
build_dir="$source_dir/build-android-static"
output_path="$project_root/android/app/src/main/jniLibs/arm64-v8a/libllama-text-server-next.so"

cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

if [[ ! -f "$ndk_root/build/cmake/android.toolchain.cmake" ]]; then
  echo "Android NDK not found at: $ndk_root"
  exit 2
fi

curl --fail --location --silent --show-error \
  "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$runtime_tag.tar.gz" \
  --output "$archive_path"

actual_sha256="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
if [[ "$actual_sha256" != "$archive_sha256" ]]; then
  echo "Archive checksum mismatch: $actual_sha256"
  exit 1
fi

tar -xzf "$archive_path" -C "$work_dir"
cmake -S "$source_dir" -B "$build_dir" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DGGML_STATIC=ON \
  -DGGML_OPENMP=OFF \
  -DGGML_OPENCL=OFF \
  -DGGML_HEXAGON=OFF \
  -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON
cmake --build "$build_dir" --target llama-server --parallel 8
"$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip" "$build_dir/bin/llama-server"
mkdir -p "$(dirname "$output_path")"
cp "$build_dir/bin/llama-server" "$output_path"
echo "Installed $runtime_tag CPU runtime at $output_path"
