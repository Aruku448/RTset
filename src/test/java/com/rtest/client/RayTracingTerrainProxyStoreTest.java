package com.rtest.client;

/** Pure-Java contracts for the asynchronous far-terrain proxy store. */
public final class RayTracingTerrainProxyStoreTest {
    private RayTracingTerrainProxyStoreTest() { }

    public static void main(String[] args) {
        identityIsolation();
        staleVersionsAreRejected();
        lruEviction();
        snapshotsAndLifecycle();
        System.out.println("Terrain proxy store contracts passed");
    }

    private static void identityIsolation() {
        RayTracingTerrainProxyStore.WorldIdentity overworld = identity("overworld", "session-a");
        RayTracingTerrainProxyStore.WorldIdentity nether = identity("nether", "session-a");
        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(2, 3, 0, 4);
        try (RayTracingTerrainProxyStore store = new RayTracingTerrainProxyStore(4)) {
            if (!store.load(overworld, key, 1, 11, mesh(1))) throw new AssertionError("overworld load failed");
            if (!store.load(nether, key, 1, 11, mesh(2))) throw new AssertionError("nether load failed");
            if (store.snapshot(overworld).nodes().size() != 1
                    || store.snapshot(nether).nodes().size() != 1) {
                throw new AssertionError("world/dimension identity was not isolated");
            }
            if (store.clear(overworld) != 1 || store.snapshot(overworld).nodes().size() != 0
                    || store.snapshot(nether).nodes().size() != 1) {
                throw new AssertionError("clearing one identity affected another");
            }
        }
    }

    private static void staleVersionsAreRejected() {
        RayTracingTerrainProxyStore.WorldIdentity identity = identity("world", "session-a");
        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(1, 0, 0, 0);
        try (RayTracingTerrainProxyStore store = new RayTracingTerrainProxyStore(4)) {
            RayTracingTerrainProxyStore.LoadRequest current = store.request(identity, key, 4, 400);
            if (!store.load(current, mesh(4))) throw new AssertionError("initial load failed");
            if (store.load(store.request(identity, key, 3, 400), mesh(3))) {
                throw new AssertionError("older generation was accepted");
            }
            if (store.load(store.request(identity, key, 4, 401), mesh(41))) {
                throw new AssertionError("changed fingerprint at same generation was accepted");
            }
            if (store.retire(store.request(identity, key, 3, 400))) {
                throw new AssertionError("stale retirement removed current node");
            }
            if (store.get(identity, key).orElseThrow().generation() != 4) {
                throw new AssertionError("stale load changed current generation");
            }
            if (!store.retire(current) || store.size() != 0) {
                throw new AssertionError("exact retirement failed");
            }
        }
    }

    private static void lruEviction() {
        RayTracingTerrainProxyStore.WorldIdentity identity = identity("world", "session-a");
        RayTracingTerrainLod.NodeKey a = new RayTracingTerrainLod.NodeKey(0, 0, 0, 0);
        RayTracingTerrainLod.NodeKey b = new RayTracingTerrainLod.NodeKey(0, 1, 0, 0);
        RayTracingTerrainLod.NodeKey c = new RayTracingTerrainLod.NodeKey(0, 2, 0, 0);
        try (RayTracingTerrainProxyStore store = new RayTracingTerrainProxyStore(2)) {
            store.load(identity, a, 1, 1, mesh(1));
            store.load(identity, b, 1, 2, mesh(2));
            if (store.get(identity, a).isEmpty()) throw new AssertionError("LRU touch failed");
            store.load(identity, c, 1, 3, mesh(3));
            if (store.get(identity, a).isEmpty() || store.get(identity, c).isEmpty()
                    || store.get(identity, b).isPresent()) {
                throw new AssertionError("least-recently-used node was not evicted");
            }
        }
    }

    private static void snapshotsAndLifecycle() {
        RayTracingTerrainProxyStore.WorldIdentity identity = identity("world", "session-a");
        RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(0, 0, 0, 0);
        float[] vertices = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] materials = opaqueMaterial();
        RayTracingTerrainProxyStore.OpaqueNodeMesh mesh =
                new RayTracingTerrainProxyStore.OpaqueNodeMesh(vertices, materials);
        vertices[0] = 99;
        materials[3] = 0;
        if (mesh.vertices()[0] != 0 || mesh.materialData()[3] != 1) {
            throw new AssertionError("mesh input was not immutable");
        }
        float[] exposed = mesh.vertices();
        exposed[0] = 77;
        if (mesh.vertices()[0] != 0) throw new AssertionError("mesh output was not immutable");

        try (RayTracingTerrainProxyStore store = new RayTracingTerrainProxyStore(2)) {
            store.load(identity, key, 1, 1, mesh);
            RayTracingTerrainProxyStore.Snapshot snapshot = store.snapshot();
            if (snapshot.nodes().size() != 1 || snapshot.revision() == 0) {
                throw new AssertionError("atomic snapshot missing");
            }
            if (store.clear(identity) != 1 || store.size() != 0 || snapshot.nodes().size() != 1) {
                throw new AssertionError("snapshot was not independent of clear");
            }
            store.load(identity, key, 2, 2, mesh);
            store.close();
            if (!store.isClosed() || store.size() != 0 || !store.snapshot().nodes().isEmpty()) {
                throw new AssertionError("close did not clear store");
            }
            if (store.load(identity, key, 3, 3, mesh)) {
                throw new AssertionError("closed store accepted a load");
            }
            store.close();
        }
    }

    private static RayTracingTerrainProxyStore.WorldIdentity identity(String dimension, String session) {
        return new RayTracingTerrainProxyStore.WorldIdentity("world", dimension, session);
    }

    private static RayTracingTerrainProxyStore.OpaqueNodeMesh mesh(float offset) {
        float[] vertices = {offset, 0, 0, offset + 1, 0, 0, offset, 1, 0};
        return new RayTracingTerrainProxyStore.OpaqueNodeMesh(vertices, opaqueMaterial());
    }

    private static float[] opaqueMaterial() {
        float[] material = new float[RayTracingTerrainLod.MATERIAL_FLOATS_PER_TRIANGLE];
        material[3] = 1;
        material[27] = 1;
        return material;
    }
}
