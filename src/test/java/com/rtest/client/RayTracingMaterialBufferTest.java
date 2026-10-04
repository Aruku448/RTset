package com.rtest.client;

import com.rtest.client.RayTracingScene.SceneGeometry.SectionGeometry;
import java.lang.reflect.Constructor;
import java.util.List;

/** Regression checks for stable section material spans across add/remove/replace deltas. */
public final class RayTracingMaterialBufferTest {
    private RayTracingMaterialBufferTest() {
    }

    public static void main(String[] args) throws Exception {
        Constructor<SectionGeometry> constructor = SectionGeometry.class.getDeclaredConstructor(
            int.class, int.class, int.class, float[].class, float[].class);
        constructor.setAccessible(true);
        float[] vertices = {0, 1, 0, 1, 1, 0, 1, 1, 1};
        SectionGeometry removed = constructor.newInstance(0, 0, 0, vertices.clone(), material(0.0F));
        SectionGeometry surviving = constructor.newInstance(16, 0, 0, vertices.clone(), material(2.0F));
        SectionGeometry added = constructor.newInstance(32, 0, 0, vertices.clone(), material(0.0F));

        RayTracingMaterialBuffer.Layout first = RayTracingMaterialBuffer.Layout.compact(
            List.of(removed, surviving));
        RayTracingMaterialBuffer.Layout delta = first.update(List.of(surviving, added));
        if (first.baseTriangle(surviving) != 1 || delta.baseTriangle(surviving) != 1
                || delta.baseTriangle(added) != 0 || delta.highWaterTriangle() != 2) {
            throw new AssertionError("surviving material span moved instead of reusing the removed section's hole");
        }

        SectionGeometry changedSurvivor = constructor.newInstance(
            16, 0, 0, vertices.clone(), material(3.0F));
        RayTracingMaterialBuffer.Layout replacement = delta.update(List.of(changedSurvivor, added));
        if (replacement.baseTriangle(changedSurvivor) != delta.baseTriangle(surviving)
                || replacement.baseTriangle(added) != delta.baseTriangle(added)) {
            throw new AssertionError("same-sized material replacement did not retain its section span");
        }

        RayTracingLightTree.Data lightTree = RayTracingLightTree.build(
            List.of(changedSurvivor, added), 0, 0, 0, replacement);
        int[] words = lightTree.words();
        int emitterOffset = words[4];
        int materialMapOffset = words[6];
        if (words[emitterOffset + 15] != 1 || words[materialMapOffset] != -1
                || words[materialMapOffset + 1] != 0) {
            throw new AssertionError("light-tree material indices did not follow stable section spans");
        }
        System.out.println("RayTracing material span tests passed");
    }

    private static float[] material(float emission) {
        float[] result = new float[28];
        result[0] = result[1] = result[2] = result[3] = 1.0F;
        result[4] = 0.0F;
        result[5] = 1.0F;
        result[6] = 0.0F;
        result[15] = 0.0F;
        result[22] = emission;
        return result;
    }
}
