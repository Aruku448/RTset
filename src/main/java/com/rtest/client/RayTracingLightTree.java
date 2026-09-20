package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Prime-compatible emissive-triangle sampler for the RT scene.
 *
 * <p>The scene is flat on the GPU, so this is the same power-weighted binary light tree as
 * Prime's section/world trees, with the two levels collapsed into one tree. Each leaf is one
 * constant-radiance triangle; a separate material-to-emitter table makes reverse MIS O(1).</p>
 */
final class RayTracingLightTree {
    private static final int MATERIAL_STRIDE = 28;
    private static final int MATERIAL_TINT_OFFSET = 0;
    private static final int MATERIAL_NORMAL_OFFSET = 4;
    private static final int MATERIAL_TEXTURE_KIND_OFFSET = 15;
    private static final int HEADER_WORDS = 8;
    private static final int NODE_WORDS = 8;
    private static final int EMITTER_WORDS = 16;
    private static final int LEAF_FLAG = Integer.MIN_VALUE;
    private static final int INDEX_MASK = Integer.MAX_VALUE;
    private static final float MIN_SOFTENING_DISTANCE_SQUARED = 0.25F;

    private RayTracingLightTree() {
    }

    static Data build(List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                      float[] materialData, double originX, double originY, double originZ) {
        List<Emitter> emitters = new ArrayList<>();
        int materialIndex = 0;
        int[] materialToEmitter = new int[materialData.length / MATERIAL_STRIDE];
        Arrays.fill(materialToEmitter, -1);
        for (RayTracingScene.SceneGeometry.SectionGeometry section : sections) {
            for (int triangle = 0; triangle < section.triangleCount(); triangle++) {
                int materialOffset = materialIndex * MATERIAL_STRIDE;
                float emission = materialData[materialOffset + 22];
                if (emission > 0.0F && Float.isFinite(emission)) {
                    int vertexOffset = triangle * 9;
                    float ax = (float)(section.originX - originX) + section.vertices[vertexOffset];
                    float ay = (float)(section.originY - originY) + section.vertices[vertexOffset + 1];
                    float az = (float)(section.originZ - originZ) + section.vertices[vertexOffset + 2];
                    float bx = (float)(section.originX - originX) + section.vertices[vertexOffset + 3];
                    float by = (float)(section.originY - originY) + section.vertices[vertexOffset + 4];
                    float bz = (float)(section.originZ - originZ) + section.vertices[vertexOffset + 5];
                    float cx = (float)(section.originX - originX) + section.vertices[vertexOffset + 6];
                    float cy = (float)(section.originY - originY) + section.vertices[vertexOffset + 7];
                    float cz = (float)(section.originZ - originZ) + section.vertices[vertexOffset + 8];
                    float e1x = bx - ax;
                    float e1y = by - ay;
                    float e1z = bz - az;
                    float e2x = cx - ax;
                    float e2y = cy - ay;
                    float e2z = cz - az;
                    float nx = e1y * e2z - e1z * e2y;
                    float ny = e1z * e2x - e1x * e2z;
                    float nz = e1x * e2y - e1y * e2x;
                    float twiceArea = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (twiceArea > 1.0E-8F && Float.isFinite(twiceArea)) {
                        float area = twiceArea * 0.5F;
                        float inverse = 1.0F / twiceArea;
                        // The GPU evaluates the sampled emitter as surface color * emission.
                        // Keep the tree's selection PDF aligned with that radiance instead of
                        // assigning the same power to a black atlas triangle and a bright one.
                        // This is an importance proxy only; the sampled radiance and PDF still
                        // make the direct-light estimator unbiased.
                        float power = area * (float)Math.PI * emission
                            * emitterImportance(materialData, materialOffset);
                        if (!(power > 0.0F) || !Float.isFinite(power)) {
                            continue;
                        }
                        // Use the face normal carried by the material ABI. Baked-quad winding is
                        // not guaranteed to match the face direction used by the hit shader; if
                        // we retain the cross-product sign, the source can be visible while its
                        // area-light cosine rejects every shadow ray on that face.
                        float emitterNormalX = materialData[materialOffset + MATERIAL_NORMAL_OFFSET];
                        float emitterNormalY = materialData[materialOffset + MATERIAL_NORMAL_OFFSET + 1];
                        float emitterNormalZ = materialData[materialOffset + MATERIAL_NORMAL_OFFSET + 2];
                        float normalLength = (float)Math.sqrt(
                            emitterNormalX * emitterNormalX
                                + emitterNormalY * emitterNormalY
                                + emitterNormalZ * emitterNormalZ);
                        if (!(normalLength > 1.0E-8F) || !Float.isFinite(normalLength)) {
                            emitterNormalX = nx * inverse;
                            emitterNormalY = ny * inverse;
                            emitterNormalZ = nz * inverse;
                        } else {
                            float normalInverse = 1.0F / normalLength;
                            emitterNormalX *= normalInverse;
                            emitterNormalY *= normalInverse;
                            emitterNormalZ *= normalInverse;
                        }
                        int emitterIndex = emitters.size();
                        emitters.add(new Emitter(ax, ay, az, e1x, e1y, e1z, e2x, e2y, e2z,
                            emitterNormalX, emitterNormalY, emitterNormalZ, area, emission, power,
                            materialIndex, emitterIndex));
                        materialToEmitter[materialIndex] = emitterIndex;
                    }
                }
                materialIndex++;
            }
        }
        return Data.create(emitters, materialToEmitter);
    }

