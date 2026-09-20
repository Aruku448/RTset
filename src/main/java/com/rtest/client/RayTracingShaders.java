package com.rtest.client;

final class RayTracingShaders {
    private static final String PBR_FUNCTIONS = """
        uint pbrReadPixel(uint offset, uint width, uint height, float u, float v) {
            if (offset == 0xffffffffu || width == 0u || height == 0u) {
                return 0u;
            }
            float sampleU = clamp(u, 0.0, 0.99999994);
            float sampleV = clamp(v, 0.0, 0.99999994);
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
        float pbrHeightTexel(uint offset, uint width, uint height, int x, int y) {
            int wrappedX = pbrWrapTexel(x, int(width));
            int wrappedY = pbrWrapTexel(y, int(height));
            uint pixel = pbrData.values[offset + uint(wrappedY) * width + uint(wrappedX)];
            return pbrDecodeHeight(pixel);
        }
        float pbrHeight(uint offset, uint width, uint height, float u, float v) {
            if (offset == 0xffffffffu || width == 0u || height == 0u) return 1.0;
            vec2 texelPosition = fract(vec2(u, v)) * vec2(width, height) - vec2(0.5);
            ivec2 texel00 = ivec2(floor(texelPosition));
            vec2 blend = fract(texelPosition);
            float h00 = pbrHeightTexel(offset, width, height, texel00.x, texel00.y);
            float h10 = pbrHeightTexel(offset, width, height, texel00.x + 1, texel00.y);
            float h01 = pbrHeightTexel(offset, width, height, texel00.x, texel00.y + 1);
            float h11 = pbrHeightTexel(offset, width, height, texel00.x + 1, texel00.y + 1);
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
            uint width = pbrData.values[info + 2u];
            uint height = pbrData.values[info + 3u];
            if (heightOffset == 0xffffffffu || width == 0u || height == 0u) return atlasUv;
            float u0 = uintBitsToFloat(pbrData.values[info + 6u]);
            float u1 = uintBitsToFloat(pbrData.values[info + 7u]);
            float v0 = uintBitsToFloat(pbrData.values[info + 8u]);
            float v1 = uintBitsToFloat(pbrData.values[info + 9u]);
            vec2 span = vec2(max(u1 - u0, 0.000001), max(v1 - v0, 0.000001));
            vec2 coord = pbrWrapCoord((atlasUv - vec2(u0, v0)) / span);
            float startHeight = pbrHeight(heightOffset, width, height, coord.x, coord.y);
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
                sampledHeight = pbrHeight(heightOffset, width, height,
                    currentSampleCoord.x, currentSampleCoord.y);
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
                float midpointHeight = pbrHeight(heightOffset, width, height,
                    midpointSampleCoord.x, midpointSampleCoord.y);
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
            uint normalWidth = pbrData.values[info + 2u];
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
                tangentNormal = vec3(normalXY, normalZ);
                tangentNormal.xy *= clamp(camera.pbrSettings.y, 0.0, 3.0);
                tangentNormal = normalize(tangentNormal);
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
        """;

