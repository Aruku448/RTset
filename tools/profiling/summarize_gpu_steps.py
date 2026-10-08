#!/usr/bin/env python3
"""Summarize sampled RTest queue intervals; post intervals are nested, not additive."""
import argparse
import datetime as dt
import json
import re
from pathlib import Path


def stats(values):
    values = sorted(values)
    def percentile(p):
        pos = (len(values) - 1) * p
        low = int(pos)
        return values[low] + (values[min(low + 1, len(values) - 1)] - values[low]) * (pos - low)
    return {"n": len(values), "median": percentile(.5), "p95": percentile(.95), "max": values[-1]}


def summarize(log, hardware, date, since=None, until=None):
    groups = {}
    post = {}
    for line in Path(log).read_text(errors="replace").splitlines():
        match = re.search(r"\[(\d\d:\d\d:\d\d)\].*RTest (gpu_steps|gpu_post_steps) (.*)", line)
        if not match:
            continue
        if (since and match[1] < since) or (until and match[1] > until):
            continue
        fields = dict(re.findall(r"(\w+)=(\S+)", match[3]))
        row = {k: float(v) for k, v in fields.items() if k.endswith("_ms")}
        row["time"] = date + "T" + match[1]
        row["frame"] = int(fields["frame"])
        if match[2] == "gpu_post_steps":
            post[(row["frame"], row["time"])] = row
        else:
            groups.setdefault((fields["extent"], int(fields["mode"]), fields.get("audit_profile", "baseline")), []).append(row)
    hw = [json.loads(line) for line in Path(hardware).read_text().splitlines()] if hardware else []
    for row in hw:
        for field in ["sclk", "mclk"]:
            active = re.search(r'(\d+)Mhz\s*\*', row.get(field) or "", re.I)
            if active:
                row[field + "_mhz"] = int(active[1])
    output = []
    for (extent, mode, profile), rows in groups.items():
        keys = [k for k in rows[0] if k.endswith("_ms")]
        linked_post = [post[(r["frame"], r["time"])] for r in rows if (r["frame"], r["time"]) in post]
        start, end = min(r["time"] for r in rows), max(r["time"] for r in rows)
        hw_rows = [h for h in hw if start <= h["time"][:19] <= end]
        hw_keys = ["gpu_busy", "mem_busy", "vram_used", "temperature_mC", "hotspot_mC", "power_uW", "power_cap_uW", "sclk_mhz", "mclk_mhz"]
        errors = [abs(sum(r[k] for k in keys if k != "command_total_ms") - r["command_total_ms"]) for r in rows]
        output.append({"extent": extent, "mode": mode, "audit_profile": profile, "start": start, "end": end,
            "steps_ms": {k: stats([r[k] for r in rows]) for k in keys},
            "nested_post_ms": {k: stats([r[k] for r in linked_post]) for k in linked_post[0] if k.endswith("_ms")} if linked_post else {},
            "hardware": {k: stats([float(h[k]) for h in hw_rows if str(h.get(k, "")).replace(".", "", 1).isdigit()]) for k in hw_keys if any(str(h.get(k, "")).replace(".", "", 1).isdigit() for h in hw_rows)},
            "sum_rounding_error_max_ms": max(errors),
            "negative_intervals": sum(r[k] < 0 for r in rows for k in keys)})
    return {"groups": output, "note": "Sampled every 120 frames. Scene/settings changes within a group are not controlled comparisons. Hardware is device-wide. Nested post intervals must not be added to parent."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log")
    parser.add_argument("--hardware")
    parser.add_argument("--date", default=dt.date.today().isoformat())
    parser.add_argument("--since", help="Inclusive local log time HH:MM:SS (same date)")
    parser.add_argument("--until", help="Inclusive local log time HH:MM:SS (same date)")
    args = parser.parse_args()
    print(json.dumps(summarize(args.log, args.hardware, args.date, args.since, args.until), ensure_ascii=False, indent=2))