    /**
     * Estimates the largest working-space component of the emitter radiance. The atlas texel is
     * intentionally not sampled here: it is read by the GPU at the sampled barycentric point, and
     * this value only steers the light-tree PDF. Using the material tint still separates a dark
     * tinted surface from a white one and preserves positive support for textured emitters.
     */
    private static float emitterImportance(float[] materialData, int materialOffset) {
        float red = finiteNonNegative(materialData[materialOffset + MATERIAL_TINT_OFFSET]);
        float green = finiteNonNegative(materialData[materialOffset + MATERIAL_TINT_OFFSET + 1]);
        float blue = finiteNonNegative(materialData[materialOffset + MATERIAL_TINT_OFFSET + 2]);
        if (materialData[materialOffset + MATERIAL_TEXTURE_KIND_OFFSET] > 0.5F) {
            // The textured GPU path decodes tint as sRGB before converting it to its working
            // space. Match that transfer function for the PDF proxy; the untextured path keeps
            // tint in the already-packed working representation.
            red = decodeSrgb(red);
            green = decodeSrgb(green);
            blue = decodeSrgb(blue);
            float workingRed = 0.6274039F * red + 0.3292830F * green + 0.0433131F * blue;
            float workingGreen = 0.0690973F * red + 0.9195404F * green + 0.0113623F * blue;
            float workingBlue = 0.0163914F * red + 0.0880133F * green + 0.8955953F * blue;
            red = workingRed;
            green = workingGreen;
            blue = workingBlue;
        }
        float importance = Math.max(red, Math.max(green, blue));
        // Keep a tiny positive support even when a tint is black but the atlas texture is not
        // available to this CPU builder. A zero tree power would make the GPU never sample the
        // emitter, while this floor only increases variance for that rare fallback case.
        return Math.max(importance, 1.0E-5F);
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(value, 0.0F) : 0.0F;
    }

    private static float decodeSrgb(float encoded) {
        encoded = Math.min(encoded, 1.0F);
        return encoded <= 0.04045F
            ? encoded / 12.92F
            : (float)Math.pow((encoded + 0.055F) / 1.055F, 2.4F);
    }

    static final class Data {
        private final int[] words;
        private final int emitterCount;

        private Data(int[] words, int emitterCount) {
            this.words = words;
            this.emitterCount = emitterCount;
        }

