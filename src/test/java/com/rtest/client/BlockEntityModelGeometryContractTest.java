package com.rtest.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Contracts for vanilla block-entity model capture: final poses, generic topology policy, block-entity
 * identity, dynamic texture descriptors and the per-quad LabPBR material encoding.
 */
public final class BlockEntityModelGeometryContractTest {
    public static void main(String[] args) throws Exception {
        assertCaptureSeamsRegistered();
        assertWhitelist();
        assertChestGeometry();
        assertMultiLayerCaptureSeam();
        assertIdentityNamespace();
        assertSpriteCapture();
        assertLayerPolicy();
        assertPbrMaterials();
        System.out.println("Block-entity model geometry contracts passed");
    }

    private static void assertCaptureSeamsRegistered() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/rtest.mixins.json"));
        String redirect = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/PlayerModelCaptureMixin.java"));
        String dispatcher = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/BlockEntityRenderDispatcherMixin.java"));
        if (!mixins.contains("BlockEntityRenderDispatcherMixin")
            || !mixins.contains("SubmitModelOwnerMixin")
            || mixins.contains("ChestRendererCaptureMixin")
            || !redirect.contains("BlockEntityModelGeometryAdapter.captureOrForward")
            || !redirect.contains("Model;renderToBuffer")
            || !dispatcher.contains("beginBlockEntity")
            || !dispatcher.contains("endBlockEntity")) {
            throw new AssertionError("Block-entity owner/capture mixins or renderToBuffer seam are not registered");
        }
    }

    /** Every supported renderer channel must resolve to its own BLAS topology. */
    private static void assertWhitelist() throws Exception {
        assertTopology("net.minecraft.client.model.object.chest.ChestModel",
            BlockEntityModelGeometryAdapter.CHEST_TOPOLOGY);
        assertTopology("net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer$ShulkerBoxModel",
            BlockEntityModelGeometryAdapter.SHULKER_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.bell.BellModel",
            BlockEntityModelGeometryAdapter.BELL_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.book.BookModel",
            BlockEntityModelGeometryAdapter.BOOK_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.statue.CopperGolemStatueModel",
            BlockEntityModelGeometryAdapter.STATUE_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.banner.BannerModel",
            BlockEntityModelGeometryAdapter.BANNER_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.banner.BannerFlagModel",
            BlockEntityModelGeometryAdapter.BANNER_FLAG_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.skull.SkullModel",
            BlockEntityModelGeometryAdapter.SKULL_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.skull.PiglinHeadModel",
            BlockEntityModelGeometryAdapter.PIGLIN_HEAD_TOPOLOGY);
        assertTopology("net.minecraft.client.model.object.skull.DragonHeadModel",
            BlockEntityModelGeometryAdapter.DRAGON_HEAD_TOPOLOGY);
        if (BlockEntityModelGeometryAdapter.topologyFor(PlayerModel.class)
            == BlockEntityModelGeometryAdapter.UNSUPPORTED_TOPOLOGY) {
            throw new AssertionError("Generic block-entity models must receive an RT topology");
        }
    }

    private static void assertTopology(String className, long expected) throws Exception {
        long actual = BlockEntityModelGeometryAdapter.topologyFor(Class.forName(className));
        if (actual != expected) {
            throw new AssertionError("Whitelist entry is stale or misspelled: " + className);
        }
    }

    private static void assertChestGeometry() {
        ChestModel single = new ChestModel(ChestModel.createSingleBodyLayer().bakeRoot());
        ChestModel left = new ChestModel(ChestModel.createDoubleBodyLeftLayer().bakeRoot());
        ChestModel right = new ChestModel(ChestModel.createDoubleBodyRightLayer().bakeRoot());

        single.setupAnim(0.0F);
        var closedSouth = capture(single, Direction.SOUTH, null, null);
        single.setupAnim(1.0F);
        var openSouth = capture(single, Direction.SOUTH, null, null);
        if (Arrays.equals(closedSouth.mesh().vertices(), openSouth.mesh().vertices())) {
            throw new AssertionError("Vanilla ChestModel.setupAnim lid pose was not captured");
        }
        single.setupAnim(1.0F);
        if (Arrays.equals(openSouth.mesh().vertices(),
            capture(single, Direction.EAST, null, null).mesh().vertices())) {
            throw new AssertionError("ChestRenderer facing transformation was not captured");
        }

        left.setupAnim(0.5F);
        right.setupAnim(0.5F);
        var leftMesh = capture(left, Direction.NORTH, null, null).mesh();
        var rightMesh = capture(right, Direction.NORTH, null, null).mesh();
        if (leftMesh.triangleCount() == 0 || rightMesh.triangleCount() == 0
            || Arrays.equals(leftMesh.vertices(), rightMesh.vertices())) {
            throw new AssertionError("Vanilla left/right double-chest geometry was not preserved");
        }

        var cutout = single.renderType(Sheets.CHEST_SHEET);
        if (!BlockEntityModelGeometryAdapter.isSupportedLayer(
            single, cutout, Sheets.CHEST_SHEET, false, false)) {
            throw new AssertionError("Vanilla opaque/cutout chest layer must be whitelisted");
        }
        if (!BlockEntityModelGeometryAdapter.isSupportedLayer(single,
                RenderTypes.entityTranslucent(Sheets.CHEST_SHEET), Sheets.CHEST_SHEET, false, false)
            || !BlockEntityModelGeometryAdapter.isSupportedLayer(single, cutout,
                Sheets.CHEST_SHEET, true, false)
            || !BlockEntityModelGeometryAdapter.isSupportedLayer(single, cutout,
                Sheets.CHEST_SHEET, false, true)
            || !BlockEntityModelGeometryAdapter.isSupportedLayer(new PlayerModel(
                net.minecraft.client.model.geom.builders.LayerDefinition.create(
                    PlayerModel.createMesh(
                        net.minecraft.client.model.geom.builders.CubeDeformation.NONE, false),
                    64, 64).bakeRoot(), false),
                cutout, Sheets.CHEST_SHEET, false, false)) {
            throw new AssertionError(
                "Every textured block-entity model layer must remain in the RT path");
        }
        if (BlockEntityModelGeometryAdapter.isSupportedLayer(single, cutout, null, false, false)) {
            throw new AssertionError("Untextured model layers must use the missing-texture RT binding");
        }
    }

    private static void assertMultiLayerCaptureSeam() throws Exception {
        String mixin = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/SubmitModelOwnerMixin.java"));
        if (!mixin.contains("method = \"submitMultiLayerBlockModel\"")
                || !mixin.contains("BlockEntityModelGeometryAdapter.captureBlockModel(pose, parts, tintLayers)")) {
            throw new AssertionError("NeoForge multi-layer animated block models must enter RT at submit time");
        }
    }

    private static void assertIdentityNamespace() {
        Identifier dimension = Identifier.withDefaultNamespace("overworld");
        Identifier type = Identifier.withDefaultNamespace("chest");
        long first = BlockEntityModelGeometryAdapter.stableIdentity(dimension, type, new BlockPos(1, 2, 3));
        long same = BlockEntityModelGeometryAdapter.stableIdentity(dimension, type, new BlockPos(1, 2, 3));
        long other = BlockEntityModelGeometryAdapter.stableIdentity(dimension, type, new BlockPos(2, 2, 3));
        if (first != same || first == other || (first >>> 48) != 0x8001L) {
            throw new AssertionError("Block-entity identity namespace/stability contract changed");
        }
        for (int entityId : new int[] {0, 1, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            if (first == (long)entityId) {
                throw new AssertionError("Block-entity identity collided with an entity id");
            }
        }
    }

    private static void assertSpriteCapture() throws Exception {
        ChestModel single = new ChestModel(ChestModel.createSingleBodyLayer().bakeRoot());
        TextureAtlasSprite sprite = createSprite();
        try {
            single.setupAnim(0.0F);
            CaptureResult remapped = capture(single, Direction.SOUTH, sprite, null);
            float[] material = remapped.mesh().materialData();
            List<Float> raster = remapped.forwarded();
            if (Math.abs(material[8] - sprite.getU(raster.get(3))) > 0.00001F
                || Math.abs(material[9] - sprite.getV(raster.get(4))) > 0.00001F
                || material[15] != 2.0F) {
                throw new AssertionError("Block-entity RT UVs did not reuse Capture sprite remap/selector");
            }
            var tagged = LivingEntityGeometryAdapter.retag(remapped.mesh(), Sheets.CHEST_SHEET);
            if (tagged.materialData()[24] <= 0.0F) {
                throw new AssertionError("Block-entity layer lost its dynamic texture descriptor slot");
            }
        } finally {
            sprite.close();
        }
    }

    /** LabPBR companions must land in the same seven-vec4 ABI the static scene uses. */
    /** All model layers stay in the RT path; the dynamic any-hit stage owns alpha handling. */
    private static void assertLayerPolicy() {
        net.minecraft.client.model.object.banner.BannerFlagModel flag =
            new net.minecraft.client.model.object.banner.BannerFlagModel(
                net.minecraft.client.model.object.banner.BannerFlagModel.createFlagLayer(false).bakeRoot());
        Identifier texture = Identifier.withDefaultNamespace("banner/base");
        var bannerPattern = RenderTypes.bannerPattern(texture);
        if (!bannerPattern.hasBlending()) {
            throw new AssertionError("Vanilla banner pattern layers are expected to blend");
        }
        if (!BlockEntityModelGeometryAdapter.isSupportedLayer(
            flag, bannerPattern, Sheets.BANNER_SHEET, false, false)) {
            throw new AssertionError("Vanilla banner base/pattern layers must be capturable as cutout");
        }
        if (!BlockEntityModelGeometryAdapter.isSupportedLayer(
            flag, flag.renderType(texture), texture, false, false)) {
            throw new AssertionError("The opaque banner flag layer must be capturable");
        }
        if (!BlockEntityModelGeometryAdapter.isSupportedLayer(
            flag, RenderTypes.entityTranslucent(texture), texture, false, false)) {
            throw new AssertionError("Translucent-tagged model layers must remain in RT");
        }
    }

    private static void assertPbrMaterials() throws Exception {
        ChestModel single = new ChestModel(ChestModel.createSingleBodyLayer().bakeRoot());
        TextureAtlasSprite sprite = createSprite();
        try {
            single.setupAnim(0.0F);
            float[] plain = capture(single, Direction.SOUTH, sprite, null).mesh().materialData();
            if (plain[19] != 0.0F || plain[21] != 0.0F) {
                throw new AssertionError("Without a sampler the model PBR slots must stay neutral");
            }
            RayTracingPbrSampler sampler = (ignoredSprite, u0, u1, u2) -> new RayTracingPbrMaterials.Sample(
                0.61F, 0.42F, 0.27F, 0.0F, 0.0F, 1.0F, false, true, 0.5F, true, 7);
            float[] pbr = capture(single, Direction.SOUTH, sprite, sampler).mesh().materialData();
            if (pbr[19] != 7.0F || Math.abs(pbr[20] - 0.27F) > 1.0e-6F
                || Math.abs(pbr[21] - 0.42F) > 1.0e-6F
                || Math.abs(pbr[22] - 0.5F) > 1.0e-6F
                || Math.abs(pbr[23] - 0.61F) > 1.0e-6F) {
                throw new AssertionError("Captured model quads did not bake the LabPBR companion sample");
            }
        } finally {
            sprite.close();
        }
    }

    private static CaptureResult capture(ChestModel model, Direction facing, TextureAtlasSprite sprite,
                                         RayTracingPbrSampler sampler) {
        PoseStack pose = new PoseStack();
        pose.translate(40.0F, 12.0F, -20.0F);
        pose.mulPose(ChestRenderer.modelTransformation(facing));
        RecordingConsumer forwarded = new RecordingConsumer();
        var mesh = PlayerModelGeometryAdapter.captureDraw(model, pose, forwarded,
            0x00f000f0, 0, -1, -40.0F, -12.0F, 20.0F, sprite, sampler);

        RecordingConsumer original = new RecordingConsumer();
        VertexConsumer vanillaBuffer = sprite == null ? original : sprite.wrap(original);
        model.renderToBuffer(pose, vanillaBuffer, 0x00f000f0, 0, -1);
        // Capture forwards model-local UV to the existing sprite wrapper. Simulate that wrapper
        // here when comparing against the final vanilla stream.
        List<Float> captureRaster = sprite == null ? forwarded.data : remapUv(forwarded.data, sprite);
        if (!original.data.equals(captureRaster)) {
            throw new AssertionError("Block-entity capture altered the vanilla vertex stream");
        }
        return new CaptureResult(mesh, forwarded.data);
    }

    private static List<Float> remapUv(List<Float> local, TextureAtlasSprite sprite) {
        List<Float> result = new ArrayList<>(local);
        for (int offset = 0; offset < result.size(); offset += 8) {
            result.set(offset + 3, sprite.getU(result.get(offset + 3)));
            result.set(offset + 4, sprite.getV(result.get(offset + 4)));
        }
        return result;
    }

    private static TextureAtlasSprite createSprite() throws Exception {
        Identifier texture = Identifier.fromNamespaceAndPath("rtest", "block_entity_contract");
        SpriteContents contents = new SpriteContents(texture, new FrameSize(16, 16),
            new NativeImage(16, 16, false));
        Constructor<TextureAtlasSprite> constructor = TextureAtlasSprite.class.getDeclaredConstructor(
            Identifier.class, SpriteContents.class, int.class, int.class,
            int.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Sheets.CHEST_SHEET, contents, 256, 256, 32, 48, 0);
    }

    private record CaptureResult(PlayerModelGeometryAdapter.Mesh mesh, List<Float> forwarded) {
    }

    private static final class RecordingConsumer implements VertexConsumer {
        private final List<Float> data = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            data.add(x); data.add(y); data.add(z); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { data.add(u); data.add(v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            data.add(x); data.add(y); data.add(z); return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
