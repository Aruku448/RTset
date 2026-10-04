#!/usr/bin/env python3
"""Verify packaged kernels against reviewed exact-toolchain outputs and vendored sources.

This is an integrity gate, not a GPU correctness test. Recompile with compilePrimeAtmosphere
when updating sources; do not replace this record with a compatibility-probe manifest.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "third_party/prime-atmosphere-26.3"
RESOURCES = ROOT / "src/main/resources/prime/atmosphere/shaders"
NAMES = {f"atmosphere_{name}.comp.spv" for name in (
    "transmittance", "directions", "incident", "moments", "multi_scattering", "ground", "sky")}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    record = json.loads((VENDOR / "toolchain/LOCKED-ARTIFACTS.json").read_text())
    sources_path = VENDOR / "SOURCE-MANIFEST.json"
    sources = json.loads(sources_path.read_text())
    if (record["mode"] != "locked" or record["target"] != "spirv1.5/vulkan1.2"
            or record["actual_compiler"] != "2026.13.1-1-g84792eb15"
            or record["required_compiler"] != record["actual_compiler"]
            or record["upstream_commit"] != sources["upstream_commit"]
            or record["source_manifest_sha256"] != sha(sources_path)
            or set(record["binaries"]) != NAMES):
        raise ValueError("Packaged atmosphere kernels are not the reviewed locked build")
    for name, expected in sources["files"].items():
        if sha(VENDOR / name) != expected:
            raise ValueError(f"Vendored atmosphere source changed: {name}")
    for name, expected in record["binaries"].items():
        path = RESOURCES / name
        if path.stat().st_size != expected["bytes"] or sha(path) != expected["sha256"]:
            raise ValueError(f"Packaged atmosphere kernel differs from locked build: {name}")
    if {path.name for path in RESOURCES.glob("*.spv")} != NAMES:
        raise ValueError("Unexpected packaged atmosphere SPIR-V")
    print("Prime atmosphere: seven packaged kernels match reviewed exact-toolchain build")


if __name__ == "__main__":
    verify()
