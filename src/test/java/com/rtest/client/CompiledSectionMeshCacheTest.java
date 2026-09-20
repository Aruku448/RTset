package com.rtest.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler.Results;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Exercises actual mesh conversion, including the material ABI consumed by the GPU. */
public final class CompiledSectionMeshCacheTest {
    private static final SectionPos SECTION = SectionPos.of(0, 0, 0);
    private static volatile int checksum;

    public static void main(String[] args) {
        try {
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                verify(layer, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, 1, false);
                verify(layer, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL, 0, false);
                verify(layer, DefaultVertexFormat.BLOCK, 0, false);
                verify(layer, DefaultVertexFormat.POSITION_TEX, 0, false);
                verify(layer, DefaultVertexFormat.POSITION_TEX, 0, true);
            }
            verifyMultipleLayers();
            if (args.length > 0 && args[0].equals("--benchmark")) benchmark();
            System.out.println("Compiled mesh conversion tests passed");
        } finally {
            CompiledSectionMeshCache.invalidateAll();
        }
    }

    private static void verify(ChunkSectionLayer layer, VertexFormat format, int normalX, boolean degenerate) {
        try (ByteBufferBuilder buffer = new ByteBufferBuilder(1024);
             MeshData mesh = mesh(buffer, format, normalX, degenerate, 2)) {
            Results results = new Results();
            results.renderedLayers.put(layer, mesh);
            CompiledSectionMeshCache.publish(SECTION, results);
            var converted = CompiledSectionMeshCache.get(BlockPos.ZERO);
            float[] expectedVertices = new float[36];
            float[] expectedMaterials = new float[112];
            int[] indices = {0, 1, 2, 0, 2, 3};
            float[] xs = {0, 1, 1, 0};
            float[] ys = {0, 0, 1, 1};
            boolean translucent = layer.translucent();
            for (int quad = 0; quad < 2; quad++) {
                for (int i = 0; i < 6; i++) {
                    int vertex = (quad * 6 + i) * 3;
                    expectedVertices[vertex] = degenerate ? 0 : xs[indices[i]];
                    expectedVertices[vertex + 1] = degenerate ? 0 : ys[indices[i]];
                    expectedVertices[vertex + 2] = quad;
                }
                for (int triangle = 0; triangle < 2; triangle++) {
                    float[] material = {
                        1, 1, 1, 1,
                        normalX, 0, normalX == 0 && !degenerate ? 1 : 0, 0,
                        0, 0, 1, triangle == 0 ? 0 : 1, triangle == 0 ? 1 : 0, 1,
                        translucent ? 0 : 1, 1, 0, 0, 0, 0,
                        0.88f, 0, 0, translucent ? 1.04f : 0.04f,
                        translucent ? 0.02f : 0, translucent ? 0.02f : 0,
                        translucent ? 0.02f : 0, translucent ? 1.5f : 1
                    };
                    System.arraycopy(material, 0, expectedMaterials, (quad * 2 + triangle) * 28, 28);
                }
            }
            assertEqual(expectedVertices, converted.vertices, "vertices");
            assertEqual(expectedMaterials, converted.materialData, "materials");
            // A later publication must not mutate an already captured snapshot.
            CompiledSectionMeshCache.publish(SECTION, new Results());
            if (CompiledSectionMeshCache.get(BlockPos.ZERO) != null) throw new AssertionError("Empty section retained");
            assertEqual(expectedMaterials, converted.materialData, "snapshot lifetime");
        }
    }

    private static void verifyMultipleLayers() {
        try (ByteBufferBuilder buffer = new ByteBufferBuilder(1024 * 1024);
             MeshData mesh = mesh(buffer, DefaultVertexFormat.BLOCK, 0, false, 100)) {
            Results results = new Results();
            results.renderedLayers.put(ChunkSectionLayer.SOLID, mesh);
            CompiledSectionMeshCache.publish(SECTION, results);
            var solid = CompiledSectionMeshCache.get(BlockPos.ZERO);
            results.renderedLayers.clear();
            results.renderedLayers.put(ChunkSectionLayer.TRANSLUCENT, mesh);
            CompiledSectionMeshCache.publish(SECTION, results);
            var translucent = CompiledSectionMeshCache.get(BlockPos.ZERO);
            results.renderedLayers.put(ChunkSectionLayer.SOLID, mesh);
            CompiledSectionMeshCache.publish(SECTION, results);
            var combined = CompiledSectionMeshCache.get(BlockPos.ZERO);
            float[] vertices = Arrays.copyOf(solid.vertices, solid.vertices.length * 2);
            System.arraycopy(translucent.vertices, 0, vertices, solid.vertices.length, translucent.vertices.length);
            float[] materials = Arrays.copyOf(solid.materialData, solid.materialData.length * 2);
            System.arraycopy(translucent.materialData, 0, materials, solid.materialData.length, translucent.materialData.length);
            assertEqual(vertices, combined.vertices, "combined vertices");
            assertEqual(materials, combined.materialData, "combined materials");
        }
    }

    private static MeshData mesh(ByteBufferBuilder buffer, VertexFormat format, int normalX,
                                 boolean degenerate, int quads) {
        BufferBuilder builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, format);
        for (int quad = 0; quad < quads; quad++) {
            for (int vertex = 0; vertex < 4; vertex++) {
                float x = vertex == 1 || vertex == 2 ? 1 : 0;
                float y = vertex >= 2 ? 1 : 0;
                builder.addVertex(degenerate ? 0 : x, degenerate ? 0 : y, quad)
                    .setColor(20 + vertex, 40, 60, 255).setUv(x, y)
                    .setLight(0x00f000f0).setNormal(normalX, 0, 0);
            }
        }
        return builder.buildOrThrow();
    }

    private static void assertEqual(float[] expected, float[] actual, String label) {
        if (expected.length != actual.length) throw new AssertionError(label + " length");
        for (int i = 0; i < expected.length; i++) {
            if (Math.abs(expected[i] - actual[i]) > 1.0e-6f || !Float.isFinite(actual[i])) {
                throw new AssertionError(label + "[" + i + "]: " + expected[i] + " != " + actual[i]);
            }
        }
    }

    private static void benchmark() {
        try (ByteBufferBuilder buffer = new ByteBufferBuilder(1024 * 1024);
             MeshData mesh = mesh(buffer, DefaultVertexFormat.BLOCK, 1, false, 4096)) {
            Results results = new Results();
            results.renderedLayers.put(ChunkSectionLayer.SOLID, mesh);
            for (int i = 0; i < 300; i++) CompiledSectionMeshCache.publish(SECTION, results);
            var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            double[] times = new double[7];
            double[] allocations = new double[7];
            for (int round = 0; round < times.length; round++) {
                long allocated = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    CompiledSectionMeshCache.publish(SECTION, results);
                    checksum = CompiledSectionMeshCache.get(BlockPos.ZERO).materialData.length;
                }
                times[round] = (System.nanoTime() - start) / 100_000_000.0;
                allocations[round] = (bean.getThreadAllocatedBytes(thread) - allocated) / 100.0;
            }
            Arrays.sort(times);
            Arrays.sort(allocations);
            System.out.printf("4096 quads: median %.3f ms/publication, %.0f bytes/publication%n", times[3], allocations[3]);
        }
    }
}
