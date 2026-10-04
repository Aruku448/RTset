#!/usr/bin/env python3
"""Measure observed RT capture cycles, separately from GPU/CPU merge time.

Coarse log timestamps quantize latency to seconds. A cycle spans the most recent
capture-start record to its full/dirty apply or stale discard. This is an
observation harness, not a synthetic renderer regression test.
"""
import argparse
import json
from pathlib import Path
import re


def profile(text, since=None, until=None):
    cycles = []
    pending = None
    for line in text.splitlines():
        timestamp = re.match(r"\[(\d{2}:\d{2}:\d{2})\]", line)
        if not timestamp:
            continue
        stamp = timestamp[1]
        if (since and stamp < since) or (until and stamp > until):
            continue
        hour, minute, second = map(int, stamp.split(":"))
        seconds = hour * 3600 + minute * 60 + second
        start = re.search(r"RTest queued incremental capture of (\d+) sections within (\d+) chunks", line)
        if start:
            pending = {"start": stamp, "seconds": seconds, "sections": int(start[1]),
                       "logged_chunk_window": int(start[2]), "kind": "full"}
        elif pending and "RTest queued " in line and "dirty-section updates" in line:
            pending["kind"] = "dirty"
        elif pending and any(marker in line for marker in (
                "RTest applied full scene snapshot", "RTest applied dirty-section update",
                "RTest discarded stale full scene finalization", "RTest discarded stale dirty-section")):
            cycles.append({"start": pending["start"], "end": stamp, "sections": pending["sections"],
                           "logged_chunk_window": pending["logged_chunk_window"], "kind": pending["kind"],
                           "duration_s_coarse": seconds - pending["seconds"],
                           "outcome": "stale" if "discarded stale" in line else "applied"})
            pending = None
    return {"cycles": cycles, "counts": {"total": len(cycles),
            "stale": sum(c["outcome"] == "stale" for c in cycles)},
            "notes": ["Observed timestamps are second-resolution, not CPU compute durations.",
                      "Starts outside the requested window and unfinished cycles are not included.",
                      "Cancellation/replacement without an end record is not measured.",
                      "logged_chunk_window retains log wording; it is not assumed to be a chunk count."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--since")
    parser.add_argument("--until")
    parser.add_argument("--max-capture-seconds", type=float)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    for stamp in (args.since, args.until):
        if stamp and not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d", stamp):
            parser.error("timestamps must be HH:MM:SS")
    if args.since and args.until and args.since > args.until:
        parser.error("since must not exceed until")
    result = profile(args.log.read_text(), args.since, args.until)
    result["requested_window"] = {"since": args.since, "until": args.until}
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output)
    else:
        print(output, end="")
    if args.max_capture_seconds is not None:
        if not result["cycles"]:
            parser.exit(2, "No completed capture cycles; insufficient evidence.\n")
        failures = [c for c in result["cycles"] if c["duration_s_coarse"] > args.max_capture_seconds]
        if failures:
            parser.exit(1, f"Capture latency limit exceeded: {len(failures)} cycles, "
                        f"max={max(c['duration_s_coarse'] for c in failures)}s (coarse)\n")


if __name__ == "__main__":
    main()
