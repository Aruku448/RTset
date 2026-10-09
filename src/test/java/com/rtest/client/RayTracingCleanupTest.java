package com.rtest.client;

import java.util.HashSet;
import java.util.Set;

/** Cleanup and single-source BSDF contracts. Compilation is covered by the enclosing shader test. */
public final class RayTracingCleanupTest {
    private RayTracingCleanupTest() { }

    public static void main(String[] args) {
        Set<String> stages = new HashSet<>();
        for (var field : RayTracingShaders.class.getDeclaredFields()) {
            if (field.getName().endsWith("_SHADER")) stages.add(field.getName());
        }
        Set<String> active = Set.of("RAYGEN_SHADER", "MISS_SHADER", "CLOSEST_HIT_SHADER",
            "SHADOW_MISS_SHADER", "SHADOW_CLOSEST_HIT_SHADER", "ANY_HIT_SHADER", "SHADOW_ANY_HIT_SHADER",
            "CONTROL_RAYGEN_SHADER", "CONTROL_COMPUTE_SHADER");
        if (!stages.equals(active)) throw new AssertionError("unused/missing shader stages: " + stages);
        String raygen = RayTracingShaders.RAYGEN_SHADER;
        for (String dead : new String[] {"pcgHash(", "randomFloat(", "inout uint seed",
                "evaluateHitEmitter(", "SUN_SAMPLE_EFFECT", "AREA_SAMPLE_EFFECT", "PRIME_UINT24_TO_FLOAT_SCALE",
                "primarySpecularPath", "selectedSpecularPath", "primeDefaultDiffuseEnergy(",
                "float normalLight", "TEMPORARY diagnostic build", "diffuseContribution =", "DIRECT_SUN_ONLY"}) {
            reject(raygen, dead);
        }
        for (String dead : new String[] {"pcgHash(", "randomFloat(", "sampleCosineHemisphere("}) {
            reject(RayTracingShaders.CLOSEST_HIT_SHADER, dead);
        }
        require(raygen, "sunDiffuseContribution += sunBsdf.diffuse * directLight;");
        require(raygen, "max(sunBsdf.f - sunBsdf.diffuse, vec3(0.0))");
        require(raygen, "result.diffuse = scale * bsdf.diffuse;");
        require(raygen, "max(scale * (bsdf.f - bsdf.diffuse), vec3(0.0))");
        require(raygen, "vec3 sampleGgx(vec3 normal, vec3 viewDirection, float roughness, vec2 sampleValue)");
        require(raygen, "primaryTransmissionPath = selectedTransmissionPath;");
        require(raygen, "sampleBase.vertexIndex = uint(bounce);");
        require(raygen, "sampleBase.sampleIndex = floatBitsToUint(camera.random.x);");
        System.out.println("Shader cleanup contracts passed (not GPU speed/pixel measurements)");
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) throw new AssertionError("dead/redundant shader fragment: " + fragment);
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) throw new AssertionError("missing cleanup contract: " + fragment);
    }
}
