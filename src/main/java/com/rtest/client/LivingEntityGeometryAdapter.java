package com.rtest.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import com.rtest.mixin.RenderSetupAccessor;
import com.rtest.mixin.RenderSetupTextureBindingAccessor;
import com.rtest.mixin.RenderTypeAccessor;

/**
 * Publishes model layers after vanilla has applied animation, visibility and renderer transforms.
 * The original renderer remains the owner of every draw; this class only stores a tee for the RT
 * dynamic scene. Texture slots are stable for the lifetime of a client world/render pass.
 */
public final class LivingEntityGeometryAdapter {
    private static final int TEXTURE_SLOT_COUNT = 64;
    private static final Map<EntityRenderState, Integer> stateIds = new WeakHashMap<>();
    private static final Map<LivingEntityRenderState, Identifier> bodyTextures = new WeakHashMap<>();
    private static final Map<LivingEntityRenderState, Integer> bodyTextureSlots = new WeakHashMap<>();
    private static final Map<Identifier, Integer> textureSlots = new LinkedHashMap<>();
    // Keep the exact prepared Vulkan binding used by vanilla. A RenderType can carry a custom
    // sampler and its texture may be an atlas or a direct entity image; resolving only the
    // Identifier later can fall back to the block atlas during an async/reload boundary.
    private static final Map<Integer, TextureBinding> textureBindings = new LinkedHashMap<>();
    private static final Map<Integer, Snapshot> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Context> current = new ThreadLocal<>();

    private record Context(int entityId, EntityRenderState state,
                           float offsetX, float offsetY, float offsetZ) {
    }

    public record Snapshot(double x, double y, double z, Identifier texture,
                           PlayerModelGeometryAdapter.Mesh mesh) {
    }

    public record TextureBinding(long view, long sampler) {
    }

    private LivingEntityGeometryAdapter() {
    }

    /** Called during extraction, before the deferred feature frame is prepared. */
    public static void registerState(EntityRenderState state, int entityId) {
        if (state != null) {
            stateIds.put(state, entityId);
        }
    }

    /** Called from the living renderer before it submits its body model. */
    public static void registerTexture(LivingEntityRenderState state, Identifier texture) {
        if (state != null && texture != null) {
            bodyTextures.put(state, texture);
            bodyTextureSlots.put(state, textureSlot(texture));
        }
    }

    public static Integer entityId(LivingEntityRenderState state) {
        return stateIds.get(state);
    }

    /** Returns the id registered for any vanilla entity render state, including items. */
    public static Integer entityId(EntityRenderState state) {
        return stateIds.get(state);
    }

    /** Returns the texture selected by a model's RenderType, including armor/cape layers. */
    public static Identifier textureFor(ModelFeatureRenderer.Submit<?> submit,
                                        LivingEntityRenderState state) {
        return textureFor(submit, (EntityRenderState)state);
    }

    /** Returns the primary texture for any entity model, including non-living render states. */
    public static Identifier textureFor(ModelFeatureRenderer.Submit<?> submit,
                                        EntityRenderState state) {
        if (submit.sprite() != null) {
            // Equipment layers in MC 26.2 are atlas sprites. The vanilla SpriteCoordinateExpander
            // remaps model-local UVs into this atlas region, so RT must use both the atlas texture
            // and the same remap rather than falling back to the entity PNG.
            Identifier atlas = submit.sprite().atlasLocation();
            rememberTextureLocation(atlas);
            return atlas;
        }
        return textureForRenderType(submit.renderType(), state);
    }

