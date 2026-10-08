#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
experiment_dir="$PWD/build/radiance-cache-experiment"
mkdir -p "$experiment_dir"
./gradlew radianceCacheExperimentTest -Pradiance_cache_dump="$experiment_dir/contracts" --offline
cc -O2 -Wall -Wextra -Werror tools/radiance_cache_smoke.c -lvulkan -lm -o "$experiment_dir/smoke"
for fixture in contracts contracts-bound contracts-analytic; do
    "$experiment_dir/smoke" "$experiment_dir/$fixture.spv" "$experiment_dir/$fixture.seed" \
        "$experiment_dir/$fixture.expected" | tee "$experiment_dir/$fixture.log"
done
./gradlew radianceCacheExperimentTest -Pradiance_cache_dump="$experiment_dir/benchmark" \
    -Pradiance_cache_queries=1824438 --offline
"$experiment_dir/smoke" "$experiment_dir/benchmark.spv" "$experiment_dir/benchmark.seed" \
    "$experiment_dir/benchmark.expected" | tee "$experiment_dir/benchmark.log"
