package com.rtest.client;

final class RayTracingShaderRaygen {
    private static String joinShaderParts(String first, String middle, String bodyTail, String last) {
            // Insert after #version/extensions, before any query call. Keep the legacy ABI intact.
            String extension = "#extension GL_EXT_ray_tracing : require\n";
            return (first.replace(extension, extension + RayTracingAtmosphereShader.GLSL
                + RayTracingAtmosphereSegmentShader.GLSL + RayTracingMoonShader.GLSL)
                + middle + bodyTail + last).replace("// SKY_IMPORTANCE_FUNCTIONS", SkyImportanceShader.GLSL)
                    .replace("// PERSISTENT_INDIRECT_FUNCTIONS", PersistentRtShader.GLSL);
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
            // Keep the full lighting path enabled; set true only for direct-sun diagnostics.
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
                uint staticBoundary;
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
            const uint PRIME_SAMPLE_EFFECT_VOLUME_SKY = 9u;
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
                    vec3 lunarScattering = vec3(0.0);
                    if (lunarValid && moon.y > 0.0) {
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
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
                        PhysicalAtmIndirectHeight indirectHeight = physicalAtmIndirectHeight(hp);
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
            struct BsdfValue { vec3 f; vec3 diffuse; float pdf; };
            // Exact per-channel partition of the full mixture estimator, not its chosen proposal.
            // Zero-energy channels carry no diffuse signal; avoid epsilon bias for dark textures.
            vec3 bsdfDiffuseShare(BsdfValue value) {
                return vec3(
                    value.f.r > 0.0 ? value.diffuse.r / value.f.r : 0.0,
                    value.f.g > 0.0 ? value.diffuse.g / value.f.g : 0.0,
                    value.f.b > 0.0 ? value.diffuse.b / value.f.b : 0.0);
            }
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
            vec2 prepareBsdfDirectionalEnergy(vec3 normal, vec3 viewDirection,
                    float roughness, float metallic) {
                if (metallic >= 0.999 || roughness == 0.0) return vec2(1.0, 0.0);
                return primeDefaultGgxDirectionalEnergy(max(dot(normal, viewDirection), 0.0),
                    max(roughness * roughness, 0.025));
            }
            float preparedSpecularSampleProbability(vec2 energy, float roughness, float metallic) {
                if (metallic >= 0.999 || roughness == 0.0) return 1.0;
                return clamp(energy.x / max(energy.x + energy.y, PRIME_BSDF_EPSILON), 0.05, 0.95);
            }
            float primeDefaultSpecularSampleProbability(vec3 viewDirection,
                    vec3 normal, float roughness, float metallic) {
                return preparedSpecularSampleProbability(
                    prepareBsdfDirectionalEnergy(normal, viewDirection, roughness, metallic), roughness, metallic);
            }
            BsdfValue evaluateBsdf(vec3 normal, vec3 wi, vec3 wo, vec3 baseColor,
                    float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,
                    float diffuseSamplingProbability, float specularSamplingProbability, vec2 directionalEnergy) {
                BsdfValue result;
                result.f = vec3(0.0);
                result.diffuse = vec3(0.0);
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
                // Visible-normal proposal: D(h) G1(wi) |wi.h| / n.wi,
                // followed by the reflection Jacobian 1 / (4 |wi.h|).
                float specPdf = ggxD(nDotH, alpha) * ggxG1(nDotI, alpha) / (4.0 * nDotI);
                // A perfect mirror is a delta distribution, not a broadened GGX lobe.
                // Its energy is sampled explicitly by the continuation branch, never by NEE.
                if (roughness == 0.0) {
                    specular = 0.0;
                    specPdf = 0.0;
                }
                // Both lobes and all lights share two prepared receiver-dependent values.
                // Their lifetime ends at this vertex; directional Fresnel/PDF remain per light.
                float diffuseEnergy = 1.0;
                float specularEnergy = 1.0;
                if (!(metallic >= 0.999 || roughness == 0.0)) {
                    float resolvedEnergy = max(directionalEnergy.x + directionalEnergy.y,
                        PRIME_BSDF_EPSILON);
                    diffuseEnergy = directionalEnergy.y / resolvedEnergy;
                    specularEnergy = 1.0 / resolvedEnergy;
                }
                result.diffuse = diffuseMaterialWeight * baseColor * (vec3(1.0) - F)
                    * diffuseEnergy / BSDF_PI;
                result.f = result.diffuse + F * specular * specularEnergy;
                result.pdf = diffuseSamplingProbability * nDotO / BSDF_PI + specularSamplingProbability * specPdf;
                return result;
            }
            // Isolated diagnostic receivers still prepare their own context.
            BsdfValue evaluateBsdf(vec3 normal, vec3 wi, vec3 wo, vec3 baseColor,
                    float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,
                    float diffuseSamplingProbability, float specularSamplingProbability) {
                return evaluateBsdf(normal, wi, wo, baseColor, roughness, metallic, reflectivity,
                    diffuseMaterialWeight, diffuseSamplingProbability, specularSamplingProbability,
                    prepareBsdfDirectionalEnergy(normal, wi, roughness, metallic));
            }
            // iterationRP-inspired artistic thin-foliage sunlight, not volumetric SSS.
            float vegetationSunResponse(vec3 normal, vec3 viewDirection, vec3 lightDirection,
                    float roughness, float kind) {
                vec3 shadingNormal = kind == 1.0
                    ? normalize(mix(normal, vec3(0.0, 1.0, 0.0), 0.49)) : normal;
                vec3 halfVector = viewDirection + lightDirection;
                float halfLength2 = dot(halfVector, halfVector);
                float lightHalf = halfLength2 > 1.0e-8
                    ? clamp(dot(lightDirection, halfVector * inversesqrt(halfLength2)), 0.0, 1.0) : 0.0;
                float nl = clamp(dot(shadingNormal, lightDirection), 0.0, 1.0);
                float nv = clamp(dot(shadingNormal, viewDirection), 0.0, 1.0);
                float grazing = 0.5 + 2.0 * roughness * lightHalf * lightHalf;
                float burley = nl * (1.0 + (grazing - 1.0) * pow(1.0 - nl, 5.0))
                    * (1.0 + (grazing - 1.0) * pow(1.0 - nv, 5.0)) / BSDF_PI;
                // ITRP blends a BRDF response (already / pi) toward 0.6.
                return mix(burley, 0.6, kind == 1.0 ? 0.20 : 0.25);
            }
            // Heitz visible GGX normals: stretch view, sample projected disk, unstretch.
            vec3 sampleGgx(vec3 normal, vec3 viewDirection, float roughness, vec2 sampleValue) {
                float alpha = max(roughness * roughness, 0.025);
                vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0))
                    : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 bitangent = cross(normal, tangent);
                vec3 view = vec3(dot(viewDirection, tangent), dot(viewDirection, bitangent),
                    max(dot(viewDirection, normal), 0.0));
                vec3 stretched = normalize(vec3(alpha * view.xy, view.z));
                float lensq = dot(stretched.xy, stretched.xy);
                vec3 t1 = lensq > 0.0 ? vec3(-stretched.y, stretched.x, 0.0) * inversesqrt(lensq)
                    : vec3(1.0, 0.0, 0.0);
                vec3 t2 = cross(stretched, t1);
                float radius = sqrt(sampleValue.x);
                float phi = 2.0 * BSDF_PI * sampleValue.y;
                float x = radius * cos(phi);
                float y = radius * sin(phi);
                float blend = 0.5 * (1.0 + stretched.z);
                y = (1.0 - blend) * sqrt(max(1.0 - x * x, 0.0)) + blend * y;
                vec3 projected = x * t1 + y * t2
                    + sqrt(max(1.0 - x * x - y * y, 0.0)) * stretched;
                vec3 halfLocal = normalize(vec3(alpha * projected.xy, max(projected.z, 0.0)));
                return normalize(tangent * halfLocal.x + bitangent * halfLocal.y + normal * halfLocal.z);
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
            void traceEmitterVisibility(vec3 shadowOrigin, vec3 target) {
                shadowTransmittance = vec3(1.0);
                shadowDynamicOccluder = 0u;
                vec3 delta = target - shadowOrigin;
                float distance = length(delta);
                float coordinateScale = max(max(max(abs(shadowOrigin.x), abs(shadowOrigin.y)), abs(shadowOrigin.z)),
                    max(max(abs(target.x), abs(target.y)), abs(target.z)));
                float endpointMargin = max(0.0001, 4.0 * 1.1920929e-7 * max(coordinateScale, distance));
                float tMax = distance - endpointMargin;
                if (!(tMax > 0.001)) return;
                traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                    1, 1, 1, shadowOrigin, 0.001, delta / distance, tMax, 1);
            }
            vec3 staticShadowVisibility(vec3 origin, vec3 direction, float tMax, bool dynamicHit, vec3 actual) {
                if (!dynamicHit || camera.dynamicParameters.w < 0.0) return actual;
                shadowExcludeDynamic = 1u;
                shadowTransmittance = vec3(1.0);
                shadowDynamicOccluder = 0u;
                if (tMax > 0.001) traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT,
                    SECONDARY_RAY_MASK, 1, 1, 1, origin, 0.001, direction, tMax, 1);
                shadowExcludeDynamic = 0u;
                return mix(vec3(1.0), shadowTransmittance, camera.settings.y);
            }
            vec3 staticEmitterVisibility(vec3 origin, vec3 target, bool dynamicHit, vec3 actual) {
                if (!dynamicHit) return actual;
                vec3 delta = target - origin;
                float distance = length(delta);
                float scale = max(max(max(abs(origin.x), abs(origin.y)), abs(origin.z)),
                    max(max(abs(target.x), abs(target.y)), abs(target.z)));
                float margin = max(0.0001, 4.0 * 1.1920929e-7 * max(scale, distance));
                return staticShadowVisibility(origin, delta / max(distance, 1.0e-8), distance - margin, true, actual);
            }
            vec3 sampleLegacyVolumeEmitter(vec3 volumePosition, vec3 viewRay,
                    AreaLightSample light, vec3 visibility) {
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
                vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric)
                    * visibility;
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
                    float volumeHeightKm = physicalAtmHeightAt(eyeHeightKm, viewRay.y, distanceKm);
                    PhysicalAtmMedium medium = physicalAtmMedium(volumeHeightKm);
                    vec4 viewT = physicalAtmLocalSpectralTransmittance(eyeHeightKm, viewRay, distanceKm);
                    AreaLightSample light = sampleVolumeAreaLight(volumePosition,
                        primeSobolSample3D(sampleBase, PRIME_SAMPLE_EFFECT_VOLUME_EMITTER, uint(i)));
                    if (light.pdf > 0.0) {
                        vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric);
                        if (any(greaterThan(sourceRadiance, vec3(0.0)))) {
                            traceEmitterVisibility(volumePosition + light.direction * 0.002, light.position);
                            bool sampleDynamic = shadowDynamicOccluder != 0u;
                            dynamicOccluder = dynamicOccluder || sampleDynamic;
                            vec3 visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
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
                            vec3 staticVisibility = staticEmitterVisibility(volumePosition + light.direction * 0.002,
                                light.position, sampleDynamic, visibility);
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
                AreaLightSample light = sampleAreaLight(surfacePosition, sampleValue);
                if (!(light.pdf > 0.0)) return result;
                result.light = light;
                float cosine = max(dot(normal, light.direction), 0.0);
                if (!(cosine > 0.0)) return result;
                vec3 sourceRadiance = evaluateEmitter(light.emitterIndex, light.barycentric);
                if (all(equal(sourceRadiance, vec3(0.0)))) return result;
                traceEmitterVisibility(surfacePosition + normal * 0.002, light.position);
                result.visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                result.dynamicOccluder = shadowDynamicOccluder != 0u;
                result.valid = true;
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
                vec3 scale = radiance * cosine * misWeight / max(light.pdf, 1.0e-6);
                // evaluateBsdf already resolved both material lobes with the same energy curve.
                result.diffuse = scale * bsdf.diffuse;
                result.specular = max(scale * (bsdf.f - bsdf.diffuse), vec3(0.0));
                vec3 staticVisibility = staticEmitterVisibility(surfacePosition + normal * 0.002,
                    light.position, result.dynamicOccluder && separateDynamic, result.visibility);
                result.staticVisibility = staticVisibility;
                vec3 deltaScale = sourceRadiance * (result.visibility - staticVisibility)
                    * cosine * misWeight / max(light.pdf, 1.0e-6);
                result.dynamicDiffuseDelta = deltaScale * bsdf.diffuse;
                result.dynamicSpecularDelta = deltaScale * max(bsdf.f - bsdf.diffuse, vec3(0.0));
                return result;
            }
            // Fluid side boundaries are inset only 1 mm from adjacent block faces. The
            // opaque 3 mm spawn bias must not jump across those faces after a dielectric hit.
            const float DIELECTRIC_RAY_MIN_OFFSET = 0.0001;
            const float DIELECTRIC_RAY_ERROR_SCALE = 4.76837158203125e-7;
            const float DIELECTRIC_RAY_T_MIN = 0.0001;
            float dielectricRayOffset(vec3 point) {
                float maxCoord = max(max(abs(point.x), abs(point.y)), abs(point.z));
                return max(DIELECTRIC_RAY_MIN_OFFSET, maxCoord * DIELECTRIC_RAY_ERROR_SCALE);
            }
            // PERSISTENT_INDIRECT_FUNCTIONS
            void main() {
                uint pixelIndex = gl_LaunchIDEXT.x + gl_LaunchSizeEXT.x * gl_LaunchIDEXT.y;
                prtSampledInvocation = (pixelIndex & 255u) == 0u;
                bool persistentTraining = false;
                uint persistentKey[10];
                pathPayload.staticBoundary = 0u;
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
                // A single active medium covers the closed vanilla glass/water surfaces. Nested
                // media remain a bounded approximation, but each segment gets physical attenuation.
                bool cameraInWater = (uint(camera.pbrParallaxSettings.w + 0.5) & 4u) != 0u;
                vec3 mediumAbsorption = cameraInWater ? vec3(0.09, 0.045, 0.015) : vec3(0.0);
                bool insideMedium = cameraInWater;
                vec3 radiance = vec3(0.0);
                vec3 diffuseRadiance = vec3(0.0);
                vec3 specularRadiance = vec3(0.0);
                vec3 directDiffuseRadiance = vec3(0.0);
                vec3 directSpecularRadiance = vec3(0.0);
                shadowExcludeDynamic = 0u;
                vec3 dynamicDiffuseDelta = vec3(0.0);
                vec3 dynamicSpecularDelta = vec3(0.0);
                float primaryDirectDistance = 0.0;
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
                vec3 primaryNormal = vec3(0.0, 0.0, 1.0);
                AreaLightSample primaryAreaLight;
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
                int giBounces = clamp(int(camera.parameters.w + 0.5), 1, 4);
                int maxPathSegments = camera.up.w > 0.5 ? 1 : 1 + giBounces;
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
                for (int bounce = 0; bounce < 5; bounce++) {
                    sampleBase.vertexIndex = uint(bounce);
                    // Consecutive temporal indices preserve Sobol coverage. Vertex/effect/dimension
                    // seeds already isolate each bounce and sampling domain.
                    sampleBase.sampleIndex = floatBitsToUint(camera.random.x);
                    pathPosition = vec4(0.0);
                    pathLocalPosition = vec4(0.0);
                    pathDynamicSlot = 0xffffffffu;
                    pathEmitterIndex = 0xffffffffu;
                    pathNormal = vec4(0.0);
                    pathBaseColorRoughness = vec4(0.0);
                    pathMaterial = vec4(0.0);
                    pathOpticalLighting = vec4(0.0);
                    prtCount(bounce == 0 ? 4u : 5u);
                    traceRayEXT(
                        topLevelAS,
                        pathPayload.staticBoundary != 0u ? gl_RayFlagsNoOpaqueEXT : 0u,
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
                        if (camera.up.w > 1.5) break;
                        if (insideMedium) throughput *= exp(-mediumAbsorption * max(camera.sun.w, 0.0));
                        if (bounce == 1) {
                            if (!primaryTransmissionPath
                                    && dot(throughput * primaryDiffuseShare, vec3(1.0)) > 0.0) {
                                diffuseHitDistance = nrdHitDistanceScale;
                            }
                            if (dot(throughput * (vec3(1.0) - primaryDiffuseShare), vec3(1.0)) > 0.0) {
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
                                diffuseRadiance += skyRadiance * primaryDiffuseShare;
                                indirectDiffuseRadiance += skyRadiance * primaryDiffuseShare;
                                specularRadiance += skyRadiance * (vec3(1.0) - primaryDiffuseShare);
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
                        if (!primaryTransmissionPath
                                && dot(throughput * primaryDiffuseShare, vec3(1.0)) > 0.0) {
                            diffuseHitDistance = firstBounceDistance;
                        }
                        if (dot(throughput * (vec3(1.0) - primaryDiffuseShare), vec3(1.0)) > 0.0) {
                            specularHitDistance = firstBounceDistance;
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
                    if (camera.up.w > 1.5) {
                        radiance = baseColor;
                        emissionRadiance = baseColor;
                        break;
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
                    float dielectricF0 = pow((ior - 1.0) / (ior + 1.0), 2.0);
                    float fresnel = dielectricF0 + (1.0 - dielectricF0) * pow(1.0 - cosTheta, 5.0);
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
                    float vegetationKind = transmission ? 0.0 : max(-pathMaterial.w, 0.0);
                    vec3 sunDiffuseContribution = vec3(0.0);
                    vec3 sunSpecularContribution = vec3(0.0);
                    bool sunDynamicOccluder = false;
                    // NEE and BSDF miss use the same actual continuation mixture.
                    float sunSpecularProbability = specularProbability;
                    float sunDiffuseProbability = diffuseProbability;
                    for (int sunIndex = 0; sunIndex < 16; sunIndex++) {
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
                        vec3 sunResponse = physicalAtmosphereEnabled()
                            ? physicalAtmosphereSunTransmittance(sampledSunDirection)
                            : sunTemperatureColor * daylight;
                        if (all(equal(sunResponse, vec3(0.0)))) continue;
                        shadowTransmittance = vec3(1.0);
                        shadowDynamicOccluder = 0u;
                        bool needsSunShadow = (physicalAtmosphereEnabled() || daylight > 0.001)
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
                        sunDynamicOccluder = sunDynamicOccluder || shadowDynamicOccluder != 0u;
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
                        if (shadowDynamicOccluder != 0u) {
                            vec3 staticVisibility = staticShadowVisibility(pathPosition.xyz + normal *
                                (vegetationKind > 0.0 && dot(normal, sampledSunDirection) < 0.0 ? -0.002 : 0.002),
                                sampledSunDirection, camera.sun.w, true, shadowFactor);
                            vec3 deltaLight = throughput * sunResponse * camera.settings.x * weatherVisibility
                                * (shadowFactor - staticVisibility) / float(sunSampleCount);
                            vec3 diffuseResponse = sunBsdf.diffuse * directCosine * sunMisWeight;
                            if (vegetationKind > 0.0) diffuseResponse += diffuseMaterialWeight * baseColor
                                * (1.0 - reflectivity) * foliageResponse - sunBsdf.diffuse * directCosine;
                            vec3 diffuseDelta = diffuseResponse * deltaLight;
                            vec3 specularDelta = max(sunBsdf.f - sunBsdf.diffuse, vec3(0.0))
                                * directCosine * sunMisWeight * deltaLight;
                            if (bounce == 0) {
                                dynamicDiffuseDelta += diffuseDelta;
                                dynamicSpecularDelta += specularDelta;
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
                    if (moonIrradiance(camera.jitter.w) > 0.0 && moonDirectionValid(moonDirection)) {
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
                            traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT, SECONDARY_RAY_MASK,
                                1, 1, 1, pathPosition.xyz + normal * 0.002, 0.001,
                                sampledMoonDirection, camera.sun.w, 1);
                            vec3 lunarVisibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                            moonDynamicOccluder = shadowDynamicOccluder != 0u;
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
                                vec3 staticVisibility = staticShadowVisibility(pathPosition.xyz + normal * 0.002,
                                    sampledMoonDirection, camera.sun.w, true, lunarVisibility);
                                vec3 deltaLight = throughput * lunarLi * moonCosine * weatherVisibility
                                    * moonNeeMisWeight / moonPdf * (lunarVisibility - staticVisibility);
                                vec3 diffuseDelta = moonBsdf.diffuse * deltaLight;
                                vec3 specularDelta = max(moonBsdf.f - moonBsdf.diffuse, vec3(0.0)) * deltaLight;
                                if (bounce == 0) {
                                    dynamicDiffuseDelta += diffuseDelta;
                                    dynamicSpecularDelta += specularDelta;
                                } else if (primaryTransmissionPath) {
                                    dynamicSpecularDelta += diffuseDelta + specularDelta;
                                } else {
                                    dynamicDiffuseDelta += (diffuseDelta + specularDelta) * primaryDiffuseShare;
                                    dynamicSpecularDelta += (diffuseDelta + specularDelta) * (vec3(1.0) - primaryDiffuseShare);
                                }
                            }
                        }
                    }
                    vec3 areaDirectDiffuseContribution = vec3(0.0);
                    vec3 areaDirectSpecularContribution = vec3(0.0);
                    vec3 areaSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_AREA_LIGHT, uint(bounce));
                    bool areaNeeEnabled = !transmission;
                    bool skyNeeEnabled = bounce == 0 && !transmission;
                    if (areaNeeEnabled) {
                        AreaDirectSplit areaDirect = estimateAreaDirect(
                            pathPosition.xyz, normal, viewDirection, baseColor,
                            roughness, metallic, reflectivity, areaSample, receiverEnergy, true);
                        areaDirectDiffuseContribution = areaDirect.diffuse;
                        areaDirectSpecularContribution = areaDirect.specular;
                        if (bounce == 0) {
                            dynamicDiffuseDelta += throughput * areaDirect.dynamicDiffuseDelta;
                            dynamicSpecularDelta += throughput * areaDirect.dynamicSpecularDelta;
                            if (areaDirect.valid) primaryDirectDistance = areaDirect.light.distance;
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
                    if (skyNeeEnabled) {
                        vec3 sampledSkyDirection = sampleSkyNee(normal,
                            primeSobolSample2D(sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_SKY, 0u));
                        float skyCosine = max(dot(normal, sampledSkyDirection), 0.0);
                        float skyPdf = skyNeePdf(normal, sampledSkyDirection);
                        if (skyCosine > 0.0 && skyPdf > 0.0) {
                            shadowTransmittance = vec3(1.0);
                            shadowDynamicOccluder = 0u;
                            traceRayEXT(topLevelAS, gl_RayFlagsTerminateOnFirstHitEXT,
                                SECONDARY_RAY_MASK, 1, 1, 1,
                                pathPosition.xyz + normal * 0.002, 0.001,
                                sampledSkyDirection, camera.sun.w, 1);
                            vec3 skyVisibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);
                            bool skyDynamicOccluder = shadowDynamicOccluder != 0u;
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
                            primaryDynamicShadow = primaryDynamicShadow || skyDynamicOccluder;
                            if (skyDynamicOccluder) {
                                vec3 staticVisibility = staticShadowVisibility(pathPosition.xyz + normal * 0.002,
                                    sampledSkyDirection, camera.sun.w, true, skyVisibility);
                                vec3 deltaLight = throughput * incidentSky * (skyCosine / skyPdf)
                                    * skyNeeMisWeight * (skyVisibility - staticVisibility);
                                dynamicDiffuseDelta += skyBsdf.diffuse * deltaLight;
                                dynamicSpecularDelta += max(skyBsdf.f - skyBsdf.diffuse, vec3(0.0)) * deltaLight;
                            }
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
                        diffuseRadiance += throughput * directDiffuseContribution;
                        // Keep the finite-sun sample in its own lobe accumulators. The final
                        // diffuse/specular AOVs include these alongside area-light samples; this
                        // is not an additional unfiltered sun layer in either denoiser.
                        directDiffuseRadiance += throughput * (sunDiffuseContribution + moonDiffuseContribution
                            + skyDirectDiffuseContribution);
                        directSpecularRadiance += throughput * (sunSpecularContribution + moonSpecularContribution
                            + skyDirectSpecularContribution);
                        primaryDynamicShadow = primaryDynamicShadow || sunDynamicOccluder || moonDynamicOccluder;
                        if (!(primaryDirectDistance > 0.0)) primaryDirectDistance = nrdHitDistanceScale;
                    } else if (primaryTransmissionPath) {
                        transmissionRadiance += localRadiance;
                    } else {
                        // The shared throughput already includes RR, media and spectral weights.
                        // Partition by evaluated primary lobes, never by the sampled proposal label.
                        diffuseRadiance += localRadiance * primaryDiffuseShare;
                        indirectDiffuseRadiance += localRadiance * primaryDiffuseShare;
                        specularRadiance += localRadiance * (vec3(1.0) - primaryDiffuseShare);
                    }
                    if (bounce + 1 >= maxPathSegments) {
                        break;
                    }
                    vec3 scatterSample = primeSobolSample3D(
                        sampleBase, PRIME_SAMPLE_EFFECT_SCATTER_BSDF, uint(bounce));
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
                        // Cache the conditional diffuse-proposal tail with the original PBR weights.
                        // Specular proposals, entities, vegetation, water and special primary materials stay live.
                        bool persistentEligible = bounce == 0 && prt.words[0] != 0u && !cameraInWater
                            && !transmission && pathMaterial.w >= 0.0 && metallic <= 0.001
                            && roughness >= 0.8 && emission == 0.0 && primaryDynamicSlot == 0xffffffffu
                            && camera.parameters.z == 0.0;
                        if (persistentEligible) {
                            prtKey(primaryPosition, normal, viewDirection, baseColor, roughness, reflectivity, persistentKey);
                            vec3 cachedDiffuse, cachedSpecular;
                            bool forceRefresh = prt.words[4] != 0u && ((pixelIndex + prt.words[3]) & 15u) == 0u;
                            bool cacheHit = prtLookup(persistentKey, cachedDiffuse, cachedSpecular);
                            persistentTraining = (!cacheHit || forceRefresh) && prtReserve(persistentKey);
                            if (cacheHit && !persistentTraining) {
                                // A cache value is reused evidence, not a new NRD Monte Carlo observation.
                                radiance += cachedDiffuse + cachedSpecular;
                                emissionRadiance += cachedDiffuse + cachedSpecular;
                                break;
                            }
                            prtCount(cacheHit ? 3u : 2u);
                            if (persistentTraining) {
                                // Static indirect target; unreserved misses use the full live fallback.
                                pathPayload.staticBoundary = uint(camera.dynamicParameters.x + 0.5);
                                shadowExcludeDynamic = 1u;
                            }
                        }
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
                    if (bounce == 0) primaryTransmissionPath = selectedTransmissionPath;
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
                if (persistentTraining) prtStoreReserved(persistentKey, indirectDiffuseRadiance, specularRadiance);
                pathPayload.staticBoundary = 0u;
                shadowExcludeDynamic = 0u;
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
                        if (primaryAreaLightValid) {
                            // Retain the legacy RGB-fog signal until the legacy atmosphere path
                            // is retired in stage 4. The physical path uses its own volume point.
                            vec3 volumePosition = camera.origin.xyz
                                + primaryRayDirection * (atmosphereDistance * 0.5);
                            vec3 volumeEmitter = sampleLegacyVolumeEmitter(
                                volumePosition, primaryRayDirection,
                                primaryAreaLight, primaryAreaVisibility);
                            float fogWeight = clamp(
                                1.0 - dot(atmosphereTransmittance, vec3(0.3333333)), 0.0, 1.0);
                            volumeEmitterInscatter = volumeEmitter * fogWeight * 0.25;
                            vec3 staticVolumeEmitter = sampleLegacyVolumeEmitter(volumePosition, primaryRayDirection,
                                primaryAreaLight, primaryAreaStaticVisibility);
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
                        || primaryMaterial.w > 0.5 || (primaryDynamicFlags & 16u) != 0u)
                    ? 1.0 : 0.0;
                float transparency = (primaryMaterial.w > 0.5 || (primaryDynamicFlags & 4u) != 0u)
                    ? 1.0 : 0.0;
                imageStore(fsrMotion, outputPixel, vec4(motion, 0.0, 0.0));
                imageStore(fsrDepth, outputPixel, vec4(depthValue, 0.0, 0.0, 0.0));
                // Frame-uniform mode: -1=off, 1=NRD. Do not generate
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
                vec3 unfilteredRadiance = emissionRadiance;
                // Zero radiance is a valid Monte Carlo observation (occluded light, rejected
                // emitter or zero lobe), not a missing surface. Preserve its history eligibility
                // so the composite cannot replace reconstructed lighting with raw black speckles.
                float nrdDiffuseSignalActive = primaryHit ? 1.0 : 0.0;
                float nrdSpecularSignalActive = primaryHit ? 1.0 : 0.0;
                if (primaryHit && !(primaryDirectDistance > 0.0))
                    primaryDirectDistance = nrdHitDistanceScale;
                // NEE remains valid when BSDF continuation terminates. Use the emitter distance
                // or the existing directional-environment sentinel, never camera viewZ.
                if (!(diffuseSignalDistance > 0.0) && nrdDiffuseSignalActive > 0.5 && primaryDirectDistance > 0.0)
                    diffuseSignalDistance = primaryDirectDistance;
                if (!(specularSignalDistance > 0.0) && nrdSpecularSignalActive > 0.5 && primaryDirectDistance > 0.0)
                    specularSignalDistance = primaryDirectDistance;
                // Raw NRD images carry linear radiance and unnormalized path hit distance.
                // nrd_motion.comp demodulates and converts to YCoCg with camera-correct guides.
                float diffuseGeneratorHitDistance = nrdDiffuseSignalActive > 0.5
                    ? diffuseSignalDistance : -1.0;
                float specularGeneratorHitDistance = nrdSpecularSignalActive > 0.5
                    ? specularSignalDistance : -1.0;
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
                    | (primaryDynamicSlot != 0xffffffffu ? 256u : 0u);
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
                result.pixels[pixelIndex] = packUnorm4x8(vec4(clamp(outputRadiance, vec3(0.0), vec3(1.0)), 1.0));
            }
            """);
}
