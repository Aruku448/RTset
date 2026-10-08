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
        var beamTexture = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/beacon_beam.png");
        for (boolean translucent : new boolean[] {false, true}) {
            var type = net.minecraft.client.renderer.rendertype.RenderTypes.beaconBeam(beamTexture, translucent);
            if (!LivingEntityGeometryAdapter.isEmissive(type))
                throw new AssertionError("Beacon beam lost native emissive pipeline semantics");
            var beam = BlockEntityModelGeometryAdapter.beaconMaterial(emissive, type);
            for (int i = 0; i < beam.materialData().length; i += 28) {
                if (beam.materialData()[i + 14] != 2 || beam.materialData()[i + 22] != 1.5F
                    || beam.materialData()[i + 26] != 1)
                    throw new AssertionError("Beacon beam repeat/emission material ABI mismatch");
            }
        }
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
        state.isCrouching = false;
        state.walkAnimationPos = state.walkAnimationSpeed = 0;
        model.setupAnim(state);
        var complete = capture(model, 0.0F);
        PoseStack viewPose = new PoseStack();
        viewPose.translate(32.0F,8.0F,-16.0F);
        viewPose.mulPose(Axis.YP.rotationDegrees(180.0F));
        viewPose.scale(-.9375F,-.9375F,.9375F);
        viewPose.translate(0,-1.501F,0);
        boolean headVisible=model.head.visible, leftVisible=model.leftArm.visible, rightVisible=model.rightArm.visible;
        var bodyView=PlayerModelGeometryAdapter.captureBodyView(model,viewPose,0x00f000f0,0,-1,
            -32,-8,16,null,null,null,0,complete);
        if(bodyView.triangleCount()==0 || bodyView.triangleCount()>=complete.triangleCount()) {
            throw new AssertionError("First-person body view must retain torso/legs and omit head/arms");
        }
        float viewMinY=Float.POSITIVE_INFINITY, viewMaxY=Float.NEGATIVE_INFINITY;
        for(int i=0;i<bodyView.vertices().length;i+=3) {
            viewMinY=Math.min(viewMinY,bodyView.vertices()[i+1]);
            viewMaxY=Math.max(viewMaxY,bodyView.vertices()[i+1]);
            if(Math.abs(bodyView.vertices()[i])>.35F) throw new AssertionError("First-person view includes an arm");
        }
        if(Math.abs(viewMinY)>.05F || viewMaxY<1.1F || viewMaxY>1.5F) {
            throw new AssertionError("First-person view must include feet and torso, not the head: "+viewMinY+".."+viewMaxY);
        }
        if(model.head.visible!=headVisible || model.leftArm.visible!=leftVisible || model.rightArm.visible!=rightVisible
            || !Arrays.equals(complete.vertices(),capture(model,0.0F).vertices())) {
            throw new AssertionError("Body view capture changed the full reflection/shadow/raster model");
        }
        // First-person submissions must remain deferred: replacement mods can change visibility
        // after the body node is queued, and capture must use that final draw.
        PlayerModelGeometryAdapter.beginWorldDraw(null);
        state.id = 32;
        state.x = 100; state.y = 64; state.z = -200;
        var storage = new FirstPersonPlayerCaptureStorage();
        storage.begin();
        storage.submitModel(model, state, viewPose,
            net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(
                net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png")),
            0x00f000f0, 0, -1, null, 0, null);
        if (!PlayerModelGeometryAdapter.drain().isEmpty()
            || !PlayerModelGeometryAdapter.drainFirstPersonViews().isEmpty()) {
            throw new AssertionError("First-person body captured before replacement mods finished submission");
        }
        var nodes = new ArrayList<net.minecraft.client.renderer.feature.submit.SubmitNode>();
        for (var collection : storage.getSubmitsPerOrder().values()) {
            for (var phase : collection.allPhases()) {
                if (!phase.isEmpty()) {
                    phase.sortInto((node, ordered) -> nodes.add(node));
                }
            }
        }
        if (nodes.size() != 1
            || !(nodes.getFirst() instanceof net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submitted)
            || submitted.model() != model || submitted.state() != state) {
            throw new AssertionError("First-person queue replaced the installed renderer's model/state");
        }
        model.setupAnim(state);
        model.root().visible = false;
        try {
            if (capture(model, 0).triangleCount() != 0) {
                throw new AssertionError("Replacement mod's hidden vanilla body was force-rendered");
            }
        } finally {
            model.root().visible = true;
        }
        storage.begin();
        if (!storage.getSubmitsPerOrder().isEmpty()) {
            throw new AssertionError("First-person submission queue leaked into the next frame");
        }
        assertTextAndHandMap();
        System.out.println("Player animation geometry contract passed: height=" + (maxY - minY));
    }

    private static void assertTextAndHandMap() {
        var texture = net.minecraft.resources.Identifier.withDefaultNamespace("map/17");
        var type = net.minecraft.client.renderer.rendertype.RenderTypes.text(texture);
        if (!WorldTextGeometry.isText(type)) throw new AssertionError("Native map text RenderType was excluded");
        net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer map = (pose, buffer) -> {
            buffer.addVertex(pose, 0, 128, -.01F).setColor(-1).setUv(0, 1).setLight(0);
            buffer.addVertex(pose, 128, 128, -.01F).setColor(-1).setUv(1, 1).setLight(0);
            buffer.addVertex(pose, 128, 0, -.01F).setColor(-1).setUv(1, 0).setLight(0);
            buffer.addVertex(pose, 0, 0, -.01F).setColor(-1).setUv(0, 0).setLight(0);
        };
        var pose = new PoseStack();
        pose.scale(.01F, .01F, .01F);
        var consumer = new PlayerModelGeometryAdapter.Capture(null, 0, 0, 0);
        map.render(pose.last(), consumer);
        var quad = consumer.finish();
        if (quad.triangleCount() != 2) throw new AssertionError("Map quad triangulation changed");
        for (int i=0; i<quad.materialData().length; i+=28) {
            if (Math.abs(quad.materialData()[i+6]) != 1 || quad.materialData()[i+22] != 0)
                throw new AssertionError("Normal-free text/map lost geometric shading normal");
        }
        var grayscale = WorldTextGeometry.material(quad,
            net.minecraft.client.renderer.rendertype.RenderTypes.textGrayscale(texture));
        if (grayscale.materialData()[14] != 3 || WorldTextGeometry.material(quad, type).materialData()[14] != 1)
            throw new AssertionError("R8 glyph coverage was mixed with RGBA map alpha");
        ItemModelGeometryAdapter.beginFirstPerson(32, new net.minecraft.world.phys.Vec3(10,20,30));
        ItemModelGeometryAdapter.captureCustomSubmit(pose.last(), type, map);
        ItemModelGeometryAdapter.endFirstPerson();
        var hand = ItemModelGeometryAdapter.drainFirstPersonItems().get(32);
        if (hand == null || hand.mesh().triangleCount()!=2 || hand.cameraX()!=10)
            throw new AssertionError("Hand map custom submit did not enter camera-local RT capture");
        for (String shader : new String[] {RayTracingShaders.CLOSEST_HIT_SHADER,
            RayTracingShaders.ANY_HIT_SHADER, RayTracingShaders.SHADOW_ANY_HIT_SHADER}) {
            if (!shader.contains("if (uv2.z > 2.5) textureSample = vec4(vec3(1.0), textureSample.r);"))
                throw new AssertionError("Font transparency coverage disagrees across ray types");
        }
        var ids = new java.util.HashSet<Long>();
        for (int owner=0; owner<100; owner++) for (int chunk=0; chunk<20; chunk++) {
            long id = BlockEntityModelGeometryAdapter.textIdentity(0x8001000000000000L|owner,chunk);
            if ((id>>>48)!=0x8002L || !ids.add(id)) throw new AssertionError("Text chunk identity aliased another owner");
        }
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