        static Data create(List<Emitter> source, int[] materialToEmitter) {
            if (source.isEmpty()) {
                int[] words = new int[HEADER_WORDS + materialToEmitter.length];
                words[5] = materialToEmitter.length;
                words[6] = HEADER_WORDS;
                words[7] = 0;
                for (int i = 0; i < materialToEmitter.length; i++) {
                    words[HEADER_WORDS + i] = materialToEmitter[i];
                }
                return new Data(words, 0);
            }
            List<Emitter> emitters = new ArrayList<>(source);
            List<Node> nodes = new ArrayList<>(emitters.size() * 2 - 1);
            int[] leafNodes = new int[emitters.size()];
            Arrays.fill(leafNodes, -1);
            nodes.add(Node.create(emitters, 0, emitters.size(), -1));
            populate(emitters, 0, emitters.size(), 0, nodes, leafNodes);

            int nodeOffset = HEADER_WORDS;
            int forwardOffset = nodeOffset + nodes.size() * NODE_WORDS;
            int reverseOffset = forwardOffset + nodes.size();
            int emitterOffset = reverseOffset + nodes.size();
            int materialMapOffset = emitterOffset + emitters.size() * EMITTER_WORDS;
            int leafNodeOffset = materialMapOffset + materialToEmitter.length;
            int[] words = new int[leafNodeOffset + emitters.size()];
            words[0] = nodes.size();
            words[1] = emitters.size();
            words[2] = forwardOffset;
            words[3] = reverseOffset;
            words[4] = emitterOffset;
            words[5] = materialToEmitter.length;
            words[6] = materialMapOffset;
            words[7] = leafNodeOffset;
            int cursor = nodeOffset;
            for (Node node : nodes) {
                words[cursor++] = Float.floatToRawIntBits(node.minX);
                words[cursor++] = Float.floatToRawIntBits(node.minY);
                words[cursor++] = Float.floatToRawIntBits(node.minZ);
                words[cursor++] = Float.floatToRawIntBits(node.power);
                words[cursor++] = Float.floatToRawIntBits(node.maxX);
                words[cursor++] = Float.floatToRawIntBits(node.maxY);
                words[cursor++] = Float.floatToRawIntBits(node.maxZ);
                words[cursor++] = Float.floatToRawIntBits(node.softening);
            }
            cursor = forwardOffset;
            for (Node node : nodes) {
                words[cursor++] = node.right < 0 ? node.left | LEAF_FLAG : node.left;
            }
            cursor = reverseOffset;
            for (Node node : nodes) {
                words[cursor++] = node.parent < 0 ? 0xffffffff : node.parent;
            }
            cursor = emitterOffset;
            for (Emitter emitter : emitters) {
                words[cursor++] = Float.floatToRawIntBits(emitter.ax);
                words[cursor++] = Float.floatToRawIntBits(emitter.ay);
                words[cursor++] = Float.floatToRawIntBits(emitter.az);
                words[cursor++] = Float.floatToRawIntBits(emitter.area);
                words[cursor++] = Float.floatToRawIntBits(emitter.e1x);
                words[cursor++] = Float.floatToRawIntBits(emitter.e1y);
                words[cursor++] = Float.floatToRawIntBits(emitter.e1z);
                words[cursor++] = Float.floatToRawIntBits(emitter.emission);
                words[cursor++] = Float.floatToRawIntBits(emitter.e2x);
                words[cursor++] = Float.floatToRawIntBits(emitter.e2y);
                words[cursor++] = Float.floatToRawIntBits(emitter.e2z);
                words[cursor++] = Float.floatToRawIntBits(emitter.power);
                words[cursor++] = Float.floatToRawIntBits(emitter.nx);
                words[cursor++] = Float.floatToRawIntBits(emitter.ny);
                words[cursor++] = Float.floatToRawIntBits(emitter.nz);
                words[cursor++] = emitter.materialIndex;
            }
            int[] packedEmitterBySource = new int[emitters.size()];
            for (int packed = 0; packed < emitters.size(); packed++) {
                packedEmitterBySource[emitters.get(packed).sourceIndex] = packed;
            }
            for (int i = 0; i < materialToEmitter.length; i++) {
                int sourceEmitter = materialToEmitter[i];
                words[materialMapOffset + i] = sourceEmitter < 0 ? -1 : packedEmitterBySource[sourceEmitter];
            }
            for (int packed = 0; packed < emitters.size(); packed++) {
                words[leafNodeOffset + packed] = leafNodes[emitters.get(packed).sourceIndex];
            }
            return new Data(words, emitters.size());
        }

