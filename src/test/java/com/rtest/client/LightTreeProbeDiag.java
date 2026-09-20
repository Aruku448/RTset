package com.rtest.client;

import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Temporary diagnostic: builds a one-quad emissive light tree and replays the exact GLSL
 * light-tree traversal against the Java words[] buffer to prove the CPU/GPU contract and
 * verify the direct area-light estimator produces non-zero radiance.
 */
public final class LightTreeProbeDiag {
    private LightTreeProbeDiag() {
    }

    public static void main(String[] args) throws Exception {
        // One emissive quad at y = 1 spanning [0,1]x[0,1], normal up, area 1, emission 1.5.
        float[] vertices = {
            0, 1, 0, 1, 1, 0, 1, 1, 1,
            0, 1, 0, 1, 1, 1, 0, 1, 1
        };
        float[] materials = new float[2 * 28];
        for (int triangle = 0; triangle < 2; triangle++) {
            int offset = triangle * 28;
            materials[offset] = 1.0F;
            materials[offset + 1] = 1.0F;
            materials[offset + 2] = 1.0F;
            materials[offset + 3] = 1.0F;
            materials[offset + 4] = 0.0F;
            materials[offset + 5] = 1.0F;   // normal up
            materials[offset + 6] = 0.0F;
            materials[offset + 7] = 0.0F;
            materials[offset + 14] = 1.0F;
            materials[offset + 15] = 1.0F;
            materials[offset + 20] = 0.88F; // roughness
            materials[offset + 21] = 0.0F;  // metallic
            materials[offset + 22] = 1.5F;  // emission
            materials[offset + 23] = 1.04F; // transmission flag + reflectivity
            materials[offset + 27] = 1.0F;  // ior
        }

        Constructor<RayTracingScene.SceneGeometry.SectionGeometry> ctor =
            RayTracingScene.SceneGeometry.SectionGeometry.class.getDeclaredConstructor(
                int.class, int.class, int.class, float[].class, float[].class);
        ctor.setAccessible(true);
        RayTracingScene.SceneGeometry.SectionGeometry section =
            ctor.newInstance(0, 0, 0, vertices, materials);

        RayTracingLightTree.Data data =
            RayTracingLightTree.build(List.of(section), materials, 0, 0, 0);
        int[] w = data.words();

        System.out.println("emitterCount=" + data.emitterCount() + " words=" + w.length);
        System.out.println("header: nodes=" + w[0] + " emitters=" + w[1] + " forward=" + w[2]
            + " reverse=" + w[3] + " emitterOffset=" + w[4] + " materialLen=" + w[5]
            + " materialMap=" + w[6] + " leafNode=" + w[7]);
        System.out.println("root power=" + f(w[8 + 3]) + " min=" + f(w[8])
            + "," + f(w[9]) + "," + f(w[10]) + " max=" + f(w[12]) + "," + f(w[13]) + "," + f(w[14]));
        for (int emitter = 0; emitter < data.emitterCount(); emitter++) {
            int base = w[4] + emitter * 16;
            System.out.println("emitter " + emitter + " corner=" + f(w[base]) + "," + f(w[base + 1])
                + "," + f(w[base + 2]) + " area=" + f(w[base + 3]) + " emissionRecord=" + f(w[base + 7])
                + " power=" + f(w[base + 11]) + " normal=" + f(w[base + 12]) + "," + f(w[base + 13])
                + "," + f(w[base + 14]) + " material=" + w[base + 15]
                + " leafNode=" + w[w[7] + emitter]);
        }

        // Replay the GLSL traversal for a point 1 m below the quad.
        float[] point = {0.5F, 0.0F, 0.5F};
        for (int frame = 0; frame < 5; frame++) {
            float seed = (frame * 2654435761L % 1000L) / 1000.0F;
            float[] pdf = new float[1];
            int leaf = pickLightLeaf(w, point, seed, pdf);
            System.out.println("frame " + frame + " seed=" + seed + " -> leaf=" + leaf
                + " treePdf=" + pdf[0]);
            if (leaf < 0) {
                continue;
            }
            int base = w[4] + leaf * 16;
            float area = f(w[base + 3]);
            float distanceSquared = 1.0F; // point is 1 m below
            float lightCosine = 1.0F;     // bottom-facing emitter
            float lightPdf = pdf[0] * distanceSquared / (area * lightCosine);
            System.out.println("   lightPdf=" + lightPdf + " radiance=" + f(w[base + 7])
                + " estimate=" + (f(w[base + 7]) / lightPdf));
        }

        // Second check: what does the emitter face downward look like if we swap the winding?
        // Second check: a multi-emitter tree must reach every leaf and sum its pdf to one.
        int quads = 6;
        float[] manyVertices = new float[quads * 2 * 9];
        float[] manyMaterials = new float[quads * 2 * 28];
        for (int quad = 0; quad < quads; quad++) {
            float x0 = quad * 4.0F;
            float x1 = x0 + 1.0F;
            int vertexBase = quad * 18;
            float[] quadVertices = {
                x0, 1, 0, x1, 1, 0, x1, 1, 1,
                x0, 1, 0, x1, 1, 1, x0, 1, 1
            };
            System.arraycopy(quadVertices, 0, manyVertices, vertexBase, 18);
            for (int triangle = 0; triangle < 2; triangle++) {
                int offset = (quad * 2 + triangle) * 28;
                manyMaterials[offset] = 1.0F;
                manyMaterials[offset + 1] = 1.0F;
                manyMaterials[offset + 2] = 1.0F;
                manyMaterials[offset + 3] = 1.0F;
                manyMaterials[offset + 5] = 1.0F;
                manyMaterials[offset + 14] = 1.0F;
                manyMaterials[offset + 15] = 1.0F;
                manyMaterials[offset + 20] = 0.88F;
                manyMaterials[offset + 22] = 1.5F;
                manyMaterials[offset + 23] = 1.04F;
                manyMaterials[offset + 27] = 1.0F;
            }
        }
        RayTracingScene.SceneGeometry.SectionGeometry manySection =
            ctor.newInstance(0, 0, 0, manyVertices, manyMaterials);
        RayTracingLightTree.Data many = RayTracingLightTree.build(
            List.of(manySection), manyMaterials, 0, 0, 0);
        int[] mw = many.words();
        int[] hits = new int[many.emitterCount()];
        double pdfSum = 0.0;
        int samples = 20000;
        for (int sample = 0; sample < samples; sample++) {
            float seed = (sample + 0.5F) / samples;
            float[] manyPdf = new float[1];
            int leaf = pickLightLeaf(mw, new float[] {21.5F, 0.0F, 0.5F}, seed, manyPdf);
            if (leaf >= 0 && leaf < hits.length) {
                hits[leaf]++;
                pdfSum += manyPdf[0];
            }
        }
        int reached = 0;
        for (int count : hits) {
            if (count > 0) reached++;
        }
        System.out.println("multi: emitters=" + many.emitterCount() + " nodes=" + mw[0]
            + " leavesReached=" + reached + "/" + many.emitterCount()
            + " meanTreePdf=" + (pdfSum / samples));

        System.out.println("material emission at index 22 = " + materials[22]);
    }

