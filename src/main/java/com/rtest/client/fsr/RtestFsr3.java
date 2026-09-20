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
            | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int DISPLAY_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

    private final RtestVulkanContext context;
    private final RtestVulkanImage sceneColor;
    private final RtestVulkanImage motion;
    private final RtestVulkanImage depth;
    private final RtestVulkanImage reactive;
    private final RtestVulkanImage transparency;
    private final RtestVulkanImage displayOutput;
    private final RtestFsr3Upscaler upscaler;
    private final NrdDenoiser nrd;
    private final SundialDenoiser sundial;
    private RtestFsrCamera currentCamera;
    private long currentSceneRevision;
    private long currentAtlasView;
    private long currentAtlasSampler;
    private float currentSunDirectionX;
    private float currentSunDirectionY = 1.0F;
    private float currentSunDirectionZ;
    private NrdDenoiser.FrameToken nrdToken;
    private SundialDenoiser.FrameToken sundialToken;
    private boolean nrdWasEnabled;
    private boolean sundialWasEnabled;
    private boolean denoiserModeLogged;
    private boolean sundialHistoryReset;
    private float previousSundialSunX = Float.NaN;
    private float previousSundialSunY = Float.NaN;
    private float previousSundialSunZ = Float.NaN;
    private boolean inputsInitialized;
    private boolean closed;

    private RtestFsr3(RtestVulkanContext context, RtestVulkanImage sceneColor,
                     RtestVulkanImage motion, RtestVulkanImage depth,
                     RtestVulkanImage reactive, RtestVulkanImage transparency,
                     RtestVulkanImage displayOutput, RtestFsr3Upscaler upscaler,
                     NrdDenoiser nrd, SundialDenoiser sundial) {
        this.context = context;
        this.sceneColor = sceneColor;
        this.motion = motion;
        this.depth = depth;
        this.reactive = reactive;
        this.transparency = transparency;
        this.displayOutput = displayOutput;
        this.upscaler = upscaler;
        this.nrd = nrd;
        this.sundial = sundial;
    }

    public static RtestFsr3 create(VulkanDevice device, int renderWidth, int renderHeight,
                                   int displayWidth, int displayHeight,
                                   RtestFsrQualityMode qualityMode) {
        RtestVulkanContext context = new RtestVulkanContext(device);
        List<RtestVulkanImage> created = new ArrayList<>();
        NrdDenoiser nrd = null;
        SundialDenoiser sundial = null;
        try {
            RtestVulkanImage scene = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16B16A16_SFLOAT, COMMON_USAGE, "RTest FSR scene color"));
            RtestVulkanImage motion = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R16G16_SFLOAT, COMMON_USAGE, "RTest FSR motion"));
            RtestVulkanImage depth = own(created, context.createImage2D(renderWidth, renderHeight,
                    VK12.VK_FORMAT_R32_SFLOAT, COMMON_USAGE, "RTest FSR depth"));
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
            sundial = SundialDenoiser.create(
                    context, renderWidth, renderHeight,
                    nrd.noisyDiffuseView(), nrd.noisySpecularView(),
                    nrd.normalRoughnessView(), nrd.viewZView(), nrd.motionView(),
                    nrd.directDiffuseView(), nrd.emissionView(), scene);
            RtestFsr3Upscaler upscaler = RtestFsr3Upscaler.create(
                    context, renderWidth, renderHeight, displayWidth, displayHeight,
                    qualityMode, scene, motion, depth, reactive, transparency, display);
            return new RtestFsr3(context, scene, motion, depth, reactive, transparency,
                    display, upscaler, nrd, sundial);
        } catch (RuntimeException | Error exception) {
            if (sundial != null) {
                try {
                    sundial.destroy();
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

    public long motionView() {
        return this.motion.view();
    }

    public long depthView() {
        return this.depth.view();
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
        this.sundialHistoryReset = this.sundialHistoryReset
            || Float.floatToIntBits(x) != Float.floatToIntBits(this.previousSundialSunX)
            || Float.floatToIntBits(y) != Float.floatToIntBits(this.previousSundialSunY)
            || Float.floatToIntBits(z) != Float.floatToIntBits(this.previousSundialSunZ);
        this.previousSundialSunX = x;
        this.previousSundialSunY = y;
        this.previousSundialSunZ = z;
        this.currentSunDirectionX = x;
        this.currentSunDirectionY = y;
        this.currentSunDirectionZ = z;
    }

    public NrdDenoiser nrd() {
        return this.nrd;
    }

    /** Must be recorded before the ray-tracing dispatch that writes the current inputs. */
    public void prepareForRayTracing(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.inputsInitialized) {
                VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(6, stack);
                RtestVulkanImage[] images = {this.sceneColor, this.motion, this.depth,
                        this.reactive, this.transparency, this.displayOutput};
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
        // RayGen always writes the NRD input AOVs, so their lifetime/layout must remain valid
        // even while NRD is disabled. Sundial owns independent history images; do not inject
        // barriers for them unless that denoiser is actually scheduled this frame.
        this.nrd.prepareForRayTrace(commandBuffer);
        if (RayTracingClientConfig.INSTANCE.sundialDenoiserEnabled.get()
                && RayTracingClientConfig.INSTANCE.sundialDenoiserStrength.get().floatValue() > 0.0001f) {
            this.sundial.prepareForRayTrace(commandBuffer);
        }
    }

    public void recordAfterRayTracing(VkCommandBuffer commandBuffer, RtestFsr3Upscaler.FrameToken token) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                    .srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
        float sundialStrength = RayTracingClientConfig.INSTANCE.sundialDenoiserStrength.get().floatValue();
        boolean sundialEnabled = RayTracingClientConfig.INSTANCE.sundialDenoiserEnabled.get()
                && sundialStrength > 0.0001f;
        float nrdStrength = RayTracingClientConfig.INSTANCE.nrdStrength.get().floatValue();
        boolean nrdEnabled = !sundialEnabled
                && RayTracingClientConfig.INSTANCE.nrdEnabled.get()
                && nrdStrength > 0.0001f;
        if (!this.denoiserModeLogged || nrdEnabled != this.nrdWasEnabled
                || sundialEnabled != this.sundialWasEnabled) {
            LOGGER.info("RTest denoiser scheduling: NRD={}, strength={}, Sundial={}, strength={}",
                    nrdEnabled, nrdStrength, sundialEnabled, sundialStrength);
            this.denoiserModeLogged = true;
        }
        if (this.nrdToken != null) {
            this.nrd.cancel(this.nrdToken);
        }
        if (this.sundialToken != null) {
            this.sundial.cancel(this.sundialToken);
        }
        this.nrdToken = null;
        this.sundialToken = null;
        this.sundial.cancelPending();
        try {
            // FSR reset/camera-cut already covers scene revision and atlas identity. Sundial
            // additionally invalidates when the sun direction changes, matching NRD's history key.
            boolean forceRestart = token.reset() || token.cameraCut() || this.sundialHistoryReset;
            if (sundialEnabled) {
                this.sundialToken = this.sundial.record(commandBuffer,
                        forceRestart || !this.sundialWasEnabled,
                        sundialStrength,
                        RayTracingClientConfig.INSTANCE.sundialDenoiserHistory.get());
            } else if (nrdEnabled) {
                this.nrd.prepareForDenoise(commandBuffer);
                this.nrdToken = this.nrd.record(commandBuffer, this.currentCamera,
                        this.currentSceneRevision, this.currentAtlasView, this.currentAtlasSampler,
                        this.currentSunDirectionX, this.currentSunDirectionY, this.currentSunDirectionZ,
                        token.jitter().x(), token.jitter().y(),
                        forceRestart || !this.nrdWasEnabled, nrdStrength);
            }
            this.sundialWasEnabled = sundialEnabled;
            this.nrdWasEnabled = nrdEnabled;
            this.sundialHistoryReset = false;
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
            if (this.sundialToken != null) {
                try {
                    this.sundial.cancel(this.sundialToken);
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                this.sundialToken = null;
            }
            throw failure;
        }
    }

    public void submitted(RtestFsr3Upscaler.FrameToken token) {
        this.upscaler.submitted(token);
        if (this.nrdToken != null) {
            this.nrd.submitted(this.nrdToken);
            this.nrdToken = null;
        }
        if (this.sundialToken != null) {
            this.sundial.submitted(this.sundialToken);
            this.sundialToken = null;
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.upscaler.destroy();
        this.sundial.destroy();
        this.nrd.destroy();
        this.displayOutput.destroy();
        this.transparency.destroy();
        this.reactive.destroy();
        this.depth.destroy();
        this.motion.destroy();
        this.sceneColor.destroy();
    }
}
