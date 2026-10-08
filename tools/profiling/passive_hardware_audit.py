#!/usr/bin/env python3
"""Read-only hardware/process sampling. Never emits process command lines."""
import argparse
import datetime
import json
import os
import time
from pathlib import Path
from run_ray_cost_audit import hardware

def read(path):
    try:
        return Path(path).read_text().strip()
    except OSError:
        return None

def process(pid):
    root = Path('/proc') / str(pid)
    status = read(root / 'status')
    if status is None:
        return None
    fields = dict(line.split(':', 1) for line in status.splitlines() if ':' in line)
    row = {'pid': pid, **{key: fields.get(key, '').strip()
        for key in ['Name', 'State', 'VmRSS', 'VmSwap', 'Threads']}}
    stat = read(root / 'stat')
    if stat:
        values = stat[stat.rfind(')') + 2:].split()
        row.update(major_faults=int(values[9]), cpu_ticks=int(values[11]) + int(values[12]))
    clients = {}
    for fd in (root / 'fdinfo').glob('*'):
        info = read(fd)
        if not info or 'drm-client-id:' not in info:
            continue
        drm = dict(line.split(':', 1) for line in info.splitlines() if line.startswith('drm-') and ':' in line)
        client = drm.get('drm-client-id', '').strip()
        clients[client] = {key: value.strip() for key, value in drm.items()}
    row['drm_clients'] = list(clients.values())
    threads = []
    for task in (root / 'task').glob('*'):
        stat = read(task / 'stat')
        if not stat:
            continue
        values = stat[stat.rfind(')') + 2:].split()
        threads.append({'tid': int(task.name), 'name': read(task / 'comm'),
            'cpu_ticks': int(values[11]) + int(values[12]), 'major_faults': int(values[9])})
    row['threads'] = threads
    return row

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pids', nargs='+', type=int, required=True)
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'metadata.json').write_text(json.dumps({
        'pids': args.pids, 'clock_ticks_per_second': os.sysconf('SC_CLK_TCK'),
        'started': datetime.datetime.now().astimezone().isoformat(),
        'note': 'Passive whole-device readings; no workload, settings, clocks or processes changed.'}, indent=2))
    started = time.monotonic()
    with (args.output / 'samples.jsonl').open('w') as out:
        while time.monotonic() - started < args.seconds:
            row = {'monotonic': time.monotonic(), 'hardware': hardware(Path('/sys/class/drm/card1/device')),
                'processes': [value for pid in args.pids if (value := process(pid)) is not None],
                'memory_pressure': read('/proc/pressure/memory'), 'io_pressure': read('/proc/pressure/io'),
                'cpu_pressure': read('/proc/pressure/cpu'), 'vmstat': read('/proc/vmstat')}
            out.write(json.dumps(row) + '\n')
            out.flush()
            time.sleep(1)
    print('Passive sample complete:', args.output, flush=True)

if __name__ == '__main__':
    main()
