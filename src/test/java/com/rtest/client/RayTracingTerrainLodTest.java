package com.rtest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(1, 0, 0, 0);
        RayTracingTerrainLod.Node node = RayTracingTerrainLod.buildNode(key, input);
        if (!node.ready() || node.mesh().triangleCount() != 1) throw new AssertionError("node build failed");

        testNegativeCoordinatesAndLevelTwoCoverage(material);
        testDirectParentBuildAndMutualExclusion(material);
        testMissingAndStaleFallback(material);
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
