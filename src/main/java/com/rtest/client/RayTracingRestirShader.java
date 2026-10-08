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
            uvec4 history;  // usable, per-pixel vec4 stride, frame, reserved
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
            vec3 diffuseRadiance;
            vec3 specularRadiance;
            vec3 directDiffuseRadiance;
            vec3 directSpecularRadiance;
            vec3 dynamicDiffuseDelta;
            vec3 dynamicSpecularDelta;
            float primaryDirectDistance;
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

        PrimeSampleBase restirDirectBase;
        bool restirDirectEligible;
        bool restirFinite(float value) { return !isnan(value) && !isinf(value); }
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
        // Selection depends only on supporting geometry, never on the chosen suffix or light.
        int restirPreviousPixel(vec3 primary, uint proposal) {
            vec3 delta = primary - camera.previousOrigin.xyz;
            float z = dot(delta, camera.previousForward.xyz);
            if (!(z > 0.001)) return -1;
            vec2 ndc = vec2(dot(delta, camera.previousRight.xyz)
                / (z * camera.previousParameters.x * camera.previousParameters.y),
                dot(delta, camera.previousUp.xyz) / (z * camera.previousParameters.x));
            vec2 uv = ndc * 0.5 + 0.5;
            ivec2 pixel = ivec2(floor(uv * vec2(gl_LaunchSizeEXT.xy)));
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
            float sum = 0.0;
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
                float weight = target * candidate.z / float(freshCount);
                sum += weight;
                if (restirRandom(200u + i) * sum < weight) {
                    selected = candidate;
                    selectedTarget = target;
                }
            }
            if (!(sum > 0.0) || !restirFinite(sum)) return selected;
            selected.z = sum / selectedTarget; // Fresh reservoir's UCW, already normalized by M.
            uint accepted = 1u;
            int seen[5];
            uint seenCount = 0u;
            if (restir.history.x != 0u) {
                for (uint proposal = 0u; proposal <= min(restir.controls.z, 4u); proposal++) {
                    int oldPixel = restirPreviousPixel(position, proposal);
                    if (oldPixel < 0) continue;
                    bool duplicate = false;
                    for (uint j = 0u; j < seenCount; j++) duplicate = duplicate || seen[j] == oldPixel;
                    if (duplicate) continue;
                    seen[seenCount++] = oldPixel;
                    uint oldOffset = uint(oldPixel) * restir.history.y;
                    vec4 old = restirPrevious.data[oldOffset];
                    vec4 geometry = restirPrevious.data[oldOffset + 1u];
                    float radius = max(0.15, 0.02 * length(position - camera.origin.xyz));
                    if (!(old.z > 0.0) || !restirFinite(old.z)
                        || !restirCompatible(geometry, position, normal, radius)) continue;
                    float oldTarget = restirDirectTarget(position, normal, view, color,
                        roughness, metallic, reflectivity, energy, old);
                    float weight = oldTarget * old.z;
                    sum += weight;
                    accepted++;
                    if (restirRandom(230u + proposal) * sum < weight) {
                        selected = old;
                        selectedTarget = oldTarget;
                    }
                }
            }
            selected.z = sum / (float(accepted) * selectedTarget); // Uniform MIS over full-support UCWs.
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
        int restirSuffixPixels[5];
        bool restirSuffixActive;
        bool restirPrefixValid;
        vec3 restirSupportPosition;
        vec3 restirSupportNormal;
        vec3 restirPrimaryPosition;
        float restirSourceWeight;
        float restirReservoirSum;
        float restirSelectedTarget;
        uint restirSelectedSeed;
        vec3 restirSumRadiance = vec3(0.0);
        vec3 restirSumIndirect = vec3(0.0);
        vec3 restirSumSpecular = vec3(0.0);
        vec3 restirSumTransmission = vec3(0.0);
        vec3 restirSumDynamicDiffuse = vec3(0.0);
        vec3 restirSumDynamicSpecular = vec3(0.0);
        float restirSumDiffuseDistance = 0.0;
        float restirSumSpecularDistance = 0.0;
        bool restirAnyDynamic = false;
        bool restirAnySkyDisk = false;

        uint restirSuffixOffset(uint pixel) {
            return pixel * restir.history.y + ((restir.controls.x & 1u) != 0u ? 2u : 0u);
        }
        void restirFindSuffixes() {
            restirSuffixCount = 0u;
            if (!restirPrefixValid || restir.history.x == 0u) return;
            int seen[5]; uint seenCount = 0u;
            for (uint proposal = 0u; proposal <= min(restir.controls.z, 4u); proposal++) {
                int pixel = restirPreviousPixel(restirPrimaryPosition, proposal);
                if (pixel < 0) continue;
                bool duplicate = false;
                for (uint j = 0u; j < seenCount; j++) duplicate = duplicate || seen[j] == pixel;
                if (duplicate) continue;
                seen[seenCount++] = pixel;
                uint offset = restirSuffixOffset(uint(pixel));
                vec4 old = restirPrevious.data[offset];
                if (!(old.y > 0.0) || !restirFinite(old.y)) continue;
                // Approximate a local supporting-prefix search in the reprojected screen window.
                if (!restirCompatible(restirPrevious.data[offset + 1u],
                        restirSupportPosition, restirSupportNormal, 2.0)) continue;
                restirSuffixPixels[restirSuffixCount++] = pixel;
            }
        }
        """;

    static final String MAIN = """
        void main() {
            uint pixel = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
            // Every enabled pixel writes both sample and geometry, including misses/fallbacks.
            for (uint i = 0u; i < restir.history.y; i++) restirCurrent.data[pixel * restir.history.y + i] = vec4(0.0);
            restirCameraHitReady = false;
            restirSuffixActive = (restir.controls.x & 2u) != 0u && uint(max(camera.parameters.z, 0.0) + 0.5) == 0u;
            restirPrefixCount = restirSuffixActive ? clamp(restir.controls.w, 1u, 4u) : 1u;
            for (restirPrefixIndex = 0u; restirPrefixIndex < restirPrefixCount; restirPrefixIndex++) {
                restirPrefixStateReady = false;
                restirSuffixCount = 0u;
                restirPrefixValid = false;
                restirReservoirSum = 0.0;
                restirSelectedTarget = 1e-6;
                for (restirProposalIndex = 0u; restirProposalIndex <= restirSuffixCount; restirProposalIndex++) {
                    if (restirProposalIndex == 0u) {
                        restirSourceWeight = 1.0;
                        restirSuffixSeed = primeHighQualityHash(pixel ^ primeHighQualityHash(restir.history.z)
                            ^ primeHighQualityHash(restirPrefixIndex + 0x9183u));
                        restirSelectedSeed = restirSuffixSeed;
                    } else {
                        vec4 source = restirPrevious.data[restirSuffixOffset(
                            uint(restirSuffixPixels[restirProposalIndex - 1u]))];
                        restirSuffixSeed = floatBitsToUint(source.x);
                        restirSourceWeight = source.y;
                    }
                    // A single call site avoids duplicating the integrator and output stores.
                    runRestirPath();
                }
            }
        }
        """;
}
