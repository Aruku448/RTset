#!/usr/bin/env python3
"""Checks actual CPU/GPU water coefficients and air-medium gating; no GPU image claims."""
from pathlib import Path
import math
import re

root = Path(__file__).resolve().parents[1]
raygen = (root / 'src/main/java/com/rtest/client/RayTracingShaderRaygen.java').read_text()
scene = (root / 'src/main/java/com/rtest/client/RayTracingScene.java').read_text()
gpu = re.search(r'mediumAbsorption = cameraInWater \? vec3\(([^)]+)\)', raygen)
cpu = re.search(r'new OpticalProperties\(1\.333F, ([^,]+), ([^,]+), ([^,]+),', scene)
assert gpu and cpu, 'Water definitions missing'
gpu_coeff = [float(x.strip()) for x in gpu.group(1).split(',')]
cpu_coeff = [float(x.strip().removesuffix('F')) for x in cpu.groups()]
assert gpu_coeff == cpu_coeff, f'CPU/GPU medium mismatch: {cpu_coeff} vs {gpu_coeff}'
for distance in [1, 5, 20, 100]:
    t = [math.exp(-sigma * distance) for sigma in gpu_coeff]
    assert 0 < t[0] < t[1] < t[2] <= 1, f'Red-biased water at {distance} blocks: {t}'
    print(f'{distance} blocks: RGB T={t}')
assert '&& !cameraInWater\n' in raygen, 'Air volume must not run inside water'
print('Water transport checks passed: matching coefficients, blue-preserving T, air volume gated.')
