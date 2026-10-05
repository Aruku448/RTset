#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$(mktemp -d /tmp/rtset-post-smoke.XXXXXX)"
trap 'rm -rf "$build_dir"' EXIT
cd "$repo_dir"
source_shader=src/main/resources/rtest/shaders/post_processing.comp
glslangValidator -V --target-env vulkan1.2 "$source_shader" -o "$build_dir/sdr.spv"
glslangValidator -V --target-env vulkan1.2 -DHDR_OUTPUT=1 "$source_shader" -o "$build_dir/hdr.spv"
cmp "$build_dir/sdr.spv" "$source_shader.spv"
cmp "$build_dir/hdr.spv" src/main/resources/rtest/shaders/post_processing_hdr.comp.spv
cc -std=c11 -Wall -Wextra -O2 tools/gpu_post_processing_smoke.c -lvulkan -lm -o "$build_dir/smoke"
"$build_dir/smoke" "$build_dir/sdr.spv" 0
"$build_dir/smoke" "$build_dir/hdr.spv" 1
