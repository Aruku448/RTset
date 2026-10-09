package com.rtest.client;

/** Compile-time ablations: baseline is byte-for-byte unchanged; no runtime profiling branches. */
final class RayTracingCostAudit {
    enum Profile {
        BASELINE, NO_GI, NO_SUN, NO_MOON, NO_AREA, NO_SKY_NEE, NO_VOLUME,
        NO_RESTIR, NO_POM, NO_PBR, NO_SHADOW_RAYS, NO_DYNAMIC_DELTA, PRIMARY_MATERIAL, TRAVERSAL_ONLY,
        SUN_VISIBILITY, OPAQUE_TRAVERSAL;

        String key() { return name().toLowerCase(java.util.Locale.ROOT); }
        boolean primaryOnly() { return this == PRIMARY_MATERIAL || this == TRAVERSAL_ONLY || this == OPAQUE_TRAVERSAL; }
        int effectiveRestirMode(int requested) {
            if (primaryOnly() || this == NO_RESTIR || this == SUN_VISIBILITY) return 0;
            if (this == NO_GI) return requested & ~RestirLayout.SUFFIX;
            if (this == NO_AREA) return requested & ~RestirLayout.DIRECT;
            return requested;
        }
    }

    static boolean valid(String value) {
        for (Profile profile : Profile.values()) if (profile.key().equals(value)) return true;
        return false;
    }

    static Profile requested() {
        return Profile.valueOf(RayTracingClientConfig.INSTANCE.rayCostAuditProfile.get()
            .toUpperCase(java.util.Locale.ROOT));
    }

    static String raygen(String source, Profile profile) {
        if (profile == Profile.BASELINE) return source;
        if (profile == Profile.OPAQUE_TRAVERSAL) {
            // Existing traversal_only still executes the material/alpha any-hit shader.
            // Isolate AS traversal and the minimal closest-hit payload without that code.
            var primaryTrace = java.util.regex.Pattern.compile(
                "traceRayEXT\\(\\s*topLevelAS,\\s*0,\\s*\\(bounce == 0 \\? PRIMARY_RAY_MASK : SECONDARY_RAY_MASK\\),");
            if (primaryTrace.matcher(source).results().count() != 1L)
                throw new IllegalArgumentException("Expected one primary path trace seam");
            source = primaryTrace.matcher(source).replaceFirst(
                "traceRayEXT(topLevelAS, gl_RayFlagsOpaqueEXT, (bounce == 0 ? PRIMARY_RAY_MASK : SECONDARY_RAY_MASK),");
            source = replace(source, "uint debugView = uint(max(camera.parameters.z, 0.0) + 0.5);",
                "uint debugView = 0u; // audit: also omit optional debug shadow/blocker rays");
        }
        if (profile == Profile.NO_GI || profile.primaryOnly()) {
            source = replace(source, "int maxPathSegments = 1 + giBounces;", "int maxPathSegments = 1;");
        }
        if (profile == Profile.NO_SUN) {
            source = replace(source, "if (sunIndex >= sunSampleCount || camera.settings.x == 0.0) break;",
                "if (true) break; // audit: remove surface solar NEE, retain environment disk");
        }
        if (profile == Profile.NO_MOON) {
            source = replace(source, "if (moonIrradiance(camera.jitter.w) > 0.0 && moonDirectionValid(moonDirection)) {",
                "if (false) { // audit: remove surface lunar NEE");
        }
        if (profile == Profile.NO_AREA) {
            source = replace(source, "bool areaNeeEnabled = !transmission;", "bool areaNeeEnabled = false;");
        }
        if (profile == Profile.NO_SKY_NEE) {
            source = replace(source, "bool skyNeeEnabled = bounce == 0 && !transmission;", "bool skyNeeEnabled = false;");
        }
        if (profile == Profile.NO_VOLUME || profile.primaryOnly() || profile == Profile.SUN_VISIBILITY) {
            source = replace(source, "camera.settings.z", "0.0");
        }
        if (profile == Profile.NO_SHADOW_RAYS) {
            // Payload 1 is exclusively visibility; payload 0 is the geometric path.
            java.util.regex.Pattern rays = java.util.regex.Pattern.compile("traceRayEXT\\([^;]+,\\s*1\\s*\\);");
            java.util.regex.Matcher calls = rays.matcher(source);
            if (!calls.find()) throw new IllegalArgumentException("Missing shadow-ray audit seam");
            source = calls.replaceAll("if (false) $0");
        }
        if (profile == Profile.NO_DYNAMIC_DELTA) {
            source = replace(source, "vec3 staticShadowVisibility(vec3 origin, vec3 direction, float tMax, bool dynamicHit, vec3 actual) {",
                "vec3 staticShadowVisibility(vec3 origin, vec3 direction, float tMax, bool dynamicHit, vec3 actual) {\nreturn actual; // audit: omit static-only visibility retrace");
        }
        if (profile.primaryOnly()) {
            // Stop after the primary payload/guides, before environment, BSDF and every NEE.
            source = replace(source, "vec3 sunTemperatureColor = colorTemperature(camera.environment.y);",
                "radiance = primaryHit ? pathBaseColorRoughness.rgb : vec3(0.0);\n"
                + "                    break;\n"
                + "                    vec3 sunTemperatureColor = colorTemperature(camera.environment.y);");
        }
        if (profile == Profile.NO_PBR) {
            source = replace(source, "float evaluateEmitterEmission(float fallbackEmission, uint mapIndex, vec2 atlasUv) {",
                "float evaluateEmitterEmission(float fallbackEmission, uint mapIndex, vec2 atlasUv) {\nreturn fallbackEmission; // audit: no emitter PBR map decode");
        }
        if (profile == Profile.SUN_VISIBILITY) {
            // Compare the SAME shadow queries at direct and mirror hits, independent of RGB
            // BSDF response. Delta mirror vertices keep their original continuation geometry.
            source = replace(source, "vec3 sunDiffuseContribution = vec3(0.0);",
                "vec3 auditSunVisibility = vec3(0.0);\nvec3 sunDiffuseContribution = vec3(0.0);");
            source = replace(source, "bool sampleSunDynamic = shadowDynamicOccluder != 0u;",
                "auditSunVisibility += shadowFactor / float(sunSampleCount);\n"
                    + "bool sampleSunDynamic = shadowDynamicOccluder != 0u;");
            source = replace(source, "radiance += localRadiance;",
                "if (!transmission && roughness > 0.01) {\n"
                    + "radiance = auditSunVisibility; break;\n}\nradiance += localRadiance;");
            // NRD preparation retains the invalid-material marker and its composite returns
            // raw output. Aerial is disabled above; normal display processing remains active.
            source = replace(source, "vec4(primaryBaseColor, primaryHit ? primaryRoughness : -1.0)",
                "vec4(primaryBaseColor, -1.0)");
        }
        return source;
    }

