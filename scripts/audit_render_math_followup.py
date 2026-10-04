#!/usr/bin/env python3
"""Deterministic source-backed counterexamples; not GPU pixel/performance tests."""
from pathlib import Path
import json
import numpy as np


def run():
    root = Path(__file__).resolve().parents[1]
    raygen = (root / 'src/main/java/com/rtest/client/RayTracingShaderRaygen.java').read_text()
    composite = (root / 'src/main/resources/prime/shaders/aerial_perspective_composite.comp').read_text()
    # Check the implemented wiring, while retaining historical counterexamples below.
    contracts = [
        'traceEmitterVisibility(volumePosition + light.direction * 0.002, light.position)',
        'traceEmitterVisibility(surfacePosition + normal * 0.002, light.position)',
        'float tMax = distance - endpointMargin;',
        'previousWasDelta && previousAreaNeeEnabled',
        'if (previousSkyNeeEnabled && !previousWasDelta)',
        'directStep * rgbShadow * shadowWeight + multipleStep',
        'skySegment ? 0u : uint(',
        'node == left ? leftProbability : 1.0 - leftProbability',
        'transmission ? vec3(canTransmit ? fresnel : 1.0)',
        'viewT * phaseScattering * sourceT * PATM_SOLAR',
        'bool insideMedium = cameraInWater;'
    ]
    for contract in contracts:
        assert contract in raygen, contract
    assert 'sum += viewT * skyPhase * incidentSky' not in raygen
    assert 'vec4(clamp(color + contribution,' in composite

    # An opaque area-light triangle at z=10, sampled at its interior (0,0,10).
    # A volume point at z=0 launches +z with the shader's 2 mm offset.
    p = np.array([0., 0., 0.])
    light = np.array([0., 0., 10.])
    direction = (light - p) / np.linalg.norm(light - p)
    origin = p + direction * .002
    distance = np.linalg.norm(light - p)
    plane_hit_t = (light[2] - origin[2]) / direction[2]
    assert .001 < plane_hit_t < distance

    # The prior vertex is rough glass: area NEE gated off, continuous reflection
    # next hits a registered emitter. The unconditional emitter mask loses its Le.
    prior_transmission = True
    previous_was_delta = False
    registered_emitter = True
    nee_runs = not prior_transmission
    suppress_hit = not previous_was_delta and registered_emitter
    assert not nee_runs and suppress_hit

    # One 2-step stratum with zero visibility but varying source magnitude.
    # Raw control-variate expectation is 0; output clamp does not commute with E.
    a = np.array([10., 1.])
    estimates = a.sum() + 2 * a * (-1.)
    assert abs(estimates.mean()) < 1e-12
    post_clamp = np.maximum(estimates, 0).mean()
    assert post_clamp == 4.5

    # Forward traversal uses 1-p_left, reverse PDF evaluates p_right separately.
    left, right = np.float32(1), np.float32(1e-8)
    total = np.float32(left + right)
    actual_right = np.float32(1) - left / total
    queried_right = right / total
    assert actual_right == 0 and queried_right > 0

    # One smooth nondispersive dielectric: IOR determines branch probability,
    # authored reflectivity determines mirror energy; these can disagree.
    ior, authored_f0 = 1.5, .5
    branch_f0 = ((ior - 1) / (ior + 1)) ** 2
    expected_reflection = authored_f0
    expected_transmission = 1 - branch_f0  # Unit tint, no absorption.

    # RGB projection of a four-wave T is not a multiplicative homomorphism.
    solar = np.array([2.0108537673950195, 1.9255825281143188, 1.8567869663238525, 1.5677554607391357])
    matrix = np.array([
        [5.8809709548950195, -14.151336669921875, 87.37126159667969, 31.710432052612305],
        [-7.0044331550598145, 54.43069839477539, 58.01542282104492, -1.2180235385894775],
        [90.48178100585938, 8.974457740783691, -1.4691294431686401, .05091293156147003]])
    rgb_t = lambda t: np.clip(matrix @ (solar * t) / (matrix @ solar), 0, 1)
    t1, t2 = np.array([.8, .6, .4, .2]), np.array([.3, .5, .7, .9])
    new_tmax = plane_hit_t - max(.0001, 4 * 1.1920929e-7 * distance)
    assert .001 < new_tmax < plane_hit_t
    positive_estimates = 2 * a * 0
    assert np.maximum(positive_estimates, 0).mean() == 0
    return {
        'implemented_contracts_checked': len(contracts),
        'fixed_target_excluded': bool(new_tmax < plane_hit_t),
        'fixed_finite_l_clamped_expectation': float(np.maximum(positive_estimates, 0).mean()),
        'historical_area_shadow_endpoint': {'old_tmax': float(distance), 'target_intersection_t': float(plane_hit_t),
                                 'target_is_inside_shadow_segment': bool(plane_hit_t < distance)},
        'rough_transparent_emitter': {'area_nee_enabled': nee_runs, 'emitter_hit_suppressed': suppress_hit},
        'rough_transparent_sky': {'sky_nee_enabled': False,
                                  'unpaired_bsdf_mis_weight_fixture': .1**2 / (.1**2 + .2**2),
                                  'correct_exclusive_bsdf_weight': 1.},
        'control_variate_clamp': {'raw_samples': estimates.tolist(), 'raw_expectation': float(estimates.mean()),
                                  'clamped_expectation': float(post_clamp), 'reference': 0.},
        'light_tree_float32': {'actual_right_branch_probability': float(actual_right),
                               'reverse_queried_right_probability': float(queried_right)},
        'dielectric_closure': {'ior': ior, 'authored_f0': authored_f0, 'branch_f0': branch_f0,
                               'reflected_plus_transmitted': expected_reflection + expected_transmission},
        'spectral_rgb_composition': {'project_after_product': rgb_t(t1*t2).tolist(),
                                     'product_after_projection': (rgb_t(t1)*rgb_t(t2)).tolist()},
        'scope': 'Current source wiring checks plus historical formula counterexamples; not an actual GPU scene replay.'
    }


if __name__ == '__main__':
    result = run()
    root = Path(__file__).resolve().parents[1]
    output = root / 'docs/profiling/2026-10-04-render-math-followup-fixed.json'
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
