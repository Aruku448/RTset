package com.rtest.client;

/** Conditional RIS in light-area space and random-tape suffix space; see docs/restir-implementation.md. */
final class RayTracingRestirShader {
    private RayTracingRestirShader() {}

    static int requestedMode() {
        var config = RayTracingClientConfig.INSTANCE;
        int requested = (config.restirDirectEnabled.get() ? RestirLayout.DIRECT : 0)
            | (config.restirSuffixEnabled.get() ? RestirLayout.SUFFIX : 0);
        return RayTracingCostAudit.requested().effectiveRestirMode(requested);
    }

    static String variant(String source, int mode) {
        // Remove inactive paths at compile time while retaining the active uniform gate
        // so a device storage-limit fallback can safely set the effective mode to zero.
        if (mode == 0) return source.replace("restir.controls.x", "0u").replace("restir.history.y", "0u");
        if ((mode & RestirLayout.DIRECT) == 0) source = source.replace("(restir.controls.x & 1u)", "0u");
        if ((mode & RestirLayout.SUFFIX) == 0) source = source.replace("(restir.controls.x & 2u)", "0u");
        return source;
    }

    static final String DECLARATIONS = """
        layout(set = 0, binding = 41, std430) readonly buffer RestirPrevious { vec4 data[]; } restirPrevious;
        layout(set = 0, binding = 42, std430) writeonly buffer RestirCurrent { vec4 data[]; } restirCurrent;
        layout(set = 0, binding = 43, std140) uniform RestirParameters {
            uvec4 controls; // mode, fresh direct candidates, spatial proposals, gather prefixes
            uvec4 history;  // DI/GI valid bits, per-pixel vec4 stride, frame, gather flags
        } restir;
        """;

