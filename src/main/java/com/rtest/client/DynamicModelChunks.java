package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Splits a complete avatar across bounded material/BLAS slots without dropping triangles. */
final class DynamicModelChunks {
    private static final long CHUNK_TAG = 0x2000000000000000L;
    private static final long TAG_MASK = 0xff00000000000000L;
    record Part(long identity, PlayerModelGeometryAdapter.Mesh mesh) { }

    static boolean isChunk(long id) { return (id & TAG_MASK) == CHUNK_TAG; }
    static long owner(long id) { return isChunk(id) ? id & 0xffffffffL : id; }
    static long identity(int owner, int index) {
        if (index == 0) return owner;
        if (index < 0 || index > 0xffffff) throw new IllegalArgumentException("Model chunk index");
        return CHUNK_TAG | ((long)index << 32) | Integer.toUnsignedLong(owner);
    }
    static List<Part> split(int owner, PlayerModelGeometryAdapter.Mesh mesh) {
        int capacity = DynamicEntityGeometry.DYNAMIC_MODEL_TRIANGLE_CAPACITY;
        if (mesh.triangleCount() <= capacity) return List.of(new Part(owner, mesh));
        List<Part> result = new ArrayList<>();
        for (int start = 0, index = 0; start < mesh.triangleCount(); start += capacity, index++) {
            int end = Math.min(start + capacity, mesh.triangleCount());
            result.add(new Part(identity(owner, index), new PlayerModelGeometryAdapter.Mesh(
                Arrays.copyOfRange(mesh.vertices(), start * 9, end * 9),
                Arrays.copyOfRange(mesh.materialData(), start * 28, end * 28))));
        }
        return List.copyOf(result);
    }
    static List<Part> publish(DynamicInstanceRegistry registry, int owner,
        PlayerModelGeometryAdapter.Mesh mesh, DynamicInstanceRegistry.Family family,
        long topology, DynamicInstanceRegistry.Transform transform, int flags) {
        List<Part> parts = split(owner, mesh);
        if (!registry.canUpsert(parts.stream().map(Part::identity).toList())) return null;
        for (Part part : parts) {
            // The ordinal and triangle count distinguish topology and survive animation.
            long revision = topology ^ ((long)part.mesh().triangleCount() << 32);
            if (!registry.upsert(part.identity(), family,
                new DynamicInstanceRegistry.GeometryKey(revision, topology), transform, flags, false))
                throw new IllegalStateException("Model chunk preflight lost a slot");
        }
        return parts;
    }
}
