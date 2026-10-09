#!/usr/bin/env python3
"""Verify pinned NVIDIA runtimes and the privately versioned bridge before packaging."""
import hashlib
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
lock = json.loads((root / "third_party/streamline/sdk-lock.json").read_text())
directory = root / "src/main/resources/rtest/natives/windows-x86_64/streamline"
for name, expected in lock["runtimeHashes"].items():
    actual = hashlib.sha256((directory / name).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"DLSS resource mismatch: {name}: {actual} != {expected}")
unexpected = set(p.name for p in directory.iterdir()) - set(lock["runtimeHashes"])
if unexpected:
    raise SystemExit(f"Unexpected DLSS package files: {sorted(unexpected)}")
print(f"Verified Streamline {lock['streamlineVersion']}, DLSS {lock['dlssVersion']}, {len(lock['runtimeHashes'])} runtime files")
