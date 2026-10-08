package com.rtest.client;

/**
 * Physical finite-segment aerial-perspective integrator for Prime 26.3 (3ab5f75e).
 *
 * <p>This is a local adapter, not a byte-for-byte epipolar port: Prime integrates aerial
 * radiance in a screen-space epipolar volume with its own terrain sun-shadow hierarchy. RTest
 * keeps the identical medium/phase/source/optical-depth/geometry/spectral transport, but bounds
 * the integration to the camera segment, samples per-pixel, and evaluates the public TLAS
 * shadow path per volume sample for the direct term only (multiple scattering is left
 * unshadowed, matching Prime's aerial source which uses a coarser terrain visibility model).
 *
 * <p>The GLSL faithfully translates {@code model/atmosphere/integrate.slang},
 * {@code model/atmosphere/source.slang}, {@code model/atmosphere/spectrum.slang},
 * {@code model/atmosphere/geometry.slang}, {@code model/atmosphere/transmittance.slang} and
 * {@code model/atmosphere/optical_mapping.slang} from prime-atmosphere-26.3. Fixed physical
 * coefficients come from {@code AtmosphereMedium} (bank zero source/mean/ground/high plus the
 * optical-depth table). See third_party/prime-atmosphere-26.3/{LICENSE,LICENSE-EXCEPTIONS,
 * SKY-TRACER-NOTICE.md} and docs/prime-atmosphere-integration.md.
 *
 * <p>The bindings below are only declared in the physical raygen variant
 * ({@code RTEST_ATMOSPHERE_LUT}); the legacy variant keeps its 27-binding ABI.
 */
final class RayTracingAtmosphereSegmentShader {
    private RayTracingAtmosphereSegmentShader() {
    }

