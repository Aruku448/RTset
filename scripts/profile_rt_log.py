#!/usr/bin/env python3
"""Summarize existing RTest timing logs; never mix CPU and GPU samples.

Choose a stable configuration/time window. The log samples only some frames;
these are sample statistics, not a complete-frame profiler. No renderer changes.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics


def summarize(values):
    values = sorted(values)
    return {
        "n": len(values),
        "mean_ms": round(statistics.mean(values), 6),
        "median_ms": round(statistics.median(values), 6),
        "p95_ms": round(values[math.ceil(len(values) * 0.95) - 1], 6),
        "min_ms": values[0],
        "max_ms": values[-1],
    }


def profile(text, since=None, until=None):
    groups = {}
    counts = {}
    timestamps = []
    for line in text.splitlines():
        match = re.match(r"\[(\d{2}:\d{2}:\d{2})\]", line)
        if not match:
            continue
        stamp = match[1]
        if (since and stamp < since) or (until and stamp > until):
            continue
        if "RTest gpu_timing " in line:
            scope = "gpu"
        elif "RTest frame_timing " in line:
            scope_match = re.search(r"scope=(\w+)", line)
            if not scope_match:
                continue
            scope = scope_match[1]
        elif "RTest geometry CPU merge:" in line:
            scope = "geometry_merge_event"
        elif "RTest geometry publish:" in line:
            scope = "geometry_publish_event"
        else:
            continue
        timestamps.append(stamp)
        counts[scope] = counts.get(scope, 0) + 1
        group = groups.setdefault(scope, {})
        for key, unit, value in re.findall(r"(\w+)_(us|ms)=([\d.]+)", line):
            group.setdefault(key, []).append(float(value) / (1000 if unit == "us" else 1))
        duration = re.search(r"duration=(\d+) ms", line)
        if duration:
            group.setdefault("duration", []).append(float(duration[1]))
    return {
        "log_sha256": hashlib.sha256(text.encode()).hexdigest(),
        "requested_window": {"since": since, "until": until},
        "sample_window": {"first": timestamps[0] if timestamps else None,
                          "last": timestamps[-1] if timestamps else None},
        "scope_sample_counts": counts,
        "statistics": {scope: {key: summarize(values) for key, values in fields.items()}
                       for scope, fields in groups.items()},
        "notes": ["All durations are milliseconds.",
                  "CPU waits overlap GPU work; do not sum CPU and GPU durations.",
                  "Zero in sampled frames does not imply a stage is free or never executed.",
                  "GPU results are asynchronous; configuration-change boundaries are ambiguous.",
                  "Select a stable resolution, denoiser and offline-mode window before comparing.",
                  "P95 uses nearest rank; sparse samples are not full-frame P95."],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--since", help="HH:MM:SS, inclusive; single-day logs only")
    parser.add_argument("--until", help="HH:MM:SS, inclusive; single-day logs only")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    for value in (args.since, args.until):
        if value and not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d", value):
            parser.error("timestamps must be HH:MM:SS")
    if args.since and args.until and args.since > args.until:
        parser.error("--since must not exceed --until")
    result = profile(args.log.read_text(), args.since, args.until)
    result["source"] = str(args.log)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
