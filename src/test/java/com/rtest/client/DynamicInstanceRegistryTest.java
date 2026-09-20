package com.rtest.client;

/** Contract tests for dynamic slot and transform history semantics. */
public final class DynamicInstanceRegistryTest {
    public static void main(String[] args) {
        var registry = new DynamicInstanceRegistry(1);
        var key = new DynamicInstanceRegistry.GeometryKey(10L, 20L);
        var first = DynamicInstanceRegistry.Transform.translation(1.0F, 2.0F, 3.0F);

        registry.beginFrame();
        registry.upsert(42L, DynamicInstanceRegistry.Family.ENTITY, key, first,
            DynamicInstanceRegistry.FLAG_OPAQUE, false);
        var initial = registry.finish();
        require(initial.activeCount() == 1 && initial.changedGeometry().contains(42L), "new instance not reported");
        require(initial.instances().getFirst().historyReset(), "new instance must reset history");

        registry.beginFrame();
        var second = DynamicInstanceRegistry.Transform.translation(4.0F, 2.0F, 3.0F);
        registry.upsert(42L, DynamicInstanceRegistry.Family.ENTITY, key, second,
            DynamicInstanceRegistry.FLAG_OPAQUE, false);
        var moved = registry.finish().instances().getFirst();
        require(moved.slot() == initial.instances().getFirst().slot(), "slot changed during movement");
        require(!moved.historyReset(), "ordinary movement reset history");
        require(x(moved.previousTransform()) == 1.0F && x(moved.currentTransform()) == 4.0F,
            "current/previous transform history is wrong");

        registry.beginFrame();
        var masked = registry.finish();
        require(masked.activeCount() == 0 && masked.instances().getFirst().masked(), "missing instance was not masked");

        registry.beginFrame();
        registry.finish();
        registry.beginFrame();
        registry.finish();
        registry.beginFrame();
        registry.upsert(99L, DynamicInstanceRegistry.Family.BLOCK_ENTITY, key,
            DynamicInstanceRegistry.Transform.translation(8.0F, 0.0F, 0.0F), DynamicInstanceRegistry.FLAG_CUTOUT, false);
        var reused = registry.finish().instances().getFirst();
        require(reused.slot() == initial.instances().getFirst().slot(), "retired slot was not reused");
        require(reused.generation() > initial.instances().getFirst().generation(), "slot generation did not advance");

        registry.beginFrame();
        registry.upsert(99L, DynamicInstanceRegistry.Family.BLOCK_ENTITY,
            new DynamicInstanceRegistry.GeometryKey(11L, 20L), reused.currentTransform(),
            DynamicInstanceRegistry.FLAG_CUTOUT, false);
        var changed = registry.finish();
        require(changed.changedGeometry().contains(99L), "topology change was not reported");
        require(changed.instances().getFirst().historyReset(), "topology change did not reset history");

        var bounded = new DynamicInstanceRegistry(1, 1);
        bounded.beginFrame();
        require(bounded.upsert(1L, DynamicInstanceRegistry.Family.ENTITY, key, first,
            DynamicInstanceRegistry.FLAG_OPAQUE, false), "first bounded slot was rejected");
        require(!bounded.upsert(2L, DynamicInstanceRegistry.Family.ENTITY, key, first,
            DynamicInstanceRegistry.FLAG_OPAQUE, false), "overflow slot was silently accepted");
        require(bounded.finish().activeCount() == 1, "overflow object was marked active");

        var previousDynamicFrame = initial;
        var previousEntityFrame = new DynamicEntityGeometry.Frame(previousDynamicFrame,
            java.util.List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), 0);
        var missedCapture = new DynamicEntityGeometry.Frame(
            new DynamicInstanceRegistry.Frame(2L, java.util.List.of(), java.util.Set.of(), 0),
            java.util.List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), 1);
        require(DynamicEntityGeometry.isTransientCaptureMiss(missedCapture, previousEntityFrame),
            "an empty frame with visible capture failures was not recognized as transient");
        var emptyWorld = new DynamicEntityGeometry.Frame(
            new DynamicInstanceRegistry.Frame(3L, java.util.List.of(), java.util.Set.of(), 0),
            java.util.List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            java.util.Map.of(), 0);
        require(!DynamicEntityGeometry.isTransientCaptureMiss(emptyWorld, previousEntityFrame),
            "an actually empty world frame was incorrectly retained");
    }

    private static float x(DynamicInstanceRegistry.Transform transform) {
        return transform.m03();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
