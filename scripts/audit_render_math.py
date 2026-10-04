#!/usr/bin/env python3
"""Deterministic numerical checks for audit proposals; not GPU/performance tests."""
import json
import math
from pathlib import Path
import numpy as np


def run():
    rng = np.random.default_rng(20261004)
    a = rng.random((8, 3))
    visibility = rng.random((8, 3))
    p = np.linalg.norm(a, axis=1)
    p /= p.sum()
    baseline = a.sum(axis=0)
    # Enumerate the expectation instead of relying on a noisy Monte Carlo estimate.
    estimates = baseline + a * (visibility - 1) / p[:, None]
    expectation = (p[:, None] * estimates).sum(axis=0)
    reference = (a * visibility).sum(axis=0)
    error = float(np.max(np.abs(expectation - reference)))
    assert error < 1e-12

    f32 = np.float32
    radius = rng.uniform(np.deg2rad(.05), np.deg2rad(5), 100000).astype(np.float32)
    omega = (f32(4 * math.pi) * np.sin(radius * f32(.5)) ** 2).astype(np.float32)
    pdf = f32(1) / np.maximum(omega, f32(1e-8))
    strength = rng.uniform(.001, 16, len(radius)).astype(np.float32)
    old = (strength / omega) / pdf
    rel_error = float(np.max(np.abs(old - strength) / strength))
    assert rel_error < 5e-7

    alpha = f32(np.deg2rad(.05))
    u = np.linspace(0, 1, 100001, dtype=np.float32)
    old_cos = np.cos(alpha) * (f32(1) - u) + u
    old_sin = np.sqrt(np.maximum(f32(1) - old_cos * old_cos, f32(0)))
    delta = (f32(1) - u) * f32(2) * np.sin(alpha * f32(.5)) ** 2
    stable_sin = np.sqrt(np.maximum(delta * (f32(2) - delta), f32(0)))

    ior, cosine = 1.5, .5
    discriminant = 1 - ior * ior * (1 - cosine * cosine)
    assert discriminant < 0
    schlick = .04 + .96 * (1 - cosine) ** 5
    assert abs(schlick - .07) < 1e-12

    samples, bsdf_conditional_pdf, specular_probability = 1, 2., .07
    light_pdf = 40.
    w_nee = (samples * light_pdf) ** 2 / ((samples * light_pdf) ** 2 + bsdf_conditional_pdf ** 2)
    actual_bsdf_pdf = specular_probability * bsdf_conditional_pdf
    w_bsdf = actual_bsdf_pdf ** 2 / (actual_bsdf_pdf ** 2 + (samples * light_pdf) ** 2)
    assert abs(w_nee + w_bsdf - 1) > 1e-4

    return {
        'volume_control_variate_expectation_max_abs_error': error,
        'sun_radiance_pdf_cancellation_float32_max_relative_error': rel_error,
        'small_sun_radius_degrees': .05,
        'old_sample_sin_zero_fraction': float(np.mean(old_sin == 0)),
        'stable_sample_sin_zero_fraction': float(np.mean(stable_sin == 0)),
        'tir_example': {'ior_ratio': ior, 'cosine': cosine, 'refract_discriminant': discriminant,
                        'schlick_weight': schlick, 'tir_reflection_weight': 1.},
        'mismatched_mis_example_sum': w_nee + w_bsdf,
        'notes': ['Synthetic deterministic mathematical fixtures, not rendered pixels.',
                  'Float32 sampling results depend on arithmetic/compiler lowering.',
                  'No GPU runtime or speedup percentage is measured.']
    }


if __name__ == '__main__':
    result = run()
    output = Path('docs/profiling/2026-10-04-render-math-checks.json')
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
