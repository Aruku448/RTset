package com.rtest.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceManager;
import com.rtest.client.RayTracingScene.SceneGeometry;
import com.rtest.client.fsr.RtestFsr3;
import com.rtest.client.fsr.RtestFsrQualityMode;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Coordinates the RT pass without exposing Vulkan resource ownership to callers. */
public final class RayTracingSmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static RayTracingVulkanPass activeResources;
    private static long frameCounter;
    private static RayTracingVulkanPass.BlasCache blasCache = new RayTracingVulkanPass.BlasCache();
    private static RayTracingSkybox skybox;
    private static RtestFsr3 activeFsr;
    private static RtestFsrQualityMode activeFsrQuality;
    private static final RayTracingFrameTiming frameTiming = new RayTracingFrameTiming();

    private RayTracingSmokeTest() {
    }

    public static boolean hasPresentedFrame() {
        return activeResources != null && activeResources.hasPresentedFrame();
    }

    public static String reconstructionStatus() {
        if (activeFsr == null) return "FSR3";
        return switch (activeFsr.mode()) {
            case FSR -> com.rtest.client.fsr.DlssRuntime.requested() == com.rtest.client.fsr.RtestUpscalerMode.FSR
                ? "FSR3" : "FSR3 (DLSS fallback)";
            case DLSS -> "DLSS";
            case DLSS_RR -> "DLSS + RR";
        };
    }

    /** Keeps the last completed RT world visible during a paused GUI frame. */
    public static boolean replayLastFrame(
        VulkanDevice device, RenderTarget target, TextureAtlas blockAtlas, SceneGeometry geometry,
        boolean terrainTraversalEnabled) {
        if (!hasPresentedFrameFor(device, target, blockAtlas, geometry, terrainTraversalEnabled)) {
            return false;
        }
        activeResources.replayLastPresentationInCurrentFrame(frameTiming);
        return true;
    }

    /**
     * Cancellation guard for the LevelRenderer seam. A completed frame is reusable only when the
     * current target, FSR extent, atlas binding, sampler, format and Vulkan device still match.
     */
    public static boolean hasPresentedFrameFor(
        VulkanDevice device, RenderTarget target, TextureAtlas blockAtlas, SceneGeometry geometry,
        boolean terrainTraversalEnabled) {
        if (activeResources == null || activeFsr == null || !activeResources.hasPresentedFrame()
            || !activeResources.usesGeometry(geometry)
            || target == null || blockAtlas == null) {
            return false;
        }
        if (!(target.getColorTexture() instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture texture)
            || !(target.getColorTextureView() instanceof VulkanGpuTextureView targetView)
            || !(blockAtlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
            || !(blockAtlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
            return false;
        }
        RtestFsrQualityMode quality = RtestFsrQualityMode.fromId(
            RayTracingClientConfig.INSTANCE.fsrQuality.get());
        RtestFsrQualityMode.Extent extent = com.rtest.client.fsr.DlssRuntime.renderExtent(device, quality, target.width, target.height);
        return activeFsrQuality == quality && activeFsr.mode() == com.rtest.client.fsr.DlssRuntime.effective(device) && activeResources.matches(
            device, extent.width(), extent.height(), target.width, target.height,
            texture.vkImage(), targetView.vkImageView(), target.getColorTexture().getFormat(),
            geometry, atlasView.vkImageView(), atlasSampler.vkSampler(), activeFsr,
            terrainTraversalEnabled);
    }

    public static boolean run(
        VulkanDevice device,
        RenderTarget target,
        SceneGeometry geometry,
        ClientLevel level,
        Camera camera,
        TextureAtlas blockAtlas,
        ResourceManager resourceManager,
        RayTracingPbrMaterials pbrMaterials,
        DynamicEntityGeometry.Frame dynamicFrame,
        boolean terrainTraversalEnabled
    ) {
        long start = System.nanoTime();
        long frame = ++frameCounter;
        RayTracingFrameTiming timing = frameTiming;
        timing.reset();
        int centerPixel = 0;
        boolean rendered = false;
        boolean resourcesWereMatch = false;
        try {
            GpuTexture colorTexture = target.getColorTexture();
            if (!(colorTexture instanceof com.mojang.blaze3d.vulkan.VulkanGpuTexture vulkanTexture)
                || !(target.getColorTextureView() instanceof VulkanGpuTextureView targetView)) {
                throw new IllegalStateException("The active render target is not a Vulkan texture view");
            }
            if (!(blockAtlas.getTextureView() instanceof VulkanGpuTextureView atlasView)
                || !(blockAtlas.getSampler() instanceof VulkanGpuSampler atlasSampler)) {
                throw new IllegalStateException("The block texture atlas is not a Vulkan texture");
            }

            if (skybox == null) {
                skybox = RayTracingSkybox.create(device, resourceManager);
            }
            RtestFsrQualityMode fsrQuality = RtestFsrQualityMode.fromId(
                RayTracingClientConfig.INSTANCE.fsrQuality.get());
            RtestFsrQualityMode.Extent renderExtent = com.rtest.client.fsr.DlssRuntime.renderExtent(device, fsrQuality, target.width, target.height);
            long resourceMatchStart = System.nanoTime();
            boolean resourcesMatch = activeResources != null && activeFsr != null
                && activeFsrQuality == fsrQuality
                && activeFsr.mode() == com.rtest.client.fsr.DlssRuntime.effective(device)
                && activeResources.matches(
                    device,
                    renderExtent.width(),
                    renderExtent.height(),
                    target.width,
                    target.height,
                    vulkanTexture.vkImage(),
                    targetView.vkImageView(),
                    colorTexture.getFormat(),
                    geometry,
                    atlasView.vkImageView(),
                    atlasSampler.vkSampler(),
                    activeFsr,
                    terrainTraversalEnabled
                );
            resourcesWereMatch = resourcesMatch;
            timing.add(RayTracingFrameTiming.Segment.RESOURCE_MATCH, resourceMatchStart);
            if (!resourcesMatch) {
                long resourceRebuildStart = System.nanoTime();
                try {
                    closeActivePass();
                    activeFsr = RtestFsr3.create(
                        device,
                        renderExtent.width(),
                        renderExtent.height(),
                        target.width,
                        target.height,
                        fsrQuality
                    );
                    activeFsrQuality = fsrQuality;
                    activeResources = RayTracingVulkanPass.create(
                        device,
                        renderExtent.width(),
                        renderExtent.height(),
                        target.width,
                        target.height,
                        vulkanTexture.vkImage(),
                        targetView.vkImageView(),
                        colorTexture.getFormat(),
                        geometry,
                        atlasView.vkImageView(),
                        atlasSampler.vkSampler(),
                        skybox,
                        pbrMaterials,
                        blasCache,
                        activeFsr,
                        dynamicFrame,
                        terrainTraversalEnabled
                    );
                } finally {
                    timing.add(RayTracingFrameTiming.Segment.RESOURCE_REBUILD, resourceRebuildStart);
                }
            } else if (!activeResources.usesGeometry(geometry)) {
                long geometryUpdateStart = System.nanoTime();
                try {
                    activeResources.updateGeometry(geometry, blasCache);
                } finally {
                    timing.add(RayTracingFrameTiming.Segment.GEOMETRY_UPDATE, geometryUpdateStart);
                }
            }

            centerPixel = activeResources.dispatch(level, camera, dynamicFrame);
            rendered = true;
            return true;
        } catch (Throwable throwable) {
            LOGGER.error("RTest Vulkan ray-tracing smoke test failed", throwable);
            // Device loss is fatal to Minecraft's Vulkan device. Replaying an image or
            // returning to vanilla would submit more work to the same failed device.
            if (throwable instanceof com.mojang.blaze3d.GpuDeviceLossException deviceLoss) {
                RtResourceRollback.attempt(deviceLoss, RayTracingSmokeTest::close);
                throw deviceLoss;
            }
            // If the world was cancelled using a matching, already-presented pass, copy that
            // completed image before teardown. The controller disables cancellation next frame,
            // while this frame still has a visual RT fallback instead of a black target.
            if (resourcesWereMatch && activeResources != null && activeResources.hasPresentedFrame()) {
                try {
                    activeResources.replayLastPresentation(timing);
                } catch (Throwable replayFailure) {
                    throwable.addSuppressed(replayFailure);
                }
            }
            RtResourceRollback.attempt(throwable, RayTracingSmokeTest::close);
            return false;
        } finally {
            long durationMillis = (System.nanoTime() - start) / 1_000_000L;
            timing.log(LOGGER, "smoke_run_cpu", frame, rendered);
            if (rendered && frame % 120L == 0L) {
                LOGGER.info(
                    "RTest Vulkan RT + FSR3 rendered {}x{} -> {}x{} (centerPixel=0x{}, duration={} ms)",
                    activeResources.outputWidth(),
                    activeResources.outputHeight(),
                    target.width,
                    target.height,
                    Integer.toHexString(centerPixel),
                    durationMillis
                );
            }
        }
    }

    public static void close() {
        RayTracingVulkanPass.clearRepresentedBlockEntities();
        // Keep cleanup progressing if one native close reports a device-loss/runtime failure.
        // The pass must be closed before shared BLAS and skybox objects, but each later owner
        // still gets a chance to release its handles.
        try {
            closeActivePass();
        } finally {
            RayTracingSkybox currentSkybox = skybox;
            skybox = null;
            try {
                if (currentSkybox != null) {
                    currentSkybox.close();
                }
            } finally {
                try {
                    blasCache.close();
                } finally {
                    blasCache = new RayTracingVulkanPass.BlasCache();
                    frameCounter = 0L;
                }
            }
        }
    }

    private static void closeActivePass() {
        RayTracingVulkanPass resources = activeResources;
        activeResources = null;
        try {
            if (resources != null) {
                resources.close();
            }
        } finally {
            RtestFsr3 fsr = activeFsr;
            activeFsr = null;
            activeFsrQuality = null;
            if (fsr != null) {
                fsr.close();
            }
        }
    }

}
