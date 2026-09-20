package com.rtest.client;

import java.lang.reflect.Constructor;
import java.util.List;

/** Regression checks for emissive-triangle importance and source-face orientation. */
public final class RayTracingLightTreeTest {
    private static final float EPSILON = 1.0E-5F;

    private RayTracingLightTreeTest() {
    }

    public static void main(String[] args) throws Exception {
        Constructor<RayTracingScene.SceneGeometry.SectionGeometry> constructor =
            RayTracingScene.SceneGeometry.SectionGeometry.class.getDeclaredConstructor(
                int.class, int.class, int.class, float[].class, float[].class);
        constructor.setAccessible(true);

        // The vertex order deliberately produces a -Y cross product while the material face is
        // +Y. LightTree must use the same face direction as the hit shader for shadow cosines.
        float[] vertices = {0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F};
        float[] materials = material(1.0F, 1.0F, 1.0F, 2.0F);
        RayTracingScene.SceneGeometry.SectionGeometry section =
            constructor.newInstance(0, 0, 0, vertices, materials);
        RayTracingLightTree.Data data = RayTracingLightTree.build(
            List.of(section), materials, 0.0, 0.0, 0.0);
        int[] words = data.words();
        if (data.emitterCount() != 1) {
            throw new AssertionError("Expected one emissive triangle");
        }
        int emitter = words[4];
        assertClose(0.0F, Float.intBitsToFloat(words[emitter + 12]), "emitter normal X");
        assertClose(1.0F, Float.intBitsToFloat(words[emitter + 13]), "emitter normal Y");
        assertClose(0.0F, Float.intBitsToFloat(words[emitter + 14]), "emitter normal Z");
        // area=0.5, pi flux factor, emission=2, white importance=1.
        assertClose((float)Math.PI, Float.intBitsToFloat(words[emitter + 11]), "white emitter power");

        // A colored tint participates in the selection PDF because GPU evaluateEmitter multiplies
        // the same tint into the sampled radiance. This changes variance only, not source energy.
        float[] redTintMaterials = material(0.5F, 0.0F, 0.0F, 2.0F);
        RayTracingScene.SceneGeometry.SectionGeometry redTintSection =
            constructor.newInstance(0, 0, 0, vertices, redTintMaterials);
        RayTracingLightTree.Data redData = RayTracingLightTree.build(
            List.of(redTintSection), redTintMaterials, 0.0, 0.0, 0.0);
        int redEmitter = redData.words()[4];
        assertClose((float)Math.PI * 0.5F,
            Float.intBitsToFloat(redData.words()[redEmitter + 11]), "tinted emitter power");
        System.out.println("RayTracingLightTree tests passed");
    }

    private static float[] material(float red, float green, float blue, float emission) {
        float[] result = new float[28];
        result[0] = red;
        result[1] = green;
        result[2] = blue;
        result[3] = 1.0F;
        result[4] = 0.0F;
        result[5] = 1.0F;
        result[6] = 0.0F;
        result[15] = 0.0F; // untextured path; tint is already in the packed working representation
        result[22] = emission;
        return result;
    }

    private static void assertClose(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
