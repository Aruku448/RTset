package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Prime-compatible emissive-triangle sampler for the RT scene.
 *
 * <p>This power-weighted binary light tree has one constant-radiance triangle per leaf.
 * The CPU path partitions along the longest
 * axis; the GPU path reduces a Morton-ordered binary heap. Both share the same distance/power
 * sampling contract and reverse MIS lookup ABI.</p>
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
    // A full block face triangle is 0.5 block². Minecraft block-light levels describe a
    // source, not the authored mesh area; normalize small torch/lantern panels to this
    // reference so their total light is not lost merely because their model is compact.
    private static final float REFERENCE_EMITTER_AREA = 0.5F;

    private RayTracingLightTree() {
    }

    /** Reuse an identical emitter hierarchy; resize its material lookup when only dark spans change. */
    static Data buildOrReuse(RayTracingScene.SceneGeometry previous,
                            List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                            double originX, double originY, double originZ,
                            RayTracingMaterialBuffer.Layout materialLayout) {
        boolean gpuRequested = RayTracingClientConfig.SPEC.isLoaded()
            && RayTracingClientConfig.INSTANCE.gpuLightTreeEnabled.get();
        if (previous != null
                && previous.lightTree.gpuBuild() == (gpuRequested && previous.lightTree.emitterCount() >= 1024)
                && sameLightInputs(previous, sections, originX, originY, originZ, materialLayout)) {
            return previous.lightTree.withMaterialMapLength(materialLayout.highWaterTriangle());
        }
        return build(sections, originX, originY, originZ, materialLayout,
            gpuRequested);
    }

    private static final int[] LIGHT_MATERIAL_INPUTS = {0, 1, 2, 4, 5, 6, 15, 22};

    static boolean sameLightInputs(RayTracingScene.SceneGeometry previous,
                                          List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                                          double originX, double originY, double originZ,
                                          RayTracingMaterialBuffer.Layout layout) {
        if (Double.doubleToRawLongBits(previous.originX) != Double.doubleToRawLongBits(originX)
                || Double.doubleToRawLongBits(previous.originY) != Double.doubleToRawLongBits(originY)
                || Double.doubleToRawLongBits(previous.originZ) != Double.doubleToRawLongBits(originZ)
                || previous.lightTree.words()[5] != previous.materialLayout.highWaterTriangle()) return false;
        int oldIndex = 0;
        for (RayTracingScene.SceneGeometry.SectionGeometry next : sections) {
            if (next.emissiveTriangles.length == 0) continue;
            while (oldIndex < previous.sections.size()
                    && previous.sections.get(oldIndex).emissiveTriangles.length == 0) oldIndex++;
            if (oldIndex == previous.sections.size()) return false;
            RayTracingScene.SceneGeometry.SectionGeometry old = previous.sections.get(oldIndex++);
            if (previous.materialLayout.baseTriangle(old) != layout.baseTriangle(next)) return false;
            if (old == next) continue;
            // Keep source order, candidate indices and raw bits: stable sort ties and floating
            // accumulation order must match. Never use a hash as proof of equality.
            if (old.originX != next.originX || old.originY != next.originY || old.originZ != next.originZ
                    || !Arrays.equals(old.emissiveTriangles, next.emissiveTriangles)) return false;
            for (int triangle : next.emissiveTriangles) {
                for (int v = triangle * 9; v < triangle * 9 + 9; v++) {
                    if (Float.floatToRawIntBits(old.vertices[v]) != Float.floatToRawIntBits(next.vertices[v])) return false;
                }
                for (int field : LIGHT_MATERIAL_INPUTS) {
                    int m = triangle * MATERIAL_STRIDE + field;
                    if (Float.floatToRawIntBits(old.materialData[m]) != Float.floatToRawIntBits(next.materialData[m])) return false;
                }
            }
        }
        while (oldIndex < previous.sections.size()
                && previous.sections.get(oldIndex).emissiveTriangles.length == 0) oldIndex++;
        return oldIndex == previous.sections.size();
    }

    static Data build(List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                      float[] materialData, double originX, double originY, double originZ) {
        return build(sections, originX, originY, originZ,
            RayTracingMaterialBuffer.Layout.compact(sections));
    }

    static Data build(List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                      double originX, double originY, double originZ) {
        return build(sections, originX, originY, originZ,
            RayTracingMaterialBuffer.Layout.compact(sections));
    }

    static Data build(List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                      double originX, double originY, double originZ,
                      RayTracingMaterialBuffer.Layout materialLayout) {
        return build(sections, originX, originY, originZ, materialLayout, false);
    }

    static Data build(List<RayTracingScene.SceneGeometry.SectionGeometry> sections,
                      double originX, double originY, double originZ,
                      RayTracingMaterialBuffer.Layout materialLayout, boolean gpu) {
        List<Emitter> emitters = new ArrayList<>();
        // GPU packing writes the final material lookup directly; do not allocate and
        // initialize an equally large intermediate table for that path.
        int[] materialToEmitter = gpu ? null : new int[materialLayout.highWaterTriangle()];
        if (materialToEmitter != null) Arrays.fill(materialToEmitter, -1);
        for (RayTracingScene.SceneGeometry.SectionGeometry section : sections) {
            int materialBase = materialLayout.baseTriangle(section);
            float[] sectionMaterials = section.materialData;
            for (int triangle : section.emissiveTriangles) {
                int materialOffset = triangle * MATERIAL_STRIDE;
                float emission = sectionMaterials[materialOffset + 22];
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
                        // Normalize compact emissive panels to a reference block-face triangle:
                        // Minecraft's light level describes source strength, while using raw
                        // mesh area makes lanterns and torches contribute far less than a full
                        // glowstone face. Keep the tree's selection PDF aligned with that
                        // normalized radiance.
                        float emitterEmission = emission * REFERENCE_EMITTER_AREA / area;
                        float power = area * (float)Math.PI * emitterEmission
                            * emitterImportance(sectionMaterials, materialOffset);
                        if (!(power > 0.0F) || !Float.isFinite(power)) {
                            continue;
                        }
                        // Use the face normal carried by the material ABI. Baked-quad winding is
                        // not guaranteed to match the face direction used by the hit shader; if
                        // we retain the cross-product sign, the source can be visible while its
                        // area-light cosine rejects every shadow ray on that face.
                        float emitterNormalX = sectionMaterials[materialOffset + MATERIAL_NORMAL_OFFSET];
                        float emitterNormalY = sectionMaterials[materialOffset + MATERIAL_NORMAL_OFFSET + 1];
                        float emitterNormalZ = sectionMaterials[materialOffset + MATERIAL_NORMAL_OFFSET + 2];
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
                        int materialIndex = materialBase + triangle;
                        emitters.add(new Emitter(ax, ay, az, e1x, e1y, e1z, e2x, e2y, e2z,
                            emitterNormalX, emitterNormalY, emitterNormalZ, area, emitterEmission, power,
                            materialIndex, emitterIndex));
                        if (materialToEmitter != null) materialToEmitter[materialIndex] = emitterIndex;
                    }
                }
            }
        }
        if (gpu && emitters.size() >= 1024)
            return Data.createGpu(emitters, materialLayout.highWaterTriangle());
        if (materialToEmitter == null) {
            materialToEmitter = new int[materialLayout.highWaterTriangle()];
            Arrays.fill(materialToEmitter, -1);
            for (Emitter emitter : emitters) materialToEmitter[emitter.materialIndex] = emitter.sourceIndex;
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
        private final boolean gpuBuild;

        private Data(int[] words, int emitterCount) {
            this(words, emitterCount, false);
        }

        private Data(int[] words, int emitterCount, boolean gpuBuild) {
            this.words = words;
            this.emitterCount = emitterCount;
            this.gpuBuild = gpuBuild;
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
            Emitter[] emitters = source.toArray(Emitter[]::new);
            List<Node> nodes = new ArrayList<>(emitters.length * 2 - 1);
            int[] leafNodes = new int[emitters.length];
            Arrays.fill(leafNodes, -1);
            RadixWorkspace sorter = emitters.length >= RadixWorkspace.MIN_RADIX_SIZE
                ? new RadixWorkspace(emitters.length) : null;
            nodes.add(Node.create(emitters, 0, emitters.length, -1));
            populate(emitters, 0, emitters.length, 0, nodes, leafNodes, sorter);

            int nodeOffset = HEADER_WORDS;
            int forwardOffset = nodeOffset + nodes.size() * NODE_WORDS;
            int reverseOffset = forwardOffset + nodes.size();
            int emitterOffset = reverseOffset + nodes.size();
            int materialMapOffset = emitterOffset + emitters.length * EMITTER_WORDS;
            int leafNodeOffset = materialMapOffset + materialToEmitter.length;
            int[] words = new int[leafNodeOffset + emitters.length];
            words[0] = nodes.size();
            words[1] = emitters.length;
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
                writeEmitter(words, cursor, emitter);
                cursor += EMITTER_WORDS;
            }
            int[] packedEmitterBySource = new int[emitters.length];
            for (int packed = 0; packed < emitters.length; packed++) {
                packedEmitterBySource[emitters[packed].sourceIndex] = packed;
            }
            for (int i = 0; i < materialToEmitter.length; i++) {
                int sourceEmitter = materialToEmitter[i];
                words[materialMapOffset + i] = sourceEmitter < 0 ? -1 : packedEmitterBySource[sourceEmitter];
            }
            for (int packed = 0; packed < emitters.length; packed++) {
                words[leafNodeOffset + packed] = leafNodes[emitters[packed].sourceIndex];
            }
            return new Data(words, emitters.length);
        }

        /** Resize only the lookup span: emitter addresses, tree topology and PDFs are unchanged. */
        Data withMaterialMapLength(int length) {
            int oldLength = words[5];
            if (length == oldLength) return this;
            if (length < 0) throw new IllegalArgumentException("negative material map length");
            int map = words[6];
            // Never truncate a live emitter lookup if a malformed layout reaches this seam.
            for (int i = length; i < oldLength; i++) {
                if (words[map + i] != -1) throw new IllegalArgumentException("truncated live emitter");
            }
            int[] resized = new int[Math.addExact(Math.addExact(map, length), emitterCount)];
            System.arraycopy(words, 0, resized, 0, map);
            Arrays.fill(resized, map, map + length, -1);
            System.arraycopy(words, map, resized, map, Math.min(length, oldLength));
            if (emitterCount > 0) System.arraycopy(words, words[7], resized, map + length, emitterCount);
            resized[5] = length;
            resized[7] = emitterCount == 0 ? 0 : map + length;
            return new Data(resized, emitterCount, gpuBuild);
        }

        int[] words() { return this.words; }
        int emitterCount() { return this.emitterCount; }

        boolean gpuBuild() { return gpuBuild; }

        // Spatial ordering is done once. Heap leaves are filled in DFS order, so each
        // internal subtree spans a contiguous Morton interval even for non-power-of-two N.
        private static Data createGpu(List<Emitter> source, int materialMapLength) {
            Emitter[] sorted = source.toArray(Emitter[]::new);
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (Emitter e : sorted) {
                float x = e.ax + .5F * (e.e1x + e.e2x), y = e.ay + .5F * (e.e1y + e.e2y), z = e.az + .5F * (e.e1z + e.e2z);
                min[0] = Math.min(min[0], x); max[0] = Math.max(max[0], x);
                min[1] = Math.min(min[1], y); max[1] = Math.max(max[1], y);
                min[2] = Math.min(min[2], z); max[2] = Math.max(max[2], z);
            }
            int[] keys = new int[sorted.length];
            for (Emitter e : sorted) {
                keys[e.sourceIndex] = morton(e.ax + .5F * (e.e1x + e.e2x), e.ay + .5F * (e.e1y + e.e2y),
                    e.az + .5F * (e.e1z + e.e2z), min, max);
            }
            Emitter[] scratch = new Emitter[sorted.length];
            Emitter[] from = sorted, to = scratch;
            int[] counts = new int[256];
            // Four stable byte passes: O(N), deterministic ties, no recursive sorting.
            for (int shift = 0; shift < 32; shift += 8) {
                Arrays.fill(counts, 0);
                for (Emitter e : from) counts[(keys[e.sourceIndex] >>> shift) & 255]++;
                int cursor = 0;
                for (int bucket = 0; bucket < 256; bucket++) {
                    int count = counts[bucket]; counts[bucket] = cursor; cursor += count;
                }
                for (Emitter e : from) to[counts[(keys[e.sourceIndex] >>> shift) & 255]++] = e;
                Emitter[] swap = from; from = to; to = swap;
            }
            int n = sorted.length, nodes = Math.subtractExact(Math.multiplyExact(n, 2), 1);
            int forward = Math.addExact(HEADER_WORDS, Math.multiplyExact(nodes, NODE_WORDS));
            int reverse = Math.addExact(forward, nodes), emitterOffset = Math.addExact(reverse, nodes);
            int map = Math.addExact(emitterOffset, Math.multiplyExact(n, EMITTER_WORDS));
            int leaf = Math.addExact(map, materialMapLength);
            int[] words = new int[Math.addExact(leaf, n)];
            words[0] = nodes; words[1] = n; words[2] = forward; words[3] = reverse;
            words[4] = emitterOffset; words[5] = materialMapLength; words[6] = map; words[7] = leaf;
            Arrays.fill(words, map, leaf, -1);
            packHeapLeaves(sorted, 0, 0, words);
            return new Data(words, n, true);
        }

        private static int packHeapLeaves(Emitter[] sorted, int node, int rank, int[] words) {
            int n = sorted.length;
            if (node < n - 1) {
                rank = packHeapLeaves(sorted, 2 * node + 1, rank, words);
                return packHeapLeaves(sorted, 2 * node + 2, rank, words);
            }
            int packed = node - (n - 1);
            Emitter emitter = sorted[rank];
            writeEmitter(words, words[4] + packed * EMITTER_WORDS, emitter);
            words[words[6] + emitter.materialIndex] = packed;
            return rank + 1;
        }

        private static int morton(float x, float y, float z, float[] min, float[] max) {
            return spread(quantize(x, min[0], max[0])) | (spread(quantize(y, min[1], max[1])) << 1)
                | (spread(quantize(z, min[2], max[2])) << 2);
        }
        private static int quantize(float v, float min, float max) {
            return max > min ? Math.max(0, Math.min(1023, (int)((v - min) / (max - min) * 1023))) : 0;
        }
        private static int spread(int v) {
            v = (v | v << 16) & 0x030000FF; v = (v | v << 8) & 0x0300F00F;
            v = (v | v << 4) & 0x030C30C3; v = (v | v << 2) & 0x09249249; return v;
        }

        /** Same heap reduction as compute, used only after a GPU build failure and in tests. */
        int[] completeOnCpu() {
            if (!gpuBuild) return words;
            int[] result = words.clone();
            int n = emitterCount;
            for (int node = result[0] - 1; node >= 0; node--) {
                int b = HEADER_WORDS + node * NODE_WORDS;
                if (node >= n - 1) {
                    int packed = node - n + 1, e = result[4] + packed * EMITTER_WORDS;
                    for (int a = 0; a < 3; a++) {
                        float p = Float.intBitsToFloat(result[e + a]);
                        float q = p + Float.intBitsToFloat(result[e + 4 + a]);
                        float r = p + Float.intBitsToFloat(result[e + 8 + a]);
                        result[b + a] = Float.floatToRawIntBits(Math.min(p, Math.min(q, r)));
                        result[b + 4 + a] = Float.floatToRawIntBits(Math.max(p, Math.max(q, r)));
                    }
                    result[b + 3] = result[e + 11];
                    result[result[2] + node] = packed | LEAF_FLAG;
                    result[result[7] + packed] = node;
                } else {
                    int left = 2 * node + 1, right = left + 1;
                    int l = HEADER_WORDS + left * NODE_WORDS, r = HEADER_WORDS + right * NODE_WORDS;
                    for (int a = 0; a < 3; a++) {
                        result[b + a] = Float.floatToRawIntBits(Math.min(Float.intBitsToFloat(result[l + a]), Float.intBitsToFloat(result[r + a])));
                        result[b + 4 + a] = Float.floatToRawIntBits(Math.max(Float.intBitsToFloat(result[l + 4 + a]), Float.intBitsToFloat(result[r + 4 + a])));
                    }
                    result[b + 3] = Float.floatToRawIntBits(Float.intBitsToFloat(result[l + 3]) + Float.intBitsToFloat(result[r + 3]));
                    result[result[2] + node] = left;
                }
                result[b + 7] = Float.floatToRawIntBits(MIN_SOFTENING_DISTANCE_SQUARED);
                result[result[3] + node] = node == 0 ? -1 : (node - 1) / 2;
            }
            return result;
        }

        private static void writeEmitter(int[] words, int cursor, Emitter emitter) {
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

        private static void populate(Emitter[] emitters, int start, int end, int nodeIndex,
                                     List<Node> nodes, int[] leafNodes, RadixWorkspace sorter) {
            Node node = nodes.get(nodeIndex);
            if (end - start == 1) {
                node.left = start;
                leafNodes[emitters[start].sourceIndex] = nodeIndex;
                return;
            }
            int axis = node.longestAxis();
            if (end - start < RadixWorkspace.MIN_RADIX_SIZE) {
                Arrays.sort(emitters, start, end, RadixWorkspace.comparator(axis));
            } else {
                sorter.sort(emitters, start, end, axis);
            }
            int middle = start + (end - start) / 2;
            int left = nodes.size();
            nodes.add(Node.create(emitters, start, middle, nodeIndex));
            int right = nodes.size();
            nodes.add(Node.create(emitters, middle, end, nodeIndex));
            node.left = left;
            node.right = right;
            populate(emitters, start, middle, left, nodes, leafNodes, sorter);
            populate(emitters, middle, end, right, nodes, leafNodes, sorter);
        }
    }

    /** Stable IEEE-float order, identical to comparingDouble(float), including -0 and NaN. */
    private static int floatSortKey(float value) {
        int bits = Float.floatToIntBits(value);
        return bits < 0 ? ~bits : bits ^ Integer.MIN_VALUE;
    }

    /** One scratch array per build; four stable byte passes give O(k) sorting at each node. */
    private static final class RadixWorkspace {
        static final int MIN_RADIX_SIZE = 128;
        private static final Comparator<Emitter> X = (a, b) -> Integer.compareUnsigned(a.keyX, b.keyX);
        private static final Comparator<Emitter> Y = (a, b) -> Integer.compareUnsigned(a.keyY, b.keyY);
        private static final Comparator<Emitter> Z = (a, b) -> Integer.compareUnsigned(a.keyZ, b.keyZ);
        private final Emitter[] scratch;
        private final int[] counts = new int[256];

        RadixWorkspace(int size) {
            scratch = new Emitter[size];
        }

        static Comparator<Emitter> comparator(int axis) {
            return axis == 0 ? X : axis == 1 ? Y : Z;
        }

        void sort(Emitter[] emitters, int start, int end, int axis) {
            Emitter[] from = emitters, to = scratch;
            for (int shift = 0; shift < 32; shift += 8) {
                Arrays.fill(counts, 0);
                for (int i = start; i < end; i++) {
                    counts[(from[i].sortKey(axis) >>> shift) & 255]++;
                }
                int cursor = start;
                for (int bucket = 0; bucket < counts.length; bucket++) {
                    int length = counts[bucket];
                    counts[bucket] = cursor;
                    cursor += length;
                }
                for (int i = start; i < end; i++) {
                    Emitter emitter = from[i];
                    int bucket = (emitter.sortKey(axis) >>> shift) & 255;
                    to[counts[bucket]++] = emitter;
                }
                Emitter[] swap = from;
                from = to;
                to = swap;
            }
            // Four passes finish in the original array; neither children nor siblings need copies.
        }
    }

    private static final class Node {
        float minX, minY, minZ, maxX, maxY, maxZ, power, softening;
        int parent, left = -1, right = -1;

        static Node create(Emitter[] emitters, int start, int end, int parent) {
            Node node = new Node();
            node.minX = node.minY = node.minZ = Float.POSITIVE_INFINITY;
            node.maxX = node.maxY = node.maxZ = Float.NEGATIVE_INFINITY;
            for (int i = start; i < end; i++) {
                Emitter e = emitters[i];
                node.minX = Math.min(node.minX, e.minX);
                node.minY = Math.min(node.minY, e.minY);
                node.minZ = Math.min(node.minZ, e.minZ);
                node.maxX = Math.max(node.maxX, e.maxX);
                node.maxY = Math.max(node.maxY, e.maxY);
                node.maxZ = Math.max(node.maxZ, e.maxZ);
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
                           float area, float emission, float power, int materialIndex, int sourceIndex,
                           int keyX, int keyY, int keyZ,
                           float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        private Emitter(float ax, float ay, float az, float e1x, float e1y, float e1z,
                        float e2x, float e2y, float e2z, float nx, float ny, float nz,
                        float area, float emission, float power, int materialIndex, int sourceIndex) {
            this(ax, ay, az, e1x, e1y, e1z, e2x, e2y, e2z, nx, ny, nz,
                area, emission, power, materialIndex, sourceIndex,
                floatSortKey(ax + 0.5F * (e1x + e2x)),
                floatSortKey(ay + 0.5F * (e1y + e2y)),
                floatSortKey(az + 0.5F * (e1z + e2z)),
                Math.min(ax, Math.min(ax + e1x, ax + e2x)),
                Math.min(ay, Math.min(ay + e1y, ay + e2y)),
                Math.min(az, Math.min(az + e1z, az + e2z)),
                Math.max(ax, Math.max(ax + e1x, ax + e2x)),
                Math.max(ay, Math.max(ay + e1y, ay + e2y)),
                Math.max(az, Math.max(az + e1z, az + e2z)));
        }

        int sortKey(int axis) {
            return axis == 0 ? keyX : axis == 1 ? keyY : keyZ;
        }
    }
}
