package com.rtest.client;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import com.mojang.logging.LogUtils;
import com.rtest.RTest;
import com.rtest.mixin.GpuDeviceAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = RTest.MOD_ID, value = Dist.CLIENT)
public final class RayTracingProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    // A selected-cut change changes active terrain geometry and can trigger TLAS/material updates.
    // Readiness can arrive in bursts, so coalesce those changes at this interval.
    private static final long TERRAIN_LOD_SELECTION_INTERVAL_NANOS = 1_000_000_000L;
    private static final long TERRAIN_DIRTY_IDLE_FLUSH_NANOS = 250_000_000L;
    private static final long TERRAIN_DIRTY_MAX_BATCH_AGE_NANOS = 2_000_000_000L;
    private static final long RT_RETRY_BASE_DELAY_NANOS = 1_000_000_000L;
    private static final int MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY = 50_000_000;
    private static final int MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL =
        MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY;
    private static final DynamicSnapshotLogThrottle DYNAMIC_SNAPSHOT_LOG_THROTTLE =
        new DynamicSnapshotLogThrottle(5_000_000_000L);
    private static boolean completed;
    private static boolean smokeTestStarted;
    private static boolean smokeTestRequested;
    private static boolean sceneDirty;
    private static Frustum frameCullFrustum;
    private static boolean fullCaptureRequested;
    private static boolean partialCapture;
    // The first complete snapshot is an activation transaction. Chunk streaming and block
    // callbacks may keep advancing sceneGeneration for seconds while the CPU fallback capture
    // runs; rejecting that finished snapshot would starve RT forever. Keep those changes queued,
    // publish one coherent snapshot, and resume incremental work after its first presentation.
    private static boolean activationFreeze;
    private static long sceneGeneration;
    private static long captureGeneration;
    private static long fullCaptureCount;
    private static long partialCaptureCount;
    private static volatile long resourceGeneration;
    private static long capturedResourceGeneration;
    private static RayTracingScene.SceneGeometry smokeGeometry;
    private static RayTracingScene.SceneGeometry activeGeometry;
    private static RayTracingTerrainLodScheduler<RayTracingTerrainLod.Node> terrainLodScheduler;
    // The store retains only immutable, opaque proxies; it never owns a ClientLevel or chunk
    // object. 4096 nodes is bounded and covers a useful walked/flew-through horizon without
    // turning the experimental path into an unbounded world cache.
    private static final RayTracingTerrainProxyStore terrainProxyStore =
        new RayTracingTerrainProxyStore(4096);
    private static RayTracingTerrainProxyStore.WorldIdentity terrainProxyIdentity;
    private static final Map<Long, RayTracingTerrainLod.Node> terrainLodResults = new HashMap<>();
    // Scheduler keys are deliberately opaque longs. Keep the lossless NodeKey mapping beside
    // them instead of packing level and signed coordinates into a BlockPos (which would alias
    // level 1/2 nodes and negative coordinates).
    private static final Map<Long, RayTracingTerrainLod.NodeKey> terrainLodKeysById = new HashMap<>();
    private static final Map<RayTracingTerrainLod.NodeKey, Long> terrainLodIdsByKey = new HashMap<>();
    private static long nextTerrainLodId = 1L;
    private static final Set<RayTracingTerrainLod.NodeKey> terrainLodHierarchyKeys = new LinkedHashSet<>();
    private static final Map<Long, List<RayTracingTerrainLod.SectionInput>> terrainLodInputs = new LinkedHashMap<>();
    private static final Map<Long, Long> terrainLodFingerprints = new HashMap<>();
    private static final Set<Long> terrainLodBlocked = new LinkedHashSet<>();
    private static final Set<Long> terrainLodPending = new LinkedHashSet<>();
    private static final Set<Long> terrainLodSelected = new LinkedHashSet<>();
    private static final Set<RayTracingTerrainLod.NodeKey> terrainLodSelectedKeys = new LinkedHashSet<>();
    private static final Map<RayTracingTerrainLodScheduler.Token, List<RayTracingTerrainLod.SectionInput>> terrainLodWorkerInputs = new ConcurrentHashMap<>();
    private static final Map<Long, RayTracingTerrainLodScheduler.Token> terrainLodWorkerTokens = new HashMap<>();
    private static Map<RayTracingTerrainLodScheduler.NodeKey, RayTracingTerrainLodScheduler.NodeVersion>
        terrainLodNodeVersions = Map.of();
    private static Map<Long, RayTracingTerrainLod.Node> terrainLodAvailableNodes = Map.of();
    private static RayTracingTerrainLod.Selection terrainLodSelection;
    private static boolean terrainLodSelectionDirty = true;
    private static boolean terrainLodCompositionPending;
    private static long terrainLodLastSelectionNanos;
    private static RayTracingScene.SceneGeometry terrainLodSourceGeometry;
    // LOD requests consume one immutable published snapshot. Pending chunk events advance
    // sceneGeneration before their replacement geometry exists and must not cancel these builds.
    private static long terrainLodSourceGeneration;
    private static long terrainLodConfigFingerprint = Long.MIN_VALUE;
    private static int terrainLodCameraChunkX;
    private static int terrainLodCameraChunkZ;
    private static boolean terrainLodSelectionValid;
    private static long terrainLodWindowGeneration;
    private static long terrainLodDiagnosticFrame;
    private static long terrainLodWarmupFrames;
    private static boolean terrainLodFarProxyActive;
    private static boolean terrainLodGpuTraversalActive;
    private static boolean terrainLodGpuCandidateLimitExceeded;
    private static long nextRtRetryNanos;
    private static int rtFailureCount;
    private static RayTracingScene.SceneGeometry.CaptureSession captureSession;
    /**
     * Geometry finalization is deliberately serialized. Capture still advances on the render
     * thread in small steps, while PBR packing, light-tree construction and dirty merges never do.
     */
    private static final ExecutorService GEOMETRY_MERGE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "rtest-geometry-merge");
        worker.setDaemon(true);
        return worker;
    });
    // LOD composition and its emissive-light tree can be CPU-heavy. Keep it off the Render
    // thread and separate from capture finalization so scene merging cannot starve either queue.
    private static final ExecutorService TERRAIN_COMPOSITION_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "rtest-terrain-compose");
        worker.setDaemon(true);
        return worker;
    });
    private static CompletableFuture<DirtyGeometryMerge> pendingGeometryMerge;
    private static CompletableFuture<FullGeometryBuild> pendingFullGeometryBuild;
    private static CompletableFuture<TerrainGeometryComposition> pendingTerrainGeometryComposition;
    private static long terrainLodChangeSerial;
    private static RayTracingPbrMaterials pbrMaterials;
    private static final DynamicEntityGeometry dynamicEntities = new DynamicEntityGeometry();
    private static DynamicEntityGeometry.Frame lastEntityFrame;
    private static final Set<Long> pendingDirtySections = new LinkedHashSet<>();
    private static long firstPendingDirtyNanos;
    private static long lastPendingDirtyNanos;
    private static final Set<Long> pendingCaptureSections = new LinkedHashSet<>();
    private static Set<Long> activeDirtySections;
    private static int capturedWindowChunkX;
    private static int capturedWindowChunkZ;
    // Last camera window reconciled into the pending delta. The published window remains the
    // base until a merge commits, so comparing only against capturedWindowChunkX/Z would rebuild
    // the same large window delta on every frame while its worker is running.
    private static int queuedWindowChunkX;
    private static int queuedWindowChunkZ;
    private static boolean queuedWindowValid;
    // Geometry sections omit valid-but-empty/non-renderable sections. Keep the published window
    // membership separately so those sections are not recaptured forever on every frame.
    private static final Set<Long> capturedWindowOrigins = new LinkedHashSet<>();
    private static ClientLevel capturedLevel;
    private static int activeWindowChunkX;
    private static int activeWindowChunkZ;
    private static boolean capturedWindowValid;
    private static boolean renderFramePrepared;
    private static boolean dynamicFramePrepared;
    private static long dynamicCollectionFrames;
    // Latched before deferred model preparation. Capture may run while vanilla still owns the
    // current frame, so model redirects must know whether this exact world pass will be cancelled.
    private static boolean vanillaWorldReplacementRequested;
    // Keep incremental CPU fallback capture below the frame-time spike observed at higher budgets.
    private static final int SECTIONS_PER_FRAME = 2;
    // Initial capture has no RT scene to render yet, so spend a moderate amount more frame time
    // to shorten the blocking activation wait. Once a scene exists, incremental work uses the
    // lower budget above to protect steady-state frame pacing.
    private static final int INITIAL_CAPTURE_SECTIONS_PER_FRAME = 4;
    // Bound one transaction as well as one frame. New dirty events remain queued instead of
    // invalidating unrelated work already captured for this transaction.
    // Dirty publication updates acceleration structures and affected material ranges.
    // Coalesce small section changes to avoid repeatedly publishing during camera streaming.
    private static final int SECTIONS_PER_TRANSACTION = 512;
    private record DirtyGeometryMerge(
        RayTracingScene.SceneGeometry geometry,
        Set<Long> windowOrigins,
        Set<Long> dirtySections,
        int windowChunkX,
        int windowChunkZ,
        long generation,
        ClientLevel level
    ) {
    }

    private record FullGeometryBuild(
        RayTracingScene.SceneGeometry geometry,
        Set<Long> windowOrigins,
        int windowChunkX,
        int windowChunkZ,
        long generation,
        ClientLevel level
    ) {
    }

    private record TerrainGeometryComposition(
        RayTracingScene.SceneGeometry geometry,
        RayTracingScene.SceneGeometry sourceGeometry,
        long changeSerial,
        long configFingerprint,
        boolean gpuTraversalEnabled,
        boolean farProxyActive,
        long durationNanos
    ) {
    }

    private RayTracingProbe() {
    }

    public static boolean captureNativePlayers() {
        // The vanilla submit path is the source of truth for model vertices. It can run before
        // the RT request flag is consumed, so capture must not depend on smoke-test state. The
        // dynamic frame collector remains the consumer/owner of these queues.
        return RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()
            && Minecraft.getInstance().level != null;
    }

    /**
     * Returns whether the deferred vanilla feature queue must be prepared for RT model capture.
     * ModelFeatureRenderer executes model vertices during FeatureRenderDispatcher.prepareFrame,
     * not during submitEntities. The LevelRenderer mixin uses this to run that preparation once
     * before it cancels the native world pass.
     */
    public static boolean shouldPrepareDeferredEntityCapture() {
        return captureNativePlayers() && (smokeTestRequested || smokeTestStarted);
    }

    /** Captures vanilla's already-extracted particle quads without replaying its raster renderer. */
    public static void captureParticles(net.minecraft.client.renderer.state.level.ParticlesRenderState state) {
        if ((smokeTestRequested || smokeTestStarted) && state != null) {
            ParticleGeometryAdapter.capture(state);
        }
    }

    /** Supplies the live LabPBR sampler to dynamic entity and block-entity captures. */
    public static RayTracingPbrSampler pbrSampler() {
        return pbrMaterials;
    }

    /** Vanilla hand features remain enabled unless this frame actually presented RT output. */
    public static boolean shouldSuppressVanillaFirstPersonModel() {
        return VanillaRenderController.INSTANCE.wasRtPresentedThisFrame();
    }

    /** Captures a hand item only while F8 is queued or active; vanilla mode owns it otherwise. */
    public static boolean shouldCaptureFirstPersonItem() {
        return smokeTestRequested || smokeTestStarted;
    }

    /** Returns the current LevelRenderer decision for model capture redirects. */
    public static boolean shouldSuppressVanillaWorldModels() {
        return vanillaWorldReplacementRequested;
    }

    /**
     * Ends the capture window after deferred model preparation has consumed SubmitNodeStorage.
     * Clearing it at submitFeatures() is too early: ModelFeatureRenderer prepares model nodes
     * only after that callback returns.
     */
    public static void endDeferredEntityCapture() {
        PlayerModelGeometryAdapter.endWorldDraw();
        ItemModelGeometryAdapter.endWorldDraw();
        LivingEntityGeometryAdapter.endWorldDraw();
        BlockEntityModelGeometryAdapter.endWorldDraw();
    }

    /** Captures render failures at the cancellation seam without interrupting vanilla rendering. */
    public static void abortLevelRenderPreparation(Throwable throwable) {
        LOGGER.error("RTest kept vanilla LevelRenderer because RT preparation failed", throwable);
        try {
            stopSmokeTestResources();
        } catch (Throwable cleanupFailure) {
            // Cleanup is best effort here: the original preparation exception must not turn the
            // optional RT hook into a vanilla render failure.
            throwable.addSuppressed(cleanupFailure);
            LOGGER.error("RTest could not fully clean up after RT preparation failure", cleanupFailure);
        }
    }

    /** Called by the reload listener; only a generation marker crosses thread boundaries. */
    public static void markResourcesReloaded() {
        resourceGeneration++;
    }

    public static void beginRenderFrame() {
        renderFramePrepared = false;
        dynamicFramePrepared = false;
        vanillaWorldReplacementRequested = false;
        frameCullFrustum = null;
        VanillaRenderController.INSTANCE.beginFrame();
    }

    public static void setFrameCullFrustum(Frustum frustum) {
        frameCullFrustum = frustum;
    }

    public static boolean isRtFrameReady() {
        if (!smokeTestStarted || smokeGeometry == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (minecraft.level == null || device == null
            || !(((GpuDeviceAccessor) device).rtest$getBackend() instanceof VulkanDevice vulkanDevice)) {
            return false;
        }
        try {
            return RayTracingSmokeTest.hasPresentedFrameFor(
                vulkanDevice,
                minecraft.gameRenderer.mainRenderTarget(),
                minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS),
                activeGeometry == null ? smokeGeometry : activeGeometry,
                terrainLodGpuTraversalActive);
        } catch (RuntimeException exception) {
            // Resource reload/resize can invalidate a handle between the guard and the render seam.
            // Keeping vanilla is safer than cancelling on an unverified presentation.
            return false;
        }
    }

    /** Decides cancellation only from the previous completed presentation, never from capture state. */
    public static boolean shouldCancelVanillaLevelRenderer() {
        // The RT image replaces the complete LevelRenderer output. When dynamic capture is
        // disabled there is no entity/block-entity raster overlay after that replacement, so
        // keeping vanilla world rendering alive is the only correct fallback. Do not let the
        // static terrain RT path make entities disappear behind an incomplete frame.
        if (!RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
            vanillaWorldReplacementRequested = false;
            return false;
        }
        // There is no per-object raster replay after the whole LevelRenderer is cancelled.
        // Never hide vanilla entities that failed dynamic admission: on Windows this is an
        // especially important safety path because a driver/backend-specific model submission
        // can fail without invalidating the rest of the RT scene.
        if (lastEntityFrame == null || lastEntityFrame.admissionFailures() != 0) {
            vanillaWorldReplacementRequested = false;
            return false;
        }
        // Keep vanilla visible while a section/window update is in flight. The previous RT
        // image is safe to reuse only until the scene generation changes; cancelling vanilla
        // during the replacement window can expose an incomplete TLAS/FSR image as a missing
        // chunk, which is particularly easy to reproduce on the Windows Vulkan path.
        if (sceneDirty || fullCaptureRequested || pendingFullGeometryBuild != null
                || pendingGeometryMerge != null || !pendingDirtySections.isEmpty()) {
            vanillaWorldReplacementRequested = false;
            return false;
        }
        // Once a complete RT image exists, the whole level render is replaced only when every
        // visible dynamic object has a valid RT representation.
        vanillaWorldReplacementRequested = VanillaRenderController.INSTANCE
            .shouldCancelLevelRenderer(isRtFrameReady());
        return vanillaWorldReplacementRequested;
    }

    public static void markVanillaWorldSkipped() {
        VanillaRenderController.INSTANCE.markWorldSkipped();
    }

    @SubscribeEvent
    public static void onRenderLevelAfterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        try {
            prepareLevelRender();
        } catch (Throwable throwable) {
            // The event seam is also inside LevelRenderer.render; an exception here must not
            // escape before the mixin gets a chance to preserve vanilla rendering.
            abortLevelRenderPreparation(throwable);
        }
    }

    /** Prepares the RT scene after feature submission, before level-render cancellation. */
    public static void prepareLevelRender() {
        if (!smokeTestRequested && !smokeTestStarted) {
            return;
        }

        // The current world replacement has no post-RT vanilla entity replay. Allowing this
        // legacy diagnostic option to remain false leaves the activation state alive while the
        // pre-hand RT seam returns forever, which looks like an intermittent F8 failure. Entity
        // shadow denoising is controlled independently by the ray payload/composite mask, so the
        // dynamic geometry path must stay enabled whenever RT is requested.
        ensureDynamicGeometryPath();

        Minecraft minecraft = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (minecraft.level == null || device == null
            || !(((GpuDeviceAccessor) device).rtest$getBackend() instanceof VulkanDevice vulkanDevice)) {
            return;
        }
        if (renderFramePrepared) {
            return;
        }
        renderFramePrepared = true;
        if (smokeTestStarted && capturedResourceGeneration != resourceGeneration) {
            // Resource reload may replace model/atlas data while keeping the same Identifier.
            // Retire the PBR cache and Vulkan descriptors on this render thread, then let the
            // existing request path rebuild a fresh snapshot on the next world frame.
            stopSmokeTestResources();
            smokeTestRequested = true;
            return;
        }
        if (smokeTestStarted && capturedLevel != null && capturedLevel != minecraft.level) {
            // Do not carry a previous world's CPU snapshots, PBR NativeImages, BLAS cache or
            // command encoder into the next ClientLevel. The unload event normally handles this;
            // this identity check is the fallback for reload paths that skip that event.
            stopSmokeTestResources();
            return;
        }

        var camera = minecraft.gameRenderer.mainCamera();
        int renderDistanceChunks = Math.max(2, minecraft.options.getEffectiveRenderDistance());
        if (!smokeTestStarted) {
            smokeTestRequested = false;
            smokeTestStarted = true;
            activationFreeze = true;
            sceneDirty = true;
            fullCaptureRequested = true;
            sceneGeneration++;
            fullCaptureCount = 0L;
            partialCaptureCount = 0L;
            capturedWindowValid = false;
            queuedWindowValid = false;
            pendingDirtySections.clear();
            firstPendingDirtyNanos = 0L;
            lastPendingDirtyNanos = 0L;
            pendingCaptureSections.clear();
            capturedLevel = minecraft.level;
            capturedResourceGeneration = resourceGeneration;
            capturedWindowOrigins.clear();
            pbrMaterials = new RayTracingPbrMaterials(minecraft.getResourceManager());
            ItemModelGeometryAdapter.setPbrSampler(pbrMaterials);
            BlockEntityModelGeometryAdapter.setPbrSampler(pbrMaterials);
            LOGGER.info("RTest queued incremental Vulkan section capture with activation freeze");
        }

        boolean renderDistanceChanged = (captureSession != null
                && captureSession.renderDistanceChunks() != renderDistanceChunks)
            || (captureSession == null && smokeGeometry != null
                && smokeGeometry.renderDistanceChunks != renderDistanceChunks);
        if (renderDistanceChanged) {
            fullCaptureRequested = true;
            sceneDirty = true;
            sceneGeneration++;
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            activeDirtySections = null;
            partialCapture = false;
            pendingDirtySections.clear();
            firstPendingDirtyNanos = 0L;
            lastPendingDirtyNanos = 0L;
            pendingCaptureSections.clear();
            capturedWindowOrigins.clear();
            capturedWindowValid = false;
            queuedWindowValid = false;
        }

        pollFullGeometryBuild(renderDistanceChunks);
        pollDirtyGeometryMerge(renderDistanceChunks);
        if (!smokeTestStarted) {
            return;
        }
        // Build/select the coarse terrain even while the first complete snapshot is frozen.
        // RT presentation may be gated on scene size, so waiting for a successful RT frame
        // before advancing LOD would deadlock oversized scenes on vanilla forever.
        if (smokeGeometry != null) {
            try {
                updateTerrainLod(camera);
            } catch (Throwable throwable) {
                // LOD is an optional reduction layer. A bad coarse node must not be allowed to
                // escape through the level-render seam and tear down an otherwise valid RT scene.
                disableTerrainLodAfterFailure(throwable);
            }
        }
        // Once the frozen full snapshot is published, do not start a dirty merge or move the
        // capture window in the same frame. The before-hand seam must get one stable chance to
        // build its BLAS/TLAS and present it. LOD preparation above only reads the immutable
        // published snapshot; all capture events remain queued until activation completes.
        if (RtActivationFreeze.holdIncrementalUpdates(activationFreeze, smokeGeometry != null)) {
            return;
        }
        // The worker owns the completed CaptureSession until the immutable merge has finished.
        // Do not start another capture (or mutate the shared PBR sampler) while it is reading it.
        if (pendingFullGeometryBuild != null || pendingGeometryMerge != null) {
            return;
        }

        if (smokeGeometry != null && captureSession == null && !fullCaptureRequested) {
            queueCameraWindowUpdate(minecraft.level, camera, renderDistanceChunks);
        }

        if (captureSession == null) {
            long dirtyNow = System.nanoTime();
            boolean dirtyBatchReady = pendingDirtySections.size() >= SECTIONS_PER_TRANSACTION
                || firstPendingDirtyNanos == 0L
                || dirtyNow - firstPendingDirtyNanos >= TERRAIN_DIRTY_MAX_BATCH_AGE_NANOS
                || dirtyNow - lastPendingDirtyNanos >= TERRAIN_DIRTY_IDLE_FLUSH_NANOS;
            RtCaptureSchedule captureSchedule = RtCaptureSchedule.select(
                smokeGeometry != null, fullCaptureRequested,
                !pendingDirtySections.isEmpty(), dirtyBatchReady);
            if (captureSchedule == RtCaptureSchedule.DIRTY) {
                activeDirtySections = new LinkedHashSet<>();
                for (long origin : pendingDirtySections) {
                    activeDirtySections.add(origin);
                    if (activeDirtySections.size() >= SECTIONS_PER_TRANSACTION) {
                        break;
                    }
                }
                pendingDirtySections.removeAll(activeDirtySections);
                if (pendingDirtySections.isEmpty()) {
                    firstPendingDirtyNanos = 0L;
                    lastPendingDirtyNanos = 0L;
                }
                Set<Long> activeCaptureSections = new LinkedHashSet<>();
                for (long origin : activeDirtySections) {
                    if (pendingCaptureSections.contains(origin)) {
                        activeCaptureSections.add(origin);
                    }
                }
                pendingCaptureSections.removeAll(activeDirtySections);
                activeWindowChunkX = cameraChunkX(camera);
                activeWindowChunkZ = cameraChunkZ(camera);
                captureGeneration = sceneGeneration;
                partialCapture = true;
                List<BlockPos> captureOrigins = activeCaptureSections.stream()
                    .map(BlockPos::of)
                    .toList();
                captureSession = RayTracingScene.SceneGeometry.CaptureSession.beginShared(
                    minecraft.level,
                    camera,
                    minecraft.getModelManager().getBlockStateModelSet(),
                    minecraft.getModelManager().getFluidStateModelSet(),
                    minecraft.getBlockColors(),
                    renderDistanceChunks,
                    captureOrigins,
                    RayTracingScene.SceneGeometry.requestedSectionOrigins(
                        minecraft.level, camera, renderDistanceChunks),
                    pbrMaterials
                );
                LOGGER.info("RTest queued {} dirty-section updates ({} sections to capture)",
                    activeDirtySections.size(), captureOrigins.size());
            } else if (captureSchedule == RtCaptureSchedule.FULL) {
                captureGeneration = sceneGeneration;
                partialCapture = false;
                // Consume this explicit full request. Ordinary chunk/block notifications stay
                // queued for incremental capture instead of requesting another whole-scene pass.
                fullCaptureRequested = false;
                activeWindowChunkX = cameraChunkX(camera);
                activeWindowChunkZ = cameraChunkZ(camera);
                captureSession = RayTracingScene.SceneGeometry.CaptureSession.beginShared(
                    minecraft.level,
                    camera,
                    minecraft.getModelManager().getBlockStateModelSet(),
                    minecraft.getModelManager().getFluidStateModelSet(),
                    minecraft.getBlockColors(),
                    renderDistanceChunks,
                    RayTracingScene.SceneGeometry.loadedSectionOrigins(
                        minecraft.level, camera, renderDistanceChunks),
                    RayTracingScene.SceneGeometry.requestedSectionOrigins(
                        minecraft.level, camera, renderDistanceChunks),
                    pbrMaterials
                );
            }
        }

        if (captureSession != null) {
            try {
            int sectionBudget = smokeGeometry == null
                ? INITIAL_CAPTURE_SECTIONS_PER_FRAME
                : SECTIONS_PER_FRAME;
            boolean complete = captureSession.step(sectionBudget);
            if (complete) {
                RayTracingScene.SceneGeometry.CaptureSession finished = captureSession;
                boolean completedPartial = partialCapture;
                // A partial capture is coherent for its starting window even if the camera has
                // moved meanwhile. Publish this batch, then reconcile the newer window from the
                // committed base. Discarding each in-flight batch during movement starves both
                // scene updates and terrain LOD builds.
                if (completedPartial && smokeGeometry != null && activeDirtySections != null) {
                    // CaptureSession is complete and will no longer be mutated. Keep it alive on
                    // the single geometry worker until buildPartial() and replaceSections() have
                    // produced the immutable replacement. This keeps LightTree construction off
                    // the render thread as well as section-map publication.
                    Set<Long> dirtySections = Set.copyOf(activeDirtySections);
                    pendingGeometryMerge = submitDirtyGeometryMerge(
                        finished,
                        smokeGeometry,
                        dirtySections,
                        camera.position(),
                        sectionOriginKeys(finished.windowOrigins()),
                        activeWindowChunkX,
                        activeWindowChunkZ,
                        captureGeneration,
                        capturedLevel
                    );
                } else if (!completedPartial) {
                    Set<Long> windowOrigins = sectionOriginKeys(finished.windowOrigins());
                    pendingFullGeometryBuild = submitFullGeometryBuild(
                        finished,
                        windowOrigins,
                        activeWindowChunkX,
                        activeWindowChunkZ,
                        captureGeneration,
                        capturedLevel
                    );
                    LOGGER.info("RTest queued full scene finalization for the geometry worker");
                } else {
                    finished.close();
                }
                captureSession = null;
                activeDirtySections = null;
                partialCapture = false;
                if (completedPartial) {
                    // The old smokeGeometry remains the render input until the worker result
                    // is observed at the start of a later render callback.
                    sceneDirty = true;
                    LOGGER.info("RTest queued dirty-section CPU merge for the geometry worker");
                } else {
                    // Keep notifications and explicit invalidations while the worker finalizes.
                    // Publication still applies the full-snapshot compatibility policy below.
                    sceneDirty = fullCaptureRequested
                        || captureGeneration != sceneGeneration
                        || !pendingDirtySections.isEmpty();
                    // The immutable full snapshot is published by pollFullGeometryBuild once
                    // its worker future completes. Keep the previous scene active meanwhile.
                }
                if (minecraft.player != null) {
                    minecraft.gui.hud.setOverlayMessage(
                        Component.translatable("overlay.rtest.scene.active"), false);
                }
            } else if (smokeGeometry == null) {
                if (minecraft.player != null) {
                    minecraft.gui.hud.setOverlayMessage(
                        Component.translatable("overlay.rtest.capturing",
                            captureSession.processedSections(), captureSession.totalSections()), false);
                }
                return;
            }
            } catch (Throwable throwable) {
                LOGGER.error("RTest incremental scene capture failed", throwable);
                stopSmokeTestResources();
                if (minecraft.player != null) {
                    minecraft.gui.hud.setOverlayMessage(
                        Component.translatable("overlay.rtest.smokeTest.failed"), false);
                }
                return;
            }
        }

    }

    /** Executes RT after the current hand mesh was captured and before screen effects/UI. */
    public static void renderRtAfterHandCapture() {
        // GUI widgets are drawn after this seam. Keep RT active underneath them; the Vulkan
        // command-thread guard separately protects asynchronous GUI glyph uploads.
        // Without dynamic capture the RT result cannot be composited with vanilla entities and
        // block entities. Leave the complete vanilla world visible until a real overlay path is
        // available instead of overwriting it with terrain-only RT output.
        if (!RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()
                || !smokeTestStarted || smokeGeometry == null) {
            return;
        }
        // RT also runs during the bootstrap frame before wasWorldSkippedThisFrame(); subsequent
        // frames may cancel vanilla once the previous RT presentation has been verified.

        Minecraft minecraft = Minecraft.getInstance();
        GpuDevice device = RenderSystem.tryGetDevice();
        if (minecraft.level == null || device == null
            || !(((GpuDeviceAccessor) device).rtest$getBackend() instanceof VulkanDevice vulkanDevice)) {
            return;
        }

        Camera camera = minecraft.gameRenderer.mainCamera();
        var target = minecraft.gameRenderer.mainRenderTarget();
        var blockAtlas = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        // Opening a GUI pauses the world. Do not record a new RT dispatch in this frame:
        // Minecraft may submit GUI uploads through the same Vulkan queue. Replaying the already
        // completed RT image through the shared frame encoder preserves the RT world underneath
        // the GUI without introducing another AS/FSR submission at the pause boundary.
        if (minecraft.isPaused()) {
            boolean replayed = RayTracingSmokeTest.replayLastFrame(vulkanDevice, target, blockAtlas,
                activeGeometry == null ? smokeGeometry : activeGeometry,
                terrainLodGpuTraversalActive);
            VanillaRenderController.INSTANCE.markRtResult(replayed);
            return;
        }
        finishDeferredEntityCapture();
        RayTracingScene.SceneGeometry renderGeometry = activeGeometry == null ? smokeGeometry : activeGeometry;
        if (renderGeometry.triangleCount() > MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY) {
            // Never submit an unculled oversized snapshot, even if LOD was disabled after a
            // worker/publication failure. Keep vanilla visible instead of allocating multi-GB
            // material and BLAS tables as a fallback.
            if (++terrainLodWarmupFrames == 1L) {
                boolean lodEnabled = RayTracingClientConfig.INSTANCE.terrainLodEnabled.get();
                LOGGER.warn("RTest RT presentation is waiting for terrain below the startup limit "
                    + "(triangles={}, limit={}, lodEnabled={})",
                    renderGeometry.triangleCount(), MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY, lodEnabled);
                if (minecraft.player != null) {
                    minecraft.gui.hud.setOverlayMessage(Component.literal(lodEnabled
                        ? "RTest: preparing terrain LOD for RT"
                        : "RTest: enable Terrain LOD to render this scene"), false);
                }
            } else if (terrainLodWarmupFrames % 120L == 0L) {
                LOGGER.info("RTest waiting for terrain geometry below the RT startup limit (triangles={}, limit={}, lodEnabled={})",
                    renderGeometry.triangleCount(), MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY,
                    RayTracingClientConfig.INSTANCE.terrainLodEnabled.get());
            }
            VanillaRenderController.INSTANCE.markRtResult(false);
            return;
        }
        terrainLodWarmupFrames = 0L;
        if (System.nanoTime() < nextRtRetryNanos) {
            VanillaRenderController.INSTANCE.markRtResult(false);
            return;
        }
        boolean rtPresented = RayTracingSmokeTest.run(
            vulkanDevice,
            target,
            renderGeometry,
            minecraft.level,
            camera,
            blockAtlas,
            minecraft.getResourceManager(),
            pbrMaterials,
            lastEntityFrame,
            terrainLodGpuTraversalActive
        );
        // Retry from native sections only when their size is already below the startup limit.
        // Oversized RD32 scenes stay on vanilla if a coarse publication fails.
        if (!rtPresented && renderGeometry != smokeGeometry && smokeGeometry != null) {
            if (smokeGeometry.triangleCount() <= MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY) {
                rtPresented = RayTracingSmokeTest.run(
                    vulkanDevice,
                    target,
                    smokeGeometry,
                    minecraft.level,
                    camera,
                    blockAtlas,
                    minecraft.getResourceManager(),
                    pbrMaterials,
                    lastEntityFrame,
                    false
                );
            }
        }
        VanillaRenderController.INSTANCE.markRtResult(rtPresented);
        if (rtPresented) {
            nextRtRetryNanos = 0L;
            rtFailureCount = 0;
            if (activationFreeze) {
                activationFreeze = false;
                sceneDirty = fullCaptureRequested
                    || captureGeneration != sceneGeneration
                    || !pendingDirtySections.isEmpty();
                LOGGER.info("RTest released activation freeze after the first presented RT frame; deferred scene updates will resume");
            }
            LOGGER.debug("RTest presented the complete world through Vulkan RT (dynamicEntities={})",
                lastEntityFrame == null ? 0 : lastEntityFrame.instances().size());
        } else {
            rtFailureCount = Math.min(rtFailureCount + 1, 6);
            long retryDelay = Math.min(30_000_000_000L,
                RT_RETRY_BASE_DELAY_NANOS << (rtFailureCount - 1));
            nextRtRetryNanos = System.nanoTime() + retryDelay;
            if (minecraft.player != null) {
                minecraft.gui.hud.setOverlayMessage(Component.literal("RTest: RT failed; retrying in "
                    + Math.max(1L, retryDelay / 1_000_000_000L) + "s"), false);
            }
        }
    }

    /** Consumes model vertices after FeatureRenderDispatcher has prepared the deferred queue. */
    public static void finishDeferredEntityCapture() {
        if (!captureNativePlayers()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        collectDynamicEntityFrame(
            minecraft,
            camera,
            Math.max(2, minecraft.options.getEffectiveRenderDistance()));
    }

    private static void collectDynamicEntityFrame(Minecraft minecraft, Camera camera, int renderDistanceChunks) {
        if (dynamicFramePrepared) {
            return;
        }
        dynamicFramePrepared = true;
        if (RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
            float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            long collectionStart = System.nanoTime();
            DynamicEntityGeometry.Frame captured =
                dynamicEntities.collect(minecraft.level, camera, renderDistanceChunks, partialTick,
                    frameCullFrustum);
            if (++dynamicCollectionFrames % 120 == 0) {
                LOGGER.info("RTest dynamic_collection frame={} collect_us={}", dynamicCollectionFrames,
                    (System.nanoTime() - collectionStart) / 1000);
            }
            // A visible entity with no captured geometry means the deferred capture missed this
            // frame. Do not turn that transient miss into a frame that masks every dynamic TLAS
            // slot; a genuinely empty world frame has no admission failures and is still published.
            if (DynamicEntityGeometry.isTransientCaptureMiss(captured, lastEntityFrame)) {
                return;
            }
            lastEntityFrame = captured;
            String dynamicSummary = "entities=" + lastEntityFrame.instances().size()
                + ", active=" + lastEntityFrame.dynamicFrame().activeCount()
                + ", playerMeshes=" + lastEntityFrame.playerMeshes().size()
                + ", playerSkins=" + lastEntityFrame.playerSkinTextures().size()
                + ", livingMeshes=" + lastEntityFrame.livingMeshes().size()
                + ", blockEntityMeshes=" + lastEntityFrame.blockEntityMeshes().size()
                + ", itemMeshes=" + lastEntityFrame.itemMeshes().size()
                + ", changed=" + lastEntityFrame.dynamicFrame().changedGeometry().size()
                + ", entityAdmissionFailures=" + lastEntityFrame.admissionFailures()
                // representedEntityIds() remains available for consumers that need the actual
                // identity set; the per-frame diagnostic only needs its allocation-free count.
                + ", entityRt=" + lastEntityFrame.representedEntityCount();
            if (DYNAMIC_SNAPSHOT_LOG_THROTTLE.shouldLog(dynamicSummary, System.nanoTime())) {
                LOGGER.info("RTest dynamic CPU snapshot: {}", dynamicSummary);
            }
        } else if (lastEntityFrame != null) {
            ItemModelGeometryAdapter.clear();
            LivingEntityGeometryAdapter.clear();
            ParticleGeometryAdapter.clear();
            BlockEntityModelGeometryAdapter.clear();
            dynamicEntities.clear();
            lastEntityFrame = dynamicEntities.collect(minecraft.level, camera, renderDistanceChunks, 0.0F,
                frameCullFrustum);
            DYNAMIC_SNAPSHOT_LOG_THROTTLE.reset();
        }
    }

    /**
     * Marks only the sections affected by one client chunk load/unload.
     *
     * <p>A chunk event changes the scene membership, but it does not require rebuilding every
     * section. Existing scene sections on the chunk boundary are included so vanilla face
     * culling is refreshed when a neighbor appears or disappears.</p>
     */
    public static void markChunkDirty(ClientLevel level, ChunkPos chunkPosition, boolean loaded) {
        CompiledSectionMeshCache.invalidateChunk(chunkPosition, level.getMinSectionY(), level.getMaxSectionY());
        for (ChunkPos affected : affectedChunks(chunkPosition)) {
            CompiledSectionMeshCache.invalidateChunk(affected, level.getMinSectionY(), level.getMaxSectionY());
        }

        Set<ChunkPos> affectedChunks = affectedChunks(chunkPosition);
        if (terrainProxyIdentity != null) {
            int maxLevel = Math.min(RayTracingTerrainLod.MAX_HIERARCHY_LEVEL,
                RayTracingClientConfig.INSTANCE.terrainLodMaxLevel.get());
            for (ChunkPos affected : affectedChunks) {
                int sx = affected.x();
                int sz = affected.z();
                for (int sectionY = level.getMinSectionY(); sectionY <= level.getMaxSectionY(); sectionY++) {
                    for (int lodLevel = 1; lodLevel <= maxLevel; lodLevel++) {
                        terrainProxyStore.invalidate(terrainProxyIdentity,
                            new RayTracingTerrainLod.NodeKey(lodLevel,
                                Math.floorDiv(sx, 1 << lodLevel),
                                Math.floorDiv(sectionY, 1 << lodLevel),
                                Math.floorDiv(sz, 1 << lodLevel)));
                    }
                }
            }
        }
        Set<Long> dirty = new LinkedHashSet<>();
        if (loaded && isWithinCaptureWindow(chunkPosition)) {
            LevelChunk chunk = level.getChunkSource().getChunk(
                chunkPosition.x(), chunkPosition.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (chunk != null) {
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    if (!sections[index].hasOnlyAir()) {
                        dirty.add(new BlockPos(
                            chunkPosition.x() * 16,
                            level.getSectionYFromSectionIndex(index) * 16,
                            chunkPosition.z() * 16).asLong());
                    }
                }
            }
        }
        if (smokeGeometry != null) {
            for (RayTracingScene.SceneGeometry.SectionGeometry section : smokeGeometry.sections) {
                ChunkPos sectionChunk = new ChunkPos(section.originX >> 4, section.originZ >> 4);
                if (affectedChunks.contains(sectionChunk)) {
                    dirty.add(new BlockPos(section.originX, section.originY, section.originZ).asLong());
                }
            }
        }
        if (smokeTestStarted) {
            dirty.removeIf(origin -> {
                BlockPos position = BlockPos.of(origin);
                return !isWithinCaptureWindow(new ChunkPos(position.getX() >> 4, position.getZ() >> 4));
            });
        }
        if (!smokeTestStarted || dirty.isEmpty()) {
            return;
        }
        pendingDirtySections.addAll(dirty);
        pendingCaptureSections.addAll(dirty);
        notePendingDirtyEvents();
        sceneGeneration++;
        sceneDirty = true;
    }

    private static Set<ChunkPos> affectedChunks(ChunkPos center) {
        return Set.of(
            center,
            new ChunkPos(center.x() - 1, center.z()),
            new ChunkPos(center.x() + 1, center.z()),
            new ChunkPos(center.x(), center.z() - 1),
            new ChunkPos(center.x(), center.z() + 1));
    }

    private static boolean isWithinCaptureWindow(ChunkPos chunkPosition) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance());
        int cameraChunkX = (int)Math.floor(camera.position().x / 16.0);
        int cameraChunkZ = (int)Math.floor(camera.position().z / 16.0);
        return Math.abs(chunkPosition.x() - cameraChunkX) <= radius
            && Math.abs(chunkPosition.z() - cameraChunkZ) <= radius;
    }

    /** Retained for callers that invalidate the entire client scene for a non-chunk event. */
    public static void markSceneDirty() {
        if (smokeTestStarted) {
            sceneGeneration++;
            sceneDirty = true;
            fullCaptureRequested = true;
            pendingDirtySections.clear();
            firstPendingDirtyNanos = 0L;
            lastPendingDirtyNanos = 0L;
            pendingCaptureSections.clear();
        }
    }

    public static void markSectionDirty(BlockPos position) {
        net.minecraft.core.SectionPos section = net.minecraft.core.SectionPos.of(position);
        boolean relevant = invalidateDirtySection(section);
        if (Math.floorMod(position.getX(), 16) == 0) {
            relevant |= invalidateDirtySection(section.offset(-1, 0, 0));
        } else if (Math.floorMod(position.getX(), 16) == 15) {
            relevant |= invalidateDirtySection(section.offset(1, 0, 0));
        }
        if (Math.floorMod(position.getY(), 16) == 0) {
            relevant |= invalidateDirtySection(section.offset(0, -1, 0));
        } else if (Math.floorMod(position.getY(), 16) == 15) {
            relevant |= invalidateDirtySection(section.offset(0, 1, 0));
        }
        if (Math.floorMod(position.getZ(), 16) == 0) {
            relevant |= invalidateDirtySection(section.offset(0, 0, -1));
        } else if (Math.floorMod(position.getZ(), 16) == 15) {
            relevant |= invalidateDirtySection(section.offset(0, 0, 1));
        }
        if (relevant) {
            sceneGeneration++;
            sceneDirty = true;
        }
    }

    private static boolean invalidateDirtySection(net.minecraft.core.SectionPos section) {
        BlockPos origin = section.origin();
        CompiledSectionMeshCache.invalidate(origin);
        if (terrainProxyIdentity != null) {
            int maxLevel = Math.min(RayTracingTerrainLod.MAX_HIERARCHY_LEVEL,
                RayTracingClientConfig.INSTANCE.terrainLodMaxLevel.get());
            // Proxy invalidation needs only the section origin; avoid retaining a synthetic
            // SectionGeometry just to reuse the array-based helper.
            int sx = Math.floorDiv(origin.getX(), RayTracingTerrainLod.SECTION_SIZE);
            int sy = Math.floorDiv(origin.getY(), RayTracingTerrainLod.SECTION_SIZE);
            int sz = Math.floorDiv(origin.getZ(), RayTracingTerrainLod.SECTION_SIZE);
            for (int level = 1; level <= maxLevel; level++) {
                terrainProxyStore.invalidate(terrainProxyIdentity, new RayTracingTerrainLod.NodeKey(level,
                    Math.floorDiv(sx, 1 << level), Math.floorDiv(sy, 1 << level),
                    Math.floorDiv(sz, 1 << level)));
            }
        }
        if (smokeTestStarted && isWithinCaptureWindow(
                new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4))) {
            pendingDirtySections.add(origin.asLong());
            pendingCaptureSections.add(origin.asLong());
            notePendingDirtyEvents();
            return true;
        }
        return false;
    }

    private static void queueCameraWindowUpdate(ClientLevel level, Camera camera, int renderDistanceChunks) {
        if (!capturedWindowValid) {
            return;
        }
        int currentChunkX = cameraChunkX(camera);
        int currentChunkZ = cameraChunkZ(camera);
        boolean windowMoved = !queuedWindowValid
            || currentChunkX != queuedWindowChunkX
            || currentChunkZ != queuedWindowChunkZ;
        if (!windowMoved) {
            // Window membership cannot have changed, so avoid rebuilding sets containing every
            // loaded section on every frame while a merge or dirty-section batch is pending.
            return;
        }
        // A dirty event can arrive for a section that is being evicted while the incremental
        // session is in flight. Reconcile pending updates too, otherwise that section could be
        // captured again and accidentally reintroduced outside the current view-distance window.
        Set<Long> currentOrigins = new LinkedHashSet<>(capturedWindowOrigins);
        Set<Long> desiredOrigins = new LinkedHashSet<>();
        for (BlockPos origin : RayTracingScene.SceneGeometry.requestedSectionOrigins(
                level, camera, renderDistanceChunks)) {
            desiredOrigins.add(origin.asLong());
        }
        queuedWindowChunkX = currentChunkX;
        queuedWindowChunkZ = currentChunkZ;
        queuedWindowValid = true;
        // Block callbacks can arrive for far-away loaded chunks. They do not belong to the
        // current RT scene and must not accumulate until the player returns there; the camera
        // delta will request the current contents when that window is entered.
        pendingDirtySections.retainAll(desiredOrigins);
        pendingCaptureSections.retainAll(desiredOrigins);
        if (pendingDirtySections.isEmpty()) {
            firstPendingDirtyNanos = 0L;
            lastPendingDirtyNanos = 0L;
        }
        SceneWindowDelta.Delta delta = SceneWindowDelta.between(currentOrigins, desiredOrigins);
        if (delta.removed().isEmpty() && delta.added().isEmpty()) {
            capturedWindowChunkX = currentChunkX;
            capturedWindowChunkZ = currentChunkZ;
            return;
        }
        pendingDirtySections.addAll(delta.removed());
        pendingDirtySections.addAll(delta.added());
        pendingCaptureSections.removeAll(delta.removed());
        Set<Long> renderableOrigins = sectionOriginKeys(
            RayTracingScene.SceneGeometry.loadedSectionOrigins(level, camera, renderDistanceChunks));
        for (long added : delta.added()) {
            if (renderableOrigins.contains(added)) {
                pendingCaptureSections.add(added);
            }
        }
        notePendingDirtyEvents();
        sceneGeneration++;
        sceneDirty = true;
        LOGGER.info("RTest queued camera-window update {} -> {}: remove {} sections, capture {} sections",
            capturedWindowChunkX + "," + capturedWindowChunkZ,
            currentChunkX + "," + currentChunkZ,
            delta.removed().size(), delta.added().size());
    }

    private static Set<Long> sectionOriginKeys(List<BlockPos> origins) {
        Set<Long> keys = new LinkedHashSet<>();
        for (BlockPos origin : origins) {
            keys.add(origin.asLong());
        }
        return keys;
    }

    private static void notePendingDirtyEvents() {
        long now = System.nanoTime();
        if (firstPendingDirtyNanos == 0L) {
            firstPendingDirtyNanos = now;
        }
        lastPendingDirtyNanos = now;
    }

    private static CompletableFuture<DirtyGeometryMerge> submitDirtyGeometryMerge(
        RayTracingScene.SceneGeometry.CaptureSession session,
        RayTracingScene.SceneGeometry previous,
        Set<Long> dirtySections,
        net.minecraft.world.phys.Vec3 cameraPosition,
        Set<Long> windowOrigins,
        int windowChunkX,
        int windowChunkZ,
        long generation,
        ClientLevel level
    ) {
        // The session is complete and no longer touched by the render thread. Keeping it owned
        // by this closure moves buildPartial(), PBR packing and LightTree construction off the
        // render thread without exposing mutable capture state to any other worker.
        return CompletableFuture.supplyAsync(() -> {
            try {
                RayTracingScene.SceneGeometry delta = session.buildPartial();
                RayTracingScene.SceneGeometry merged = previous.replaceSections(
                    dirtySections, delta, cameraPosition);
                return new DirtyGeometryMerge(
                    merged, Set.copyOf(windowOrigins), Set.copyOf(dirtySections),
                    windowChunkX, windowChunkZ, generation, level);
            } finally {
                session.close();
            }
        }, GEOMETRY_MERGE_EXECUTOR);
    }

    private static CompletableFuture<FullGeometryBuild> submitFullGeometryBuild(
        RayTracingScene.SceneGeometry.CaptureSession session,
        Set<Long> windowOrigins,
        int windowChunkX,
        int windowChunkZ,
        long generation,
        ClientLevel level
    ) {
        // PBR packing and RayTracingLightTree construction are all CPU work;
        // keep them on the serialized geometry worker and publish only the immutable result.
        return CompletableFuture.supplyAsync(() -> {
            try {
                RayTracingScene.SceneGeometry geometry = session.build();
                return new FullGeometryBuild(
                    geometry, Set.copyOf(windowOrigins), windowChunkX, windowChunkZ,
                    generation, level);
            } finally {
                session.close();
            }
        }, GEOMETRY_MERGE_EXECUTOR);
    }

    /** Publishes a completed full snapshot at the render boundary. */
    private static void pollFullGeometryBuild(int renderDistanceChunks) {
        CompletableFuture<FullGeometryBuild> future = pendingFullGeometryBuild;
        if (future == null || !future.isDone()) {
            return;
        }
        pendingFullGeometryBuild = null;
        final FullGeometryBuild completedBuild;
        try {
            completedBuild = future.join();
        } catch (CompletionException exception) {
            LOGGER.error("RTest full scene finalization failed", exception.getCause());
            stopSmokeTestResources();
            return;
        }
        boolean sameWorld = completedBuild.level() == capturedLevel
            && completedBuild.level() == Minecraft.getInstance().level;
        boolean currentSnapshot = RtActivationFreeze.mayPublishFullSnapshot(
            activationFreeze,
            sameWorld,
            completedBuild.geometry().renderDistanceChunks == renderDistanceChunks,
            completedBuild.generation() == sceneGeneration,
            fullCaptureRequested);
        if (!currentSnapshot) {
            fullCaptureRequested = true;
            sceneDirty = true;
            LOGGER.info("RTest discarded stale full scene finalization (captureGeneration={}, sceneGeneration={})",
                completedBuild.generation(), sceneGeneration);
            return;
        }
        // One reference assignment is the only render-thread publication. Vulkan upload sees a
        // complete immutable section snapshot; no partially built scene can escape to the frame.
        smokeGeometry = completedBuild.geometry();
        capturedWindowOrigins.clear();
        capturedWindowOrigins.addAll(completedBuild.windowOrigins());
        capturedWindowChunkX = completedBuild.windowChunkX();
        capturedWindowChunkZ = completedBuild.windowChunkZ();
        queuedWindowChunkX = completedBuild.windowChunkX();
        queuedWindowChunkZ = completedBuild.windowChunkZ();
        queuedWindowValid = true;
        capturedWindowValid = true;
        fullCaptureCount++;
        sceneDirty = completedBuild.generation() != sceneGeneration
            || fullCaptureRequested
            || !pendingDirtySections.isEmpty();
        LOGGER.info("RTest applied full scene snapshot; scene now has {} triangles (partialCaptures={}, fullCaptures={})",
            smokeGeometry.triangleCount(), partialCaptureCount, fullCaptureCount);
    }

    /** Applies a completed CPU merge only at the beginning of the render callback. */
    private static void pollDirtyGeometryMerge(int renderDistanceChunks) {
        CompletableFuture<DirtyGeometryMerge> future = pendingGeometryMerge;
        if (future == null || !future.isDone()) {
            return;
        }
        pendingGeometryMerge = null;
        final DirtyGeometryMerge completedMerge;
        try {
            completedMerge = future.join();
        } catch (CompletionException exception) {
            LOGGER.error("RTest dirty-section CPU merge failed", exception.getCause());
            stopSmokeTestResources();
            return;
        }
        // New block notifications and camera-window movement are reconciled after this coherent
        // batch. Only a world/render-distance change or a wholly new snapshot makes it unsafe.
        boolean currentSnapshot = smokeGeometry != null
            && smokeGeometry.renderDistanceChunks == renderDistanceChunks
            && completedMerge.level() == capturedLevel
            && completedMerge.level() == Minecraft.getInstance().level
            && !fullCaptureRequested;
        if (!currentSnapshot) {
            // A world, render-distance, or full-capture change makes the result unsafe. Re-queue
            // its sections; ordinary notifications and camera movement do not invalidate it.
            pendingDirtySections.addAll(completedMerge.dirtySections());
            pendingCaptureSections.addAll(completedMerge.dirtySections());
            notePendingDirtyEvents();
            sceneDirty = true;
            LOGGER.info("RTest discarded stale dirty-section merge (captureGeneration={}, sceneGeneration={}, fullCaptureRequested={})",
                completedMerge.generation(), sceneGeneration, fullCaptureRequested);
            return;
        }
        // This single reference assignment is the render-boundary publication. The old geometry
        // remains untouched and is still safe for the preceding frame; its Vulkan resources are
        // retired by RayTracingSmokeTest when it observes the new revision.
        smokeGeometry = completedMerge.geometry();
        capturedWindowOrigins.clear();
        capturedWindowOrigins.addAll(completedMerge.windowOrigins());
        capturedWindowChunkX = completedMerge.windowChunkX();
        capturedWindowChunkZ = completedMerge.windowChunkZ();
        queuedWindowChunkX = completedMerge.windowChunkX();
        queuedWindowChunkZ = completedMerge.windowChunkZ();
        queuedWindowValid = true;
        capturedWindowValid = true;
        partialCaptureCount++;
        sceneDirty = fullCaptureRequested || !pendingDirtySections.isEmpty();
        LOGGER.info("RTest applied dirty-section update; scene now has {} triangles (partialCaptures={}, fullCaptures={})",
            smokeGeometry.triangleCount(), partialCaptureCount, fullCaptureCount);
    }

    /**
     * Replaces only LOD parents that contain a changed native section. Clean SectionGeometry
     * instances are retained by replaceSections(), so identity comparison is a cheap and exact
     * change detector. This keeps a camera moving through a streamed world from restarting the
     * entire hierarchy after every small scene publication.
     */
    private static void updateTerrainLodInputsIncrementally(
            RayTracingScene.SceneGeometry previous,
            RayTracingScene.SceneGeometry next,
            int maxLevel,
            RayTracingTerrainProxyStore.WorldIdentity proxyIdentity) {
        Map<Long, RayTracingScene.SceneGeometry.SectionGeometry> previousSections =
            terrainSectionsByOrigin(previous);
        Map<Long, RayTracingScene.SceneGeometry.SectionGeometry> nextSections =
            terrainSectionsByOrigin(next);
        Set<Long> origins = new LinkedHashSet<>(previousSections.keySet());
        origins.addAll(nextSections.keySet());
        Set<Long> changedOrigins = new LinkedHashSet<>();
        Map<Long, RayTracingScene.SceneGeometry.SectionGeometry> changedNext = new LinkedHashMap<>();
        Map<Long, RayTracingTerrainLod.NodeKey> affectedNodes = new LinkedHashMap<>();
        for (long origin : origins) {
            RayTracingScene.SceneGeometry.SectionGeometry oldSection = previousSections.get(origin);
            RayTracingScene.SceneGeometry.SectionGeometry newSection = nextSections.get(origin);
            if (oldSection == newSection) continue;
            changedOrigins.add(origin);
            if (oldSection != null && isOpaqueSection(oldSection)) {
                terrainLodHierarchyKeys.remove(terrainLodKey(oldSection));
                collectAffectedTerrainParents(oldSection, maxLevel, affectedNodes);
                invalidateTerrainProxyParents(proxyIdentity, oldSection, maxLevel);
            }
            if (newSection != null && isOpaqueSection(newSection)) {
                terrainLodHierarchyKeys.add(terrainLodKey(newSection));
                collectAffectedTerrainParents(newSection, maxLevel, affectedNodes);
                changedNext.put(origin, newSection);
                invalidateTerrainProxyParents(proxyIdentity, newSection, maxLevel);
            }
        }
        if (changedOrigins.isEmpty()) return;

        Map<RayTracingTerrainLodScheduler.NodeKey, RayTracingTerrainLodScheduler.NodeVersion> versions =
            new HashMap<>(terrainLodNodeVersions);
        for (Map.Entry<Long, RayTracingTerrainLod.NodeKey> affected : affectedNodes.entrySet()) {
            long id = affected.getKey();
            RayTracingTerrainLod.NodeKey nodeKey = affected.getValue();
            List<RayTracingTerrainLod.SectionInput> updated = new ArrayList<>(
                terrainLodInputs.getOrDefault(id, List.of()));
            updated.removeIf(input -> changedOrigins.contains(
                new BlockPos(input.originX(), input.originY(), input.originZ()).asLong()));
            for (Map.Entry<Long, RayTracingScene.SceneGeometry.SectionGeometry> replacement
                    : changedNext.entrySet()) {
                RayTracingScene.SceneGeometry.SectionGeometry section = replacement.getValue();
                if (terrainLodParentKey(section, nodeKey.level()).equals(nodeKey)) {
                    updated.add(terrainLodInput(section));
                }
            }

            RayTracingTerrainLodScheduler.NodeKey schedulerKey =
                new RayTracingTerrainLodScheduler.NodeKey(id);
            Long oldFingerprint = terrainLodFingerprints.get(id);
            if (updated.isEmpty()) {
                terrainLodInputs.remove(id);
                terrainLodFingerprints.remove(id);
                terrainLodHierarchyKeys.remove(nodeKey);
                versions.remove(schedulerKey);
                invalidateTerrainLodNode(id, schedulerKey);
                continue;
            }

            long fingerprint = inputFingerprint(updated);
            terrainLodInputs.put(id, List.copyOf(updated));
            terrainLodFingerprints.put(id, fingerprint);
            terrainLodHierarchyKeys.add(nodeKey);
            if (oldFingerprint == null || oldFingerprint.longValue() != fingerprint) {
                RayTracingTerrainLodScheduler.NodeVersion oldVersion = versions.get(schedulerKey);
                long nodeGeneration = oldVersion == null ? 1L : oldVersion.nodeGeneration() + 1L;
                versions.put(schedulerKey,
                    new RayTracingTerrainLodScheduler.NodeVersion(nodeGeneration, fingerprint));
                invalidateTerrainLodNode(id, schedulerKey);
            }
        }
        terrainLodNodeVersions = Map.copyOf(versions);
        terrainLodSelectionDirty = true;
    }

    private static Map<Long, RayTracingScene.SceneGeometry.SectionGeometry> terrainSectionsByOrigin(
            RayTracingScene.SceneGeometry geometry) {
        Map<Long, RayTracingScene.SceneGeometry.SectionGeometry> sections = new LinkedHashMap<>();
        for (RayTracingScene.SceneGeometry.SectionGeometry section : geometry.sections) {
            sections.put(new BlockPos(section.originX, section.originY, section.originZ).asLong(), section);
        }
        return sections;
    }

    private static RayTracingTerrainLod.SectionInput terrainLodInput(
            RayTracingScene.SceneGeometry.SectionGeometry section) {
        return RayTracingTerrainLod.SectionInput.viewOf(section.originX, section.originY, section.originZ,
            section.vertices, section.materialData,
            ((long)section.vertexFingerprint() << 32) ^ (section.materialFingerprint & 0xffffffffL));
    }

    private static RayTracingTerrainLod.NodeKey terrainLodParentKey(
            RayTracingScene.SceneGeometry.SectionGeometry section, int level) {
        int sx = Math.floorDiv(section.originX, RayTracingTerrainLod.SECTION_SIZE);
        int sy = Math.floorDiv(section.originY, RayTracingTerrainLod.SECTION_SIZE);
        int sz = Math.floorDiv(section.originZ, RayTracingTerrainLod.SECTION_SIZE);
        return new RayTracingTerrainLod.NodeKey(level, Math.floorDiv(sx, 1 << level),
            Math.floorDiv(sy, 1 << level), Math.floorDiv(sz, 1 << level));
    }

    private static void collectAffectedTerrainParents(
            RayTracingScene.SceneGeometry.SectionGeometry section,
            int maxLevel,
            Map<Long, RayTracingTerrainLod.NodeKey> affectedNodes) {
        for (int level = 1; level <= maxLevel; level++) {
            RayTracingTerrainLod.NodeKey key = terrainLodParentKey(section, level);
            affectedNodes.put(terrainLodId(key), key);
        }
    }

    private static void invalidateTerrainLodNode(
            long id, RayTracingTerrainLodScheduler.NodeKey schedulerKey) {
        terrainLodResults.remove(id);
        terrainLodBlocked.remove(id);
        terrainLodPending.remove(id);
        RayTracingTerrainLodScheduler.Token token = terrainLodWorkerTokens.remove(id);
        if (token != null) terrainLodWorkerInputs.remove(token);
        if (terrainLodScheduler != null) terrainLodScheduler.cancel(schedulerKey);
    }

    private static void updateTerrainLod(Camera camera) {
        boolean enabled = RayTracingClientConfig.INSTANCE.terrainLodEnabled.get();
        pollTerrainGeometryComposition(enabled);
        enabled = RayTracingClientConfig.INSTANCE.terrainLodEnabled.get();
        if (!enabled) {
            if (terrainLodScheduler != null || terrainLodSourceGeometry != null) {
                closeTerrainLodScheduler();
            }
            terrainLodGpuTraversalActive = false;
            activeGeometry = smokeGeometry;
            return;
        }
        int radius = RayTracingClientConfig.INSTANCE.terrainLodNativeRadiusChunks.get();
        int maxLevel = Math.min(RayTracingTerrainLod.MAX_HIERARCHY_LEVEL,
            RayTracingClientConfig.INSTANCE.terrainLodMaxLevel.get());
        boolean farCacheEnabled = RayTracingClientConfig.INSTANCE.terrainLodFarCacheEnabled.get();
        int farRadius = Math.min(smokeGeometry.renderDistanceChunks,
            Math.max(radius, RayTracingClientConfig.INSTANCE.terrainLodFarRadiusChunks.get()));
        RayTracingTerrainProxyStore.WorldIdentity proxyIdentity =
            ensureTerrainProxyIdentity(capturedLevel == null ? Minecraft.getInstance().level : capturedLevel);
        if (!farCacheEnabled && proxyIdentity != null) {
            terrainProxyStore.clear(proxyIdentity);
        }
        int budget = RayTracingClientConfig.INSTANCE.terrainLodBuildBudget.get();
        int queueLimit = RayTracingClientConfig.INSTANCE.terrainLodQueueLimit.get();
        boolean gpuTraversalRequested = RayTracingClientConfig.INSTANCE.terrainLodGpuTraversalEnabled.get();
        long config = 31L * radius + 37L * maxLevel + 41L * budget + 43L * queueLimit
            + 61L * farRadius + (farCacheEnabled ? 67L : 71L)
            + (gpuTraversalRequested ? 73L : 79L);
        int cx = cameraChunkX(camera);
        int cz = cameraChunkZ(camera);
        boolean configChanged = config != terrainLodConfigFingerprint;
        // Partial section merges preserve the scene revision. Use that epoch to distinguish a
        // few changed parents from a genuinely new world snapshot; invalidating every parent on
        // each immutable merge starves LOD whenever chunks stream during movement.
        boolean sourceChanged = smokeGeometry != terrainLodSourceGeometry;
        if (sourceChanged || configChanged) {
            terrainLodGpuCandidateLimitExceeded = false;
        }
        // GPU traversal keeps every native BLAS resident so it can fall back when a coarse
        // node is unavailable. On large captures this defeats the CPU cut and can exceed both
        // the startup memory budget and the triangle gate. Enable it only when the complete
        // native-plus-coarse candidate set fits those limits.
        boolean gpuTraversalEnabled = gpuTraversalRequested
            && smokeGeometry.triangleCount() <= MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL
            && !terrainLodGpuCandidateLimitExceeded;
        if (gpuTraversalRequested && !gpuTraversalEnabled && (sourceChanged || configChanged)) {
            LOGGER.info("RTest using CPU terrain cut before RT because GPU traversal retains the full native scene "
                + "(triangles={}, GPU traversal limit={})",
                smokeGeometry.triangleCount(), MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL);
        }
        boolean windowChanged = !terrainLodSelectionValid
            || cx != terrainLodCameraChunkX || cz != terrainLodCameraChunkZ;
        if (sourceChanged || configChanged || windowChanged) {
            terrainLodChangeSerial++;
        }
        boolean compositionNeeded = sourceChanged || windowChanged;
        if (terrainLodScheduler == null || configChanged) {
            closeTerrainLodScheduler();
            terrainLodScheduler = new RayTracingTerrainLodScheduler<>(1, queueLimit,
                request -> {
                    RayTracingTerrainLod.NodeKey key = terrainLodNodeKey(request.nodeKey().nodeId());
                    if (key == null) {
                        throw new IllegalStateException("Missing terrain LOD node key for worker request");
                    }
                    return RayTracingTerrainLod.buildNode(key,
                        terrainLodWorkerInputs.getOrDefault(request.token(), List.of()));
            });
            terrainLodConfigFingerprint = config;
            sourceChanged = true;
            windowChanged = true;
            compositionNeeded = true;
        }
        boolean fullSourceRebuild = sourceChanged && (terrainLodSourceGeometry == null
            || smokeGeometry.revision() != terrainLodSourceGeometry.revision() || configChanged);
        if (fullSourceRebuild) {
            terrainLodSourceGeneration++;
            // LOD meshes depend on the source snapshot, not the camera. Keep this generation
            // stable as the view crosses chunks so in-flight parent builds remain publishable.
            terrainLodWindowGeneration = terrainLodSourceGeneration;
            terrainLodSourceGeometry = smokeGeometry;
            if (farCacheEnabled && proxyIdentity != null) {
                // A newly captured section is authoritative for every parent that contains it.
                // Remove an older proxy before its replacement worker completes; never render a
                // stale parent while the native source is already available.
                for (RayTracingScene.SceneGeometry.SectionGeometry section : smokeGeometry.sections) {
                    invalidateTerrainProxyParents(proxyIdentity, section, maxLevel);
                }
            }
            terrainLodResults.clear();
            terrainLodInputs.clear();
            terrainLodFingerprints.clear();
            terrainLodBlocked.clear();
            terrainLodPending.clear();
            terrainLodSelected.clear();
            terrainLodSelectedKeys.clear();
            terrainLodWorkerInputs.clear();
            terrainLodWorkerTokens.clear();
            terrainLodHierarchyKeys.clear();
            terrainLodSelectionDirty = true;
            terrainLodCompositionPending = false;
            terrainLodSelection = null;
            terrainLodAvailableNodes = Map.of();
            terrainLodLastSelectionNanos = 0L;
            terrainLodScheduler.cancelAll();
            for (RayTracingScene.SceneGeometry.SectionGeometry section : smokeGeometry.sections) {
                if (!isOpaqueSection(section)) {
                    continue;
                }
                int sx = Math.floorDiv(section.originX, RayTracingTerrainLod.SECTION_SIZE);
                int sy = Math.floorDiv(section.originY, RayTracingTerrainLod.SECTION_SIZE);
                int sz = Math.floorDiv(section.originZ, RayTracingTerrainLod.SECTION_SIZE);
                RayTracingTerrainLod.NodeKey leaf = new RayTracingTerrainLod.NodeKey(0, sx, sy, sz);
                terrainLodHierarchyKeys.add(leaf);
                // A section participates in each parent level. Share one immutable view over
                // the already-owned section arrays instead of cloning multi-megabyte arrays
                // once per level.
                RayTracingTerrainLod.SectionInput input = RayTracingTerrainLod.SectionInput.viewOf(
                    section.originX, section.originY, section.originZ,
                    section.vertices, section.materialData,
                    ((long)section.vertexFingerprint() << 32)
                        ^ (section.materialFingerprint & 0xffffffffL));
                for (int level = 1; level <= maxLevel; level++) {
                    RayTracingTerrainLod.NodeKey key = new RayTracingTerrainLod.NodeKey(level,
                        Math.floorDiv(sx, 1 << level), Math.floorDiv(sy, 1 << level),
                        Math.floorDiv(sz, 1 << level));
                    terrainLodHierarchyKeys.add(key);
                    long id = terrainLodId(key);
                    terrainLodInputs.computeIfAbsent(id, ignored -> new ArrayList<>()).add(input);
                }
            }
            for (Map.Entry<Long, List<RayTracingTerrainLod.SectionInput>> entry : terrainLodInputs.entrySet()) {
                terrainLodFingerprints.put(entry.getKey(), inputFingerprint(entry.getValue()));
            }
            Map<RayTracingTerrainLodScheduler.NodeKey, RayTracingTerrainLodScheduler.NodeVersion> versions =
                new HashMap<>();
            for (Map.Entry<Long, Long> entry : terrainLodFingerprints.entrySet()) {
                versions.put(new RayTracingTerrainLodScheduler.NodeKey(entry.getKey()),
                    new RayTracingTerrainLodScheduler.NodeVersion(1L, entry.getValue()));
            }
            terrainLodNodeVersions = Map.copyOf(versions);
        } else if (sourceChanged) {
            updateTerrainLodInputsIncrementally(terrainLodSourceGeometry, smokeGeometry,
                maxLevel, farCacheEnabled ? proxyIdentity : null);
            terrainLodSourceGeometry = smokeGeometry;
        }
        if (windowChanged) {
            terrainLodCameraChunkX = cx;
            terrainLodCameraChunkZ = cz;
            terrainLodSelectionValid = true;
            // Node meshes are view independent. Re-evaluate the cut for the new camera, but
            // preserve pending and ready nodes so rapid movement cannot cancel all LOD work.
            terrainLodSelectionDirty = true;
        }
        for (RayTracingTerrainLodScheduler.Result<RayTracingTerrainLod.Node> result :
                terrainLodScheduler.poll(budget, terrainLodSourceGeneration,
                    terrainLodWindowGeneration, terrainLodNodeVersions)) {
            long id = result.request().nodeKey().nodeId();
            terrainLodPending.remove(id);
            terrainLodWorkerInputs.remove(result.request().token());
            terrainLodWorkerTokens.remove(id, result.request().token());
            RayTracingTerrainLod.NodeKey key = terrainLodNodeKey(id);
            if (key != null && key.equals(result.value().key())) {
                terrainLodResults.put(id, result.value());
                terrainLodChangeSerial++;
                if (farCacheEnabled && proxyIdentity != null && result.value().ready()) {
                    terrainProxyStore.load(proxyIdentity, key, sceneGeneration,
                        terrainLodFingerprints.getOrDefault(id, 0L),
                        RayTracingTerrainProxyStore.OpaqueNodeMesh.from(result.value().mesh()));
                }
                terrainLodSelectionDirty = true;
                terrainLodCompositionPending = true;
            }
        }
        for (RayTracingTerrainLodScheduler.Failure failure : terrainLodScheduler.pollFailures(4)) {
            long failedId = failure.request().nodeKey().nodeId();
            terrainLodPending.remove(failedId);
            terrainLodWorkerInputs.remove(failure.request().token());
            terrainLodWorkerTokens.remove(failedId, failure.request().token());
            terrainLodBlocked.add(failedId);
            LOGGER.warn("RTest terrain LOD worker failed for request {}", failure.request(), failure.cause());
        }

        long selectionNow = System.nanoTime();
        boolean selectionUpdateDue = terrainLodSelectionDirty
            && (sourceChanged || windowChanged || terrainLodSelection == null
                || selectionNow - terrainLodLastSelectionNanos >= TERRAIN_LOD_SELECTION_INTERVAL_NANOS);
        if (selectionUpdateDue) {
            // Add only immutable proxies that came from a previously observed native section. A
            // proxy overlapping the current ClientLevel snapshot is deliberately ignored: native
            // capture remains authoritative there, including transparent/fluid sections.
            Map<Long, RayTracingTerrainLod.Node> availableTerrainNodes = new HashMap<>(terrainLodResults);
            if (farCacheEnabled && proxyIdentity != null) {
                for (RayTracingTerrainProxyStore.NodeSnapshot snapshot
                        : terrainProxyStore.snapshot(proxyIdentity).nodes()) {
                    RayTracingTerrainLod.NodeKey key = snapshot.id().nodeKey();
                    if (key.level() <= 0 || key.level() > maxLevel
                            || distanceToNode(key, camera) > farRadius * 16.0
                            || overlapsCurrentNativeGeometry(key, smokeGeometry)) {
                        continue;
                    }
                    long id = terrainLodId(key);
                    if (terrainLodResults.containsKey(id)) continue;
                    RayTracingTerrainLod.Node node = RayTracingTerrainLod.Node.fromMesh(
                        key, snapshot.mesh().vertices(), snapshot.mesh().materialData());
                    if (node.ready()) {
                        availableTerrainNodes.put(id, node);
                    }
                }
            }

            // Unready LOD keys and native leaves are implicit fallbacks. They never need nodes in
            // the selector: retaining only ready coarse nodes preserves the selected cut while
            // avoiding one selection step per native section on RD32 worlds.
            Set<Long> previousSelected = new HashSet<>(terrainLodSelected);
            List<RayTracingTerrainLod.Node> hierarchyNodes = new ArrayList<>(availableTerrainNodes.size());
            for (RayTracingTerrainLod.Node node : availableTerrainNodes.values()) {
                if (node.key().level() > 0 && node.ready()) hierarchyNodes.add(node);
            }
            RayTracingTerrainLod.Hierarchy hierarchy = RayTracingTerrainLod.fromNodes(hierarchyNodes);
            double enterDistance = radius * 16.0;
            double exitDistance = Math.max(0.0, (radius - 1) * 16.0);
            terrainLodSelection = hierarchy.select(
                new RayTracingTerrainLod.View(camera.position().x, camera.position().y, camera.position().z,
                    1.0, Math.PI * 0.5),
                new RayTracingTerrainLod.Hysteresis(enterDistance, exitDistance,
                    Double.MAX_VALUE, Double.MAX_VALUE),
                terrainLodSelectedKeys);
            terrainLodAvailableNodes = Map.copyOf(availableTerrainNodes);
            terrainLodSelected.clear();
            terrainLodSelectedKeys.clear();
            for (RayTracingTerrainLod.NodeKey key : terrainLodSelection.nodes()) {
                if (key.level() > 0) {
                    terrainLodSelected.add(terrainLodId(key));
                    terrainLodSelectedKeys.add(key);
                }
            }
            boolean selectedSetChanged = !terrainLodSelected.equals(previousSelected);
            if (selectedSetChanged) terrainLodChangeSerial++;
            compositionNeeded |= terrainLodCompositionPending
                // The GPU shader can choose visibility within the candidate set, but the
                // candidate set itself is the selected CPU cut. Recompose when that cut moves
                // so a newly selected parent cannot coexist with its old child proxies.
                || selectedSetChanged;
            terrainLodSelectionDirty = false;
            terrainLodLastSelectionNanos = selectionNow;
        }
        Map<Long, RayTracingTerrainLod.Node> availableTerrainNodes = terrainLodAvailableNodes;
        RayTracingTerrainLod.Selection selection = terrainLodSelection;
        Set<RayTracingTerrainLod.NodeKey> selectedTerrainKeys = terrainLodSelectedKeys;

        int submitted = 0;
        int nativeRadiusSkipped = 0;
        boolean hasFarProxy = false;
        for (Map.Entry<Long, List<RayTracingTerrainLod.SectionInput>> entry : terrainLodInputs.entrySet()) {
            long id = entry.getKey();
            if (terrainLodBlocked.contains(id) || terrainLodPending.contains(id) || terrainLodResults.containsKey(id)) continue;
            RayTracingTerrainLod.NodeKey key = terrainLodKeyFromId(id);
            double distance = distanceToNode(key, camera);
            if (distance < radius * 16.0) {
                nativeRadiusSkipped++;
                continue;
            }
            long fingerprint = terrainLodFingerprints.getOrDefault(id, 0L);
            RayTracingTerrainLodScheduler.NodeVersion nodeVersion = terrainLodNodeVersions.get(
                new RayTracingTerrainLodScheduler.NodeKey(id));
            if (nodeVersion == null) continue;
            RayTracingTerrainLodScheduler.Request request = terrainLodScheduler.request(
                new RayTracingTerrainLodScheduler.NodeKey(id), terrainLodSourceGeneration,
                terrainLodWindowGeneration,
                nodeVersion.nodeGeneration(), fingerprint, key.level(), distance);
            terrainLodWorkerInputs.put(request.token(), List.copyOf(entry.getValue()));
            if (submitted >= budget || !terrainLodScheduler.submit(request)) {
                terrainLodWorkerInputs.remove(request.token());
                break;
            }
            terrainLodPending.add(id);
            terrainLodWorkerTokens.put(id, request.token());
            submitted++;
        }
        gpuTraversalEnabled &= terrainLodPending.isEmpty();
        if (gpuTraversalEnabled != terrainLodGpuTraversalActive) {
            compositionNeeded = true;
        }
        long diagnosticFrame = ++terrainLodDiagnosticFrame;
        if (!compositionNeeded || pendingTerrainGeometryComposition != null) {
            logTerrainLodState(diagnosticFrame, radius, maxLevel, gpuTraversalEnabled,
                nativeRadiusSkipped, availableTerrainNodes, selection, terrainLodFarProxyActive, submitted);
            return;
        }
        List<RayTracingScene.SceneGeometry.SectionGeometry> nativeSections = new ArrayList<>();
        List<RayTracingScene.SceneGeometry.SectionGeometry> coarseSections = new ArrayList<>();
        for (RayTracingScene.SceneGeometry.SectionGeometry section : smokeGeometry.sections) {
            if (!isOpaqueSection(section)) {
                nativeSections.add(section);
                continue;
            }
            RayTracingTerrainLod.NodeKey leaf = terrainLodKey(section);
            boolean covered = false;
            RayTracingTerrainLod.NodeKey ancestor = leaf;
            for (int level = 1; level <= maxLevel; level++) {
                ancestor = ancestor.parent();
                if (ancestor != null && selectedTerrainKeys.contains(ancestor)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) nativeSections.add(section);
        }
        List<RayTracingScene.SceneGeometry.SectionGeometry> cpuNativeSections = List.copyOf(nativeSections);
        if (gpuTraversalEnabled) {
            coarseSections.clear();
            Set<RayTracingTerrainLod.NodeKey> gpuCoarseKeys = new HashSet<>();
            // Feed the shader the same mutually-exclusive cut selected by the CPU hierarchy
            // walker. Passing every ready node made the GPU candidate set contain parents and
            // descendants at the same time; after native compaction that sparse set could no
            // longer describe a complete octree cut, so multiple LOD layers were visible in
            // the same ray. GPU traversal still performs frustum/Hi-Z selection over this cut,
            // while the CPU cut guarantees that a ray cannot hit overlapping LOD proxies.
            for (RayTracingTerrainLod.NodeKey key : selectedTerrainKeys) {
                if (key.level() == 0 || distanceToNode(key, camera) > farRadius * 16.0) continue;
                long nodeId = terrainLodId(key);
                RayTracingTerrainLod.Node node = availableTerrainNodes.get(nodeId);
                if (node == null) continue;
                RayTracingScene.SceneGeometry.SectionGeometry coarse =
                    RayTracingScene.SceneGeometry.SectionGeometry.coarse(node);
                if (coarse != null) {
                    coarseSections.add(coarse);
                    gpuCoarseKeys.add(key);
                    hasFarProxy |= !terrainLodResults.containsKey(nodeId);
                }
            }
            // Keep the close native ring for detail. Far native leaves covered by a ready
            // coarse proxy only inflate the TLAS: masking them still pays for their AS boxes
            // and for a full TLAS UPDATE. If a proxy is absent, retain the leaf as fallback.
            nativeSections = new ArrayList<>();
            double nativeRadiusBlocks = radius * 16.0;
            long candidateTriangleCount = 0L;
            for (RayTracingScene.SceneGeometry.SectionGeometry section : smokeGeometry.sections) {
                if (!isOpaqueSection(section)) {
                    nativeSections.add(section);
                    candidateTriangleCount += section.triangleCount();
                    continue;
                }
                RayTracingTerrainLod.NodeKey leaf = terrainLodKey(section);
                if (RayTracingTerrainLod.keepGpuNativeLeaf(
                        leaf, gpuCoarseKeys, distanceToNode(leaf, camera), nativeRadiusBlocks)) {
                    nativeSections.add(section);
                    candidateTriangleCount += section.triangleCount();
                }
            }
            for (RayTracingScene.SceneGeometry.SectionGeometry coarse : coarseSections) {
                candidateTriangleCount += coarse.triangleCount();
            }
            if (candidateTriangleCount > MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL) {
                // The compacted candidate set is the budget that matters. Disable GPU traversal
                // only when the instances that would actually enter the TLAS still exceed it.
                gpuTraversalEnabled = false;
                terrainLodGpuCandidateLimitExceeded = true;
                nativeSections = new ArrayList<>(cpuNativeSections);
                coarseSections.clear();
            } else {
                terrainLodGpuCandidateLimitExceeded = false;
            }
        }
        if (!gpuTraversalEnabled) {
            hasFarProxy = false;
            for (long selectedId : terrainLodSelected) {
                RayTracingTerrainLod.Node node = availableTerrainNodes.get(selectedId);
                if (node != null) {
                    RayTracingScene.SceneGeometry.SectionGeometry coarse =
                        RayTracingScene.SceneGeometry.SectionGeometry.coarse(node);
                    if (coarse != null) {
                        coarseSections.add(coarse);
                        hasFarProxy |= !terrainLodResults.containsKey(selectedId);
                    }
                }
            }
        }
        if (coarseSections.isEmpty() && nativeSections.size() == smokeGeometry.sections.size()) {
            activeGeometry = smokeGeometry;
            terrainLodGpuTraversalActive = gpuTraversalEnabled;
            terrainLodFarProxyActive = false;
            terrainLodCompositionPending = false;
        } else {
            List<RayTracingScene.SceneGeometry.SectionGeometry> nativeSnapshot = List.copyOf(nativeSections);
            List<RayTracingScene.SceneGeometry.SectionGeometry> coarseSnapshot = List.copyOf(coarseSections);
            RayTracingScene.SceneGeometry source = smokeGeometry;
            RayTracingScene.SceneGeometry previousActive = activeGeometry == null ? source : activeGeometry;
            int composedRenderDistance = hasFarProxy
                ? Math.max(source.renderDistanceChunks, farRadius) : source.renderDistanceChunks;
            long compositionSerial = terrainLodChangeSerial;
            long compositionConfig = config;
            boolean compositionGpuTraversalEnabled = gpuTraversalEnabled;
            boolean compositionFarProxyActive = hasFarProxy;
            terrainLodCompositionPending = true;
            pendingTerrainGeometryComposition = CompletableFuture.supplyAsync(() -> {
                long compositionStart = System.nanoTime();
                RayTracingScene.SceneGeometry geometry = RayTracingScene.SceneGeometry.compose(
                    nativeSnapshot, coarseSnapshot, source, composedRenderDistance, previousActive);
                return new TerrainGeometryComposition(geometry, source, compositionSerial,
                    compositionConfig, compositionGpuTraversalEnabled, compositionFarProxyActive,
                    System.nanoTime() - compositionStart);
            }, TERRAIN_COMPOSITION_EXECUTOR);
        }
        logTerrainLodState(diagnosticFrame, radius, maxLevel, gpuTraversalEnabled,
            nativeRadiusSkipped, availableTerrainNodes, selection, hasFarProxy, submitted);
    }

    /**
     * Reports the CPU cut separately from the Vulkan pass's final geometry counts. In GPU mode
     * the native sections are intentionally retained as candidates, so coarse_sections alone
     * cannot tell whether a CPU node was ready or selected.
     */
    private static void logTerrainLodState(
            long frame, int radius, int maxLevel, boolean gpuTraversalEnabled,
            int nativeRadiusSkipped, Map<Long, RayTracingTerrainLod.Node> availableNodes,
            RayTracingTerrainLod.Selection selection, boolean hasFarProxy, int submitted) {
        if (frame % 120L != 0L) return;
        int readyNodes = 0;
        int levelOneReady = 0;
        int levelTwoReady = 0;
        for (RayTracingTerrainLod.Node node : availableNodes.values()) {
            if (!node.ready() || node.key().level() == 0) continue;
            readyNodes++;
            if (node.key().level() == 1) levelOneReady++;
            if (node.key().level() == 2) levelTwoReady++;
        }
        int selectedLevelOne = 0;
        int selectedLevelTwo = 0;
        for (RayTracingTerrainLod.NodeKey key : selection.nodes()) {
            if (key.level() == 1) selectedLevelOne++;
            if (key.level() == 2) selectedLevelTwo++;
        }
        int activeCoarse = 0;
        if (activeGeometry != null) {
            for (RayTracingScene.SceneGeometry.SectionGeometry section : activeGeometry.sections) {
                if (section.terrainNodeKey().level() > 0) activeCoarse++;
            }
        }
        int nativeFallbacks = 0;
        for (RayTracingTerrainLod.NodeKey key : terrainLodHierarchyKeys) {
            if (key.level() != 0) continue;
            boolean covered = false;
            for (RayTracingTerrainLod.NodeKey ancestor = key.parent(); ancestor != null;
                    ancestor = ancestor.parent()) {
                if (terrainLodSelectedKeys.contains(ancestor)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) nativeFallbacks++;
        }
        LOGGER.info(
            "RTest terrain LOD: frame={} radius_chunks={} max_level={} gpu={} source_sections={} "
                + "hierarchy_nodes={} ready_nodes={} ready_l1={} ready_l2={} selected_l1={} selected_l2={} "
                + "native_fallbacks={} native_radius_skipped={} submitted={} pending={} blocked={} "
                + "active_native={} active_coarse={} far_proxy={}",
            frame, radius, maxLevel, gpuTraversalEnabled,
            terrainLodSourceGeometry == null ? 0 : terrainLodSourceGeometry.sections.size(),
            terrainLodHierarchyKeys.size(), readyNodes, levelOneReady, levelTwoReady,
            selectedLevelOne, selectedLevelTwo, nativeFallbacks, nativeRadiusSkipped,
            submitted, terrainLodPending.size(), terrainLodBlocked.size(),
            activeGeometry == null ? 0 : activeGeometry.sections.size() - activeCoarse,
            activeCoarse, hasFarProxy);
    }

    private static boolean isOpaqueSection(RayTracingScene.SceneGeometry.SectionGeometry section) {
        return section.isOpaqueTerrain();
    }

    private static long inputFingerprint(List<RayTracingTerrainLod.SectionInput> inputs) {
        long hash = 1;
        for (RayTracingTerrainLod.SectionInput input : inputs) {
            hash = 31 * hash + input.contentFingerprint();
        }
        return hash;
    }

    private static double distanceToNode(RayTracingTerrainLod.NodeKey key, Camera camera) {
        RayTracingTerrainLod.Bounds b = RayTracingTerrainLod.boundsFor(key);
        double dx = camera.position().x < b.minX() ? b.minX() - camera.position().x
            : camera.position().x > b.maxX() ? camera.position().x - b.maxX() : 0.0;
        double dz = camera.position().z < b.minZ() ? b.minZ() - camera.position().z
            : camera.position().z > b.maxZ() ? camera.position().z - b.maxZ() : 0.0;
        return Math.hypot(dx, dz);
    }

    private static RayTracingTerrainLod.NodeKey terrainLodKey(RayTracingScene.SceneGeometry.SectionGeometry section) {
        return new RayTracingTerrainLod.NodeKey(0,
            Math.floorDiv(section.originX, RayTracingTerrainLod.SECTION_SIZE),
            Math.floorDiv(section.originY, RayTracingTerrainLod.SECTION_SIZE),
            Math.floorDiv(section.originZ, RayTracingTerrainLod.SECTION_SIZE));
    }

    private static RayTracingTerrainProxyStore.WorldIdentity ensureTerrainProxyIdentity(ClientLevel level) {
        if (level == null) return null;
        String levelIdentity = Integer.toHexString(System.identityHashCode(level));
        RayTracingTerrainProxyStore.WorldIdentity next =
            new RayTracingTerrainProxyStore.WorldIdentity(
                "client-level@" + levelIdentity,
                level.dimension().toString(),
                "session@" + levelIdentity);
        if (!next.equals(terrainProxyIdentity)) {
            if (terrainProxyIdentity != null) terrainProxyStore.clear(terrainProxyIdentity);
            terrainProxyIdentity = next;
        }
        return terrainProxyIdentity;
    }

    private static void invalidateTerrainProxyParents(
            RayTracingTerrainProxyStore.WorldIdentity identity,
            RayTracingScene.SceneGeometry.SectionGeometry section, int maxLevel) {
        if (identity == null) return;
        int sx = Math.floorDiv(section.originX, RayTracingTerrainLod.SECTION_SIZE);
        int sy = Math.floorDiv(section.originY, RayTracingTerrainLod.SECTION_SIZE);
        int sz = Math.floorDiv(section.originZ, RayTracingTerrainLod.SECTION_SIZE);
        for (int level = 1; level <= maxLevel; level++) {
            terrainProxyStore.invalidate(identity, new RayTracingTerrainLod.NodeKey(level,
                Math.floorDiv(sx, 1 << level), Math.floorDiv(sy, 1 << level),
                Math.floorDiv(sz, 1 << level)));
        }
    }

    private static boolean overlapsCurrentNativeGeometry(RayTracingTerrainLod.NodeKey node,
                                                          RayTracingScene.SceneGeometry geometry) {
        RayTracingTerrainLod.Bounds bounds = RayTracingTerrainLod.boundsFor(node);
        for (RayTracingScene.SceneGeometry.SectionGeometry section : geometry.sections) {
            if (section.originX + RayTracingTerrainLod.SECTION_SIZE > bounds.minX()
                    && section.originX < bounds.maxX()
                    && section.originY + RayTracingTerrainLod.SECTION_SIZE > bounds.minY()
                    && section.originY < bounds.maxY()
                    && section.originZ + RayTracingTerrainLod.SECTION_SIZE > bounds.minZ()
                    && section.originZ < bounds.maxZ()) {
                return true;
            }
        }
        return false;
    }

    /** Assigns a collision-free scheduler identity for the current immutable source snapshot. */
    private static long terrainLodId(RayTracingTerrainLod.NodeKey key) {
        Long existing = terrainLodIdsByKey.get(key);
        if (existing != null) return existing;
        if (nextTerrainLodId == Long.MAX_VALUE) {
            throw new IllegalStateException("terrain LOD node identity exhausted");
        }
        long id = nextTerrainLodId++;
        terrainLodIdsByKey.put(key, id);
        terrainLodKeysById.put(id, key);
        return id;
    }

    private static RayTracingTerrainLod.NodeKey terrainLodKeyFromId(long id) {
        return terrainLodKeysById.get(id);
    }

    private static RayTracingTerrainLod.NodeKey terrainLodNodeKey(long id) {
        return terrainLodKeyFromId(id);
    }

    private static void disableTerrainLodAfterFailure(Throwable failure) {
        if (failure == null) {
            LOGGER.error("RTest disabled terrain LOD after a coarse geometry publication failure; retrying with native terrain");
        } else {
            LOGGER.error("RTest disabled terrain LOD after a coarse geometry preparation failure; retrying with native terrain", failure);
        }
        closeTerrainLodScheduler();
        activeGeometry = smokeGeometry;
        if (RayTracingClientConfig.INSTANCE.terrainLodEnabled.get()) {
            try {
                RayTracingClientConfig.INSTANCE.terrainLodEnabled.set(false);
                RayTracingClientConfig.INSTANCE.save();
            } catch (Throwable configFailure) {
                LOGGER.warn("RTest could not persist the terrain LOD safety disable", configFailure);
            }
        }
    }

    private static void pollTerrainGeometryComposition(boolean enabled) {
        CompletableFuture<TerrainGeometryComposition> future = pendingTerrainGeometryComposition;
        if (future == null || !future.isDone()) return;
        pendingTerrainGeometryComposition = null;
        TerrainGeometryComposition result;
        try {
            result = future.join();
        } catch (CompletionException failure) {
            disableTerrainLodAfterFailure(failure.getCause());
            return;
        }
        if (!enabled) {
            terrainLodCompositionPending = false;
            return;
        }
        if (result.sourceGeometry() != smokeGeometry
                || result.configFingerprint() != terrainLodConfigFingerprint
                || result.changeSerial() != terrainLodChangeSerial) {
            // Inputs changed while the worker was composing. Keep the current published scene
            // and request a fresh snapshot from the latest selection on this render tick.
            terrainLodCompositionPending = true;
            return;
        }
        activeGeometry = result.geometry();
        terrainLodGpuTraversalActive = result.gpuTraversalEnabled();
        terrainLodFarProxyActive = result.farProxyActive();
        terrainLodCompositionPending = false;
        LOGGER.info("RTest terrain composition published: sections={}, triangles={}, gpuTraversal={}, "
                + "farProxy={}, workerDuration={} ms",
            result.geometry().sections.size(), result.geometry().triangleCount(),
            result.gpuTraversalEnabled(), result.farProxyActive(), result.durationNanos() / 1_000_000L);
    }

    private static void closeTerrainLodScheduler() {
        if (terrainLodScheduler != null) {
            terrainLodScheduler.close();
            terrainLodScheduler = null;
        }
        terrainLodPending.clear();
        terrainLodResults.clear();
        terrainLodInputs.clear();
        terrainLodFingerprints.clear();
        terrainLodBlocked.clear();
        terrainLodSelected.clear();
        terrainLodSelectedKeys.clear();
        terrainLodWorkerInputs.clear();
        terrainLodWorkerTokens.clear();
        terrainLodHierarchyKeys.clear();
        terrainLodNodeVersions = Map.of();
        terrainLodAvailableNodes = Map.of();
        terrainLodSelection = null;
        terrainLodSelectionDirty = true;
        terrainLodCompositionPending = false;
        terrainLodChangeSerial++;
        terrainLodLastSelectionNanos = 0L;
        terrainLodKeysById.clear();
        terrainLodIdsByKey.clear();
        nextTerrainLodId = 1L;
        terrainLodSelectionValid = false;
        terrainLodSourceGeometry = null;
        terrainLodSourceGeneration = 0L;
        terrainLodDiagnosticFrame = 0L;
        terrainLodWarmupFrames = 0L;
        terrainLodFarProxyActive = false;
        terrainLodGpuTraversalActive = false;
        terrainLodGpuCandidateLimitExceeded = false;
    }

    private static int cameraChunkX(Camera camera) {
        return (int)Math.floor(camera.position().x / 16.0);
    }

    private static int cameraChunkZ(Camera camera) {
        return (int)Math.floor(camera.position().z / 16.0);
    }

    private static void stopSmokeTestResources() {
        smokeTestRequested = false;
        smokeTestStarted = false;
        activationFreeze = false;
        sceneDirty = false;
        fullCaptureRequested = false;
        partialCapture = false;
        nextRtRetryNanos = 0L;
        rtFailureCount = 0;
        sceneGeneration++;
        closeTerrainLodScheduler();
        if (terrainProxyIdentity != null) {
            terrainProxyStore.clear(terrainProxyIdentity);
            terrainProxyIdentity = null;
        }
        activeGeometry = null;
        smokeGeometry = null;
        pendingDirtySections.clear();
        firstPendingDirtyNanos = 0L;
        lastPendingDirtyNanos = 0L;
        pendingCaptureSections.clear();
        activeDirtySections = null;
        capturedWindowOrigins.clear();
        capturedWindowValid = false;
        queuedWindowValid = false;
        lastEntityFrame = null;
        DYNAMIC_SNAPSHOT_LOG_THROTTLE.reset();
        capturedLevel = null;
        RayTracingScene.SceneGeometry.CaptureSession session = captureSession;
        captureSession = null;
        CompletableFuture<DirtyGeometryMerge> dirtyMerge = pendingGeometryMerge;
        pendingGeometryMerge = null;
        CompletableFuture<FullGeometryBuild> fullBuild = pendingFullGeometryBuild;
        pendingFullGeometryBuild = null;
        CompletableFuture<TerrainGeometryComposition> terrainComposition = pendingTerrainGeometryComposition;
        pendingTerrainGeometryComposition = null;
        RayTracingPbrMaterials materials = pbrMaterials;
        pbrMaterials = null;
        try {
            if (session != null) {
                session.close();
            }
            // A worker may still be packing PBR data or reading the session arrays. Retire both
            // futures before closing NativeImages so shutdown cannot race the geometry worker.
            awaitGeometryFuture(dirtyMerge);
            awaitGeometryFuture(fullBuild);
            awaitGeometryFuture(terrainComposition);
        } finally {
            ItemModelGeometryAdapter.setPbrSampler(null);
            BlockEntityModelGeometryAdapter.clear();
            ItemModelGeometryAdapter.clear();
            LivingEntityGeometryAdapter.clear();
            PlayerModelGeometryAdapter.clear();
            dynamicEntities.clear();
            VanillaRenderController.INSTANCE.reset();
            // Close NativeImages here, on the client/render thread; never release them from a
            // CompletableFuture completion callback running on rtest-geometry-merge.
            closePbrMaterials(materials);
            try {
                RayTracingSmokeTest.close();
            } finally {
                CompiledSectionMeshCache.invalidateAll();
            }
        }
    }

    private static void awaitGeometryFuture(CompletableFuture<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.join();
        } catch (CompletionException exception) {
            LOGGER.warn("RTest geometry worker stopped during resource cleanup", exception.getCause());
        }
    }

    private static void closePbrMaterials(RayTracingPbrMaterials materials) {
        if (materials != null) {
            materials.close();
        }
    }

    private static void ensureDynamicGeometryPath() {
        if (RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
            return;
        }
        RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.set(true);
        LOGGER.warn("RTest re-enabled the required dynamic entity RT path; entity shadow NRD bypass remains active");
    }

    /** Releases all render-owned state when NeoForge unloads a ClientLevel. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) {
            if (smokeTestStarted || pbrMaterials != null || captureSession != null) {
                stopSmokeTestResources();
            } else {
                // Vanilla compilation can populate this cache even when RT was never started;
                // do not retain up to 256 MiB of a previous world's mesh snapshots.
                CompiledSectionMeshCache.invalidateAll();
            }
            LOGGER.info("RTest released client-world scene resources after ClientLevel unload");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        // F9 is a configuration screen, not an RT activation control. Handle it before
        // Vulkan/level checks so the LOD switch is reachable from menus and vanilla rendering.
        if (ClientKeyMappings.OPEN_RAY_TRACING_SETTINGS.consumeClick()) {
            if (!(minecraft.gui.screen() instanceof RayTracingSettingsScreen)) {
                minecraft.gui.setScreen(new RayTracingSettingsScreen(minecraft.gui.screen()));
            }
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }

        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return;
        }

        if (!(((GpuDeviceAccessor) device).rtest$getBackend() instanceof VulkanDevice vulkanDevice)) {
            if (!completed) {
                completed = true;
                LOGGER.warn("RTest ray tracing is disabled because Minecraft is not using the Vulkan backend");
            }
            return;
        }

        if (!completed) {
            completed = true;
            RayTracingSupport.Limits limits = RayTracingSupport.queryLimits(vulkanDevice);
            if (limits == null) {
                LOGGER.error("RTest could not query Vulkan ray-tracing properties");
                return;
            }

            LOGGER.info(
                "RTest ray tracing ready: handleSize={}, handleAlignment={}, baseAlignment={}, maxRecursionDepth={}, maxInstances={}, scratchAlignment={}",
                limits.shaderGroupHandleSize(),
                limits.shaderGroupHandleAlignment(),
                limits.shaderGroupBaseAlignment(),
                limits.maxRayRecursionDepth(),
                limits.maxInstanceCount(),
                limits.minScratchAlignment()
            );
        }

        if (minecraft.level == null) {
            if (smokeTestStarted || pbrMaterials != null || captureSession != null) {
                stopSmokeTestResources();
                LOGGER.info("RTest released Vulkan scene resources because the ClientLevel is gone");
            }
            return;
        }

        if (!ClientKeyMappings.RUN_RAY_TRACING_SMOKE_TEST.consumeClick()) {
            return;
        }
        if (smokeTestStarted) {
            stopSmokeTestResources();
            LOGGER.info("RTest stopped Vulkan ray-tracing smoke test");
            if (minecraft.player != null) {
                minecraft.gui.hud.setOverlayMessage(Component.translatable("overlay.rtest.smokeTest.stopped"), false);
            }
            return;
        }

        ensureDynamicGeometryPath();
        smokeTestRequested = true;
        LOGGER.info("RTest queued Vulkan ray-tracing smoke test for the next active 3D world frame");
        if (minecraft.player != null) {
            minecraft.gui.hud.setOverlayMessage(Component.translatable("overlay.rtest.smokeTest.queued"), false);
        }
    }
}
