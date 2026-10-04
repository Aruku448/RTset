#!/usr/bin/env python3
"""Compile pinned Prime atmosphere entries; compatibility probes never become game resources.

Requires only Python stdlib, Slang and spirv-val. Runtime integration is a separate step.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "third_party/prime-atmosphere-26.3"
EXPECTED = "2026.13.1-1-g84792eb15"
STAGES = ("transmittance", "directions", "incident", "moments", "multi_scattering", "ground", "sky")
CAPABILITIES = (
    "SPV_KHR_non_semantic_info", "SPV_GOOGLE_user_type", "spvSparseResidency", "spvMinLod",
    "spvFragmentFullyCoveredEXT", "spvGroupNonUniform", "spvGroupNonUniformBallot",
    "spvShaderInvocationReorderEXT", "spvRayTracingPositionFetchKHR",
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    if result.returncode:
        raise RuntimeError(f"tool failed ({result.returncode}): {command[0]}\n{result.stdout}")
    return result.stdout.strip()


def generate_abi(source, destination):
    # Only the scalar atmosphere interface is needed by these standalone compute entries.
    # Generate from the pinned upstream schema rather than duplicating physical constants.
    contract = json.loads(source.read_text())["atmosphereContract"]
    lines = [
        "// Generated from pinned Prime GPL-3.0-only ABI; see third_party/prime-atmosphere-26.3.",
        "#language slang 2026", 'module "prime_abi_types.slang";',
    ]
    for name, value in contract.items():
        if isinstance(value, str):
            continue
        constant = re.sub(r"(?<!^)(?=[A-Z])", "_", name).upper()
        kind = "uint" if isinstance(value, int) else "float"
        literal = str(value) + ("u" if kind == "uint" else "")
        lines.append(f"public static const {kind} PRIME_ATMOSPHERE_{constant} = {literal};")
    destination.write_text("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--slangc", required=True, help="absolute compiler path; keep its matching lib directory")
    parser.add_argument("--spirv-val", default="spirv-val")
    parser.add_argument("--compatibility-probe", action="store_true", help="explicit non-production compiler experiment")
    args = parser.parse_args()
    compiler = Path(shutil.which(args.slangc) or args.slangc).resolve(strict=True)
    version = run([str(compiler), "-version"])
    if version != EXPECTED and not args.compatibility_probe:
        raise RuntimeError(f"Slang version mismatch: required {EXPECTED}, actual {version}. "
                           "No production artifacts generated; --compatibility-probe is experimental only.")
    mode = "compatibility-probe" if args.compatibility_probe else "locked"
    if args.compatibility_probe:
        print(f"WARNING: experimental compiler {version}; expected {EXPECTED}; not packaged or deployed", flush=True)
    manifest = json.loads((VENDOR / "SOURCE-MANIFEST.json").read_text())
    for relative, expected in manifest["files"].items():
        if sha(VENDOR / relative) != expected:
            raise RuntimeError("pinned source digest mismatch: " + relative)
    output = ROOT / "build/prime-atmosphere" / mode
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="compile-", dir=output.parent) as temporary:
        work = Path(temporary)
        generated = work / "includes"
        generated.mkdir()
        abi = generated / "prime_abi_types.slang"
        generate_abi(VENDOR / "shaders/abi.json", abi)
        binaries = {}
        for stage in STAGES:
            source = VENDOR / f"shaders/entry/atmosphere/atmosphere_{stage}.compute.slang"
            binary = work / f"atmosphere_{stage}.comp.spv"
            command = [str(compiler), str(source), "-target", "spirv", "-profile", "glsl_460",
                       "-capability", "spirv_1_5"]
            for capability in CAPABILITIES:
                command.extend(["-capability", capability])
            command.extend(["-entry", "main", "-stage", "compute", "-allow-glsl",
                            "-matrix-layout-row-major", "-fvk-use-gl-layout", "-emit-spirv-directly",
                            "-warnings-as-errors", "all", "-O2", "-g0",
                            "-I", str(generated), "-I", str(VENDOR / "shaders"), "-o", str(binary)])
            run(command)
            run([args.spirv_val, "--target-env", "vulkan1.2", str(binary)])
            binaries[binary.name] = {"sha256": sha(binary), "bytes": binary.stat().st_size}
            print("compiled and validated", stage, binary.stat().st_size, flush=True)
        libraries = {str(p.relative_to(compiler.parent.parent)): sha(p)
                     for p in sorted((compiler.parent.parent / "lib").glob("libslang*.so*")) if p.is_file()}
        artifact = {
            "upstream_commit": manifest["upstream_commit"], "mode": mode,
            "required_compiler": EXPECTED, "actual_compiler": version,
            "compiler_sha256": sha(compiler), "compiler_libraries": libraries,
            "source_manifest_sha256": sha(VENDOR / "SOURCE-MANIFEST.json"),
            "generated_scalar_abi_sha256": sha(abi), "target": "spirv1.5/vulkan1.2",
            "runtime_integrated": False, "binaries": binaries,
        }
        # Validate the entire batch before publishing any files. Manifest is the completion marker.
        output.mkdir(parents=True, exist_ok=True)
        for name in binaries:
            (work / name).replace(output / name)
        (output / "prime_abi_types.slang").write_bytes(abi.read_bytes())
        record = work / "manifest.json"
        record.write_text(json.dumps(artifact, indent=2) + "\n")
        record.replace(output / "manifest.json")
    print("artifact manifest:", output / "manifest.json")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired, ValueError) as error:
        raise SystemExit(str(error))
