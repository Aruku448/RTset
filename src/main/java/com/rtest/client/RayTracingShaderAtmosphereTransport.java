package com.rtest.client;

/** Camera-segment RGB and physical atmosphere transport source. */
final class RayTracingShaderAtmosphereTransport {
    private RayTracingShaderAtmosphereTransport() {
    }

    static final String GLSL = """
            // Prime 26.3 integrates aerial radiance in an epipolar volume. RTest keeps the same
            // ordering and transport equation in the existing raygen pass: the camera segment is
            // integrated before FSR/NRD. Keep this as a bounded camera-segment integration so the
            // feature remains part of the RT radiance/AOV path instead of creating a second shadow
            // layer with a different visibility query.
            const int ATMOSPHERE_MAX_SEGMENT_SAMPLES = 16;
            const float ATMOSPHERE_BLOCK_TO_KM = 0.001;
            // A literal physical kilometre per block is too sparse for Minecraft's short camera
            // distances. This artistic density multiplier makes 64-512 block horizons visibly hazy
            // while keeping the atmospheric height falloff in the same coordinate system.
            const float ATMOSPHERE_FOG_DENSITY_SCALE = 4.0;
            const float ATMOSPHERE_RAYLEIGH_SCALE_HEIGHT_KM = 8.0;
            const float ATMOSPHERE_AEROSOL_SCALE_HEIGHT_KM = 1.2;
            const vec3 ATMOSPHERE_RAYLEIGH_EXTINCTION = vec3(0.018, 0.038, 0.080);
            const vec3 ATMOSPHERE_AEROSOL_EXTINCTION = vec3(0.090);
            const vec3 ATMOSPHERE_RAYLEIGH_SCATTERING = vec3(0.017, 0.035, 0.075);
            const vec3 ATMOSPHERE_AEROSOL_SCATTERING = vec3(0.084);
            const float ATMOSPHERE_AEROSOL_G = 0.76;
            // A Minecraft block is much smaller than the physical atmosphere segment used by
            // Prime's aerial LUTs. Keep extinction physically bounded, but lift the single-scatter
            // signal enough for short 8-64 block camera paths to survive HDR quantisation and FSR.
            const float ATMOSPHERE_VISIBLE_SCATTER_SCALE = 8.0;
            float atmosphereRayleighPhase(float cosine) {
                return 3.0 / (16.0 * BSDF_PI) * (1.0 + cosine * cosine);
            }
            float atmosphereAerosolPhase(float cosine) {
                float g = ATMOSPHERE_AEROSOL_G;
                float denominator = max(1.0 + g * g - 2.0 * g * cosine, 1.0e-4);
                return (1.0 - g * g) / (4.0 * BSDF_PI * denominator * sqrt(denominator));
            }
            void integrateAtmosphereSegment(
                    vec3 origin,
                    vec3 direction,
                    float segmentLength,
                    vec3 sunDirectionInput,
                    out vec3 transmittance,
                    out vec3 inscatter) {
                transmittance = vec3(1.0);
                inscatter = vec3(0.0);
                float strength = max(camera.settings.z, 0.0);
                float fogDensity = max(camera.environment.x, 0.0);
                int quality = int(clamp(camera.settings.w, 1.0, 3.0) + 0.5);
                // Four samples are insufficient even for a small room: a sky ray previously placed
                // its first midpoint tens of blocks beyond the opening. Keep a usable near-field
                // visibility resolution while retaining explicit performance/balanced/quality tiers.
                int sampleCount = quality == 1 ? 4 : (quality == 3 ? 16 : 8);
                float daylight = clamp(1.0 - camera.environmentState.w, 0.0, 1.0);
                if (!(strength > 1.0e-4) || !(fogDensity > 1.0e-4) || !(segmentLength > 1.0e-3)) {
                    return;
                }
                float sunLengthSquared = dot(sunDirectionInput, sunDirectionInput);
                bool sunValid = sunLengthSquared > 1.0e-8;
                vec3 sunDirection = sunValid ? sunDirectionInput * inversesqrt(sunLengthSquared)
                    : vec3(0.0, 1.0, 0.0);
                float rain = clamp(camera.environmentState.y, 0.0, 1.0);
                float thunder = clamp(camera.environmentState.z, 0.0, 1.0);
                float weatherVisibility = clamp(1.0 - rain * 0.55 - thunder * 0.25, 0.25, 1.0);
                // The phase angle is between the incoming solar ray (sun -> sample) and
                // the outgoing camera ray (sample -> camera). `sunDirection` points from
                // the sample toward the sun, while `direction` points from the camera into
                // the sample, so both directions must be negated for the incoming/outgoing
                // pair. Their dot product is therefore equivalent to dot(direction, sunDirection).
                // Using dot(viewDirection, sunDirection) mirrored the forward aerosol lobe and
                // placed the volumetric light wheel opposite the visible sun disk.
                float phaseCosine = dot(direction, sunDirection);
                float rayleighPhase = atmosphereRayleighPhase(phaseCosine);
                float aerosolPhase = atmosphereAerosolPhase(phaseCosine);
                vec3 moonDirection = vec3(camera.origin.w, camera.environment.w, camera.jitter.z);
                float lunarIrradiance = moonIrradiance(camera.jitter.w);
                bool lunarValid = lunarIrradiance > 0.0 && moonDirectionValid(moonDirection);
                vec3 moon = lunarValid ? normalize(moonDirection) : vec3(0.0, 1.0, 0.0);
                float lunarRayleighPhase = atmosphereRayleighPhase(dot(direction, moon));
                float lunarAerosolPhase = atmosphereAerosolPhase(dot(direction, moon));
                float stepLengthKm = segmentLength * ATMOSPHERE_BLOCK_TO_KM
                    * ATMOSPHERE_FOG_DENSITY_SCALE * fogDensity
                    / float(sampleCount);
                for (int step = 0; step < ATMOSPHERE_MAX_SEGMENT_SAMPLES; step++) {
                    if (step >= sampleCount) {
                        break;
                    }
                    float fraction = (float(step) + 0.5) / float(sampleCount);
                    vec3 samplePoint = origin + direction * (segmentLength * fraction);
                    float altitudeKm = max(
                        (samplePoint.y - camera.origin.y) * ATMOSPHERE_BLOCK_TO_KM,
                        0.0);
                    float rayleighDensity = exp(-altitudeKm / ATMOSPHERE_RAYLEIGH_SCALE_HEIGHT_KM);
                    float aerosolDensity = exp(-altitudeKm / ATMOSPHERE_AEROSOL_SCALE_HEIGHT_KM);
                    vec3 extinction = strength * (
                        ATMOSPHERE_RAYLEIGH_EXTINCTION * rayleighDensity
                            + ATMOSPHERE_AEROSOL_EXTINCTION * aerosolDensity);
                    vec3 stepTransmittance = exp(-extinction * stepLengthKm);
                    vec3 opticalDepth = extinction * stepLengthKm;
                    vec3 segmentIntegral = stepLengthKm
                        * (vec3(1.0) - stepTransmittance)
                        / max(opticalDepth, vec3(1.0e-5));
                    // Extinction and ambient fog remain active at night. Solar in-scatter is an
                    // optional daylight term, not a prerequisite for the volumetric medium: the
                    // previous daylight early-return made the feature disappear exactly when the
                    // night scene was used to validate the RT path.
                    vec3 solarScattering = sunValid
                        ? ATMOSPHERE_RAYLEIGH_SCATTERING * rayleighDensity * rayleighPhase
                            + ATMOSPHERE_AEROSOL_SCATTERING * aerosolDensity * aerosolPhase
                        : vec3(0.0);
                    vec3 nightAmbientScattering = vec3(0.004, 0.006, 0.010)
                        * (0.35 + 0.65 * (1.0 - daylight));
                    // Visibility must be evaluated at the volume sample. Reusing the terminal
                    // surface's shadow value makes the complete camera segment uniformly lit or
                    // uniformly dark, which mathematically removes every occlusion boundary and
                    // therefore every sun shaft. Query the same TLAS and transparent-shadow path as
                    // surface lighting so the Tyndall pattern and RT geometry remain coincident.
                    vec3 volumeSunVisibility = vec3(1.0);
                    if (sunValid && daylight > 1.0e-4 && camera.settings.y != 0.0) {
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        traceRayEXT(
                            topLevelAS,
                            gl_RayFlagsTerminateOnFirstHitEXT,
                            SECONDARY_RAY_MASK,
                            1,
                            1,
                            1,
                            samplePoint + sunDirection * 0.002,
                            0.001,
                            sunDirection,
                            camera.sun.w,
                            1
                        );
                        volumeSunVisibility = mix(
                            vec3(1.0), shadowTransmittance, camera.settings.y);
                    }
                    vec3 lunarScattering = vec3(0.0);
                    if (lunarValid && moon.y > 0.0) {
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        if (camera.settings.y != 0.0) traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                            1, 1, 1, samplePoint + moon * 0.002, 0.001, moon, camera.sun.w, 1);
                        lunarScattering = (ATMOSPHERE_RAYLEIGH_SCATTERING * rayleighDensity * lunarRayleighPhase
                            + ATMOSPHERE_AEROSOL_SCATTERING * aerosolDensity * lunarAerosolPhase)
                            * lunarIrradiance * mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                    }
                    vec3 scattering = strength * (nightAmbientScattering + lunarScattering
                        + solarScattering * daylight * volumeSunVisibility)
                        * ATMOSPHERE_VISIBLE_SCATTER_SCALE
                        * weatherVisibility;
                    inscatter += transmittance * scattering * segmentIntegral;
                    transmittance *= stepTransmittance;
                }
            }
            #ifdef RTEST_ATMOSPHERE_LUT
            // Physical finite-segment aerial perspective for the camera-to-surface span. This is
            // the faithful four-wave transport from integrate.slang/source.slang/spectrum.slang,
            // NOT the legacy RGB approximation above: extinction/scattering/phase/source/optical
            // depth are the pinned Prime medium, resolution matches the 4/8/16 sample tiers, and
            // the direct term is converted to Rec.2020 before multiplying the per-step TLAS RGB
            // shadow. Multiple scattering stays unshadowed, matching Prime's aerial source.
            void integratePhysicalAtmosphereSegment(
                    vec3 origin,
                    vec3 direction,
                    float segmentBlocks,
                    vec3 sunDirectionInput,
                    float eyeRadiusKm,
                    int sampleCount,
                    bool skySegment,
                    float shadowStrength,
                    float weatherVisibility,
                    PrimeSampleBase sampleBase,
                    out vec3 transmittance,
                    out vec3 inscatter,
                    out vec3 skyShadowCorrection) {
                transmittance = vec3(1.0);
                inscatter = vec3(0.0);
                skyShadowCorrection = vec3(0.0);
                if (!(segmentBlocks > 1.0e-3)) {
                    return;
                }
                float sunLengthSquared = dot(sunDirectionInput, sunDirectionInput);
                bool sunValid = sunLengthSquared > 1.0e-8
                    && !any(isnan(sunDirectionInput)) && !any(isinf(sunDirectionInput));
                vec3 sun = sunValid ? sunDirectionInput * inversesqrt(sunLengthSquared)
                    : vec3(0.0, 1.0, 0.0);
                float segment = segmentBlocks * 0.001;
                vec3 ray = normalize(direction);
                float h = eyeRadiusKm - PATM_BOTTOM_KM;
                float mu = ray.y;
                float sunMu = sun.y;
                float nu = dot(ray, sun);
                float entry = 0.0;
                vec3 up = vec3(0.0, 1.0, 0.0);
                if (h > PATM_THICKNESS_KM) {
                    float radius = PATM_BOTTOM_KM + h;
                    float b = radius * mu;
                    float c = (h - PATM_THICKNESS_KM) * (radius + PATM_BOTTOM_KM + PATM_THICKNESS_KM);
                    float disc = b * b - c;
                    if (b >= 0.0 || disc <= 0.0) {
                        return;
                    }
                    entry = c / (-b + sqrt(disc));
                    if (segment > 0.0 && entry >= segment) {
                        return;
                    }
                    up = normalize(up * radius + ray * entry);
                    h = PATM_THICKNESS_KM;
                    mu = dot(ray, up);
                    sunMu = dot(sun, up);
                }
                bool hit = physicalAtmGroundKms(h, mu);
                float fullLength = physicalAtmBoundary(h, mu, hit);
                float length = fullLength;
                if (segment > 0.0) {
                    length = min(length, max(segment - entry, 0.0));
                }
                uint count = uint(sampleCount);
                // Camera volume has no BSDF vertex; do not seed it from the last surface bounce.
                sampleBase.vertexIndex = 0u;
                sampleBase.pathIndex = 0u;
                // Nonnegative direct-source estimator over the existing finite-step sum.
                // Each stratum draws one index; inverse probability is its integer size.
                // Sky residuals require exhaustive visibility to avoid signed-sample clamp bias.
                uint budget = skySegment ? 0u : uint(clamp(atmosphereCamera.parameters.z, 0.0, 16.0) + 0.5);
                uint shadowWeights[16];
                for (uint j = 0u; j < count; j++) shadowWeights[j] = 1u;
                if (budget > 0u && budget < count) {
                    for (uint j = 0u; j < count; j++) shadowWeights[j] = 0u;
                    for (uint k = 0u; k < budget; k++) {
                        uint start = k * count / budget;
                        uint end = (k + 1u) * count / budget;
                        uint size = end - start;
                        float u = primeSobolSample1D(sampleBase, PRIME_SAMPLE_EFFECT_VOLUME_SHADOW, k);
                        uint selected = start + min(uint(u * float(size)), size - 1u);
                        shadowWeights[selected] = size;
                    }
                }
                // Concentrate both sides of a grazing path near its minimum height (faithful to the
                // upstream non-uniform step boundaries).
                float b = (PATM_BOTTOM_KM + h) * mu;
                float middle = clamp(-b, 0.0, length);
                float hmin = physicalAtmHeightAt(h, mu, middle);
                float hend = physicalAtmHeightAt(h, mu, length);
                float scale = max(0.25, 1e-10);
                float before = log(1.0 + max(h - hmin, 0.0) / scale);
                float after = log(1.0 + max(hend - hmin, 0.0) / scale);
                uint split = uint(round(float(count) * before / max(before + after, 1e-20)));
                if (middle <= 0.0) {
                    split = 0u;
                } else if (middle >= length) {
                    split = count;
                } else {
                    split = clamp(split, 1u, count - 1u);
                }
                vec4 solarPhase[5];
                float cosine = clamp(nu, -1.0, 1.0);
                PhysicalAtmPhaseStencil stencil = physicalAtmPhaseStencil(cosine);
                solarPhase[0] = vec4(3.0 * (1.0 + cosine * cosine) / (16.0 * PATM_PI));
                for (uint k = 0u; k < 4u; k++) {
                    solarPhase[k + 1u] = physicalAtmTabulatedPhase(stencil, k);
                }
                vec3 moonDirection = vec3(camera.origin.w, camera.environment.w, camera.jitter.z);
                float lunarIrradiance = moonIrradiance(camera.jitter.w);
                bool lunarValid = lunarIrradiance > 0.0 && moonDirectionValid(moonDirection);
                vec3 moon = lunarValid ? normalize(moonDirection) : vec3(0.0, 1.0, 0.0);
                float lunarNu = dot(ray, moon);
                float solarPhaseCoordinate = physicalAtmPhaseCoord(nu);
                float lunarPhaseCoordinate = lunarValid ? physicalAtmPhaseCoord(lunarNu) : 0.0;
                vec4 lunarPhase[5];
                PhysicalAtmPhaseStencil lunarStencil = physicalAtmPhaseStencil(clamp(lunarNu, -1.0, 1.0));
                lunarPhase[0] = vec4(3.0 * (1.0 + lunarNu * lunarNu) / (16.0 * PATM_PI));
                for (uint k = 0u; k < 4u; k++) lunarPhase[k + 1u] = physicalAtmTabulatedPhase(lunarStencil, k);
                vec4 spectralTransmittance = vec4(1.0);
                PhysicalAtmIndirectHeight previousHeight = PhysicalAtmIndirectHeight(0u, 1u, 0.0);
                float previous = 0.0;
                for (uint i = 1u; i <= count; i++) {
                    float edge = length * float(i) / float(count);
                    if (before + after >= 1e-5 && i < count) {
                        if (i == split) {
                            edge = middle;
                        } else {
                            bool incoming = i < split;
                            float lh = after * float(i - split) / max(float(count - split), 1.0);
                            if (incoming) {
                                lh = before * (1.0 - float(i) / max(float(split), 1.0));
                            }
                            float hh = hmin + scale * (exp(lh) - 1.0);
                            float c = (h - hh) * (2.0 * PATM_BOTTOM_KM + h + hh);
                            float root = sqrt(max(b * b - c, 0.0));
                            if (incoming) {
                                edge = c / max(-b + root, 1e-20);
                            } else if (b > 0.0) {
                                edge = -c / max(root + b, 1e-20);
                            } else {
                                edge = -b + root;
                            }
                            edge = clamp(edge, previous, length);
                        }
                    }
                    float dt = edge - previous;
                    float d = (edge + previous) * 0.5;
                    previous = edge;
                    float hp = physicalAtmHeightAt(h, mu, d);
                    vec3 localUp = (up * (PATM_BOTTOM_KM + h) + ray * d) / (PATM_BOTTOM_KM + hp);
                    float sampleSunMu = dot(localUp, sun);
                    float lunarMu = dot(localUp, moon);
                    vec3 directStep, multipleStep;
                    vec3 lunarDirectStep = vec3(0.0), lunarMultipleStep = vec3(0.0);
                    {
                        // End medium/table/interpolation state before either visibility query.
                        PhysicalAtmIndirectHeight indirectHeight = physicalAtmCoherentIndirectHeight(hp, previousHeight);
                        previousHeight = indirectHeight;
                        PhysicalAtmMedium c = physicalAtmMedium(hp);
                        vec4 direct = vec4(0.0);
                        for (uint k = 0u; k < 5u; k++) {
                            direct += c.scattering[k] * solarPhase[k];
                        }
                        direct *= physicalAtmSunT(hp, sampleSunMu);
                        direct *= PATM_SOLAR;
                        vec4 multiple = physicalAtmIndirect(hp, dot(localUp, ray), sampleSunMu, nu, c,
                            indirectHeight, solarPhaseCoordinate);
                        vec4 tau = c.extinction * dt;
                        vec4 trans = exp(-tau);
                        // Small-tau Taylor stabilizes near-transparent steps without replacing zero
                        // extinction by absorption (faithful to integrate.slang's select expression).
                        vec4 integral = mix((vec4(1.0) - trans) / max(c.extinction, vec4(1e-30)),
                            dt * (vec4(1.0) - tau * 0.5 + tau * tau / 6.0),
                            lessThan(tau, vec4(0.001)));
                        vec4 stepDirect = spectralTransmittance * direct * integral;
                        vec4 stepMultiple = spectralTransmittance * multiple * integral;
                        // Direct spectral -> Rec.2020 -> RGB shadow. Multiple stays unshadowed.
                        directStep = physicalAtmRadiance(stepDirect);
                        multipleStep = physicalAtmRadiance(stepMultiple);
                        if (lunarValid) {
                            vec4 lunarDirect = vec4(0.0);
                            for (uint k = 0u; k < 5u; k++) lunarDirect += c.scattering[k] * lunarPhase[k];
                            // Reflected-solar spectrum approximation; fixed medium response is linear,
                            // so query the same source table at lunar elevation, not the solar one.
                            lunarDirect *= physicalAtmSunT(hp, lunarMu) * PATM_SOLAR;
                            vec4 lunarMultiple = physicalAtmIndirect(hp, dot(localUp, ray), lunarMu, lunarNu, c,
                                indirectHeight, lunarPhaseCoordinate);
                            lunarDirectStep = physicalAtmRadiance(spectralTransmittance * lunarDirect * integral);
                            lunarMultipleStep = physicalAtmRadiance(spectralTransmittance * lunarMultiple * integral);
                        }
                        spectralTransmittance *= trans;
                    }
                    float shadowWeight = float(shadowWeights[i - 1u]);
                    vec3 rgbShadow = vec3(1.0);
                    if (shadowWeight > 0.0 && shadowStrength != 0.0 && sunValid && camera.settings.x != 0.0
                            && !all(equal(directStep, vec3(0.0)))
                            && !physicalAtmGroundKms(hp, sampleSunMu)) {
                        vec3 samplePoint = origin + ray * ((entry + d) / 0.001);
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        traceRayEXT(
                            topLevelAS,
                            gl_RayFlagsTerminateOnFirstHitEXT,
                            SECONDARY_RAY_MASK,
                            1,
                            1,
                            1,
                            samplePoint + sun * 0.002,
                            0.001,
                            sun,
                            camera.sun.w,
                            1
                        );
                        rgbShadow = mix(vec3(1.0), shadowTransmittance, shadowStrength);
                    }
                    vec3 solarResidual = directStep * (rgbShadow - vec3(1.0)) * shadowWeight;
                    inscatter += (directStep * rgbShadow * shadowWeight + multipleStep)
                        * (max(camera.settings.x, 0.0) / PATM_SPACE_SUN);
                    skyShadowCorrection += solarResidual * (max(camera.settings.x, 0.0) / PATM_SPACE_SUN);
                    if (lunarValid) {
                        vec3 lunarShadow = vec3(1.0);
                        if (shadowWeight > 0.0 && shadowStrength != 0.0
                                && !all(equal(lunarDirectStep, vec3(0.0)))
                                && !physicalAtmGroundKms(hp, lunarMu)) {
                            vec3 samplePoint = origin + ray * ((entry + d) / 0.001);
                            shadowTransmittance = vec3(1.0);
                            shadowDynamicOccluder = 0u;
                            traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                                1, 1, 1, samplePoint + moon * 0.002, 0.001, moon, camera.sun.w, 1);
                            lunarShadow = mix(vec3(1.0), shadowTransmittance, shadowStrength);
                        }
                        vec3 lunarResidual = lunarDirectStep * (lunarShadow - vec3(1.0)) * shadowWeight;
                        inscatter += (lunarDirectStep * lunarShadow * shadowWeight + lunarMultipleStep)
                            * (lunarIrradiance / PATM_SPACE_SUN);
                        skyShadowCorrection += lunarResidual * (lunarIrradiance / PATM_SPACE_SUN);
                    }
                }
                // A finite camera segment never reaches the earth boundary surface, so the upstream
                // ground-radiance tail is deliberately absent: real MC surfaces already provide
                // their own lit color through the ray-traced primary hit.
                transmittance = physicalAtmRec2020Transmittance(spectralTransmittance);
                inscatter *= weatherVisibility;
                skyShadowCorrection *= weatherVisibility;
            }
            #endif
            """;
}