    static final String GLSL = """
            #ifdef RTEST_ATMOSPHERE_LUT
            // Physical aerial-perspective output: solar in-scatter only (Rec.2020, sun scale 12.5).
            // Surface transmittance stays in RayGen and pre-multiplies the existing surface AOVs;
            // local-emitter fog continues through the NRD diffuse history. The composite pass adds
            // this L post-NRD, gated on primary depth > 0.
            layout(set = 0, binding = 30, rgba16f) uniform writeonly image2D physicalAerialL;
            layout(set = 0, binding = 31, std430) readonly buffer PrimeAtmData { vec4 values[]; } primeAtmData;
            layout(set = 0, binding = 32) uniform sampler2D primeOpticalDepth;
            layout(set = 0, binding = 33) uniform sampler2D primeScatteringSource;
            layout(set = 0, binding = 34, rgba32f) uniform readonly image2D primeIncidentMean;
            layout(set = 0, binding = 35, rgba32f) uniform readonly image2D primeGroundRadiance;
            layout(set = 0, binding = 36, rgba32f) uniform readonly image2D primeRayleighSource;

            const float PATM_BOTTOM_KM = 6360.0;
            const float PATM_TOP_KM = 6480.0;
            const float PATM_THICKNESS_KM = 120.0;
            const float PATM_PI = 3.141592653589793;
            const float PATM_SPACE_SUN = 12.5;
            const uvec4 PATM_DIMS = uvec4(40u, 160u, 128u, 512u);
            const uvec4 PATM_OFFSETS0 = uvec4(12234u, 0u, 40u, 390u);
            const uvec4 PATM_OFFSETS1 = uvec4(4486u, 20u, 50u, 0u);
            const uvec4 PATM_MAPPING = uvec4(63u, 11934u, 12094u, 12222u);
            const vec4 PATM_SOLAR = vec4(2.0108537673950195, 1.9255825281143188, 1.8567869663238525, 1.5677554607391357);
            const vec4 PATM_OPTICAL_SEGMENTS[6] = vec4[6](
                vec4(1.0, 0.15748031437397003, 0.0, 0.15748031437397003),
                vec4(1.4142135381698608, 0.3041529357433319, -0.14667262136936188, 0.28346458077430725),
                vec4(3.316624879837036, 0.17383655905723572, 0.03762257099151611, 0.6141732335090637),
                vec4(3.464101552963257, 0.2669578790664673, -0.2712259292602539, 0.6535432934761047),
                vec4(5.916079998016357, 0.07064840942621231, 0.408810019493103, 0.8267716765403748),
                vec4(10.954451560974121, 0.03438180685043335, 0.6233661770820618, 1.0));

            struct PhysicalAtmMedium {
                vec4 extinction;
                vec4 scattering[5];
            };
            struct PhysicalAtmPhaseStencil {
                uint index;
                float fraction;
            };

            float physicalAtmHorizonKms(float h) {
                return -sqrt(max(h * (2.0 * PATM_BOTTOM_KM + h), 0.0)) / (PATM_BOTTOM_KM + h);
            }
            bool physicalAtmGroundKms(float h, float mu) {
                return mu < 0.0 && mu <= physicalAtmHorizonKms(h);
            }
            float physicalAtmHeightAt(float h, float mu, float d) {
                float dh = d * (d + 2.0 * (PATM_BOTTOM_KM + h) * mu);
                return max(0.0, h + dh / (sqrt(max((PATM_BOTTOM_KM + h) * (PATM_BOTTOM_KM + h) + dh, 0.0))
                    + PATM_BOTTOM_KM + h));
            }
            float physicalAtmBoundary(float h, float mu, bool hit) {
                float b = (PATM_BOTTOM_KM + h) * mu;
                if (hit) {
                    float c = h * (2.0 * PATM_BOTTOM_KM + h);
                    return max(0.0, c / max(-b + sqrt(max(b * b - c, 0.0)), 1e-20));
                }
                float c = (PATM_THICKNESS_KM - h) * (2.0 * PATM_BOTTOM_KM + PATM_THICKNESS_KM + h);
                float root = sqrt(max(b * b + c, 0.0));
                if (b > 0.0) {
                    return max(0.0, c / max(root + b, 1e-20));
                }
                return max(0.0, -b + root);
            }

            // ------------------------------------------------------------------ spectrum / medium
            PhysicalAtmMedium physicalAtmMedium(float h) {
                uint lo = 0u;
                uint hi = PATM_OFFSETS1.z - 1u;
                while (hi - lo > 1u) {
                    uint mid = (lo + hi) / 2u;
                    if (primeAtmData.values[PATM_OFFSETS0.z + 7u * mid].x <= h) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                uint a = PATM_OFFSETS0.z + lo * 7u;
                uint b = PATM_OFFSETS0.z + hi * 7u;
                float t = clamp((h - primeAtmData.values[a].x)
                    / (primeAtmData.values[b].x - primeAtmData.values[a].x), 0.0, 1.0);
                PhysicalAtmMedium result;
                result.extinction = mix(primeAtmData.values[a + 1u], primeAtmData.values[b + 1u], t);
                for (int j = 0; j < 5; j++) {
                    result.scattering[j] = mix(primeAtmData.values[a + 2u + uint(j)],
                        primeAtmData.values[b + 2u + uint(j)], t);
                }
                return result;
            }

            PhysicalAtmPhaseStencil physicalAtmPhaseStencil(float mu) {
                float x = clamp(pow(max((1.0 - mu) * 0.5, 0.0), 1.0 / 3.0) * 1024.0 - 0.5, 0.0, 1023.0);
                uint i = min(uint(x), 1022u);
                PhysicalAtmPhaseStencil stencil;
                stencil.index = i;
                stencil.fraction = x - float(i);
                return stencil;
            }
            vec4 physicalAtmTabulatedPhase(PhysicalAtmPhaseStencil stencil, uint species) {
                uint offset = PATM_OFFSETS0.w + species * 1024u + stencil.index;
                return mix(primeAtmData.values[offset], primeAtmData.values[offset + 1u], stencil.fraction);
            }

            vec3 physicalAtmLinearRec2020FromSpectral(vec4 value) {
                return value[0] * vec3(5.8809709548950195, -7.0044331550598145, 90.48178100585938)
                    + value[1] * vec3(-14.151336669921875, 54.43069839477539, 8.974457740783691)
                    + value[2] * vec3(87.37126159667969, 58.01542282104492, -1.4691294431686401)
                    + value[3] * vec3(31.710432052612305, -1.2180235385894775, 0.05091293156147003);
            }
            vec3 physicalAtmRadiance(vec4 value) {
                return max(physicalAtmLinearRec2020FromSpectral(value), vec3(0.0))
                    * (vec3(PATM_SPACE_SUN) / physicalAtmLinearRec2020FromSpectral(PATM_SOLAR));
            }
            vec3 physicalAtmRec2020Transmittance(vec4 value) {
                return clamp(physicalAtmLinearRec2020FromSpectral(value * PATM_SOLAR)
                    / physicalAtmLinearRec2020FromSpectral(PATM_SOLAR), vec3(0.0), vec3(1.0));
            }

            // --------------------------------------------------------- optical depth / transmittance
            float physicalAtmHeightCoord(float h) {
                float x = sqrt(clamp(h, 0.0, PATM_THICKNESS_KM));
                uint j = 0u;
                for (int i = 0; i < 5; i++) {
                    if (x > PATM_OPTICAL_SEGMENTS[i].x) {
                        j = uint(i + 1);
                    }
                }
                vec4 c = PATM_OPTICAL_SEGMENTS[j];
                return clamp(x * c.y + c.z, 0.0, 1.0);
            }
            vec2 physicalAtmOpticalUv(float h, float mu) {
                float x = clamp((mu - physicalAtmHorizonKms(h)) / (1.0 - physicalAtmHorizonKms(h)), 0.0, 1.0);
                float u = pow(x, 1.0 / 3.0);
                vec2 pixel = vec2(u * float(PATM_DIMS.w - 1u), physicalAtmHeightCoord(h) * float(PATM_DIMS.z - 1u))
                    + vec2(0.5);
                return pixel / vec2(float(PATM_DIMS.w), float(PATM_DIMS.z));
            }
            vec4 physicalAtmOpticalTransmittance(float h, float mu) {
                return exp(-textureLod(primeOpticalDepth, physicalAtmOpticalUv(h, mu), 0.0));
            }
            vec4 physicalAtmSunT(float h, float mu) {
                return physicalAtmGroundKms(h, mu) ? vec4(0.0) : physicalAtmOpticalTransmittance(h, mu);
            }

            // ----------------------------------------------------------------- multiple scattering
            float physicalAtmSolarLayer(uint layer, float e) {
                uint offset = PATM_MAPPING.y + 4u * layer;
                vec4 base = primeAtmData.values[offset];
                float d = max(PATM_PI * 0.5 - e, 0.0);
                float y = base.x * e + base.y - base.z * sqrt(d / (d + 0.0872664626));
                for (uint j = 1u; j <= 3u; j++) {
                    vec4 c = primeAtmData.values[offset + j];
                    float x = e - c.x;
                    y += c.z * x / (abs(x) + c.y);
                }
                return clamp(y, 0.0, 1.0);
            }
            float physicalAtmPhaseCoord(float nu) {
                float a = 0.61;
                return a * acos(clamp(nu, -1.0, 1.0)) / PATM_PI
                    + (1.0 - a) * pow(max((1.0 - nu) * 0.5, 0.0), 1.0 / 3.0);
            }
            vec4 physicalAtmShape(uint hi, uint si, vec2 uv) {
                vec2 pixel = vec2(float(si * 20u), float(hi * 12u)) + uv * vec2(19.0, 11.0) + vec2(0.5);
                return textureLod(primeScatteringSource, pixel / vec2(textureSize(primeScatteringSource, 0)), 0.0);
            }
            vec4 physicalAtmLogMean(uint hi, uint si) {
                return imageLoad(primeIncidentMean, ivec2(int(si), int(hi)));
            }
            vec4 physicalAtmHighBasis(float mu, float sunMu, float nu) {
                float y = clamp(mu, -1.0, 1.0);
                float radial = sqrt(max(1.0 - y * y, 0.0));
                float z = clamp((nu - y * sunMu) / sqrt(max(1.0 - sunMu * sunMu, 1e-20)), -radial, radial);
                return vec4(1.0, y * y, z * z, y * z);
            }
            vec4 physicalAtmHighShape(uint hi, uint si, vec4 b) {
                vec4 m[5];
                for (uint k = 0u; k < 5u; k++) {
                    m[k] = imageLoad(primeRayleighSource, ivec2(int(si * 5u + k), int(hi + 1u - PATM_OFFSETS1.y)));
                }
                return (m[0] + m[1] * b.y + m[2] * b.z + m[3] * b.w) / max(dot(m[4], b), 1e-20);
            }
            struct PhysicalAtmIndirectHeight { uint lo; uint hi; float fraction; };
            PhysicalAtmIndirectHeight physicalAtmIndirectHeight(float h) {
                uint lo = 0u;
                uint hi = PATM_DIMS.x - 1u;
                while (hi - lo > 1u) {
                    uint mid = (lo + hi) / 2u;
                    if (primeAtmData.values[mid].x <= h) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                float h0 = primeAtmData.values[lo].x;
                float h1 = primeAtmData.values[hi].x;
                float th = clamp((h - h0) / (h1 - h0), 0.0, 1.0);
                return PhysicalAtmIndirectHeight(lo, hi, th);
            }
            // Camera integration visits nearby heights. Reuse or step one table interval;
            // large jumps fall back to the original binary search, preserving endpoint rules.
            PhysicalAtmIndirectHeight physicalAtmCoherentIndirectHeight(float h,
                    PhysicalAtmIndirectHeight previous) {
                uint lo = previous.lo;
                uint hi = previous.hi;
                float h0 = primeAtmData.values[lo].x;
                float h1 = primeAtmData.values[hi].x;
                if (h < h0 && lo > 0u) {
                    hi = lo;
                    lo -= 1u;
                    h1 = h0;
                    h0 = primeAtmData.values[lo].x;
                } else if (h >= h1 && hi < PATM_DIMS.x - 1u) {
                    lo = hi;
                    hi += 1u;
                    h0 = h1;
                    h1 = primeAtmData.values[hi].x;
                }
                bool lowerCovered = h >= h0 || lo == 0u;
                bool upperCovered = h < h1 || hi == PATM_DIMS.x - 1u;
                if (!(lowerCovered && upperCovered)) return physicalAtmIndirectHeight(h);
                return PhysicalAtmIndirectHeight(lo, hi, clamp((h - h0) / (h1 - h0), 0.0, 1.0));
            }
            vec4 physicalAtmIndirect(float h, float mu, float sunMu, float nu, PhysicalAtmMedium c,
                    PhysicalAtmIndirectHeight heightStencil, float phaseCoordinate) {
                uint lo = heightStencil.lo;
                uint hi = heightStencil.hi;
                float th = heightStencil.fraction;
                float elevation = asin(clamp(sunMu, -1.0, 1.0));
                float s0 = physicalAtmSolarLayer(lo, elevation) * float(PATM_DIMS.y - 1u);
                float s1 = physicalAtmSolarLayer(hi, elevation) * float(PATM_DIMS.y - 1u);
                uint i0 = min(uint(s0), PATM_DIMS.y - 2u);
                uint i1 = min(uint(s1), PATM_DIMS.y - 2u);
                float t0 = s0 - float(i0);
                float t1 = s1 - float(i1);
                vec4 brightness = exp(mix(mix(physicalAtmLogMean(lo, i0), physicalAtmLogMean(lo, i0 + 1u), t0),
                    mix(physicalAtmLogMean(hi, i1), physicalAtmLogMean(hi, i1 + 1u), t1), th));
                // The complete >= 35 km high-moment branch is preserved for elevated camera shells;
                // it reads the Rayleigh source image instead of the near-ground phase shape.
                if (h >= 35.0 && PATM_OFFSETS1.y < PATM_DIMS.x) {
                    vec4 b = physicalAtmHighBasis(mu, sunMu, nu);
                    vec4 v = mix(mix(physicalAtmHighShape(lo, i0, b), physicalAtmHighShape(lo, i0 + 1u, b), t0),
                        mix(physicalAtmHighShape(hi, i1, b), physicalAtmHighShape(hi, i1 + 1u, b), t1), th);
                    return max(v * brightness * c.scattering[0], vec4(0.0));
                }
                float cp = clamp((mu - sunMu * nu) / sqrt(max((1.0 - sunMu * sunMu) * (1.0 - nu * nu), 1e-20)),
                    -1.0, 1.0);
                float angle = 0.0;
                {
                    float t = (1.0 - cp) * 0.5;
                    angle = t + 0.164285714 * t * (1.0 - t) * (2.0 * t - 1.0);
                }
                vec2 uv = vec2(phaseCoordinate, angle);
                vec4 v = mix(mix(physicalAtmShape(lo, i0, uv), physicalAtmShape(lo, i0 + 1u, uv), t0),
                    mix(physicalAtmShape(hi, i1, uv), physicalAtmShape(hi, i1 + 1u, uv), t1), th);
                vec4 sigma = vec4(0.0);
                for (uint k = 0u; k < 5u; k++) {
                    sigma += c.scattering[k];
                }
                return max(v * brightness * sigma, vec4(0.0));
            }
            #endif
            """;
}