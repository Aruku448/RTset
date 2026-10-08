package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.logging.LogUtils;
import com.rtest.client.RayTracingScene.SceneGeometry;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.slf4j.Logger;

/**
 * Owns mutable dynamic-scene state and publishes per-frame BLAS, TLAS-instance, material, and
 * motion-metadata updates. The pass lends it the current frame's Vulkan resources.
 */
final class RayTracingDynamicInstances implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DYNAMIC_BLAS_UPDATE_INTERVAL_FRAMES = 1;

    // Player body overlays plus both held-item hands share one dynamic BLAS. Keep enough
    // material slots for a fully decorated body and a high-poly baked item without silently
    // dropping the player instance.
    static final int DYNAMIC_PLAYER_MATERIAL_TRIANGLES =
        DynamicEntityGeometry.DYNAMIC_MODEL_TRIANGLE_CAPACITY;
    static final int DYNAMIC_PLACEHOLDER_TRIANGLES = 12;
    static final int DYNAMIC_ITEM_MATERIAL_TRIANGLES =
        DynamicEntityGeometry.DYNAMIC_ITEM_TRIANGLE_CAPACITY;
    static final int DYNAMIC_SLOT_MATERIAL_TRIANGLES =
        DYNAMIC_PLAYER_MATERIAL_TRIANGLES + DYNAMIC_ITEM_MATERIAL_TRIANGLES;
    static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;

    static final float[] DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL =
        DynamicPlaceholderGeometry.box(0.20F, 0.55F, 0.90F, false).materialData();
    static final float[] DYNAMIC_ITEM_PLACEHOLDER_MATERIAL =
        DynamicPlaceholderGeometry.box(1.0F, 0.35F, 0.05F, true).materialData();

    private final int dynamicSlotCapacity;
    private final List<DynamicCachedBlas> dynamicBlas = new ArrayList<>();
    private final DynamicBlasCache dynamicBlasCache = new DynamicBlasCache();
    private final Map<Long, DynamicCachedBlas> playerModelBlas = new HashMap<>();
    private final Map<Long, DynamicCachedBlas> livingModelBlas = new HashMap<>();
    private final Map<Long, DynamicCachedBlas> blockEntityModelBlas = new HashMap<>();
    private final Map<Long, DynamicCachedBlas> itemModelBlas = new HashMap<>();
    private final Map<Long, Long> dynamicAddressesScratch = new HashMap<>(96);
    private final Map<Long, Integer> dynamicMaterialBasesScratch = new HashMap<>(96);
    private final Set<Long> livePlayersScratch = new HashSet<>(96);
    private final Set<Long> liveLivingScratch = new HashSet<>(96);
    private final Set<Long> liveBlockEntitiesScratch = new HashSet<>(96);
    private final Set<Long> liveItemsScratch = new HashSet<>(96);
    private final Set<Long> historyResetIdentitiesScratch = new HashSet<>(96);
    private final DynamicInstanceRegistry.Instance[] dynamicInstancesBySlotScratch =
        new DynamicInstanceRegistry.Instance[64];
    private final byte[] dynamicMotionMetadataScratch = new byte[64
        * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT];
    private final byte[] uploadedDynamicMotionMetadata = new byte[64
        * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT];
    private boolean dynamicMotionMetadataInitialized;
    // A replacement TLAS has no valid correspondence with prior secondary-ray geometry.
    private boolean dynamicHistoryResetPending = true;
    // Refill dynamic material rows after a full geometry publication resets their layout.
    private boolean dynamicMaterialWritePending = true;
    private boolean reportedItemMaterial;
    private Set<Long> representedBlockEntities = Set.of();
    private long dynamicTlasUpdateCount;
    private long dynamicBlasBuildCommandCount;
    private long dynamicBlasDeferredUpdateCount;
    private long dynamicMaterialUploadCount;
    private long dynamicMaterialBytes;
    private long dynamicBlasBatchCount;
    private long dynamicMetadataUploadCount;

    // Borrowed for one update; all buffers remain owned by RayTracingVulkanPass.
    private VulkanDevice device;
    private SceneGeometry geometry;
    private int sectionBlasCount;
    private long fallbackBlasDeviceAddress;
    private NativeBuffer instanceBuffer;
    private NativeBuffer dynamicMotionMetadataBuffer;
    private NativeBuffer scratchBuffer;
    private NativeBuffer materialBuffer;
    private long frameNumber;
    private long scratchAlignment;

    RayTracingDynamicInstances(int dynamicSlotCapacity) {
        this.dynamicSlotCapacity = dynamicSlotCapacity;
    }

    List<DynamicCachedBlas> blases() {
        return this.dynamicBlas;
    }

    DynamicCachedBlas addPlaceholder(VulkanDevice device, long topologyKey,
                                     DynamicPlaceholderGeometry.Mesh mesh) {
        DynamicCachedBlas cached = this.dynamicBlasCache.acquire(device, topologyKey, mesh);
        this.dynamicBlas.add(cached);
        return cached;
    }

    void retireCompleted() {
        this.dynamicBlasCache.retireCompleted();
    }

    void markMaterialWritePending() {
        this.dynamicMaterialWritePending = true;
    }

    void markHistoryResetPending() {
        this.dynamicHistoryResetPending = true;
    }

    void historyResetSubmitted() {
        this.dynamicHistoryResetPending = false;
    }

    void recordBlasBuildBatches(int count) {
        this.dynamicBlasBatchCount += count;
    }

    void recordBlasBuildCommands(int count) {
        this.dynamicBlasBuildCommandCount += count;
    }

    Stats stats() {
        return new Stats(this.dynamicTlasUpdateCount, this.dynamicBlasBuildCommandCount,
            this.dynamicBlasDeferredUpdateCount, this.dynamicMaterialUploadCount, this.dynamicMetadataUploadCount,
            this.dynamicBlasBatchCount, this.dynamicMaterialBytes);
    }

    NativeBuffer scratchBuffer() {
        return this.scratchBuffer;
    }

    Set<Long> representedBlockEntities() {
        return this.representedBlockEntities;
    }

    boolean update(
        DynamicEntityGeometry.Frame frame,
        boolean forceInstanceWrite,
        VulkanDevice device,
        RayTracingSupport.Limits accelerationLimits,
        SceneGeometry geometry,
        int sectionBlasCount,
        long fallbackBlasDeviceAddress,
        NativeBuffer instanceBuffer,
        NativeBuffer dynamicMotionMetadataBuffer,
        NativeBuffer materialBuffer,
        NativeBuffer scratchBuffer,
        long frameNumber
    ) {
        this.device = device;
        this.scratchAlignment = Math.max(1L, accelerationLimits.minScratchAlignment());
        this.geometry = geometry;
        this.sectionBlasCount = sectionBlasCount;
        this.fallbackBlasDeviceAddress = fallbackBlasDeviceAddress;
        this.instanceBuffer = instanceBuffer;
        this.dynamicMotionMetadataBuffer = dynamicMotionMetadataBuffer;
        this.materialBuffer = materialBuffer;
        this.scratchBuffer = scratchBuffer;
        this.frameNumber = frameNumber;
        return updateDynamicInstances(frame, forceInstanceWrite);
    }

    record Stats(long tlasUpdates, long blasBuildCommands, long deferredBlasUpdates,
                 long materialUploads, long metadataUploads, long blasBuildBatches, long materialBytes) {
    }

    private static final class DynamicBlasCache implements AutoCloseable {
        private final Map<Long, DynamicCachedBlas> entries = new HashMap<>();
        // A replaced/removed dynamic BLAS may still be referenced by the old TLAS while
        // the current submission records its replacement update. Retire it only after the
        // submission fence, rather than destroying its storage during instance publication.
        private final List<DynamicCachedBlas> retired = new ArrayList<>();

        DynamicCachedBlas acquire(VulkanDevice device, long topologyKey, DynamicPlaceholderGeometry.Mesh mesh) {
            DynamicCachedBlas cached = entries.get(topologyKey);
            int fingerprint = java.util.Arrays.hashCode(mesh.vertices());
            if (cached != null && cached.fingerprint == fingerprint
                && cached.triangleCount == mesh.triangleCount()
                && java.util.Arrays.equals(cached.topologyVertices, mesh.vertices())) {
                return cached;
            }
            DynamicCachedBlas candidate = createCandidate(device, topologyKey, mesh);
            try {
                DynamicCachedBlas previous = entries.put(topologyKey, candidate);
                if (previous != null && previous != candidate) {
                    retired.add(previous);
                }
                return candidate;
            } catch (Throwable throwable) {
                // The old entry remains valid if publication or its replacement fails.
                if (entries.get(topologyKey) != candidate) {
                    candidate.close();
                }
                throw throwable;
            }
        }

        /**
         * Publishes a new BLAS for changed animation vertices. RADV has been unstable when
         * BUILD repeatedly targets an AS that was referenced by the previous TLAS. Keep the
         * old resource in the fence-protected retire list and build the replacement instead.
         */
        DynamicCachedBlas replace(VulkanDevice device, long topologyKey, DynamicPlaceholderGeometry.Mesh mesh) {
            DynamicCachedBlas candidate = createCandidate(device, topologyKey, mesh);
            try {
                DynamicCachedBlas previous = entries.put(topologyKey, candidate);
                if (previous != null && previous != candidate) {
                    retired.add(previous);
                }
                return candidate;
            } catch (Throwable throwable) {
                if (entries.get(topologyKey) != candidate) {
                    candidate.close();
                }
                throw throwable;
            }
        }

        private DynamicCachedBlas createCandidate(VulkanDevice device, long topologyKey,
                                                  DynamicPlaceholderGeometry.Mesh mesh) {
            int fingerprint = java.util.Arrays.hashCode(mesh.vertices());
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            NativeBuffer vertices = NativeBuffer.create(device, (long)mesh.vertices().length * Float.BYTES, usage, true);
            AccelerationStructure bottomLevel = null;
            DynamicCachedBlas candidate = null;
            try {
                try (NativeBuffer.Mapped mapped = vertices.map()) {
                    mapped.buffer().asFloatBuffer().put(mesh.vertices());
                }
                bottomLevel = AccelerationStructure.createBottomLevel(device, vertices, mesh.triangleCount());
                candidate = new DynamicCachedBlas(topologyKey, fingerprint, mesh.triangleCount(),
                    mesh.vertices(), vertices, bottomLevel);
            } catch (Throwable throwable) {
                if (candidate != null) {
                    candidate.close();
                } else if (bottomLevel != null) {
                    bottomLevel.close();
                    vertices.close();
                } else {
                    vertices.close();
                }
                throw throwable;
            }
            return candidate;
        }

        void release(long key) {
            DynamicCachedBlas cached = entries.remove(key);
            if (cached != null) {
                retired.add(cached);
            }
        }

        void retireCompleted() {
            Throwable failure = null;
            for (DynamicCachedBlas cached : retired) {
                try {
                    cached.close();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            retired.clear();
            rethrow(failure);
        }

        @Override
        public void close() {
            Throwable failure = null;
            for (DynamicCachedBlas cached : entries.values()) {
                try {
                    cached.close();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            entries.clear();
            for (DynamicCachedBlas cached : retired) {
                try {
                    cached.close();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            retired.clear();
            rethrow(failure);
        }
    }

    static final class DynamicCachedBlas implements AutoCloseable {
        final long topologyKey;
        int fingerprint;
        final int triangleCount;
        final float[] topologyVertices;
        final NativeBuffer vertexBuffer;
        final AccelerationStructure bottomLevel;
        boolean built;
        boolean pendingUpdate;
        float[] uploadedVertices;
        float[] uploadedMaterials;
        long lastBlasReplacementFrame = -1L;
        private boolean closed;

        DynamicCachedBlas(long topologyKey, int fingerprint, int triangleCount,
                                  float[] topologyVertices, NativeBuffer vertexBuffer,
                                  AccelerationStructure bottomLevel) {
            this.topologyKey = topologyKey;
            this.fingerprint = fingerprint;
            this.triangleCount = triangleCount;
            this.topologyVertices = java.util.Arrays.copyOf(topologyVertices, topologyVertices.length);
            // The initial upload and topology share one owned, immutable snapshot.
            // UPDATE replaces uploadedVertices; it never mutates this topology array.
            this.uploadedVertices = this.topologyVertices;
            this.vertexBuffer = vertexBuffer;
            this.bottomLevel = bottomLevel;
        }

        private boolean updateVertices(float[] vertices) {
            if (vertices.length * Float.BYTES != vertexBuffer.size) {
                throw new IllegalArgumentException("Dynamic mesh topology changed during BLAS UPDATE");
            }
            if (java.util.Arrays.equals(uploadedVertices, vertices)) return false;
            try (NativeBuffer.Mapped mapped = vertexBuffer.map()) {
                mapped.buffer().asFloatBuffer().put(vertices);
            }
            uploadedVertices = java.util.Arrays.copyOf(vertices, vertices.length);
            // Keep the cache fingerprint in sync with the uploaded geometry. Otherwise an
            // animated model is mistaken for a topology replacement on every frame, causing
            // a fresh BLAS allocation instead of an in-place update.
            fingerprint = java.util.Arrays.hashCode(vertices);
            pendingUpdate = true;
            return true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                bottomLevel.close();
            } finally {
                vertexBuffer.close();
            }
        }
    }

    private boolean updateDynamicInstances(DynamicEntityGeometry.Frame frame,
                                           boolean forceInstanceWrite) {
        long dynamicFrameNumber = this.frameNumber;

        DynamicInstanceRegistry.Frame dynamicFrame = frame.dynamicFrame();
        DynamicInstanceRegistry.Instance[] instancesBySlot = this.dynamicInstancesBySlotScratch;
        DynamicTlasInstanceWriter.indexInstancesBySlot(dynamicFrame, dynamicSlotCapacity, instancesBySlot);
        boolean tlasChanged = frame.dynamicFrame().instances().stream()
            .anyMatch(instance -> instance.historyReset()
                || !instance.currentTransform().equals(instance.previousTransform()));
        Map<Long, Long> addresses = this.dynamicAddressesScratch;
        Map<Long, Integer> materialBases = this.dynamicMaterialBasesScratch;
        Set<Long> livePlayers = this.livePlayersScratch;
        Set<Long> liveLiving = this.liveLivingScratch;
        Set<Long> liveBlockEntities = this.liveBlockEntitiesScratch;
        Set<Long> liveItems = this.liveItemsScratch;
        Set<Long> historyResetIdentities = this.historyResetIdentitiesScratch;
        addresses.clear();
        materialBases.clear();
        livePlayers.clear();
        liveLiving.clear();
        liveBlockEntities.clear();
        liveItems.clear();
        historyResetIdentities.clear();
        if (this.dynamicHistoryResetPending) {
            for (DynamicInstanceRegistry.Instance instance : frame.dynamicFrame().instances()) {
                if (instance.active()) {
                    historyResetIdentities.add(instance.identity());
                }
            }
        }
        // dispatch waits for its previous frame fence before returning, so the previous use is
        // complete. Stable dynamic frames do not need to map/flush the full material SSBO.
        boolean materialUploadNeeded = this.dynamicMaterialWritePending
            || hasChangedDynamicMaterials(frame);
        try (NativeBuffer.Mapped mapped = materialUploadNeeded ? materialBuffer.map() : null) {
            FloatBuffer materials = mapped == null ? null : mapped.buffer().asFloatBuffer();
            if (mapped != null) mapped.flushOnlyWrittenRanges();
            for (DynamicEntityGeometry.Instance instance : frame.instances()) {
                long id = instance.snapshot().identity();
                int slot = instance.snapshot().slot();
                if (slot >= dynamicSlotCapacity || !instance.snapshot().active()) continue;
                if (instance.family() == DynamicEntityGeometry.Family.PLAYER_BODY
                    || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_BODY
                    || instance.family() == DynamicEntityGeometry.Family.LIVING_BODY
                    || instance.family() == DynamicEntityGeometry.Family.BLOCK_ENTITY_MODEL
                    || instance.family() == DynamicEntityGeometry.Family.PARTICLE) {
                    boolean player = instance.family() == DynamicEntityGeometry.Family.PLAYER_BODY
                        || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_BODY;
                    boolean blockEntity =
                        instance.family() == DynamicEntityGeometry.Family.BLOCK_ENTITY_MODEL;
                    PlayerModelGeometryAdapter.Mesh mesh = player ? frame.playerMeshes().get(id)
                        : blockEntity ? frame.blockEntityMeshes().get(id)
                        : frame.livingMeshes().get(id);
                    if (mesh != null && mesh.triangleCount() <= DYNAMIC_PLAYER_MATERIAL_TRIANGLES) {
                        // Block-entity identities and cache keys occupy their own high-bit
                        // namespace; their per-slot material range is the same bounded model
                        // range as player and living models.
                        long key = DynamicEntityGeometry.modelBlasCacheKey(id, instance.snapshot().flags(), blockEntity);
                        Map<Long, DynamicCachedBlas> cacheMap = player ? playerModelBlas
                            : blockEntity ? blockEntityModelBlas : livingModelBlas;
                        DynamicCachedBlas cached = cacheMap.get(id);
                        boolean verticesChanged = cached != null && cached.triangleCount == mesh.triangleCount()
                            && !java.util.Arrays.equals(cached.uploadedVertices, mesh.vertices());
                        DynamicPlaceholderGeometry.Mesh dynamicMesh =
                            new DynamicPlaceholderGeometry.Mesh(mesh.vertices(), mesh.materialData());
                        if (cached != null && cached.triangleCount != mesh.triangleCount()) {
                            // acquire() publishes the replacement only after its complete
                            // BLAS allocation succeeds; keep the old entry/list membership
                            // untouched on an allocation failure.
                            DynamicCachedBlas previous = cached;
                            cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                            cached.uploadedMaterials = previous.uploadedMaterials;
                            dynamicBlas.remove(previous);
                            dynamicBlas.add(cached);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            cacheMap.put(id, cached);
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (cached == null) {
                            cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            cacheMap.put(id, cached);
                            dynamicBlas.add(cached);
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (verticesChanged && shouldReplaceDynamicBlas(cached, dynamicFrameNumber)) {
                            // Do not rewrite a BLAS input buffer that is still referenced by
                            // the previous TLAS. Publish a replacement resource and retire
                            // the old one after the frame fence instead.
                            DynamicCachedBlas previous = cached;
                            cached = dynamicBlasCache.replace(device, key, dynamicMesh);
                            cached.uploadedMaterials = previous.uploadedMaterials;
                            dynamicBlas.remove(previous);
                            dynamicBlas.add(cached);
                            cacheMap.put(id, cached);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (verticesChanged) {
                            this.dynamicBlasDeferredUpdateCount++;
                        }
                        if (player) {
                            livePlayers.add(id);
                        } else if (blockEntity) {
                            liveBlockEntities.add(id);
                        } else {
                            liveLiving.add(id);
                        }
                        int base = geometry.materialLayout.highWaterTriangle() + DYNAMIC_PLACEHOLDER_TRIANGLES
                            + DYNAMIC_ITEM_MATERIAL_TRIANGLES + slot * DYNAMIC_SLOT_MATERIAL_TRIANGLES;
                        if (materials != null) {
                            // Each triangle retains its authored texture descriptor index;
                            // the TLAS slot must not replace skin/armor/trim layer indices.
                            DynamicMaterialDelta.Upload upload = DynamicMaterialDelta.writeAndRemember(materials, base,
                                cached.uploadedMaterials, mesh.materialData(), this.dynamicMaterialWritePending,
                                mapped::flushOnlyRange);
                            this.dynamicMaterialBytes += upload.bytes();
                            cached.uploadedMaterials = upload.snapshot();
                        }
                        materialBases.put(id, base);
                        addresses.put(id, cached.bottomLevel.deviceAddress);
                        continue;
                    }
                } else if (instance.family() == DynamicEntityGeometry.Family.ITEM_PLACEHOLDER
                    || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_ITEM) {
                    ItemModelGeometryAdapter.Mesh mesh = frame.itemMeshes().get(id);
                    if (mesh != null && mesh.triangleCount() <= DYNAMIC_ITEM_MATERIAL_TRIANGLES) {
                        long key = 0x6000000000000000L | (id & 0xffffffffL);
                        DynamicCachedBlas cached = itemModelBlas.get(id);
                        boolean verticesChanged = cached != null && cached.triangleCount == mesh.triangleCount()
                            && !java.util.Arrays.equals(cached.uploadedVertices, mesh.vertices());
                        DynamicPlaceholderGeometry.Mesh dynamicMesh =
                            new DynamicPlaceholderGeometry.Mesh(mesh.vertices(), mesh.materialData());
                        if (cached != null && cached.triangleCount != mesh.triangleCount()) {
                            // See the player path above: publish the new BLAS before
                            // removing the old list entry so a failed replacement is atomic.
                            DynamicCachedBlas previous = cached;
                            cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                            cached.uploadedMaterials = previous.uploadedMaterials;
                            dynamicBlas.remove(previous);
                            dynamicBlas.add(cached);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            itemModelBlas.put(id, cached);
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (cached == null) {
                            cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            itemModelBlas.put(id, cached);
                            dynamicBlas.add(cached);
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (verticesChanged && shouldReplaceDynamicBlas(cached, dynamicFrameNumber)) {
                            DynamicCachedBlas previous = cached;
                            cached = dynamicBlasCache.replace(device, key, dynamicMesh);
                            cached.uploadedMaterials = previous.uploadedMaterials;
                            dynamicBlas.remove(previous);
                            dynamicBlas.add(cached);
                            itemModelBlas.put(id, cached);
                            cached.lastBlasReplacementFrame = dynamicFrameNumber;
                            historyResetIdentities.add(id);
                            tlasChanged = true;
                        } else if (verticesChanged) {
                            this.dynamicBlasDeferredUpdateCount++;
                        }
                        liveItems.add(id);
                        int base = geometry.materialLayout.highWaterTriangle() + DYNAMIC_PLACEHOLDER_TRIANGLES
                            + DYNAMIC_ITEM_MATERIAL_TRIANGLES
                            + slot * DYNAMIC_SLOT_MATERIAL_TRIANGLES
                            + DYNAMIC_PLAYER_MATERIAL_TRIANGLES;
                        if (materials != null) {
                            DynamicMaterialDelta.Upload upload = DynamicMaterialDelta.writeAndRemember(materials, base,
                                cached.uploadedMaterials, mesh.materialData(), this.dynamicMaterialWritePending,
                                mapped::flushOnlyRange);
                            this.dynamicMaterialBytes += upload.bytes();
                            cached.uploadedMaterials = upload.snapshot();
                        }
                        if (!reportedItemMaterial) {
                            reportedItemMaterial = true;
                            float[] material = mesh.materialData();
                            LOGGER.info(
                                "RTest item material upload: id={}, base={}, triangles={}, selector={}, pbrMapIndex={}, opticalX={}, uv=({}, {})..({}, {})",
                                id, base, mesh.triangleCount(), material[15], material[19], material[24],
                                material[8], material[9], material[10], material[11]);
                        }
                        materialBases.put(id, base);
                        addresses.put(id, cached.bottomLevel.deviceAddress);
                        continue;
                    }
                }

                long topology = switch (instance.family()) {
                    case PLAYER_BODY, FIRST_PERSON_BODY -> 0x504C415945524CL;
                    case LIVING_BODY -> 0x4C4956494E474CL;
                    case BLOCK_ENTITY_MODEL -> instance.topology();
                    case ITEM_PLACEHOLDER, FIRST_PERSON_ITEM -> 0x4954454D4CL;
                    case PARTICLE -> 0x5041525449434C45L;
                };
                for (DynamicCachedBlas cached : dynamicBlas) {
                    if (cached.topologyKey == topology) {
                        addresses.put(id, cached.bottomLevel.deviceAddress);
                        materialBases.put(id, geometry.materialLayout.highWaterTriangle()
                            + (instance.family() == DynamicEntityGeometry.Family.ITEM_PLACEHOLDER
                                ? DYNAMIC_PLACEHOLDER_TRIANGLES : 0));
                        break;
                    }
                }
            }
            this.dynamicMaterialWritePending = false;
        }
        if (materialUploadNeeded) {
            this.dynamicMaterialUploadCount++;
        }
        var players = playerModelBlas.entrySet().iterator();
        while (players.hasNext()) {
            var entry = players.next();
            if (!livePlayers.contains(entry.getKey())) {
                DynamicCachedBlas cached = entry.getValue();
                dynamicBlas.remove(cached);
                dynamicBlasCache.release(cached.topologyKey);
                tlasChanged = true;
                players.remove();
            }
        }
        var living = livingModelBlas.entrySet().iterator();
        while (living.hasNext()) {
            var entry = living.next();
            if (!liveLiving.contains(entry.getKey())) {
                DynamicCachedBlas cached = entry.getValue();
                dynamicBlas.remove(cached);
                dynamicBlasCache.release(cached.topologyKey);
                tlasChanged = true;
                living.remove();
            }
        }
        var blockEntities = blockEntityModelBlas.entrySet().iterator();
        while (blockEntities.hasNext()) {
            var entry = blockEntities.next();
            if (!liveBlockEntities.contains(entry.getKey())) {
                DynamicCachedBlas cached = entry.getValue();
                dynamicBlas.remove(cached);
                dynamicBlasCache.release(cached.topologyKey);
                tlasChanged = true;
                blockEntities.remove();
            }
        }
        var items = itemModelBlas.entrySet().iterator();
        while (items.hasNext()) {
            var entry = items.next();
            if (!liveItems.contains(entry.getKey())) {
                DynamicCachedBlas cached = entry.getValue();
                dynamicBlas.remove(cached);
                dynamicBlasCache.release(cached.topologyKey);
                tlasChanged = true;
                items.remove();
            }
        }
        long scratchSize = 0L;
        for (DynamicCachedBlas cached : dynamicBlas) {
            scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
        }
        long scratchAlignment = this.scratchAlignment;
        long[] pendingSizes = dynamicBlas.stream().filter(cached -> !cached.built || cached.pendingUpdate)
            .mapToLong(cached -> cached.bottomLevel.scratchSize).toArray();
        scratchSize = Math.max(scratchSize, BlasBuildBatch.plan(pendingSizes, scratchAlignment).bytes());
        long requiredScratchBufferSize = VulkanAccelerationResources.scratchBufferSize(scratchSize, scratchAlignment);
        if (requiredScratchBufferSize > scratchBuffer.size) {
            NativeBuffer replacement = NativeBuffer.create(device, requiredScratchBufferSize,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
            NativeBuffer previousScratch = scratchBuffer;
            // Publish ownership before closing the old allocation. If destruction reports a
            // runtime/device failure, the replacement is still reachable from the pass and
            // will be released by close().
            scratchBuffer = replacement;
            previousScratch.close();
        }
        if (tlasChanged || forceInstanceWrite) {
            if (tlasChanged) {
                this.dynamicTlasUpdateCount++;
            }
            try (NativeBuffer.Mapped mapped = instanceBuffer.map()) {
                ByteBuffer buffer = mapped.buffer();
                buffer.position(sectionBlasCount * VkAccelerationStructureInstanceKHR.SIZEOF);
                mapped.flushOnlyRange((long)sectionBlasCount * VkAccelerationStructureInstanceKHR.SIZEOF,
                    (long)dynamicSlotCapacity * VkAccelerationStructureInstanceKHR.SIZEOF);
                DynamicTlasInstanceWriter.write(buffer, dynamicSlotCapacity, instancesBySlot, addresses,
                    materialBases, fallbackBlasDeviceAddress,
                    (float)geometry.originX, (float)geometry.originY, (float)geometry.originZ);
            }
        }
        ByteBuffer metadataCandidate = ByteBuffer.wrap(this.dynamicMotionMetadataScratch)
            .order(java.nio.ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.writeMotionMetadata(metadataCandidate, dynamicSlotCapacity,
            instancesBySlot, historyResetIdentities,
            (float)geometry.originX, (float)geometry.originY, (float)geometry.originZ);
        int metadataByteCount = dynamicSlotCapacity
            * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT;
        boolean metadataChanged = !this.dynamicMotionMetadataInitialized
            || !java.util.Arrays.equals(this.dynamicMotionMetadataScratch, 0, metadataByteCount,
                this.uploadedDynamicMotionMetadata, 0, metadataByteCount);
        if (metadataChanged) {
            this.dynamicMetadataUploadCount++;
            try (NativeBuffer.Mapped mapped = dynamicMotionMetadataBuffer.map()) {
                mapped.flushOnlyRange(0, metadataByteCount);
                mapped.buffer().put(this.dynamicMotionMetadataScratch, 0, metadataByteCount);
            }
            System.arraycopy(this.dynamicMotionMetadataScratch, 0,
                this.uploadedDynamicMotionMetadata, 0, metadataByteCount);
            this.dynamicMotionMetadataInitialized = true;
        }
        // Publish exactly the block entities whose geometry reached the TLAS this frame.
        Set<Long> represented = new HashSet<>();
        for (Long identity : materialBases.keySet()) {
            if ((identity >>> 48) == 0x8001L) {
                represented.add(identity);
            }
        }
        this.representedBlockEntities = Set.copyOf(represented);
        return tlasChanged;
    }

    private boolean hasChangedDynamicMaterials(DynamicEntityGeometry.Frame frame) {
        Set<Long> changedGeometry = frame.dynamicFrame().changedGeometry();
        for (DynamicEntityGeometry.Instance instance : frame.instances()) {
            long id = instance.snapshot().identity();
            if (!instance.snapshot().active() || instance.snapshot().slot() >= dynamicSlotCapacity) {
                continue;
            }
            float[] materialData;
            DynamicCachedBlas cached;
            switch (instance.family()) {
                case PLAYER_BODY, FIRST_PERSON_BODY -> {
                    PlayerModelGeometryAdapter.Mesh mesh = frame.playerMeshes().get(id);
                    cached = playerModelBlas.get(id);
                    materialData = mesh != null && mesh.triangleCount() <= DYNAMIC_PLAYER_MATERIAL_TRIANGLES
                        ? mesh.materialData() : null;
                }
                case LIVING_BODY, PARTICLE -> {
                    PlayerModelGeometryAdapter.Mesh mesh = frame.livingMeshes().get(id);
                    cached = livingModelBlas.get(id);
                    materialData = mesh != null && mesh.triangleCount() <= DYNAMIC_PLAYER_MATERIAL_TRIANGLES
                        ? mesh.materialData() : null;
                }
                case BLOCK_ENTITY_MODEL -> {
                    PlayerModelGeometryAdapter.Mesh mesh = frame.blockEntityMeshes().get(id);
                    cached = blockEntityModelBlas.get(id);
                    materialData = mesh != null && mesh.triangleCount() <= DYNAMIC_PLAYER_MATERIAL_TRIANGLES
                        ? mesh.materialData() : null;
                }
                case ITEM_PLACEHOLDER, FIRST_PERSON_ITEM -> {
                    ItemModelGeometryAdapter.Mesh mesh = frame.itemMeshes().get(id);
                    cached = itemModelBlas.get(id);
                    materialData = mesh != null && mesh.triangleCount() <= DYNAMIC_ITEM_MATERIAL_TRIANGLES
                        ? mesh.materialData() : null;
                }
                default -> throw new IllegalStateException(
                    "Unhandled dynamic instance family: " + instance.family());
            }
            // Inactive GPU entries are released before the registry retires their slots.
            // Reactivation with the same GeometryKey is absent from changedGeometry, but
            // the replacement cache still needs an initialized material snapshot.
            if (materialData != null && (cached == null || cached.uploadedMaterials == null
                || (changedGeometry.contains(id)
                    && !java.util.Arrays.equals(cached.uploadedMaterials, materialData)))) {
                return true;
            }
        }
        return false;
    }
    @Override
    public void close() {
        this.dynamicBlasCache.close();
    }

    private static boolean shouldReplaceDynamicBlas(DynamicCachedBlas cached, long frame) {
        return cached.lastBlasReplacementFrame < 0L
            || frame - cached.lastBlasReplacementFrame >= DYNAMIC_BLAS_UPDATE_INTERVAL_FRAMES;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) return;
        if (failure instanceof RuntimeException exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("Vulkan resource teardown failed", failure);
    }
}
