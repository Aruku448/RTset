package com.rtest.client;

final class RayTracingShaderRaygen {
    private static String joinShaderParts(String first, String... following) {
        // Insert shared declarations after extensions, before any query call.
        String extension = "#extension GL_EXT_ray_tracing : require\n";
        return (first.replace(extension, extension + RayTracingAtmosphereShader.GLSL
            + RayTracingAtmosphereSegmentShader.GLSL + RayTracingMoonShader.GLSL)
            + String.join("", following)).replace("// SKY_IMPORTANCE_FUNCTIONS", SkyImportanceShader.GLSL)
            .replace("// RESTIR_DECLARATIONS", RayTracingRestirShader.DECLARATIONS)
            .replace("// RESTIR_FUNCTIONS", RayTracingRestirShader.FUNCTIONS)
            .replace("// RESTIR_MAIN", RayTracingRestirShader.MAIN);
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
            // RESTIR_DECLARATIONS
            layout(set = 0, binding = 26, std430) readonly buffer LightData { uint values[]; } lightData;
            layout(set = 0, binding = 2, std140) uniform Camera {
                vec4 origin;
                // forward.w = solar angular radius (radians); right.w = primary solar samples.
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
                // x = dynamic material start, y = slot stride, z = slot capacity;
                // w = guide mode (-1=off, 1=NRD).
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
            // Keep bit 7 out of all ray masks. Nonzero instances using only that bit stay active
            // for TLAS UPDATE validation while remaining invisible to every trace.
            // Bit 6 retains the first-person body primary/secondary split.
            const uint PRIMARY_RAY_MASK = 0x3fu;
            const uint SECONDARY_RAY_MASK = 0x7eu;
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
            struct ShadowPayload {
                vec3 transmittance;
                uint dynamicOccluder;
                uint excludeDynamic;
            };
            layout(location = 1) rayPayloadEXT ShadowPayload shadowPayload;
            #define shadowTransmittance shadowPayload.transmittance
            #define shadowDynamicOccluder shadowPayload.dynamicOccluder
            #define shadowExcludeDynamic shadowPayload.excludeDynamic
            #define pathPosition pathPayload.position
            #define pathNormal pathPayload.normal
            #define pathBaseColorRoughness pathPayload.baseColorRoughness
            #define pathMaterial pathPayload.material
            #define pathOpticalLighting pathPayload.opticalLighting
            #define pathLocalPosition pathPayload.localPosition
            #define pathDynamicSlot pathPayload.dynamicSlot
            #define pathEmitterIndex pathPayload.emitterIndex
            const uint PRIME_SAMPLE_EFFECT_DIRECT_SUN = 2u;
            const uint PRIME_SAMPLE_EFFECT_SCATTER_BSDF = 3u;
            const uint PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE = 4u;
            const uint PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT = 5u;
            const uint PRIME_SAMPLE_EFFECT_DIRECT_MOON = 6u;
            const uint PRIME_SAMPLE_EFFECT_VOLUME_DISTANCE = 7u;
            const uint PRIME_SAMPLE_EFFECT_VOLUME_EMITTER = 8u;
            const uint PRIME_SAMPLE_EFFECT_DIRECT_SKY = 10u;
            const uint PRIME_SAMPLE_EFFECT_VOLUME_SHADOW = 11u;
            const uint PRIME_SOBOL_INDEX_MASK = 0xffff0000u;
            const float PRIME_UINT32_TO_FLOAT_EXCLUSIVE_SCALE = 1.0 / 4294967808.0;
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
            const float BSDF_PI = 3.14159265359;
            // SKY_IMPORTANCE_FUNCTIONS
            // The sun is sampled uniformly in solid angle, matching Prime's distant-disk
            // sample. Keep its authored intensity as the existing dimensionless multiplier:
            // the disk sample is an average of the same direct-light integrand, not a new
            // solid-angle radiance scale.
            float sunAngularRadius() {
                return clamp(camera.forward.w, 0.000872664626, 0.0872664626);
            }
            float sunSolidAngle() {
                // Stable for small disks: 1-cos(radius) loses precision in float arithmetic.
                float halfSin = sin(0.5 * sunAngularRadius());
                return 4.0 * BSDF_PI * halfSin * halfSin;
            }
            bool sunDiskHit(vec3 rayDirection, vec3 sunDirection) {
                float sunLengthSquared = dot(sunDirection, sunDirection);
                if (!(sunLengthSquared > 1.0e-8)) return false;
                // Unit-vector chord distance avoids the small-disk cosine cancellation too.
                vec3 delta = rayDirection - sunDirection * inversesqrt(sunLengthSquared);
                float halfSin = sin(0.5 * sunAngularRadius());
                return dot(delta, delta) <= 4.0 * halfSin * halfSin;
            }
            vec3 sampleSunDirection(vec3 sunDirection, vec2 sampleValue) {
                vec3 center = normalize(sunDirection);
                float phi = 6.28318530718 * sampleValue.y;
                float halfSin = sin(0.5 * sunAngularRadius());
                float delta = (1.0 - sampleValue.x) * 2.0 * halfSin * halfSin;
                float cosTheta = 1.0 - delta;
                float sinTheta = sqrt(max(delta * (2.0 - delta), 0.0));
                vec3 tangent = normalize(abs(center.y) < 0.999
                    ? cross(center, vec3(0.0, 1.0, 0.0))
                    : cross(center, vec3(1.0, 0.0, 0.0)));
                vec3 bitangent = cross(center, tangent);
                return normalize(tangent * (cos(phi) * sinTheta)
                    + bitangent * (sin(phi) * sinTheta) + center * cosTheta);
            }
            """, RayTracingShaderAtmosphereTransport.GLSL, RayTracingShaderBsdf.GLSL,
            RayTracingShaderEmitters.GLSL, """
            // Fluid side boundaries are inset only 1 mm from adjacent block faces. The
            // opaque 3 mm spawn bias must not jump across those faces after a dielectric hit.
            const float DIELECTRIC_RAY_MIN_OFFSET = 0.0001;
            const float DIELECTRIC_RAY_ERROR_SCALE = 4.76837158203125e-7;
            const float DIELECTRIC_RAY_T_MIN = 0.0001;
            float dielectricRayOffset(vec3 point) {
                float maxCoord = max(max(abs(point.x), abs(point.y)), abs(point.z));
                return max(DIELECTRIC_RAY_MIN_OFFSET, maxCoord * DIELECTRIC_RAY_ERROR_SCALE);
            }
            void runRestirPath() {
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
                float rayTMin = 0.001;
                vec3 throughput = vec3(1.0);
                bool restirPrimaryPrefixValid = false;
                vec3 prefixRadiance = vec3(0.0);
                vec3 prefixDynamicDiffuse = vec3(0.0);
                vec3 prefixDynamicSpecular = vec3(0.0);
                // A single active medium covers the closed vanilla glass/water surfaces. Nested
                // media remain a bounded approximation, but each segment gets physical attenuation.
                bool cameraInWater = (uint(camera.pbrParallaxSettings.w + 0.5) & 4u) != 0u;
                vec3 mediumAbsorption = cameraInWater ? vec3(0.09, 0.045, 0.015) : vec3(0.0);
                bool insideMedium = cameraInWater;
                vec3 radiance = vec3(0.0);
                vec3 specularRadiance = vec3(0.0);
                vec3 directDiffuseRadiance = vec3(0.0);
                vec3 directSpecularRadiance = vec3(0.0);
                shadowExcludeDynamic = 0u;
                vec3 dynamicDiffuseDelta = vec3(0.0);
                vec3 dynamicSpecularDelta = vec3(0.0);
                // Both finite-sun visibility and area-light NEE are sampled. Keep their lobe
                // accumulators separate here, then include both in the denoisable surface AOVs.
                // Visible emission, camera-segment atmosphere and entity visibility deltas bypass reconstruction.
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
                float mediumIor = cameraInWater ? 1.333 : 1.0;
                vec3 primaryPosition = vec3(0.0);
                vec3 primaryBaseColor = vec3(0.0);
                vec4 primaryLocalPosition = vec4(0.0);
                uint primaryDynamicSlot = 0xffffffffu;
                bool pathContainsDynamicModel = false;
                vec3 primaryNormal = vec3(0.0, 0.0, 1.0);
                AreaLightSample primaryAreaLight = AreaLightSample(
                    vec3(0.0), vec3(0.0, 1.0, 0.0), 0.0, 0.0, 0.0, LIGHT_NO_EMITTER, vec2(0.0));
                vec3 primaryAreaVisibility = vec3(1.0);
                vec3 primaryAreaStaticVisibility = vec3(1.0);
                bool primaryAreaLightValid = false;
                bool primaryDynamicShadow = false;
                float primaryRoughness = 0.8;
                vec3 primaryRayDirection = rayDirection;
                vec4 primaryMaterial = vec4(0.0);
                bool primaryHit = false;
                bool primaryTransmissionPath = false;
                // Fixed at the first continuous scatter. Delta reflection has diffuse share zero;
                // delta transmission retains its dedicated accumulator and spectral semantics.
                vec3 primaryDiffuseShare = vec3(0.0);
                // The analytic sun disk is part of the environment miss, but its position changes
                // with the game-clock sun direction. FSR history has no geometry motion vector for
                // this infinitesimal source, so mark its pixels reactive below to prevent a previous
                // frame's cloud texel from covering the current disk.
                bool skySunDiskHit = false;
                const float nrdHitDistanceScale = 64.0;
                float diffuseHitDistance = 0.0;
                float specularHitDistance = 0.0;
                bool primaryDiffuseGuide = false;
                bool primarySpecularGuide = false;
                int giBounces = clamp(int(camera.parameters.w + 0.5), 1, 4);
                int maxPathSegments = 1 + giBounces;
                float previousBsdfPdf = 1.0;
                int previousSunNeeSamples = 1;
                vec3 previousSurfaceNormal = vec3(0.0, 1.0, 0.0);
                // A delta lobe (perfect mirror or delta transmission) has zero probability of being
                // produced by light sampling, so its environment-miss MIS weight must be exactly 1.
                // Treating it as a continuous lobe let the power heuristic cancel most of the sun.
                bool previousWasDelta = false;
                bool previousAreaNeeEnabled = false;
                bool previousSkyNeeEnabled = false;
                // Advance the Sobol sequence with the RT frame. Keeping this at zero repeats the
                // first-bounce direction and light samples every frame, preventing temporal NRD
                // accumulation from increasing the effective first-bounce sample count.
                PrimeSampleBase sampleBase = primeMakeSampleBase(
                    gl_LaunchIDEXT.xy, floatBitsToUint(camera.random.x),
                    floatBitsToUint(camera.random.y), 0u, 0u);
            """, """
                int firstBounce = 0;
                if (restirSuffixActive && restirProposalIndex > 0u && restirPrefixStateReady) {
                    rayOrigin = restirPrefixState.rayOrigin;
                    rayTMin = restirPrefixState.rayTMin;
                    throughput = restirPrefixState.throughput;
                    restirPrimaryPrefixValid = restirPrefixState.restirPrimaryPrefixValid;
                    prefixRadiance = restirPrefixState.prefixRadiance;
                    prefixDynamicDiffuse = restirPrefixState.prefixDynamicDiffuse;
                    prefixDynamicSpecular = restirPrefixState.prefixDynamicSpecular;
                    mediumAbsorption = restirPrefixState.mediumAbsorption;
                    insideMedium = restirPrefixState.insideMedium;
                    radiance = restirPrefixState.radiance;
                    specularRadiance = restirPrefixState.specularRadiance;
                    directDiffuseRadiance = restirPrefixState.directDiffuseRadiance;
                    directSpecularRadiance = restirPrefixState.directSpecularRadiance;
                    dynamicDiffuseDelta = restirPrefixState.dynamicDiffuseDelta;
                    dynamicSpecularDelta = restirPrefixState.dynamicSpecularDelta;
                    areaDirectDiffuseRadiance = restirPrefixState.areaDirectDiffuseRadiance;
                    areaDirectSpecularRadiance = restirPrefixState.areaDirectSpecularRadiance;
                    indirectDiffuseRadiance = restirPrefixState.indirectDiffuseRadiance;
                    emissionRadiance = restirPrefixState.emissionRadiance;
                    areaLightRadiance = restirPrefixState.areaLightRadiance;
                    transmissionRadiance = restirPrefixState.transmissionRadiance;
                    spectralChannel = restirPrefixState.spectralChannel;
                    spectralMasked = restirPrefixState.spectralMasked;
                    mediumIor = restirPrefixState.mediumIor;
                    primaryPosition = restirPrefixState.primaryPosition;
                    primaryBaseColor = restirPrefixState.primaryBaseColor;
                    primaryLocalPosition = restirPrefixState.primaryLocalPosition;
                    primaryDynamicSlot = restirPrefixState.primaryDynamicSlot;
                    pathContainsDynamicModel = restirPrefixState.pathContainsDynamicModel;
                    primaryNormal = restirPrefixState.primaryNormal;
                    primaryAreaLight = restirPrefixState.primaryAreaLight;
                    primaryAreaVisibility = restirPrefixState.primaryAreaVisibility;
                    primaryAreaStaticVisibility = restirPrefixState.primaryAreaStaticVisibility;
                    primaryAreaLightValid = restirPrefixState.primaryAreaLightValid;
                    primaryDynamicShadow = restirPrefixState.primaryDynamicShadow;
                    primaryRoughness = restirPrefixState.primaryRoughness;
                    primaryRayDirection = restirPrefixState.primaryRayDirection;
                    primaryMaterial = restirPrefixState.primaryMaterial;
                    primaryHit = restirPrefixState.primaryHit;
                    primaryTransmissionPath = restirPrefixState.primaryTransmissionPath;
                    primaryDiffuseShare = restirPrefixState.primaryDiffuseShare;
                    primaryDiffuseGuide = restirPrefixState.primaryDiffuseGuide;
                    primarySpecularGuide = restirPrefixState.primarySpecularGuide;
                    skySunDiskHit = restirPrefixState.skySunDiskHit;
                    diffuseHitDistance = restirPrefixState.diffuseHitDistance;
                    specularHitDistance = restirPrefixState.specularHitDistance;
                    previousBsdfPdf = restirPrefixState.previousBsdfPdf;
                    previousSunNeeSamples = restirPrefixState.previousSunNeeSamples;
                    previousSurfaceNormal = restirPrefixState.previousSurfaceNormal;
                    previousWasDelta = restirPrefixState.previousWasDelta;
                    previousAreaNeeEnabled = restirPrefixState.previousAreaNeeEnabled;
                    previousSkyNeeEnabled = restirPrefixState.previousSkyNeeEnabled;
                    rayDirection = restirPrefixState.rayDirection;
                    firstBounce = 1;
                }
                for (int bounce = firstBounce; bounce < 5; bounce++) {
                    // Save after the first scatter; borrowed tapes resume this exact prefix.
                    if (restirSuffixActive && restirProposalIndex == 0u && bounce == 1) {
                        restirPrefixState.rayOrigin = rayOrigin;
                        restirPrefixState.rayTMin = rayTMin;
                        restirPrefixState.throughput = throughput;
                        restirPrefixState.restirPrimaryPrefixValid = restirPrimaryPrefixValid;
                        restirPrefixState.prefixRadiance = prefixRadiance;
                        restirPrefixState.prefixDynamicDiffuse = prefixDynamicDiffuse;
                        restirPrefixState.prefixDynamicSpecular = prefixDynamicSpecular;
                        restirPrefixState.mediumAbsorption = mediumAbsorption;
                        restirPrefixState.insideMedium = insideMedium;
                        restirPrefixState.radiance = radiance;
                        restirPrefixState.specularRadiance = specularRadiance;
                        restirPrefixState.directDiffuseRadiance = directDiffuseRadiance;
                        restirPrefixState.directSpecularRadiance = directSpecularRadiance;
                        restirPrefixState.dynamicDiffuseDelta = dynamicDiffuseDelta;
                        restirPrefixState.dynamicSpecularDelta = dynamicSpecularDelta;
                        restirPrefixState.areaDirectDiffuseRadiance = areaDirectDiffuseRadiance;
                        restirPrefixState.areaDirectSpecularRadiance = areaDirectSpecularRadiance;
                        restirPrefixState.indirectDiffuseRadiance = indirectDiffuseRadiance;
                        restirPrefixState.emissionRadiance = emissionRadiance;
                        restirPrefixState.areaLightRadiance = areaLightRadiance;
                        restirPrefixState.transmissionRadiance = transmissionRadiance;
                        restirPrefixState.spectralChannel = spectralChannel;
                        restirPrefixState.spectralMasked = spectralMasked;
                        restirPrefixState.mediumIor = mediumIor;
                        restirPrefixState.primaryPosition = primaryPosition;
                        restirPrefixState.primaryBaseColor = primaryBaseColor;
                        restirPrefixState.primaryLocalPosition = primaryLocalPosition;
                        restirPrefixState.primaryDynamicSlot = primaryDynamicSlot;
                        restirPrefixState.pathContainsDynamicModel = pathContainsDynamicModel;
                        restirPrefixState.primaryNormal = primaryNormal;
                        restirPrefixState.primaryAreaLight = primaryAreaLight;
                        restirPrefixState.primaryAreaVisibility = primaryAreaVisibility;
                        restirPrefixState.primaryAreaStaticVisibility = primaryAreaStaticVisibility;
                        restirPrefixState.primaryAreaLightValid = primaryAreaLightValid;
                        restirPrefixState.primaryDynamicShadow = primaryDynamicShadow;
                        restirPrefixState.primaryRoughness = primaryRoughness;
                        restirPrefixState.primaryRayDirection = primaryRayDirection;
                        restirPrefixState.primaryMaterial = primaryMaterial;
                        restirPrefixState.primaryHit = primaryHit;
                        restirPrefixState.primaryTransmissionPath = primaryTransmissionPath;
                        restirPrefixState.primaryDiffuseShare = primaryDiffuseShare;
                        restirPrefixState.primaryDiffuseGuide = primaryDiffuseGuide;
                        restirPrefixState.primarySpecularGuide = primarySpecularGuide;
                        restirPrefixState.skySunDiskHit = skySunDiskHit;
                        restirPrefixState.diffuseHitDistance = diffuseHitDistance;
                        restirPrefixState.specularHitDistance = specularHitDistance;
                        restirPrefixState.previousBsdfPdf = previousBsdfPdf;
                        restirPrefixState.previousSunNeeSamples = previousSunNeeSamples;
                        restirPrefixState.previousSurfaceNormal = previousSurfaceNormal;
                        restirPrefixState.previousWasDelta = previousWasDelta;
                        restirPrefixState.previousAreaNeeEnabled = previousAreaNeeEnabled;
                        restirPrefixState.previousSkyNeeEnabled = previousSkyNeeEnabled;
                        restirPrefixState.rayDirection = rayDirection;
                        restirPrefixStateReady = true;
                    }
                    sampleBase.vertexIndex = uint(bounce);
                    // Consecutive temporal indices preserve Sobol coverage. Vertex/effect/dimension
                    // seeds already isolate each bounce and sampling domain.
                    sampleBase.sampleIndex = floatBitsToUint(camera.random.x);
                    if (restirSuffixActive && bounce >= 1) {
                        sampleBase.pixel = uvec2(0u);
                        sampleBase.sampleEpoch = restirSuffixSeed;
                        sampleBase.sampleIndex = 0u;
                        sampleBase.pathIndex = 0u;
                    }
                    pathPosition = vec4(0.0);
                    pathLocalPosition = vec4(0.0);
                    pathDynamicSlot = 0xffffffffu;
                    pathEmitterIndex = 0xffffffffu;
                    pathNormal = vec4(0.0);
                    pathBaseColorRoughness = vec4(0.0);
                    pathMaterial = vec4(0.0);
                    pathOpticalLighting = vec4(0.0);
                    // Camera ray is identical across gather prefixes. The secondary ray is
                    // identical across suffix proposals at the same prefix. Cache their hits
                    // within this invocation; all subsequent suffix/visibility rays stay fresh.
                    if (restirSuffixActive && bounce == 0 && restirCameraHitReady) {
                        pathPayload = restirCameraHit;
                    } else if (restirSuffixActive && bounce == 1 && restirProposalIndex > 0u
                            && restirPrefixStateReady) {
                        pathPayload = restirPrefixHit;
                    } else {
                    traceRayEXT(
                        topLevelAS,
                        0,
                        (bounce == 0 ? PRIMARY_RAY_MASK : SECONDARY_RAY_MASK),
                        0,
                        0,
                        0,
                        rayOrigin,
                        rayTMin,
                        rayDirection,
                        camera.sun.w,
                        0
                    );
                        if (restirSuffixActive && bounce == 0) {
                            restirCameraHit = pathPayload;
                            restirCameraHitReady = true;
                        }
                        if (restirSuffixActive && bounce == 1 && restirProposalIndex == 0u) {
                            restirPrefixHit = pathPayload;
                        }
                    }
                    pathContainsDynamicModel = pathContainsDynamicModel || pathDynamicSlot != 0xffffffffu;
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
                        if (insideMedium) throughput *= exp(-mediumAbsorption * max(camera.sun.w, 0.0));
                        if (bounce == 1) {
                            if (primaryDiffuseGuide
                                    && dot(throughput * primaryDiffuseShare, vec3(1.0)) > 0.0) {
                                diffuseHitDistance = nrdHitDistanceScale;
                            }
                            if (primarySpecularGuide
                                    && dot(throughput * (vec3(1.0) - primaryDiffuseShare), vec3(1.0)) > 0.0) {
                                specularHitDistance = nrdHitDistanceScale;
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
                        {
                            vec3 skyRadiance;
                            if (physicalAtmosphereEnabled()) {
                                // LUT is already linear Rec.2020, calibrated at sun scale 12.5.
                                skyRadiance = throughput * physicalAtmosphereSky(rayDirection, camera.sun.xyz)
                                    * (camera.settings.x / 12.5) * weatherVisibility;
                                vec3 moonDirection = vec3(camera.origin.w, camera.environment.w, camera.jitter.z);
                                float moonIrradianceValue = moonIrradiance(camera.jitter.w);
                                if (moonIrradianceValue > 0.0 && moonDirectionValid(moonDirection)) {
                                    // The reflected-solar Moon spectrum is an explicit approximation:
                                    // share the solved medium and scale its unit-solar response by
                                    // the phase-resolved lunar irradiance.
                                    skyRadiance += throughput * physicalAtmosphereMoonSky(rayDirection, moonDirection)
                                        * (moonIrradianceValue / 12.5) * weatherVisibility;
                                }
#ifdef RTEST_ATMOSPHERE_LUT
                                // Artistic PNG alpha-over, in linear Rec.2020, shared by all misses.
                                // Decode only the PNG, never the already-linear physical LUT.
                                if (camera.pbrParallaxSettings.z > 0.5 && atmosphereCamera.parameters.y > 0.0) {
                                    vec3 textureRadiance = throughput * skySrgbToWorking(
                                        skyDecodeSrgb(textureLod(skybox, rayDirection, 0.0).rgb))
                                        * ambientTemperatureColor * (0.08 + 0.92 * skyBrightness) * weatherVisibility;
                                    skyRadiance = mix(skyRadiance, textureRadiance,
                                        clamp(atmosphereCamera.parameters.y, 0.0, 1.0));
                                }
#endif
                            } else {
                                // Diagnostic switch: no PNG background or environment illumination.
                                // Physical LUT and solar disk remain independent of this legacy flag.
                                skyRadiance = vec3(0.0);
                                if (camera.pbrParallaxSettings.z > 0.5) {
                                    skyRadiance = throughput * skySrgbToWorking(
                                        skyDecodeSrgb(textureLod(skybox, rayDirection, 0.0).rgb))
                                        * ambientTemperatureColor * (0.08 + 0.92 * skyBrightness) * weatherVisibility;
                                }
                            }
                            float skyMisWeight = 1.0;
                            if (previousSkyNeeEnabled && !previousWasDelta) {
                                float skyPdf = skyNeePdf(previousSurfaceNormal, rayDirection);
                                skyMisWeight = powerHeuristic(previousBsdfPdf, skyPdf);
                            }
                            skyRadiance *= skyMisWeight;
                            bool sunIsValid = dot(camera.sun.xyz, camera.sun.xyz) > 1.0e-8;
                            bool sunDiskIsHit = sunIsValid && sunDiskHit(rayDirection, camera.sun.xyz);
                            if ((physicalAtmosphereEnabled() || daylight > 0.0) && sunDiskIsHit) {
                                skySunDiskHit = true;
                                // settings.x is the calibrated disk-integrated direct-sun scale.
                                // Convert that same source to directional radiance for an environment miss.
                                float sunMisWeight = (bounce == 0 || previousWasDelta) ? 1.0 : powerHeuristic(
                                    previousBsdfPdf, float(previousSunNeeSamples) / max(sunSolidAngle(), 1.0e-8));
                                if (physicalAtmosphereEnabled()) {
                                    // Clip/transmit the actual disk ray, not the sun centre.
                                    skyRadiance += throughput * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))
                                        * physicalAtmosphereSunTransmittance(rayDirection) * sunMisWeight * weatherVisibility;
                                } else {
                                    skyRadiance += throughput * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))
                                        * sunTemperatureColor * sunMisWeight * daylight * weatherVisibility;
                                }
                            }
                            // Spare camera lanes contain the actual MC moon direction and phase.
                            // Independent of PNG opacity; both physical and legacy misses share this.
                            vec3 moonRadiance = visibleMoonRadiance(rayDirection,
                                vec3(camera.origin.w, camera.environment.w, camera.jitter.z), camera.jitter.w);
                            if (dot(moonRadiance, vec3(1.0)) > 0.0) {
                                vec3 moonTransmittance = physicalAtmosphereEnabled()
                                    ? physicalAtmosphereSunTransmittance(rayDirection)
                                    : vec3(rayDirection.y > 0.0 ? 1.0 : 0.0);
                                float moonMisWeight = (bounce == 0 || previousWasDelta) ? 1.0
                                    : powerHeuristic(previousBsdfPdf, 1.0 / max(moonSolidAngle(), 1e-8));
                                skyRadiance += throughput * moonRadiance * moonTransmittance * weatherVisibility * moonMisWeight;
                                skySunDiskHit = true; // Reuse celestial reactive mask, not the solar MIS PDF.
                            }
                            radiance += skyRadiance;
                            if (bounce > 0 && primaryTransmissionPath) {
                                transmissionRadiance += skyRadiance;
                            } else if (bounce > 0) {
                                indirectDiffuseRadiance += skyRadiance * primaryDiffuseShare;
                                specularRadiance += skyRadiance * (vec3(1.0) - primaryDiffuseShare);
                            } else {
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
                        if (primaryDiffuseGuide
                                && dot(throughput * primaryDiffuseShare, vec3(1.0)) > 0.0) {
                            diffuseHitDistance = firstBounceDistance;
                        }
                        if (primarySpecularGuide
                                    && dot(throughput * (vec3(1.0) - primaryDiffuseShare), vec3(1.0)) > 0.0) {
                            specularHitDistance = firstBounceDistance;
                        }
                    }

                    // Closest-hit orients the mapped normal using the geometric face.
                    // Do not flip it again by its view-dependent mapped-normal dot product.
                    vec3 normal = normalize(pathNormal.xyz);
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
                    if (bounce == 0) restirPrimaryPrefixValid = !cameraInWater && !transmission
                        && roughness >= 0.2 && pathDynamicSlot == 0xffffffffu;
                    if (restirSuffixActive && bounce == 1 && restirProposalIndex == 0u) {
                        // Geometry of the integration prefix is established before any suffix draws.
                        restirSupportPosition = pathPosition.xyz;
                        restirSupportNormal = normal;
                        restirPrimaryPosition = primaryPosition;
                        restirPrefixValid = restirPrimaryPrefixValid && !insideMedium && !transmission
                            && primaryMaterial.w < 0.5 && primaryRoughness >= 0.2 && roughness >= 0.2
                            && primaryDynamicSlot == 0xffffffffu && pathDynamicSlot == 0xffffffffu;
                        restirBeginSuffixReplay(sampleBase);
                    }
                    // Apply the albedo tint when entering the medium; Beer-Lambert handles the
                    // color carried through the interior and the exit interface stays untinted.
                    vec3 transmissionColor = enteringMedium
                        ? materialTransmissionColor(baseColor, transmissionOpacity,
                            pathOpticalLighting.x)
                        : vec3(1.0);
                    vec3 viewDirection = normalize(-rayDirection);
                    float diffuseMaterialWeight = transmission ? 0.0 : (1.0 - metallic);
                    // Resolve the continuation mixture once before lunar NEE. This is the same
                    // interface/Fresnel calculation and Sobol domain previously used below;
                    // moving it does not change BSDF, RR or emitter-selection sequences.
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
                    float iorRatio = (ior - 1.0) / (ior + 1.0);
                    float dielectricF0 = iorRatio * iorRatio;
                    float fresnel = dielectricF0 + (1.0 - dielectricF0) * power5(1.0 - cosTheta);
                    float eta = interfaceEntering ? 1.0 / ior : ior;
                    vec3 refractedDirection = refract(rayDirection, interfaceNormal, eta);
                    bool canTransmit = transmission && dot(refractedDirection, refractedDirection) > 1.0e-6;
                    if (transmission) {
                        // One dielectric closure for NEE, GGX reflection and delta transmission.
                        reflectivity = canTransmit ? dielectricF0 : 1.0;
                        metallic = 0.0;
                        if (bounce == 0) primaryMaterial = vec4(metallic, emission, reflectivity, pathMaterial.w);
                    }
                    // Only two receiver-dependent values cross light queries, not a full closure.
                    vec2 receiverEnergy = prepareBsdfDirectionalEnergy(normal, viewDirection, roughness, metallic);
                    float specularSamplingProbability = canTransmit
                        ? clamp(fresnel, 0.0, 1.0)
                        : preparedSpecularSampleProbability(receiverEnergy, roughness, metallic);
                    float transmissionProbability = canTransmit ? max(1.0 - specularSamplingProbability, 0.0) : 0.0;
                    float diffuseProbability = transmission ? 0.0 : max(1.0 - specularSamplingProbability, 0.0);
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
                    // Each effect owns a stable Sobol/Burley domain. Sampling one effect cannot
                    // shift the sequence used by BSDF continuation or Russian roulette.
                    float rain = clamp(camera.environmentState.y, 0.0, 1.0);
                    float thunder = clamp(camera.environmentState.z, 0.0, 1.0);
                    float night = clamp(camera.environmentState.w, 0.0, 1.0);
                    float daylight = 1.0 - night;
                    float weatherVisibility = clamp(1.0 - rain * 0.55 - thunder * 0.25, 0.25, 1.0);
                    int sunSampleCount = bounce == 0
                        ? clamp(int(camera.right.w + 0.5), 1, 16) : 1;
                    previousSunNeeSamples = sunSampleCount;
                    bool cachePrimaryNee = restirSuffixActive && bounce == 0 && restirPrimaryPrefixValid;
                    bool reusePrimaryNee = cachePrimaryNee && restirPrimaryNeeReady;
                    vec3 nonAreaDynamicDiffuse = reusePrimaryNee ? restirPrimaryNee.dynamicDiffuse : vec3(0.0);
                    vec3 nonAreaDynamicSpecular = reusePrimaryNee ? restirPrimaryNee.dynamicSpecular : vec3(0.0);
                    float vegetationKind = transmission ? 0.0 : max(-pathMaterial.w, 0.0);
                    vec3 sunDiffuseContribution = reusePrimaryNee ? restirPrimaryNee.diffuse : vec3(0.0);
                    vec3 sunSpecularContribution = reusePrimaryNee ? restirPrimaryNee.specular : vec3(0.0);
                    bool sunDynamicOccluder = reusePrimaryNee && restirPrimaryNee.dynamicOccluder;
                    // NEE and BSDF miss use the same actual continuation mixture.
                    float sunSpecularProbability = specularProbability;
                    float sunDiffuseProbability = diffuseProbability;
                    if (!reusePrimaryNee) for (int sunIndex = 0; sunIndex < 16; sunIndex++) {
                        if (sunIndex >= sunSampleCount || camera.settings.x == 0.0) break;
                        // Stratify disk area within each frame. Preserve the first sample's Sobol
                        // domain and isolate additional rays from BSDF, moon and volume sequences.
                        vec2 sunSample = primeSobolSample2D(
                            sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_SUN, uint(sunIndex));
                        sunSample.x = (float(sunIndex) + sunSample.x) / float(sunSampleCount);
                        vec3 sampledSunDirection = physicalAtmosphereEnabled()
                            && !(dot(camera.sun.xyz, camera.sun.xyz) > 1.0e-8
                                && !any(isnan(camera.sun.xyz)) && !any(isinf(camera.sun.xyz)))
                            ? vec3(0.0) : sampleSunDirection(camera.sun.xyz, sunSample);
                        float directCosine = max(dot(normal, sampledSunDirection), 0.0);
                        float foliageResponse = vegetationKind > 0.0
                            ? vegetationSunResponse(normal, viewDirection, sampledSunDirection,
                                roughness, vegetationKind) : 0.0;
                        // Both ordinary and foliage transported responses vanish here.
                        if (directCosine == 0.0 && foliageResponse == 0.0) continue;
                        vec3 sunResponse = physicalAtmosphereEnabled()
                            ? physicalAtmosphereSunTransmittance(sampledSunDirection)
                            : sunTemperatureColor * daylight;
                        if (all(equal(sunResponse, vec3(0.0)))) continue;
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        bool needsSunShadow = camera.settings.y != 0.0
                            && (physicalAtmosphereEnabled() || daylight > 0.001)
                            && (directCosine > 0.0 || foliageResponse > 0.0)
                            && dot(sampledSunDirection, sampledSunDirection) > 0.5;
                        if (needsSunShadow) {
                            traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                                1, 1, 1, pathPosition.xyz + normal *
                                    (vegetationKind > 0.0 && dot(normal, sampledSunDirection) < 0.0
                                        ? -0.002 : 0.002), 0.001,
                                sampledSunDirection, camera.sun.w, 1);
                        }
                        // Average transported RGB radiance, not a scalar or a blurred shadow mask.
                        vec3 shadowFactor = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                        bool sampleSunDynamic = shadowDynamicOccluder != 0u;
                        sunDynamicOccluder = sunDynamicOccluder || sampleSunDynamic;
                        vec3 sunStaticVisibility = staticShadowVisibility(pathPosition.xyz + normal *
                            (vegetationKind > 0.0 && dot(normal, sampledSunDirection) < 0.0 ? -0.002 : 0.002),
                            sampledSunDirection, camera.sun.w, sampleSunDynamic, shadowFactor);
                        // Preserve the signed static baseline, including colored transmission.
                        if (!emitterTransportRequired(shadowFactor, sunStaticVisibility)) continue;
                        BsdfValue sunBsdf = evaluateBsdf(normal, viewDirection, sampledSunDirection, baseColor,
                            roughness, metallic, reflectivity, diffuseMaterialWeight,
                            sunDiffuseProbability, sunSpecularProbability, receiverEnergy);
                        float sunPdf = 1.0 / max(sunSolidAngle(), 1.0e-8);
                        float sunMisWeight = bounce + 1 >= maxPathSegments ? 1.0
                            : powerHeuristic(float(sunSampleCount) * sunPdf, sunBsdf.pdf);
                        // Li = intensity / omega, p = 1 / omega: same clamped omega cancels.
                        vec3 sampledSunRadiance = sunResponse * camera.settings.x * sunMisWeight;
                        vec3 directLight = vec3(directCosine) * sampledSunRadiance
                            * weatherVisibility * shadowFactor / float(sunSampleCount);
                        sunDiffuseContribution += sunBsdf.diffuse * directLight;
                        if (vegetationKind > 0.0) {
                            // Add only the artistic correction. The ordinary BRDF retains its
                            // matching MIS/continuation; the correction is integrated only by NEE.
                            vec3 foliageLight = sunResponse * camera.settings.x
                                * weatherVisibility * shadowFactor / float(sunSampleCount);
                            vec3 foliageDiffuse = diffuseMaterialWeight * baseColor
                                * (1.0 - reflectivity) * foliageResponse;
                            sunDiffuseContribution += (foliageDiffuse
                                - sunBsdf.diffuse * directCosine) * foliageLight;
                        }
                        sunSpecularContribution += max(sunBsdf.f - sunBsdf.diffuse, vec3(0.0)) * directLight;
                        if (sampleSunDynamic) {
                            vec3 deltaLight = throughput * sunResponse * camera.settings.x * weatherVisibility
                                * (shadowFactor - sunStaticVisibility) / float(sunSampleCount);
                            vec3 diffuseResponse = sunBsdf.diffuse * directCosine * sunMisWeight;
                            if (vegetationKind > 0.0) diffuseResponse += diffuseMaterialWeight * baseColor
                                * (1.0 - reflectivity) * foliageResponse - sunBsdf.diffuse * directCosine;
                            vec3 diffuseDelta = diffuseResponse * deltaLight;
                            vec3 specularDelta = max(sunBsdf.f - sunBsdf.diffuse, vec3(0.0))
                                * directCosine * sunMisWeight * deltaLight;
                            if (bounce == 0) {
                                nonAreaDynamicDiffuse += diffuseDelta;
                                nonAreaDynamicSpecular += specularDelta;
                            } else if (primaryTransmissionPath) {
                                dynamicSpecularDelta += diffuseDelta + specularDelta;
                            } else {
                                dynamicDiffuseDelta += (diffuseDelta + specularDelta) * primaryDiffuseShare;
                                dynamicSpecularDelta += (diffuseDelta + specularDelta) * (vec3(1.0) - primaryDiffuseShare);
                            }
                        }
                    }
                    // Lunar NEE has its own stable Sobol domain and the same phase-resolved
                    // disk radiance/PDF as a BSDF miss. Do not borrow the sun's PDF or daylight gate.
                    vec3 moonDiffuseContribution = vec3(0.0);
                    vec3 moonSpecularContribution = vec3(0.0);
                    bool moonDynamicOccluder = false;
                    vec3 moonDirection = vec3(camera.origin.w, camera.environment.w, camera.jitter.z);
                    if (!reusePrimaryNee && moonIrradiance(camera.jitter.w) > 0.0 && moonDirectionValid(moonDirection)) {
                        vec3 sampledMoonDirection = sampleMoonDirection(moonDirection, primeSobolSample2D(
                            sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_MOON, uint(bounce)));
                        float moonCosine = max(dot(normal, sampledMoonDirection), 0.0);
                        vec3 lunarT = physicalAtmosphereEnabled()
                            ? physicalAtmosphereSunTransmittance(sampledMoonDirection)
                            : vec3(sampledMoonDirection.y > 0.0 ? 1.0 : 0.0);
                        vec3 lunarLi = visibleMoonRadiance(sampledMoonDirection, moonDirection, camera.jitter.w) * lunarT;
                        if (moonCosine > 0.0 && dot(lunarLi, vec3(1.0)) > 0.0) {
                            shadowTransmittance = vec3(1.0);
                            shadowDynamicOccluder = 0u;
                            if (camera.settings.y != 0.0) traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                                1, 1, 1, pathPosition.xyz + normal * 0.002, 0.001,
                                sampledMoonDirection, camera.sun.w, 1);
                            vec3 lunarVisibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                            moonDynamicOccluder = shadowDynamicOccluder != 0u;
                            vec3 moonStaticVisibility = staticShadowVisibility(pathPosition.xyz + normal * 0.002,
                                sampledMoonDirection, camera.sun.w, moonDynamicOccluder, lunarVisibility);
                            if (emitterTransportRequired(lunarVisibility, moonStaticVisibility)) {
                                BsdfValue moonBsdf = evaluateBsdf(normal, viewDirection, sampledMoonDirection, baseColor,
                                    roughness, metallic, reflectivity, diffuseMaterialWeight,
                                    diffuseProbability, specularProbability, receiverEnergy);
                                float moonPdf = 1.0 / max(moonSolidAngle(), 1e-8);
                                float moonNeeMisWeight = bounce + 1 >= maxPathSegments ? 1.0
                                    : powerHeuristic(moonPdf, moonBsdf.pdf);
                                vec3 moonLight = lunarLi * moonCosine * lunarVisibility * weatherVisibility
                                    * moonNeeMisWeight / moonPdf;
                                moonDiffuseContribution = moonBsdf.diffuse * moonLight;
                                moonSpecularContribution = max(moonBsdf.f - moonBsdf.diffuse, vec3(0.0)) * moonLight;
                                if (moonDynamicOccluder) {
                                    vec3 deltaLight = throughput * lunarLi * moonCosine * weatherVisibility
                                        * moonNeeMisWeight / moonPdf * (lunarVisibility - moonStaticVisibility);
                                    vec3 diffuseDelta = moonBsdf.diffuse * deltaLight;
                                    vec3 specularDelta = max(moonBsdf.f - moonBsdf.diffuse, vec3(0.0)) * deltaLight;
                                    if (bounce == 0) {
                                        nonAreaDynamicDiffuse += diffuseDelta;
                                        nonAreaDynamicSpecular += specularDelta;
                                    } else if (primaryTransmissionPath) {
                                        dynamicSpecularDelta += diffuseDelta + specularDelta;
                                    } else {
                                        dynamicDiffuseDelta += (diffuseDelta + specularDelta) * primaryDiffuseShare;
                                        dynamicSpecularDelta += (diffuseDelta + specularDelta) * (vec3(1.0) - primaryDiffuseShare);
                                    }
                                }
                            }
                        }
                    }
                    vec3 areaDirectDiffuseContribution = vec3(0.0);
                    vec3 areaDirectSpecularContribution = vec3(0.0);
                    vec3 areaSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, uint(bounce));
                    restirDirectBase = sampleBase;
                    restirDirectEligible = bounce == 0 && !transmission
                        && pathDynamicSlot == 0xffffffffu && roughness > 0.0;
                    bool areaNeeEnabled = !transmission;
                    bool skyNeeEnabled = bounce == 0 && !transmission;
                    if (areaNeeEnabled) {
                        AreaDirectSplit areaDirect;
                        if (restirSuffixActive && bounce == 0 && restirPrimaryAreaDirectReady) {
                            areaDirect = restirPrimaryAreaDirect;
                        } else {
                            areaDirect = estimateAreaDirect(
                                pathPosition.xyz, normal, viewDirection, baseColor,
                                roughness, metallic, reflectivity, areaSample, receiverEnergy, true);
                            if (restirSuffixActive && bounce == 0) {
                                restirPrimaryAreaDirect = areaDirect;
                                restirPrimaryAreaDirectReady = true;
                            }
                        }
                        areaDirectDiffuseContribution = areaDirect.diffuse;
                        areaDirectSpecularContribution = areaDirect.specular;
                        if (bounce == 0) {
                            dynamicDiffuseDelta += throughput * areaDirect.dynamicDiffuseDelta;
                            dynamicSpecularDelta += throughput * areaDirect.dynamicSpecularDelta;
                            areaLightRadiance += throughput * (areaDirect.diffuse + areaDirect.specular);
                            areaDirectDiffuseRadiance += throughput * areaDirect.diffuse;
                            areaDirectSpecularRadiance += throughput * areaDirect.specular;
                            if (areaDirect.valid) {
                                primaryAreaLight = areaDirect.light;
                                primaryAreaVisibility = areaDirect.visibility;
                                primaryAreaStaticVisibility = areaDirect.staticVisibility;
                                primaryAreaLightValid = true;
                            }
                            primaryDynamicShadow = primaryDynamicShadow || areaDirect.dynamicOccluder;
                        } else if (primaryTransmissionPath) {
                            dynamicSpecularDelta += throughput * (areaDirect.dynamicDiffuseDelta + areaDirect.dynamicSpecularDelta);
                        } else {
                            vec3 delta = throughput * (areaDirect.dynamicDiffuseDelta + areaDirect.dynamicSpecularDelta);
                            dynamicDiffuseDelta += delta * primaryDiffuseShare;
                            dynamicSpecularDelta += delta * (vec3(1.0) - primaryDiffuseShare);
                        }
                    }
                    // Sample the visible sky directly at primary surfaces. Previously sky
                    // illumination reached grass and other diffuse surfaces only when a sparse
                    // GI continuation happened to miss into the sky, leaving broad daylight
                    // shadows unnaturally dark. Mix cosine and texture-importance proposals; the
                    // paired bounce-1 environment MIS weight prevents double counting.
                    vec3 skyDirectDiffuseContribution = vec3(0.0);
                    vec3 skyDirectSpecularContribution = vec3(0.0);
                    if (skyNeeEnabled && !reusePrimaryNee) {
                        vec3 sampledSkyDirection = sampleSkyNee(normal,
                            primeSobolSample2D(sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_SKY, 0u));
                        float skyCosine = max(dot(normal, sampledSkyDirection), 0.0);
                        float skyPdf = skyNeePdf(normal, sampledSkyDirection);
                        if (skyCosine > 0.0 && skyPdf > 0.0) {
                            shadowTransmittance = vec3(1.0);
                            shadowDynamicOccluder = 0u;
                            if (camera.settings.y != 0.0) traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT,
                                SECONDARY_RAY_MASK, 1, 1, 1,
                                pathPosition.xyz + normal * 0.002, 0.001,
                                sampledSkyDirection, camera.sun.w, 1);
                            vec3 skyVisibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                            bool skyDynamicOccluder = shadowDynamicOccluder != 0u;
                            primaryDynamicShadow = primaryDynamicShadow || skyDynamicOccluder;
                            vec3 skyStaticVisibility = staticShadowVisibility(pathPosition.xyz + normal * 0.002,
                                sampledSkyDirection, camera.sun.w, skyDynamicOccluder, skyVisibility);
                            if (emitterTransportRequired(skyVisibility, skyStaticVisibility)) {
                                vec3 incidentSky = vec3(0.0);
                                float skyDaylight = clamp(1.0 - camera.environmentState.w, 0.0, 1.0);
                                float skyWeather = clamp(1.0 - clamp(camera.environmentState.y, 0.0, 1.0) * 0.55
                                    - clamp(camera.environmentState.z, 0.0, 1.0) * 0.25, 0.25, 1.0);
#ifdef RTEST_ATMOSPHERE_LUT
                                if (physicalAtmosphereEnabled()) {
                                    incidentSky = physicalAtmosphereSky(sampledSkyDirection, camera.sun.xyz)
                                        * (max(camera.settings.x, 0.0) / 12.5) * skyWeather;
                                    vec3 skyMoonDirection = vec3(camera.origin.w, camera.environment.w, camera.jitter.z);
                                    float skyMoonIrradiance = moonIrradiance(camera.jitter.w);
                                    if (skyMoonIrradiance > 0.0 && moonDirectionValid(skyMoonDirection)) {
                                        incidentSky += physicalAtmosphereMoonSky(sampledSkyDirection, skyMoonDirection)
                                            * (skyMoonIrradiance / 12.5) * skyWeather;
                                    }
                                    if (camera.pbrParallaxSettings.z > 0.5 && atmosphereCamera.parameters.y > 0.0) {
                                        vec3 textureSky = skySrgbToWorking(skyDecodeSrgb(
                                            textureLod(skybox, sampledSkyDirection, 0.0).rgb))
                                            * colorTemperature(camera.environment.z)
                                            * (0.08 + 0.92 * clamp(0.25 + 0.75 * skyDaylight, 0.0, 1.0))
                                            * skyWeather;
                                        incidentSky = mix(incidentSky, textureSky,
                                            clamp(atmosphereCamera.parameters.y, 0.0, 1.0));
                                    }
                                } else if (camera.pbrParallaxSettings.z > 0.5) {
                                    incidentSky = skySrgbToWorking(skyDecodeSrgb(
                                        textureLod(skybox, sampledSkyDirection, 0.0).rgb))
                                        * colorTemperature(camera.environment.z)
                                        * (0.08 + 0.92 * clamp(0.25 + 0.75 * skyDaylight, 0.0, 1.0))
                                        * skyWeather;
                                }
#else
                                if (camera.pbrParallaxSettings.z > 0.5) {
                                    incidentSky = skySrgbToWorking(skyDecodeSrgb(
                                        textureLod(skybox, sampledSkyDirection, 0.0).rgb))
                                        * colorTemperature(camera.environment.z)
                                        * (0.08 + 0.92 * clamp(0.25 + 0.75 * skyDaylight, 0.0, 1.0))
                                        * skyWeather;
                                }
#endif
                                BsdfValue skyBsdf = evaluateBsdf(normal, viewDirection, sampledSkyDirection,
                                    baseColor, roughness, metallic, reflectivity, diffuseMaterialWeight,
                                    diffuseProbability, specularProbability, receiverEnergy);
                                float skyNeeMisWeight = powerHeuristic(skyPdf, skyBsdf.pdf);
                                vec3 sampledSkyIrradiance = incidentSky * (skyCosine / skyPdf) * skyVisibility * skyNeeMisWeight;
                                skyDirectDiffuseContribution = skyBsdf.diffuse * sampledSkyIrradiance;
                                skyDirectSpecularContribution = max(skyBsdf.f - skyBsdf.diffuse, vec3(0.0)) * sampledSkyIrradiance;
                                if (skyDynamicOccluder) {
                                    vec3 deltaLight = throughput * incidentSky * (skyCosine / skyPdf)
                                        * skyNeeMisWeight * (skyVisibility - skyStaticVisibility);
                                    nonAreaDynamicDiffuse += skyBsdf.diffuse * deltaLight;
                                    nonAreaDynamicSpecular += max(skyBsdf.f - skyBsdf.diffuse, vec3(0.0)) * deltaLight;
                                }
                            }
                        }
                    }
                    if (bounce == 0) {
                        dynamicDiffuseDelta += nonAreaDynamicDiffuse;
                        dynamicSpecularDelta += nonAreaDynamicSpecular;
                        if (cachePrimaryNee && !reusePrimaryNee) {
                            restirPrimaryNee.diffuse = sunDiffuseContribution + moonDiffuseContribution
                                + skyDirectDiffuseContribution;
                            restirPrimaryNee.specular = sunSpecularContribution + moonSpecularContribution
                                + skyDirectSpecularContribution;
                            restirPrimaryNee.dynamicDiffuse = nonAreaDynamicDiffuse;
                            restirPrimaryNee.dynamicSpecular = nonAreaDynamicSpecular;
                            restirPrimaryNee.dynamicOccluder = sunDynamicOccluder || moonDynamicOccluder;
                            restirPrimaryNeeReady = true;
                        }
                    }
                    vec3 directDiffuseContribution = sunDiffuseContribution
                        + moonDiffuseContribution + areaDirectDiffuseContribution + skyDirectDiffuseContribution;
                    vec3 directSpecularContribution = sunSpecularContribution
                        + moonSpecularContribution + areaDirectSpecularContribution + skyDirectSpecularContribution;
                    // Suppress a secondary emitter hit only when the preceding continuous BSDF lobe
                    // could also have received that source through light-tree NEE. Perfect mirrors
                    // and dielectric transmission are delta paths: NEE has zero probability of
                    // producing their exact direction, so removing that hit erased PBR emission in
                    // mirrors and behind glass. Delta hits retain their full transported radiance.
                    bool bsdfSampledEmitter = bounce > 0 && !previousWasDelta && previousAreaNeeEnabled
                        && pathEmitterIndex != LIGHT_NO_EMITTER;
                    vec3 emissiveContribution = bsdfSampledEmitter ? vec3(0.0) : baseColor * emission;
                    vec3 localRadiance = throughput * (directDiffuseContribution
                        + directSpecularContribution + emissiveContribution);
                    radiance += localRadiance;
                    if (bounce == 0) {
                        // Only directly visible emission is deterministic and bypasses NRD.
                        emissionRadiance += throughput * emissiveContribution;
                        // Keep the finite-sun sample in its own lobe accumulators. The final
                        // diffuse/specular AOVs include these alongside area-light samples; this
                        // is not an additional unfiltered sun layer in either denoiser.
                        directDiffuseRadiance += throughput * (sunDiffuseContribution + moonDiffuseContribution
                            + skyDirectDiffuseContribution);
                        directSpecularRadiance += throughput * (sunSpecularContribution + moonSpecularContribution
                            + skyDirectSpecularContribution);
                        primaryDynamicShadow = primaryDynamicShadow || sunDynamicOccluder || moonDynamicOccluder;
                    } else if (primaryTransmissionPath) {
                        transmissionRadiance += localRadiance;
                    } else {
                        // The shared throughput already includes RR, media and spectral weights.
                        // Partition by evaluated primary lobes, never by the sampled proposal label.
                        indirectDiffuseRadiance += localRadiance * primaryDiffuseShare;
                        specularRadiance += localRadiance * (vec3(1.0) - primaryDiffuseShare);
                    }
                    if (bounce == 0) {
                        prefixRadiance = radiance;
                        prefixDynamicDiffuse = dynamicDiffuseDelta;
                        prefixDynamicSpecular = dynamicSpecularDelta;
                    }
                    if (bounce + 1 >= maxPathSegments) {
                        break;
                    }
                    PrimeSampleBase scatterBase = sampleBase;
                    if (restirSuffixActive && bounce == 0) scatterBase.pathIndex = restirPrefixIndex;
                    vec3 scatterSample = primeSobolSample3D(
                        scatterBase, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, uint(bounce));
                    float choice = scatterSample.z;
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
                        selectedTransmissionPath = true;
                    } else if (choice < transmissionProbability + specularProbability) {
                        if (roughness == 0.0) {
                            rayDirection = normalize(reflect(-viewDirection, normal));
                            // A failed dielectric refraction is total reflection, independent of Schlick.
                            vec3 mirrorF = transmission ? vec3(canTransmit ? fresnel : 1.0)
                                : schlickFresnel(
                                    mix(vec3(reflectivity), baseColor, metallic),
                                    max(dot(normal, viewDirection), 0.0));
                            throughput *= mirrorF / max(specularProbability, 1.0e-6);
                            previousBsdfPdf = specularProbability;
                            previousWasDelta = true;
                        } else {
                            vec3 halfVector = sampleGgx(normal, viewDirection, roughness, scatterSample.xy);
                            rayDirection = normalize(reflect(-viewDirection, halfVector));
                            BsdfValue sampled = evaluateBsdf(normal, viewDirection, rayDirection, baseColor,
                                roughness, metallic, reflectivity, diffuseMaterialWeight,
                                diffuseProbability, specularProbability, receiverEnergy);
                            float sampledCosine = max(dot(normal, rayDirection), 0.0);
                            if (bounce == 0) primaryDiffuseShare = bsdfDiffuseShare(sampled);
                            throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);
                            previousBsdfPdf = sampled.pdf;
                            previousWasDelta = false;
                        }
                        if (spectralInterface && !spectralMasked) {
                            throughput *= spectralHeroWeight(spectralChannel);
                            spectralMasked = true;
                        }
                    } else {
                        rayDirection = sampleCosineHemisphere(normal, scatterSample.xy);
                        BsdfValue sampled = evaluateBsdf(normal, viewDirection, rayDirection, baseColor,
                            roughness, metallic, reflectivity, diffuseMaterialWeight,
                            diffuseProbability, specularProbability, receiverEnergy);
                        float sampledCosine = max(dot(normal, rayDirection), 0.0);
                        if (bounce == 0) primaryDiffuseShare = bsdfDiffuseShare(sampled);
                        throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);
                        previousBsdfPdf = sampled.pdf;
                        previousWasDelta = false;
                    }
                    previousAreaNeeEnabled = areaNeeEnabled;
                    previousSkyNeeEnabled = skyNeeEnabled;
                    // Preserve primary classification before terminating a zero-energy path.
                    if (bounce == 0) {
                        primaryTransmissionPath = selectedTransmissionPath;
                        // Guide samples follow the chosen BSDF proposal lobe; radiance still
                        // partitions by evaluated lobes. No PDF, throughput or UCW scales hitT.
                        primaryDiffuseGuide = !selectedTransmissionPath
                            && choice >= transmissionProbability + specularProbability;
                        primarySpecularGuide = !primaryDiffuseGuide;
                    }
                    if (all(equal(throughput, vec3(0.0)))) break;
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
                    previousSurfaceNormal = normal;
                    vec3 offsetNormal = dot(rayDirection, normal) >= 0.0 ? normal : -normal;
                    float rayOffset = transmission ? dielectricRayOffset(pathPosition.xyz) : 0.003;
                    rayTMin = transmission ? DIELECTRIC_RAY_T_MIN : 0.001;
                    rayOrigin = pathPosition.xyz + offsetNormal * rayOffset;
                }
            """, """
                if (restirSuffixActive) {
                    float misWeight = 1.0 / float(restirSuffixDomainCount
                        + (restirHasCanonicalSuffix ? 1u : 0u));
                    float gatherWeight = (misWeight * restirGatherSelectionScale
                        / float(restirPrefixCount)) * restirSourceWeight;
                    vec3 suffixRadiance = radiance - prefixRadiance;
                    if (restirPrefixIndex == 0u) {
                        // Exclude primary direct-light UCWs from the suffix target entirely.
                        vec3 suffixTargetRadiance = indirectDiffuseRadiance + specularRadiance + transmissionRadiance;
                        float target = max(dot(max(suffixTargetRadiance, vec3(0.0)),
                            vec3(0.2126, 0.7152, 0.0722)), 1e-6);
                        if (restirAddMass(restirReservoirSum, target, restirSourceWeight, misWeight,
                                restirRandom(400u + restirProposalIndex))) {
                            restirSelectedSeed = restirSuffixSeed;
                            restirSelectedTarget = target;
                        }
                    }
                    // Conditional RIS Equation 14: every original source retains its MIS
                    // mass even if identical tapes share one evaluation. Borrowed-only
                    // gather is valid here because identity tape shifts retain full support.
                    restirSumRadiance += suffixRadiance * gatherWeight;
                    restirSumIndirect += indirectDiffuseRadiance * gatherWeight;
                    restirSumSpecular += specularRadiance * gatherWeight;
                    restirSumTransmission += transmissionRadiance * gatherWeight;
                    restirSumDynamicDiffuse += (dynamicDiffuseDelta - prefixDynamicDiffuse) * gatherWeight;
                    restirSumDynamicSpecular += (dynamicSpecularDelta - prefixDynamicSpecular) * gatherWeight;
                    if (restirProposalIndex == 0u) {
                        // One geometric observation per independent prefix, regardless of
                        // how many tapes replay its shared first indirect hit.
                        if (diffuseHitDistance > 0.0) {
                            restirSumDiffuseDistance += diffuseHitDistance;
                            restirDiffuseDistanceCount++;
                        }
                        // NRD 4.17.3 specular hit-distance helper: minimum positive hitT.
                        if (specularHitDistance > 0.0 && (!(restirSumSpecularDistance > 0.0)
                                || specularHitDistance < restirSumSpecularDistance)) {
                            restirSumSpecularDistance = specularHitDistance;
                        }
                    }
                    restirAnyDynamic = restirAnyDynamic || pathContainsDynamicModel;
                    restirAnySkyDisk = restirAnySkyDisk || skySunDiskHit;
                    if (restirProposalIndex == 0u) {
                        restirSumRadiance += prefixRadiance / float(restirPrefixCount);
                        restirSumDynamicDiffuse += prefixDynamicDiffuse / float(restirPrefixCount);
                        restirSumDynamicSpecular += prefixDynamicSpecular / float(restirPrefixCount);
                    }
                    bool lastProposal = restirProposalIndex + 1u == restirProposalCount;
                    if (lastProposal && restirPrefixIndex == 0u && restirPrefixValid) {
                        uint offset = restirSuffixOffset(pixelIndex);
                        float suffixUcw = restirResolveUcw(restirReservoirSum, restirSelectedTarget, 1.0);
                        // Spare lanes expose range, numeric failure and donor acceptance
                        // to buffer inspection without new storage or GPU readback.
                        uint diagnostics = (restirReservoirSum.valid ? 0u : 1u)
                            | (restirSuffixDomainCount << 4u) | (restirSuffixCount << 8u)
                            | (restirSuffixReadCount << 12u);
                        restirCurrent.data[offset] = vec4(uintBitsToFloat(restirSelectedSeed), suffixUcw,
                            float(restirReservoirSum.exponent), float(diagnostics));
                        restirCurrent.data[offset + 1u] = vec4(restirSupportPosition,
                            uintBitsToFloat(restirNormal(restirSupportNormal)));
                    }
                    if (!lastProposal || restirPrefixIndex + 1u < restirPrefixCount) return;
                    radiance = restirSumRadiance;
                    indirectDiffuseRadiance = restirSumIndirect;
                    specularRadiance = restirSumSpecular;
                    transmissionRadiance = restirSumTransmission;
                    dynamicDiffuseDelta = restirSumDynamicDiffuse;
                    dynamicSpecularDelta = restirSumDynamicSpecular;
                    diffuseHitDistance = restirDiffuseDistanceCount > 0u
                        ? restirSumDiffuseDistance / float(restirDiffuseDistanceCount) : 0.0;
                    specularHitDistance = restirSumSpecularDistance;
                    pathContainsDynamicModel = restirAnyDynamic;
                    skySunDiskHit = restirAnySkyDisk;
                    // Camera-segment volume is outside suffix space and uses independent draws.
                    sampleBase = primeMakeSampleBase(gl_LaunchIDEXT.xy,
                        floatBitsToUint(camera.random.x), floatBitsToUint(camera.random.y), 0u, 0u);
                }
                // Diagnostic views bypass the denoiser by writing into the unfiltered direct/emission
                // composition terms and zeroing the filtered indirect/specular payload.
                uint debugView = uint(max(camera.parameters.z, 0.0) + 0.5);
                if (debugView != 0u) {
                    vec3 debugRadiance = debugView == 1u ? directDiffuseRadiance
                        : ((debugView == 2u || debugView == 8u) ? areaLightRadiance
                        : (debugView == 3u ? emissionRadiance : indirectDiffuseRadiance));
                    radiance = sqrt(max(debugRadiance, vec3(0.0)));
                    emissionRadiance = debugView == 3u ? debugRadiance : vec3(0.0);
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
                                                    probeView, probeNormal,
                                                    primaryRoughness, primaryMaterial.x);
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
                vec3 volumeDynamicDelta = vec3(0.0);
                vec3 atmosphereDiagnostic = vec3(0.0);
                vec3 outputRadiance = max(radiance, vec3(0.0));
