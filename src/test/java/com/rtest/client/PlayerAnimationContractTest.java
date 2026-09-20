package com.rtest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Runs real PlayerModel.setupAnim and the same draw tee installed in ModelFeatureRenderer. */
public final class PlayerAnimationContractTest {
    public static void main(String[] args) {
        PlayerModel model = new PlayerModel(LayerDefinition.create(
            PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64).bakeRoot(), false);
        AvatarRenderState state = new AvatarRenderState();
        state.scale = state.ageScale = 1.0F;
        model.setupAnim(state);
        var standing = capture(model, 0.0F);
        var emissive = capture(model, 0.0F, 1.5F);
        assertEmissiveLayerInFront(standing, emissive);
        for (int base = 0; base < emissive.materialData().length; base += 28) {
            if (emissive.materialData()[base + 22] != 1.5F || emissive.materialData()[base + 26] != 1.0F) {
                throw new AssertionError("Vanilla emissive pipeline was not encoded into RT material");
            }
        }
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 1; i < standing.vertices().length; i += 3) {
            minY = Math.min(minY, standing.vertices()[i]);
            maxY = Math.max(maxY, standing.vertices()[i]);
        }
        if (maxY - minY < 1.7F || maxY - minY > 2.1F) {
            throw new AssertionError("Vanilla player height must remain in blocks, got " + (maxY - minY));
        }
        if (Math.abs(minY) > 0.05F) throw new AssertionError("Feet drifted from entity origin: " + minY);
        state.walkAnimationPos = 1.5F;
        state.walkAnimationSpeed = 0.8F;
        model.setupAnim(state);
        var walking = capture(model, 0.0F);
        if (Arrays.equals(standing.vertices(), walking.vertices())) {
            throw new AssertionError("Vanilla walking pose did not reach captured vertices");
        }
        if (standing.triangleCount() != walking.triangleCount()) throw new AssertionError("Animation changed topology");
        if (Arrays.equals(walking.vertices(), capture(model, 90.0F).vertices())) {
            throw new AssertionError("Vanilla body rotation was lost");
        }
        state.isCrouching = true;
        model.setupAnim(state);
        if (Arrays.equals(walking.vertices(), capture(model, 0.0F).vertices())) {
            throw new AssertionError("Vanilla crouching pose was lost");
        }
        // Overlay visibility can change primitive count. It must not be forced into 128 triangles.
        state.showHat = state.showJacket = state.showLeftPants = state.showRightPants = true;
        state.showLeftSleeve = state.showRightSleeve = true;
        model.setupAnim(state);
        if (capture(model, 0.0F).triangleCount() <= 128) throw new AssertionError("Overlay fixture missing");
        System.out.println("Player animation geometry contract passed: height=" + (maxY - minY));
    }

    private static PlayerModelGeometryAdapter.Mesh capture(PlayerModel model, float bodyYaw) {
        return capture(model, bodyYaw, 0.0F);
    }

    private static void assertEmissiveLayerInFront(PlayerModelGeometryAdapter.Mesh body,
                                                  PlayerModelGeometryAdapter.Mesh eyes) {
        for (int triangle = 0; triangle < body.triangleCount(); triangle++) {
            int m = triangle * 28;
            float nx = body.materialData()[m + 4];
            float ny = body.materialData()[m + 5];
            float nz = body.materialData()[m + 6];
            for (int corner = 0; corner < 3; corner++) {
                int v = triangle * 9 + corner * 3;
                float separation = (eyes.vertices()[v] - body.vertices()[v]) * nx
                    + (eyes.vertices()[v + 1] - body.vertices()[v + 1]) * ny
                    + (eyes.vertices()[v + 2] - body.vertices()[v + 2]) * nz;
                if (!(separation > 0.0001F && separation < 0.003F)) {
                    throw new AssertionError("Eye layer competes with body at identical ray distance: "
                        + separation);
                }
            }
        }
    }

    private static PlayerModelGeometryAdapter.Mesh capture(PlayerModel model, float bodyYaw,
                                                            float pipelineEmission) {
        // An example pose delivered by the renderer; production never reconstructs this pose.
        PoseStack pose = new PoseStack();
        pose.translate(32.0F, 8.0F, -16.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - bodyYaw));
        pose.scale(-0.9375F, -0.9375F, 0.9375F);
        pose.translate(0.0F, -1.501F, 0.0F);
        RecordingConsumer original = new RecordingConsumer();
        model.renderToBuffer(pose, original, 0x00f000f0, 0, -1);
        RecordingConsumer forwarded = new RecordingConsumer();
        var mesh = PlayerModelGeometryAdapter.captureDraw(model, pose, forwarded, 0x00f000f0, 0, -1,
            -32, -8, 16, null, null, pipelineEmission);
        if (!original.data.equals(forwarded.data)) throw new AssertionError("Capture altered vanilla vertex stream");
        if (mesh.vertices().length != original.data.size() / 8 * 9 / 2) {
            throw new AssertionError("Quad triangulation count mismatch");
        }
        for (int base = 0; base < mesh.materialData().length; base += 28) {
            if (mesh.materialData()[base + 14] != 1 || mesh.materialData()[base + 15] != 2
                || mesh.materialData()[base + 27] != 1) {
                throw new AssertionError("Player skin material layout mismatch");
            }
        }
        return mesh;
    }

    private static final class RecordingConsumer implements VertexConsumer {
        final List<Float> data = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { data.add(x); data.add(y); data.add(z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { data.add(u); data.add(v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { data.add(x); data.add(y); data.add(z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
