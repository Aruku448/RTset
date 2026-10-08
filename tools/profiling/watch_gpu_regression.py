#!/usr/bin/env python3
"""Observe the human-driven RT movement repro; no launch, input, config changes or deliberate GPU fault."""
import argparse, hashlib, json, re, subprocess, time
from pathlib import Path

SIGNAL = re.compile(r'VK_ERROR_DEVICE_LOST|ring .* timeout|GPU reset begin|GPU reset\([0-9]+\) succeeded|Illegal opcode')

def signals(text):
    return [line[:400] for line in text.splitlines() if SIGNAL.search(line)]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--replay', type=Path)
    parser.add_argument('--kernel-replay', type=Path)
    parser.add_argument('--seconds', type=int, default=120)
    parser.add_argument('--label', default='baseline')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.replay:
        text = args.replay.read_text(errors='replace')
        kernel = args.kernel_replay.read_text(errors='replace') if args.kernel_replay else ''
        result = {'mode': 'symptom detector replay, not GPU execution', 'verdict': 'FAULT' if signals(text + kernel) else 'NO_RECORDED_FAULT',
                  'signals': signals(text + kernel), 'rt_samples': text.count('RTest gpu_steps'),
                  'publications': text.count('RTest geometry publish')}
    else:
        root = Path('/home/aruku/.minecraft/versions/RTest')
        log = root / 'logs/latest.log'
        jar = root / 'mods/rtest-0.1.0.jar'
        start, since = time.monotonic(), time.strftime('%Y-%m-%d %H:%M:%S')
        cursor = log.stat().st_size if log.exists() else 0
        inode = log.stat().st_ino if log.exists() else None
        parts, faults, publications, rt_samples = [], [], 0, 0
        while time.monotonic() - start < args.seconds:
            if log.exists():
                stat = log.stat()
                if inode != stat.st_ino or stat.st_size < cursor:
                    cursor = 0
                inode = stat.st_ino
                with log.open('rb') as stream:
                    stream.seek(cursor)
                    text = stream.read().decode(errors='replace')
                    cursor = stream.tell()
                # Only narrow telemetry retained, never launch arguments/auth fields.
                parts.extend(line for line in text.splitlines() if 'RTest gpu_steps' in line or 'RTest geometry publish' in line)
                faults.extend(signals(text))
                publications += text.count('RTest geometry publish')
                rt_samples += text.count('RTest gpu_steps')
            if faults:
                break
            time.sleep(1)
        kernel = subprocess.run(['journalctl', '-k', '--since', since, '--no-pager'], capture_output=True, text=True).stdout
        faults.extend(signals(kernel))
        verdict = 'FAULT' if faults else 'OBSERVED_NO_FAULT' if publications >= 10 and rt_samples >= 5 else 'INCONCLUSIVE'
        result = {'mode': 'human-driven real game observation', 'label': args.label, 'verdict': verdict,
                  'seconds': round(time.monotonic() - start, 1), 'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                  'signals': faults, 'rt_samples': rt_samples, 'publications': publications,
                  'limitation': 'No-fault window does not prove stability. Hardware event attribution and controlled movement still require inspection.'}
        (args.output / 'telemetry.log').write_text('\n'.join(parts) + '\n')
    (args.output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    return 1 if result['verdict'] == 'FAULT' else 2 if result['verdict'] == 'INCONCLUSIVE' else 0

if __name__ == '__main__':
    raise SystemExit(main())
