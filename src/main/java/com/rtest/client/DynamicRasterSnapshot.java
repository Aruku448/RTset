package com.rtest.client;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * Frame-scoped entity draw data with no acceleration-structure ownership. Captured mesh
 * arrays are borrowed immutable data; retain this snapshot until its GPU upload retires.
 * Neither a current nor a previous display-camera matrix is stored in this snapshot.
 */
final class DynamicRasterSnapshot {
    static final int POSITION_FLOATS_PER_TRIANGLE = 9;
    static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    record Draw(DynamicInstanceRegistry.Instance instance, DynamicEntityGeometry.Family family,
                FloatBuffer positions, FloatBuffer materials, Identifier texture) {
        Draw {
            if (positions.remaining() % POSITION_FLOATS_PER_TRIANGLE != 0
                || materials.remaining() != positions.remaining() / POSITION_FLOATS_PER_TRIANGLE
                    * MATERIAL_FLOATS_PER_TRIANGLE) throw new IllegalArgumentException("Raster mesh stride mismatch");
            positions = positions.asReadOnlyBuffer();
            materials = materials.asReadOnlyBuffer();
        }
        @Override public FloatBuffer positions() { return positions.asReadOnlyBuffer(); }
        @Override public FloatBuffer materials() { return materials.asReadOnlyBuffer(); }
        int triangleCount() { return positions.remaining() / POSITION_FLOATS_PER_TRIANGLE; }
        boolean primaryVisible() {
            return instance.active()
                && (instance.flags() & DynamicInstanceRegistry.FLAG_FIRST_PERSON_BODY) == 0;
        }
        // Materials retain the existing texture selector and UV ABI. texture == null
        // means atlas/procedural material, not an absent mesh.
    }
    record Frame(long generation, List<Draw> draws, List<Long> missingGeometry, int admissionFailures) {
        Frame { draws = List.copyOf(draws); missingGeometry = List.copyOf(missingGeometry); }
        boolean complete() { return missingGeometry.isEmpty() && admissionFailures == 0; }
    }
    private DynamicRasterSnapshot() { }
    static Frame from(DynamicEntityGeometry.Frame frame) {
        if (frame == null) return new Frame(0, List.of(), List.of(), 0);
        List<Draw> draws = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (DynamicEntityGeometry.Instance captured : frame.instances()) {
            var instance = captured.snapshot();
            if (!instance.active()) continue;
            long id = instance.identity();
            float[] positions = null, materials = null;
            Identifier texture = null;
            switch (captured.family()) {
                case PLAYER_BODY, FIRST_PERSON_BODY -> {
                    var mesh = frame.playerMeshes().get(id);
                    if (mesh != null) { positions = mesh.vertices(); materials = mesh.materialData(); }
                    texture = frame.playerSkinTextures().get(id);
                }
                case LIVING_BODY, PARTICLE -> {
                    var mesh = frame.livingMeshes().get(id);
                    if (mesh != null) { positions = mesh.vertices(); materials = mesh.materialData(); }
                    texture = frame.livingTextures().get(id);
                }
                case BLOCK_ENTITY_MODEL -> {
                    var mesh = frame.blockEntityMeshes().get(id);
                    if (mesh != null) { positions = mesh.vertices(); materials = mesh.materialData(); }
                    texture = frame.blockEntityTextures().get(id);
                }
                case ITEM_PLACEHOLDER, FIRST_PERSON_ITEM -> {
                    var mesh = frame.itemMeshes().get(id);
                    if (mesh != null) { positions = mesh.vertices(); materials = mesh.materialData(); }
                }
            }
            if (positions == null || positions.length == 0) { missing.add(id); continue; }
            draws.add(new Draw(instance, captured.family(), FloatBuffer.wrap(positions),
                FloatBuffer.wrap(materials), texture));
        }
        return new Frame(frame.dynamicFrame().frame(), draws, missing, frame.admissionFailures());
    }
}