        int[] words() { return this.words; }
        int emitterCount() { return this.emitterCount; }

        private static void populate(List<Emitter> emitters, int start, int end, int nodeIndex,
                                     List<Node> nodes, int[] leafNodes) {
            Node node = nodes.get(nodeIndex);
            if (end - start == 1) {
                node.left = start;
                leafNodes[emitters.get(start).sourceIndex] = nodeIndex;
                return;
            }
            int axis = node.longestAxis();
            emitters.subList(start, end).sort(Comparator.comparingDouble(e -> e.center(axis)));
            int middle = start + (end - start) / 2;
            int left = nodes.size();
            nodes.add(Node.create(emitters, start, middle, nodeIndex));
            int right = nodes.size();
            nodes.add(Node.create(emitters, middle, end, nodeIndex));
            node.left = left;
            node.right = right;
            populate(emitters, start, middle, left, nodes, leafNodes);
            populate(emitters, middle, end, right, nodes, leafNodes);
        }
    }

    private static final class Node {
        float minX, minY, minZ, maxX, maxY, maxZ, power, softening;
        int parent, left = -1, right = -1;

        static Node create(List<Emitter> emitters, int start, int end, int parent) {
            Node node = new Node();
            node.minX = node.minY = node.minZ = Float.POSITIVE_INFINITY;
            node.maxX = node.maxY = node.maxZ = Float.NEGATIVE_INFINITY;
            for (int i = start; i < end; i++) {
                Emitter e = emitters.get(i);
                node.minX = Math.min(node.minX, Math.min(e.ax, Math.min(e.ax + e.e1x, e.ax + e.e2x)));
                node.minY = Math.min(node.minY, Math.min(e.ay, Math.min(e.ay + e.e1y, e.ay + e.e2y)));
                node.minZ = Math.min(node.minZ, Math.min(e.az, Math.min(e.az + e.e1z, e.az + e.e2z)));
                node.maxX = Math.max(node.maxX, Math.max(e.ax, Math.max(e.ax + e.e1x, e.ax + e.e2x)));
                node.maxY = Math.max(node.maxY, Math.max(e.ay, Math.max(e.ay + e.e1y, e.ay + e.e2y)));
                node.maxZ = Math.max(node.maxZ, Math.max(e.az, Math.max(e.az + e.e1z, e.az + e.e2z)));
                node.power += e.power;
            }
            // The branch score is power * distanceSquared(other). Only a tiny epsilon is added to
            // keep a query point inside the node bounds from producing an infinite importance.
            // Adding the whole node diagonal here would flatten the distance term, so the sampler
            // would pick far, usually occluded emitters as often as the nearby lamp. That makes
            // the tree pdf for the one visible light ~1/N, which then drives the power-heuristic
            // MIS weight toward zero and leaves the room dark.
            node.softening = MIN_SOFTENING_DISTANCE_SQUARED;
            node.parent = parent;
            return node;
        }

        int longestAxis() {
            float x = maxX - minX, y = maxY - minY, z = maxZ - minZ;
            return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
        }
    }

    private record Emitter(float ax, float ay, float az, float e1x, float e1y, float e1z,
                           float e2x, float e2y, float e2z, float nx, float ny, float nz,
                           float area, float emission, float power, int materialIndex, int sourceIndex) {
        float center(int axis) {
            return switch (axis) {
                case 0 -> ax + 0.5F * (e1x + e2x);
                case 1 -> ay + 0.5F * (e1y + e2y);
                default -> az + 0.5F * (e1z + e2z);
            };
        }
    }
}
