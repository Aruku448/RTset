package com.rtest.client;

/** GLSL reader for Prime 26.3's Sun/Moon log-radiance sky LUTs and shared directional T. */
final class RayTracingAtmosphereShader {
    private RayTracingAtmosphereShader() {
    }

    // Adapted from Prime 26.3 (3ab5f75), sky_view.slang / geometry.slang / trace.slang.
    // Sky chart/geometry adapted upstream from Sky Tracer b66b163 (GPL-3.0-only).
    // See third_party/prime-atmosphere-26.3/{LICENSE,LICENSE-EXCEPTIONS,SKY-TRACER-NOTICE.md}.
    static final String GLSL = """
            #ifdef RTEST_ATMOSPHERE_LUT
            layout(set = 0, binding = 27, rgba32f) uniform readonly image2D atmosphereSkyView;
            layout(set = 0, binding = 28, rgba16f) uniform readonly image2D atmosphereTransmittance;
            layout(set = 0, binding = 37, rgba32f) uniform readonly image2D atmosphereMoonSkyView;
            // Radius is supplied by the host: (worldY + 64) * .001 + .300 + 6360.
            layout(set = 0, binding = 29, std140) uniform AtmosphereCamera {
                vec4 parameters;
            } atmosphereCamera;
            const float PHYSICAL_ATM_BOTTOM = 6360.0;
            const float PHYSICAL_ATM_TOP = 6480.0;
            const float PHYSICAL_ATM_PI = 3.141592653589793;
            bool physicalAtmosphereEnabled() {
                float radius = atmosphereCamera.parameters.x;
                return atmosphereCamera.parameters.w > 0.5 && !isnan(radius) && !isinf(radius)
                    && radius >= PHYSICAL_ATM_BOTTOM && radius < PHYSICAL_ATM_TOP;
            }
            bool physicalAtmosphereDirectionValid(vec3 direction) {
                float squareLength = dot(direction, direction);
                return !any(isnan(direction)) && !any(isinf(direction))
                    && squareLength > 1e-20 && !isinf(squareLength);
            }
            float physicalAtmosphereHorizon(float h) {
                return -sqrt(max(h * (2.0 * PHYSICAL_ATM_BOTTOM + h), 0.0))
                    / (PHYSICAL_ATM_BOTTOM + h);
            }
            bool physicalAtmosphereGround(float h, float mu) {
                return mu < 0.0 && mu <= physicalAtmosphereHorizon(h);
            }
            struct PhysicalAtmSkyChart {
                vec2 bounds;
                vec3 centers;
                vec4 weights;
                vec3 widths;
            };
            PhysicalAtmSkyChart physicalAtmosphereChart(float h, float sunElevation, uint chart) {
                float hor = asin(physicalAtmosphereHorizon(h));
                bool space = h >= 120.0;
                float outer = space ? -acos(clamp(PHYSICAL_ATM_TOP / (PHYSICAL_ATM_BOTTOM + h), 0.0, 1.0)) : hor;
                PhysicalAtmSkyChart c;
                c.bounds = chart == 2u ? vec2(-PHYSICAL_ATM_PI * 0.5, hor)
                    : chart == 1u ? vec2(0.0, PHYSICAL_ATM_PI * 0.5)
                    : vec2(hor, space ? outer : 0.0);
                c.centers = vec3(hor, sunElevation, outer);
                if (chart == 2u) {
                    c.weights = vec4(0.20124773681163788, 0.301099956035614, 0.051217593252658844, 0.44643473625183105);
                    c.widths = vec3(0.08166702091693878, 0.015121965669095516, 0.05319792777299881);
                } else if (space) {
                    c.weights = vec4(0.10427150130271912, 0.5162510275840759, 0.302908718585968, 0.07656880468130112);
                    c.widths = vec3(0.03553646430373192, 0.0036403569392859936, 0.0031041009351611137);
                } else if (chart == 1u) {
                    c.weights = vec4(0.23954732716083527, 0.02231575734913349, 0.5578426122665405, 0.18029437959194183);
                    c.widths = vec3(0.0869770422577858, 0.0739380493760109, 0.14374907314777374);
                } else {
                    c.weights = vec4(0.28717395663261414, 0.044344790279865265, 0.2831573188304901, 0.38532397150993347);
                    c.widths = vec3(0.0033508469350636005, 0.0030857601668685675, 0.029294535517692566);
                }
                if (chart == 2u && !space) {
                    float t = 1.0 - smoothstep(1.0, 4.0, h);
                    c.weights = mix(c.weights, vec4(0.07399826496839523, 0.01126958429813385, 0.49432915449142456, 0.42040303349494934), t);
                    c.widths = mix(c.widths, vec3(0.010801837779581547, 0.11641104519367218, 0.01404983177781105), t);
                }
                return c;
            }
            float physicalAtmosphereElevationCoordinate(float e, PhysicalAtmSkyChart c) {
                float lo = c.bounds.x, hi = c.bounds.y;
                float x = clamp(e, lo, hi);
                float y = c.weights.x * (x - lo) / max(hi - lo, 1e-10);
                for (int j = 0; j < 3; j++) {
                    float center = c.centers[j], width = c.widths[j];
                    if (center < lo || center > hi) {
                        y += c.weights[j + 1] * (x - lo) / max(hi - lo, 1e-10)
                            * (abs(hi - center) + width) / (abs(x - center) + width);
                    } else {
                        float a = (lo - center) / (abs(lo - center) + width);
                        float b = (hi - center) / (abs(hi - center) + width);
                        float d = (x - center) / (abs(x - center) + width);
                        y += c.weights[j + 1] * (d - a) / max(b - a, 1e-10);
                    }
                }
                return clamp(y, 0.0, 1.0);
            }
            vec3 physicalAtmosphereFinite(vec3 value, vec3 fallback) {
                return vec3(isnan(value.x) || isinf(value.x) ? fallback.x : value.x,
                    isnan(value.y) || isinf(value.y) ? fallback.y : value.y,
                    isnan(value.z) || isinf(value.z) ? fallback.z : value.z);
            }
            vec4 physicalAtmosphereSkyLoad(ivec2 coordinate, bool moon) {
                return moon ? imageLoad(atmosphereMoonSkyView, coordinate)
                    : imageLoad(atmosphereSkyView, coordinate);
            }
            vec3 physicalAtmosphereSkyFrom(vec3 direction, vec3 sunDirection, bool moon) {
                if (!physicalAtmosphereEnabled() || !physicalAtmosphereDirectionValid(direction)
                    || !physicalAtmosphereDirectionValid(sunDirection)) return vec3(0.0);
                vec3 ray = normalize(direction), sun = normalize(sunDirection);
                float h = atmosphereCamera.parameters.x - PHYSICAL_ATM_BOTTOM;
                if (h >= 120.0) {
                    float radius = PHYSICAL_ATM_BOTTOM + h;
                    float b = radius * ray.y;
                    float c = (h - 120.0) * (radius + PHYSICAL_ATM_TOP);
                    if (b >= 0.0 || b * b <= c) return vec3(0.0);
                }
                float horizontal = length(ray.xz) * length(sun.xz);
                float cp = horizontal > 1e-10 ? clamp(dot(ray.xz, sun.xz) / horizontal, -1.0, 1.0) : 1.0;
                float x = sqrt(sqrt(max((1.0 - cp) * 0.5, 0.0))) * 255.0;
                bool hit = physicalAtmosphereGround(h, ray.y);
                int lower = h >= 120.0 ? 192 : 96;
                uvec3 chartRows = hit ? uvec3(2, 192, 64)
                    : ray.y >= 0.0 ? uvec3(1, lower, 192 - lower) : uvec3(0, 0, lower);
                PhysicalAtmSkyChart chart = physicalAtmosphereChart(h, asin(clamp(sun.y, -1.0, 1.0)), chartRows.x);
                float y = physicalAtmosphereElevationCoordinate(asin(clamp(ray.y, -1.0, 1.0)), chart);
                float row = float(chartRows.y) + y * float(chartRows.z - 1u);
                int ix = min(int(x), 254);
                // Search producer's stored row mu, confined to this chart (never filter across
                // the horizon). Interpolate in chart coordinate, NOT mu or linear radiance.
                int first = int(chartRows.y), last = int(chartRows.y + chartRows.z - 1u);
                int lo = first, hi = last;
                for (int step = 0; step < 8 && hi - lo > 1; step++) {
                    int mid = (lo + hi) / 2;
                    if (physicalAtmosphereSkyLoad(ivec2(0, mid), moon).w <= ray.y) lo = mid;
                    else hi = mid;
                }
                int iy = clamp(lo, first, last - 1);
                float mu0 = physicalAtmosphereSkyLoad(ivec2(0, iy), moon).w;
                float mu1 = physicalAtmosphereSkyLoad(ivec2(0, iy + 1), moon).w;
                float y0 = physicalAtmosphereElevationCoordinate(asin(clamp(mu0, -1.0, 1.0)), chart);
                float y1 = physicalAtmosphereElevationCoordinate(asin(clamp(mu1, -1.0, 1.0)), chart);
                float fraction = y1 > y0 ? clamp((y - y0) / (y1 - y0), 0.0, 1.0)
                    : clamp(row - float(iy), 0.0, 1.0);
                vec3 a = mix(physicalAtmosphereSkyLoad(ivec2(ix, iy), moon).rgb,
                    physicalAtmosphereSkyLoad(ivec2(ix + 1, iy), moon).rgb, x - float(ix));
                vec3 b = mix(physicalAtmosphereSkyLoad(ivec2(ix, iy + 1), moon).rgb,
                    physicalAtmosphereSkyLoad(ivec2(ix + 1, iy + 1), moon).rgb, x - float(ix));
                if (h >= 120.0 && !hit && iy == lower - 2) {
                    float previous = physicalAtmosphereSkyLoad(ivec2(0, lower - 2), moon).w;
                    float previous2 = physicalAtmosphereSkyLoad(ivec2(0, lower - 3), moon).w;
                    float radius = PHYSICAL_ATM_BOTTOM + h;
                    float c = (h - 120.0) * (radius + PHYSICAL_ATM_TOP);
                    float d = max(radius * radius * ray.y * ray.y - c, 0.0);
                    float d0 = max(radius * radius * previous * previous - c, 1e-20);
                    float d1 = max(radius * radius * previous2 * previous2 - c, d0 + 1e-10);
                    vec3 earlier = mix(physicalAtmosphereSkyLoad(ivec2(ix, iy - 1), moon).rgb,
                        physicalAtmosphereSkyLoad(ivec2(ix + 1, iy - 1), moon).rgb, x - float(ix));
                    vec3 change = exp(earlier - a) * sqrt(d0 / d1) - 1.0;
                    return physicalAtmosphereFinite(max(exp(a) * sqrt(clamp(d / d0, 0.0, 1.0))
                        * (1.0 + change * ((d - d0) / (d1 - d0))), vec3(0.0)), vec3(0.0));
                }
                return physicalAtmosphereFinite(max(exp(mix(a, b, fraction)) - vec3(1e-30), vec3(0.0)), vec3(0.0));
            }
            vec3 physicalAtmosphereSky(vec3 direction, vec3 sunDirection) {
                return physicalAtmosphereSkyFrom(direction, sunDirection, false);
            }
            vec3 physicalAtmosphereMoonSky(vec3 direction, vec3 moonDirection) {
                return physicalAtmosphereSkyFrom(direction, moonDirection, true);
            }
            vec3 physicalAtmosphereSunTransmittance(vec3 direction) {
                if (!physicalAtmosphereEnabled()) return vec3(1.0);
                if (!physicalAtmosphereDirectionValid(direction)) return vec3(0.0);
                float mu = normalize(direction).y;
                float h = max(atmosphereCamera.parameters.x - PHYSICAL_ATM_BOTTOM, 0.0);
                if (physicalAtmosphereGround(h, mu)) return vec3(0.0);
                float coordinate = (0.5 + 0.5 * sign(mu) * sqrt(abs(clamp(mu, -1.0, 1.0)))) * 8192.0;
                int first = int(coordinate), second = min(first + 1, 8192);
                return clamp(physicalAtmosphereFinite(mix(
                    imageLoad(atmosphereTransmittance, ivec2(first, 0)).rgb,
                    imageLoad(atmosphereTransmittance, ivec2(second, 0)).rgb,
                    fract(coordinate)), vec3(1.0)), vec3(0.0), vec3(1.0));
            }
            #else
            bool physicalAtmosphereEnabled() { return false; }
            vec3 physicalAtmosphereSky(vec3 direction, vec3 sunDirection) { return vec3(0.0); }
            vec3 physicalAtmosphereMoonSky(vec3 direction, vec3 moonDirection) { return vec3(0.0); }
            vec3 physicalAtmosphereSunTransmittance(vec3 direction) { return vec3(1.0); }
            #endif
            """;
}
