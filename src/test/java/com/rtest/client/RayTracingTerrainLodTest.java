package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
/** Dependency-free contracts for the renderer-independent terrain LOD MVP. */
public final class RayTracingTerrainLodTest {
    private RayTracingTerrainLodTest() { }

    public static void main(String[] args) throws Exception {
        float[] vertices = { 0, 0, 0, 1, 0, 0, 0, 1, 0 };
        float[] material = new float[28];
        material[3] = 1.0F;
        material[27] = 1.0F;
        RayTracingTerrainLod.SectionInput input =
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, material);
        vertices[0] = 99;
        if (input.vertices()[0] != 0) throw new AssertionError("input was not copied");
        float[] returnedMaterials = input.materialData();
        returnedMaterials[3] = 0.0F;
        if (input.materialData()[3] != 1.0F) throw new AssertionError("material accessor exposed its array");

        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(1, 0, 0, 0);
        testCoarseVoxelClosesAllDirections(material);
        RayTracingTerrainLod.Node node = RayTracingTerrainLod.buildNode(key, input);
        if (!node.ready() || node.mesh().triangleCount() != 12) throw new AssertionError("node build failed");

        testLargeWorldCoordinates(material);
        testSkewedProxyUvDoesNotDegenerate(material);
        testCoarseVoxelBoundaryAndWinding(material);
        testCoarseVoxelCullsInternalFaces(material);
        testMergedSurfaceVoxelCoverage(material);
        testCoarseProxyPreservesDenseSurfaceCoverage(material);
        testCoarseProxyPreservesVerticalSurfaceCoverage(material);
        testNegativeCoordinatesAndLevelTwoCoverage(material);
        testLevelTwoProxyResolution(material);
        testCoarseProxyUvRemainsInSourceTile(material);
        testDirectParentBuildAndMutualExclusion(material);
        testLargeNodeInput(material);
        testLargeHierarchySelection();
        testReadyOnlyCutMatchesNativeFallbackHierarchy(material);
        testMissingAndStaleFallback(material);
        testGpuNativeCandidateFilter();
        testCancelsOneStaleNodeWithoutDroppingOtherWork();
        if (RayTracingTerrainLod.select(node, 0, 0, 32, 24,
                RayTracingTerrainLod.GeometryChoice.NATIVE)
                != RayTracingTerrainLod.GeometryChoice.NATIVE) throw new AssertionError("near selection failed");
        if (RayTracingTerrainLod.select(node, 100, 100, 32, 24,
                RayTracingTerrainLod.GeometryChoice.NATIVE)
                != RayTracingTerrainLod.GeometryChoice.COARSE) throw new AssertionError("far selection failed");

