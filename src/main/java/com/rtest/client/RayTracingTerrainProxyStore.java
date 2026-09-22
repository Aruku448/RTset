package com.rtest.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Minecraft-independent cache for far-terrain RT proxies.
 *
 * <p>This is the RTest-native storage boundary for a later loaded-window extension. It stores
 * only immutable opaque coarse meshes and never retains a {@code ClientLevel}, a block state, a
 * renderer object, or any other Minecraft-owned value. Workers may build a mesh off-thread and
 * publish it through {@link #load(LoadRequest, OpaqueNodeMesh)}; publication and snapshotting are
 * atomic with respect to one another.</p>
 *
 * <p>The cache is deliberately a store, not a scene selector or a renderer integration. The
 * current {@link RayTracingProbe} path does not depend on it.</p>
 */
public final class RayTracingTerrainProxyStore implements AutoCloseable {
    /** Opaque caller-owned identity. Values should be stable for the lifetime of a session. */
    public record WorldIdentity(String worldId, String dimensionId, String sessionId) {
        public WorldIdentity {
            requireText(worldId, "worldId");
            requireText(dimensionId, "dimensionId");
            requireText(sessionId, "sessionId");
        }
    }

    /** The hierarchical coordinate used by the existing renderer-independent terrain LOD model. */
    public record NodeId(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey) {
        public NodeId {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(nodeKey, "nodeKey");
        }
    }

    /** A version captured before asynchronous mesh generation starts. */
    public record LoadRequest(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey,
                              long generation, long sourceFingerprint) {
        public LoadRequest {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(nodeKey, "nodeKey");
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        }

        public NodeId id() {
            return new NodeId(identity, nodeKey);
        }
    }

    /**
     * Immutable opaque mesh payload. Arrays are copied on construction and on access, so a worker
     * or a render-thread snapshot can never observe the other side mutating its input.
     */
    public static final class OpaqueNodeMesh {
        private static final float EPSILON = 1.0e-6F;
        private final float[] vertices;
        private final float[] materialData;
        private final long contentFingerprint;

        public OpaqueNodeMesh(float[] vertices, float[] materialData) {
            Objects.requireNonNull(vertices, "vertices");
            Objects.requireNonNull(materialData, "materialData");
            if (vertices.length == 0 || vertices.length % 9 != 0
                    || materialData.length != vertices.length / 9
                    * RayTracingTerrainLod.MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("mesh arrays do not match the terrain ABI");
            }
            this.vertices = vertices.clone();
            this.materialData = materialData.clone();
            validateOpaque(this.vertices, this.materialData);
            this.contentFingerprint = fingerprint(this.vertices, this.materialData);
        }

        /** Converts an already reduced terrain mesh without retaining the mutable source object. */
        public static OpaqueNodeMesh from(RayTracingTerrainLod.Mesh mesh) {
            Objects.requireNonNull(mesh, "mesh");
            return new OpaqueNodeMesh(mesh.vertices(), mesh.materialData());
        }

        public float[] vertices() {
            return vertices.clone();
        }

        public float[] materialData() {
            return materialData.clone();
        }

        public int triangleCount() {
            return vertices.length / 9;
        }

        /** Fingerprint of the immutable mesh bytes, useful for upload/resource reuse. */
        public long contentFingerprint() {
            return contentFingerprint;
        }

        public long byteSize() {
            return ((long) vertices.length + materialData.length) * Float.BYTES;
        }

        private static void validateOpaque(float[] vertices, float[] materials) {
            for (float value : vertices) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("mesh has non-finite vertices");
            }
            int materialStride = RayTracingTerrainLod.MATERIAL_FLOATS_PER_TRIANGLE;
            for (int offset = 0; offset < materials.length; offset += materialStride) {
                for (int i = 0; i < materialStride; i++) {
                    if (!Float.isFinite(materials[offset + i])) {
                        throw new IllegalArgumentException("mesh has non-finite material data");
                    }
                }
                // Match the RT terrain ABI: opaque/cutout, non-emissive, no absorption, and air IOR.
                if (materials[offset + 3] < 0.999F
                        || materials[offset + 22] > EPSILON
                        || Math.abs(materials[offset + 24]) > EPSILON
                        || Math.abs(materials[offset + 25]) > EPSILON
                        || Math.abs(materials[offset + 26]) > EPSILON
                        || Math.abs(materials[offset + 27] - 1.0F) > 1.0e-4F) {
                    throw new IllegalArgumentException("proxy mesh is not opaque");
                }
            }
        }

        private static long fingerprint(float[] vertices, float[] materials) {
            long hash = 0xcbf29ce484222325L;
            for (float value : vertices) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
            for (float value : materials) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
            return hash;
        }
    }

    /** Immutable entry returned by a point lookup or a store snapshot. */
    public record NodeSnapshot(NodeId id, long generation, long sourceFingerprint,
                               OpaqueNodeMesh mesh) {
        public NodeSnapshot {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(mesh, "mesh");
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        }
    }

    /** A coherent, immutable view that can be handed to a worker or renderer. */
    public record Snapshot(long revision, List<NodeSnapshot> nodes) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
            nodes = List.copyOf(nodes);
        }
    }

    private record Version(long generation, long fingerprint) { }
    private record Entry(NodeSnapshot snapshot, Version version) { }

    private final Object lock = new Object();
    private final int capacity;
    // accessOrder is intentionally false: all recency changes happen explicitly while holding lock.
    private final LinkedHashMap<NodeId, Entry> entries = new LinkedHashMap<>();
    private long revision;
    private boolean closed;

    public RayTracingTerrainProxyStore(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Builds a worker-safe version token without capturing any Minecraft object. */
    public LoadRequest request(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey,
                               long generation, long sourceFingerprint) {
        return new LoadRequest(identity, nodeKey, generation, sourceFingerprint);
    }

    /**
     * Publishes a mesh if its version is current. Older generations and a changed fingerprint at
     * the same generation are rejected, which makes late asynchronous workers harmless.
     */
    public boolean load(LoadRequest request, OpaqueNodeMesh mesh) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(mesh, "mesh");
        synchronized (lock) {
            if (closed) return false;
            Entry old = entries.get(request.id());
            Version incoming = new Version(request.generation(), request.sourceFingerprint());
            if (old != null && isStale(incoming, old.version())) return false;
            NodeSnapshot snapshot = new NodeSnapshot(request.id(), request.generation(),
                    request.sourceFingerprint(), mesh);
            entries.remove(request.id());
            entries.put(request.id(), new Entry(snapshot, incoming));
            revision++;
            evictIfNeeded();
            return true;
        }
    }

    public boolean load(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey,
                        long generation, long sourceFingerprint, OpaqueNodeMesh mesh) {
        return load(request(identity, nodeKey, generation, sourceFingerprint), mesh);
    }

    /** Returns one immutable entry and marks it recently used. */
    public Optional<NodeSnapshot> get(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey) {
        NodeId id = new NodeId(identity, nodeKey);
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry == null) return Optional.empty();
            touch(id, entry);
            return Optional.of(entry.snapshot());
        }
    }

    /** Retires only the exact version that was requested; a newer mesh is never removed by a late worker. */
    public boolean retire(LoadRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (lock) {
            if (closed) return false;
            Entry entry = entries.get(request.id());
            if (entry == null || !entry.version().equals(new Version(request.generation(), request.sourceFingerprint()))) {
                return false;
            }
            entries.remove(request.id());
            revision++;
            return true;
        }
    }

    public boolean retire(WorldIdentity identity, RayTracingTerrainLod.NodeKey nodeKey,
                          long generation, long sourceFingerprint) {
        return retire(request(identity, nodeKey, generation, sourceFingerprint));
    }

    /** Removes every node for one world/dimension/session identity. */
    public int clear(WorldIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (lock) {
            int removed = 0;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getKey().identity().equals(identity)) {
                    iterator.remove();
                    removed++;
                }
            }
            if (removed != 0) revision++;
            return removed;
        }
    }

    /** Atomically clears all identities. */
    public int clear() {
        synchronized (lock) {
            int removed = entries.size();
            if (removed != 0) {
                entries.clear();
                revision++;
            }
            return removed;
        }
    }

    /** Returns a coherent snapshot; no mutable map or array escapes this method. */
    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(revision, snapshots(entries.values()));
        }
    }

    public Snapshot snapshot(WorldIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (lock) {
            ArrayList<NodeSnapshot> result = new ArrayList<>();
            for (Entry entry : entries.values()) {
                if (entry.snapshot().id().identity().equals(identity)) result.add(entry.snapshot());
            }
            return new Snapshot(revision, result);
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** Closing is terminal and clears all immutable payloads from the store. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (!entries.isEmpty()) {
                entries.clear();
                revision++;
            }
        }
    }

    private static boolean isStale(Version incoming, Version existing) {
        return incoming.generation() < existing.generation()
                || (incoming.generation() == existing.generation()
                && incoming.fingerprint() != existing.fingerprint());
    }

    private void touch(NodeId id, Entry entry) {
        entries.remove(id);
        entries.put(id, entry);
    }

    private void evictIfNeeded() {
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    private static List<NodeSnapshot> snapshots(Iterable<Entry> values) {
        ArrayList<NodeSnapshot> result = new ArrayList<>();
        for (Entry entry : values) result.add(entry.snapshot());
        return result;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
