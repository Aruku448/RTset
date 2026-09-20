package com.rtest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/**
 * Captures vanilla block-entity model draws after setupAnim for the dynamic RT scene.
 *
 * <p>The owner comes from {@code BlockEntityRenderDispatcher.submit}, so this adapter is not tied to
 * one renderer: any model submitted inside a block-entity scope is captured when its texture can be
 * resolved. The RT image is authoritative; there is no post-RT vanilla replay path.
 */
public final class BlockEntityModelGeometryAdapter {
    private static final org.slf4j.Logger REPORT_LOGGER =
        org.slf4j.LoggerFactory.getLogger("RTest");
    /** High 16 bits mark the block-entity identity namespace, disjoint from entity ids. */
    private static final long BLOCK_ENTITY_NAMESPACE = 0x8001000000000000L;
    private static final long BLOCK_ENTITY_HASH_MASK = 0x0000ffffffffffffL;
    public static final long UNSUPPORTED_TOPOLOGY = 0L;
    /** One topology per whitelisted vanilla model so a BLAS never mixes model shapes. */
    public static final long CHEST_TOPOLOGY = 0x43484553544CL;
    public static final long SHULKER_TOPOLOGY = 0x5348554C4B4552L;
    public static final long BELL_TOPOLOGY = 0x42454C4CL;
    public static final long BOOK_TOPOLOGY = 0x424F4F4BL;
    public static final long STATUE_TOPOLOGY = 0x535441545545L;
    public static final long BANNER_TOPOLOGY = 0x42414E4E4552L;
    public static final long BANNER_FLAG_TOPOLOGY = 0x42414E4E464C47L;
    public static final long SKULL_TOPOLOGY = 0x534B554C4CL;
    public static final long PIGLIN_HEAD_TOPOLOGY = 0x50494748454144L;
    public static final long DRAGON_HEAD_TOPOLOGY = 0x445241474F4EL;

