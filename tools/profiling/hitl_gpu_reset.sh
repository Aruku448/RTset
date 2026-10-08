#!/usr/bin/env bash
# Adapted from diagnosing-bugs/scripts/hitl-loop.template.sh.
# Requires the original movement scenario; never installs or launches a candidate.
set -euo pipefail
step() {
    printf '\n>>> %s\n' "$1"
    read -r -p '    [Enter when done] ' _
}
rt_workspace="$(cd "$(dirname "$0")/../.." && pwd)"
rt_label="${1:-cpu-batch-baseline}"
rt_seconds="${2:-180}"
step "重启待测版本${rt_label}，进入原场景，开启光追；保持与故障时相同分辨率、NRD/ReSTIR设置。"
printf '\n>>> 接下来%s秒重复原来的移动、转视角与新区块加载操作；若重置，停止操作。\n' "$rt_seconds"
python3 "$rt_workspace/tools/profiling/watch_gpu_regression.py" --seconds "$rt_seconds" --label "$rt_label" --output "$rt_workspace/tmp/gpu-regression-${rt_label}-hitl-20261008"
