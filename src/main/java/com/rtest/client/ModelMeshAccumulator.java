package com.rtest.client;

import java.util.ArrayList;

/** Owned append-only mesh storage; amortized linear copying, sealed once per frame. */
final class ModelMeshAccumulator {
    private final FloatArrayBuilder vertices = new FloatArrayBuilder();
    private final FloatArrayBuilder materials = new FloatArrayBuilder();
    private final ArrayList<Tag> tags = new ArrayList<>();
    private boolean sealed;
    private record Tag(int start, int length, int slot) { }

    void append(PlayerModelGeometryAdapter.Mesh mesh, int textureSlot) {
        if (sealed) throw new IllegalStateException("Mesh already sealed");
        // Reserve every allocation before publishing new rows: failed growth preserves the prefix.
        vertices.reserve(mesh.vertices().length);
        materials.reserve(mesh.materialData().length);
        tags.ensureCapacity(Math.addExact(tags.size(), 1));
        Tag tag = new Tag(materials.size(), mesh.materialData().length, textureSlot);
        vertices.addAll(mesh.vertices());
        materials.addAll(mesh.materialData());
        tags.add(tag);
    }

    PlayerModelGeometryAdapter.Mesh finish() {
        if (sealed) throw new IllegalStateException("Mesh already sealed");
        float[] positions = vertices.toArray();
        float[] data = materials.toArray();
        for (Tag tag : tags) {
            if (tag.slot < 0) continue;
            int end = tag.start + tag.length;
            for (int offset = tag.start; offset < end; offset += 28) {
                if (data[offset + 15] > 1.5F && data[offset + 15] < 2.5F)
                    data[offset + 24] = tag.slot;
            }
        }
        var result = new PlayerModelGeometryAdapter.Mesh(positions, data);
        sealed = true;
        return result;
    }

    int parts() { return tags.size(); }
    long copiedBytes() { return (vertices.copiedFloats() + materials.copiedFloats()) * Float.BYTES; }
}