        try (RayTracingTerrainLodScheduler<String> scheduler =
                 new RayTracingTerrainLodScheduler<>(1, 2, request -> "token-" + request.token().id())) {
            RayTracingTerrainLodScheduler.NodeKey schedulerKey =
                new RayTracingTerrainLodScheduler.NodeKey(7);
            scheduler.submit(scheduler.request(schedulerKey, 1, 1, 1, 2, 0, 10));
            scheduler.submit(scheduler.request(schedulerKey, 1, 1, 2, 3, 0, 10));
            List<RayTracingTerrainLodScheduler.Result<String>> results = List.of();
            Map<RayTracingTerrainLodScheduler.NodeKey, RayTracingTerrainLodScheduler.NodeVersion> versions =
                Map.of(schedulerKey, new RayTracingTerrainLodScheduler.NodeVersion(2, 3));
            for (int i = 0; i < 100 && results.isEmpty(); i++) {
                results = scheduler.poll(1, 1, 1, versions);
                if (results.isEmpty()) Thread.sleep(2);
            }
            if (results.isEmpty()) throw new AssertionError("scheduler result missing");
        }
        System.out.println("Terrain LOD contracts passed");
    }

    private static void testCoarseVoxelClosesAllDirections(float[] material) {
        // Three faces of a solid block all land in one coarse cell. No direction may
        // disappear just because another triangle won the cell's material vote.
        float[] vertices = new float[27];
        float[] materials = new float[84];
        putTriangle(vertices, materials, 0, 1, 1, 1, 1, 2, 1, 1, 1, 2, material);
        putTriangle(vertices, materials, 1, 1, 1, 1, 1, 1, 2, 2, 1, 1, material);
        putTriangle(vertices, materials, 2, 1, 1, 1, 2, 1, 1, 1, 2, 1, material);
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials));
        float[] mesh = proxy.mesh().vertices();
        for (int axis = 0; axis < 3; axis++) {
            if (projectedArea(mesh, axis) < 32.0) {
                throw new AssertionError("coarse voxel lost exterior faces on axis " + axis);
            }
        }
        if (proxy.mesh().triangleCount() != 12) {
            throw new AssertionError("one occupied coarse voxel must have six exterior quads");
        }
    }

    private static void testLargeWorldCoordinates(float[] material) {
        float[] vertices = {0, 8, 0, 1, 8, 0, 0, 8, 1};
        for (int origin : new int[] {0, 16_777_216, -16_777_216}) {
            RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
                new RayTracingTerrainLod.NodeKey(1, origin / 32, 0, 0),
                new RayTracingTerrainLod.SectionInput(origin, 0, 0, vertices, material));
            if (proxy.mesh().triangleCount() != 12) {
                throw new AssertionError("large world coordinate lost surface voxel: " + origin);
            }
        }
    }

    private static void testSkewedProxyUvDoesNotDegenerate(float[] material) {
        float[] vertices = {0, 8, 0, 1, 8, 0, 0, 8, 1};
        float[] skewed = material.clone();
        skewed[8] = 0.25F; skewed[9] = 0.5F;
        skewed[10] = 0.3125F; skewed[11] = 0.5F;
        skewed[12] = 0.3125F; skewed[13] = 0.5625F;
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, skewed));
        float[] materials = proxy.mesh().materialData();
        for (int offset = 0; offset < materials.length; offset += 28) {
            double au = materials[offset + 10] - materials[offset + 8];
            double av = materials[offset + 11] - materials[offset + 9];
            double bu = materials[offset + 12] - materials[offset + 8];
            double bv = materials[offset + 13] - materials[offset + 9];
            if (Math.abs(au * bv - av * bu) < 1.0e-6) {
                throw new AssertionError("coarse triangle UV mapping degenerated into a line");
            }
        }
    }

    private static void testCoarseVoxelBoundaryAndWinding(float[] material) {
        for (int origin : new int[] {0, -32}) {
            for (int boundary : new int[] {0, 32}) {
                // A source face at either node border must not get shifted out and lost.
                float[] vertices = new float[9];
                float[] materials = new float[28];
                putTriangle(vertices, materials, 0,
                    0, 0, boundary, 1, 0, boundary, 0, 1, boundary, material);
                RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
                    new RayTracingTerrainLod.NodeKey(1, origin / 32, origin / 32, origin / 32),
                    new RayTracingTerrainLod.SectionInput(origin, origin, origin, vertices, materials));
                if (proxy.mesh().triangleCount() != 12) {
                    throw new AssertionError("node border lost its closed voxel fallback");
                }
                float[] mesh = proxy.mesh().vertices();
                double cx = origin + 2.0, cy = origin + 2.0;
                double cz = origin + (boundary == 0 ? 2.0 : 30.0);
                for (int offset = 0; offset < mesh.length; offset += 9) {
                    for (int i = 0; i < 9; i++) {
                        if (mesh[offset + i] < origin || mesh[offset + i] > origin + 32) {
                            throw new AssertionError("coarse voxel escaped its node bounds");
                        }
                    }
                    double ax = mesh[offset + 3] - mesh[offset];
                    double ay = mesh[offset + 4] - mesh[offset + 1];
                    double az = mesh[offset + 5] - mesh[offset + 2];
                    double bx = mesh[offset + 6] - mesh[offset];
                    double by = mesh[offset + 7] - mesh[offset + 1];
                    double bz = mesh[offset + 8] - mesh[offset + 2];
                    double outward = (ay * bz - az * by) * (mesh[offset] - cx)
                        + (az * bx - ax * bz) * (mesh[offset + 1] - cy)
                        + (ax * by - ay * bx) * (mesh[offset + 2] - cz);
                    if (!(outward > 0.0)) throw new AssertionError("voxel face winding is not outward");
                }
            }
        }
    }

    private static void testCoarseVoxelCullsInternalFaces(float[] material) {
        float[] vertices = new float[18];
        float[] materials = new float[56];
        putTriangle(vertices, materials, 0, 1, 1, 1, 2, 1, 1, 1, 2, 1, material);
        putTriangle(vertices, materials, 1, 5, 1, 1, 6, 1, 1, 5, 2, 1, material);
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials));
        if (proxy.mesh().triangleCount() != 20) {
            throw new AssertionError("adjacent voxels must emit ten exterior quads");
        }
        float[] mesh = proxy.mesh().vertices();
        for (int offset = 0; offset < mesh.length; offset += 9) {
            if (mesh[offset] == 4 && mesh[offset + 3] == 4 && mesh[offset + 6] == 4) {
                throw new AssertionError("internal voxel face was not culled");
            }
        }
    }

    private static void testMergedSurfaceVoxelCoverage(float[] material) {
        // A merged quad spans sixteen cells; centroid-only assignment loses most of it.
        float[] vertices = new float[18];
        float[] materials = new float[56];
        putTriangle(vertices, materials, 0, 0, 8, 0, 16, 8, 0, 16, 8, 16, material);
        putTriangle(vertices, materials, 1, 0, 8, 0, 16, 8, 16, 0, 8, 16, material);
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials));
        if (proxy.mesh().triangleCount() != 96 || projectedArea(proxy.mesh().vertices(), 1) != 512.0) {
            throw new AssertionError("merged surface did not produce a closed 4x4 voxel slab");
        }
        // Rasterization must not fill the empty half of a diagonal triangle's AABB.
        RayTracingTerrainLod.Node diagonal = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0,
                Arrays.copyOf(vertices, 9), Arrays.copyOf(materials, 28)));
        if (projectedArea(diagonal.mesh().vertices(), 1) >= 512.0) {
            throw new AssertionError("triangle voxelization filled empty AABB corners");
        }
        // Changing section iteration order must not change material votes or face order.
        RayTracingTerrainLod.SectionInput first = new RayTracingTerrainLod.SectionInput(0, 0, 0,
            Arrays.copyOfRange(vertices, 0, 9), Arrays.copyOfRange(materials, 0, 28));
        RayTracingTerrainLod.SectionInput second = new RayTracingTerrainLod.SectionInput(0, 0, 0,
            Arrays.copyOfRange(vertices, 9, 18), Arrays.copyOfRange(materials, 28, 56));
        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(1, 0, 0, 0);
        if (RayTracingTerrainLod.buildNode(key, first, second).mesh().fingerprint()
                != RayTracingTerrainLod.buildNode(key, second, first).mesh().fingerprint()) {
            throw new AssertionError("voxel publication depends on source iteration order");
        }
    }

    private static void testGpuNativeCandidateFilter() {
        RayTracingTerrainLod.NodeKey leaf = new RayTracingTerrainLod.NodeKey(0, 3, 2, -4);
        RayTracingTerrainLod.NodeKey coarse = leaf.parent();
        Set<RayTracingTerrainLod.NodeKey> coarseNodes = Set.of(coarse);
        if (!RayTracingTerrainLod.keepGpuNativeLeaf(leaf, coarseNodes, 32.0, 96.0)) {
            throw new AssertionError("GPU filter removed a near native leaf");
        }
        if (RayTracingTerrainLod.keepGpuNativeLeaf(leaf, coarseNodes, 160.0, 96.0)) {
            throw new AssertionError("GPU filter kept a far leaf covered by a coarse proxy");
        }
        if (!RayTracingTerrainLod.keepGpuNativeLeaf(leaf, Set.of(), 160.0, 96.0)) {
            throw new AssertionError("GPU filter removed a far leaf without fallback proxy");
        }
    }

    private static void testCancelsOneStaleNodeWithoutDroppingOtherWork() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondRuns = new AtomicInteger();
        RayTracingTerrainLodScheduler.NodeKey first = new RayTracingTerrainLodScheduler.NodeKey(101);
        RayTracingTerrainLodScheduler.NodeKey second = new RayTracingTerrainLodScheduler.NodeKey(102);
        try (RayTracingTerrainLodScheduler<Long> scheduler = new RayTracingTerrainLodScheduler<>(1, 2, request -> {
            if (request.nodeKey().equals(first)) {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("scheduler test release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            } else {
                secondRuns.incrementAndGet();
            }
            return request.nodeKey().nodeId();
        })) {
            scheduler.submit(scheduler.request(first, 1, 1, 1, 1, 0, 0));
            if (!firstStarted.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("first scheduler request did not start");
            }
            scheduler.submit(scheduler.request(second, 1, 1, 1, 1, 0, 0));
            scheduler.cancel(second);
            releaseFirst.countDown();
            List<RayTracingTerrainLodScheduler.Result<Long>> results = List.of();
            for (int attempt = 0; attempt < 100 && results.isEmpty(); attempt++) {
                results = scheduler.poll(2);
                if (results.isEmpty()) Thread.sleep(2);
            }
            if (results.size() != 1 || !results.getFirst().request().nodeKey().equals(first)
                    || secondRuns.get() != 0) {
                throw new AssertionError("node cancellation disrupted independent scheduler work: " + results);
            }
        } finally {
            releaseFirst.countDown();
        }
    }

    private static void testLevelTwoProxyResolution(float[] material) {
        int triangleCount = 16;
        float[] vertices = new float[triangleCount * 9];
        float[] materials = new float[triangleCount * 28];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            float centerX = (triangle + 0.5F) * 4.0F;
            int offset = triangle * 9;
            vertices[offset] = centerX - 0.1F;
            vertices[offset + 1] = 1.0F;
            vertices[offset + 2] = 1.0F;
            vertices[offset + 3] = centerX + 0.1F;
            vertices[offset + 4] = 1.0F;
            vertices[offset + 5] = 1.0F;
            vertices[offset + 6] = centerX;
            vertices[offset + 7] = 2.0F;
            vertices[offset + 8] = 1.0F;
            System.arraycopy(material, 0, materials, triangle * 28, 28);
        }
        RayTracingTerrainLod.SectionInput input =
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials);
        RayTracingTerrainLod.Node coarse = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(2, 0, 0, 0), input);
        // Sixteen adjacent occupied cells form a closed bar: four long sides plus
        // two end caps. Adjacent voxel faces must not remain in the output.
        int expectedTriangles = triangleCount * 8 + 4;
        if (coarse.mesh().triangleCount() != expectedTriangles) {
            throw new AssertionError("level-two proxy coverage/internal culling failed: "
                + coarse.mesh().triangleCount() + " != " + expectedTriangles);
        }
    }

    private static void testCoarseProxyUvRemainsInSourceTile(float[] material) {
        float[] vertices = {
            0.0F, 8.0F, 0.0F,
            1.0F, 8.0F, 0.0F,
            0.0F, 8.0F, 1.0F
        };
        float[] materials = material.clone();
        materials[8] = 0.25F;
        materials[9] = 0.50F;
        materials[10] = 0.3125F;
        materials[11] = 0.50F;
        materials[12] = 0.25F;
        materials[13] = 0.5625F;
        materials[15] = 1.0F;
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0),
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials));
        float[] proxyMaterials = proxy.mesh().materialData();
        for (int triangle = 0; triangle < proxy.mesh().triangleCount(); triangle++) {
            int base = triangle * RayTracingTerrainLod.MATERIAL_FLOATS_PER_TRIANGLE;
            float minTriangleU = Float.POSITIVE_INFINITY, maxTriangleU = Float.NEGATIVE_INFINITY;
            float minTriangleV = Float.POSITIVE_INFINITY, maxTriangleV = Float.NEGATIVE_INFINITY;
            for (int vertex = 0; vertex < 3; vertex++) {
                minTriangleU = Math.min(minTriangleU, proxyMaterials[base + 8 + vertex * 2]);
                maxTriangleU = Math.max(maxTriangleU, proxyMaterials[base + 8 + vertex * 2]);
                minTriangleV = Math.min(minTriangleV, proxyMaterials[base + 9 + vertex * 2]);
                maxTriangleV = Math.max(maxTriangleV, proxyMaterials[base + 9 + vertex * 2]);
            }
            if (maxTriangleU - minTriangleU < 0.01F || maxTriangleV - minTriangleV < 0.01F) {
                throw new AssertionError("coarse voxel UVs collapsed to a single texel");
            }
            for (int uv = 0; uv < 6; uv++) {
                float value = proxyMaterials[base + 8 + uv];
                float min = (uv & 1) == 0 ? 0.25F : 0.50F;
                float max = (uv & 1) == 0 ? 0.3125F : 0.5625F;
                if (value < min - 1.0e-5F || value > max + 1.0e-5F) {
                    throw new AssertionError("coarse proxy UV escaped source atlas tile: " + value);
                }
            }
        }
    }

    private static void testCoarseProxyPreservesDenseSurfaceCoverage(float[] material) {
        int cellsPerAxis = 32;
        float[] vertices = new float[cellsPerAxis * cellsPerAxis * 2 * 9];
        float[] materials = new float[cellsPerAxis * cellsPerAxis * 2 * 28];
        int triangle = 0;
        for (int z = 0; z < cellsPerAxis; z++) {
            for (int x = 0; x < cellsPerAxis; x++) {
                int x0 = x, x1 = x + 1, z0 = z, z1 = z + 1;
                putTriangle(vertices, materials, triangle++, x0, 8, z0, x1, 8, z0, x1, 8, z1, material);
                putTriangle(vertices, materials, triangle++, x0, 8, z0, x1, 8, z1, x0, 8, z1, material);
            }
        }
        RayTracingTerrainLod.SectionInput flatTerrain =
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials);
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0), flatTerrain);
        double inputArea = projectedArea(vertices, 1);
        double proxyArea = projectedArea(proxy.mesh().vertices(), 1);
        if (proxyArea < inputArea * 0.9) {
            throw new AssertionError("coarse proxy kept only " + proxyArea + " of " + inputArea
                + " blocks of a continuous flat surface");
        }
    }

    private static void testCoarseProxyPreservesVerticalSurfaceCoverage(float[] material) {
        int cellsPerAxis = 32;
        float[] vertices = new float[cellsPerAxis * cellsPerAxis * 2 * 9];
        float[] materials = new float[cellsPerAxis * cellsPerAxis * 2 * 28];
        int triangle = 0;
        for (int z = 0; z < cellsPerAxis; z++) {
            for (int y = 0; y < cellsPerAxis; y++) {
                int y0 = y, y1 = y + 1, z0 = z, z1 = z + 1;
                putTriangle(vertices, materials, triangle++, 8, y0, z0, 8, y1, z0, 8, y1, z1, material);
                putTriangle(vertices, materials, triangle++, 8, y0, z0, 8, y1, z1, 8, y0, z1, material);
            }
        }
        RayTracingTerrainLod.SectionInput flatWall =
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials);
        RayTracingTerrainLod.Node proxy = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(1, 0, 0, 0), flatWall);
        double inputArea = projectedArea(vertices, 0);
        double proxyArea = projectedArea(proxy.mesh().vertices(), 0);
        if (proxyArea < inputArea * 0.9) {
            throw new AssertionError("coarse proxy kept only " + proxyArea + " of " + inputArea
                + " blocks of a continuous vertical wall");
        }
    }

    private static void putTriangle(float[] vertices, float[] materials, int triangle,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2, float[] material) {
        int vertexOffset = triangle * 9;
        vertices[vertexOffset] = x0;
        vertices[vertexOffset + 1] = y0;
        vertices[vertexOffset + 2] = z0;
        vertices[vertexOffset + 3] = x1;
        vertices[vertexOffset + 4] = y1;
        vertices[vertexOffset + 5] = z1;
        vertices[vertexOffset + 6] = x2;
        vertices[vertexOffset + 7] = y2;
        vertices[vertexOffset + 8] = z2;
        System.arraycopy(material, 0, materials, triangle * 28, 28);
    }

    private static double projectedArea(float[] vertices, int omittedAxis) {
        int uAxis = omittedAxis == 0 ? 1 : 0;
        int vAxis = omittedAxis == 2 ? 1 : 2;
        double area = 0.0;
        for (int offset = 0; offset < vertices.length; offset += 9) {
            double au = vertices[offset + 3 + uAxis] - vertices[offset + uAxis];
            double av = vertices[offset + 3 + vAxis] - vertices[offset + vAxis];
            double bu = vertices[offset + 6 + uAxis] - vertices[offset + uAxis];
            double bv = vertices[offset + 6 + vAxis] - vertices[offset + vAxis];
            area += Math.abs(au * bv - av * bu) * 0.5;
        }
        return area;
    }

    private static void testLargeNodeInput(float[] material) {
        int triangleCount = 100_000;
        float[] vertices = new float[triangleCount * 9];
        float[] materials = new float[triangleCount * 28];
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int vertexOffset = triangle * 9;
            vertices[vertexOffset + 3] = 1.0F;
            vertices[vertexOffset + 7] = 1.0F;
            System.arraycopy(material, 0, materials, triangle * 28, 28);
        }
        RayTracingTerrainLod.SectionInput dense =
            new RayTracingTerrainLod.SectionInput(0, 0, 0, vertices, materials);
        RayTracingTerrainLod.Node reduced = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(0, 0, 0, 0), dense);
        if (reduced.mesh().triangleCount() != 12) {
            throw new AssertionError("dense node reduction retained too many triangles: "
                + reduced.mesh().triangleCount());
        }
        if (!Arrays.equals(dense.vertices(), vertices)) {
            throw new AssertionError("dense snapshot changed while building its coarse node");
        }
    }

    private static void testLargeHierarchySelection() {
        Set<RayTracingTerrainLod.NodeKey> keys = new LinkedHashSet<>();
        int leafCount = 0;
        for (int y = -5; y < 5; y++) {
            for (int x = -14; x < 14; x++) {
                for (int z = -14; z < 14; z++) {
                    RayTracingTerrainLod.NodeKey leaf = new RayTracingTerrainLod.NodeKey(0, x, y, z);
                    keys.add(leaf);
                    keys.add(leaf.parent());
                    keys.add(leaf.parent().parent());
                    leafCount++;
                }
            }
        }
        List<RayTracingTerrainLod.Node> nodes = keys.stream()
            .map(key -> new RayTracingTerrainLod.Node(key, null))
            .toList();
        RayTracingTerrainLod.Hierarchy hierarchy = RayTracingTerrainLod.fromNodes(nodes);
        long started = System.nanoTime();
        RayTracingTerrainLod.Selection selection = hierarchy.select(
            new RayTracingTerrainLod.View(0, 80, 0, 1080, Math.toRadians(70)),
            new RayTracingTerrainLod.Hysteresis(128, 112, Double.MAX_VALUE, Double.MAX_VALUE), Set.of());
        long elapsed = System.nanoTime() - started;
        if (selection.nativeFallbacks().size() != leafCount) {
            throw new AssertionError("large hierarchy lost native fallback sections: "
                + selection.nativeFallbacks().size() + " != " + leafCount);
        }
        if (elapsed > 500_000_000L) {
            throw new AssertionError("large hierarchy selection took " + elapsed / 1_000_000L
                + " ms for " + nodes.size() + " nodes; expected under 500 ms");
        }
    }

    private static void testReadyOnlyCutMatchesNativeFallbackHierarchy(float[] material) {
        List<RayTracingTerrainLod.SectionInput> sections = new ArrayList<>();
        List<RayTracingTerrainLod.Node> full = new ArrayList<>();
        List<RayTracingTerrainLod.Node> readyCoarse = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                RayTracingTerrainLod.SectionInput input = section(x * 16, 0, z * 16, material);
                sections.add(input);
                full.add(new RayTracingTerrainLod.Node(
                    new RayTracingTerrainLod.NodeKey(0, x, 0, z), null));
            }
        }
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                int groupX = x;
                int groupZ = z;
                List<RayTracingTerrainLod.SectionInput> group = sections.stream()
                    .filter(input -> Math.floorDiv(input.originX(), 32) == groupX
                        && Math.floorDiv(input.originZ(), 32) == groupZ)
                    .toList();
                RayTracingTerrainLod.Node levelOne = RayTracingTerrainLod.buildNode(
                    new RayTracingTerrainLod.NodeKey(1, x, 0, z), group);
                full.add(levelOne);
                readyCoarse.add(levelOne);
            }
        }
        RayTracingTerrainLod.Node levelTwo = RayTracingTerrainLod.buildNode(
            new RayTracingTerrainLod.NodeKey(2, 0, 0, 0), sections);
        full.add(levelTwo);
        readyCoarse.add(levelTwo);
        RayTracingTerrainLod.Hysteresis hysteresis =
            new RayTracingTerrainLod.Hysteresis(100, 90, 10.0, 5.0);
        RayTracingTerrainLod.Hierarchy fullHierarchy = RayTracingTerrainLod.fromNodes(full);
        RayTracingTerrainLod.Hierarchy readyHierarchy = RayTracingTerrainLod.fromNodes(readyCoarse);
        for (RayTracingTerrainLod.View view : List.of(
                new RayTracingTerrainLod.View(10000, 10000, 10000, 1080, Math.toRadians(70)),
                new RayTracingTerrainLod.View(1, 1, 1, 1080, Math.toRadians(70)))) {
            Set<RayTracingTerrainLod.NodeKey> fullCut = fullHierarchy.select(view, hysteresis).nodes();
            Set<RayTracingTerrainLod.NodeKey> readyCut = readyHierarchy.select(view, hysteresis).nodes();
            if (!fullCut.equals(readyCut)) {
                throw new AssertionError("ready-only hierarchy changed the selected coarse cut: "
                    + fullCut + " != " + readyCut);
            }
        }
    }

    private static void testNegativeCoordinatesAndLevelTwoCoverage(float[] material) {
        RayTracingTerrainLod.NodeKey negative = new RayTracingTerrainLod.NodeKey(0, -1, -2, -3);
        if (!negative.parent().equals(new RayTracingTerrainLod.NodeKey(1, -1, -1, -2))) {
            throw new AssertionError("negative floor-div parent failed");
        }
        if (!negative.parent().parent().equals(new RayTracingTerrainLod.NodeKey(2, -1, -1, -1))) {
            throw new AssertionError("negative level-2 parent failed");
        }

        List<RayTracingTerrainLod.SectionInput> raw = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                raw.add(section(x * 16, 0, z * 16, material));
            }
        }
        RayTracingTerrainLod.Hierarchy hierarchy = RayTracingTerrainLod.buildHierarchy(raw);
        RayTracingTerrainLod.NodeKey level2 = new RayTracingTerrainLod.NodeKey(2, 0, 0, 0);
        RayTracingTerrainLod.Node parent = hierarchy.node(level2);
        if (parent == null || parent.bounds().maxX() != 64.0 || parent.bounds().maxZ() != 64.0) {
            throw new AssertionError("level-2 coverage failed");
        }
        if (hierarchy.nodes().size() != 16 + 4 + 1) {
            throw new AssertionError("unexpected hierarchy node count: " + hierarchy.nodes().size());
        }
    }

    private static void testDirectParentBuildAndMutualExclusion(float[] material) {
        List<RayTracingTerrainLod.SectionInput> raw = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) raw.add(section(x * 16, 0, z * 16, material));
        }
        RayTracingTerrainLod.NodeKey level2 = new RayTracingTerrainLod.NodeKey(2, 0, 0, 0);
        RayTracingTerrainLod.Node direct = RayTracingTerrainLod.buildNode(level2, raw);
        RayTracingTerrainLod.Node hierarchyParent = RayTracingTerrainLod.buildHierarchy(raw).node(level2);
        if (hierarchyParent.mesh().fingerprint() != direct.mesh().fingerprint()) {
            throw new AssertionError("parent was not built directly from raw sections");
        }
        RayTracingTerrainLod.Hierarchy hierarchy = RayTracingTerrainLod.buildHierarchy(raw);
        RayTracingTerrainLod.Hysteresis hysteresis = new RayTracingTerrainLod.Hysteresis(1000, 900, 10.0, 5.0);
        RayTracingTerrainLod.Selection far = hierarchy.select(
            new RayTracingTerrainLod.View(10000, 10000, 10000, 1080, Math.toRadians(70)),
            hysteresis, Set.of());
        if (!far.nodes().equals(Set.of(level2))) throw new AssertionError("far parent selection failed: " + far.nodes());
        RayTracingTerrainLod.Selection near = hierarchy.select(
            new RayTracingTerrainLod.View(1, 1, 1, 1080, Math.toRadians(70)),
            hysteresis, Set.of(level2));
        for (RayTracingTerrainLod.NodeKey selected : near.nodes()) {
            if (selected.level() != 0) throw new AssertionError("near selection retained a parent: " + near.nodes());
            if (selected.parent() != null && near.nodes().contains(selected.parent())) {
                throw new AssertionError("parent and child selected together");
            }
        }
    }

    private static void testMissingAndStaleFallback(float[] material) {
        RayTracingTerrainLod.NodeKey level0 = new RayTracingTerrainLod.NodeKey(0, 0, 0, 0);
        RayTracingTerrainLod.NodeKey level2 = new RayTracingTerrainLod.NodeKey(2, 0, 0, 0);
        RayTracingTerrainLod.Node nativeNode = RayTracingTerrainLod.buildNode(level0, section(0, 0, 0, material));
        RayTracingTerrainLod.Hysteresis hysteresis = new RayTracingTerrainLod.Hysteresis(100, 90, 10.0, 5.0);
        RayTracingTerrainLod.Selection missingParent = RayTracingTerrainLod.fromNodes(List.of(nativeNode)).select(
            new RayTracingTerrainLod.View(0, 0, 0, 1080, Math.toRadians(70)), hysteresis, Set.of());
        if (!missingParent.nodes().equals(Set.of(level0))) throw new AssertionError("native child fallback failed");

        RayTracingTerrainLod.Hierarchy staleParent = RayTracingTerrainLod.fromNodes(
            List.of(new RayTracingTerrainLod.Node(level2, null), nativeNode));
        RayTracingTerrainLod.Selection stale = staleParent.select(
            new RayTracingTerrainLod.View(0, 0, 0, 1080, Math.toRadians(70)), hysteresis, Set.of(level2));
        if (!stale.nodes().equals(Set.of(level0)) || !stale.nativeFallbacks().isEmpty()) {
            throw new AssertionError("stale parent did not fall back to child: " + stale);
        }

        RayTracingTerrainLod.Selection staleNative = RayTracingTerrainLod.fromNodes(
            List.of(new RayTracingTerrainLod.Node(level0, null))).select(
                new RayTracingTerrainLod.View(0, 0, 0, 1080, Math.toRadians(70)), hysteresis, Set.of());
        if (!staleNative.nodes().isEmpty() || !staleNative.usesNativeFallback(level0)) {
            throw new AssertionError("stale native fallback missing: " + staleNative);
        }
    }

    private static RayTracingTerrainLod.SectionInput section(int originX, int originY, int originZ,
                                                              float[] material) {
        float[] vertices = { 0, 0, 0, 8, 0, 0, 0, 8, 0 };
        return new RayTracingTerrainLod.SectionInput(originX, originY, originZ, vertices, material);
    }
}