    private static Identifier textureForRenderType(RenderType renderType, EntityRenderState state) {
        try {
            RenderSetup setup = ((RenderTypeAccessor)(Object)renderType).rtest$getState();
            Map<String, ?> bindings = ((RenderSetupAccessor)(Object)setup).rtest$getTextures();
            // RenderSetup may also contain auxiliary lightmap/overlay bindings. Caustica resolves
            // the entity albedo from Sampler0 explicitly; iterating values can silently select an
            // auxiliary texture and produces magenta/incorrect armor and feature layers.
            Object primary = bindings.get("Sampler0");
            if (primary != null) {
                Identifier texture = ((RenderSetupTextureBindingAccessor)primary).rtest$getLocation();
                if (texture != null) {
                    rememberPreparedTexture(renderType, texture);
                    return texture;
                }
            }
            // Keep a compatibility fallback for custom RenderTypes that use a non-standard primary
            // binding name, but never treat the known auxiliary samplers as entity albedo.
            for (Map.Entry<String, ?> entry : bindings.entrySet()) {
                if ("Sampler1".equals(entry.getKey()) || "Sampler2".equals(entry.getKey())) {
                    continue;
                }
                Identifier texture = ((RenderSetupTextureBindingAccessor)entry.getValue()).rtest$getLocation();
                if (texture != null) {
                    rememberPreparedTexture(renderType, texture);
                    return texture;
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the body texture as a compatibility fallback for custom RenderTypes.
        }
        return state instanceof LivingEntityRenderState living ? bodyTextures.get(living) : null;
    }

    /** Captures the prepared Sampler0 image/sampler pair used by vanilla for this RenderType. */
    private static void rememberPreparedTexture(RenderType renderType, Identifier location) {
        try {
            PreparedRenderType prepared = renderType.prepare();
            PreparedRenderType.Texture selected = null;
            PreparedRenderType.Texture fallback = null;
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                if ("Sampler0".equals(texture.name())) {
                    selected = texture;
                    break;
                }
                if (fallback == null && !"Sampler1".equals(texture.name()) && !"Sampler2".equals(texture.name())) {
                    fallback = texture;
                }
            }
            if (selected == null) {
                selected = fallback;
            }
            if (selected != null
                && selected.textureView() instanceof VulkanGpuTextureView view
                && selected.sampler() instanceof VulkanGpuSampler sampler) {
                textureBindings.put(textureSlot(location), new TextureBinding(view.vkImageView(), sampler.vkSampler()));
            }
        } catch (RuntimeException ignored) {
            // Descriptor upload retries through the Identifier path on the next frame.
        }
    }

    /** Captures a direct texture-manager binding, used by atlas sprites and player skins. */
    public static void rememberTextureLocation(Identifier location) {
        if (location == null) {
            return;
        }
        try {
            var texture = Minecraft.getInstance().getTextureManager().getTexture(location);
            GpuTextureView view = texture.getTextureView();
            GpuSampler sampler = texture.getSampler();
            if (view instanceof VulkanGpuTextureView vulkanView
                && sampler instanceof VulkanGpuSampler vulkanSampler) {
                textureBindings.put(textureSlot(location),
                    new TextureBinding(vulkanView.vkImageView(), vulkanSampler.vkSampler()));
            }
        } catch (RuntimeException ignored) {
            // Resource reloads can temporarily expose no Vulkan view; retry next frame.
        }
    }

    /** Reserves the same descriptor slot used by dynamic model materials. */
    static int textureSlotForRt(Identifier location) {
        rememberTextureLocation(location);
        return textureSlot(location);
    }

    /**
     * Resolves the primary Sampler0 texture of a RenderType for dynamic captures that receive a
     * RenderType without a TextureAtlasSprite, such as skulls and copper golem statues.
     */
    public static Identifier atlasForRenderType(RenderType renderType) {
        return renderType == null ? null : textureForRenderType(renderType, null);
    }

    /** True for vanilla layers whose pipeline is explicitly full-bright/emissive. */
    public static boolean isEmissive(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        var pipeline = renderType.pipeline();
        if (pipeline == RenderPipelines.EYES
            || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE) {
            return true;
        }
        // Some entity layers wrap/recreate RenderType while retaining the vanilla name. ITRP
        // handles these as the dedicated spidereyes/entity-emissive pass too; keep the same
        // semantic fallback instead of relying only on object identity for RenderPipeline.
        String name = renderType.toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("eyes[")
            || name.startsWith("entity_translucent_emissive[")
            || name.startsWith("entity translucent emissive[");
    }

    /** Radiance assigned to a vanilla full-bright entity layer. */
    public static float emissionFor(RenderType renderType) {
        if (!isEmissive(renderType)) {
            return 0.0F;
        }
        // ITRP treats the dedicated eyes/emissive pass as a radiance source, not merely as a
        // full-bright albedo. Reuse the same calibrated scale as authored/block emitters.
        return Math.max(RayTracingEmission.fromMinecraftLevel(15),
            RayTracingClientConfig.INSTANCE.emissionScale.get().floatValue());
    }

    public static TextureBinding textureBinding(int slot) {
        return textureBindings.get(slot);
    }

    public static int textureSlot(Identifier texture) {
        if (texture == null) {
            return 0;
        }
        Integer existing = textureSlots.get(texture);
        if (existing != null) {
            return existing;
        }
        // Slot zero is reserved for the missing-texture fallback.
        if (textureSlots.size() >= TEXTURE_SLOT_COUNT - 1) {
            return 0;
        }
        int slot = textureSlots.size() + 1;
        textureSlots.put(texture, slot);
        return slot;
    }

    public static int textureSlot(LivingEntityRenderState state) {
        Integer slot = bodyTextureSlots.get(state);
        return slot == null ? textureSlot(bodyTextures.get(state)) : slot;
    }

    /** Re-tags selector-2 materials with the descriptor slot belonging to this layer texture. */
    public static PlayerModelGeometryAdapter.Mesh retag(
        PlayerModelGeometryAdapter.Mesh mesh, Identifier texture
    ) {
        if (mesh == null || texture == null) {
            return mesh;
        }
        int slot = textureSlot(texture);
        float[] materials = mesh.materialData().clone();
        for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
            int offset = triangle * 28;
            if (materials[offset + 15] > 1.5F && materials[offset + 15] < 2.5F) {
                materials[offset + 24] = slot;
            }
        }
        return new PlayerModelGeometryAdapter.Mesh(mesh.vertices(), materials);
    }