#ifdef RTEST_ATMOSPHERE_LUT
                // Every pixel gets this frame's identity, including sky, debug and disabled volume.
                imageStore(physicalAerialL, ivec2(gl_LaunchIDEXT.xy), vec4(0.0));
#endif
                // Stage 1 hybrid: a physical background already includes its infinite atmosphere.
                // The physical finite segment supplies surface transmittance + a post-NRD solar
                // in-scatter AOV; the legacy segment keeps its RGB emission-channel path.
                // Water already applies Beer absorption along the traced path. The air LUT
                // cannot represent this medium; skip it until mixed water/air segments exist.
                if ((debugView == 0u || debugView == 10u || debugView == 11u)
                        && !cameraInWater
                        && camera.settings.z > 1.0e-4
                        && (physicalAtmosphereEnabled() || camera.environment.x > 1.0e-4)) {
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
                    float rain = clamp(camera.environmentState.y, 0.0, 1.0);
                    float thunder = clamp(camera.environmentState.z, 0.0, 1.0);
                    float weatherVisibility = clamp(1.0 - rain * 0.55 - thunder * 0.25, 0.25, 1.0);
                    bool physicalSegment = false;
#ifdef RTEST_ATMOSPHERE_LUT
                    if (physicalAtmosphereEnabled()) {
                        int quality = int(clamp(camera.settings.w, 1.0, 3.0) + 0.5);
                        int sampleCount = quality == 1 ? 4 : (quality == 3 ? 16 : 8);
                        float strength = clamp(camera.settings.z, 0.0, 2.0);
                        vec3 skyShadowCorrection;
                        integratePhysicalAtmosphereSegment(
                            camera.origin.xyz,
                            primaryRayDirection,
                            atmosphereDistance,
                            volumeSunDirection,
                            atmosphereCamera.parameters.x,
                            sampleCount,
                            !primaryHit,
                            camera.settings.y,
                            weatherVisibility,
                            sampleBase,
                            atmosphereTransmittance,
                            atmosphereInscatter,
                            skyShadowCorrection);
                        // Each celestial source was calibrated inside the shared integrator;
                        // do not multiply lunar energy by the solar intensity a second time.
                        atmosphereInscatter *= strength;
                        atmosphereDiagnostic = debugView == 11u
                            ? max(-skyShadowCorrection, vec3(0.0))
                            : atmosphereInscatter;
                        if (!primaryHit) {
                            float physicalSkyShare = camera.pbrParallaxSettings.z > 0.5
                                ? 1.0 - clamp(atmosphereCamera.parameters.y, 0.0, 1.0) : 1.0;
                            // SkyView already includes unshadowed infinite-path scattering and T.
                            // Replace its near direct source through a signed residual; do not
                            // multiply the complete sky by camera-segment T or add its L twice.
                            atmosphereInscatter = skyShadowCorrection * physicalSkyShare;
                            atmosphereTransmittance = vec3(1.0);
                        }
                        imageStore(physicalAerialL, ivec2(gl_LaunchIDEXT.xy),
                            vec4(atmosphereInscatter, primaryHit ? 0.0 : 1.0));
                        physicalSegment = true;
                    }
#endif
                    if (!physicalSegment) {
                        integrateAtmosphereSegment(
                            camera.origin.xyz,
                            primaryRayDirection,
                            atmosphereDistance,
                            volumeSunDirection,
                            atmosphereTransmittance,
                            atmosphereInscatter);
                    }
                    if (primaryHit || physicalSegment) {
#ifdef RTEST_ATMOSPHERE_LUT
                        if (physicalSegment) {
                            bool volumeDynamicOccluder;
                            volumeEmitterInscatter = samplePhysicalVolumeEmitter(
                                camera.origin.xyz, primaryRayDirection, atmosphereDistance,
                                atmosphereCamera.parameters.x, sampleBase, volumeDynamicOccluder, volumeDynamicDelta)
                                * clamp(camera.settings.z, 0.0, 2.0);
                            volumeDynamicDelta *= clamp(camera.settings.z, 0.0, 2.0);
                            primaryDynamicShadow = primaryDynamicShadow || volumeDynamicOccluder;
                        } else
#endif
                        if (primaryAreaLightValid || (restir.controls.x & 1u) != 0u) {
                            // Retain the legacy RGB-fog signal until the legacy atmosphere path
                            // is retired in stage 4. The physical path uses its own volume point.
                            vec3 volumePosition = camera.origin.xyz
                                + primaryRayDirection * (atmosphereDistance * 0.5);
                            AreaLightSample volumeLight = primaryAreaLight;
                            vec3 volumeVisibility = primaryAreaVisibility;
                            vec3 volumeStaticVisibility = primaryAreaStaticVisibility;
                            if ((restir.controls.x & 1u) != 0u) {
                                // A reservoir UCW is not the original light-selection PDF.
                                // Use an independent full-support volume proposal instead.
                                sampleBase.vertexIndex = 0u;
                                volumeLight = sampleVolumeAreaLight(volumePosition,
                                    primeSobolSample3D(sampleBase, PRIME_SAMPLE_EFFECT_VOLUME_EMITTER, 0u));
                                volumeVisibility = vec3(0.0);
                                volumeStaticVisibility = vec3(0.0);
                                if (volumeLight.pdf > 0.0) {
                                    vec3 emitterShadowOrigin = volumePosition + volumeLight.direction * 0.002;
                                    EmitterVisibilityRay emitterRay = prepareEmitterVisibilityRay(emitterShadowOrigin, volumeLight.position);
                                    traceEmitterVisibility(emitterShadowOrigin, emitterRay);
                                    bool volumeDynamic = shadowDynamicOccluder != 0u;
                                    volumeVisibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                                    volumeStaticVisibility = staticEmitterVisibility(emitterShadowOrigin,
                                        emitterRay, volumeDynamic, volumeVisibility);
                                    primaryDynamicShadow = primaryDynamicShadow || volumeDynamic;
                                }
                            }
                            vec3 unoccludedVolumeEmitter = sampleLegacyVolumeEmitter(
                                volumePosition, primaryRayDirection, volumeLight);
                            vec3 volumeEmitter = unoccludedVolumeEmitter * volumeVisibility;
                            float fogWeight = clamp(
                                1.0 - dot(atmosphereTransmittance, vec3(0.3333333)), 0.0, 1.0);
                            volumeEmitterInscatter = volumeEmitter * fogWeight * 0.25;
                            vec3 staticVolumeEmitter = unoccludedVolumeEmitter * volumeStaticVisibility;
                            volumeDynamicDelta = (volumeEmitter - staticVolumeEmitter) * fogWeight * 0.25;
                        }
                    }
                    outputRadiance = outputRadiance * atmosphereTransmittance
                        + volumeEmitterInscatter;
                    if (!physicalSegment) {
                        outputRadiance += atmosphereInscatter;
                    }
                    atmosphereApplied = true;
                }
                // NRD reconstructs surface color from split AOVs after RayGen. Carry the
                // same atmosphere through those signals so their composite cannot erase surface fog.
                // Camera-segment L has no surface correspondence; retain its dedicated AOV.
                // Shadow-budget sampling adds noise but must not borrow surface NRD history.
                if (atmosphereApplied) {
                    directDiffuseRadiance *= atmosphereTransmittance;
                    directSpecularRadiance *= atmosphereTransmittance;
                    dynamicDiffuseDelta *= atmosphereTransmittance;
                    dynamicSpecularDelta *= atmosphereTransmittance;
                    dynamicDiffuseDelta += volumeDynamicDelta;
                    areaDirectDiffuseRadiance *= atmosphereTransmittance;
                    areaDirectSpecularRadiance *= atmosphereTransmittance;
                    // Local block-light volume scattering is a one-sample stochastic estimate. It
                    // has a valid primary receiver/depth on this path, so carry it in NRD's diffuse
                    // history instead of the unfiltered emission channel. Solar/ambient atmosphere
                    // retains its dedicated camera-segment AOV and bypasses surface denoising.
                    indirectDiffuseRadiance = indirectDiffuseRadiance * atmosphereTransmittance
                        + volumeEmitterInscatter;
                    specularRadiance *= atmosphereTransmittance;
                    transmissionRadiance *= atmosphereTransmittance;
                    emissionRadiance *= atmosphereTransmittance;
#ifdef RTEST_ATMOSPHERE_LUT
                    if (!physicalAtmosphereEnabled()) {
                        // Camera-segment in-scatter has no surface correspondence and must not enter
                        // NRD's surface history. The emission AOV is the existing unfiltered channel
                        // for the legacy RGB path; the physical solar L is added post-NRD by
                        // AerialPerspectiveComposite and must not be double-added here.
                        emissionRadiance += atmosphereInscatter;
                    }
#else
                    emissionRadiance += atmosphereInscatter;
#endif
                }
                if (debugView == 10u || debugView == 11u) {
                    // Diagnostic exposure only: normal rendering and the physical medium are
                    // unchanged. Display the actual per-pixel integral without surface/sky color.
                    vec3 diagnostic = atmosphereDiagnostic;
                    if (debugView == 10u) diagnostic += volumeEmitterInscatter;
                    diagnostic = max(diagnostic, vec3(0.0)) * 100.0;
                    outputRadiance = diagnostic;
                    emissionRadiance = diagnostic;
                    directDiffuseRadiance = vec3(0.0);
                    directSpecularRadiance = vec3(0.0);
                    areaDirectDiffuseRadiance = vec3(0.0);
                    areaDirectSpecularRadiance = vec3(0.0);
                    indirectDiffuseRadiance = vec3(0.0);
                    specularRadiance = vec3(0.0);
                    transmissionRadiance = vec3(0.0);
#ifdef RTEST_ATMOSPHERE_LUT
                    imageStore(physicalAerialL, ivec2(gl_LaunchIDEXT.xy), vec4(0.0));
#endif
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
                        || pathContainsDynamicModel || primaryMaterial.w > 0.5 || (primaryDynamicFlags & 16u) != 0u)
                    ? 1.0 : 0.0;
                float transparency = (primaryMaterial.w > 0.5 || (primaryDynamicFlags & 4u) != 0u)
                    ? 1.0 : 0.0;
                imageStore(fsrMotion, outputPixel, vec4(motion, 0.0, 0.0));
                imageStore(fsrDepth, outputPixel, vec4(depthValue, 0.0, 0.0, 0.0));
                // Frame-uniform mode: -1=off, 1=NRD, 2=DLSS RR. Do not generate
                // eleven unconsumed guide images while NRD is disabled.
                if (camera.dynamicParameters.w >= 0.0) {
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
                // Unsampled lobes stay at zero. Camera distance is viewZ, not path hit distance;
                // secondary environment misses retain the 64-block sentinel assigned above.
                float diffuseSignalDistance = diffuseHitDistance;
                float specularSignalDistance = specularHitDistance;
                // A primary miss is environment lighting, not a denoisable surface signal.
                // Keep it out of NRD history; the composite pass preserves it verbatim.
                // Denoise illumination with static visibility. Add the signed entity shadow difference
                // after reconstruction instead of allowing its receiver pixel to bypass every light.
                if (debugView != 0u) {
                    dynamicDiffuseDelta = vec3(0.0);
                    dynamicSpecularDelta = vec3(0.0);
                }
                vec3 directAovRadiance = dynamicDiffuseDelta + dynamicSpecularDelta;
                vec3 filteredDiffuseRadiance = directDiffuseRadiance
                    + indirectDiffuseRadiance + areaDirectDiffuseRadiance - dynamicDiffuseDelta;
                float indirectDiffuseSignalActive = primaryHit
                    && dot(filteredDiffuseRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0;
                // Perfect mirrors still use exact delta reflection for geometry, while every sampled
                // surface-light lobe enters the matching NRD channel so visibility can accumulate.
                vec3 nrdDiffuseRadiance = filteredDiffuseRadiance;
                vec3 nrdSpecularRadiance = directSpecularRadiance
                    + specularRadiance + transmissionRadiance
                    + areaDirectSpecularRadiance - dynamicSpecularDelta;
                // Zero radiance is a valid Monte Carlo observation (occluded light, rejected
                // emitter or zero lobe), not a missing surface. Preserve its history eligibility
                // so the composite cannot replace reconstructed lighting with raw black speckles.
                float nrdDiffuseSignalActive = primaryHit ? 1.0 : 0.0;
                float nrdSpecularSignalActive = primaryHit ? 1.0 : 0.0;
                // NEE radiance remains valid even when no BSDF guide observation exists.
                // A DI emitter selected for total lighting is not a BRDF-lobe guide sample.
                // Leave missing lobe hitT at zero for NRD hit-distance reconstruction.
                // Raw NRD images carry linear radiance and unnormalized path hit distance.
                // nrd_motion.comp demodulates and converts to YCoCg with camera-correct guides.
                float diffuseGeneratorHitDistance = nrdDiffuseSignalActive > 0.5
                    ? diffuseSignalDistance : -1.0;
                float specularGeneratorHitDistance = nrdSpecularSignalActive > 0.5
                    ? specularSignalDistance : -1.0;
                if (camera.dynamicParameters.w > 1.5) {
                    // RR receives the nonnegative secondary guide distance.
                    specularGeneratorHitDistance = max(specularHitDistance, 0.0);
                }
                imageStore(nrdNoisyDiffuse, outputPixel,
                    vec4(nrdDiffuseRadiance, diffuseGeneratorHitDistance));
                imageStore(nrdNoisySpecular, outputPixel,
                    vec4(nrdSpecularRadiance, specularGeneratorHitDistance));
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
                    | (primaryDynamicSlot != 0xffffffffu ? 256u : 0u)
                    | (pathContainsDynamicModel && (uint(camera.pbrParallaxSettings.w + 0.5) & 32u) == 0u ? 512u : 0u);
                // Transparent continuation is classified as NRD specular by the preparation pass;
                // keep the primary material valid so its color-filtered path can accumulate in the
                // same temporal history instead of falling back to the noisy raw image.
                imageStore(nrdMaterial, outputPixel,
                    vec4(primaryBaseColor, primaryHit ? primaryRoughness : -1.0));
                imageStore(nrdSpecularMaterial, outputPixel,
                    vec4(primaryNormal, float(nrdMaterialFlags)));
                // Reuse DirectDiffuse for the signed current-frame entity visibility residual.
                // Static finite-sun, moon, sky and area-light signals use the NRD histories above;
                // the composite adds this residual after reconstruction.
                imageStore(nrdDirectDiffuse, outputPixel,
                    vec4(directAovRadiance, primaryDynamicShadow ? 1.0 : 0.0));
                imageStore(nrdIndirectDiffuse, outputPixel,
                    vec4(filteredDiffuseRadiance, indirectDiffuseSignalActive));
                imageStore(nrdEmission, outputPixel,
                    vec4(emissionRadiance,
                        dot(emissionRadiance, vec3(1.0)) > 1.0e-5 ? 1.0 : 0.0));
                }
                imageStore(fsrReactive, outputPixel, vec4(reactive));
                imageStore(fsrTransparency, outputPixel, vec4(transparency));
                // Dedicated diagnostic uint; every display pixel already went to fsrSceneColor.
                if (all(equal(gl_LaunchIDEXT.xy, gl_LaunchSizeEXT.xy / 2u))) {
                    result.pixels[pixelIndex] = packUnorm4x8(vec4(clamp(outputRadiance, vec3(0.0), vec3(1.0)), 1.0));
                }
            }
            // RESTIR_MAIN
            """);
}