    /**
     * Name-keyed because some vanilla renderers nest their model type privately. A wrong name only
     * disables one channel, and the contract test pins every entry.
     */
    private static final Map<String, Long> SUPPORTED_MODELS = Map.ofEntries(
        Map.entry("net.minecraft.client.model.object.chest.ChestModel", CHEST_TOPOLOGY),
        Map.entry("net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer$ShulkerBoxModel",
            SHULKER_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.bell.BellModel", BELL_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.book.BookModel", BOOK_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.statue.CopperGolemStatueModel", STATUE_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.banner.BannerModel", BANNER_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.banner.BannerFlagModel", BANNER_FLAG_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.skull.SkullModel", SKULL_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.skull.PiglinHeadModel", PIGLIN_HEAD_TOPOLOGY),
        Map.entry("net.minecraft.client.model.object.skull.DragonHeadModel", DRAGON_HEAD_TOPOLOGY)
    );

    private static final ThreadLocal<Owner> submittingOwner = new ThreadLocal<>();
    private static final Map<PoseStack.Pose, Owner> submitOwners =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Long, Snapshot> pending = new ConcurrentHashMap<>();
    private static final Set<String> reportedRejectedModels = ConcurrentHashMap.newKeySet();
    private static volatile RayTracingPbrSampler pbrSampler;

    public record Owner(long identity, int x, int y, int z,
                        double cameraX, double cameraY, double cameraZ, long topology) {
    }

    public record Snapshot(long identity, double x, double y, double z,
                           Identifier texture, long topology, PlayerModelGeometryAdapter.Mesh mesh) {
    }

    private BlockEntityModelGeometryAdapter() {
    }

    /** Installs the LabPBR companion sampler used for captured block-entity model quads. */
    static void setPbrSampler(RayTracingPbrSampler sampler) {
        pbrSampler = sampler;
    }

    static RayTracingPbrSampler pbrSampler() {
        return pbrSampler;
    }

    /**
     * Opens the owner scope for one {@code BlockEntityRenderDispatcher.submit} call. The state
     * carries block position, block-entity type and the camera the deferred pose is relative to.
     */
    public static void beginBlockEntity(BlockEntityRenderState state, CameraRenderState camera) {
        if (!RayTracingProbe.captureNativePlayers() || state == null || state.blockPos == null
            || camera == null || camera.pos == null) {
            submittingOwner.remove();
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            submittingOwner.remove();
            return;
        }
        BlockPos pos = state.blockPos;
        Identifier dimension = level.dimension().identifier();
        Identifier type = state.blockEntityType == null
            ? Identifier.fromNamespaceAndPath("rtest", state.getClass().getName())
            : state.blockEntityType.builtInRegistryHolder().key().identifier();
        // Custom block-entity renderers do not submit a Model, so registerSubmittedPose() cannot
        // assign their topology. Give the owner a stable per-type topology or DynamicEntityGeometry
        // will discard the captured moving-part mesh as UNSUPPORTED_TOPOLOGY.
        submittingOwner.set(new Owner(stableIdentity(dimension, type, pos),
            pos.getX(), pos.getY(), pos.getZ(), camera.pos.x, camera.pos.y, camera.pos.z,
            stableTopology(type.toString())));
    }

    public static void endBlockEntity() {
        submittingOwner.remove();
    }

    /** Returns whether the current deferred custom-geometry submit belongs to a block entity. */
    public static boolean hasBlockEntityOwner() {
        return submittingOwner.get() != null;
    }

    /** Captures custom geometry used by animated block-entity renderers (moving parts, fire, etc.). */
    public static SubmitNodeCollector.CustomGeometryRenderer wrapCustom(
            RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        Owner owner = submittingOwner.get();
        if (owner == null || renderer == null) {
            return renderer;
        }
        Identifier texture = LivingEntityGeometryAdapter.atlasForRenderType(renderType);
        return (pose, buffer) -> {
            PlayerModelGeometryAdapter.Capture capture = new PlayerModelGeometryAdapter.Capture(
                buffer,
                (float)(owner.cameraX() - owner.x()),
                (float)(owner.cameraY() - owner.y()),
                (float)(owner.cameraZ() - owner.z()),
                null,
                pbrSampler,
                LivingEntityGeometryAdapter.emissionFor(renderType));
            renderer.render(pose, capture);
            PlayerModelGeometryAdapter.Mesh mesh = LivingEntityGeometryAdapter.retag(capture.finish(), texture);
            if (mesh.triangleCount() > 0) {
                Snapshot previous = pending.get(owner.identity());
                if (previous != null && previous.mesh().triangleCount() > 0) {
                    mesh = PlayerModelGeometryAdapter.append(previous.mesh(), mesh);
                }
                pending.put(owner.identity(), new Snapshot(owner.identity(), owner.x(), owner.y(), owner.z(),
                    texture, owner.topology(), mesh));
            }
        };
    }

    /** Captures the principal moving baked model path used by modded block entities. */
    public static void captureBlockModel(PoseStack pose,
                                         java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                         int[] tintLayers) {
        Owner owner = submittingOwner.get();
        if (owner == null || pose == null || parts == null || parts.isEmpty()) {
            return;
        }
        ItemModelGeometryAdapter.Mesh captured = ItemModelGeometryAdapter.captureBlockModel(
            pose.last(), parts, tintLayers,
            (float)(owner.cameraX() - owner.x()),
            (float)(owner.cameraY() - owner.y()),
            (float)(owner.cameraZ() - owner.z()), pbrSampler);
        PlayerModelGeometryAdapter.Mesh mesh = new PlayerModelGeometryAdapter.Mesh(
            captured.vertices(), captured.materialData());
        if (mesh.triangleCount() == 0) {
            return;
        }
        Snapshot previous = pending.get(owner.identity());
        if (previous != null && previous.mesh().triangleCount() > 0) {
            mesh = PlayerModelGeometryAdapter.append(previous.mesh(), mesh);
        }
        pending.put(owner.identity(), new Snapshot(owner.identity(), owner.x(), owner.y(), owner.z(),
            net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS, owner.topology(), mesh));
    }

    /** Associates the copied pose stored in {@code ModelFeatureRenderer.Submit} with its owner. */
    public static void registerSubmittedPose(PoseStack.Pose pose, Model<?> model) {
        Owner owner = submittingOwner.get();
        if (owner == null || pose == null || model == null) {
            return;
        }
        long topology = topologyFor(model.getClass());
        if (topology == UNSUPPORTED_TOPOLOGY) {
            reportRejectedModel(model);
            return;
        }
        submitOwners.put(pose, new Owner(owner.identity(), owner.x(), owner.y(), owner.z(),
            owner.cameraX(), owner.cameraY(), owner.cameraZ(), topology));
    }

    /**
     * Handles a block-entity model submit and captures every model-compatible layer for RT.
     *
     * @return false when this submit does not belong to a block-entity owner scope, letting the
     *     entity/player capture path continue unchanged.
     */
    public static boolean captureOrForward(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                           int light, int overlay, int color,
                                           ModelFeatureRenderer.Submit<?> submit) {
        if (model == null || submit == null) {
            return false;
        }
        Owner owner = submitOwners.get(submit.pose());
        if (owner == null || owner.topology() == UNSUPPORTED_TOPOLOGY) {
            return false;
        }
        RenderType renderType = submit.renderType();
        TextureAtlasSprite sprite = submit.sprite();
        Identifier texture = sprite == null
            ? LivingEntityGeometryAdapter.atlasForRenderType(renderType)
            : sprite.atlasLocation();
        if (texture == null) {
            // RT remains authoritative even when a custom renderer omitted a resolvable texture.
            // The missing texture binding keeps the model in the dynamic TLAS rather than
            // allowing the submit to fall through to a native draw that will be overwritten.
            texture = net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation();
        }

        // prepareModel already ran the model's setupAnim and the submitted pose contains the
        // renderer's final placement. Remove only camera-relative translation so the BLAS stays
        // block-local and the matching TLAS translation re-applies the world position.
        var mesh = PlayerModelGeometryAdapter.captureDraw(model, pose, buffer, light, overlay, color,
            (float)(owner.cameraX() - owner.x()),
            (float)(owner.cameraY() - owner.y()),
            (float)(owner.cameraZ() - owner.z()), sprite,
            pbrSampler);
        LivingEntityGeometryAdapter.rememberTextureLocation(texture);
        mesh = LivingEntityGeometryAdapter.retag(mesh, texture);
        if (mesh.triangleCount() > 0) {
            Snapshot previous = pending.get(owner.identity());
            if (previous != null && previous.mesh().triangleCount() > 0) {
                // A renderer may submit several models for one block entity (for example a banner
                // base plus its pattern layers). Merge them into the single per-identity BLAS.
                mesh = PlayerModelGeometryAdapter.append(previous.mesh(), mesh);
            }
            pending.put(owner.identity(), new Snapshot(owner.identity(), owner.x(), owner.y(),
                owner.z(), texture, owner.topology(), mesh));
        }
        return true;
    }

    /** Retained as a public policy query for integrations; dynamic model submits are RT-owned. */
    public static boolean isSupportedLayer(Model<?> model, RenderType renderType, Identifier texture,
                                           boolean outline, boolean sheetedDecal) {
        if (model == null || renderType == null || texture == null) {
            return false;
        }
        // Dynamic RT uses the same material/alpha path for every model layer. Outline and decal
        // flags are accepted as well: their geometry must not disappear when the world raster is
        // replaced by RT.
        return true;
    }

    /** Maps a vanilla model class to its BLAS topology, or {@link #UNSUPPORTED_TOPOLOGY}. */
    public static long topologyFor(Class<?> modelClass) {
        if (modelClass == null) {
            return UNSUPPORTED_TOPOLOGY;
        }
        Long topology = SUPPORTED_MODELS.get(modelClass.getName());
        if (topology != null) {
            return topology;
        }
        long hash = 0xcbf29ce484222325L;
        String name = modelClass.getName();
        for (int index = 0; index < name.length(); index++) {
            hash ^= name.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash == UNSUPPORTED_TOPOLOGY ? 1L : hash;
    }

    /** A disjoint 48-bit block-entity namespace cannot equal a sign-extended 32-bit entity id. */
    private static long stableTopology(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash == UNSUPPORTED_TOPOLOGY ? 1L : hash;
    }

    public static long stableIdentity(Identifier dimension, Identifier type, BlockPos pos) {
        long hash = 0xcbf29ce484222325L;
        String value = dimension + "|" + type + "|" + pos.asLong();
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return BLOCK_ENTITY_NAMESPACE | (hash & BLOCK_ENTITY_HASH_MASK);
    }

    public static void beginWorldDraw() {
        pending.clear();
    }

    public static void endWorldDraw() {
        submitOwners.clear();
        submittingOwner.remove();
    }

    static Map<Long, Snapshot> drain() {
        Map<Long, Snapshot> result = new LinkedHashMap<>(pending);
        pending.clear();
        return Map.copyOf(result);
    }

    public static void clear() {
        pending.clear();
        submitOwners.clear();
        submittingOwner.remove();
        reportedRejectedModels.clear();
        pbrSampler = null;
    }

    /** Reports a malformed owner/model pairing without changing RT ownership. */
    private static void reportRejectedModel(Model<?> model) {
        String name = model.getClass().getName();
        if (reportedRejectedModels.add(name)) {
            REPORT_LOGGER.info("RTest block-entity model could not be assigned an RT topology: {}", name);
        }
    }

    /** Reports a malformed model layer; the RT pipeline remains authoritative. */
    private static void reportRejectedLayer(Model<?> model, RenderType renderType) {
        String name = model.getClass().getSimpleName() + " "
            + (renderType == null ? "<null>" : renderType.pipeline().toString());
        if (reportedRejectedModels.add(name)) {
            REPORT_LOGGER.info("RTest block-entity layer could not be captured by RT: {}", name);
        }
    }
}