    /** Appends one opaque/cutout model layer to the same living-entity BLAS. */
    /** Opens the owner context while an entity renderer submits deferred custom geometry. */
    public static void beginEntity(LivingEntityRenderState state, net.minecraft.world.phys.Vec3 camera) {
        beginEntity((EntityRenderState)state, camera);
    }

    /** Opens a capture scope for every vanilla entity render state, not only living entities. */
    public static void beginEntity(EntityRenderState state, net.minecraft.world.phys.Vec3 camera) {
        Integer entityId = entityId(state);
        if (entityId == null || camera == null) {
            current.remove();
            return;
        }
        current.set(new Context(entityId, state,
            (float)(camera.x - state.x), (float)(camera.y - state.y), (float)(camera.z - state.z)));
    }

    public static void endEntity() {
        current.remove();
    }

    /** Tees solid custom geometry into the same RT mesh without touching translucent vanilla work. */
    public static SubmitNodeCollector.CustomGeometryRenderer wrapCustom(
        RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer
    ) {
        Context context = current.get();
        if (context == null || (renderType.hasBlending() && !isEmissive(renderType))) {
            return renderer;
        }
        Identifier texture = textureForRenderType(renderType, context.state());
        return (pose, buffer) -> {
            PlayerModelGeometryAdapter.Capture capture = new PlayerModelGeometryAdapter.Capture(
                buffer, context.offsetX(), context.offsetY(), context.offsetZ(), null,
                RayTracingProbe.pbrSampler(), emissionFor(renderType));
            renderer.render(pose, capture);
            publishModel(context.entityId(), context.state(), texture, capture.finish());
        };
    }

    public static void publishModel(int entityId, LivingEntityRenderState state,
                                    Identifier texture,
                                    PlayerModelGeometryAdapter.Mesh mesh) {
        publishModel(entityId, (EntityRenderState)state, texture, mesh);
    }

    public static void publishModel(int entityId, EntityRenderState state,
                                    Identifier texture,
                                    PlayerModelGeometryAdapter.Mesh mesh) {
        Identifier selected = texture != null
            ? texture
            : state instanceof LivingEntityRenderState living ? bodyTextures.get(living) : null;
        Snapshot previous = pending.get(entityId);
        PlayerModelGeometryAdapter.Mesh tagged = retag(mesh, selected);
        if (previous == null) {
            pending.put(entityId, new Snapshot(state.x, state.y, state.z, selected, tagged));
        } else {
            pending.put(entityId, new Snapshot(previous.x, previous.y, previous.z, selected,
                PlayerModelGeometryAdapter.append(previous.mesh, tagged)));
        }
    }

    public static void beginWorldDraw() {
        pending.clear();
    }

    public static void endWorldDraw() {
        stateIds.clear();
        bodyTextures.clear();
        bodyTextureSlots.clear();
    }

    public static void clear() {
        pending.clear();
        stateIds.clear();
        bodyTextures.clear();
        bodyTextureSlots.clear();
        textureSlots.clear();
        textureBindings.clear();
    }

    static Map<Integer, Snapshot> drain() {
        Map<Integer, Snapshot> result = Map.copyOf(pending);
        pending.clear();
        return result;
    }

    static Map<Integer, Identifier> drainTextureSlots() {
        Map<Integer, Identifier> result = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Integer> entry : textureSlots.entrySet()) {
            result.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(result);
    }
}
