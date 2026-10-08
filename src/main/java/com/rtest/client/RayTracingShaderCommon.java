package com.rtest.client;

final class RayTracingShaderCommon {
    static final String PBR_FUNCTIONS = """
            uint pbrReadPixel(uint offset, uint width, uint height, float u, float v) {
                if (offset == 0xffffffffu || width == 0u || height == 0u) {
                    return 0u;
                }
                float sampleU = clamp(u, 0.0, 0.99999994);
                float sampleV = clamp(v, 0.0, 0.99999994);
                // Animated companion maps are square frames stacked vertically. Keep the frame
                // aspect ratio instead of squeezing the whole strip into one sprite, and advance
                // it from the Minecraft game tick supplied in random.w.
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
            float pbrDecodeHeight(uint pixel) {
                float heightValue = float((pixel >> 24u) & 0xffu) / 255.0;
                // Iteration/Sundial reserve both alpha 0 and alpha 1 as the flat-height
                // sentinel. Only the open interval carries authored relief height.
                return heightValue <= 0.0 || heightValue >= 0.999 ? 1.0 : heightValue;
            }
            int pbrWrapTexel(int value, int size) {
                int wrapped = value % size;
                return wrapped < 0 ? wrapped + size : wrapped;
            }
            float pbrHeightWrappedTexel(uint offset, uint width, uint x, uint y) {
                return pbrDecodeHeight(pbrData.values[offset + y * width + x]);
            }
            // Prepared once per POM hit, with the original animation arithmetic order.
            vec2 pbrHeightAnimation(uint width, uint height) {
                if (height <= width || width == 0u) return vec2(0.0, 0.0);
                uint frameCount = max(height / width, 1u);
                uint frame = (floatBitsToUint(camera.random.w) / 2u) % frameCount;
                return vec2(float(frame), 1.0);
            }
            float pbrHeightPrepared(uint offset, uint width, uint height, float u, float v, vec2 animation) {
                if (offset == 0xffffffffu || width == 0u || height == 0u) return 1.0;
                if (animation.y > 0.0) {
                    v = (animation.x + clamp(v, 0.0, 0.99999994)) * float(width) / float(height);
                }
                vec2 texelPosition = fract(vec2(u, v)) * vec2(width, height) - vec2(0.5);
                ivec2 texel00 = ivec2(floor(texelPosition));
                vec2 blend = fract(texelPosition);
                uint x0 = uint(pbrWrapTexel(texel00.x, int(width)));
                uint y0 = uint(pbrWrapTexel(texel00.y, int(height)));
                uint x1 = x0 + 1u == width ? 0u : x0 + 1u;
                uint y1 = y0 + 1u == height ? 0u : y0 + 1u;
                float h00 = pbrHeightWrappedTexel(offset, width, x0, y0);
                float h10 = pbrHeightWrappedTexel(offset, width, x1, y0);
                float h01 = pbrHeightWrappedTexel(offset, width, x0, y1);
                float h11 = pbrHeightWrappedTexel(offset, width, x1, y1);
                return mix(mix(h00, h10, blend.x), mix(h01, h11, blend.x), blend.y);
            }
            vec2 pbrWrapCoord(vec2 coord) {
                return fract(coord);
            }
            vec3 pbrTangent(vec3 faceNormal, float tangentAngle, float tangentHandedness) {
                vec3 referenceTangent = normalize(abs(faceNormal.y) < 0.999
                    ? cross(faceNormal, vec3(0.0, 1.0, 0.0))
                    : cross(faceNormal, vec3(1.0, 0.0, 0.0)));
                if (abs(tangentHandedness) <= 0.5) return referenceTangent;
                vec3 referenceBitangent = cross(faceNormal, referenceTangent);
                return normalize(referenceTangent * cos(tangentAngle)
                    + referenceBitangent * sin(tangentAngle));
            }
            vec3 pbrBitangent(vec3 faceNormal, vec3 tangent, float tangentHandedness) {
                vec3 bitangent = cross(faceNormal, tangent);
                return bitangent * (tangentHandedness < -0.5 ? -1.0 : 1.0);
            }
            const int PBR_PARALLAX_STEPS = 32;
            const int PBR_PARALLAX_REFINEMENTS = 5;
            vec2 pbrParallaxUv(uint mapIndex, vec2 atlasUv, vec3 faceNormal,
                               float tangentAngle, float tangentHandedness,
                               vec3 rayDirection, bool entityMaterial) {
                uint flags = uint(max(camera.pbrParallaxSettings.w, 0.0) + 0.5);
                if ((flags & 1u) == 0u || (entityMaterial && (flags & 2u) == 0u)) return atlasUv;
                if (mapIndex == 0u || mapIndex > pbrData.values[0]) return atlasUv;
                uint info = 1u + (mapIndex - 1u) * 10u;
                uint heightOffset = pbrData.values[info];
                // High bit proves decoded height is flat for every texel/frame.
                uint widthAndFlags = pbrData.values[info + 2u];
                if ((widthAndFlags & 0x80000000u) != 0u) return atlasUv;
                uint width = widthAndFlags & 0x7fffffffu;
                uint height = pbrData.values[info + 3u];
                if (heightOffset == 0xffffffffu || width == 0u || height == 0u) return atlasUv;
                float u0 = uintBitsToFloat(pbrData.values[info + 6u]);
                float u1 = uintBitsToFloat(pbrData.values[info + 7u]);
                float v0 = uintBitsToFloat(pbrData.values[info + 8u]);
                float v1 = uintBitsToFloat(pbrData.values[info + 9u]);
                vec2 span = vec2(max(u1 - u0, 0.000001), max(v1 - v0, 0.000001));
                vec2 coord = pbrWrapCoord((atlasUv - vec2(u0, v0)) / span);
                vec2 heightAnimation = pbrHeightAnimation(width, height);
                float startHeight = pbrHeightPrepared(heightOffset, width, height, coord.x, coord.y, heightAnimation);
                if (startHeight <= 0.0 || startHeight >= 0.999) return atlasUv;
                vec3 tangent = pbrTangent(faceNormal, tangentAngle, tangentHandedness);
                vec3 bitangent = pbrBitangent(faceNormal, tangent, tangentHandedness);
                vec3 viewTangent = vec3(dot(-rayDirection, tangent), dot(-rayDirection, bitangent),
                    max(dot(-rayDirection, faceNormal), 0.0));
                if (viewTangent.z < 0.001) return atlasUv;
                vec2 parallaxDirection = viewTangent.xy / viewTangent.z;
                // Match Iteration's atlas-space correction: its quad texel ratio is
                // equivalent to span.x/span.y in the normalized sprite coordinate.
                parallaxDirection.y *= span.x / span.y;
                vec2 parallaxDelta = parallaxDirection
                    * clamp(camera.pbrParallaxSettings.x, 0.0, 4.0)
                    * 0.2 / float(PBR_PARALLAX_STEPS);
                if (all(equal(parallaxDelta, vec2(0.0)))) {
                    return vec2(u0, v0) + coord * span;
                }
                float rayDepthDelta = 1.0 / float(PBR_PARALLAX_STEPS);
                vec2 previousCoord = coord;
                vec2 currentCoord = coord;
                float previousRayDepth = 1.0;
                float currentRayDepth = 1.0;
                float previousHeight = startHeight;
                float sampledHeight = startHeight;
                bool intersectionFound = false;

                // Sundial walks from the undisplaced surface through the height field. A single
                // offset based only on the first height sample cannot represent overhang-like
                // relief transitions and makes the texture swim as the view direction changes.
                for (int stepIndex = 0; stepIndex < PBR_PARALLAX_STEPS; stepIndex++) {
                    previousCoord = currentCoord;
                    previousRayDepth = currentRayDepth;
                    previousHeight = sampledHeight;
                    currentCoord -= parallaxDelta;
                    currentRayDepth -= rayDepthDelta;
                    vec2 currentSampleCoord = pbrWrapCoord(currentCoord);
                    sampledHeight = pbrHeightPrepared(heightOffset, width, height,
                        currentSampleCoord.x, currentSampleCoord.y, heightAnimation);
                    if (sampledHeight > currentRayDepth) {
                        intersectionFound = true;
                        break;
                    }
                }
                if (!intersectionFound) return atlasUv;

                // Refine the last crossed layer, matching Sundial's refinement stage while keeping
                // every sample inside this sprite instead of leaking into an atlas neighbour.
                for (int refinement = 0; refinement < PBR_PARALLAX_REFINEMENTS; refinement++) {
                    vec2 midpointCoord = mix(previousCoord, currentCoord, 0.5);
                    float midpointRayDepth = mix(previousRayDepth, currentRayDepth, 0.5);
                    vec2 midpointSampleCoord = pbrWrapCoord(midpointCoord);
                    float midpointHeight = pbrHeightPrepared(heightOffset, width, height,
                        midpointSampleCoord.x, midpointSampleCoord.y, heightAnimation);
                    if (midpointHeight > midpointRayDepth) {
                        currentCoord = midpointCoord;
                        currentRayDepth = midpointRayDepth;
                        sampledHeight = midpointHeight;
                    } else {
                        previousCoord = midpointCoord;
                        previousRayDepth = midpointRayDepth;
                        previousHeight = midpointHeight;
                    }
                }
                float previousDelta = previousHeight - previousRayDepth;
                float currentDelta = sampledHeight - currentRayDepth;
                float intersectionWeight = clamp(-previousDelta
                    / max(currentDelta - previousDelta, 0.000001), 0.0, 1.0);
                vec2 refinedCoord = mix(previousCoord, currentCoord, intersectionWeight);
                coord = pbrWrapCoord(refinedCoord);
                return vec2(u0, v0) + coord * span;
            }
            const uint PBR_FORMAT_LAB = 0u;
            const uint PBR_FORMAT_CLASSIC = 1u;
            const uint PBR_FORMAT_BEDROCK = 2u;
            const uint PBR_FEATURE_TEXTURE_AO = 1u;
            const uint PBR_FEATURE_POROSITY = 2u;
            const uint PBR_FEATURE_PREDEFINED_METAL = 4u;
            const uint PBR_FEATURE_EMISSION = 8u;
            vec3 pbrPredefinedMetalF0(uint metalByte) {
                if (metalByte == 230u) return vec3(0.56, 0.58, 0.58);
                if (metalByte == 231u) return vec3(1.00, 0.69, 0.28);
                if (metalByte == 232u) return vec3(0.81, 0.82, 0.83);
                if (metalByte == 233u) return vec3(0.50, 0.49, 0.49);
                if (metalByte == 234u) return vec3(0.95, 0.52, 0.35);
                if (metalByte == 235u) return vec3(0.81, 0.83, 0.87);
                if (metalByte == 236u) return vec3(0.66, 0.63, 0.58);
                if (metalByte == 237u) return vec3(0.95, 0.91, 0.81);
                return vec3(0.0);
            }
            void samplePbr(
                uint mapIndex,
                vec2 atlasUv,
                inout vec3 tangentNormal,
                inout float roughness,
                inout float metallic,
                inout float reflectivity,
                inout float emission,
                out float porosity,
                out float textureAo,
                out bool hasAuthoredEmission,
                out bool hasNormal,
                out bool hasSpecular
            ) {
                hasNormal = false;
                hasSpecular = false;
                porosity = 0.0;
                textureAo = 1.0;
                hasAuthoredEmission = false;
                if (mapIndex == 0u || mapIndex > pbrData.values[0]) {
                    return;
                }
                uint packedMode = uint(max(camera.pbrSettings.x, 0.0) + 0.5);
                uint format = packedMode & 0xffu;
                uint features = packedMode >> 8u;
                uint info = 1u + (mapIndex - 1u) * 10u;
                uint normalOffset = pbrData.values[info];
                uint specularOffset = pbrData.values[info + 1u];
                uint normalWidth = pbrData.values[info + 2u] & 0x7fffffffu;
                uint normalHeight = pbrData.values[info + 3u];
                uint specularWidth = pbrData.values[info + 4u];
                uint specularHeight = pbrData.values[info + 5u];
                float u0 = uintBitsToFloat(pbrData.values[info + 6u]);
                float u1 = uintBitsToFloat(pbrData.values[info + 7u]);
                float v0 = uintBitsToFloat(pbrData.values[info + 8u]);
                float v1 = uintBitsToFloat(pbrData.values[info + 9u]);
                float u = (atlasUv.x - u0) / max(u1 - u0, 0.000001);
                float v = (atlasUv.y - v0) / max(v1 - v0, 0.000001);
                if (normalOffset != 0xffffffffu) {
                    uint pixel = pbrReadPixel(normalOffset, normalWidth, normalHeight, u, v);
                    vec2 normalXY = vec2(
                        float((pixel >> 16u) & 0xffu) / 127.5 - 1.0,
                        float((pixel >> 8u) & 0xffu) / 127.5 - 1.0);
                    float normalZ;
                    if (format == PBR_FORMAT_LAB) {
                        normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
                        if ((features & PBR_FEATURE_TEXTURE_AO) != 0u) {
                            textureAo = float(pixel & 0xffu) / 255.0;
                        }
                    } else {
                        normalZ = float(pixel & 0xffu) / 127.5 - 1.0;
                    }
                    float normalStrength = clamp(camera.pbrSettings.y, 0.0, 3.0);
                    normalXY *= normalStrength;
                    // Match CPU decoding: LabPBR reconstructs Z from the scaled XY.
                    // Strength zero must disable the map even at authored grazing normals.
                    if (format == PBR_FORMAT_LAB)
                        normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));
                    tangentNormal = normalStrength == 0.0 ? vec3(0.0, 0.0, 1.0)
                        : vec3(normalXY, normalZ);
                    float normalLength2 = dot(tangentNormal, tangentNormal);
                    tangentNormal = normalLength2 > 1.0e-12
                        && !any(isnan(tangentNormal)) && !any(isinf(tangentNormal))
                        ? tangentNormal * inversesqrt(normalLength2) : vec3(0.0, 0.0, 1.0);
                    hasNormal = true;
                }
                if (specularOffset != 0xffffffffu) {
                    uint pixel = pbrReadPixel(specularOffset, specularWidth, specularHeight, u, v);
                    uint redByte = (pixel >> 16u) & 0xffu;
                    uint greenByte = (pixel >> 8u) & 0xffu;
                    uint blueByte = pixel & 0xffu;
                    uint alphaByte = (pixel >> 24u) & 0xffu;
                    float red = float(redByte) / 255.0;
                    float green = float(greenByte) / 255.0;
                    float blue = float(blueByte) / 255.0;
                    if (format == PBR_FORMAT_BEDROCK) {
                        roughness = blue;
                        metallic = red;
                        emission = green;
                        if ((features & PBR_FEATURE_EMISSION) != 0u) {
                            hasAuthoredEmission = true;
                        }
                    } else {
                        roughness = 1.0 - red;
                        metallic = green;
                        if (format == PBR_FORMAT_LAB) {
                            if ((features & PBR_FEATURE_POROSITY) != 0u) porosity = blue;
                            if ((features & PBR_FEATURE_EMISSION) != 0u) {
                                emission = alphaByte < 255u ? float(alphaByte) / 254.0 : 0.0;
                                // The _s alpha channel is a complete per-texel mask.  Zero-valued
                                // pixels still override the material fallback so only the authored
                                // lantern panels emit, while its frame remains dark.
                                hasAuthoredEmission = true;
                            }
                        } else if ((features & PBR_FEATURE_EMISSION) != 0u) {
                            emission = blue;
                            hasAuthoredEmission = true;
                        }
                    }
                    if (format == PBR_FORMAT_LAB && (features & PBR_FEATURE_PREDEFINED_METAL) != 0u
                        && greenByte >= 230u && greenByte <= 237u) {
                        vec3 f0 = pbrPredefinedMetalF0(greenByte);
                        reflectivity = dot(f0, vec3(0.2126, 0.7152, 0.0722));
                    } else {
                        reflectivity = 0.04 + 0.96 * metallic;
                    }
                    hasSpecular = true;
                }
            }
            vec3 pbrWorldNormal(vec3 faceNormal, vec3 tangentNormal,
                                float tangentAngle, float tangentHandedness) {
                vec3 tangent = pbrTangent(faceNormal, tangentAngle, tangentHandedness);
                vec3 bitangent = pbrBitangent(faceNormal, tangent, tangentHandedness);
                return normalize(tangent * tangentNormal.x + bitangent * tangentNormal.y + faceNormal * tangentNormal.z);
            }
            vec3 pbrOrientSurfaceNormal(vec3 faceNormal, vec3 shadingNormal, vec3 rayDirection) {
                // A normal map may lean away from the observer while the geometric face
                // remains front-facing. Flipping by the mapped normal reverses its entire
                // light hemisphere between direct views and mirror views of that same face.
                return dot(rayDirection, faceNormal) < 0.0 ? shadingNormal : -shadingNormal;
            }
            """;

