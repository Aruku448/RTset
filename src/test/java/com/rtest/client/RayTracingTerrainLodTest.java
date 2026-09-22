package com.rtest.client;

import java.util.List;
import java.util.Map;
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
}
