package com.rtest.client;

/** Shared finite lunar emitter used by visibility, NEE and volume source scaling. */
final class RayTracingMoonShader {
    private RayTracingMoonShader() {
    }

    static final String GLSL = """
            const float MOON_ANGULAR_RADIUS = 0.01884; // Four times the previous diameter.
            // phaseToken = phaseIndex + 1 + configured full-moon irradiance / 4.
            float moonFullIrradiance(float phaseToken) { return 4.0 * fract(phaseToken); }
            bool moonDirectionValid(vec3 direction) {
                return dot(direction, direction) > 1e-8 && !any(isnan(direction)) && !any(isinf(direction));
            }
            const float MOON_PI = 3.141592653589793;
            float moonSolidAngle() {
                return 2.0 * MOON_PI * (1.0 - cos(MOON_ANGULAR_RADIUS));
            }
            float moonIrradiance(float phaseToken) {
                if (phaseToken < 0.5 || isnan(phaseToken) || isinf(phaseToken)) return 0.0;
                int phase = clamp(int(phaseToken + 0.5) - 1, 0, 7);
                if (phase == 4) return 0.0;
                float alpha = min(float(phase), float(8 - phase)) * (MOON_PI / 4.0);
                return moonFullIrradiance(phaseToken) * max((sin(alpha) + (MOON_PI - alpha) * cos(alpha)) / MOON_PI, 0.0);
            }
            vec3 sampleMoonDirection(vec3 direction, vec2 sampleValue) {
                vec3 centre = normalize(direction);
                float phi = 2.0 * MOON_PI * sampleValue.y;
                float cosine = mix(cos(MOON_ANGULAR_RADIUS), 1.0, sampleValue.x);
                float sine = sqrt(max(1.0 - cosine * cosine, 0.0));
                vec3 tangent = normalize(abs(centre.y) < 0.999
                    ? cross(centre, vec3(0.0, 1.0, 0.0)) : cross(centre, vec3(1.0, 0.0, 0.0)));
                return normalize(centre * cosine + tangent * (cos(phi) * sine)
                    + cross(centre, tangent) * (sin(phi) * sine));
            }
            vec3 visibleMoonRadiance(vec3 direction, vec3 moonDirection, float phaseToken) {
                if (phaseToken < 0.5 || isnan(phaseToken) || isinf(phaseToken) || !moonDirectionValid(moonDirection)) {
                    return vec3(0.0);
                }
                float lengthSquared = dot(moonDirection, moonDirection);
                if (lengthSquared < 1e-8) return vec3(0.0);
                vec3 centre = moonDirection * inversesqrt(lengthSquared);
                float along = dot(direction, centre);
                if (along < cos(MOON_ANGULAR_RADIUS)) return vec3(0.0);
                int phaseIndex = clamp(int(phaseToken + 0.5) - 1, 0, 7);
                if (phaseIndex == 4) return vec3(0.0); // New moon: no invented light source.
                vec3 reference = abs(centre.z) < 0.95 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
                vec3 right = normalize(cross(reference, centre));
                vec3 up = cross(centre, right);
                vec2 uv = vec2(dot(direction, right), dot(direction, up))
                    / (max(along, 1e-6) * tan(MOON_ANGULAR_RADIUS));
                float radiusSquared = dot(uv, uv);
                if (radiusSquared > 1.0) return vec3(0.0);
                vec3 normal = vec3(uv, sqrt(max(1.0 - radiusSquared, 0.0)));
                // MC order: full, waning gibbous, third quarter, waning crescent,
                // new, waxing crescent, first quarter, waxing gibbous.
                float phaseAngle = float(phaseIndex) * (MOON_PI / 4.0);
                vec3 light = vec3(-sin(phaseAngle), 0.0, cos(phaseAngle));
                float illuminated = max(dot(normal, light), 0.0);
                // Full-moon Lambertian disk has mean normal.z = 2/3. Keep the same
                // radiance for visible hits and NEE; widening the disk must not multiply energy.
                return vec3(moonFullIrradiance(phaseToken) * 1.5 / max(moonSolidAngle(), 1e-8)) * illuminated;
            }
            """;
}