    private static int pickLightLeaf(int[] values, float[] point, float seed, float[] pdfOut) {
        final int leafFlag = 0x80000000;
        float pdf = 1.0F;
        pdfOut[0] = pdf;
        if (values[0] == 0 || values[1] == 0) {
            return -1;
        }
        int node = 0;
        float value = seed;
        for (int depth = 0; depth < 64; depth++) {
            int childOrLeaf = values[values[2] + node];
            if ((childOrLeaf & leafFlag) != 0) {
                pdfOut[0] = pdf;
                return childOrLeaf & 0x7fffffff;
            }
            int left = childOrLeaf;
            int right = left + 1;
            float leftProbability = branchProbability(values, left, right, point);
            float rightProbability = 1.0F - leftProbability;
            if (!(leftProbability >= 0.0F)) {
                return -1;
            }
            if (value < leftProbability) {
                pdf *= leftProbability;
                value /= leftProbability;
                node = left;
            } else {
                pdf *= rightProbability;
                value = (value - leftProbability) / rightProbability;
                node = right;
            }
        }
        return -1;
    }

    private static float branchProbability(int[] values, int left, int right, float[] point) {
        float leftScore = Math.max(nodeFloat(values, left, 3), 0.0F) * nodeDistanceSquared(values, right, point);
        float rightScore = Math.max(nodeFloat(values, right, 3), 0.0F) * nodeDistanceSquared(values, left, point);
        float sum = leftScore + rightScore;
        return sum > 0.0F ? leftScore / sum : -1.0F;
    }

    private static float nodeDistanceSquared(int[] values, int node, float[] point) {
        float[] minimum = {nodeFloat(values, node, 0), nodeFloat(values, node, 1), nodeFloat(values, node, 2)};
        float[] maximum = {nodeFloat(values, node, 4), nodeFloat(values, node, 5), nodeFloat(values, node, 6)};
        float sum = 0.0F;
        for (int axis = 0; axis < 3; axis++) {
            float closest = Math.min(Math.max(point[axis], minimum[axis]), maximum[axis]);
            float delta = point[axis] - closest;
            sum += delta * delta;
        }
        return sum + nodeFloat(values, node, 7);
    }

    private static float nodeFloat(int[] values, int node, int word) {
        return f(values[8 + node * 8 + word]);
    }

    private static float f(int bits) {
        return Float.intBitsToFloat(bits);
    }
}