    static final String PLAYER_SKIN_FUNCTIONS = """
            #extension GL_EXT_nonuniform_qualifier : require
            // Shared descriptor table for player, mob, armor, cape and other living-model textures.
            layout(set = 0, binding = 17) uniform sampler2D livingEntityTextures[64];
            layout(set = 0, binding = 18) uniform sampler2D itemAtlas;
            vec4 samplePlayerSkin(vec2 uv, float slot) {
                // Item materials reuse the special-texture branch with optical.x=-1.0;
                // keeping this path shared guarantees primary and shadow alpha tests agree.
                if (slot < -0.5) {
                    return textureLod(itemAtlas, uv, 0.0);
                }
                uint index = min(uint(max(slot, 0.0) + 0.5), 63u);
                return textureLod(livingEntityTextures[nonuniformEXT(index)], uv, 0.0);
            }
            ivec2 materialCutoutTexel(vec2 uv, ivec2 size) {
                return clamp(ivec2(floor(uv * vec2(size))), ivec2(0), size - ivec2(1));
            }
            vec4 samplePlayerSkinCutout(vec2 uv, float slot) {
                // Transparent black texels must not be linearly mixed into an accepted cutout hit.
                // It creates dark row seams on low-resolution animated sprites such as fire.
                if (slot < -0.5) {
                    ivec2 size = textureSize(itemAtlas, 0);
                    return texelFetch(itemAtlas, materialCutoutTexel(uv, size), 0);
                }
                uint index = min(uint(max(slot, 0.0) + 0.5), 63u);
                ivec2 size = textureSize(livingEntityTextures[nonuniformEXT(index)], 0);
                return texelFetch(livingEntityTextures[nonuniformEXT(index)],
                    materialCutoutTexel(uv, size), 0);
            }
            """;
}