    static final String FUNCTIONS = """
        // Per-invocation state after primary BSDF scatter, reused by borrowed tapes.
        struct RestirPrefixState {
            vec3 rayDirection;
            vec3 rayOrigin;
            float rayTMin;
            vec3 throughput;
            bool restirPrimaryPrefixValid;
            vec3 prefixRadiance;
            vec3 prefixDynamicDiffuse;
            vec3 prefixDynamicSpecular;
            vec3 mediumAbsorption;
            bool insideMedium;
            vec3 radiance;
            vec3 specularRadiance;
            vec3 directDiffuseRadiance;
            vec3 directSpecularRadiance;
            vec3 dynamicDiffuseDelta;
            vec3 dynamicSpecularDelta;
            vec3 areaDirectDiffuseRadiance;
            vec3 areaDirectSpecularRadiance;
            vec3 indirectDiffuseRadiance;
            vec3 emissionRadiance;
            vec3 areaLightRadiance;
            vec3 transmissionRadiance;
            int spectralChannel;
            bool spectralMasked;
            float mediumIor;
            vec3 primaryPosition;
            vec3 primaryBaseColor;
            vec4 primaryLocalPosition;
            uint primaryDynamicSlot;
            bool pathContainsDynamicModel;
            vec3 primaryNormal;
            AreaLightSample primaryAreaLight;
            vec3 primaryAreaVisibility;
            vec3 primaryAreaStaticVisibility;
            bool primaryAreaLightValid;
            bool primaryDynamicShadow;
            float primaryRoughness;
            vec3 primaryRayDirection;
            vec4 primaryMaterial;
            bool primaryHit;
            bool primaryTransmissionPath;
            vec3 primaryDiffuseShare;
            bool primaryDiffuseGuide;
            bool primarySpecularGuide;
            bool skySunDiskHit;
            float diffuseHitDistance;
            float specularHitDistance;
            float previousBsdfPdf;
            int previousSunNeeSamples;
            vec3 previousSurfaceNormal;
            bool previousWasDelta;
            bool previousAreaNeeEnabled;
            bool previousSkyNeeEnabled;
        };
        RestirPrefixState restirPrefixState;
        PathPayload restirPrefixHit;
        PathPayload restirCameraHit;
        bool restirCameraHitReady;
        bool restirPrefixStateReady;
        // Primary area-light sampling and visibility are identical across gather prefixes.
        // This cache is private to this invocation; it never reuses previous-frame visibility.
        AreaDirectSplit restirPrimaryAreaDirect;
        bool restirPrimaryAreaDirectReady;

        struct RestirPrimaryNee {
            vec3 diffuse;
            vec3 specular;
            vec3 dynamicDiffuse;
            vec3 dynamicSpecular;
            bool dynamicOccluder;
        };
        RestirPrimaryNee restirPrimaryNee;
        bool restirPrimaryNeeReady;

        PrimeSampleBase restirDirectBase;
        bool restirDirectEligible;
        bool restirFinite(float value) { return !isnan(value) && !isinf(value); }
        // Maintain positive resampling mass as mantissa * 2^exponent. Products and
        // sums may exceed float range even when the final normalized UCW is finite.
        // This changes neither the target nor any input's mathematical mass.
        struct RestirWeightSum {
            float mantissa;
            int exponent;
            bool valid;
        };
        bool restirAddMass(inout RestirWeightSum total, float target, float ucw,
                float mass, float randomValue) {
            if (!(target > 0.0) || !(ucw > 0.0) || !(mass > 0.0)
                    || !restirFinite(target) || !restirFinite(ucw) || !restirFinite(mass)) {
                total.valid = false;
                return false;
            }
            int targetExponent, ucwExponent, massExponent;
            float weight = frexp(target, targetExponent) * frexp(ucw, ucwExponent)
                * frexp(mass, massExponent);
            int exponent = targetExponent + ucwExponent + massExponent;
            int sharedExponent = total.mantissa > 0.0 ? max(total.exponent, exponent) : exponent;
            int oldShift = total.exponent - sharedExponent;
            int newShift = exponent - sharedExponent;
            float oldMass = total.mantissa > 0.0 && oldShift >= -149
                ? ldexp(total.mantissa, oldShift) : 0.0;
            float newMass = newShift >= -149 ? ldexp(weight, newShift) : 0.0;
            float sum = oldMass + newMass;
            bool selected = randomValue * sum < newMass;
            int normalizationExponent;
            total.mantissa = frexp(sum, normalizationExponent);
            total.exponent = sharedExponent + normalizationExponent;
            return selected;
        }
        float restirResolveUcw(inout RestirWeightSum total, float target, float domains) {
            if (!total.valid || !(total.mantissa > 0.0)) return 0.0;
            int targetExponent, domainExponent;
            float divisor = frexp(target, targetExponent) * frexp(domains, domainExponent);
            int normalizationExponent;
            float mantissa = frexp(total.mantissa / divisor, normalizationExponent);
            int exponent = total.exponent - targetExponent - domainExponent + normalizationExponent;
            if (exponent > 128 || exponent < -149) {
                total.valid = false;
                return 0.0;
            }
            float ucw = ldexp(mantissa, exponent);
            // The persistent ABI stores a linear float UCW. An unrepresentable result
            // invalidates history rather than poisoning subsequent frames. This is an
            // explicit finite-precision failure, not an unbiased weight-clamping scheme.
            if (!(ucw > 0.0) || !restirFinite(ucw)) {
                total.valid = false;
                return 0.0;
            }
            return ucw;
        }
        float restirRandom(uint dimension) {
            uint pixel = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
            return float(primeHighQualityHash(pixel ^ primeHighQualityHash(restir.history.z)
                ^ primeHighQualityHash(dimension))) * PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
        }
        uint restirNormal(vec3 normal) {
            normal /= max(dot(abs(normal), vec3(1.0)), 1e-8);
            vec2 p = normal.xy;
            if (normal.z < 0.0) p = (1.0 - abs(p.yx)) * mix(vec2(-1.0), vec2(1.0), greaterThanEqual(p, vec2(0.0)));
            return packSnorm2x16(p);
        }
        vec3 restirDecodeNormal(float packed) {
            vec2 p = unpackSnorm2x16(floatBitsToUint(packed));
            vec3 n = vec3(p, 1.0 - abs(p.x) - abs(p.y));
            float t = max(-n.z, 0.0);
            n.xy += mix(vec2(t), vec2(-t), greaterThanEqual(n.xy, vec2(0.0)));
            return normalize(n);
        }
        // Reproject once per receiver, then use the same center for every proposal.
        ivec2 restirPreviousCenter(vec3 primary) {
            vec3 delta = primary - camera.previousOrigin.xyz;
            float z = dot(delta, camera.previousForward.xyz);
            if (!(z > 0.001)) return ivec2(-1000000);
            vec2 ndc = vec2(dot(delta, camera.previousRight.xyz)
                / (z * camera.previousParameters.x * camera.previousParameters.y),
                dot(delta, camera.previousUp.xyz) / (z * camera.previousParameters.x));
            vec2 uv = ndc * 0.5 + 0.5;
            return ivec2(floor(uv * vec2(gl_LaunchSizeEXT.xy)));
        }
        // Selection depends only on supporting geometry, never on the chosen suffix or light.
        int restirPreviousPixel(ivec2 center, uint proposal) {
            ivec2 pixel = center;
            if (proposal > 0u) {
                float angle = 6.28318530718 * restirRandom(100u + proposal * 2u);
                float radius = 2.0 + 6.0 * restirRandom(101u + proposal * 2u);
                pixel += ivec2(round(vec2(cos(angle), sin(angle)) * radius));
            }
            if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, ivec2(gl_LaunchSizeEXT.xy)))) return -1;
            return pixel.x + int(gl_LaunchSizeEXT.x) * pixel.y;
        }
        bool restirCompatible(vec4 geometry, vec3 position, vec3 normal, float radius) {
            return distance(geometry.xyz, position) <= radius
                && dot(restirDecodeNormal(geometry.w), normal) > 0.9;
        }
        float restirEmitterArea(uint emitter) {
            return uintBitsToFloat(lightData.values[lightData.values[4] + emitter * LIGHT_EMITTER_WORDS + 3u]);
        }
        AreaLightSample restirLight(vec3 position, vec4 sampleData) {
            uint emitter = floatBitsToUint(sampleData.w);
            if (emitter >= lightData.values[1] || !(sampleData.z > 0.0))
                return sampleAreaLightAtIndex(position, LIGHT_NO_EMITTER, 0.0, vec2(0.0));
            float edge = sampleData.x + sampleData.y;
            vec2 uv = vec2(edge * edge, edge > 0.0 ? sampleData.y / edge : 0.0);
            float area = restirEmitterArea(emitter);
            // W is an inverse area density; the existing NEE converts it to solid angle.
            return sampleAreaLightAtIndex(position, emitter, area / sampleData.z, uv);
        }
        float restirDirectTarget(vec3 position, vec3 normal, vec3 view, vec3 color,
                float roughness, float metallic, float reflectivity, vec2 energy, vec4 sampleData) {
            uint emitter = floatBitsToUint(sampleData.w);
            if (emitter >= lightData.values[1]) return 1e-6;
            float area = restirEmitterArea(emitter);
            // The target must not depend on the stochastic incoming UCW. Use a unit
            // triangle-selection proposal solely to recover the receiver geometry term.
            vec4 geometrySample = vec4(sampleData.xy, area, sampleData.w);
            AreaLightSample light = restirLight(position, geometrySample);
            vec3 contribution = vec3(0.0);
            if (light.pdf > 0.0 && emitter < lightData.values[1]) {
                float specularProbability = preparedSpecularSampleProbability(energy, roughness, metallic);
                BsdfValue bsdf = evaluateBsdf(normal, view, light.direction, color, roughness,
                    metallic, reflectivity, 1.0 - metallic, 1.0 - specularProbability, specularProbability, energy);
                // Recover G without the light-selection PDF. No visibility ray per candidate.
                float geometry = 1.0 / max(light.pdf * area, 1e-20);
                contribution = evaluateEmitter(emitter, sampleData.xy) * bsdf.f
                    * max(dot(normal, light.direction), 0.0) * geometry;
            }
            // Positive support even at black texels/backfaces; avoids biased cross-receiver reuse.
            return max(dot(max(contribution, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)), 1e-6);
        }
        vec4 restirDirectSample(vec3 position, vec3 normal, vec3 view, vec3 color,
                float roughness, float metallic, float reflectivity, vec2 energy, PrimeSampleBase base) {
            uint pixel = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
            uint offset = pixel * restir.history.y;
            vec4 selected = vec4(0.0, 0.0, 0.0, uintBitsToFloat(LIGHT_NO_EMITTER));
            if (lightData.values[1] == 0u) return selected;
            RestirWeightSum sum = RestirWeightSum(0.0, 0, true);
            float selectedTarget = 1e-6;
            uint freshCount = clamp(restir.controls.y, 1u, 16u);
            for (uint i = 0u; i < freshCount; i++) {
                vec3 u = primeSobolSample3D(base, 12u, i);
                float pdf;
                uint emitter = pickLightLeaf(position, u.x, pdf);
                if (emitter == LIGHT_NO_EMITTER || !(pdf > 0.0)) continue;
                float edge = sqrt(u.y);
                vec4 candidate = vec4(edge * (1.0 - u.z), edge * u.z,
                    restirEmitterArea(emitter) / pdf, uintBitsToFloat(emitter));
                float target = restirDirectTarget(position, normal, view, color,
                    roughness, metallic, reflectivity, energy, candidate);
                if (restirAddMass(sum, target, candidate.z, 1.0 / float(freshCount),
                        restirRandom(200u + i))) {
                    selected = candidate;
                    selectedTarget = target;
                }
            }
            if (!(sum.mantissa > 0.0) || !sum.valid) {
                selected.z = 0.0;
                return selected;
            }
            // Keep the fresh aggregate in scaled form while merging history.
            uint accepted = 1u;
            int seen[5];
            uint seenCount = 0u;
            if ((restir.history.x & 1u) != 0u) {
                ivec2 center = restirPreviousCenter(position);
                float radius = max(0.15, 0.02 * length(position - camera.origin.xyz));
                for (uint proposal = 0u; proposal <= min(restir.controls.z, 4u); proposal++) {
                    int oldPixel = restirPreviousPixel(center, proposal);
                    if (oldPixel < 0) continue;
                    bool duplicate = false;
                    for (uint j = 0u; j < seenCount; j++) duplicate = duplicate || seen[j] == oldPixel;
                    if (duplicate) continue;
                    seen[seenCount++] = oldPixel;
                    uint oldOffset = uint(oldPixel) * restir.history.y;
                    vec4 old = restirPrevious.data[oldOffset];
                    vec4 geometry = restirPrevious.data[oldOffset + 1u];
                    if (!(old.z > 0.0) || !restirFinite(old.z)
                        || !restirCompatible(geometry, position, normal, radius)) continue;
                    // Reused copies may have different UCWs but the same area sample.
                    // Its target is independent of the UCW; retain every input's MIS mass.
                    bool sameSample = all(equal(old.xy, selected.xy))
                        && floatBitsToUint(old.w) == floatBitsToUint(selected.w);
                    float oldTarget = sameSample ? selectedTarget
                        : restirDirectTarget(position, normal, view, color,
                            roughness, metallic, reflectivity, energy, old);
                    accepted++;
                    if (restirAddMass(sum, oldTarget, old.z, 1.0,
                            restirRandom(230u + proposal))) {
                        selected = old;
                        selectedTarget = oldTarget;
                    }
                }
            }
            selected.z = restirResolveUcw(sum, selectedTarget, float(accepted));
            restirCurrent.data[offset] = selected;
            restirCurrent.data[offset + 1u] = vec4(position, uintBitsToFloat(restirNormal(normal)));
            return selected;
        }

        // Replay shift: the suffix integration variable is a uniformly sampled random tape.
        // Its identity mapping has determinant 1 and full support at every supporting prefix.
        // Prefix dimensions NEVER use this seed, making joint prefix/suffix UCWs independent.
        uint restirSuffixSeed;
        uint restirPrefixIndex;
        uint restirProposalIndex;
        uint restirPrefixCount;
        uint restirSuffixCount;
        uint restirSuffixDomainCount;
        uint restirSuffixReadCount;
        uint restirProposalCount;
        uint restirSuffixSeeds[5];
        float restirSuffixWeights[5];
        bool restirHasCanonicalSuffix;
        bool restirSuffixActive;
        bool restirPrefixValid;
        vec3 restirSupportPosition;
        vec3 restirSupportNormal;
        vec3 restirPrimaryPosition;
        float restirSourceWeight;
        float restirGatherSelectionScale;
        RestirWeightSum restirReservoirSum;
        float restirSelectedTarget;
        uint restirSelectedSeed;
        vec3 restirSumRadiance = vec3(0.0);
        vec3 restirSumIndirect = vec3(0.0);
        vec3 restirSumSpecular = vec3(0.0);
        vec3 restirSumTransmission = vec3(0.0);
        vec3 restirSumDynamicDiffuse = vec3(0.0);
        vec3 restirSumDynamicSpecular = vec3(0.0);
        float restirSumDiffuseDistance = 0.0;
        uint restirDiffuseDistanceCount = 0u;
        float restirSumSpecularDistance = 0.0;
        bool restirAnyDynamic = false;
        bool restirAnySkyDisk = false;

        uint restirSuffixOffset(uint pixel) {
            return pixel * restir.history.y + ((restir.controls.x & 1u) != 0u ? 2u : 0u);
        }
        void restirFindSuffixes() {
            restirSuffixCount = 0u;
            restirSuffixDomainCount = 0u;
            restirSuffixReadCount = 0u;
            if (!restirPrefixValid || (restir.history.x & 2u) == 0u) return;
            int seen[5]; uint seenCount = 0u;
            ivec2 center = restirPreviousCenter(restirPrimaryPosition);
            for (uint proposal = 0u; proposal <= min(restir.controls.z, 4u); proposal++) {
                int pixel = restirPreviousPixel(center, proposal);
                if (pixel < 0) continue;
                bool duplicate = false;
                for (uint j = 0u; j < seenCount; j++) duplicate = duplicate || seen[j] == pixel;
                if (duplicate) continue;
                seen[seenCount++] = pixel;
                restirSuffixReadCount++;
                uint offset = restirSuffixOffset(uint(pixel));
                vec4 old = restirPrevious.data[offset];
                if (!(old.y > 0.0) || !restirFinite(old.y)) continue;
                // Approximate a local supporting-prefix search in the reprojected screen window.
                if (!restirCompatible(restirPrevious.data[offset + 1u],
                        restirSupportPosition, restirSupportNormal, 2.0)) continue;
                restirSuffixDomainCount++;
                // Identical tapes evaluate the same integrand at this prefix. Coalesce
                // their UCWs, but keep the original domain count in the MIS denominator.
                // Never drop duplicate samples or average their weights a second time.
                uint seed = floatBitsToUint(old.x);
                bool combined = false;
                for (uint j = 0u; j < restirSuffixCount; j++) {
                    float combinedWeight = restirSuffixWeights[j] + old.y;
                    if (restirSuffixSeeds[j] == seed && restirFinite(combinedWeight)) {
                        restirSuffixWeights[j] = combinedWeight;
                        combined = true;
                        break;
                    }
                }
                if (!combined) {
                    restirSuffixSeeds[restirSuffixCount] = seed;
                    restirSuffixWeights[restirSuffixCount++] = old.y;
                }
            }
        }
        void restirBeginSuffixReplay(inout PrimeSampleBase base) {
            // Called after the supporting endpoint is known, before ANY suffix draws.
            restirFindSuffixes();
            // All accepted tapes have full support in the same normalized seed domain
            // (identity shifts and strictly positive targets). Thus later integration
            // prefixes can use borrowed UCWs alone. The first prefix always refreshes
            // the reservoir with a canonical tape; unsupported/history misses fall back.
            restirHasCanonicalSuffix = restirPrefixIndex == 0u || restirSuffixDomainCount == 0u;
            restirProposalCount = restirSuffixCount + (restirHasCanonicalSuffix ? 1u : 0u);
            if (!restirHasCanonicalSuffix) {
                uint sourceIndex = 0u;
                if ((restir.history.w & 1u) != 0u) {
                    // Uniform selection over coalesced groups: q=1/uniqueCount.
                    // Domain denominator remains the original source count in gather.
                    sourceIndex = min(uint(restirRandom(450u + restirPrefixIndex)
                        * float(restirSuffixCount)), restirSuffixCount - 1u);
                    restirGatherSelectionScale = float(restirSuffixCount);
                    restirProposalCount = 1u;
                }
                restirSuffixSeed = restirSuffixSeeds[sourceIndex];
                restirSourceWeight = restirSuffixWeights[sourceIndex];
                base.sampleEpoch = restirSuffixSeed;
            }
        }
        """;

