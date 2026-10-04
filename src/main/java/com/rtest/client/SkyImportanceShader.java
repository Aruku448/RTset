package com.rtest.client;

final class SkyImportanceShader {
    static final String GLSL = """
        layout(set = 0, binding = 38, std430) readonly buffer SkyImportance { vec4 entries[]; } skyImportance;
        layout(set = 0, binding = 39) uniform accelerationStructureEXT skyCdfAS;
        layout(set = 0, binding = 40, std430) readonly buffer SkyCdfBounds { vec4 entries[]; } skyCdfBounds;
        layout(location = 3) rayPayloadEXT vec2 skyCdfHit;
        const int SKY_IMPORTANCE_SIDE = 32;
        const int SKY_IMPORTANCE_COUNT = 6144;
        // Static texture proposal only; atmospheric LUTs remain on the cosine strategy.
        float skyImportanceMix() {
            if ((uint(camera.pbrParallaxSettings.w + 0.5) & 8u) == 0u) return 0.0;
            if (camera.pbrParallaxSettings.z <= 0.5) return 0.0;
        #ifdef RTEST_ATMOSPHERE_LUT
            if (physicalAtmosphereEnabled()) return 0.5 * clamp(atmosphereCamera.parameters.y, 0.0, 1.0);
        #endif
            return 0.5;
        }
        vec3 skyCellDirection(int face, vec2 uv) {
            if (face == 0) return normalize(vec3(1.0, -uv.y, -uv.x));
            if (face == 1) return normalize(vec3(-1.0, -uv.y, uv.x));
            if (face == 2) return normalize(vec3(uv.x, 1.0, uv.y));
            if (face == 3) return normalize(vec3(uv.x, -1.0, -uv.y));
            if (face == 4) return normalize(vec3(uv.x, -uv.y, 1.0));
            return normalize(vec3(-uv.x, -uv.y, -1.0));
        }
        int skyProposalOffset(vec3 normal) {
            vec3 a = abs(normal);
            int axis = a.x >= a.y && a.x >= a.z ? (normal.x >= 0.0 ? 0 : 1)
                : a.y >= a.z ? (normal.y >= 0.0 ? 2 : 3) : (normal.z >= 0.0 ? 4 : 5);
            return axis * SKY_IMPORTANCE_COUNT;
        }
        float skyTexturePdf(vec3 normal, vec3 direction) {
            vec3 a = abs(direction);
            int face;
            vec2 uv;
            if (a.x >= a.y && a.x >= a.z) {
                face = direction.x >= 0.0 ? 0 : 1;
                uv = vec2(direction.x >= 0.0 ? -direction.z : direction.z, -direction.y) / a.x;
            } else if (a.y >= a.z) {
                face = direction.y >= 0.0 ? 2 : 3;
                uv = vec2(direction.x, direction.y >= 0.0 ? direction.z : -direction.z) / a.y;
            } else {
                face = direction.z >= 0.0 ? 4 : 5;
                uv = vec2(direction.z >= 0.0 ? direction.x : -direction.x, -direction.y) / a.z;
            }
            ivec2 cell = clamp(ivec2(floor((uv * 0.5 + 0.5) * float(SKY_IMPORTANCE_SIDE))),
                ivec2(0), ivec2(SKY_IMPORTANCE_SIDE - 1));
            int index = (face * SKY_IMPORTANCE_SIDE + cell.y) * SKY_IMPORTANCE_SIDE + cell.x;
            // Uniform face UV -> solid angle: dOmega / (du dv) = (1+u*u+v*v)^(-3/2).
            float q = 1.0 + dot(uv, uv);
            return skyImportance.entries[skyProposalOffset(normal) + index].z * float(SKY_IMPORTANCE_SIDE * SKY_IMPORTANCE_SIDE)
                * 0.25 * q * sqrt(q);
        }
        float skyNeePdf(vec3 normal, vec3 direction) {
            float cosinePdf = max(dot(normal, direction), 0.0) / BSDF_PI;
            float mixture = skyImportanceMix();
            if (mixture <= 0.0) return cosinePdf;
            return mix(cosinePdf, skyTexturePdf(normal, direction), mixture);
        }
        // Rare boundary/precision miss recovery inverts the SAME quantized CDF, never a different proposal.
        int skyCdfRecover(int offset, vec2 xi) {
            int lo = 0, hi = 191;
            while (lo < hi) {
                int mid = (lo + hi) / 2;
                if (xi.x < skyCdfBounds.entries[offset + mid * SKY_IMPORTANCE_SIDE].w) hi = mid;
                else lo = mid + 1;
            }
            int row = lo;
            lo = 0; hi = SKY_IMPORTANCE_SIDE - 1;
            while (lo < hi) {
                int mid = (lo + hi) / 2;
                if (xi.y < skyCdfBounds.entries[offset + row * SKY_IMPORTANCE_SIDE + mid].y) hi = mid;
                else lo = mid + 1;
            }
            return offset + row * SKY_IMPORTANCE_SIDE + lo;
        }
        vec3 sampleSkyCdf(vec3 normal, vec2 xi) {
            int offset = skyProposalOffset(normal);
            xi = clamp(xi, vec2(0.0), vec2(0.99999994));
            skyCdfHit = vec2(-1.0);
            traceRayEXT(skyCdfAS, gl_RayFlagsOpaqueEXT | gl_RayFlagsTerminateOnFirstHitEXT,
                1u << uint(offset / SKY_IMPORTANCE_COUNT), 2, 0, 2,
                vec3(-0.001, xi.y, xi.x), 0.0, vec3(1.0, 0.0, 0.0), 1.002, 3);
            int index = int(skyCdfHit.x);
            bool hardwareHit = index >= offset && index < offset + SKY_IMPORTANCE_COUNT;
            if (!hardwareHit) index = skyCdfRecover(offset, xi);
            vec4 b = skyCdfBounds.entries[index];
            int cell = index - offset;
            int face = cell / (SKY_IMPORTANCE_SIDE * SKY_IMPORTANCE_SIDE);
            int local = cell % (SKY_IMPORTANCE_SIDE * SKY_IMPORTANCE_SIDE);
            // Hit primitive identifies the ribbon; its slope and width give the proposal PDF.
            // Reconstruct within-cell position from quantized bounds to share exact alias PDF data.
            vec2 jitter = clamp(vec2((xi.y - b.x) / max(b.y - b.x, 1.0e-30),
                (xi.x - b.z) / max(b.w - b.z, 1.0e-30)), vec2(0.0), vec2(0.99999994));
            vec2 uv = (vec2(local % SKY_IMPORTANCE_SIDE, local / SKY_IMPORTANCE_SIDE) + jitter)
                * (2.0 / float(SKY_IMPORTANCE_SIDE)) - 1.0;
            if (hardwareHit) {
                float columnStart = float(local % SKY_IMPORTANCE_SIDE) / float(SKY_IMPORTANCE_SIDE);
                float columnEnd = columnStart + 1.0 / float(SKY_IMPORTANCE_SIDE);
                uv.x = 2.0 * clamp(skyCdfHit.y, columnStart, columnEnd - 0.00000006) - 1.0;
            }
            return skyCellDirection(face, uv);
        }
        vec3 sampleSkyNee(vec3 normal, vec2 xi) {
            float mixture = skyImportanceMix();
            if (mixture <= 0.0 || xi.x >= mixture) {
                xi.x = (xi.x - mixture) / (1.0 - mixture);
                return sampleCosineHemisphere(normal, xi);
            }
            if ((uint(camera.pbrParallaxSettings.w + 0.5) & 16u) != 0u)
                return sampleSkyCdf(normal, vec2(xi.x / mixture, xi.y));
            float scaled = min(xi.x / mixture, 0.99999994) * float(SKY_IMPORTANCE_COUNT);
            int bucket = min(int(scaled), SKY_IMPORTANCE_COUNT - 1);
            float residual = scaled - float(bucket);
            vec4 entry = skyImportance.entries[skyProposalOffset(normal) + bucket];
            int index;
            if (residual < entry.x) {
                index = bucket;
                residual /= max(entry.x, 1.0e-30);
            } else {
                index = int(entry.y + 0.5);
                residual = (residual - entry.x) / max(1.0 - entry.x, 1.0e-30);
            }
            // Remapping the alias branch residual gives a uniform jitter without an extra RNG call.
            vec2 jitter = clamp(vec2(residual, xi.y), vec2(0.0), vec2(0.99999994));
            int face = index / (SKY_IMPORTANCE_SIDE * SKY_IMPORTANCE_SIDE);
            int local = index % (SKY_IMPORTANCE_SIDE * SKY_IMPORTANCE_SIDE);
            vec2 uv = (vec2(local % SKY_IMPORTANCE_SIDE, local / SKY_IMPORTANCE_SIDE) + jitter)
                * (2.0 / float(SKY_IMPORTANCE_SIDE)) - 1.0;
            return skyCellDirection(face, uv);
        }
        """;

    static final String MISS = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        layout(location = 3) rayPayloadInEXT vec2 skyCdfHit;
        void main() { skyCdfHit = vec2(-1.0); }
        """;
    static final String HIT = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        layout(location = 3) rayPayloadInEXT vec2 skyCdfHit;
        void main() {
            skyCdfHit = vec2(float(gl_InstanceCustomIndexEXT + gl_PrimitiveID / 2), gl_HitTEXT - 0.001);
        }
        """;
}