    static String closestHit(String source, Profile profile) {
        if (profile == Profile.TRAVERSAL_ONLY || profile == Profile.OPAQUE_TRAVERSAL) {
            source = replace(source, "void main() {", "void main() {\n"
                + "pathPosition = vec4(gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT, 1.0);\n"
                + "pathLocalPosition = vec4(gl_ObjectRayOriginEXT + gl_HitTEXT * gl_ObjectRayDirectionEXT, 1.0);\n"
                + "pathDynamicSlot = 0xffffffffu;\n"
                + "pathEmitterIndex = 0xffffffffu;\n"
                + "pathNormal = vec4(-normalize(gl_WorldRayDirectionEXT), 0.0);\n"
                + "pathBaseColorRoughness = vec4(vec3(0.18), 1.0);\n"
                + "pathMaterial = vec4(0.0);\n"
                + "pathOpticalLighting = vec4(0.0);\nreturn;\n");
        }
        return material(source, profile);
    }

    private static String material(String source, Profile profile) {
        if (profile == Profile.NO_POM || profile == Profile.NO_PBR) {
            // Surface POM belongs to closest-hit; emitter lookup does not run POM.
            int function = source.indexOf("vec2 pbrParallaxUv(");
            if (function < 0) throw new IllegalArgumentException("Missing audit POM function");
            int body = source.indexOf('{', function) + 1;
            source = source.substring(0, body) + "\nreturn atlasUv; // audit: no POM\n" + source.substring(body);
        }
        if (profile == Profile.NO_PBR) {
            // Out parameters retain the exact ordinary defaults before skipping map reads.
            source = replace(source, "hasAuthoredEmission = false;", "hasAuthoredEmission = false;\nreturn; // audit: no companion PBR maps");
        }
        return source;
    }

    private static String replace(String source, String from, String to) {
        if (!source.contains(from)) throw new IllegalArgumentException("Missing ray-cost audit seam: " + from);
        return source.replace(from, to);
    }
}
