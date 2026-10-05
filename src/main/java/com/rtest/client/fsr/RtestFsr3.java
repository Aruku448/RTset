package com.rtest.client.fsr;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.logging.LogUtils;
import com.rtest.client.HdrSupport;
import com.rtest.client.RayTracingClientConfig;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;

/** Owns FSR 3 input/history images and records the complete temporal upscaler chain. */
public final class RtestFsr3 implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int COMMON_USAGE = VK12.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    private static final int DISPLAY_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private final RtestVulkanContext context;
    private final RtestVulkanImage sceneColor;
    private final RtestVulkanImage motion;
    private final RtestVulkanImage depth;
    private final RtestVulkanImage terrainHiZ;
    private final RtestVulkanImage reactive;
    private final RtestVulkanImage transparency;
    private final RtestVulkanImage displayOutput;
    private final RtestVulkanImage physicalAerialL;
    private final RtestFsr3Upscaler upscaler;
    private final NrdDenoiser nrd;
    private final AerialPerspectiveComposite aerialComposite;
    private RtestFsrCamera currentCamera;
    private boolean rasterInput;
    private long currentSceneRevision;
    private long currentAtlasView;
    private long currentAtlasSampler;
    private float currentSunDirectionX;
    private float currentSunDirectionY = 1.0F;
    private float currentSunDirectionZ;
    private NrdDenoiser.FrameToken nrdToken;
    private boolean nrdWasEnabled;
    private boolean denoiserModeLogged;
    private RtestDenoiserMode frameDenoiserMode = RtestDenoiserMode.OFF;
    private float frameNrdStrength;
    private boolean guideDescriptorsInitialized;
    private boolean inputsInitialized;
    private boolean closed;

    private RtestFsr3(RtestVulkanContext context, RtestVulkanImage sceneColor,
                     RtestVulkanImage motion, RtestVulkanImage depth,
                     RtestVulkanImage terrainHiZ, RtestVulkanImage reactive, RtestVulkanImage transparency,
                     RtestVulkanImage displayOutput, RtestVulkanImage physicalAerialL,
                     RtestFsr3Upscaler upscaler, NrdDenoiser nrd,
                     AerialPerspectiveComposite aerialComposite) {
        this.context = context;
        this.sceneColor = sceneColor;
        this.motion = motion;
        this.depth = depth;
        this.terrainHiZ = terrainHiZ;
        this.reactive = reactive;
        this.transparency = transparency;
        this.displayOutput = displayOutput;
        this.physicalAerialL = physicalAerialL;
        this.upscaler = upscaler;
        this.nrd = nrd;
        this.aerialComposite = aerialComposite;
    }

    public static RtestFsr3 create(VulkanDevice device, int renderWidth, int renderHeight,
                                   int displayWidth, int displayHeight,
                                   RtestFsrQualityMode qualityMode) {
        RtestVulkanContext context = new RtestVulkanContext(device);
        List<RtestVulkanImage> created = new ArrayList<>();
        NrdDenoiser nrd = null;
        AerialPerspectiveComposite aerialComposite = null;
        try {
            RtestVulkanImage scene = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, COMMON_USAGE, "RTest FSR scene color"));
            RtestVulkanImage motion = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16_SFLOAT, COMMON_USAGE, "RTest FSR motion"));
            RtestVulkanImage depth = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R32_SFLOAT, COMMON_USAGE, "RTest FSR depth"));
            int terrainHiZLevels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(renderWidth, renderHeight)));
            RtestVulkanImage terrainHiZ = own(created, context.createMipmappedImage2D(
                    renderWidth, renderHeight, terrainHiZLevels, VK12.VK_FORMAT_R32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT
                        | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "RTest terrain Hi-Z"));
            RtestVulkanImage reactive = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R8G8B8A8_UNORM, COMMON_USAGE, "RTest FSR reactive mask"));
            RtestVulkanImage transparency = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R8G8B8A8_UNORM, COMMON_USAGE, "RTest FSR transparency mask"));
            int displayFormat = HdrSupport.isActive()
                    ? VK12.VK_FORMAT_R16G16B16A16_SFLOAT
                    : VK12.VK_FORMAT_R8G8B8A8_UNORM;
            RtestVulkanImage display = own(created, context.createImage2D(displayWidth, displayHeight,
                    displayFormat, DISPLAY_USAGE, "RTest FSR display output"));
            nrd = NrdDenoiser.create(context, renderWidth, renderHeight, scene, motion);
            RtestVulkanImage physicalAerialL = own(created, context.createImage2D(
                    renderWidth, renderHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, COMMON_USAGE,
                    "RTest physical aerial L"));
            aerialComposite = AerialPerspectiveComposite.create(
                    context, scene, depth, physicalAerialL);
            RtestFsr3Upscaler upscaler = RtestFsr3Upscaler.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight,
                    qualityMode, scene, motion, depth, reactive, transparency, display);
            return new RtestFsr3(context, scene, motion, depth, terrainHiZ, reactive, transparency,
                    display, physicalAerialL, upscaler, nrd, aerialComposite);
        } catch (RuntimeException | Error exception) {
            if (aerialComposite != null) {
                try {
                    aerialComposite.destroy();
                } catch (Throwable cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (nrd != null) {
                try {
                    nrd.destroy();
                } catch (Throwable cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).destroy();
                } catch (Throwable cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    private static RtestVulkanImage own(List<RtestVulkanImage> images, RtestVulkanImage image) {
        images.add(image);
        return image;
    }

    public int renderWidth() {
        return this.sceneColor.width();
    }

    public int renderHeight() {
        return this.sceneColor.height();
    }

    public long sceneColorImage() {
        return this.sceneColor.image();
    }

    public long sceneColorView() {
        return this.sceneColor.view();
    }

    public long motionImage() { return this.motion.image(); }
    public long reactiveImage() { return this.reactive.image(); }
    public long transparencyImage() { return this.transparency.image(); }

    public long motionView() {
        return this.motion.view();
    }

    public long depthView() {
        return this.depth.view();
    }

    /** RayGen writes the physical solar in-scatter here; the aerial composite adds it post-NRD. */
    public long physicalAerialLView() {
        return this.physicalAerialL.view();
    }

    public RtestVulkanImage physicalAerialL() {
        return this.physicalAerialL;
    }

    /** Image handle used by optional compute passes that read the previous depth history. */
    public long depthImage() {
        return this.depth.image();
    }

    public long terrainHiZImage() {
        return this.terrainHiZ.image();
    }

    public long terrainHiZView() {
        return this.terrainHiZ.view();
    }

    public long terrainHiZMipView(int level) {
        return this.terrainHiZ.mipView(level);
    }

    public int terrainHiZMipLevels() {
        return this.terrainHiZ.mipLevels();
    }

    public long terrainHiZWidth() {
        return this.terrainHiZ.width();
    }

    public long terrainHiZHeight() {
        return this.terrainHiZ.height();
    }

    public long reactiveView() {
        return this.reactive.view();
    }

    public long transparencyView() {
        return this.transparency.view();
    }

    public long displayImage() {
        return this.displayOutput.image();
    }

    public RtestFsr3Upscaler.FrameToken beginFrame(RtestFsrCamera camera, long sceneRevision,
                                                    long atlasView, long atlasSampler) {
        // Resolve once before camera upload. RayGen and post-processing must agree
        // on whether this frame produces guides, including zero-strength toggles.
        this.frameNrdStrength = RayTracingClientConfig.INSTANCE.nrdStrength.get().floatValue();
        RtestDenoiserMode nextDenoiserMode = RtestDenoiserMode.select(
            !this.rasterInput && RayTracingClientConfig.INSTANCE.nrdEnabled.get(), this.frameNrdStrength);
        if (nextDenoiserMode != this.frameDenoiserMode) {
            // FSR must not blend previous raw/other-denoiser output into this mode's history.
            // Request before beginFrame snapshots the reset bit; stable modes do not reset.
            this.upscaler.requestReset();
        }
        this.frameDenoiserMode = nextDenoiserMode;
        this.currentCamera = camera;
        this.currentSceneRevision = sceneRevision;
        this.currentAtlasView = atlasView;
        this.currentAtlasSampler = atlasSampler;
        return this.upscaler.beginFrame(camera, sceneRevision, atlasView, atlasSampler);
    }

    /** Invalidates FSR and the coupled NRD temporal histories before the next RT submission. */
    public void requestReset() {
        this.upscaler.requestReset();
    }

    /** Supplies the same world-space sun direction used by RayGen to NRD history validation. */
    public void setSunDirection(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Sun direction must be finite");
        }
        this.currentSunDirectionX = x;
        this.currentSunDirectionY = y;
        this.currentSunDirectionZ = z;
    }

    public RtestDenoiserMode denoiserMode() {
        return this.frameDenoiserMode;
    }

    public NrdDenoiser nrd() {
        return this.nrd;
    }

    /** Must be recorded before the ray-tracing dispatch that writes the current inputs. */
    public void prepareForRayTracing(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.inputsInitialized) {
                VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(7, stack);
                RtestVulkanImage[] images = {this.sceneColor, this.motion, this.depth,
                        this.reactive, this.transparency, this.displayOutput, this.physicalAerialL};
                for (int index = 0; index < images.length; index++) {
                    barriers.get(index).sType$Default()
                            .srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                            .srcAccessMask(0L)
                            .dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                                    | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                            .dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR
                                    | VK12.VK_ACCESS_SHADER_READ_BIT
                                    | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                            .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .image(images[index].image());
                    barriers.get(index).subresourceRange(new VkImageSubresourceRange(stack.malloc(
                            VkImageSubresourceRange.SIZEOF))
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));
                }
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                        VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
                this.inputsInitialized = true;
            } else {
                VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                        VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
            }
        }
        // Keep allocated guides/descriptors alive across mode switches, but do not
        // synchronize images that neither RayGen nor a denoiser uses in this frame.
        if (this.frameDenoiserMode.needsGuides() || !this.guideDescriptorsInitialized) {
            // The uniform branch does not change static descriptor usage. Put even
            // disabled guide bindings into their declared GENERAL layout once.
            this.nrd.prepareForRayTrace(commandBuffer);
            this.guideDescriptorsInitialized = true;
        }
    }

    public void setRasterInput(boolean rasterInput) { this.rasterInput = rasterInput; }

    public void prepareForRasterDisplay(VkCommandBuffer commandBuffer) {
        prepareForRayTracing(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                .srcAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT | VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                .dstAccessMask(VK12.VK_ACCESS_TRANSFER_READ_BIT | VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    /** Raster inputs are fresh display samples; a reused world field is never a new NRD observation. */
    public void recordAfterRasterDisplay(VkCommandBuffer commandBuffer, RtestFsr3Upscaler.FrameToken token) {
        if (this.nrdToken != null) { this.nrd.cancel(this.nrdToken); this.nrdToken = null; }
        this.nrdWasEnabled = false;
        computeReadWriteBarrier(commandBuffer);
        this.upscaler.record(commandBuffer, token);
    }

    public void recordAfterRayTracing(VkCommandBuffer commandBuffer, RtestFsr3Upscaler.FrameToken token,
                                      boolean aerialPerspectiveEnabled) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                    .srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
        float nrdStrength = this.frameNrdStrength;
        boolean nrdEnabled = this.frameDenoiserMode == RtestDenoiserMode.NRD;
        if (!this.denoiserModeLogged || nrdEnabled != this.nrdWasEnabled) {
            LOGGER.info("RTest denoiser scheduling: NRD={}, strength={}, guides={}",
                    nrdEnabled, nrdStrength,
                    this.frameDenoiserMode.needsGuides());
            this.denoiserModeLogged = true;
        }
        if (this.nrdToken != null) {
            this.nrd.cancel(this.nrdToken);
        }
        this.nrdToken = null;
        try {
            // FSR reset/camera-cut covers scene revision and atlas identity. NRD owns its
            // angular sun threshold; bit-exact sun resets would prevent temporal convergence.
            boolean forceRestart = token.reset() || token.cameraCut();
            if (nrdEnabled) {
                this.nrd.prepareForDenoise(commandBuffer);
                this.nrdToken = this.nrd.record(commandBuffer, this.currentCamera,
                        this.currentSceneRevision, this.currentAtlasView, this.currentAtlasSampler,
                        this.currentSunDirectionX, this.currentSunDirectionY, this.currentSunDirectionZ,
                        token.jitter().x(), token.jitter().y(),
                        forceRestart || !this.nrdWasEnabled, nrdStrength);
            }
            this.nrdWasEnabled = nrdEnabled;
            // Both NRD and raw RT share this path. Preserve compute RAW/WAR/WAW edges
            // from NRD's composite to aerial and from aerial to FSR; binding is not a barrier.
            if (aerialPerspectiveEnabled) {
                computeReadWriteBarrier(commandBuffer);
                this.aerialComposite.record(commandBuffer, this.renderWidth(), this.renderHeight(), true);
                computeReadWriteBarrier(commandBuffer);
            }
            this.upscaler.record(commandBuffer, token);
        } catch (Throwable failure) {
            if (this.nrdToken != null) {
                try {
                    this.nrd.cancel(this.nrdToken);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                this.nrdToken = null;
            }
            throw failure;
        }
    }

    private static void computeReadWriteBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    public void submitted(RtestFsr3Upscaler.FrameToken token) {
        this.upscaler.submitted(token);
        if (this.nrdToken != null) {
            this.nrd.submitted(this.nrdToken);
            this.nrdToken = null;
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.aerialComposite.destroy();
        this.upscaler.destroy();
        this.nrd.destroy();
        this.displayOutput.destroy();
        this.transparency.destroy();
        this.reactive.destroy();
        this.physicalAerialL.destroy();
        this.depth.destroy();
        this.terrainHiZ.destroy();
        this.motion.destroy();
        this.sceneColor.destroy();
    }
}
