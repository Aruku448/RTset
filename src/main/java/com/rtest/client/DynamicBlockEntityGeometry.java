package com.rtest.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * CPU-side collection of block-entity instances for the audited model/RT geometry path.
 *
 * <p>This module collects immutable CPU geometry; {@link RayTracingVulkanPass} owns its Vulkan
 * upload and BLAS/TLAS lifetime. A {@link Geometry} owns immutable baked
 * quads, while an {@link Instance} only owns the per-frame transform and visibility metadata.
 * Consequently a consumer can upload a new transform every frame without rebuilding a BLAS.
 * Geometry is keyed by block-entity type and block state; changing either produces a new
 * geometry revision.</p>
 *
 * <p>This is not a BlockEntityRenderer replacement and never calls {@code submit}.  Native
 * rendering therefore remains responsible for text, items, particles, portals, and all
 * unsupported renderers.</p>
 */
public final class DynamicBlockEntityGeometry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SCAN_RADIUS = 128;

    public enum Support {
        /** The block-state baked model is an explicitly opted-in RT geometry source. */
        RT_GEOMETRY,
        /** Keep the vanilla BlockEntityRenderer as a raster overlay. */
        RASTER_OVERLAY,
        /** No safe adapter is available yet. */
        UNSUPPORTED
    }

    public record GeometryId(long value) {
    }

    /** Immutable cache key. The string is intentionally derived from BlockState, not object identity. */
    public record GeometryKey(String typeId, String blockState) {
    }

    public record Geometry(GeometryKey key, List<BakedQuad> quads, long revision) {
        public Geometry {
            quads = List.copyOf(quads);
        }
    }

    public record Instance(
        GeometryId id,
        Geometry geometry,
        Matrix4f transform,
        Support support,
        BlockPos blockPos,
        boolean animated,
        boolean emissive
    ) {
        public Instance {
            transform = new Matrix4f(transform);
            blockPos = blockPos.immutable();
        }
    }

    public record Frame(List<Instance> instances, Set<GeometryId> changedGeometry,
                        DynamicInstanceRegistry.Frame dynamicFrame) {
        public Frame {
            instances = List.copyOf(instances);
            changedGeometry = Set.copyOf(changedGeometry);
        }

        public Frame(List<Instance> instances, Set<GeometryId> changedGeometry) {
            this(instances, changedGeometry, null);
        }
    }

    private final Map<GeometryKey, Geometry> geometryCache = new HashMap<>();
    private final DynamicInstanceRegistry instanceRegistry = new DynamicInstanceRegistry();
    private final Set<String> bakedModelTypes = new HashSet<>();
    private final Map<GeometryId, String> liveGeometryKeys = new HashMap<>();
    private long nextGeometryRevision;

    /** Opts a type into the conservative BakedModel path after it has been audited. */
    public void registerBakedModelType(Identifier typeId) {
        bakedModelTypes.add(typeId.toString());
    }

    /** Removes an opt-in, causing subsequent frames to use the native overlay classification. */
    public void unregisterBakedModelType(Identifier typeId) {
        bakedModelTypes.remove(typeId.toString());
    }

    /**
     * Collects loaded block entities near the camera. This operation is CPU-only and bounded by
     * loaded chunks; it does not force chunk loading. Call once per rendered frame.
     */
    public Frame collect(
        ClientLevel level,
        Camera camera,
        BlockStateModelSet models,
        BlockEntityRenderDispatcher dispatcher,
        int renderDistanceChunks
    ) {
        int radius = Math.min(MAX_SCAN_RADIUS, Math.max(2, renderDistanceChunks) + 1);
        BlockPos cameraBlock = BlockPos.containing(camera.position());
        ChunkPos center = new ChunkPos(Math.floorDiv(cameraBlock.getX(), 16), Math.floorDiv(cameraBlock.getZ(), 16));
        Map<GeometryId, Instance> current = new HashMap<>();
        instanceRegistry.beginFrame();

        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                if (!level.hasChunk(x, z)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    addEntity(current, level, entity, models, dispatcher);
                }
            }
        }

        // End portals and similar renderers may be registered outside ordinary chunk iteration.
        for (BlockEntity entity : level.getGloballyRenderedBlockEntities()) {
            addEntity(current, level, entity, models, dispatcher);
        }

        List<Instance> instances = new ArrayList<>(current.values());
        instances.sort(Comparator.comparingLong(instance -> instance.id().value()));
        Set<GeometryId> changed = new HashSet<>();
        for (Instance instance : instances) {
            String oldKey = liveGeometryKeys.put(instance.id(), instance.geometry().key().toString());
            if (instance.support().equals(Support.RT_GEOMETRY)
                && (oldKey == null || !oldKey.equals(instance.geometry().key().toString()))) {
                changed.add(instance.id());
            }
        }
        liveGeometryKeys.keySet().retainAll(current.keySet());
        DynamicInstanceRegistry.Frame dynamicFrame = instanceRegistry.finish();
        if (!changed.isEmpty() || dynamicFrame.activeCount() > 0) {
            LOGGER.info("RTest BlockEntity MVP collected {} instances ({} RT geometry changes, {} raster/unsupported)",
                instances.size(), changed.size(), instances.size() - changed.size());
        }
        return new Frame(instances, changed, dynamicFrame);
    }

    private void addEntity(
        Map<GeometryId, Instance> output,
        ClientLevel level,
        BlockEntity entity,
        BlockStateModelSet models,
        BlockEntityRenderDispatcher dispatcher
    ) {
        if (entity.isRemoved() || entity.getLevel() != level) {
            return;
        }
        Identifier typeId = entity.getType().builtInRegistryHolder().key().identifier();
        GeometryId id = stableId(level, typeId, entity.getBlockPos());
        Support support = classify(typeId, dispatcher, entity);
        GeometryKey key = new GeometryKey(typeId.toString(), entity.getBlockState().toString());
        Geometry geometry = geometryCache.computeIfAbsent(key, ignored -> bake(id, key, entity, models));
        boolean animated = support == Support.RASTER_OVERLAY || hasAnimatedQuad(geometry.quads());
        BlockPos pos = entity.getBlockPos();
        Matrix4f transform = new Matrix4f().translation(pos.getX(), pos.getY(), pos.getZ());
        output.put(id, new Instance(id, geometry, transform, support, pos, animated, false));
        if (support == Support.RT_GEOMETRY) {
            long topologyKey = stableKey(key.typeId() + "|" + key.blockState());
            long materialKey = stableKey(key.typeId());
            instanceRegistry.upsert(id.value(), DynamicInstanceRegistry.Family.BLOCK_ENTITY,
                new DynamicInstanceRegistry.GeometryKey(topologyKey, materialKey),
                DynamicInstanceRegistry.Transform.translation(pos.getX(), pos.getY(), pos.getZ()),
                DynamicInstanceRegistry.FLAG_CUTOUT, false);
        }
    }

    private Support classify(Identifier typeId, BlockEntityRenderDispatcher dispatcher, BlockEntity entity) {
        if (bakedModelTypes.contains(typeId.toString())) {
            return Support.RT_GEOMETRY;
        }
        // The dispatcher is queried only to document/observe the native path; no draw call is intercepted.
        BlockEntityRenderer<?, ?> renderer = dispatcher.getRenderer(entity);
        if (renderer != null) {
            return Support.RASTER_OVERLAY;
        }
        return Support.UNSUPPORTED;
    }

    private Geometry bake(GeometryId id, GeometryKey key, BlockEntity entity, BlockStateModelSet models) {
        BlockStateModel model = models.get(entity.getBlockState());
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(net.minecraft.util.RandomSource.create(entity.getBlockPos().asLong()), parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                quads.addAll(part.getQuads(direction));
            }
            quads.addAll(part.getQuads(null));
        }
        return new Geometry(key, quads, ++nextGeometryRevision);
    }

    private static long stableKey(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static boolean hasAnimatedQuad(List<BakedQuad> quads) {
        return quads.stream().anyMatch(quad -> (quad.materialInfo().flags() & BakedQuad.FLAG_ANIMATED) != 0);
    }

    private static GeometryId stableId(ClientLevel level, Identifier typeId, BlockPos pos) {
        long hash = 0xcbf29ce484222325L;
        String value = level.dimension().identifier() + "|" + typeId + "|" + pos.asLong();
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return new GeometryId(hash);
    }
}
