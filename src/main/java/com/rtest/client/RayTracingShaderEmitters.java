package com.rtest.client;

/** Emitter sampling, visibility and surface/volume lighting source. */
final class RayTracingShaderEmitters {
    private RayTracingShaderEmitters() {
    }

    static final String GLSL = String.join("", """
            const uint LIGHT_HEADER_WORDS = 8u;
            const uint LIGHT_NODE_WORDS = 8u;
            const uint LIGHT_EMITTER_WORDS = 16u;
            const uint LIGHT_LEAF_FLAG = 0x80000000u;
            const uint LIGHT_NO_EMITTER = 0xffffffffu;
            struct AreaLightSample {
                vec3 position;
                vec3 direction;
                float distance;
                float pdf;
                // Actual tree-selection probability at the point that generated this sample.
                float selectionPdf;
                uint emitterIndex;
                vec2 barycentric;
            };
            float lightNodeFloat(uint node, uint word) {
                uint base = LIGHT_HEADER_WORDS + node * LIGHT_NODE_WORDS + word;
                return uintBitsToFloat(lightData.values[base]);
            }
            float lightNodeDistanceSquared(uint node, vec3 point) {
                vec3 minimum = vec3(lightNodeFloat(node, 0u), lightNodeFloat(node, 1u), lightNodeFloat(node, 2u));
                vec3 maximum = vec3(lightNodeFloat(node, 4u), lightNodeFloat(node, 5u), lightNodeFloat(node, 6u));
                vec3 closest = clamp(point, minimum, maximum);
                vec3 delta = point - closest;
                return dot(delta, delta) + lightNodeFloat(node, 7u);
            }
            float lightBranchProbability(uint left, uint right, vec3 point) {
                float leftScore = max(lightNodeFloat(left, 3u), 0.0) * lightNodeDistanceSquared(right, point);
                float rightScore = max(lightNodeFloat(right, 3u), 0.0) * lightNodeDistanceSquared(left, point);
                float sum = leftScore + rightScore;
                // Both children retain support; use this exact left probability in both directions.
                return sum > 0.0 ? clamp(leftScore / sum, 0.000001, 0.999999) : -1.0;
            }
            uint emitterPbrReadPixel(uint offset, uint width, uint height, float u, float v) {
                if (offset == 0xffffffffu || width == 0u || height == 0u) return 0u;
                float sampleU = clamp(u, 0.0, 0.99999994);
                float sampleV = clamp(v, 0.0, 0.99999994);
                if (height > width && width > 0u) {
                    uint frameHeight = width;
                    uint frameCount = max(height / frameHeight, 1u);
                    uint frame = (floatBitsToUint(camera.random.w) / 2u) % frameCount;
                    sampleV = (float(frame) + sampleV) * float(frameHeight) / float(height);
                }
                uint x = min(uint(sampleU * float(width)), width - 1u);
                uint y = min(uint(sampleV * float(height)), height - 1u);
                return pbrData.values[offset + y * width + x];
            }
            float evaluateEmitterEmission(float fallbackEmission, uint mapIndex, vec2 atlasUv) {
                uint packedMode = uint(max(camera.pbrSettings.x, 0.0) + 0.5);
                uint features = packedMode >> 8u;
                if ((features & 8u) == 0u) return fallbackEmission;
                if (mapIndex == 0u || mapIndex > pbrData.values[0]) return fallbackEmission;
                uint info = 1u + (mapIndex - 1u) * 10u;
                uint specularOffset = pbrData.values[info + 1u];
                uint specularWidth = pbrData.values[info + 4u];
                uint specularHeight = pbrData.values[info + 5u];
                if (specularOffset == 0xffffffffu || specularWidth == 0u || specularHeight == 0u) {
                    return fallbackEmission;
                }
                float u0 = uintBitsToFloat(pbrData.values[info + 6u]);
                float u1 = uintBitsToFloat(pbrData.values[info + 7u]);
                float v0 = uintBitsToFloat(pbrData.values[info + 8u]);
                float v1 = uintBitsToFloat(pbrData.values[info + 9u]);
                vec2 span = vec2(max(u1 - u0, 0.000001), max(v1 - v0, 0.000001));
                vec2 localUv = clamp((atlasUv - vec2(u0, v0)) / span, 0.0, 0.99999994);
                uint pixel = emitterPbrReadPixel(
                    specularOffset, specularWidth, specularHeight, localUv.x, localUv.y);
                uint format = packedMode & 0xffu;
                uint redByte = (pixel >> 16u) & 0xffu;
                uint greenByte = (pixel >> 8u) & 0xffu;
                uint blueByte = pixel & 0xffu;
                uint alphaByte = (pixel >> 24u) & 0xffu;
                float authoredEmission;
                if (format == 2u) {
                    authoredEmission = float(greenByte) / 255.0;
                } else if (format == 0u) {
                    authoredEmission = alphaByte < 255u ? float(alphaByte) / 254.0 : 0.0;
                } else {
                    authoredEmission = float(blueByte) / 255.0;
                }
                // Reaching this point means an emission-capable PBR map is bound. Its channel is a
                // full mask, so a zero texel must suppress the material fallback rather than making
                // the entire textured quad an emitter.
                // The visible surface multiplier is deliberately independent from the light-tree
                // radiance scale. Otherwise a correctly masked PBR lamp looks right but contributes
                // only the small display-glow value to direct lighting.
                // surface.z carries the calibrated block-light radiance for terrain emitters. The
                // authored channel only masks the emitting pixels; replacing that radiance with the
                // user-facing PBR glow strength made torch and lantern irradiance far too weak and
                // also made the CPU light-tree PDF disagree with this GPU evaluation. Materials that
                // have no Minecraft block-light level retain the authored-emission calibration.
                float emitterStrength = fallbackEmission > 0.0
                    ? fallbackEmission
                    : max(camera.pbrSettings.z, camera.pbrParallaxSettings.y);
                return authoredEmission * max(emitterStrength, 0.0);
            }
            uint pickLightLeaf(vec3 point, float seed, out float pdf) {
                pdf = 1.0;
                if (lightData.values[0] == 0u || lightData.values[1] == 0u) return LIGHT_NO_EMITTER;
                uint node = 0u;
                float value = seed;
                for (uint depth = 0u; depth < 64u; depth++) {
                    uint childOrLeaf = lightData.values[lightData.values[2] + node];
                    if ((childOrLeaf & LIGHT_LEAF_FLAG) != 0u) return childOrLeaf & 0x7fffffffu;
                    uint left = childOrLeaf;
                    uint right = left + 1u;
                    float leftProbability = lightBranchProbability(left, right, point);
                    if (!(leftProbability >= 0.0)) return LIGHT_NO_EMITTER;
                    float rightProbability = 1.0 - leftProbability;
                    if (value < leftProbability) {
                        if (!(leftProbability > 0.0)) return LIGHT_NO_EMITTER;
                        pdf *= leftProbability;
                        value /= leftProbability;
                        node = left;
                    } else {
                        if (!(rightProbability > 0.0)) return LIGHT_NO_EMITTER;
                        pdf *= rightProbability;
                        value = (value - leftProbability) / rightProbability;
                        node = right;
                    }
                }
                return LIGHT_NO_EMITTER;
            }
            vec3 evaluateEmitter(uint emitterIndex, vec2 barycentric) {
                uint base = lightData.values[4] + emitterIndex * LIGHT_EMITTER_WORDS;
                uint materialIndex = lightData.values[base + 15u];
                uint materialBase = materialIndex * 7u;
                vec4 tint = materials.entries[materialBase];
                vec4 uv01 = materials.entries[materialBase + 2u];
                vec4 uv2 = materials.entries[materialBase + 3u];
                vec4 lighting = materials.entries[materialBase + 4u];
                vec4 surface = materials.entries[materialBase + 5u];
                // The light tree stores area-normalized source radiance separately from the
                // visible material emission. This keeps compact lantern/torch geometry as bright
                // as a full glowstone face without clipping the directly visible surface.
                float emitterFallbackEmission =
                    max(uintBitsToFloat(lightData.values[base + 7u]), 0.0);
                vec2 uv = uv01.xy * (1.0 - barycentric.x - barycentric.y)
                    + uv01.zw * barycentric.x + uv2.xy * barycentric.y;
                vec4 texel = uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0);
                vec3 color = uv2.w > 0.5
                    ? materialLinearSrgbToWorking(materialDecodeSrgb(texel.rgb) * materialDecodeSrgb(tint.rgb))
                    : tint.rgb;
                if (uv2.z > 0.5 && texel.a < 0.5) return vec3(0.0);
                float emitterEmission = evaluateEmitterEmission(
                    emitterFallbackEmission, uint(max(lighting.w, 0.0) + 0.5), uv);
                return max(color, vec3(0.0)) * emitterEmission;
            }
            float emitterSelectionPdf(vec3 point, uint emitterIndex) {
                if (emitterIndex == LIGHT_NO_EMITTER || lightData.values[1] == 0u
                        || emitterIndex >= lightData.values[1]) return 0.0;
                uint node = lightData.values[lightData.values[7] + emitterIndex];
                float pdf = 1.0;
                for (uint depth = 0u; depth < 64u; depth++) {
                    if (node == 0u) return pdf;
                    uint parent = lightData.values[lightData.values[3] + node];
                    if (parent == 0xffffffffu) return 0.0;
                    uint sibling = (node & 1u) != 0u ? node + 1u : node - 1u;
                    uint left = min(node, sibling);
                    uint right = max(node, sibling);
                    float leftProbability = lightBranchProbability(left, right, point);
                    if (!(leftProbability >= 0.0)) return 0.0;
                    float branch = node == left ? leftProbability : 1.0 - leftProbability;
                    pdf *= branch;
                    node = parent;
                }
                return 0.0;
            }
            """, """
            AreaLightSample sampleAreaLightAtIndex(vec3 surfacePosition, uint emitterIndex,
                    float selectionPdf, vec2 areaSample) {
                AreaLightSample result;
                result.position = vec3(0.0);
                result.direction = vec3(0.0, 1.0, 0.0);
                result.distance = 0.0;
                result.pdf = 0.0;
                result.selectionPdf = 0.0;
                result.emitterIndex = LIGHT_NO_EMITTER;
                result.barycentric = vec2(0.0);
                if (emitterIndex == LIGHT_NO_EMITTER || !(selectionPdf > 0.0)) return result;
                uint base = lightData.values[4] + emitterIndex * LIGHT_EMITTER_WORDS;
                float squareRoot = sqrt(areaSample.x);
                vec2 barycentric = vec2(squareRoot * (1.0 - areaSample.y), squareRoot * areaSample.y);
                vec3 corner = vec3(uintBitsToFloat(lightData.values[base]), uintBitsToFloat(lightData.values[base + 1u]), uintBitsToFloat(lightData.values[base + 2u]));
                vec3 edgeOne = vec3(uintBitsToFloat(lightData.values[base + 4u]), uintBitsToFloat(lightData.values[base + 5u]), uintBitsToFloat(lightData.values[base + 6u]));
                vec3 edgeTwo = vec3(uintBitsToFloat(lightData.values[base + 8u]), uintBitsToFloat(lightData.values[base + 9u]), uintBitsToFloat(lightData.values[base + 10u]));
                float area = uintBitsToFloat(lightData.values[base + 3u]);
                vec3 lightPosition = corner + edgeOne * barycentric.x + edgeTwo * barycentric.y;
                vec3 delta = lightPosition - surfacePosition;
                float distanceSquared = dot(delta, delta);
                float distance = sqrt(max(distanceSquared, 0.0));
                if (!(distance > 0.0) || !(area > 0.0)) return result;
                vec3 direction = delta / distance;
                vec3 normal = vec3(uintBitsToFloat(lightData.values[base + 12u]), uintBitsToFloat(lightData.values[base + 13u]), uintBitsToFloat(lightData.values[base + 14u]));
                float lightCosine = max(dot(normal, -direction), 0.0);
                if (!(lightCosine > 0.0) || !(selectionPdf > 0.0)) return result;
                result.position = lightPosition;
                result.direction = direction;
                result.distance = max(distance - 0.002, 0.0);
                result.pdf = selectionPdf * distanceSquared / (area * lightCosine);
                result.selectionPdf = selectionPdf;
                result.emitterIndex = emitterIndex;
                result.barycentric = barycentric;
                return result;
            }
            AreaLightSample sampleAreaLight(vec3 surfacePosition, vec3 sampleValue) {
                float treePdf;
                uint emitterIndex = pickLightLeaf(surfacePosition, sampleValue.x, treePdf);
                return sampleAreaLightAtIndex(surfacePosition, emitterIndex, treePdf, sampleValue.yz);
            }
            AreaLightSample sampleVolumeAreaLight(vec3 volumePosition, vec3 sampleValue) {
                // Half of the proposal uses the light tree at the actual volume point. The other
                // half selects uniformly among all emitters, so a light with zero tree weight at
                // this point still has support. Both branches use the full mixture PDF below.
                uint emitterCount = lightData.values[1];
                if (emitterCount == 0u) {
                    return sampleAreaLightAtIndex(volumePosition, LIGHT_NO_EMITTER, 0.0, sampleValue.yz);
                }
                uint emitterIndex;
                float treePdf;
                if (sampleValue.x < 0.5) {
                    emitterIndex = pickLightLeaf(volumePosition, sampleValue.x * 2.0, treePdf);
                } else {
                    emitterIndex = min(uint((sampleValue.x - 0.5) * 2.0 * float(emitterCount)),
                        emitterCount - 1u);
                    treePdf = emitterSelectionPdf(volumePosition, emitterIndex);
                }
                float selectionPdf = 0.5 * (treePdf + 1.0 / float(emitterCount));
                return sampleAreaLightAtIndex(volumePosition, emitterIndex, selectionPdf, sampleValue.yz);
            }
            struct EmitterVisibilityRay { vec3 direction; float tMax; };
            EmitterVisibilityRay prepareEmitterVisibilityRay(vec3 shadowOrigin, vec3 target) {
                vec3 delta = target - shadowOrigin;
                float distance = length(delta);
                float coordinateScale = max(max(max(abs(shadowOrigin.x), abs(shadowOrigin.y)), abs(shadowOrigin.z)),
                    max(max(abs(target.x), abs(target.y)), abs(target.z)));
                float endpointMargin = max(0.0001, 4.0 * 1.1920929e-7 * max(coordinateScale, distance));
                float tMax = distance - endpointMargin;
                return EmitterVisibilityRay(delta / max(distance, 1.0e-8), tMax);
            }
            void traceEmitterVisibility(vec3 shadowOrigin, EmitterVisibilityRay ray) {
                shadowTransmittance = vec3(1.0);
                shadowDynamicOccluder = 0u;
                if (!(ray.tMax > 0.001) || camera.settings.y == 0.0) return;
                traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                    1, 1, 1, shadowOrigin, 0.001, ray.direction, ray.tMax, 1);
            }
            void traceEmitterVisibility(vec3 shadowOrigin, vec3 target) {
                traceEmitterVisibility(shadowOrigin, prepareEmitterVisibilityRay(shadowOrigin, target));
            }
            vec3 staticShadowVisibility(vec3 origin, vec3 direction, float tMax, bool dynamicHit, vec3 actual) {
                if (!dynamicHit || camera.dynamicParameters.w < 0.0 || camera.settings.y == 0.0) return actual;
                shadowExcludeDynamic = 1u;
                shadowTransmittance = vec3(1.0);
                shadowDynamicOccluder = 0u;
                if (tMax > 0.001) traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT,
                    SECONDARY_RAY_MASK, 1, 1, 1, origin, 0.001, direction, tMax, 1);
                shadowExcludeDynamic = 0u;
                return mix(vec3(1.0), shadowTransmittance, camera.settings.y);
            }
            vec3 staticEmitterVisibility(vec3 origin, EmitterVisibilityRay ray, bool dynamicHit, vec3 actual) {
                return staticShadowVisibility(origin, ray.direction, ray.tMax, dynamicHit, actual);
            }
            vec3 staticEmitterVisibility(vec3 origin, vec3 target, bool dynamicHit, vec3 actual) {
                if (!dynamicHit) return actual;
                return staticEmitterVisibility(origin, prepareEmitterVisibilityRay(origin, target), dynamicHit, actual);
            }
            bool emitterTransportRequired(vec3 actual, vec3 staticVisibility) {
                // Keep colored transmission and the unoccluded dynamic-shadow baseline.
                return any(notEqual(actual, vec3(0.0))) || any(notEqual(staticVisibility, vec3(0.0)));
            }
            vec3 sampleLegacyVolumeEmitter(vec3 volumePosition, vec3 viewRay,
                    AreaLightSample light) {
                if (!(light.pdf > 0.0)) return vec3(0.0);
                vec3 volumeDelta = light.position - volumePosition;
                float volumeDistanceSquared = dot(volumeDelta, volumeDelta);
                if (!(volumeDistanceSquared > 1.0e-8)) return vec3(0.0);
                vec3 volumeDirection = volumeDelta * inversesqrt(volumeDistanceSquared);
                uint emitterBase = lightData.values[4] + light.emitterIndex * LIGHT_EMITTER_WORDS;
                float emitterArea = uintBitsToFloat(lightData.values[emitterBase + 3u]);
                vec3 emitterNormal = vec3(
                    uintBitsToFloat(lightData.values[emitterBase + 12u]),
                    uintBitsToFloat(lightData.values[emitterBase + 13u]),
                    uintBitsToFloat(lightData.values[emitterBase + 14u]));
                float emitterCosine = max(dot(emitterNormal, -volumeDirection), 0.0);
                if (!(emitterArea > 0.0) || !(emitterCosine > 0.0)) return vec3(0.0);
                float volumePdf = light.selectionPdf
                    * volumeDistanceSquared / max(emitterArea * emitterCosine, 1.0e-6);
                if (!(volumePdf > 0.0)) return vec3(0.0);
                vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric);
                float phase = atmosphereAerosolPhase(dot(viewRay, volumeDirection));
                return sourceRadiance * phase / volumePdf;
            }
            #ifdef RTEST_ATMOSPHERE_LUT
            // Both local paths use the active four-wave medium generation. The RGB conversion is
            // solar-spectrum weighted, matching the existing physical finite-segment T convention.
            vec4 physicalAtmLocalSpectralTransmittance(float startHeightKm, vec3 direction,
                    float distanceKm) {
                if (!(distanceKm > 0.0)) return vec4(1.0);
                vec4 tau = vec4(0.0);
                float stepKm = distanceKm * 0.25;
                for (int i = 0; i < 4; i++) {
                    float sampleDistance = (float(i) + 0.5) * stepKm;
                    float h = physicalAtmHeightAt(startHeightKm, direction.y, sampleDistance);
                    tau += physicalAtmMedium(h).extinction * stepKm;
                }
                return exp(-tau);
            }
            vec4 physicalAtmLocalSpectralPhaseScattering(PhysicalAtmMedium medium, float cosine) {
                float mu = clamp(cosine, -1.0, 1.0);
                vec4 spectral = medium.scattering[0]
                    * (3.0 * (1.0 + mu * mu) / (16.0 * PATM_PI));
                PhysicalAtmPhaseStencil stencil = physicalAtmPhaseStencil(mu);
                for (uint species = 0u; species < 4u; species++) {
                    spectral += medium.scattering[species + 1u]
                        * physicalAtmTabulatedPhase(stencil, species);
                }
                return spectral;
            }
            vec3 samplePhysicalVolumeEmitter(vec3 origin, vec3 viewRay, float segmentBlocks,
                    float eyeRadiusKm, PrimeSampleBase sampleBase, out bool dynamicOccluder, out vec3 dynamicDelta) {
                dynamicOccluder = false;
                dynamicDelta = vec3(0.0);
                if (!(segmentBlocks > 1.0e-3)) return vec3(0.0);
                int quality = int(clamp(camera.settings.w, 1.0, 3.0) + 0.5);
                int count = quality == 1 ? 1 : (quality == 3 ? 4 : 2);
                float segmentKm = segmentBlocks * 0.001;
                float eyeHeightKm = eyeRadiusKm - PATM_BOTTOM_KM;
                sampleBase.vertexIndex = 0u;
                sampleBase.pathIndex = 0u;
                vec3 sum = vec3(0.0);
                for (int i = 0; i < 4; i++) {
                    if (i >= count) break;
                    float sampleU = primeSobolSample1D(sampleBase,
                        PRIME_SAMPLE_EFFECT_VOLUME_DISTANCE, uint(i));
                    float distanceKm = segmentKm * (float(i) + sampleU) / float(count);
                    vec3 volumePosition = origin + viewRay * (distanceKm / 0.001);
                    AreaLightSample light = sampleVolumeAreaLight(volumePosition,
                        primeSobolSample3D(sampleBase, PRIME_SAMPLE_EFFECT_VOLUME_EMITTER, uint(i)));
                    if (light.pdf > 0.0) {
                        vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric);
                        if (any(greaterThan(sourceRadiance, vec3(0.0)))) {
                            vec3 emitterShadowOrigin = volumePosition + light.direction * 0.002;
                            EmitterVisibilityRay emitterRay = prepareEmitterVisibilityRay(emitterShadowOrigin, light.position);
                            traceEmitterVisibility(emitterShadowOrigin, emitterRay);
                            bool sampleDynamic = shadowDynamicOccluder != 0u;
                            dynamicOccluder = dynamicOccluder || sampleDynamic;
                            vec3 visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                            vec3 staticVisibility = staticEmitterVisibility(emitterShadowOrigin,
                                emitterRay, sampleDynamic, visibility);
                            if (!emitterTransportRequired(visibility, staticVisibility)) continue;
                            // Neither rejected proposals nor doubly occluded samples need medium tables.
                            float volumeHeightKm = physicalAtmHeightAt(eyeHeightKm, viewRay.y, distanceKm);
                            PhysicalAtmMedium medium = physicalAtmMedium(volumeHeightKm);
                            vec4 viewT = physicalAtmLocalSpectralTransmittance(eyeHeightKm, viewRay, distanceKm);
                            vec4 phaseScattering = physicalAtmLocalSpectralPhaseScattering(
                                medium, dot(viewRay, light.direction));
                            float sourceKm = length(light.position - volumePosition) * 0.001;
                            vec4 sourceT = physicalAtmLocalSpectralTransmittance(
                                volumeHeightKm, light.direction, sourceKm);
                            // Compose medium factors spectrally, then project once. Lamp RGB still
                            // uses the documented solar-spectrum proxy; this is not full spectral BSDF.
                            vec3 transport = max(physicalAtmLinearRec2020FromSpectral(
                                viewT * phaseScattering * sourceT * PATM_SOLAR)
                                / physicalAtmLinearRec2020FromSpectral(PATM_SOLAR), vec3(0.0));
                            sum += transport * sourceRadiance * visibility / light.pdf;
                            dynamicDelta += transport * sourceRadiance * (visibility - staticVisibility) / light.pdf;
                        }
                    }

                    // Sky incident scattering is already represented by the shared multiple LUT.
                    // Do not add a second full hemisphere source here. A future local-occlusion
                    // correction must be relative to that same angular/spectral baseline.
                }
                dynamicDelta *= segmentKm / float(count);
                return sum * (segmentKm / float(count));
            }
            #endif
            float powerHeuristic(float firstPdf, float secondPdf) {
                float first = firstPdf * firstPdf;
                float second = secondPdf * secondPdf;
                return first / max(first + second, 1.0e-30);
            }
            struct AreaDirectSplit {
                vec3 diffuse;
                vec3 specular;
                vec3 dynamicDiffuseDelta;
                vec3 dynamicSpecularDelta;
                AreaLightSample light;
                vec3 visibility;
                vec3 staticVisibility;
                bool dynamicOccluder;
                bool valid;
            };
            // RESTIR_FUNCTIONS
            AreaDirectSplit estimateAreaDirect(vec3 surfacePosition, vec3 normal, vec3 viewDirection,
                    vec3 baseColor, float roughness, float metallic, float reflectivity,
                    vec3 sampleValue, vec2 receiverEnergy, bool separateDynamic) {
                AreaDirectSplit result;
                result.diffuse = vec3(0.0);
                result.specular = vec3(0.0);
                result.dynamicDiffuseDelta = vec3(0.0);
                result.dynamicSpecularDelta = vec3(0.0);
                result.light.position = vec3(0.0);
                result.light.direction = vec3(0.0, 1.0, 0.0);
                result.light.distance = 0.0;
                result.light.pdf = 0.0;
                result.light.selectionPdf = 0.0;
                result.light.emitterIndex = LIGHT_NO_EMITTER;
                result.light.barycentric = vec2(0.0);
                result.visibility = vec3(1.0);
                result.staticVisibility = vec3(1.0);
                result.dynamicOccluder = false;
                result.valid = false;
                AreaLightSample light;
                if ((restir.controls.x & 1u) != 0u && restirDirectEligible) {
                    vec4 selected = restirDirectSample(surfacePosition, normal, viewDirection, baseColor,
                        roughness, metallic, reflectivity, receiverEnergy, restirDirectBase);
                    light = restirLight(surfacePosition, selected);
                } else {
                    light = sampleAreaLight(surfacePosition, sampleValue);
                }
                if (!(light.pdf > 0.0)) return result;
                result.light = light;
                float cosine = max(dot(normal, light.direction), 0.0);
                if (!(cosine > 0.0)) return result;
                vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric);
                if (all(equal(sourceRadiance, vec3(0.0)))) return result;
                vec3 emitterShadowOrigin = surfacePosition + normal * 0.002;
                EmitterVisibilityRay emitterRay = prepareEmitterVisibilityRay(emitterShadowOrigin, light.position);
                traceEmitterVisibility(emitterShadowOrigin, emitterRay);
                result.visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                result.dynamicOccluder = shadowDynamicOccluder != 0u;
                result.valid = true;
                vec3 staticVisibility = staticEmitterVisibility(emitterShadowOrigin,
                    emitterRay, result.dynamicOccluder && separateDynamic, result.visibility);
                result.staticVisibility = staticVisibility;
                if (!emitterTransportRequired(result.visibility, staticVisibility)) return result;
                vec3 radiance = sourceRadiance * result.visibility;
                float specularProbability = preparedSpecularSampleProbability(receiverEnergy, roughness, metallic);
                float diffuseProbability = 1.0 - specularProbability;
                float diffuseMaterialWeight = 1.0 - metallic;
                BsdfValue bsdf = evaluateBsdf(normal, viewDirection, light.direction, baseColor,
                    roughness, metallic, reflectivity, diffuseMaterialWeight,
                    diffuseProbability, specularProbability, receiverEnergy);
                // Emitter illumination is estimated exclusively by the light tree at every path
                // vertex. The BSDF-sampling strategy cannot hit Minecraft's small emissive blocks
                // often enough to earn its share of the power-heuristic weight, so applying that
                // weight here drained most of the direct emitter energy (measured misWeight ~0.26).
                float misWeight = 1.0;
                float neePdfFloor = (restir.controls.x & 1u) != 0u && restirDirectEligible ? 1e-20 : 1e-6;
                vec3 scale = radiance * cosine * misWeight / max(light.pdf, neePdfFloor);
                // evaluateBsdf already resolved both material lobes with the same energy curve.
                result.diffuse = scale * bsdf.diffuse;
                result.specular = max(scale * (bsdf.f - bsdf.diffuse), vec3(0.0));
                vec3 deltaScale = sourceRadiance * (result.visibility - staticVisibility)
                    * cosine * misWeight / max(light.pdf, neePdfFloor);
                result.dynamicDiffuseDelta = deltaScale * bsdf.diffuse;
                result.dynamicSpecularDelta = deltaScale * max(bsdf.f - bsdf.diffuse, vec3(0.0));
                return result;
            }
            """);
}