    static final String MAIN = """
        void main() {
            uint pixel = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
            // Every enabled pixel writes both sample and geometry, including misses/fallbacks.
            for (uint i = 0u; i < restir.history.y; i++) restirCurrent.data[pixel * restir.history.y + i] = vec4(0.0);
            restirCameraHitReady = false;
            restirPrimaryAreaDirectReady = false;
            restirPrimaryNeeReady = false;
            restirSuffixActive = (restir.controls.x & 2u) != 0u && uint(max(camera.parameters.z, 0.0) + 0.5) == 0u;
            restirPrefixCount = restirSuffixActive ? clamp(restir.controls.w, 1u, 4u) : 1u;
            for (restirPrefixIndex = 0u; restirPrefixIndex < restirPrefixCount; restirPrefixIndex++) {
                restirPrefixStateReady = false;
                restirSuffixCount = 0u;
                restirSuffixDomainCount = 0u;
                restirProposalCount = 1u;
                restirHasCanonicalSuffix = true;
                restirGatherSelectionScale = 1.0;
                restirPrefixValid = false;
                restirReservoirSum = RestirWeightSum(0.0, 0, true);
                restirSelectedTarget = 1e-6;
                for (restirProposalIndex = 0u; restirProposalIndex < restirProposalCount; restirProposalIndex++) {
                    if (restirProposalIndex == 0u) {
                        restirSourceWeight = 1.0;
                        restirSuffixSeed = primeHighQualityHash(pixel ^ primeHighQualityHash(restir.history.z)
                            ^ primeHighQualityHash(restirPrefixIndex + 0x9183u));
                        restirSelectedSeed = restirSuffixSeed;
                    } else {
                        uint sourceIndex = restirProposalIndex - (restirHasCanonicalSuffix ? 1u : 0u);
                        restirSuffixSeed = restirSuffixSeeds[sourceIndex];
                        restirSourceWeight = restirSuffixWeights[sourceIndex];
                    }
                    // A single call site avoids duplicating the integrator and output stores.
                    runRestirPath();
                }
            }
        }
        """;
}
