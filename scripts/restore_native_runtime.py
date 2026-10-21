#!/usr/bin/env python3
"""Verify and restore the repository's Android native runtime (no models)."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / "native_runtime"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without writing libraries")
    parser.add_argument("--destination", type=Path, default=ROOT / "android/app/src/audioCpu/jniLibs/arm64-v8a")
    args = parser.parse_args()
    manifest = json.loads((BUNDLE / "manifest.json").read_text())
    expected, name = (BUNDLE / "SHA256SUMS").read_text().strip().split()
    if name != "llama-b11565-android-arm64.zip":
        raise SystemExit("Unexpected runtime archive name")
    archive = BUNDLE / name
    if sha256(archive.read_bytes()) != expected:
        raise SystemExit("Runtime archive checksum mismatch")
    payloads = []
    with zipfile.ZipFile(archive) as zipped:
        expected_names = {"manifest.json"}
        expected_names.update("arm64-v8a/" + f["name"] for f in manifest["files"])
        expected_names.update("notices/" + n["name"] for n in manifest["notices"])
        if len(zipped.namelist()) != len(expected_names) or set(zipped.namelist()) != expected_names:
            raise SystemExit("Unexpected or duplicate archive entries")
        if zipped.read("manifest.json") != (BUNDLE / "manifest.json").read_bytes():
            raise SystemExit("Runtime manifest mismatch")
        for notice in manifest["notices"]:
            data = zipped.read("notices/" + notice["name"])
            if sha256(data) != notice["sha256"] or data != (BUNDLE / "notices" / notice["name"]).read_bytes():
                raise SystemExit("Runtime notice mismatch: " + notice["name"])
        for entry in manifest["files"]:
            if Path(entry["name"]).name != entry["name"]:
                raise SystemExit("Invalid library filename")
            data = zipped.read("arm64-v8a/" + entry["name"])
            if len(data) != entry["bytes"] or sha256(data) != entry["sha256"]:
                raise SystemExit("Runtime library mismatch: " + entry["name"])
            target = args.destination / entry["name"]
            if not args.check and target.exists():
                if sha256(target.read_bytes()) not in (entry["sha256"], entry["original_sha256"]):
                    raise SystemExit("Refusing to overwrite a different local runtime: " + str(target))
            payloads.append((target, data, int(entry["mode"], 8)))
    if args.check:
        print(f"Verified {len(payloads)} libraries and {len(manifest['notices'])} notices; no files changed.")
        return
    args.destination.mkdir(parents=True, exist_ok=True)
    restored = 0
    for target, data, mode in payloads:
        if not target.exists():
            target.write_bytes(data)
            target.chmod(mode)
            restored += 1
    print(f"Restored {restored} libraries; preserved {len(payloads) - restored} matching local libraries.")


if __name__ == "__main__":
    main()
