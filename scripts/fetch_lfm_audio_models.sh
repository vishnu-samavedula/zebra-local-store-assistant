#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
destination="$repo_root/models/downloads"
base="https://huggingface.co/LiquidAI/LFM2.5-Audio-1.5B-GGUF/resolve/11446932f83474ac91eb8b03c4892a7e74eb3db6"
mode="${1:---core-only}"

mkdir -p "$destination"

fetch() {
  name="$1"
  expected="$2"
  target="$destination/$name"
  if [ -f "$target" ]; then
    actual=$(shasum -a 256 "$target" | awk '{print $1}')
    if [ "$actual" = "$expected" ]; then
      echo "Already verified: $name"
      return
    fi
  fi
  curl -L --fail --show-error --continue-at - --output "$target" "$base/$name"
  actual=$(shasum -a 256 "$target" | awk '{print $1}')
  if [ "$actual" != "$expected" ]; then
    echo "SHA-256 mismatch for $name: expected $expected, got $actual" >&2
    exit 1
  fi
  echo "Verified: $name"
}

fetch "LFM2.5-Audio-1.5B-Q4_0.gguf" "3583bee853be20331ca342b0593fefd8acc43fb61a41ec6f1a1dc7465823e0d8"
fetch "mmproj-LFM2.5-Audio-1.5B-Q4_0.gguf" "6b483682c263b100f8cc8022d61507e446b1d320b9febc328e7960f72d03f7ea"

if [ "$mode" = "--all" ]; then
  fetch "vocoder-LFM2.5-Audio-1.5B-Q4_0.gguf" "423cfcb054f41b69a5706226c243abc96d2531c3aff1121f7a2ed17149b79c95"
  fetch "tokenizer-LFM2.5-Audio-1.5B-Q4_0.gguf" "01ec6afe4578bb1e02a4d43d87e7e5827d6b3d94d2d36912ee931b9c3050f1c1"
fi

echo "Model files are in $destination"
