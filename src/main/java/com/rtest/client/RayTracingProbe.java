package com.rtest.client;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static boolean completed;
    private static boolean smokeTestStarted;
    private static boolean smokeTestRequested;
    private static boolean sceneDirty;
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
    private static RayTracingScene.SceneGeometry.CaptureSession captureSession;
    /**
     * Geometry finalization is deliberately serialized. Capture still advances on the render
     * thread in small steps, but immutable flattening, LightTree construction and dirty merges
     * never do.
     */
    private static final ExecutorService GEOMETRY_MERGE_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "rtest-geometry-merge");
        worker.setDaemon(true);
        return worker;
    });
    private static CompletableFuture<DirtyGeometryMerge> pendingGeometryMerge;
    private static CompletableFuture<FullGeometryBuild> pendingFullGeometryBuild;
    private static RayTracingPbrMaterials pbrMaterials;
    private static final DynamicEntityGeometry dynamicEntities = new DynamicEntityGeometry();
    private static DynamicEntityGeometry.Frame lastEntityFrame;
    private static String lastDynamicSummary;
    private static final Set<Long> pendingDirtySections = new LinkedHashSet<>();
    private static final Set<Long> pendingCaptureSections = new LinkedHashSet<>();
    private static Set<Long> activeDirtySections;
    private static int capturedWindowChunkX;
    private static int capturedWindowChunkZ;
    // Geometry sections omit valid-but-empty/non-renderable sections. Keep the published window
    // membership separately so those sections are not recaptured forever on every frame.
    private static final Set<Long> capturedWindowOrigins = new LinkedHashSet<>();
    private static ClientLevel capturedLevel;
    private static int activeWindowChunkX;
    private static int activeWindowChunkZ;
    private static boolean capturedWindowValid;
    private static boolean renderFramePrepared;
    private static boolean dynamicFramePrepared;
    // Latched before deferred model preparation. Capture may run while vanilla still owns the
    // current frame, so model redirects must know whether this exact world pass will be cancelled.
    private static boolean vanillaWorldReplacementRequested;
    // Keep CPU fallback capture below the frame-time spike observed at higher budgets;
    private static final int SECTIONS_PER_FRAME = 2;
    // Bound one transaction as well as one frame. New dirty events remain queued instead of
    // invalidating unrelated work already captured for this transaction.
    // Keep the atomic scene publish small enough that dirty terrain cannot create a low-FPS hitch.
    // Remaining sections stay queued for subsequent frames, like Caustica's bounded completion pass.
    private static final int SECTIONS_PER_TRANSACTION = 8;

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
        VanillaRenderController.INSTANCE.beginFrame();
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
                smokeGeometry);
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
            pendingDirtySections.clear();
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
            pendingCaptureSections.clear();
            capturedWindowOrigins.clear();
            capturedWindowValid = false;
        }

        pollFullGeometryBuild(renderDistanceChunks);
        pollDirtyGeometryMerge(renderDistanceChunks);
        if (!smokeTestStarted) {
            return;
        }
        // Once the frozen full snapshot is published, do not start a dirty merge or move the
        // capture window in the same frame. The before-hand seam must get one stable chance to
        // build its BLAS/TLAS and present it. All events received meanwhile remain in the queues.
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
            if (smokeGeometry != null && !fullCaptureRequested && !pendingDirtySections.isEmpty()) {
                activeDirtySections = new LinkedHashSet<>();
                for (long origin : pendingDirtySections) {
                    activeDirtySections.add(origin);
                    if (activeDirtySections.size() >= SECTIONS_PER_TRANSACTION) {
                        break;
                    }
                }
                pendingDirtySections.removeAll(activeDirtySections);
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
            } else if (fullCaptureRequested || sceneDirty || smokeGeometry == null) {
                captureGeneration = sceneGeneration;
                partialCapture = false;
                // Consume the request for this session. A later chunk event sets it again;
                // the current snapshot is still allowed to finish and be rendered.
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
            boolean complete = captureSession.step(SECTIONS_PER_FRAME);
            if (complete) {
                RayTracingScene.SceneGeometry.CaptureSession finished = captureSession;
                boolean completedPartial = partialCapture;
                // Only a camera-window move makes this whole transaction unrelated. Independent
                // block/chunk events remain pending for a later transaction; invalidating this
                // completed batch on every new event causes starvation during chunk streaming.
                boolean cameraMovedDuringCapture = completedPartial
                    && (cameraChunkX(camera) != activeWindowChunkX
                        || cameraChunkZ(camera) != activeWindowChunkZ);
                if (cameraMovedDuringCapture) {
                    sceneGeneration++;
                    sceneDirty = true;
                }
                boolean stalePartialCapture = completedPartial && cameraMovedDuringCapture;
                if (completedPartial && !stalePartialCapture
                        && smokeGeometry != null && activeDirtySections != null) {
                    // CaptureSession is complete and will no longer be mutated. Keep it alive on
                    // the single geometry worker until buildPartial() and replaceSections() have
                    // produced the immutable replacement. This keeps LightTree construction off
                    // the render thread as well as the final array flattening.
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
                    // A stale partial capture is not handed to the worker. No caller can use the
                    // session after this render callback, so it is safe to retire it here.
                    finished.close();
                }
                captureSession = null;
                activeDirtySections = null;
                partialCapture = false;
                if (completedPartial) {
                    if (stalePartialCapture) {
                        // Updates arriving during this session remain pending for the next frame;
                        // do not publish the old delta or advance the published window.
                        sceneDirty = true;
                        LOGGER.info("RTest discarded stale dirty-section update for capture generation {} (scene generation {})",
                            captureGeneration, sceneGeneration);
                    } else {
                        // The old smokeGeometry remains the render input until the worker result
                        // is observed at the start of a later render callback.
                        sceneDirty = true;
                        LOGGER.info("RTest queued dirty-section CPU merge for the geometry worker");
                    }
                } else {
                    // Chunk loads may have arrived while this snapshot was being built.
                    // Keep the request for the next full pass, but publish this snapshot now
                    // so RT can render instead of restarting capture every frame.
                    boolean recaptureRequested = fullCaptureRequested;
                    fullCaptureRequested = recaptureRequested;
                    sceneDirty = recaptureRequested
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

        if (smokeGeometry == null) {
            return;
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
            boolean replayed = RayTracingSmokeTest.replayLastFrame(vulkanDevice, target, blockAtlas, smokeGeometry);
            VanillaRenderController.INSTANCE.markRtResult(replayed);
            return;
        }
        finishDeferredEntityCapture();
        boolean rtPresented = RayTracingSmokeTest.run(
            vulkanDevice,
            target,
            smokeGeometry,
            minecraft.level,
            camera,
            blockAtlas,
            minecraft.getResourceManager(),
            pbrMaterials,
            lastEntityFrame
        );
        VanillaRenderController.INSTANCE.markRtResult(rtPresented);
        if (rtPresented) {
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
            stopSmokeTestResources();
            if (minecraft.player != null) {
                minecraft.gui.hud.setOverlayMessage(Component.translatable("overlay.rtest.smokeTest.failed"), false);
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
            DynamicEntityGeometry.Frame captured =
                dynamicEntities.collect(minecraft.level, camera, renderDistanceChunks, partialTick);
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
            if (!dynamicSummary.equals(lastDynamicSummary)) {
                lastDynamicSummary = dynamicSummary;
                LOGGER.info("RTest dynamic CPU snapshot: {}", dynamicSummary);
            }
        } else if (lastEntityFrame != null) {
            ItemModelGeometryAdapter.clear();
            LivingEntityGeometryAdapter.clear();
            ParticleGeometryAdapter.clear();
            BlockEntityModelGeometryAdapter.clear();
            dynamicEntities.clear();
            lastEntityFrame = dynamicEntities.collect(minecraft.level, camera, renderDistanceChunks, 0.0F);
            lastDynamicSummary = null;
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
        if (smokeTestStarted && isWithinCaptureWindow(
                new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4))) {
            pendingDirtySections.add(origin.asLong());
            pendingCaptureSections.add(origin.asLong());
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
        boolean windowMoved = currentChunkX != capturedWindowChunkX
            || currentChunkZ != capturedWindowChunkZ;
        // A dirty event can arrive for a section that is being evicted while the incremental
        // session is in flight. Reconcile pending updates too, otherwise that section could be
        // captured again and accidentally reintroduced outside the current view-distance window.
        Set<Long> currentOrigins = new LinkedHashSet<>(capturedWindowOrigins);
        Set<Long> desiredOrigins = new LinkedHashSet<>();
        for (BlockPos origin : RayTracingScene.SceneGeometry.requestedSectionOrigins(
                level, camera, renderDistanceChunks)) {
            desiredOrigins.add(origin.asLong());
        }
        // Block callbacks can arrive for far-away loaded chunks. They do not belong to the
        // current RT scene and must not accumulate until the player returns there; the camera
        // delta will request the current contents when that window is entered.
        pendingDirtySections.retainAll(desiredOrigins);
        pendingCaptureSections.retainAll(desiredOrigins);
        if (!windowMoved && pendingDirtySections.isEmpty()) {
            return;
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
        // Full flattening, PBR packing and RayTracingLightTree construction are all CPU work;
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
        // complete immutable scene; no partially flattened arrays can escape to the frame.
        smokeGeometry = completedBuild.geometry();
        capturedWindowOrigins.clear();
        capturedWindowOrigins.addAll(completedBuild.windowOrigins());
        capturedWindowChunkX = completedBuild.windowChunkX();
        capturedWindowChunkZ = completedBuild.windowChunkZ();
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
        boolean currentSnapshot = smokeGeometry != null
            && smokeGeometry.renderDistanceChunks == renderDistanceChunks
            && completedMerge.generation() == sceneGeneration
            && completedMerge.level() == capturedLevel
            && completedMerge.level() == Minecraft.getInstance().level
            && !fullCaptureRequested;
        if (!currentSnapshot) {
            // Dirty notifications, world changes, window changes and full recapture requests all
            // advance the generation. Re-queue the transaction that was captured by the worker so
            // an invalidated result can never be published as the current world snapshot.
            pendingDirtySections.addAll(completedMerge.dirtySections());
            pendingCaptureSections.addAll(completedMerge.dirtySections());
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
        capturedWindowValid = true;
        partialCaptureCount++;
        sceneDirty = fullCaptureRequested || !pendingDirtySections.isEmpty();
        LOGGER.info("RTest applied dirty-section update; scene now has {} triangles (partialCaptures={}, fullCaptures={})",
            smokeGeometry.triangleCount(), partialCaptureCount, fullCaptureCount);
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
        sceneGeneration++;
        smokeGeometry = null;
        pendingDirtySections.clear();
        pendingCaptureSections.clear();
        activeDirtySections = null;
        capturedWindowOrigins.clear();
        capturedWindowValid = false;
        lastEntityFrame = null;
        lastDynamicSummary = null;
        capturedLevel = null;
        RayTracingScene.SceneGeometry.CaptureSession session = captureSession;
        captureSession = null;
        CompletableFuture<DirtyGeometryMerge> dirtyMerge = pendingGeometryMerge;
        pendingGeometryMerge = null;
        CompletableFuture<FullGeometryBuild> fullBuild = pendingFullGeometryBuild;
        pendingFullGeometryBuild = null;
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

        if (ClientKeyMappings.OPEN_RAY_TRACING_SETTINGS.consumeClick()) {
            minecraft.gui.setScreen(new RayTracingSettingsScreen(minecraft.gui.screen()));
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
