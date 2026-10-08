package com.rtest.client;

import java.util.Arrays;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.resources.Identifier;

/** Exercises the real accumulation and frame publication paths without a Vulkan device. */
public final class ModelCaptureBatchTest {
    public static void main(String[] args) {
        ModelMeshAccumulator accumulator = new ModelMeshAccumulator();
        int count = 1000;
        float[] expectedVertices = new float[count * 18];
        float[] expectedMaterials = new float[count * 56];
        for (int i = 0; i < count; i++) {
            var part = part(i);
            int slot = i % 4 == 0 ? -1 : i % 63;
            System.arraycopy(part.vertices(), 0, expectedVertices, i * 18, 18);
            System.arraycopy(part.materialData(), 0, expectedMaterials, i * 56, 56);
            if (slot >= 0) expectedMaterials[i * 56 + 24] = slot;
            accumulator.append(part, slot);
            // Upstream buffers may be changed/recycled after publication.
            Arrays.fill(part.vertices(), -99);
            Arrays.fill(part.materialData(), -99);
        }
        var mesh = accumulator.finish();
        require(Arrays.equals(expectedVertices, mesh.vertices()), "vertex order and ownership");
        require(Arrays.equals(expectedMaterials, mesh.materialData()), "all material fields and selective texture tagging");
        long payloadBytes = (expectedVertices.length + expectedMaterials.length) * 4L;
        long copiedBytes = accumulator.copiedBytes();
        require(copiedBytes < payloadBytes * 5, "copy traffic must stay linear in total mesh size");
        long previousCopies = payloadBytes * (count * (count + 1L) / 2 - 1) / count;
        require(previousCopies > copiedBytes * 50, "representative fragmented model must reduce copies");
        reject(() -> accumulator.append(part(8), 1));
        reject(accumulator::finish);
        require(Arrays.equals(expectedMaterials, mesh.materialData()), "sealed snapshot remains unchanged");
        publication();
        System.out.printf("Model batching passed: 1000 quads, payload=%d B, copies=%d B, prior append copies=%d B%n",
            payloadBytes, copiedBytes, previousCopies);
    }

    private static void publication() {
        LivingEntityGeometryAdapter.clear();
        var first = new EntityRenderState();
        first.x = 3; first.y = 4; first.z = 5;
        var later = new EntityRenderState();
        later.x = 8; later.y = 9; later.z = 10;
        Identifier textureA = Identifier.fromNamespaceAndPath("rtest", "batch_a");
        Identifier textureB = Identifier.fromNamespaceAndPath("rtest", "batch_b");
        int slotA = LivingEntityGeometryAdapter.textureSlot(textureA);
        int slotB = LivingEntityGeometryAdapter.textureSlot(textureB);
        var a = part(1);
        LivingEntityGeometryAdapter.publishModel(1, first, textureA, a);
        LivingEntityGeometryAdapter.publishModel(2, later, textureB, part(3));
        LivingEntityGeometryAdapter.publishModel(1, later, textureB, part(2));
        Arrays.fill(a.vertices(), -99);
        LivingEntityGeometryAdapter.endWorldDraw();
        var snapshots = LivingEntityGeometryAdapter.drain();
        require(snapshots.size() == 2, "interleaved owners and endWorldDraw preserve publication");
        var snapshot = snapshots.get(1);
        require(snapshot.x() == 3 && snapshot.y() == 4 && snapshot.z() == 5, "retain first owner transform");
        require(textureB.equals(snapshot.texture()), "retain last texture metadata");
        require(snapshot.mesh().triangleCount() == 4, "retain every fragment");
        require(snapshot.mesh().vertices()[0] == 1 && snapshot.mesh().vertices()[18] == 2, "fragment order and owned storage");
        require(snapshot.mesh().materialData()[24] == slotA && snapshot.mesh().materialData()[80] == slotB,
            "each fragment keeps its texture slot");
        require(LivingEntityGeometryAdapter.drain().isEmpty(), "drain consumes frame");
        float[] retained = snapshot.mesh().materialData().clone();
        LivingEntityGeometryAdapter.publishModel(1, later, null, part(4));
        var next = LivingEntityGeometryAdapter.drain().get(1);
        require(next.texture() == null && next.mesh().materialData()[24] == 17, "missing texture preserves material slot");
        require(Arrays.equals(retained, snapshot.mesh().materialData()), "later frame cannot mutate earlier snapshot");
        LivingEntityGeometryAdapter.publishModel(1, first, textureA, part(5));
        LivingEntityGeometryAdapter.beginWorldDraw();
        require(LivingEntityGeometryAdapter.drain().isEmpty(), "beginWorldDraw discards stale pending frame");
        LivingEntityGeometryAdapter.publishModel(1, first, textureA, part(6));
        LivingEntityGeometryAdapter.clear();
        require(LivingEntityGeometryAdapter.drain().isEmpty() && LivingEntityGeometryAdapter.drainTextureSlots().isEmpty(),
            "world clear releases pending geometry and texture slots");
    }

    private static PlayerModelGeometryAdapter.Mesh part(int id) {
        float[] vertices = new float[18];
        float[] materials = new float[56];
        for (int i = 0; i < vertices.length; i++) vertices[i] = id + i * 0.01F;
        for (int i = 0; i < materials.length; i++) materials[i] = id + i * 0.01F;
        materials[15] = 2; materials[24] = 17;
        materials[43] = 1; materials[52] = 23;
        return new PlayerModelGeometryAdapter.Mesh(vertices, materials);
    }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("sealed accumulator accepted mutation");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