    private static final String PLAYER_SKIN_FUNCTIONS = """
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

    private static String joinShaderParts(String first, String second) {
        return first + second;
    }

    static final String RAYGEN_SHADER = joinShaderParts("""
        #version 460
        #extension GL_EXT_ray_tracing : require
        // Approximate black-body color for adjustable sunlight and sky tint.
        vec3 colorTemperature(float kelvin) {
            float temperature = clamp(kelvin, 1000.0, 20000.0) / 100.0;
            float red = temperature <= 66.0 ? 255.0
                : 329.698727446 * pow(temperature - 60.0, -0.1332047592);
            float green = temperature <= 66.0
                ? 99.4708025861 * log(temperature) - 161.1195681661
                : 288.1221695283 * pow(temperature - 60.0, -0.0755148492);
            float blue = temperature >= 66.0 ? 255.0
                : temperature <= 19.0 ? 0.0
                : 138.5177312231 * log(temperature - 10.0) - 305.0447927307;
            return clamp(vec3(red, green, blue) / 255.0, vec3(0.0), vec3(1.0));
        }
        layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
        layout(set = 0, binding = 1, std430) buffer Result { uint pixels[]; } result;
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        // The light tree is evaluated in raygen, so it needs the same packed PBR companion
        // textures that closest-hit uses when it resolves authored emission. Keeping this
        // binding in the active raygen module makes emissive-area sampling agree with the
        // visible surface instead of falling back to the CPU centroid scalar.
        layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
        layout(set = 0, binding = 26, std430) readonly buffer LightData { uint values[]; } lightData;
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            // x = time of day, y = rain, z = thunder, w = night factor.
            vec4 environmentState;
            // x = dynamic material start, y = slot stride, z = slot capacity.
            vec4 dynamicParameters;
            // x = format|feature mask, y = normal strength, z = emission strength, w = wetness.
            vec4 pbrSettings;
            // x = parallax depth, y = area-light emission scale, w = parallax flags.
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 6) uniform samplerCube skybox;
        layout(set = 0, binding = 7, rgba16f) uniform writeonly image2D fsrSceneColor;
        layout(set = 0, binding = 8, rg16f) uniform writeonly image2D fsrMotion;
        layout(set = 0, binding = 9, r32f) uniform writeonly image2D fsrDepth;
        layout(set = 0, binding = 10, rgba8) uniform writeonly image2D fsrReactive;
        layout(set = 0, binding = 11, rgba8) uniform writeonly image2D fsrTransparency;
        // NRD REBLUR inputs. Raygen writes the current-frame signal directly; the NRD compute
        // scheduler consumes these images before the result is sent through FSR3.
        layout(set = 0, binding = 12, rgba16f) uniform writeonly image2D nrdNoisyDiffuse;
        layout(set = 0, binding = 13, rgba16f) uniform writeonly image2D nrdNoisySpecular;
        layout(set = 0, binding = 14, rgb10_a2) uniform writeonly image2D nrdNormalRoughness;
        layout(set = 0, binding = 15, r32f) uniform writeonly image2D nrdViewZ;
        layout(set = 0, binding = 16, rgba16f) uniform writeonly image2D nrdMotion;
        layout(set = 0, binding = 20, rgba16f) uniform writeonly image2D nrdMaterial;
        layout(set = 0, binding = 21, rgba16f) uniform writeonly image2D nrdDirectDiffuse;
        layout(set = 0, binding = 22, rgba16f) uniform writeonly image2D nrdIndirectDiffuse;
        layout(set = 0, binding = 23, rgba16f) uniform writeonly image2D nrdEmission;
        // Raw primary guides consumed by the post-raygen NRD preparation pass.
        layout(set = 0, binding = 24, rgba32f) uniform writeonly image2D nrdPrimaryPosition;
        layout(set = 0, binding = 25, rgba16f) uniform writeonly image2D nrdSpecularMaterial;
        layout(set = 0, binding = 19, std430) readonly buffer DynamicMotionMetadata { vec4 values[]; } dynamicMotion;

        ivec2 materialCutoutTexel(vec2 uv, ivec2 size) {
            return clamp(ivec2(floor(uv * vec2(size))), ivec2(0), size - ivec2(1));
        }

        // PNG skybox faces are sRGB/BT.709; The RT integrator and the display pass use
        // linear Rec.2020, so decode and convert here before applying world illumination.
        // Without this step the display pass encodes the already-encoded PNG values again,
        // which shifts the sky toward a brighter/cyan result instead of preserving its color.
        float skyDecodeSrgbChannel(float encoded) {
            return encoded <= 0.04045
                ? encoded / 12.92
                : pow((encoded + 0.055) / 1.055, 2.4);
        }
        vec3 skyDecodeSrgb(vec3 encoded) {
            return vec3(
                skyDecodeSrgbChannel(encoded.r),
                skyDecodeSrgbChannel(encoded.g),
                skyDecodeSrgbChannel(encoded.b));
        }
        vec3 skySrgbToWorking(vec3 linearSrgb) {
            return vec3(
                dot(vec3(0.6274039, 0.3292830, 0.0433131), linearSrgb),
                dot(vec3(0.0690973, 0.9195404, 0.0113623), linearSrgb),
                dot(vec3(0.0163914, 0.0880133, 0.8955953), linearSrgb));
        }
        // One TLAS serves every ray. Instance masks reserve bit 7 for the first-person body:
        // primary camera rays use 0x7f and continuation/reflection/shadow rays use 0xfe.
        // This is the lightweight equivalent of separate primary/secondary TLASes.
        const uint PRIMARY_RAY_MASK = 0x7fu;
        const uint SECONDARY_RAY_MASK = 0xfeu;
        // Keep the full lighting path enabled; set true only for direct-sun diagnostics.
        const bool DIRECT_SUN_ONLY = false;
        // RGB hero wavelengths are the inexpensive spectral-transmission approximation used by
        // the path tracer. A path chooses one wavelength at its first dispersive interface;
        // masking the other channels and multiplying by three keeps the estimate unbiased while
        // allowing refraction to separate red, green and blue directions.
        const vec3 SPECTRAL_WAVELENGTHS_NM = vec3(610.0, 550.0, 450.0);
        const float SPECTRAL_ABBE_NUMBER = 55.0;
        float primeRcDispersionIor(float nd, float vd, float scale, float lambda) {
            const float lambdaC = 656.3;
            const float lambdaD = 587.6;
            const float lambdaF = 486.1;
            const float lambdaFC2 = 1.0
                / (1.0 / (lambdaF * lambdaF) - 1.0 / (lambdaC * lambdaC));
            float b = (nd - 1.0) * lambdaFC2 / max(0.1, vd) * scale;
            float a = nd - b / (lambdaD * lambdaD);
            return a + b / (lambda * lambda);
        }
        vec3 spectralHeroWeight(int channel) {
            return channel == 0 ? vec3(3.0, 0.0, 0.0)
                : (channel == 1 ? vec3(0.0, 3.0, 0.0) : vec3(0.0, 0.0, 3.0));
        }
        float materialDecodeSrgbChannel(float encoded) {
            return encoded <= 0.04045
                ? encoded / 12.92
                : pow((encoded + 0.055) / 1.055, 2.4);
        }
        vec3 materialDecodeSrgb(vec3 encoded) {
            return vec3(
                materialDecodeSrgbChannel(encoded.r),
                materialDecodeSrgbChannel(encoded.g),
                materialDecodeSrgbChannel(encoded.b));
        }
        vec3 materialLinearSrgbToWorking(vec3 color) {
            return vec3(
                dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
                dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
                dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
        }
        vec3 materialTransmissionColor(vec3 baseColor, float opacity, float ior) {
            if (ior < 1.4) {
                // Water keeps its authored spectral ratio, matching Prime's Minecraft adapter.
                return mix(vec3(1.0), baseColor, opacity);
            }
            // Prime normalizes stained-glass RGB by its peak before applying coverage; otherwise
            // a dark display-encoded texel becomes an almost black filter instead of colored light.
            float peak = max(baseColor.r, max(baseColor.g, baseColor.b));
            vec3 filterColor = peak > 1.0e-6 ? baseColor / peak : vec3(1.0);
            float tintWeight = mix(0.75, 1.0, opacity);
            return mix(vec3(1.0), filterColor, tintWeight);
        }
        struct PathPayload {
            vec4 position;
            vec4 normal;
            vec4 baseColorRoughness;
            vec4 material;
            // x = IOR; yzw = per-channel Beer-Lambert absorption coefficients.
            vec4 opticalLighting;
            vec4 localPosition;
            uint dynamicSlot;
            uint emitterIndex;
        };
        // One traceRayEXT call carries exactly one payload object. Keep all primary
        // hit data in that object instead of assigning unrelated payload locations.
        layout(location = 0) rayPayloadEXT PathPayload pathPayload;
        layout(location = 1) rayPayloadEXT vec3 shadowTransmittance;
        // Set by the shadow any-hit stage when an accepted blocker belongs to the dynamic TLAS
        // range. The composite uses this to bypass temporal filtering for moving entity shadows.
        layout(location = 2) rayPayloadEXT uint shadowDynamicOccluder;
        #define pathPosition pathPayload.position
        #define pathNormal pathPayload.normal
        #define pathBaseColorRoughness pathPayload.baseColorRoughness
        #define pathMaterial pathPayload.material
        #define pathOpticalLighting pathPayload.opticalLighting
        #define pathLocalPosition pathPayload.localPosition
        #define pathDynamicSlot pathPayload.dynamicSlot
        #define pathEmitterIndex pathPayload.emitterIndex
        uint pcgHash(uint value) {
            uint state = value * 747796405u + 2891336453u;
            uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
            return (word >> 22u) ^ word;
        }
        float randomFloat(inout uint seed) {
            seed = pcgHash(seed);
            return float(seed) / 4294967296.0;
        }
        const uint PRIME_SAMPLE_EFFECT_DIRECT_SUN = 2u;
        const uint PRIME_SAMPLE_EFFECT_SCATTER_BSDF = 3u;
        const uint PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE = 4u;
        const uint PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT = 5u;
        const uint PRIME_SOBOL_INDEX_MASK = 0xffff0000u;
        const float PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0 / 4294967808.0;
        const float PRIME_UINT24_TO_FLOAT_SCALE = 1.0 / 16777216.0;
        struct PrimeSampleBase {
            uvec2 pixel;
            uint sampleIndex;
            uint sampleEpoch;
            uint vertexIndex;
            uint pathIndex;
        };
        const uint PRIME_SOBOL_BURLEY_TABLE[4][32] = uint[4][32](
            uint[32](
                0x00000001u, 0x00000002u, 0x00000004u, 0x00000008u,
                0x00000010u, 0x00000020u, 0x00000040u, 0x00000080u,
                0x00000100u, 0x00000200u, 0x00000400u, 0x00000800u,
                0x00001000u, 0x00002000u, 0x00004000u, 0x00008000u,
                0x00010000u, 0x00020000u, 0x00040000u, 0x00080000u,
                0x00100000u, 0x00200000u, 0x00400000u, 0x00800000u,
                0x01000000u, 0x02000000u, 0x04000000u, 0x08000000u,
                0x10000000u, 0x20000000u, 0x40000000u, 0x80000000u),
            uint[32](
                0x00000001u, 0x00000003u, 0x00000005u, 0x0000000fu,
                0x00000011u, 0x00000033u, 0x00000055u, 0x000000ffu,
                0x00000101u, 0x00000303u, 0x00000505u, 0x00000f0fu,
                0x00001111u, 0x00003333u, 0x00005555u, 0x0000ffffu,
                0x00010001u, 0x00030003u, 0x00050005u, 0x000f000fu,
                0x00110011u, 0x00330033u, 0x00550055u, 0x00ff00ffu,
                0x01010101u, 0x03030303u, 0x05050505u, 0x0f0f0f0fu,
                0x11111111u, 0x33333333u, 0x55555555u, 0xffffffffu),
            uint[32](
                0x00000001u, 0x00000003u, 0x00000006u, 0x00000009u,
                0x00000017u, 0x0000003au, 0x00000071u, 0x000000a3u,
                0x00000116u, 0x00000339u, 0x00000677u, 0x000009aau,
                0x00001601u, 0x00003903u, 0x00007706u, 0x0000aa09u,
                0x00010117u, 0x0003033au, 0x00060671u, 0x000909a3u,
                0x00171616u, 0x003a3939u, 0x00717777u, 0x00a3aaaau,
                0x01170001u, 0x033a0003u, 0x06710006u, 0x09a30009u,
                0x16160017u, 0x3939003au, 0x77770071u, 0xaaaa00a3u),
            uint[32](
                0x00000001u, 0x00000003u, 0x00000004u, 0x0000000au,
                0x0000001fu, 0x0000002eu, 0x00000045u, 0x000000c9u,
                0x0000011bu, 0x000002a4u, 0x0000079au, 0x00000b67u,
                0x0000101eu, 0x0000302du, 0x00004041u, 0x0000a0c3u,
                0x0001f104u, 0x0002e28au, 0x000457dfu, 0x000c9baeu,
                0x0011a105u, 0x002a7289u, 0x0079e7dbu, 0x00b6dba4u,
                0x0100011au, 0x030002a7u, 0x0400079eu, 0x0a000b6du,
                0x1f001001u, 0x2e003003u, 0x45004004u, 0xc900a00au));
        uint primeHash32(uint value) {
            value ^= value >> 16u;
            value *= 0x21f0aaadu;
            value ^= value >> 15u;
            value *= 0xf35a2d97u;
            return value ^ (value >> 15u);
        }
        uint primeHighQualityHash(uint value) {
            value ^= value >> 16u;
            value *= 0x21f0aaadu;
            value ^= value >> 15u;
            value *= 0xd35a2d97u;
            value ^= value >> 15u;
            return value ^ 0xe6fe3bebu;
        }
        uint primeHashCombine(uint seed, uint value) {
            return seed ^ (primeHash32(value) + 0x9e3779b9u + (seed << 6u) + (seed >> 2u));
        }
        uint primeSampleBaseSeed(PrimeSampleBase base) {
            uint seed = primeHash32(base.pixel.x);
            seed = primeHashCombine(seed, base.pixel.y);
            seed = primeHashCombine(seed, base.sampleEpoch);
            seed = primeHashCombine(seed, base.pathIndex);
            return primeHashCombine(seed, base.vertexIndex);
        }
        uint primeEffectSeed(PrimeSampleBase base, uint effect) {
            return primeHashCombine(primeSampleBaseSeed(base), effect);
        }
        uint primeReversedBitOwen(uint value, uint seed) {
            value ^= value * 0x3d20adeau;
            value += seed;
            value *= (seed >> 16u) | 1u;
            value ^= value * 0x05526c56u;
            value ^= value * 0x53a22864u;
            return value;
        }
        float primeSobolBurley(uint reversedBitIndex, uint dimension, uint seed) {
            uint result = 0u;
            if (dimension == 0u) {
                result = bitfieldReverse(reversedBitIndex);
            } else {
                uint index = reversedBitIndex;
                uint tableIndex = 0u;
                while (index != 0u) {
                    uint leadingZeroes = uint(31 - findMSB(index));
                    result ^= PRIME_SOBOL_BURLEY_TABLE[dimension][tableIndex + leadingZeroes];
                    tableIndex += leadingZeroes + 1u;
                    index <<= leadingZeroes;
                    index <<= 1u;
                }
            }
            uint scrambled = primeReversedBitOwen(result, seed);
            return float(bitfieldReverse(scrambled)) * PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE;
        }
        float primeSobolSample1D(PrimeSampleBase base, uint effect, uint dimension) {
            uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimension);
            uint shuffledIndex = primeReversedBitOwen(bitfieldReverse(base.sampleIndex),
                mixedSeed ^ 0xbff95bfeu) & PRIME_SOBOL_INDEX_MASK;
            return primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0x635c77bdu);
        }
        vec2 primeSobolSample2D(PrimeSampleBase base, uint effect, uint dimensionSet) {
            uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
            uint shuffledIndex = primeReversedBitOwen(bitfieldReverse(base.sampleIndex),
                mixedSeed ^ 0xf8ade99au) & PRIME_SOBOL_INDEX_MASK;
            return vec2(
                primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0xe0aaaf76u),
                primeSobolBurley(shuffledIndex, 1u, mixedSeed ^ 0x94964d4eu));
        }
        vec3 primeSobolSample3D(PrimeSampleBase base, uint effect, uint dimensionSet) {
            uint mixedSeed = primeEffectSeed(base, effect) ^ primeHighQualityHash(dimensionSet);
            uint shuffledIndex = primeReversedBitOwen(bitfieldReverse(base.sampleIndex),
                mixedSeed ^ 0xcaa726acu) & PRIME_SOBOL_INDEX_MASK;
            return vec3(
                primeSobolBurley(shuffledIndex, 0u, mixedSeed ^ 0x9e78e391u),
                primeSobolBurley(shuffledIndex, 1u, mixedSeed ^ 0x67c33241u),
                primeSobolBurley(shuffledIndex, 2u, mixedSeed ^ 0x78c395c5u));
        }
        PrimeSampleBase primeMakeSampleBase(uvec2 pixel, uint sampleIndex,
                uint sampleEpoch, uint vertexIndex, uint pathIndex) {
            PrimeSampleBase result;
            result.pixel = pixel;
            result.sampleIndex = sampleIndex;
            result.sampleEpoch = sampleEpoch;
            result.vertexIndex = vertexIndex;
            result.pathIndex = pathIndex;
            return result;
        }
        vec3 sampleCosineHemisphere(vec3 normal, vec2 sampleValue) {
            float phi = 6.28318530718 * sampleValue.x;
            float cosTheta = sqrt(1.0 - sampleValue.y);
            float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
            vec3 tangent = normalize(abs(normal.y) < 0.999
                ? cross(normal, vec3(0.0, 1.0, 0.0))
                : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta)
                + bitangent * (sin(phi) * sinTheta) + normal * cosTheta);
        }
        vec3 sampleCosineHemisphere(vec3 normal, inout uint seed) {
            float phi = 6.28318530718 * randomFloat(seed);
            float cosTheta = sqrt(1.0 - randomFloat(seed));
            float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
            vec3 tangent = normalize(abs(normal.y) < 0.999
                ? cross(normal, vec3(0.0, 1.0, 0.0))
                : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta)
                + bitangent * (sin(phi) * sinTheta) + normal * cosTheta);
        }
        const float BSDF_PI = 3.14159265359;
        // The sun is sampled uniformly in solid angle, matching Prime's distant-disk
        // sample. Keep its authored intensity as the existing dimensionless multiplier:
        // the disk sample is an average of the same direct-light integrand, not a new
        // solid-angle radiance scale.
        const float SUN_ANGULAR_RADIUS_RADIANS = 0.00471;
        const uint SUN_SAMPLE_EFFECT = 0x53554e31u;
        const uint AREA_SAMPLE_EFFECT = 0x41524541u;
        float sunSolidAngle() {
            return 2.0 * BSDF_PI * (1.0 - cos(SUN_ANGULAR_RADIUS_RADIANS));
        }
        bool sunDiskHit(vec3 rayDirection, vec3 sunDirection) {
            float sunLengthSquared = dot(sunDirection, sunDirection);
            return sunLengthSquared > 1.0e-8
                && dot(rayDirection, sunDirection * inversesqrt(sunLengthSquared))
                    >= cos(SUN_ANGULAR_RADIUS_RADIANS);
        }
        vec3 sampleSunDirection(vec3 sunDirection, vec2 sampleValue) {
            vec3 center = normalize(sunDirection);
            float phi = 6.28318530718 * sampleValue.y;
            float cosTheta = mix(cos(SUN_ANGULAR_RADIUS_RADIANS), 1.0, sampleValue.x);
            float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
            vec3 tangent = normalize(abs(center.y) < 0.999
                ? cross(center, vec3(0.0, 1.0, 0.0))
                : cross(center, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(center, tangent);
            return normalize(tangent * (cos(phi) * sinTheta)
                + bitangent * (sin(phi) * sinTheta) + center * cosTheta);
        }
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
                if (sunValid && daylight > 1.0e-4) {
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
                vec3 scattering = strength * (nightAmbientScattering
                    + solarScattering * daylight * volumeSunVisibility)
                    * ATMOSPHERE_VISIBLE_SCATTER_SCALE
                    * weatherVisibility;
                inscatter += transmittance * scattering * segmentIntegral;
                transmittance *= stepTransmittance;
            }
        }
        struct BsdfValue { vec3 f; float pdf; };
        float ggxD(float nDotH, float alpha) {
            float a2 = alpha * alpha;
            float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
            return a2 / max(BSDF_PI * d * d, 1.0e-7);
        }
        float ggxG1(float nDotV, float alpha) {
            float a2 = alpha * alpha;
            return 2.0 * nDotV / max(nDotV + sqrt(a2 + (1.0 - a2) * nDotV * nDotV), 1.0e-6);
        }
        vec3 schlickFresnel(vec3 f0, float vDotH) {
            float x = pow(clamp(1.0 - vDotH, 0.0, 1.0), 5.0);
            return f0 + (1.0 - f0) * x;
        }
        // Prime's default closure restores the energy lost by single-scattering GGX. Keep the
        // calibrated table and its alpha fit in the RT shader so rough PBR surfaces use the same
        // directional-energy partition instead of a second ad-hoc normalization heuristic.
        const float PRIME_BSDF_EPSILON = 1.0e-6;
        const float PRIME_DEFAULT_GGX_ALPHA = 0.64;
        const vec2 PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[32] = vec2[](
            vec2(0.105050949, 0.141424098), vec2(0.101618740, 0.177912561),
            vec2(0.092052501, 0.255356359), vec2(0.082015169, 0.326194899),
            vec2(0.075255580, 0.385283190), vec2(0.069562661, 0.435654352),
            vec2(0.065026574, 0.481349955), vec2(0.060138182, 0.516639700),
            vec2(0.055822648, 0.546962264), vec2(0.052415524, 0.571843207),
            vec2(0.048774748, 0.594311533), vec2(0.046079235, 0.613289194),
            vec2(0.043347252, 0.628604169), vec2(0.041271370, 0.642250667),
            vec2(0.038961432, 0.654930494), vec2(0.036981937, 0.664647269),
            vec2(0.035516171, 0.674041851), vec2(0.033806834, 0.681722658),
            vec2(0.032365771, 0.688765066), vec2(0.031207238, 0.694330635),
            vec2(0.029979644, 0.699656529), vec2(0.028958354, 0.703829794),
            vec2(0.027843981, 0.707299607), vec2(0.027112505, 0.710047934),
            vec2(0.026286324, 0.712231314), vec2(0.025693271, 0.713452229),
            vec2(0.025029263, 0.713788850), vec2(0.024538412, 0.713297400),
            vec2(0.024024045, 0.711565628), vec2(0.023714662, 0.708079364),
            vec2(0.023220258, 0.701350782), vec2(0.022881333, 0.677331905));
        float primeDefaultReflectiveDirectionalEnergyFit(float cosineView, float ggxAlpha) {
            float x = clamp(cosineView, 0.0, 1.0);
            float y = clamp(ggxAlpha, 0.0, 1.0);
            float x2 = x * x;
            float y2 = y * y;
            vec4 fit = vec4(0.1003, 0.9345, 1.0, 1.0)
                + vec4(-0.6303, -2.323, -1.765, 0.2281) * x
                + vec4(9.748, 2.229, 8.263, 15.94) * y
                + vec4(-2.038, -3.748, 11.53, -55.83) * x * y
                + vec4(29.34, 1.424, 28.96, 13.08) * x2
                + vec4(-8.245, -0.7684, -7.507, 41.26) * y2
                + vec4(-26.44, 1.436, -36.11, 54.9) * x2 * y
                + vec4(19.99, 0.2913, 15.86, 300.2) * x * y2
                + vec4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
            vec2 coefficients = clamp(fit.xy / fit.zw, 0.0, 1.0);
            return 0.04 * coefficients.x + coefficients.y;
        }
        vec2 primeDefaultGgxDirectionalEnergy(float cosineView, float ggxAlpha) {
            float coordinate = clamp(cosineView, 0.0, 1.0) * 31.0;
            int lowerIndex = int(floor(coordinate));
            int upperIndex = min(lowerIndex + 1, 31);
            vec2 calibrated = mix(PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[lowerIndex],
                PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[upperIndex], coordinate - float(lowerIndex));
            float reflectionDelta = primeDefaultReflectiveDirectionalEnergyFit(cosineView, ggxAlpha)
                - primeDefaultReflectiveDirectionalEnergyFit(cosineView, PRIME_DEFAULT_GGX_ALPHA);
            float totalResolvedEnergy = calibrated.x + calibrated.y;
            float reflectedEnergy = clamp(calibrated.x + reflectionDelta, 0.0, totalResolvedEnergy);
            return vec2(reflectedEnergy, totalResolvedEnergy - reflectedEnergy);
        }
        float primeDefaultSpecularSampleProbability(vec3 baseColor, vec3 viewDirection,
                vec3 normal, float roughness, float metallic, float reflectivity) {
            if (metallic >= 0.999 || roughness == 0.0) return 1.0;
            float alpha = max(roughness * roughness, 0.025);
            vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(
                max(dot(normal, viewDirection), 0.0), alpha);
            return clamp(directionalEnergy.x
                / max(directionalEnergy.x + directionalEnergy.y, PRIME_BSDF_EPSILON), 0.05, 0.95);
        }
        float primeDefaultDiffuseEnergy(vec3 normal, vec3 viewDirection,
                float roughness, float metallic) {
            if (metallic >= 0.999 || roughness == 0.0) return 1.0;
            float alpha = max(roughness * roughness, 0.025);
            vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(
                max(dot(normal, viewDirection), 0.0), alpha);
            return directionalEnergy.y / max(directionalEnergy.x + directionalEnergy.y,
                PRIME_BSDF_EPSILON);
        }
        BsdfValue evaluateBsdf(vec3 normal, vec3 wi, vec3 wo, vec3 baseColor,
                float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,
                float diffuseSamplingProbability, float specularSamplingProbability) {
            BsdfValue result;
            result.f = vec3(0.0);
            result.pdf = 0.0;
            float nDotI = max(dot(normal, wi), 0.0);
            float nDotO = max(dot(normal, wo), 0.0);
            if (nDotI <= 0.0 || nDotO <= 0.0) return result;
            vec3 h = normalize(wi + wo);
            float nDotH = max(dot(normal, h), 0.0);
            float iDotH = max(dot(wi, h), 1.0e-6);
            float alpha = max(roughness * roughness, 0.025);
            vec3 f0 = mix(vec3(reflectivity), baseColor, metallic);
            vec3 F = schlickFresnel(f0, iDotH);
            float specular = ggxD(nDotH, alpha) * ggxG1(nDotI, alpha) * ggxG1(nDotO, alpha)
                / max(4.0 * nDotI * nDotO, 1.0e-6);
            float specPdf = ggxD(nDotH, alpha) * nDotH / max(4.0 * iDotH, 1.0e-6);
            // A perfect mirror is a delta distribution, not a broadened GGX lobe.
            // Its energy is sampled explicitly by the continuation branch, never by NEE.
            if (roughness == 0.0) {
                specular = 0.0;
                specPdf = 0.0;
            }
            float diffuseEnergy = primeDefaultDiffuseEnergy(normal, wi, roughness, metallic);
            float specularEnergy = 1.0;
            if (metallic < 0.999 && roughness > 0.0) {
                vec2 directionalEnergy = primeDefaultGgxDirectionalEnergy(nDotI, alpha);
                float resolvedEnergy = max(directionalEnergy.x + directionalEnergy.y,
                    PRIME_BSDF_EPSILON);
                specularEnergy = 1.0 / resolvedEnergy;
            }
            result.f = diffuseMaterialWeight * baseColor * (vec3(1.0) - F)
                * diffuseEnergy / BSDF_PI + F * specular * specularEnergy;
            result.pdf = diffuseSamplingProbability * nDotO / BSDF_PI + specularSamplingProbability * specPdf;
            return result;
        }
        vec3 sampleGgx(vec3 normal, vec3 wi, float roughness, inout uint seed) {
            float alpha = max(roughness * roughness, 0.025);
            float phi = 6.28318530718 * randomFloat(seed);
            float u = randomFloat(seed);
            float cosTheta = sqrt((1.0 - u) / (1.0 + (alpha * alpha - 1.0) * u));
            float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
            vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0))
                : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta) + bitangent * (sin(phi) * sinTheta)
                + normal * cosTheta);
        }
        vec3 sampleGgx(vec3 normal, vec3 wi, float roughness, vec2 sampleValue) {
            float alpha = max(roughness * roughness, 0.025);
            float phi = 6.28318530718 * sampleValue.x;
            float u = sampleValue.y;
            float cosTheta = sqrt((1.0 - u) / (1.0 + (alpha * alpha - 1.0) * u));
            float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 0.0));
            vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0))
                : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta)
                + bitangent * (sin(phi) * sinTheta) + normal * cosTheta);
        }
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
            return sum > 0.0 ? leftScore / sum : -1.0;
        }
        uint emitterPbrReadPixel(uint offset, uint width, uint height, float u, float v) {
            if (offset == 0xffffffffu || width == 0u || height == 0u) return 0u;
            float sampleU = clamp(u, 0.0, 0.99999994);
            float sampleV = clamp(v, 0.0, 0.99999994);
            uint x = min(uint(sampleU * float(width)), width - 1u);
            uint y = min(uint(sampleV * float(height)), height - 1u);
            return pbrData.values[offset + y * width + x];
        }
        float evaluateEmitterEmission(float fallbackEmission, uint mapIndex, vec2 atlasUv) {
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
            uint packedMode = uint(max(camera.pbrSettings.x, 0.0) + 0.5);
            uint format = packedMode & 0xffu;
            uint features = packedMode >> 8u;
            if ((features & 8u) == 0u) return fallbackEmission;
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
                max(surface.z, 0.0), uint(max(lighting.w, 0.0) + 0.5), uv);
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
                float branch = lightBranchProbability(node, sibling, point);
                if (!(branch >= 0.0)) return 0.0;
                pdf *= branch;
                node = parent;
            }
            return 0.0;
        }
        vec3 evaluateHitEmitter(vec3 point, vec3 rayOrigin, vec3 rayDirection,
                uint emitterIndex, out float pdf) {
            pdf = 0.0;
            if (emitterIndex == LIGHT_NO_EMITTER || emitterIndex >= lightData.values[1]) return vec3(0.0);
            uint base = lightData.values[4] + emitterIndex * LIGHT_EMITTER_WORDS;
            vec3 corner = vec3(uintBitsToFloat(lightData.values[base]), uintBitsToFloat(lightData.values[base + 1u]), uintBitsToFloat(lightData.values[base + 2u]));
            vec3 edgeOne = vec3(uintBitsToFloat(lightData.values[base + 4u]), uintBitsToFloat(lightData.values[base + 5u]), uintBitsToFloat(lightData.values[base + 6u]));
            vec3 edgeTwo = vec3(uintBitsToFloat(lightData.values[base + 8u]), uintBitsToFloat(lightData.values[base + 9u]), uintBitsToFloat(lightData.values[base + 10u]));
            vec3 relative = point - corner;
            float firstDot = dot(edgeOne, edgeOne);
            float crossDot = dot(edgeOne, edgeTwo);
            float secondDot = dot(edgeTwo, edgeTwo);
            float denominator = firstDot * secondDot - crossDot * crossDot;
            if (!(abs(denominator) > 1.0e-12)) return vec3(0.0);
            vec2 barycentric = vec2(
                (secondDot * dot(relative, edgeOne) - crossDot * dot(relative, edgeTwo)) / denominator,
                (firstDot * dot(relative, edgeTwo) - crossDot * dot(relative, edgeOne)) / denominator);
            float area = uintBitsToFloat(lightData.values[base + 3u]);
            vec3 normal = vec3(uintBitsToFloat(lightData.values[base + 12u]), uintBitsToFloat(lightData.values[base + 13u]), uintBitsToFloat(lightData.values[base + 14u]));
            float lightCosine = max(dot(normal, -rayDirection), 0.0);
            vec3 delta = point - rayOrigin;
            float distanceSquared = dot(delta, delta);
            float areaPdf = emitterSelectionPdf(rayOrigin, emitterIndex) / max(area, 1.0e-6);
            pdf = areaPdf * distanceSquared / max(lightCosine, 1.0e-6);
            return lightCosine > 0.0 && pdf > 0.0 ? evaluateEmitter(emitterIndex, barycentric) : vec3(0.0);
        }
        AreaLightSample sampleAreaLight(vec3 surfacePosition, vec3 sampleValue) {
            AreaLightSample result;
            result.position = vec3(0.0);
            result.direction = vec3(0.0, 1.0, 0.0);
            result.distance = 0.0;
            result.pdf = 0.0;
            result.emitterIndex = LIGHT_NO_EMITTER;
            result.barycentric = vec2(0.0);
            float treePdf;
            uint emitterIndex = pickLightLeaf(surfacePosition, sampleValue.x, treePdf);
            if (emitterIndex == LIGHT_NO_EMITTER) return result;
            uint base = lightData.values[4] + emitterIndex * LIGHT_EMITTER_WORDS;
            float squareRoot = sqrt(sampleValue.y);
            vec2 barycentric = vec2(squareRoot * (1.0 - sampleValue.z), squareRoot * sampleValue.z);
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
            if (!(lightCosine > 0.0) || !(treePdf > 0.0)) return result;
            result.position = lightPosition;
            result.direction = direction;
            result.distance = max(distance - 0.002, 0.0);
            result.pdf = treePdf * distanceSquared / (area * lightCosine);
            result.emitterIndex = emitterIndex;
            result.barycentric = barycentric;
            return result;
        }
        vec3 sampleVolumeEmitter(vec3 volumePosition, vec3 viewRay,
                AreaLightSample light, vec3 visibility) {
            if (!(light.pdf > 0.0)) return vec3(0.0);
            // Reuse the primary surface's sampled emitter and its RGB visibility. The volume
            // contribution must not launch a second shadow path from a different point: that
            // was the source of the visible offset between emissive fog and RT shadows.
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
            float volumePdf = emitterSelectionPdf(volumePosition, light.emitterIndex)
                * volumeDistanceSquared / max(emitterArea * emitterCosine, 1.0e-6);
            if (!(volumePdf > 0.0)) return vec3(0.0);
            vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric)
                * visibility;
            float phase = atmosphereAerosolPhase(dot(viewRay, volumeDirection));
            return sourceRadiance * phase / volumePdf;
        }
        float powerHeuristic(float firstPdf, float secondPdf) {
            float first = firstPdf * firstPdf;
            float second = secondPdf * secondPdf;
            return first / max(first + second, 1.0e-30);
        }
        struct AreaDirectSplit {
            vec3 diffuse;
            vec3 specular;
            AreaLightSample light;
            vec3 visibility;
            bool dynamicOccluder;
            bool valid;
        };
        AreaDirectSplit estimateAreaDirect(vec3 surfacePosition, vec3 normal, vec3 viewDirection,
                vec3 baseColor, float roughness, float metallic, float reflectivity,
                vec3 sampleValue) {
            AreaDirectSplit result;
            result.diffuse = vec3(0.0);
            result.specular = vec3(0.0);
            result.light.position = vec3(0.0);
            result.light.direction = vec3(0.0, 1.0, 0.0);
            result.light.distance = 0.0;
            result.light.pdf = 0.0;
            result.light.emitterIndex = LIGHT_NO_EMITTER;
            result.light.barycentric = vec2(0.0);
            result.visibility = vec3(1.0);
            result.dynamicOccluder = false;
            result.valid = false;
            AreaLightSample light = sampleAreaLight(surfacePosition, sampleValue);
            if (!(light.pdf > 0.0)) return result;
            result.light = light;
            float cosine = max(dot(normal, light.direction), 0.0);
            if (!(cosine > 0.0)) return result;
            shadowTransmittance = vec3(1.0);
            shadowDynamicOccluder = 0u;
            traceRayEXT(topLevelAS,
                gl_RayFlagsTerminateOnFirstHitEXT,
                SECONDARY_RAY_MASK, 1, 1, 1,
                surfacePosition + normal * 0.002, 0.001,
                light.direction, light.distance, 1);
            result.visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
            result.dynamicOccluder = shadowDynamicOccluder != 0u;
            result.valid = true;
            vec3 radiance = evaluateEmitter(light.emitterIndex, light.barycentric)
                * result.visibility;
            if (all(lessThanEqual(radiance, vec3(0.0)))) return result;
            float specularProbability = primeDefaultSpecularSampleProbability(
                baseColor, viewDirection, normal, roughness, metallic, reflectivity);
            float diffuseProbability = 1.0 - specularProbability;
            float diffuseMaterialWeight = 1.0 - metallic;
            BsdfValue bsdf = evaluateBsdf(normal, viewDirection, light.direction, baseColor,
                roughness, metallic, reflectivity, diffuseMaterialWeight,
                diffuseProbability, specularProbability);
            // Emitter illumination is estimated exclusively by the light tree at every path
            // vertex. The BSDF-sampling strategy cannot hit Minecraft's small emissive blocks
            // often enough to earn its share of the power-heuristic weight, so applying that
            // weight here drained most of the direct emitter energy (measured misWeight ~0.26).
            float misWeight = 1.0;
            vec3 scale = radiance * cosine * misWeight / max(light.pdf, 1.0e-6);
            vec3 halfVector = normalize(viewDirection + light.direction);
            vec3 fresnel = schlickFresnel(
                mix(vec3(reflectivity), baseColor, metallic),
                max(dot(viewDirection, halfVector), 1.0e-6));
            float diffuseEnergy = primeDefaultDiffuseEnergy(
                normal, viewDirection, roughness, metallic);
            vec3 diffuse = diffuseMaterialWeight * baseColor * (vec3(1.0) - fresnel)
                * diffuseEnergy / BSDF_PI;
            result.diffuse = scale * diffuse;
            result.specular = max(scale * (bsdf.f - diffuse), vec3(0.0));
            return result;
        }
        void main() {
            uint pixelIndex = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
            vec2 jitteredPixel = vec2(gl_LaunchIDEXT.xy) + vec2(0.5) + camera.jitter.xy;
            vec2 pixel = jitteredPixel / vec2(gl_LaunchSizeEXT.xy);
            vec2 ndc = pixel * 2.0 - 1.0;
            vec3 rayDirection = normalize(
                camera.forward.xyz
                    + camera.right.xyz * (ndc.x * camera.parameters.x * camera.parameters.y)
                    + camera.up.xyz * (ndc.y * camera.parameters.x)
            );
            vec3 rayOrigin = camera.origin.xyz;
            vec3 throughput = vec3(1.0);
            // A single active medium covers the closed vanilla glass/water surfaces. Nested
            // media remain a bounded approximation, but each segment gets physical attenuation.
            vec3 mediumAbsorption = vec3(0.0);
            bool insideMedium = false;
            vec3 radiance = vec3(0.0);
            vec3 diffuseRadiance = vec3(0.0);
            vec3 specularRadiance = vec3(0.0);
            vec3 directDiffuseRadiance = vec3(0.0);
            vec3 directSpecularRadiance = vec3(0.0);
            // Sun NEE is a stable analytic layer and remains unfiltered.  Area-light NEE is
            // sampled from the light tree, so keep its diffuse/specular parts in NRD's noisy
            // histories instead of bypassing temporal reconstruction.
            vec3 areaDirectDiffuseRadiance = vec3(0.0);
            vec3 areaDirectSpecularRadiance = vec3(0.0);
            vec3 indirectDiffuseRadiance = vec3(0.0);
            vec3 emissionRadiance = vec3(0.0);
            // Diagnostic-only accumulator: the primary surface area-light (light tree) NEE term.
            vec3 areaLightRadiance = vec3(0.0);
            // Primary transmission is an indirect delta/specular signal. Keep it in a dedicated
            // accumulator until the AOV split, then feed it through NRD's specular history so
            // stained glass is denoised without being mixed into the diffuse history.
            vec3 transmissionRadiance = vec3(0.0);
            // -1 means the path is still RGB. Once a dispersive interface is sampled, the
            // selected hero channel is kept through the medium and its exit interface.
            int spectralChannel = -1;
            bool spectralMasked = false;
            float mediumIor = 1.0;
            vec3 primaryPosition = vec3(0.0);
            vec3 primaryBaseColor = vec3(0.0);
            vec4 primaryLocalPosition = vec4(0.0);
            uint primaryDynamicSlot = 0xffffffffu;
            vec3 primaryNormal = vec3(0.0, 0.0, 1.0);
            AreaLightSample primaryAreaLight;
            vec3 primaryAreaVisibility = vec3(1.0);
            bool primaryAreaLightValid = false;
            bool primaryDynamicShadow = false;
            float primaryRoughness = 0.8;
            vec3 primaryRayDirection = rayDirection;
            vec4 primaryMaterial = vec4(0.0);
            bool primaryHit = false;
            bool primarySpecularPath = false;
            bool primaryTransmissionPath = false;
            // The analytic sun disk is part of the environment miss, but its position changes
            // with the game-clock sun direction. FSR history has no geometry motion vector for
            // this infinitesimal source, so mark its pixels reactive below to prevent a previous
            // frame's cloud texel from covering the current disk.
            bool skySunDiskHit = false;
            const float nrdHitDistanceScale = 64.0;
            float diffuseHitDistance = 0.0;
            float specularHitDistance = 0.0;
            int giBounces = clamp(int(camera.parameters.w + 0.5), 1, 4);
            int maxPathSegments = DIRECT_SUN_ONLY ? 1 : 1 + giBounces;
            float previousBsdfPdf = 1.0;
            // A delta lobe (perfect mirror or delta transmission) has zero probability of being
            // produced by light sampling, so its environment-miss MIS weight must be exactly 1.
            // Treating it as a continuous lobe let the power heuristic cancel most of the sun.
            bool previousWasDelta = false;
            // Advance the Sobol sequence with the RT frame. Keeping this at zero repeats the
            // first-bounce direction and light samples every frame, preventing temporal NRD
            // accumulation from increasing the effective first-bounce sample count.
            PrimeSampleBase sampleBase = primeMakeSampleBase(
                gl_LaunchIDEXT.xy, floatBitsToUint(camera.random.x),
                floatBitsToUint(camera.random.y), 0u, 0u);
        """, """
            for (int bounce = 0; bounce < 5; bounce++) {
                sampleBase.vertexIndex = uint(bounce);
                // Reserve a disjoint temporal Sobol lane for every continuation bounce.
                sampleBase.sampleIndex = floatBitsToUint(camera.random.x) * 4u + uint(bounce);
                pathPosition = vec4(0.0);
                pathLocalPosition = vec4(0.0);
                pathDynamicSlot = 0xffffffffu;
                pathEmitterIndex = 0xffffffffu;
                pathNormal = vec4(0.0);
                pathBaseColorRoughness = vec4(0.0);
                pathMaterial = vec4(0.0);
                pathOpticalLighting = vec4(0.0);
                traceRayEXT(
                    topLevelAS,
                    0,
                    (bounce == 0 ? PRIMARY_RAY_MASK : SECONDARY_RAY_MASK),
                    0,
                    0,
                    0,
                    rayOrigin,
                    0.001,
                    rayDirection,
                    camera.sun.w,
                    0
                );
                if (bounce == 0 && pathPosition.w >= 0.5) {
                    primaryPosition = pathPosition.xyz;
                    primaryLocalPosition = pathLocalPosition;
                    primaryDynamicSlot = pathDynamicSlot;
                    primaryRayDirection = rayDirection;
                    primaryMaterial = pathMaterial;
                    primaryHit = true;
                }
                vec3 sunTemperatureColor = colorTemperature(camera.environment.y);
                if (pathPosition.w < 0.5) {
                    if (bounce == 1) {
                        if (primarySpecularPath) {
                            specularHitDistance = nrdHitDistanceScale;
                        } else {
                            diffuseHitDistance = nrdHitDistanceScale;
                        }
                    }
                    float rain = clamp(camera.environmentState.y, 0.0, 1.0);
                    float thunder = clamp(camera.environmentState.z, 0.0, 1.0);
                    float night = clamp(camera.environmentState.w, 0.0, 1.0);
                    float daylight = 1.0 - night;
                    float weatherVisibility = clamp(1.0 - rain * 0.55 - thunder * 0.25, 0.25, 1.0);
                    // Environment energy is authored by the RT pass, never sampled from
                    // Minecraft's baked sky/light attributes. skyBrightness only applies
                    // day/night exposure to the static skybox; the sun disk below uses the
                    // same disk-integrated direct scale as surface NEE.
                    float skyBrightness = clamp(0.25 + 0.75 * daylight, 0.0, 1.0);
                    vec3 ambientTemperatureColor = colorTemperature(camera.environment.z);
                    if (!DIRECT_SUN_ONLY) {
                        vec3 skyRadiance = throughput * skySrgbToWorking(
                                skyDecodeSrgb(textureLod(skybox, rayDirection, 0.0).rgb))
                            * ambientTemperatureColor * (0.08 + 0.92 * skyBrightness) * weatherVisibility;
                        bool sunIsValid = dot(camera.sun.xyz, camera.sun.xyz) > 1.0e-8;
                        bool sunDiskIsHit = sunIsValid && sunDiskHit(rayDirection, camera.sun.xyz);
                        if (daylight > 0.0 && sunDiskIsHit) {
                            skySunDiskHit = true;
                            // settings.x is the calibrated disk-integrated direct-sun scale.
                            // Convert that same source to directional radiance for an environment miss.
                            float sunMisWeight = (bounce == 0 || previousWasDelta) ? 1.0 : powerHeuristic(
                                previousBsdfPdf, 1.0 / max(sunSolidAngle(), 1.0e-8));
                            skyRadiance += throughput * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))
                                * sunTemperatureColor * sunMisWeight * daylight * weatherVisibility;
                        }
                        radiance += skyRadiance;
                        if (bounce > 0 && primaryTransmissionPath) {
                            transmissionRadiance += skyRadiance;
                        } else if (bounce > 0 && primarySpecularPath) {
                            specularRadiance += skyRadiance;
                        } else {
                            diffuseRadiance += skyRadiance;
                            indirectDiffuseRadiance += skyRadiance;
                        }
                    }
                    break;
                }

                if (insideMedium) {
                    float mediumDistance = length(pathPosition.xyz - rayOrigin);
                    throughput *= exp(-mediumAbsorption * mediumDistance);
                }
                if (bounce == 1) {
                    float firstBounceDistance = length(pathPosition.xyz - rayOrigin);
                    if (primarySpecularPath) {
                        specularHitDistance = firstBounceDistance;
                    } else {
                        diffuseHitDistance = firstBounceDistance;
                    }
                }

                // Baked quads are two-sided in the BLAS. Orient the hit normal toward
                // the ray origin so direct-light cosine tests and ray offsets use the
                // visible side instead of occasionally starting inside the surface.
                vec3 geometricNormal = normalize(pathNormal.xyz);
                vec3 normal = dot(rayDirection, geometricNormal) < 0.0
                    ? geometricNormal
                    : -geometricNormal;
                // The ray state, not quad winding, determines whether this is an entry. This
                // also makes a double-sided glass pane tint correctly from either face.
                bool enteringMedium = !insideMedium;
                vec3 baseColor = max(pathBaseColorRoughness.rgb, vec3(0.0));
                float roughness = clamp(pathBaseColorRoughness.w, 0.0, 1.0);
                if (bounce == 0) {
                    primaryNormal = normal;
                    primaryBaseColor = baseColor;
                    primaryRoughness = roughness;
                }
                float metallic = clamp(pathMaterial.x, 0.0, 1.0);
                float emission = max(pathMaterial.y, 0.0);
                float reflectivity = clamp(pathMaterial.z, 0.04, 1.0);
                // The explicit flag is preferred, but IOR is a safe fallback for glass/water
                // captured from a resource-pack layer that was misclassified as opaque.
                bool transmission = pathMaterial.w > 0.5 || pathOpticalLighting.x > 1.001;
                float transmissionOpacity = clamp(pathLocalPosition.w, 0.0, 1.0);
                // Apply the albedo tint when entering the medium; Beer-Lambert handles the
                // color carried through the interior and the exit interface stays untinted.
                vec3 transmissionColor = enteringMedium
                    ? materialTransmissionColor(baseColor, transmissionOpacity,
                        pathOpticalLighting.x)
                    : vec3(1.0);
                vec3 viewDirection = normalize(-rayDirection);
                float diffuseMaterialWeight = transmission ? 0.0 : (1.0 - metallic);
                // Each effect owns a stable Sobol/Burley domain. Sampling one effect cannot
                // shift the sequence used by BSDF continuation or Russian roulette.
                vec2 sunSample = primeSobolSample2D(
                    sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_SUN, uint(bounce));
                vec3 sampledSunDirection = sampleSunDirection(camera.sun.xyz, sunSample);
                float directCosine = max(dot(normal, sampledSunDirection), 0.0);
                float rain = clamp(camera.environmentState.y, 0.0, 1.0);
                float thunder = clamp(camera.environmentState.z, 0.0, 1.0);
                float night = clamp(camera.environmentState.w, 0.0, 1.0);
                float daylight = 1.0 - night;
                float weatherVisibility = clamp(1.0 - rain * 0.55 - thunder * 0.25, 0.25, 1.0);
                shadowTransmittance = vec3(1.0);
                shadowDynamicOccluder = 0u;
                // When volumetric lighting is enabled, the primary surface still runs the
                // exact same endpoint shadow query even for a back-facing BRDF. The surface
                // contribution remains zero because directCosine is zero, but the resulting
                // visibility is shared with the camera segment below instead of being replaced
                // by a second midpoint shadow path.
                bool needsSunShadow = daylight > 0.001 && (directCosine > 0.0
                    || (bounce == 0 && camera.settings.z > 1.0e-4
                        && camera.environment.x > 1.0e-4));
                if (needsSunShadow) {
                    traceRayEXT(
                        topLevelAS,
                        gl_RayFlagsTerminateOnFirstHitEXT,
                        SECONDARY_RAY_MASK,
                        1,
                        1,
                        1,
                        pathPosition.xyz + normal * 0.002,
                        0.001,
                        sampledSunDirection,
                        camera.sun.w,
                        1
                    );
                }
                // Blend toward unshadowed light according to the existing strength slider,
                // while transmissive blockers preserve their RGB filter.
                vec3 shadowFactor = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                bool sunDynamicOccluder = shadowDynamicOccluder != 0u;
                if (bounce == 0 && needsSunShadow) {
                }
                vec3 directLight = sunTemperatureColor * vec3(directCosine * camera.settings.x
                    * daylight * weatherVisibility) * shadowFactor;
                vec3 sunHalfVector = normalize(viewDirection + sampledSunDirection);
                float sunVDotH = max(dot(viewDirection, sunHalfVector), 1.0e-6);
                vec3 sunF0 = mix(vec3(reflectivity), baseColor, metallic);
                vec3 sunF = schlickFresnel(sunF0, sunVDotH);
                // Use the same mixture helper as the continuation sampler for opaque materials.
                // A transmissive surface resolves its interface Fresnel later in the loop, so the
                // specular lobe is the only non-zero lobe here and its weight is 1. This MIS
                // weight is a variance heuristic, not a transport term; the transport fix is the
                // cosine below.
                float sunSpecularProbability = transmission
                    ? 1.0
                    : primeDefaultSpecularSampleProbability(
                        baseColor, viewDirection, normal, roughness, metallic, reflectivity);
                float sunDiffuseProbability = transmission ? 0.0 : 1.0 - sunSpecularProbability;
                BsdfValue sunBsdf = evaluateBsdf(normal, viewDirection, sampledSunDirection, baseColor,
                    roughness, metallic, reflectivity, diffuseMaterialWeight,
                    sunDiffuseProbability, sunSpecularProbability);
                float sunPdf = 1.0 / max(sunSolidAngle(), 1.0e-8);
                float sunMisWeight = powerHeuristic(sunPdf, sunBsdf.pdf);
                vec3 sampledSunRadiance = sunTemperatureColor
                    * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))
                    * sunMisWeight / max(sunPdf, 1.0e-8);
                directLight = vec3(directCosine) * sampledSunRadiance
                    * daylight * weatherVisibility * shadowFactor;
                // directLight already carries Li * cos(theta). Both lobes must therefore use the
                // same cosine; the specular term previously divided it out again, which removed
                // the cosine from the specular lobe and over-brightened grazing highlights.
                // The diffuse lobe also has to use the same directional energy term as
                // evaluateBsdf() and the area-light path, otherwise the sun and the light tree
                // were evaluating two different BSDFs.
                float sunDiffuseEnergy = primeDefaultDiffuseEnergy(
                    normal, viewDirection, roughness, metallic);
                vec3 sunDiffuseBrdf = diffuseMaterialWeight * baseColor
                    * (vec3(1.0) - sunF) * sunDiffuseEnergy / BSDF_PI;
                vec3 sunDiffuseContribution = sunDiffuseBrdf * directLight;
                vec3 sunSpecularContribution = max(sunBsdf.f - sunDiffuseBrdf, vec3(0.0))
                    * directLight;
                vec3 areaDirectDiffuseContribution = vec3(0.0);
                vec3 areaDirectSpecularContribution = vec3(0.0);
                vec3 areaSample = primeSobolSample3D(
                    sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, uint(bounce));
                if (!transmission) {
                    AreaDirectSplit areaDirect = estimateAreaDirect(
                        pathPosition.xyz, normal, viewDirection, baseColor,
                        roughness, metallic, reflectivity, areaSample);
                    areaDirectDiffuseContribution = areaDirect.diffuse;
                    areaDirectSpecularContribution = areaDirect.specular;
                    if (bounce == 0) {
                        areaLightRadiance += throughput * (areaDirect.diffuse + areaDirect.specular);
                        areaDirectDiffuseRadiance += throughput * areaDirect.diffuse;
                        areaDirectSpecularRadiance += throughput * areaDirect.specular;
                        if (areaDirect.valid) {
                            primaryAreaLight = areaDirect.light;
                            primaryAreaVisibility = areaDirect.visibility;
                            primaryAreaLightValid = true;
                        }
                        primaryDynamicShadow = primaryDynamicShadow || areaDirect.dynamicOccluder;
                    }
                }
                vec3 directDiffuseContribution = sunDiffuseContribution
                    + areaDirectDiffuseContribution;
                vec3 directSpecularContribution = sunSpecularContribution
                    + areaDirectSpecularContribution;
                // Suppress a secondary emitter hit only when the preceding continuous BSDF lobe
                // could also have received that source through light-tree NEE. Perfect mirrors
                // and dielectric transmission are delta paths: NEE has zero probability of
                // producing their exact direction, so removing that hit erased PBR emission in
                // mirrors and behind glass. Delta hits retain their full transported radiance.
                bool bsdfSampledEmitter = bounce > 0 && !previousWasDelta
                    && pathEmitterIndex != LIGHT_NO_EMITTER;
                vec3 emissiveContribution = bsdfSampledEmitter ? vec3(0.0) : baseColor * emission;
                vec3 diffuseContribution = directDiffuseContribution;
                vec3 localDiffuse = diffuseContribution;
                vec3 localSpecular = directSpecularContribution;
                vec3 localRadiance = throughput * (localDiffuse + localSpecular + emissiveContribution);
                radiance += localRadiance;
                if (bounce == 0) {
                    // Only directly visible emission is deterministic and bypasses NRD.
                    emissionRadiance += throughput * emissiveContribution;
                    diffuseRadiance += throughput * localDiffuse;
                    // Keep only the analytic sun in the unfiltered direct layer.  The area-light
                    // sample is stochastic and was the source of the noisy orange/green speckles
                    // around emissive blocks; its two lobes were accumulated above into NRD's
                    // diffuse/specular histories.
                    directDiffuseRadiance += throughput * sunDiffuseContribution;
                    directSpecularRadiance += throughput * sunSpecularContribution;
                    primaryDynamicShadow = primaryDynamicShadow || sunDynamicOccluder;
                } else if (primaryTransmissionPath) {
                    transmissionRadiance += localRadiance;
                } else if (primarySpecularPath) {
                    // Classify all continuation energy by the primary lobe, matching its
                    // material demodulation and hit-distance guide, including sampled lights.
                    specularRadiance += localRadiance;
                } else {
                    diffuseRadiance += localRadiance;
                    indirectDiffuseRadiance += localRadiance;
                }
                if (bounce + 1 >= maxPathSegments) {
                    break;
                }
                // `normal` is oriented against the incoming ray for both entry and exit. Use
                // the path medium state instead of the baked quad winding: vanilla glass is
                // double-sided, and a reversed quad must still tint the first transmitted ray.
                bool interfaceEntering = enteringMedium;
                vec3 interfaceNormal = normal;
                float cosTheta = max(dot(-rayDirection, interfaceNormal), 0.0);
                float materialIor = max(pathOpticalLighting.x, 1.001);
                float dispersionScale = clamp(pathNormal.w, 0.0, 1.0);
                bool spectralInterface = transmission && dispersionScale > 0.0;
                float ior = interfaceEntering ? materialIor : mediumIor;
                if (spectralInterface) {
                    if (spectralChannel < 0) {
                        spectralChannel = min(int(primeSobolSample1D(
                            sampleBase, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, 7u) * 3.0), 2);
                    }
                    ior = primeRcDispersionIor(
                        materialIor, SPECTRAL_ABBE_NUMBER, dispersionScale,
                        SPECTRAL_WAVELENGTHS_NM[spectralChannel]);
                }
                float dielectricF0 = pow((ior - 1.0) / (ior + 1.0), 2.0);
                float fresnel = dielectricF0 + (1.0 - dielectricF0) * pow(1.0 - cosTheta, 5.0);
                float eta = interfaceEntering ? 1.0 / ior : ior;
                vec3 refractedDirection = refract(rayDirection, interfaceNormal, eta);
                bool canTransmit = transmission && dot(refractedDirection, refractedDirection) > 1.0e-6;
                // Sampling probabilities are a stable mixture choice, independent of BRDF
                // weights. Nested media still use the most recent material as a bounded
                // approximation, while closed vanilla surfaces receive eta inversion and
                // Beer-Lambert attenuation.
                // A transmissive delta branch is selected with the same Fresnel probability
                // used by its single-interface approximation. Opaque materials use a bounded
                // luminance heuristic only to choose the BSDF mixture component.
                float specularSamplingProbability = canTransmit
                    ? clamp(fresnel, 0.0, 1.0)
                    : primeDefaultSpecularSampleProbability(
                        baseColor, viewDirection, normal, roughness, metallic, reflectivity);
                diffuseMaterialWeight = transmission ? 0.0 : (1.0 - metallic);
                float transmissionProbability = canTransmit
                    ? max(1.0 - specularSamplingProbability, 0.0) : 0.0;
                float diffuseProbability = transmission
                    ? 0.0 : max(1.0 - specularSamplingProbability, 0.0);
                float specularProbability = specularSamplingProbability;
                float probabilitySum = transmissionProbability + specularProbability + diffuseProbability;
                if (!(probabilitySum > 1.0e-6)) {
                    transmissionProbability = 0.0;
                    specularProbability = 1.0;
                    diffuseProbability = 0.0;
                    probabilitySum = 1.0;
                }
                transmissionProbability /= probabilitySum;
                specularProbability /= probabilitySum;
                diffuseProbability /= probabilitySum;
                vec3 scatterSample = primeSobolSample3D(
                    sampleBase, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, uint(bounce));
                float choice = scatterSample.z;
                bool selectedSpecularPath = false;
                bool selectedTransmissionPath = false;
                if (canTransmit && choice < transmissionProbability) {
                    rayDirection = normalize(refractedDirection);
                    // Delta transmission already carries (1 - F) in its physical weight, and the
                    // branch is selected with p = (1 - F). Dividing by that probability again
                    // multiplied the transmitted energy by 1 / (1 - F), which over-brightened
                    // glass most at grazing angles where F approaches 1.
                    throughput *= transmissionColor;
                    previousBsdfPdf = transmissionProbability;
                    previousWasDelta = true;
                    if (spectralInterface && !spectralMasked) {
                        throughput *= spectralHeroWeight(spectralChannel);
                        spectralMasked = true;
                    }
                    if (interfaceEntering) {
                        mediumAbsorption = max(pathOpticalLighting.yzw, vec3(0.0));
                        mediumIor = ior;
                        insideMedium = true;
                    } else {
                        mediumAbsorption = vec3(0.0);
                        mediumIor = 1.0;
                        insideMedium = false;
                    }
                    selectedSpecularPath = true;
                    selectedTransmissionPath = true;
                } else if (choice < transmissionProbability + specularProbability) {
                    if (roughness == 0.0) {
                        rayDirection = normalize(reflect(-viewDirection, normal));
                        vec3 mirrorF = (transmission && dispersionScale > 0.0)
                            ? vec3(fresnel)
                            : schlickFresnel(
                                mix(vec3(reflectivity), baseColor, metallic),
                                max(dot(normal, viewDirection), 0.0));
                        throughput *= mirrorF / max(specularProbability, 1.0e-6);
                        previousBsdfPdf = specularProbability;
                        previousWasDelta = true;
                    } else {
                        vec3 halfVector = sampleGgx(
                            normal, viewDirection, roughness, scatterSample.xy);
                        rayDirection = normalize(reflect(-viewDirection, halfVector));
                        BsdfValue sampled = evaluateBsdf(normal, viewDirection, rayDirection, baseColor,
                            roughness, metallic, reflectivity, diffuseMaterialWeight,
                            diffuseProbability, specularProbability);
                        float sampledCosine = max(dot(normal, rayDirection), 0.0);
                        throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);
                        previousBsdfPdf = sampled.pdf;
                        previousWasDelta = false;
                    }
                    if (spectralInterface && !spectralMasked) {
                        throughput *= spectralHeroWeight(spectralChannel);
                        spectralMasked = true;
                    }
                    selectedSpecularPath = true;
                } else {
                    rayDirection = sampleCosineHemisphere(normal, scatterSample.xy);
                    BsdfValue sampled = evaluateBsdf(normal, viewDirection, rayDirection, baseColor,
                        roughness, metallic, reflectivity, diffuseMaterialWeight,
                        diffuseProbability, specularProbability);
                    float sampledCosine = max(dot(normal, rayDirection), 0.0);
                    throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);
                    previousBsdfPdf = sampled.pdf;
                    previousWasDelta = false;
                }
                // RR follows emission, direct NEE, and BSDF scatter. It uses scatter beta, not
                // pre-scatter albedo; medium attenuation is already part of the current beta.
                if (bounce > 0) {
                    float betaLuminance = dot(max(throughput, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));
                    float survivalProbability = clamp(betaLuminance, 0.05, 0.95);
                    if (!(betaLuminance > 0.0)) survivalProbability = 0.05;
                    float rouletteSample = primeSobolSample1D(
                        sampleBase, PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE, uint(bounce));
                    if (rouletteSample > survivalProbability) break;
                    throughput /= survivalProbability;
                }
                if (bounce == 0) {
                    primarySpecularPath = selectedSpecularPath;
                    primaryTransmissionPath = selectedTransmissionPath;
                }
                vec3 offsetNormal = dot(rayDirection, normal) >= 0.0 ? normal : -normal;
                rayOrigin = pathPosition.xyz + offsetNormal * 0.003;
            }
            // Diagnostic views bypass the denoiser by writing into the unfiltered direct/emission
            // composition terms and zeroing the filtered indirect/specular payload.
            // TEMPORARY diagnostic build: force the MIS-accounting probe so a config save in the
            // F9 screen cannot select a different view.
            uint debugView = uint(max(camera.parameters.z, 0.0) + 0.5);
            if (debugView != 0u) {
                vec3 debugRadiance = debugView == 1u ? directDiffuseRadiance
                    : ((debugView == 2u || debugView == 8u) ? areaLightRadiance
                    : (debugView == 3u ? emissionRadiance : indirectDiffuseRadiance));
                radiance = sqrt(max(debugRadiance, vec3(0.0)));                emissionRadiance = debugView == 3u ? debugRadiance : vec3(0.0);
                directDiffuseRadiance = debugView == 3u ? vec3(0.0) : debugRadiance;
                directSpecularRadiance = vec3(0.0);
                areaDirectDiffuseRadiance = vec3(0.0);
                areaDirectSpecularRadiance = vec3(0.0);
                indirectDiffuseRadiance = vec3(0.0);
                specularRadiance = vec3(0.0);
                transmissionRadiance = vec3(0.0);
            }
            if (debugView == 5u) {
                vec3 debugColor = lightData.values[1] > 0u ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                radiance = debugColor;
                directDiffuseRadiance = debugColor;
                directSpecularRadiance = vec3(0.0);
                areaDirectDiffuseRadiance = vec3(0.0);
                areaDirectSpecularRadiance = vec3(0.0);
                emissionRadiance = vec3(0.0);
                indirectDiffuseRadiance = vec3(0.0);
                specularRadiance = vec3(0.0);
                transmissionRadiance = vec3(0.0);
            }
            if (debugView == 6u) {
                // Staged probe. Black = sky, red = no leaf, orange = sampleAreaLight rejected it
                // (back-facing or treePdf 0), yellow = evaluateEmitter(sample barycentric) is zero
                // (alpha cutout), magenta = surface cosine is zero, blue = shadow ray blocked,
                // white = BSDF pdf / MIS collapsed, green = full NEE produced radiance.
                vec3 debugColor = vec3(0.0);
                if (primaryHit && lightData.values[1] > 0u) {
                    vec3 probeSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, 0u);
                    vec3 probeNormal = normalize(primaryNormal);
                    vec3 probeView = normalize(-primaryRayDirection);
                    AreaLightSample probeLight = sampleAreaLight(primaryPosition, probeSample);
                    debugColor = vec3(1.0, 0.0, 0.0);
                    if (probeLight.emitterIndex != LIGHT_NO_EMITTER) {
                        debugColor = vec3(1.0, 0.5, 0.0);
                        if (probeLight.pdf > 0.0) {
                            debugColor = vec3(1.0, 1.0, 0.0);
                            if (dot(evaluateEmitter(probeLight.emitterIndex,
                                    probeLight.barycentric), vec3(1.0)) > 0.0) {
                                debugColor = vec3(1.0, 0.0, 1.0);
                                if (dot(probeNormal, probeLight.direction) > 0.0) {
                                    shadowTransmittance = vec3(1.0);
                                    traceRayEXT(topLevelAS,
                                        gl_RayFlagsTerminateOnFirstHitEXT,
                                        SECONDARY_RAY_MASK, 1, 1, 1,
                                        primaryPosition + probeNormal * 0.002, 0.001,
                                        probeLight.direction, probeLight.distance, 1);
                                    debugColor = vec3(0.0, 0.0, 1.0);
                                    if (dot(shadowTransmittance, vec3(1.0)) > 0.0) {
                                        float probeSpecularProbability =
                                            primeDefaultSpecularSampleProbability(
                                                primaryBaseColor, probeView, probeNormal,
                                                primaryRoughness, primaryMaterial.x,
                                                primaryMaterial.z);
                                        BsdfValue probeBsdf = evaluateBsdf(
                                            probeNormal, probeView, probeLight.direction,
                                            primaryBaseColor, primaryRoughness,
                                            primaryMaterial.x, primaryMaterial.z,
                                            1.0 - primaryMaterial.x,
                                            1.0 - probeSpecularProbability, probeSpecularProbability);
                                        float probeMis = powerHeuristic(probeLight.pdf, probeBsdf.pdf);
                                        debugColor = !(probeMis <= 0.0)
                                            ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 1.0, 1.0);
                                    }
                                }
                            }
                        }
                    }
                }
                radiance = debugColor;
                directDiffuseRadiance = debugColor;
                directSpecularRadiance = vec3(0.0);
                areaDirectDiffuseRadiance = vec3(0.0);
                areaDirectSpecularRadiance = vec3(0.0);
                emissionRadiance = vec3(0.0);
                indirectDiffuseRadiance = vec3(0.0);
                specularRadiance = vec3(0.0);
                transmissionRadiance = vec3(0.0);
            }
            if (debugView == 7u) {
                // Sampled-emitter geometry. R = sampled light farther than 4 blocks,
                // G = surface faces the light. Yellow = far + front, green = near + front,
                // red = far + behind, black = near + behind. Light-tree sampling should be
                // dominated by green/yellow near lights, so red/black means the tree is not
                // distance-focused.
                vec3 debugColor = vec3(0.0);
                if (primaryHit) {
                    vec3 probeSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, 0u);
                    AreaLightSample probeLight = sampleAreaLight(primaryPosition, probeSample);
                    if (probeLight.emitterIndex != LIGHT_NO_EMITTER && probeLight.pdf > 0.0) {
                        debugColor = vec3(
                            probeLight.distance > 4.0 ? 1.0 : 0.0,
                            dot(normalize(primaryNormal), probeLight.direction) > 0.0 ? 1.0 : 0.0,
                            0.0);
                    }
                }
                radiance = debugColor;
                directDiffuseRadiance = debugColor;
                directSpecularRadiance = vec3(0.0);
                areaDirectDiffuseRadiance = vec3(0.0);
                areaDirectSpecularRadiance = vec3(0.0);
                emissionRadiance = vec3(0.0);
                indirectDiffuseRadiance = vec3(0.0);
                specularRadiance = vec3(0.0);
                transmissionRadiance = vec3(0.0);
            }
            if (debugView == 9u) {
                // Blocker probe: cast the shadow ray with the MAIN hit group so the closest-hit
                // reports where it stopped. R = a blocker exists, G = blockerDistance/lightDistance,
                // B = the blocker is within 1 cm of the sampled light (i.e. the light's own geometry).
                vec3 debugColor = vec3(0.0);
                if (primaryHit && lightData.values[1] > 0u) {
                    vec3 probeSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, 0u);
                    vec3 probeNormal = normalize(primaryNormal);
                    AreaLightSample probeLight = sampleAreaLight(primaryPosition, probeSample);
                    if (probeLight.emitterIndex != LIGHT_NO_EMITTER && probeLight.pdf > 0.0
                            && dot(probeNormal, probeLight.direction) > 0.0) {
                        pathPosition = vec4(0.0);
                        traceRayEXT(topLevelAS,
                            0u, SECONDARY_RAY_MASK, 0, 1, 0,
                            primaryPosition + probeNormal * 0.002, 0.001,
                            probeLight.direction, probeLight.distance, 0);
                        if (pathPosition.w >= 0.5) {
                            float blockerDistance = length(pathPosition.xyz - primaryPosition);
                            float ratio = blockerDistance / max(probeLight.distance, 1.0e-6);
                            debugColor = vec3(
                                1.0,
                                clamp(ratio, 0.0, 1.0),
                                blockerDistance > probeLight.distance - 0.01 ? 1.0 : 0.0);
                        }
                    }
                }
                radiance = debugColor;
                directDiffuseRadiance = debugColor;
                directSpecularRadiance = vec3(0.0);
                areaDirectDiffuseRadiance = vec3(0.0);
                areaDirectSpecularRadiance = vec3(0.0);
                emissionRadiance = vec3(0.0);
                indirectDiffuseRadiance = vec3(0.0);
                specularRadiance = vec3(0.0);
                transmissionRadiance = vec3(0.0);
            }
            float primaryHitDistance = primaryHit
                ? length(primaryPosition - camera.origin.xyz)
                : 0.0;
            bool atmosphereApplied = false;
            vec3 atmosphereTransmittance = vec3(1.0);
            vec3 atmosphereInscatter = vec3(0.0);
            vec3 volumeEmitterInscatter = vec3(0.0);
            vec3 outputRadiance = max(radiance, vec3(0.0));
            if (debugView == 0u && camera.settings.z > 1.0e-4
                    && camera.environment.x > 1.0e-4) {
                // A miss still exits the locally represented RT scene at camera.sun.w. Extending
                // it to an arbitrary 512-4096 blocks spreads the fixed volume samples far beyond
                // nearby windows and skylights, so the integrated visibility no longer describes
                // their physical openings.
                float atmosphereDistance = primaryHit
                    ? primaryHitDistance
                    : max(camera.sun.w, 1.0);
                // Bind the volume to the physical sun-disk centre. The surface NEE direction is
                // randomly sampled across the disk for soft shadows; reusing that per-pixel
                // direction here shifts a distant shaft away from the opening and changes the
                // shift independently for neighbouring pixels.
                vec3 volumeSunDirection = camera.sun.xyz;
                vec3 atmosphereVolumePosition = camera.origin.xyz
                    + primaryRayDirection * (atmosphereDistance * 0.5);
                integrateAtmosphereSegment(
                    camera.origin.xyz,
                    primaryRayDirection,
                    atmosphereDistance,
                    volumeSunDirection,
                    atmosphereTransmittance,
                    atmosphereInscatter);
                if (primaryHit && primaryAreaLightValid) {
                    // Local emissive geometry reuses the same primary RT emitter sample and its
                    // RGB visibility; no independent volume shadow ray can drift from the surface.
                    vec3 volumeEmitter = sampleVolumeEmitter(
                        atmosphereVolumePosition,
                        primaryRayDirection,
                        primaryAreaLight,
                        primaryAreaVisibility);
                    float fogWeight = clamp(
                        1.0 - dot(atmosphereTransmittance, vec3(0.3333333)), 0.0, 1.0);
                    volumeEmitterInscatter = volumeEmitter * fogWeight * 0.25;
                }
                outputRadiance = outputRadiance * atmosphereTransmittance
                    + atmosphereInscatter + volumeEmitterInscatter;
                atmosphereApplied = true;
            }
            // NRD and Sundial reconstruct surface color from split AOVs after RayGen. Carry the
            // same atmosphere through those signals so their composite cannot erase surface fog.
            // In-scatter is deterministic for this camera segment, so keep it in the unfiltered
            // unfiltered channel instead of asking the denoiser to accumulate it as path noise.
            if (atmosphereApplied) {
                directDiffuseRadiance *= atmosphereTransmittance;
                directSpecularRadiance *= atmosphereTransmittance;
                areaDirectDiffuseRadiance *= atmosphereTransmittance;
                areaDirectSpecularRadiance *= atmosphereTransmittance;
                // Local block-light volume scattering is a one-sample stochastic estimate. It
                // has a valid primary receiver/depth on this path, so carry it in NRD's diffuse
                // history instead of the unfiltered emission channel. Solar/ambient atmosphere
                // remains deterministic and continues to bypass surface denoising.
                indirectDiffuseRadiance = indirectDiffuseRadiance * atmosphereTransmittance
                    + volumeEmitterInscatter;
                specularRadiance *= atmosphereTransmittance;
                transmissionRadiance *= atmosphereTransmittance;
                // Camera-segment in-scatter has no surface correspondence and must not enter
                // NRD's surface history. The emission AOV is the existing unfiltered channel.
                emissionRadiance = emissionRadiance * atmosphereTransmittance
                    + atmosphereInscatter;
            }
            ivec2 outputPixel = ivec2(gl_LaunchIDEXT.xy);
            imageStore(fsrSceneColor, outputPixel, vec4(outputRadiance, 1.0));
            vec2 currentUv = (vec2(gl_LaunchIDEXT.xy) + vec2(0.5)) / vec2(gl_LaunchSizeEXT.xy);
            vec2 motion = vec2(0.0);
            uint primaryDynamicFlags = 0u;
            float depthValue = 0.0;
            if (primaryHit) {
                vec3 toHit = primaryPosition - camera.origin.xyz;
                float currentViewZ = dot(toHit, camera.forward.xyz);
                if (currentViewZ > 0.001) {
                    depthValue = 0.05 / currentViewZ;
                    vec3 previousPosition = primaryPosition;
                    if (primaryDynamicSlot != 0xffffffffu) {
                        uint metadataBase = primaryDynamicSlot * 7u;
                        primaryDynamicFlags = floatBitsToUint(dynamicMotion.values[metadataBase + 6u].x);
                        if ((primaryDynamicFlags & 16u) == 0u) {
                            uint previousBase = metadataBase + 3u;
                            previousPosition = vec3(
                                dot(dynamicMotion.values[previousBase], vec4(primaryLocalPosition.xyz, 1.0)),
                                dot(dynamicMotion.values[previousBase + 1u], vec4(primaryLocalPosition.xyz, 1.0)),
                                dot(dynamicMotion.values[previousBase + 2u], vec4(primaryLocalPosition.xyz, 1.0)));
                        }
                    }
                    vec3 previousToHit = previousPosition - camera.previousOrigin.xyz;
                    float previousViewZ = dot(previousToHit, camera.previousForward.xyz);
                    if (previousViewZ > 0.001) {
                        vec2 previousNdc = vec2(
                            dot(previousToHit, camera.previousRight.xyz)
                                / (previousViewZ * camera.previousParameters.x * camera.previousParameters.y),
                            dot(previousToHit, camera.previousUp.xyz)
                                / (previousViewZ * camera.previousParameters.x));
                        motion = previousNdc * 0.5 + 0.5 - currentUv;
                    }
                }
            } else {
                float previousForward = dot(primaryRayDirection, camera.previousForward.xyz);
                if (previousForward > 0.001) {
                    vec2 previousNdc = vec2(
                        dot(primaryRayDirection, camera.previousRight.xyz)
                            / (previousForward * camera.previousParameters.x * camera.previousParameters.y),
                        dot(primaryRayDirection, camera.previousUp.xyz)
                            / (previousForward * camera.previousParameters.x));
                    motion = previousNdc * 0.5 + 0.5 - currentUv;
                }
            }
            float reactive = (skySunDiskHit || atmosphereApplied
                    || primaryMaterial.w > 0.5 || (primaryDynamicFlags & 16u) != 0u)
                ? 1.0 : 0.0;
            float transparency = (primaryMaterial.w > 0.5 || (primaryDynamicFlags & 4u) != 0u)
                ? 1.0 : 0.0;
            imageStore(fsrMotion, outputPixel, vec4(motion, 0.0, 0.0));
            imageStore(fsrDepth, outputPixel, vec4(depthValue, 0.0, 0.0, 0.0));
            vec3 nrdNormal = primaryHit ? normalize(primaryNormal) : vec3(0.0, 0.0, 1.0);
            vec3 octNormal = nrdNormal / max(abs(nrdNormal.x) + abs(nrdNormal.y)
                + abs(nrdNormal.z), 1.0e-6);
            vec3 packedNormal;
            packedNormal.y = octNormal.y * 0.5 + 0.5;
            packedNormal.x = octNormal.x * 0.5 + packedNormal.y;
            packedNormal.y -= octNormal.x * 0.5;
            float signedRoughness = octNormal.z < 0.0
                ? -max(primaryRoughness, 1.5 / 512.0)
                : max(primaryRoughness, 1.5 / 512.0);
            packedNormal.z = signedRoughness * 0.5 + 0.5;
            float diffuseSignalDistance = diffuseHitDistance > 0.0
                ? diffuseHitDistance
                : (primaryHitDistance > 0.0 ? primaryHitDistance : nrdHitDistanceScale);
            float specularSignalDistance = specularHitDistance > 0.0
                ? specularHitDistance
                : (primaryHitDistance > 0.0 ? primaryHitDistance : nrdHitDistanceScale);
            // A primary miss is environment lighting, not a denoisable surface signal.
            // Keep it out of NRD history; the composite pass preserves it verbatim.
            float diffuseSignalActive = primaryHit && dot(diffuseRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
            // All sampled surface lighting, including the finite sun disk and its visibility,
            // belongs to NRD. Keep this legacy AOV empty so the composite cannot add a second,
            // unfiltered copy of the same shadowed light.
            vec3 directAovRadiance = vec3(0.0);
            float directDiffuseSignalActive = primaryHit && dot(directAovRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
            vec3 filteredDiffuseRadiance = directDiffuseRadiance
                + indirectDiffuseRadiance + areaDirectDiffuseRadiance;
            float indirectDiffuseSignalActive = primaryHit
                && dot(filteredDiffuseRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
            // Perfect mirrors still use exact delta reflection for geometry, while every sampled
            // surface-light lobe enters the matching NRD channel so visibility can accumulate.
            vec3 nrdDiffuseRadiance = filteredDiffuseRadiance;
            vec3 nrdSpecularRadiance = directSpecularRadiance
                + specularRadiance + transmissionRadiance
                + areaDirectSpecularRadiance;
            vec3 unfilteredRadiance = emissionRadiance;
            float nrdDiffuseSignalActive = primaryHit
                && dot(nrdDiffuseRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
            float nrdSpecularSignalActive = primaryHit
                && dot(nrdSpecularRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
            float specularSignalActive = nrdSpecularSignalActive;
            float diffuseNrdHitDistance = diffuseSignalActive > 0.5
                ? clamp(diffuseSignalDistance / nrdHitDistanceScale, 0.0, 1.0)
                : -1.0;
            float specularNrdHitDistance = specularSignalActive > 0.5
                ? clamp(specularSignalDistance / nrdHitDistanceScale, 0.0, 1.0)
                : -1.0;
            // NRD mode keeps linear radiance and the unnormalized path hit distance in the raw
            // images. Sundial remains on the legacy YCoCg contract because it is mutually
            // exclusive with NRD; nrd_motion.comp converts the NRD branch to YCoCg after it has
            // built material factors and camera-correct guides.
            // Sundial consumes the same complete surface-light split. Only visible emission and
            // camera-segment atmosphere are written to the unfiltered AOV below.
            vec3 sundialDiffuseRadiance = filteredDiffuseRadiance;
            vec3 sundialSpecularRadiance = directSpecularRadiance
                + specularRadiance + transmissionRadiance
                + areaDirectSpecularRadiance;
            vec3 nrdDiffuse = primaryHit ? vec3(
                dot(sundialDiffuseRadiance, vec3(0.25, 0.5, 0.25)),
                dot(sundialDiffuseRadiance, vec3(0.5, 0.0, -0.5)),
                dot(sundialDiffuseRadiance, vec3(-0.25, 0.5, -0.25))) : vec3(0.0);
            vec3 nrdSpecular = primaryHit ? vec3(
                dot(sundialSpecularRadiance, vec3(0.25, 0.5, 0.25)),
                dot(sundialSpecularRadiance, vec3(0.5, 0.0, -0.5)),
                dot(sundialSpecularRadiance, vec3(-0.25, 0.5, -0.25))) : vec3(0.0);
            bool nrdSignalMode = camera.dynamicParameters.w > 0.5;
            float diffuseGeneratorHitDistance = nrdDiffuseSignalActive > 0.5
                ? diffuseSignalDistance : -1.0;
            float specularGeneratorHitDistance = nrdSpecularSignalActive > 0.5
                ? specularSignalDistance : -1.0;
            imageStore(nrdNoisyDiffuse, outputPixel, nrdSignalMode
                ? vec4(nrdDiffuseRadiance, diffuseGeneratorHitDistance)
                : vec4(nrdDiffuse, diffuseNrdHitDistance));
            imageStore(nrdNoisySpecular, outputPixel, nrdSignalMode
                ? vec4(nrdSpecularRadiance, specularGeneratorHitDistance)
                : vec4(nrdSpecular, specularNrdHitDistance));
            imageStore(nrdNormalRoughness, outputPixel,
                vec4(packedNormal, 0.0));
            float nrdViewZValue = primaryHit
                ? max(depthValue > 0.0 ? 0.05 / depthValue : 65504.0, 0.001)
                : 65504.0;
            imageStore(nrdViewZ, outputPixel, vec4(nrdViewZValue, 0.0, 0.0, 0.0));
            imageStore(nrdMotion, outputPixel, vec4(motion, 0.0, 0.0));
            // The point is camera-relative, matching the raygen coordinate system. W carries two
            // half-floats of the primary BSDF parameters for NRD demodulation.
            imageStore(nrdPrimaryPosition, outputPixel, vec4(
                primaryHit ? primaryRayDirection * primaryHitDistance : vec3(0.0),
                uintBitsToFloat(packHalf2x16(vec2(primaryMaterial.x, primaryMaterial.z)))));
            uint nrdMaterialFlags = (primaryMaterial.w > 0.5 ? 4u : 0u)
                | (primaryDynamicSlot != 0xffffffffu ? 256u : 0u);
            // Transparent continuation is classified as NRD specular by the preparation pass;
            // keep the primary material valid so its color-filtered path can accumulate in the
            // same temporal history instead of falling back to the noisy raw image.
            imageStore(nrdMaterial, outputPixel,
                vec4(primaryBaseColor, primaryHit ? primaryRoughness : -1.0));
            imageStore(nrdSpecularMaterial, outputPixel,
                vec4(primaryNormal, float(nrdMaterialFlags)));
            // These split AOVs are also populated in Sundial mode. DirectDiffuse remains bound
            // for ABI compatibility but is empty: finite-sun and area-light visibility are both
            // stochastic surface signals and travel through the histories above. Only visible
            // emission and camera-segment atmosphere bypass filtering.
            imageStore(nrdDirectDiffuse, outputPixel,
                vec4(directAovRadiance, primaryDynamicShadow ? 1.0 : 0.0));
            imageStore(nrdIndirectDiffuse, outputPixel,
                vec4(filteredDiffuseRadiance, indirectDiffuseSignalActive));
            imageStore(nrdEmission, outputPixel,
                vec4(emissionRadiance,
                    dot(emissionRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0));
            imageStore(fsrReactive, outputPixel, vec4(reactive));
            imageStore(fsrTransparency, outputPixel, vec4(transparency));
            result.pixels[pixelIndex] = packUnorm4x8(vec4(clamp(outputRadiance, vec3(0.0), vec3(1.0)), 1.0));
        }
        """);
    static final String MISS_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        struct PathPayload {
            vec4 position;
            vec4 normal;
            vec4 baseColorRoughness;
            vec4 material;
            vec4 opticalLighting;
            vec4 localPosition;
            uint dynamicSlot;
            uint emitterIndex;
        };
        layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
        #define pathPosition pathPayload.position
        #define pathLocalPosition pathPayload.localPosition
            #define pathDynamicSlot pathPayload.dynamicSlot
        #define pathEmitterIndex pathPayload.emitterIndex
        void main() {
            pathPosition = vec4(0.0);
            pathLocalPosition = vec4(0.0);
            pathDynamicSlot = 0xffffffffu;
            pathEmitterIndex = 0xffffffffu;
        }
        """;
    static final String CLOSEST_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            vec4 environmentState;
            vec4 dynamicParameters;
            vec4 pbrSettings;
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
        layout(set = 0, binding = 19, std430) readonly buffer DynamicMotionMetadata { vec4 values[]; } dynamicMotion;
        layout(set = 0, binding = 26, std430) readonly buffer LightData { uint values[]; } lightData;
""" + PBR_FUNCTIONS + """
        float materialDecodeSrgbChannel(float encoded) {
            return encoded <= 0.04045
                ? encoded / 12.92
                : pow((encoded + 0.055) / 1.055, 2.4);
        }
        vec3 materialDecodeSrgb(vec3 encoded) {
            return vec3(
                materialDecodeSrgbChannel(encoded.r),
                materialDecodeSrgbChannel(encoded.g),
                materialDecodeSrgbChannel(encoded.b));
        }
        vec3 materialLinearSrgbToWorking(vec3 color) {
            return vec3(
                dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
                dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
                dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
        }
        hitAttributeEXT vec2 barycentrics;
        struct PathPayload {
            vec4 position;
            vec4 normal;
            vec4 baseColorRoughness;
            vec4 material;
            vec4 opticalLighting;
            vec4 localPosition;
            uint dynamicSlot;
            uint emitterIndex;
        };
        layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
        #define pathPosition pathPayload.position
        #define pathNormal pathPayload.normal
        #define pathBaseColorRoughness pathPayload.baseColorRoughness
        #define pathMaterial pathPayload.material
        #define pathOpticalLighting pathPayload.opticalLighting
        #define pathLocalPosition pathPayload.localPosition
        #define pathDynamicSlot pathPayload.dynamicSlot
        #define pathEmitterIndex pathPayload.emitterIndex
        uint pcgHash(uint value) {
            uint state = value * 747796405u + 2891336453u;
            uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
            return (word >> 22u) ^ word;
        }
        float randomFloat(inout uint seed) {
            seed = pcgHash(seed);
            return float(seed) / 4294967296.0;
        }
        vec3 sampleCosineHemisphere(vec3 normal, inout uint seed) {
            float phi = 6.28318530718 * randomFloat(seed);
            float cosTheta = sqrt(1.0 - randomFloat(seed));
            float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
            vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta) + bitangent * (sin(phi) * sinTheta) + normal * cosTheta);
        }
        void main() {
            uint triangleIndex = uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID);
            uint materialIndex = triangleIndex * 7u;
            vec4 tint = materials.entries[materialIndex];
            vec3 rawGeometricNormal = materials.entries[materialIndex + 1u].xyz;
            float geometricNormalLength2 = dot(rawGeometricNormal, rawGeometricNormal);
            vec3 geometricNormal = geometricNormalLength2 > 1.0e-12
                && !any(isnan(rawGeometricNormal)) && !any(isinf(rawGeometricNormal))
                ? rawGeometricNormal * inversesqrt(geometricNormalLength2)
                : -normalize(gl_WorldRayDirectionEXT);
            vec3 normal = geometricNormal;
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 lighting = materials.entries[materialIndex + 4u];
            vec4 surface = materials.entries[materialIndex + 5u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            uint pbrMapIndex = uint(max(lighting.w, 0.0) + 0.5);
            uv = pbrParallaxUv(pbrMapIndex, uv, normal, lighting.x, lighting.z,
                gl_WorldRayDirectionEXT, uv2.w > 1.5);
            vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
            float pbrRoughness = surface.x;
            float pbrMetallic = surface.y;
            float pbrReflectivity = surface.w > 1.0 ? surface.w - 1.0 : surface.w;
            float pbrEmission = surface.z;
            bool pipelineEmissive = optical.z > 0.5;
            float pbrPorosity;
            float pbrTextureAo;
            bool pbrHasAuthoredEmission;
            bool pbrHasNormal;
            bool pbrHasSpecular;
            samplePbr(pbrMapIndex, uv, tangentNormal, pbrRoughness, pbrMetallic,
                pbrReflectivity, pbrEmission, pbrPorosity, pbrTextureAo, pbrHasAuthoredEmission,
                pbrHasNormal, pbrHasSpecular);
            if (pbrHasNormal) {
                normal = pbrWorldNormal(normal, tangentNormal, lighting.x, lighting.z);
            }
            if (pbrHasSpecular) {
                surface.x = pbrRoughness;
                surface.y = pbrMetallic;
                surface.w = (surface.w > 1.0 ? 1.0 : 0.0) + pbrReflectivity;
                surface.z = pbrHasAuthoredEmission ? pbrEmission * camera.pbrSettings.z : surface.z;
                // Vanilla EYES / ENTITY_TRANSLUCENT_EMISSIVE is ITRP's dedicated emissive
                // pass. Preserve that contract even when an entity also has a PBR companion
                // map whose emission channel is absent or zero.
                if (pipelineEmissive) {
                    surface.z = max(surface.z, max(camera.pbrSettings.z, camera.pbrParallaxSettings.y));
                }
                uint pbrMode = uint(max(camera.pbrSettings.x, 0.0) + 0.5);
                if ((pbrMode & 0x200u) != 0u) {
                    surface.x *= 1.0 - clamp(camera.environmentState.y * pbrPorosity
                        * camera.pbrSettings.w, 0.0, 1.0);
                }
            }
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            vec3 baseColor = uv2.w > 0.5
                ? materialLinearSrgbToWorking(materialDecodeSrgb(textureSample.rgb)
                    * materialDecodeSrgb(tint.rgb))
                : tint.rgb;
            if (pbrHasNormal && pbrTextureAo < 0.9999) {
                baseColor *= pow(max(pbrTextureAo, 0.0), 1.0 / 2.2);
            }
            // Keep albedo unmodified; RayGen owns direct NEE, continuation, and emission.
            pathPosition = vec4(gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT, 1.0);
            // localPosition.w carries atlas coverage/opacity as transmission opacity; xyz remains
            // object-space position.
            // Match the shadow any-hit expression exactly. The previous form multiplied tint.a
            // twice for non-textured materials (uv2.w <= 0.5), so a transmissive surface and its
            // own shadow disagreed about the same opacity.
            float surfaceOpacity = clamp(
                (uv2.w > 0.5 ? textureSample.a : 1.0) * tint.a, 0.0, 1.0);
            pathLocalPosition = vec4(gl_ObjectRayOriginEXT + gl_HitTEXT * gl_ObjectRayDirectionEXT,
                surfaceOpacity);
            uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
            uint dynamicStride = uint(max(camera.dynamicParameters.y, 0.0) + 0.5);
            uint dynamicCapacity = uint(max(camera.dynamicParameters.z, 0.0) + 0.5);
            pathDynamicSlot = 0xffffffffu;
            if (dynamicStride > 0u && gl_InstanceCustomIndexEXT >= dynamicStart) {
                uint relative = gl_InstanceCustomIndexEXT - dynamicStart;
                uint slot = relative / dynamicStride;
                if (slot < dynamicCapacity && relative < dynamicCapacity * dynamicStride) {
                    pathDynamicSlot = slot;
                }
            }
            // The normal payload's otherwise-unused w component carries the material's optical
            // dispersion scale without changing the established seven-vec4 material ABI.
            pathNormal = vec4(normal, clamp(lighting.y, 0.0, 1.0));
            pathBaseColorRoughness = vec4(baseColor, clamp(surface.x, 0.0, 1.0));
            bool transmissiveMaterial = surface.w > 1.0 || optical.w > 1.001;
            float materialF0 = surface.w > 1.0 ? surface.w - 1.0 : max(surface.w, 0.04);
            float transmissionSide = dot(gl_WorldRayDirectionEXT, geometricNormal) < 0.0 ? 1.0 : 2.0;
            pathMaterial = vec4(surface.y, surface.z, materialF0,
                transmissiveMaterial ? transmissionSide : 0.0);
            // x = IOR; yzw = per-channel Beer-Lambert absorption coefficients.
            pathOpticalLighting = vec4(optical.w, optical.xyz);
            pathEmitterIndex = triangleIndex < lightData.values[5]
                ? uint(lightData.values[lightData.values[6] + triangleIndex]) : 0xffffffffu;
        }
        """;
    // Disabled legacy stage: not bound by the active SBT/pipeline.
    static final String REFLECTION_CLOSEST_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            vec4 environmentState;
            vec4 dynamicParameters;
            vec4 pbrSettings;
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
""" + PBR_FUNCTIONS + """
        hitAttributeEXT vec2 barycentrics;
        layout(location = 2) rayPayloadInEXT vec4 reflectionPayload;
        void main() {
            uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
            vec4 tint = materials.entries[materialIndex];
            vec3 normal = normalize(materials.entries[materialIndex + 1u].xyz);
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 lighting = materials.entries[materialIndex + 4u];
            vec4 surface = materials.entries[materialIndex + 5u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            uv = pbrParallaxUv(uint(max(lighting.w, 0.0)), uv, normal, lighting.x, lighting.z,
                gl_WorldRayDirectionEXT, uv2.w > 1.5);
            vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
            float pbrRoughness = surface.x;
            float pbrMetallic = surface.y;
            float pbrReflectivity = surface.w > 1.0 ? surface.w - 1.0 : surface.w;
            float pbrEmission = surface.z;
            float pbrPorosity;
            float pbrTextureAo;
            bool pbrHasAuthoredEmission;
            bool pbrHasNormal;
            bool pbrHasSpecular;
            samplePbr(uint(max(lighting.w, 0.0)), uv, tangentNormal, pbrRoughness, pbrMetallic,
                pbrReflectivity, pbrEmission, pbrPorosity, pbrTextureAo, pbrHasAuthoredEmission,
                pbrHasNormal, pbrHasSpecular);
            if (pbrHasNormal) {
                normal = pbrWorldNormal(normal, tangentNormal, lighting.x, lighting.z);
            }
            if (pbrHasSpecular) {
                surface.x = pbrRoughness;
                surface.y = pbrMetallic;
                surface.w = (surface.w > 1.0 ? 1.0 : 0.0) + pbrReflectivity;
            }
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            vec3 baseColor = uv2.w > 0.5 ? textureSample.rgb * tint.rgb : tint.rgb;
            vec3 lightDirection = normalize(camera.sun.xyz);
            float directLight = max(dot(normal, lightDirection), 0.0) * camera.settings.x;
            float normalLight = 1.0;
            float diffuse = (0.08 + 0.92 * 1.0) * normalLight;
            reflectionPayload.rgb = baseColor * diffuse * (1.0 - surface.y) + baseColor * surface.z;
        }
        """;
    // Disabled legacy stage: not bound by the active SBT/pipeline.
    static final String GI_CLOSEST_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 0) uniform accelerationStructureEXT topLevelAS;
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            vec4 environmentState;
            vec4 dynamicParameters;
            vec4 pbrSettings;
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
""" + PBR_FUNCTIONS + """
        hitAttributeEXT vec2 barycentrics;
        layout(location = 0) rayPayloadInEXT uint payload;
        layout(location = 1) rayPayloadEXT vec3 shadowTransmittance;
        layout(location = 4) rayPayloadEXT vec4 giPayload;
        layout(location = 5) rayPayloadEXT vec4 giThroughput;
        uint pcgHash(uint value) {
            uint state = value * 747796405u + 2891336453u;
            uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
            return (word >> 22u) ^ word;
        }
        float randomFloat(inout uint seed) {
            seed = pcgHash(seed);
            return float(seed) / 4294967296.0;
        }
        vec3 sampleCosineHemisphere(vec3 normal, inout uint seed) {
            float phi = 6.28318530718 * randomFloat(seed);
            float cosTheta = sqrt(1.0 - randomFloat(seed));
            float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
            vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bitangent = cross(normal, tangent);
            return normalize(tangent * (cos(phi) * sinTheta) + bitangent * (sin(phi) * sinTheta) + normal * cosTheta);
        }
        void main() {
            uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
            vec4 tint = materials.entries[materialIndex];
            vec3 normal = normalize(materials.entries[materialIndex + 1u].xyz);
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 lighting = materials.entries[materialIndex + 4u];
            vec4 surface = materials.entries[materialIndex + 5u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            uv = pbrParallaxUv(uint(max(lighting.w, 0.0)), uv, normal, lighting.x, lighting.z,
                gl_WorldRayDirectionEXT, uv2.w > 1.5);
            vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
            float pbrRoughness = surface.x;
            float pbrMetallic = surface.y;
            float pbrReflectivity = surface.w > 1.0 ? surface.w - 1.0 : surface.w;
            bool pbrHasNormal;
            bool pbrHasSpecular;
            float pbrEmission = surface.z;
            float pbrPorosity;
            float pbrTextureAo;
            bool pbrHasAuthoredEmission;
            samplePbr(uint(max(lighting.w, 0.0)), uv, tangentNormal, pbrRoughness, pbrMetallic,
                pbrReflectivity, pbrEmission, pbrPorosity, pbrTextureAo, pbrHasAuthoredEmission,
                pbrHasNormal, pbrHasSpecular);
            if (pbrHasNormal) {
                normal = pbrWorldNormal(normal, tangentNormal, lighting.x, lighting.z);
            }
            if (pbrHasSpecular) {
                surface.x = pbrRoughness;
                surface.y = pbrMetallic;
                surface.w = (surface.w > 1.0 ? 1.0 : 0.0) + pbrReflectivity;
            }
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            vec3 baseColor = uv2.w > 0.5 ? textureSample.rgb * tint.rgb : tint.rgb;
            // This legacy stage is disabled; no baked-light contribution is evaluated.
            vec3 albedo = max(baseColor * (1.0 - surface.y), vec3(0.0));
            vec3 lightDirection = normalize(camera.sun.xyz);
            float directCosine = max(dot(normal, lightDirection), 0.0);
            float sunVisibility = 1.0;
            if (directCosine > 0.0) {
                shadowTransmittance = vec3(1.0);
                vec3 hitPosition = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT;
                traceRayEXT(
                    topLevelAS,
                    gl_RayFlagsTerminateOnFirstHitEXT,
                    0xff,
                    1,
                    1,
                    1,
                    hitPosition + normal * 0.003,
                    0.001,
                    lightDirection,
                    camera.sun.w,
                    1
                );
                sunVisibility = dot(shadowTransmittance, vec3(0.3333333));
            }
            float shadowFactor = mix(1.0, sunVisibility, camera.settings.y);
            float directLight = directCosine * camera.settings.x * shadowFactor;
            float ambientNormalLight = 1.0;
            float diffuse = (0.08 + 0.92 * 1.0) * ambientNormalLight;
            vec3 radiance = albedo * diffuse + baseColor * surface.z;
            giPayload.rgb += giThroughput.rgb * radiance;
            // One explicit sun sample (next-event estimation) complements the cosine-weighted bounce.
            giPayload.rgb += giThroughput.rgb * albedo * directLight;
            uint bounce = uint(max(giThroughput.w, 0.0) + 0.5);
            if (bounce + 1u < uint(camera.parameters.w + 0.5)) {
                // Additional diffuse bounces are Russian-roulette sampled. Without this,
                // each extra GI layer adds another expensive secondary/shadow-ray tree for
                // every GI pixel. The 1/4 survival probability keeps the estimator unbiased
                // while making deeper paths more affordable on RADV-class hardware.
                uint continuationSeed = pcgHash(payload ^ floatBitsToUint(camera.random.x)
                    ^ pcgHash(uint(gl_PrimitiveID) ^ ((bounce + 1u) * 0x9e3779b9u)));
                if ((continuationSeed & 3u) != 0u) {
                    return;
                }
                giThroughput.rgb *= albedo * 4.0;
                giThroughput.w = float(bounce + 1u);
                giPayload.w = -1.0;
                uint frameSeed = floatBitsToUint(camera.random.x);
                uint pixelFrameSeed = pcgHash(payload ^ pcgHash(frameSeed ^ floatBitsToUint(camera.random.y)));
                uint seed = pcgHash(pixelFrameSeed ^ pcgHash((bounce + 1u) * 0x9e3779b9u ^ uint(gl_PrimitiveID)));
                vec3 nextDirection = sampleCosineHemisphere(normal, seed);
                vec3 nextOrigin = gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT + normal * 0.003;
                traceRayEXT(
                    topLevelAS,
                    0,
                    0xff,
                    4,
                    1,
                    0,
                    nextOrigin,
                    0.003,
                    nextDirection,
                    camera.sun.w,
                    4
                );
            }
        }
        """;
    // Disabled legacy stage: not bound by the active SBT/pipeline.
    static final String REFRACTION_CLOSEST_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            vec4 environmentState;
            vec4 dynamicParameters;
            vec4 pbrSettings;
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
""" + PBR_FUNCTIONS + """
        hitAttributeEXT vec2 barycentrics;
        layout(location = 3) rayPayloadInEXT vec4 refractionPayload;
        void main() {
            uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
            vec4 tint = materials.entries[materialIndex];
            vec3 normal = normalize(materials.entries[materialIndex + 1u].xyz);
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 lighting = materials.entries[materialIndex + 4u];
            vec4 surface = materials.entries[materialIndex + 5u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            uv = pbrParallaxUv(uint(max(lighting.w, 0.0)), uv, normal, lighting.x, lighting.z,
                gl_WorldRayDirectionEXT, uv2.w > 1.5);
            vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
            float pbrRoughness = surface.x;
            float pbrMetallic = surface.y;
            float pbrReflectivity = surface.w > 1.0 ? surface.w - 1.0 : surface.w;
            bool pbrHasNormal;
            bool pbrHasSpecular;
            float pbrEmission = surface.z;
            float pbrPorosity;
            float pbrTextureAo;
            bool pbrHasAuthoredEmission;
            samplePbr(uint(max(lighting.w, 0.0)), uv, tangentNormal, pbrRoughness, pbrMetallic,
                pbrReflectivity, pbrEmission, pbrPorosity, pbrTextureAo, pbrHasAuthoredEmission,
                pbrHasNormal, pbrHasSpecular);
            if (pbrHasNormal) {
                normal = pbrWorldNormal(normal, tangentNormal, lighting.x, lighting.z);
            }
            if (pbrHasSpecular) {
                surface.x = pbrRoughness;
                surface.y = pbrMetallic;
                surface.w = (surface.w > 1.0 ? 1.0 : 0.0) + pbrReflectivity;
            }
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            vec3 baseColor = uv2.w > 0.5 ? textureSample.rgb * tint.rgb : tint.rgb;
            vec3 lightDirection = normalize(camera.sun.xyz);
            float directLight = max(dot(normal, lightDirection), 0.0) * camera.settings.x;
            float normalLight = 1.0;
            float diffuse = (0.08 + 0.92 * 1.0) * normalLight;
            refractionPayload.rgb = baseColor * diffuse * (1.0 - surface.y) + baseColor * surface.z;
            refractionPayload.w = gl_HitTEXT;
        }
        """;
    static final String SHADOW_MISS_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        layout(location = 1) rayPayloadInEXT vec3 shadowTransmittance;
        layout(location = 2) rayPayloadInEXT uint shadowDynamicOccluder;
        void main() {
            // The raygen stage initializes this payload before every shadow query. Do not reset
            // it here: ignored transparent any-hit intersections have already accumulated their
            // RGB filters, and the miss shader must preserve that result.
        }
        """;
    static final String SHADOW_CLOSEST_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        layout(location = 1) rayPayloadInEXT vec3 shadowTransmittance;
        layout(location = 2) rayPayloadInEXT uint shadowDynamicOccluder;
        void main() { shadowTransmittance = vec3(0.0); }
        """;
    static final String ANY_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        // Primary rays only need the alpha test; their Closest Hit shader handles
        // transmission and refraction normally.
        struct PathPayload {
            vec4 position;
            vec4 normal;
            vec4 baseColorRoughness;
            vec4 material;
            vec4 opticalLighting;
            vec4 localPosition;
            uint dynamicSlot;
            uint emitterIndex;
        };
        layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
        hitAttributeEXT vec2 barycentrics;
        const float ALPHA_CUTOFF = 0.5;
        void main() {
            uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            if (uv2.z > 0.5 && textureSample.a < ALPHA_CUTOFF) {
                ignoreIntersectionEXT;
            }
        }
        """;
    static final String SHADOW_ANY_HIT_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
""" + PLAYER_SKIN_FUNCTIONS + """
        layout(set = 0, binding = 2, std140) uniform Camera {
            vec4 origin;
            vec4 forward;
            vec4 right;
            vec4 up;
            vec4 parameters;
            vec4 sun;
            vec4 settings;
            vec4 environment;
            vec4 random;
            vec4 previousOrigin;
            vec4 previousForward;
            vec4 previousRight;
            vec4 previousUp;
            vec4 previousParameters;
            vec4 jitter;
            vec4 environmentState;
            vec4 dynamicParameters;
            vec4 pbrSettings;
            vec4 pbrParallaxSettings;
        } camera;
        layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
        hitAttributeEXT vec2 barycentrics;
        layout(location = 1) rayPayloadInEXT vec3 shadowTransmittance;
        layout(location = 2) rayPayloadInEXT uint shadowDynamicOccluder;
        const float ALPHA_CUTOFF = 0.5;
        float materialDecodeSrgbChannel(float encoded) {
            return encoded <= 0.04045
                ? encoded / 12.92
                : pow((encoded + 0.055) / 1.055, 2.4);
        }
        vec3 materialDecodeSrgb(vec3 encoded) {
            return vec3(
                materialDecodeSrgbChannel(encoded.r),
                materialDecodeSrgbChannel(encoded.g),
                materialDecodeSrgbChannel(encoded.b));
        }
        vec3 materialLinearSrgbToWorking(vec3 color) {
            return vec3(
                dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
                dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
                dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
        }
        vec3 materialTransmissionColor(vec3 baseColor, float opacity, float ior) {
            if (ior < 1.4) return mix(vec3(1.0), baseColor, opacity);
            float peak = max(baseColor.r, max(baseColor.g, baseColor.b));
            vec3 filterColor = peak > 1.0e-6 ? baseColor / peak : vec3(1.0);
            return mix(vec3(1.0), filterColor, mix(0.75, 1.0, opacity));
        }
        void main() {
            uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
            vec4 tint = materials.entries[materialIndex];
            vec4 uv01 = materials.entries[materialIndex + 2u];
            vec4 uv2 = materials.entries[materialIndex + 3u];
            vec4 surface = materials.entries[materialIndex + 5u];
            vec4 optical = materials.entries[materialIndex + 6u];
            vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                + uv01.zw * barycentrics.x
                + uv2.xy * barycentrics.y;
            vec4 textureSample = uv2.w > 1.5
                ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                    : samplePlayerSkin(uv, optical.x))
                : (uv2.z > 0.5
                    ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                    : textureLod(blockAtlas, uv, 0.0));
            if (uv2.z > 0.5 && textureSample.a < ALPHA_CUTOFF) {
                ignoreIntersectionEXT;
            }

            uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
            if (uint(gl_InstanceCustomIndexEXT) >= dynamicStart) {
                shadowDynamicOccluder = 1u;
            }

            // Continue through transmissive surfaces while carrying their RGB filter. An
            // opaque hit is accepted by Shadow Closest Hit, which writes vec3(0). Applying
            // half the absorption per interface approximates the two faces of a thin pane.
            if (surface.w > 1.0 || optical.w > 1.001) {
                vec3 baseColor = uv2.w > 0.5
                    ? materialLinearSrgbToWorking(materialDecodeSrgb(textureSample.rgb)
                        * materialDecodeSrgb(tint.rgb))
                    : tint.rgb;
                float transmissionOpacity = clamp(
                    (uv2.w > 0.5 ? textureSample.a : 1.0) * tint.a, 0.0, 1.0);
                vec3 transmissionFilter = materialTransmissionColor(
                    baseColor, transmissionOpacity, optical.w);
                transmissionFilter = clamp(transmissionFilter, vec3(0.01), vec3(1.0));
                transmissionFilter *= exp(-max(optical.xyz, vec3(0.0)) * 0.5);
                shadowTransmittance *= transmissionFilter;
                ignoreIntersectionEXT;
            }
        }
        """;

}
