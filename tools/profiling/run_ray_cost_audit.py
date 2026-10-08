#!/usr/bin/env python3
"""Run controlled shader ablations in an already open RTest scene; restore config on exit."""
import argparse
import datetime as dt
import json
import os
import re
import signal
import time
import tomllib
from pathlib import Path
from summarize_gpu_steps import summarize

PROFILES = ["baseline", "no_restir", "no_gi", "no_sun", "no_moon", "no_area",
            "no_sky_nee", "no_volume", "no_pom", "no_pbr", "no_shadow_rays", "no_dynamic_delta", "primary_material", "traversal_only", "baseline"]


def replace_config(path, data):
    temporary = path.with_suffix(".audit-tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def hardware(device):
    files = {"gpu_busy": "gpu_busy_percent", "mem_busy": "mem_busy_percent",
             "vram_used": "mem_info_vram_used", "vram_total": "mem_info_vram_total",
             "sclk": "pp_dpm_sclk", "mclk": "pp_dpm_mclk"}
    monitor = next(iter((device / "hwmon").glob("hwmon*")), device / "hwmon/unavailable")
    sensors = {"temperature_mC": "temp1_input", "hotspot_mC": "temp2_input",
               "power_uW": "power1_average", "power_cap_uW": "power1_cap"}
    result = {"time": dt.datetime.now().astimezone().isoformat()}
    for key, path in {**{k: device / v for k, v in files.items()}, **{k: monitor / v for k, v in sensors.items()}}.items():
        try:
            result[key] = path.read_text().strip()
        except OSError:
            result[key] = None
    return result


def comparisons(summaries):
    usable = [s for s in summaries if len(s["groups"]) == 1]
    baselines = [s for s in usable if s["profile"] == "baseline"]
    if not baselines:
        return {"status": "no single-extent baseline"}
    baseline = baselines[0]["groups"][0]
    reference = baseline["steps_ms"]["trace_ms"]["median"]
    rows = []
    for phase in usable:
        group = phase["groups"][0]
        if group["extent"] != baseline["extent"]:
            continue
        measured = group["steps_ms"]["trace_ms"]
        rows.append({"phase": phase["phase"], "profile": phase["profile"], "mode": group["mode"],
                     "trace_ms": measured, "baseline_minus_profile_median_ms": reference - measured["median"]})
    drift = None
    if len(baselines) > 1 and baselines[-1]["groups"][0]["extent"] == baseline["extent"] and reference > 0:
        drift = 100 * (baselines[-1]["groups"][0]["steps_ms"]["trace_ms"]["median"] / reference - 1)
    return {"extent": baseline["extent"], "baseline_bookend_drift_percent": drift, "rows": rows,
            "note": "Marginal ablations, not exclusive times. Do not add differences. Baseline drift, changed scene, settings or competing GPU work can invalidate attribution."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path, default=Path.home() / ".minecraft/versions/RTest")
    parser.add_argument("--profiles", nargs="+", choices=sorted(set(PROFILES)), default=PROFILES)
    parser.add_argument("--settle", type=float, default=12)
    parser.add_argument("--seconds", type=float, default=45)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--device", type=Path, default=Path("/sys/class/drm/card1/device"))
    args = parser.parse_args()
    if min(args.seconds, args.timeout) <= 0 or args.settle < 0:
        parser.error("seconds/timeout must be positive and settle nonnegative")
    config = args.instance / "config/rtest-audit-client.toml"
    log = args.instance / "logs/latest.log"
    if not config.exists() or not log.exists():
        parser.error("Install the audit JAR, restart RTest and enter the test scene first")
    options = args.instance / "options.txt"
    if options.exists() and re.search(r'^inactivityFpsLimit:\s*"afk"\s*$', options.read_text(), re.M):
        parser.error("AFK FPS limiting invalidates a stationary benchmark. Select minimized-only inactivity limiting and keep the game in the foreground first.")
    original = config.read_bytes()
    def interrupted(signum, frame):
        raise KeyboardInterrupt("Audit interrupted; restoring config")
    signal.signal(signal.SIGTERM, interrupted)
    output = Path("tmp/profiling") / ("ray-audit-" + dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True)
    (output / "original-config.toml").write_bytes(original)
    summaries = []
    try:
        with log.open(errors="replace") as stream:
            stream.seek(0, 2)
            for index, profile in enumerate(args.profiles):
                text = original.decode()
                text, count = re.subn(r'^rayCostAuditProfile\s*=.*$', f'rayCostAuditProfile = "{profile}"', text, flags=re.M)
                if count != 1:
                    raise RuntimeError("Expected one rayCostAuditProfile setting")
                replace_config(config, text.encode())
                print(f"[{index + 1}/{len(args.profiles)}] {profile}: waiting for matching GPU samples", flush=True)
                phase = output / f"{index:02d}-{profile}"
                phase.mkdir()
                settings = {}
                for name in ["rtest-client.toml", "rtest-restir-client.toml"]:
                    path = args.instance / "config" / name
                    if path.exists():
                        settings[name] = tomllib.loads(path.read_text())
                (phase / "settings.json").write_text(json.dumps(settings, ensure_ascii=False, indent=2))
                started = time.monotonic()
                ready = None
                sample_start = None
                gpu_rows = 0
                last_matching = started
                with (phase / "hardware.jsonl").open("w") as hw, (phase / "gpu.log").open("w") as capture:
                    while True:
                        now = time.monotonic()
                        lines = stream.readlines()
                        for line in lines:
                            matches = "RTest gpu_steps " in line and f"audit_profile={profile} " in line
                            if matches:
                                last_matching = now
                                if ready is None:
                                    ready = now
                                    sample_start = now + args.settle
                                    print(f"  {profile}: settling {args.settle:g}s, sampling {args.seconds:g}s", flush=True)
                            if sample_start is not None and now >= sample_start:
                                if matches or "RTest gpu_post_steps " in line:
                                    capture.write(line)
                                if matches:
                                    gpu_rows += 1
                        if sample_start is not None and now >= sample_start:
                            hw.write(json.dumps(hardware(args.device)) + "\n")
                        if now - last_matching > args.timeout:
                            raise RuntimeError(f"No fresh matching GPU samples for {profile}; game stopped, audit JAR absent or config not reloaded")
                        if sample_start is not None and now >= sample_start + args.seconds:
                            if gpu_rows < 3:
                                raise RuntimeError(f"Too few GPU samples for {profile}: {gpu_rows}")
                            break
                        time.sleep(1)
                summary = summarize(phase / "gpu.log", phase / "hardware.jsonl", dt.date.today().isoformat())
                summary["phase"] = index
                summary["profile"] = profile
                summaries.append(summary)
                (output / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2))
                (output / "comparisons.json").write_text(json.dumps(comparisons(summaries), ensure_ascii=False, indent=2))
                print(f"  captured {gpu_rows} rows", flush=True)
    finally:
        replace_config(config, original)
        print(f"Restored original audit config; results: {output}", flush=True)


if __name__ == "__main__":
    main()
