package com.rtest.client;

import java.util.Random;

/** Transport factorization reference and real emitter query wiring. */
public final class EmitterDataflowTest {
    public static void main(String[] args) {
        Random random = new Random(202610071);
        double maximumRelativeError = 0;
        for (int i = 0; i < 200000; i++) {
            float source = random.nextFloat() * 1000;
            float visibility = random.nextFloat();
            float phase = .001f + random.nextFloat() * 2;
            float pdf = .000001f + random.nextFloat() * 10;
            float old = ((source * visibility) * phase) / pdf;
            float cached = ((source * phase) / pdf) * visibility;
            maximumRelativeError = Math.max(maximumRelativeError,
                Math.abs((double)old - cached) / Math.max(Math.abs(old), 1e-20));
            if (visibility == 0 && cached != 0) throw new AssertionError("occlusion must remain zero");
        }
        if (maximumRelativeError > 5e-7) throw new AssertionError("factorization drift: " + maximumRelativeError);
        String shader = RayTracingShaders.RAYGEN_SHADER;
        require(shader.contains("struct EmitterVisibilityRay { vec3 direction; float tMax; }"), "short-lived query record");
        String flatShader = shader.replaceAll("\\s+", " ");
        require(flatShader.contains("staticEmitterVisibility(emitterShadowOrigin, emitterRay, result.dynamicOccluder && separateDynamic, result.visibility)"), "surface reuses exact ray");
        require(flatShader.contains("staticEmitterVisibility(emitterShadowOrigin, emitterRay, sampleDynamic, visibility)"), "physical volume reuses exact ray");
        require(shader.contains("traceEmitterVisibility(emitterShadowOrigin, emitterRay)"), "actual query uses prepared ray");
        int first = shader.indexOf("vec3 unoccludedVolumeEmitter = sampleLegacyVolumeEmitter(");
        require(first >= 0 && shader.indexOf("sampleLegacyVolumeEmitter(", first + 60) < 0, "legacy main evaluates source once");
        require(shader.contains("vec3 staticVolumeEmitter = unoccludedVolumeEmitter * volumeStaticVisibility;"), "static signal reuses source");
        int emission = shader.indexOf("float evaluateEmitterEmission(");
        int skip = shader.indexOf("if ((features & 8u) == 0u)", emission);
        int fetch = shader.indexOf("uint pixel = emitterPbrReadPixel(", emission);
        require(skip > emission && skip < fetch, "disabled emission skips companion read");
        System.out.println("Emitter dataflow transport references passed; max relative float error=" + maximumRelativeError);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
