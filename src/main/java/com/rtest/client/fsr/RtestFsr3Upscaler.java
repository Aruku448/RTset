package com.rtest.client.fsr;

import com.rtest.client.OfflineRenderController;
import com.rtest.client.PrimeRgbReinhardOutput;
import com.rtest.client.RayTracingClientConfig;

import com.mojang.blaze3d.vulkan.Destroyable;
import com.rtest.client.HdrSupport;
import com.rtest.client.fsr.RtestFsrCamera;

import com.rtest.client.fsr.RtestFsrDebugView;
import com.rtest.client.fsr.RtestFsrDispatchValidator;
import com.rtest.client.fsr.RtestFsrQualityMode;
import com.rtest.client.fsr.RtestFsrSettings;
import com.rtest.client.fsr.RtestVulkanBuffer;
import com.rtest.client.fsr.RtestVulkanContext;
import com.rtest.client.fsr.RtestVulkanImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Direct Vulkan realization of FidelityFX SDK 1.1.4's FSR 3.1 upscaler with the public 3.1.5
 * RCAS correction backported from SDK 2.0.
 *
 * <p>Only the eight platform-independent upscaler compute passes, including RCAS, are present.
 * RTest owns every resource and records every dispatch on Minecraft's Vulkan queue; there is no FidelityFX native
 * backend, swapchain proxy, optical flow, or frame-interpolation path. The input and output are
 * scene-referred linear Rec.2020 HDR. RTest's display transform is a separate final pass.
 */
public final class RtestFsr3Upscaler implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int MAIN_CONSTANT_SIZE = 148;
    private static final int SPD_CONSTANT_SIZE = 24;
    private static final int RCAS_CONSTANT_SIZE = 16;
    private static final float NEAR_PLANE = 0.05F;
    private static final int COMMON_IMAGE_USAGE = VK12.VK_IMAGE_USAGE_SAMPLED_BIT
            | VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static final int SAMPLED_CLEAR_USAGE =
            VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private final RtestVulkanContext context;
    private final int renderWidth;
    private final int renderHeight;
    private final int displayWidth;
    private final int displayHeight;
    private final RtestFsrQualityMode qualityMode;
    private final Resources resources;
    private final long pointSampler;
    private final long linearSampler;
    private final RtestVulkanBuffer mainConstants;
    private final RtestVulkanBuffer spdConstants;
    private final RtestVulkanBuffer rcasConstants;
    private final RtestVulkanBuffer lanczosUpload;
    private final Pass[] passes;
    private final Pass debugPass;
    private final Pass offlinePass;
    private final Pass displayPass;

    private RtestFsrSettings.Jitter previousJitter;
    private RtestFsrCamera previousCamera;
    private long previousSceneResetRevision = Long.MIN_VALUE;
    private long previousAtlasView;
    private long previousAtlasSampler;
    private int frameIndex;
    private long previousFrameNanos;
    private boolean resetRequested = true;
    private boolean initialized;
    private boolean destroyed;

    private RtestFsr3Upscaler(
            RtestVulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            RtestFsrQualityMode qualityMode,
            Resources resources,
            long pointSampler,
            long linearSampler,
            RtestVulkanBuffer mainConstants,
            RtestVulkanBuffer spdConstants,
            RtestVulkanBuffer rcasConstants,
            RtestVulkanBuffer lanczosUpload,
            Pass[] passes,
            Pass debugPass,
            Pass offlinePass,
            Pass displayPass) {
        this.context = context;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.displayWidth = displayWidth;
        this.displayHeight = displayHeight;
        this.qualityMode = Objects.requireNonNull(qualityMode, "qualityMode");
        this.previousJitter = this.qualityMode.jitter(0);
        this.resources = resources;
        this.pointSampler = pointSampler;
        this.linearSampler = linearSampler;
        this.mainConstants = mainConstants;
        this.spdConstants = spdConstants;
        this.rcasConstants = rcasConstants;
        this.lanczosUpload = lanczosUpload;
        this.passes = passes;
        this.debugPass = debugPass;
        this.offlinePass = offlinePass;
        this.displayPass = displayPass;
    }

    public static RtestFsr3Upscaler create(
            RtestVulkanContext context,
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            RtestFsrQualityMode qualityMode,
            RtestVulkanImage sceneColor,
            RtestVulkanImage inputMotion,
            RtestVulkanImage inputDepth,
            RtestVulkanImage reactiveMask,
            RtestVulkanImage transparencyCompositionMask,
            RtestVulkanImage displayOutput) {
        Resources resources = null;
        long pointSampler = 0L;
        long linearSampler = 0L;
        RtestVulkanBuffer mainConstants = null;
        RtestVulkanBuffer spdConstants = null;
        RtestVulkanBuffer rcasConstants = null;
        RtestVulkanBuffer lanczosUpload = null;
        ArrayList<Pass> createdPasses = new ArrayList<>();
        Pass debugPass = null;
        Pass offlinePass = null;
        Pass displayPass = null;
        try {
            resources = Resources.create(
                    context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sceneColor,
                    inputMotion,
                    inputDepth,
                    reactiveMask,
                    transparencyCompositionMask,
                    displayOutput);
            pointSampler = createSampler(context, false, "RTest FSR point-clamp sampler");
            linearSampler = createSampler(context, true, "RTest FSR linear-clamp sampler");
            mainConstants = context.createBuffer(
                    MAIN_CONSTANT_SIZE,
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "RTest FSR constants");
            spdConstants = context.createBuffer(
                    SPD_CONSTANT_SIZE,
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false,
                    "RTest FSR SPD constants");
            rcasConstants = context.createBuffer(
                    RCAS_CONSTANT_SIZE,
                    VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    true,
                    "RTest FSR RCAS constants");
            writeRcasConstants(rcasConstants);
            lanczosUpload = createLanczosUpload(context);

            boolean fp16 = false;
            for (PassSpec spec : upscalerPassSpecs(fp16)) {
                createdPasses.add(Pass.create(
                        context,
                        spec,
                        resources,
                        pointSampler,
                        linearSampler,
                        mainConstants,
                        spdConstants,
                        rcasConstants));
            }
            debugPass = Pass.create(
                    context,
                    debugPassSpec(fp16),
                    resources,
                    pointSampler,
                    linearSampler,
                    mainConstants,
                    spdConstants,
                    rcasConstants);
            offlinePass = Pass.create(
                    context,
                    offlinePassSpec(),
                    resources,
                    pointSampler,
                    linearSampler,
                    mainConstants,
                    spdConstants,
                    rcasConstants);
            displayPass = Pass.create(
                    context,
                    displayPassSpec(),
                    resources,
                    pointSampler,
                    linearSampler,
                    mainConstants,
                    spdConstants,
                    rcasConstants);
            return new RtestFsr3Upscaler(
                    context,
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    qualityMode,
                    resources,
                    pointSampler,
                    linearSampler,
                    mainConstants,
                    spdConstants,
                    rcasConstants,
                    lanczosUpload,
                    createdPasses.toArray(Pass[]::new),
                    debugPass,
                    offlinePass,
                    displayPass);
        } catch (RuntimeException | Error exception) {
            if (displayPass != null) {
                displayPass.destroy();
            }
            if (debugPass != null) {
                debugPass.destroy();
            }
            if (offlinePass != null) {
                offlinePass.destroy();
            }
            for (int index = createdPasses.size() - 1; index >= 0; index--) {
                createdPasses.get(index).destroy();
            }
            if (lanczosUpload != null) {
                lanczosUpload.destroy();
            }
            if (spdConstants != null) {
                spdConstants.destroy();
            }
            if (rcasConstants != null) {
                rcasConstants.destroy();
            }
            if (mainConstants != null) {
                mainConstants.destroy();
            }
            if (linearSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), linearSampler, null);
            }
            if (pointSampler != 0L) {
                VK12.vkDestroySampler(context.vkDevice(), pointSampler, null);
            }
            if (resources != null) {
                resources.destroy();
            }
            throw exception;
        }
    }

    public int frameIndex() {
        return this.frameIndex;
    }

    public RtestFsrSettings.Jitter jitter() {
        return this.qualityMode.jitter(this.frameIndex);
    }

    public void requestReset() {
        this.resetRequested = true;
    }

    public FrameToken beginFrame(
            RtestFsrCamera camera,
            long sceneResetRevision,
            long atlasView,
            long atlasSampler) {
        this.requireOpen();
        Objects.requireNonNull(camera, "camera");
        boolean cameraCut = this.initialized
                && RtestFsrCamera.isCut(this.previousCamera, camera);
        boolean reset = this.resetRequested
                || !this.initialized
                || cameraCut
                || sceneResetRevision != this.previousSceneResetRevision
                || atlasView != this.previousAtlasView
                || atlasSampler != this.previousAtlasSampler;
        int currentFrameIndex = reset ? 0 : this.frameIndex;
        RtestFsrSettings.Jitter jitter = this.qualityMode.jitter(currentFrameIndex);
        long now = System.nanoTime();
        float deltaSeconds = this.previousFrameNanos == 0L
                ? 1.0F / 60.0F
                : Math.min((now - this.previousFrameNanos) * 1.0e-9F, 1.0F);
        return new FrameToken(
                this,
                camera,
                sceneResetRevision,
                atlasView,
                atlasSampler,
                currentFrameIndex,
                jitter,
                reset ? jitter : this.previousJitter,
                reset,
                cameraCut,
                deltaSeconds,
                now);
    }

    public void record(VkCommandBuffer commandBuffer, FrameToken token) {
        this.requireOpen();
        if (token.owner != this || token.recorded || token.submitted) {
            throw new IllegalArgumentException("FSR frame token does not belong to this recording");
        }
        token.recorded = true;
        RtestFsrCamera camera = token.camera;
        boolean reset = token.reset;
        RtestFsrSettings.Jitter jitter = token.jitter;
        RtestFsrDispatchValidator.validate(
                this.renderWidth,
                this.renderHeight,
                this.displayWidth,
                this.displayHeight,
                jitter,
                RtestFsrSettings.EXPOSURE,
                1.0F,
                1.0F);

        // NRD's composite, motion and reversed-depth images are external inputs written by the
        // immediately preceding compute work. This dependency is separate from FSR's internal
        // transfer clears and must remain explicit on both the first and later frames.
        computeBarrier(commandBuffer);
        this.prepareResources(commandBuffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer main = createMainConstants(
                    stack,
                    camera,
                    jitter,
                    token.previousJitter,
                    token.frameIndex,
                    token.deltaSeconds);
            ByteBuffer spd = createSpdConstants(stack);
            VK12.vkCmdUpdateBuffer(
                    commandBuffer, this.mainConstants.handle(), 0L, main);
            VK12.vkCmdUpdateBuffer(
                    commandBuffer, this.spdConstants.handle(), 0L, spd);
        }

        this.clearPerFrameResources(commandBuffer, reset);
        transferToComputeBarrier(commandBuffer, this.mainConstants, this.spdConstants);

        int parity = token.frameIndex & 1;
        int sourceX = divideRoundUp(this.renderWidth, 8);
        int sourceY = divideRoundUp(this.renderHeight, 8);
        int spdX = divideRoundUp(this.renderWidth, 64);
        int spdY = divideRoundUp(this.renderHeight, 64);
        int shadingX = divideRoundUp((int) (this.renderWidth * 0.5F), 8);
        int shadingY = divideRoundUp((int) (this.renderHeight * 0.5F), 8);
        int displayX = divideRoundUp(this.displayWidth, 8);
        int displayY = divideRoundUp(this.displayHeight, 8);
        int rcasX = divideRoundUp(this.displayWidth, 16);
        int rcasY = divideRoundUp(this.displayHeight, 16);

        this.passes[0].record(commandBuffer, parity, sourceX, sourceY, null);
        computeBarrier(commandBuffer);
        this.passes[1].record(commandBuffer, parity, spdX, spdY, null);
        computeBarrier(commandBuffer);
        this.passes[2].record(commandBuffer, parity, spdX, spdY, null);
        computeBarrier(commandBuffer);
        this.passes[3].record(commandBuffer, parity, shadingX, shadingY, null);
        computeBarrier(commandBuffer);
        this.passes[4].record(commandBuffer, parity, sourceX, sourceY, null);
        computeBarrier(commandBuffer);
        this.passes[5].record(commandBuffer, parity, sourceX, sourceY, null);
        computeBarrier(commandBuffer);
        this.passes[6].record(commandBuffer, parity, displayX, displayY, null);
        computeBarrier(commandBuffer);
        this.passes[7].record(commandBuffer, parity, rcasX, rcasY, null);
        computeBarrier(commandBuffer);
        RtestFsrDebugView debugView = RtestFsrSettings.debugView();
        if (debugView == RtestFsrDebugView.OVERVIEW) {
            this.debugPass.record(commandBuffer, parity, displayX, displayY, null);
            computeBarrier(commandBuffer);
        }
        long offlineSample = OfflineRenderController.claimSample();
        if (offlineSample >= 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer offlinePush = stack.malloc(12).order(ByteOrder.nativeOrder());
                offlinePush.putInt(0, this.displayWidth);
                offlinePush.putInt(4, this.displayHeight);
                offlinePush.putInt(8, (int)Math.min(offlineSample, 0xffff_ffffL));
                this.offlinePass.record(
                        commandBuffer, 0, displayX, displayY, offlinePush);
            }
            computeBarrier(commandBuffer);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer displayPush = stack.malloc(20).order(ByteOrder.nativeOrder());
            displayPush.putInt(0, this.displayWidth);
            displayPush.putInt(4, this.displayHeight);
            displayPush.putInt(8,
                    RayTracingClientConfig.INSTANCE.primeColorManagementEnabled.get() ? 1 : 0);
            float outputPeak = HdrSupport.isActive() ? 16.0F : 1.0F;
            PrimeRgbReinhardOutput.Parameters reinhard =
                    PrimeRgbReinhardOutput.parameters(outputPeak);
            displayPush.putFloat(12, reinhard.outputPeak());
            displayPush.putFloat(16, reinhard.curvePeak());
            this.displayPass.record(
                    commandBuffer, 0, displayX, displayY, displayPush);
        }

    }

    /** Must be called immediately after the command buffer containing {@code token} is submitted. */
    public void submitted(FrameToken token) {
        this.requireOpen();
        if (token.owner != this || !token.recorded || token.submitted) {
            throw new IllegalArgumentException("FSR frame token does not belong to this submission");
        }
        token.submitted = true;
        this.initialized = true;
        this.resetRequested = false;
        this.previousCamera = token.camera;
        this.previousSceneResetRevision = token.sceneResetRevision;
        this.previousAtlasView = token.atlasView;
        this.previousAtlasSampler = token.atlasSampler;
        this.previousJitter = token.jitter;
        this.previousFrameNanos = token.frameNanos;
        this.frameIndex = token.frameIndex + 1;
    }

    private ByteBuffer createMainConstants(
            MemoryStack stack,
            RtestFsrCamera camera,
            RtestFsrSettings.Jitter jitter,
            RtestFsrSettings.Jitter previous,
            int temporalFrameIndex,
            float deltaSeconds) {
        ByteBuffer buffer = stack.calloc(MAIN_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        RtestFsrSettings.Jitter fsrJitter = jitter.forFsrDispatch();
        RtestFsrSettings.Jitter previousFsrJitter = previous.forFsrDispatch();
        putExtent(buffer, 0, this.renderWidth, this.renderHeight);
        putExtent(buffer, 8, this.renderWidth, this.renderHeight);
        putExtent(buffer, 16, this.displayWidth, this.displayHeight);
        putExtent(buffer, 24, this.displayWidth, this.displayHeight);
        putExtent(buffer, 32, this.renderWidth, this.renderHeight);
        putExtent(buffer, 40, this.displayWidth, this.displayHeight);

        // FSR's reversed infinite-depth transform is viewZ = near / (depth + epsilon). The depth
        // producer in nrd_motion.comp writes near/viewZ and zero for infinity. Projection X/Y are
        // the inverse cotangents from the exact non-jittered Minecraft projection.
        buffer.putFloat(48, -Math.ulp(1.0F));
        buffer.putFloat(52, NEAR_PLANE);
        buffer.putFloat(56, Math.abs(1.0F / camera.projectionM00()));
        buffer.putFloat(60, Math.abs(1.0F / camera.projectionM11()));
        // RTest traces pixelCenter + jitter. FidelityFX's reference integration passes the
        // opposite projection-space sign because FSR reconstructs source positions as
        // pixelCenter - Jitter(). Keep the conversion at this API boundary; NRD and raygen retain
        // RTest's direct sample-space sign.
        buffer.putFloat(64, fsrJitter.x());
        buffer.putFloat(68, fsrJitter.y());
        buffer.putFloat(72, previousFsrJitter.x());
        buffer.putFloat(76, previousFsrJitter.y());
        // RTest's shared motion texture already stores normalized old-current UV, so no pixel-to-
        // UV scale is needed and the vectors deliberately exclude camera jitter.
        buffer.putFloat(80, 1.0F);
        buffer.putFloat(84, 1.0F);
        buffer.putFloat(88, (float) this.renderWidth / this.displayWidth);
        buffer.putFloat(92, (float) this.renderHeight / this.displayHeight);
        buffer.putFloat(96, 0.0F);
        buffer.putFloat(100, 0.0F);
        buffer.putFloat(104, Math.abs(1.0F / camera.projectionM00()));
        buffer.putFloat(108, this.qualityMode.jitterPhaseCount());
        buffer.putFloat(112, deltaSeconds);
        buffer.putFloat(116, 1.0F);
        buffer.putFloat(120, 1.0F);
        buffer.putFloat(124, temporalFrameIndex);
        buffer.putFloat(128, 1.0F);
        buffer.putFloat(132, 1.0F);
        buffer.putFloat(136, 1.0F);
        buffer.putFloat(140, 1.0F / 3.0F);
        buffer.putFloat(144, -1.0F / 3.0F);
        return buffer.position(0).limit(MAIN_CONSTANT_SIZE);
    }

    private ByteBuffer createSpdConstants(MemoryStack stack) {
        int spdX = divideRoundUp(this.renderWidth, 64);
        int spdY = divideRoundUp(this.renderHeight, 64);
        int resolution = Math.max(this.renderWidth, this.renderHeight);
        int mips = Math.min(31 - Integer.numberOfLeadingZeros(resolution), 12);
        ByteBuffer buffer = stack.calloc(SPD_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
        buffer.putInt(0, mips);
        buffer.putInt(4, spdX * spdY);
        buffer.putInt(8, 0);
        buffer.putInt(12, 0);
        buffer.putInt(16, this.renderWidth);
        buffer.putInt(20, this.renderHeight);
        return buffer.position(0).limit(SPD_CONSTANT_SIZE);
    }

    private static void writeRcasConstants(RtestVulkanBuffer destination) {
        float linearSharpness = RtestFsrSettings.rcasLinearSharpness();
        int half = Float.floatToFloat16(linearSharpness) & 0xffff;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer buffer = stack.calloc(RCAS_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
            buffer.putInt(0, Float.floatToRawIntBits(linearSharpness));
            buffer.putInt(4, half | half << 16);
            destination.put(0L, buffer.position(0).limit(RCAS_CONSTANT_SIZE));
        }
    }

    private void prepareResources(VkCommandBuffer commandBuffer) {
        if (!this.initialized) {
            List<RtestVulkanImage> images = this.resources.ownedImages;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(images.size(), stack);
                for (int index = 0; index < images.size(); index++) {
                    RtestVulkanImage image = images.get(index);
                    barriers.get(index)
                            .sType$Default()
                            .srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                            .srcAccessMask(0L)
                            .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT
                                    | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                            .dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT
                                    | VK12.VK_ACCESS_SHADER_READ_BIT
                                    | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                            .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .image(image.image());
                    barriers.get(index).subresourceRange()
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(image.mipLevels())
                            .baseArrayLayer(0)
                            .layerCount(1);
                    image.markInitialized();
                }
                VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                        .sType$Default()
                        .pImageMemoryBarriers(barriers);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);

                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
                copy.get(0).bufferOffset(0L).bufferRowLength(0).bufferImageHeight(0);
                copy.get(0).imageSubresource()
                        .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1);
                copy.get(0).imageOffset().set(0, 0, 0);
                copy.get(0).imageExtent().set(128, 1, 1);
                VK12.vkCmdCopyBufferToImage(
                        commandBuffer,
                        this.lanczosUpload.handle(),
                        this.resources.lanczosLut.image(),
                        VK12.VK_IMAGE_LAYOUT_GENERAL,
                        copy);
            }
            // FSR exposure must be the same multiplier used by the later display transform. RTest
            // deliberately uses fixed exposure 1.0; writing it explicitly avoids relying on FSR's
            // special zero-means-one fallback and makes that cross-stage contract auditable.
            clearFloatImage(
                    commandBuffer,
                    this.resources.exposure,
                    RtestFsrSettings.EXPOSURE,
                    0.0F,
                    0.0F,
                    0.0F);
        } else {
            computeToTransferBarrier(commandBuffer);
        }
    }

    private void clearPerFrameResources(VkCommandBuffer commandBuffer, boolean reset) {
        clearUintImage(commandBuffer, this.resources.reconstructedDepth, 0);
        clearUintImage(commandBuffer, this.resources.spdAtomic, 0);
        clearFloatImage(commandBuffer, this.resources.spdMips, 0.0F, 0.0F, 0.0F, 0.0F);
        if (!reset) {
            return;
        }
        for (RtestVulkanImage image : this.resources.accumulation) {
            clearFloatImage(commandBuffer, image, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        for (RtestVulkanImage image : this.resources.luma) {
            clearFloatImage(commandBuffer, image, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        for (RtestVulkanImage image : this.resources.internalUpscaled) {
            clearFloatImage(commandBuffer, image, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        for (RtestVulkanImage image : this.resources.lumaHistory) {
            clearFloatImage(commandBuffer, image, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        clearFloatImage(commandBuffer, this.resources.frameInfo, -1.0F, 1.0F, 0.0F, 0.0F);
    }

    private static void clearFloatImage(
            VkCommandBuffer commandBuffer,
            RtestVulkanImage image,
            float red,
            float green,
            float blue,
            float alpha) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.float32(0, red).float32(1, green).float32(2, blue).float32(3, alpha);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(image.mipLevels())
                    .baseArrayLayer(0)
                    .layerCount(1);
            VK12.vkCmdClearColorImage(
                    commandBuffer, image.image(), VK12.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    private static void clearUintImage(VkCommandBuffer commandBuffer, RtestVulkanImage image, int value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            color.uint32(0, value).uint32(1, value).uint32(2, value).uint32(3, value);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(image.mipLevels())
                    .baseArrayLayer(0)
                    .layerCount(1);
            VK12.vkCmdClearColorImage(
                    commandBuffer, image.image(), VK12.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    private static void computeToTransferBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void transferToComputeBarrier(
            VkCommandBuffer commandBuffer, RtestVulkanBuffer main, RtestVulkanBuffer spd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack);
            memory.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VkBufferMemoryBarrier2.Buffer buffers = VkBufferMemoryBarrier2.calloc(2, stack);
            buffers.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_UNIFORM_READ_BIT)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                    .buffer(main.handle())
                    .offset(0L)
                    .size(MAIN_CONSTANT_SIZE);
            buffers.get(1).set(buffers.get(0)).buffer(spd.handle()).size(SPD_CONSTANT_SIZE);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(memory)
                    .pBufferMemoryBarriers(buffers);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static void computeBarrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack)
                    .sType$Default()
                    .pMemoryBarriers(barrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
        }
    }

    private static RtestVulkanBuffer createLanczosUpload(RtestVulkanContext context) {
        RtestVulkanBuffer buffer = context.createBuffer(
                128L * Short.BYTES,
                VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                true,
                "RTest FSR Lanczos LUT upload");
        ByteBuffer data = null;
        try {
            data = MemoryUtil.memAlloc(128 * Short.BYTES).order(ByteOrder.nativeOrder());
            for (int index = 0; index < 128; index++) {
                double x = 2.0 * index / 127.0;
                double weight = lanczos2(x);
                int encoded = (int) Math.round(weight * 32767.0);
                data.putShort((short) Math.clamp(encoded, Short.MIN_VALUE, Short.MAX_VALUE));
            }
            data.flip();
            buffer.put(0L, data);
            return buffer;
        } catch (Throwable throwable) {
            buffer.destroy();
            throw throwable;
        } finally {
            if (data != null) {
                MemoryUtil.memFree(data);
            }
        }
    }

    private static double lanczos2(double x) {
        if (Math.abs(x) < 1.0e-12) {
            return 1.0;
        }
        double pix = Math.PI * x;
        return Math.sin(pix) / pix * Math.sin(pix * 0.5) / (pix * 0.5);
    }

    private static long createSampler(RtestVulkanContext context, boolean linear, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int filter = linear ? VK12.VK_FILTER_LINEAR : VK12.VK_FILTER_NEAREST;
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(filter)
                    .minFilter(filter)
                    .mipmapMode(linear
                            ? VK12.VK_SAMPLER_MIPMAP_MODE_LINEAR
                            : VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0F)
                    .maxLod(VK12.VK_LOD_CLAMP_NONE);
            LongBuffer pointer = stack.mallocLong(1);
            RtestVulkanContext.check(
                    VK12.vkCreateSampler(context.vkDevice(), createInfo, null, pointer),
                    "create " + label);
            long sampler = pointer.get(0);
            try {
                context.device().instance().debug().setObjectName(
                        context.vkDevice(), VK12.VK_OBJECT_TYPE_SAMPLER, sampler, label);
                return sampler;
            } catch (Throwable throwable) {
                VK12.vkDestroySampler(context.vkDevice(), sampler, null);
                throw throwable;
            }
        }
    }

    private static void putExtent(ByteBuffer buffer, int offset, int width, int height) {
        buffer.putInt(offset, width);
        buffer.putInt(offset + Integer.BYTES, height);
    }

    private static int divideRoundUp(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    private void requireOpen() {
        if (this.destroyed) {
            throw new IllegalStateException("FSR upscaler has been destroyed");
        }
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.displayPass.destroy();
        this.offlinePass.destroy();
        this.debugPass.destroy();
        for (int index = this.passes.length - 1; index >= 0; index--) {
            this.passes[index].destroy();
        }
        this.lanczosUpload.destroy();
        this.rcasConstants.destroy();
        this.spdConstants.destroy();
        this.mainConstants.destroy();
        VK12.vkDestroySampler(this.context.vkDevice(), this.linearSampler, null);
        VK12.vkDestroySampler(this.context.vkDevice(), this.pointSampler, null);
        this.resources.destroy();
    }

    private enum ResourceId {
        SCENE_COLOR,
        INPUT_MOTION,
        INPUT_DEPTH,
        DISPLAY_OUTPUT,
        DILATED_MOTION,
        DILATED_DEPTH,
        RECONSTRUCTED_DEPTH,
        INTERMEDIATE,
        CURRENT_LUMA,
        PREVIOUS_LUMA,
        SPD_MIPS,
        SPD_ATOMIC,
        FRAME_INFO,
        FARTHEST_DEPTH_MIP1,
        SHADING_CHANGE,
        REACTIVE_MASK,
        TRANSPARENCY_COMPOSITION_MASK,
        EXPOSURE,
        ACCUMULATION_PREVIOUS,
        ACCUMULATION_CURRENT,
        NEW_LOCKS,
        DILATED_REACTIVE,
        LUMA_HISTORY_PREVIOUS,
        LUMA_HISTORY_CURRENT,
        INTERNAL_UPSCALED_PREVIOUS,
        INTERNAL_UPSCALED_CURRENT,
        LANCZOS_LUT,
        FSR_OUTPUT,
        OFFLINE_MEAN
    }

    private enum BufferId {
        MAIN,
        SPD,
        RCAS
    }

    private record ImageSlot(int binding, int descriptorType, ResourceId resource, int mipLevel) {
        private static ImageSlot sampled(int binding, ResourceId resource) {
            return new ImageSlot(binding, VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, resource, -1);
        }

        private static ImageSlot storage(int binding, ResourceId resource) {
            return new ImageSlot(binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, resource, -1);
        }

        private static ImageSlot storageMip(int binding, int mipLevel) {
            return new ImageSlot(
                    binding, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, ResourceId.SPD_MIPS, mipLevel);
        }
    }

    private record SamplerSlot(int binding, boolean linear) {
    }

    private record BufferSlot(int binding, BufferId buffer) {
    }

    private record PassSpec(
            String label,
            String shaderResource,
            List<ImageSlot> images,
            List<SamplerSlot> samplers,
            List<BufferSlot> buffers,
            int pushConstantSize) {
    }

    private static List<SamplerSlot> fsrSamplers() {
        return List.of(new SamplerSlot(1000, false), new SamplerSlot(1001, true));
    }

    private static List<PassSpec> upscalerPassSpecs(boolean fp16) {
        String root = shaderRoot(fp16);
        return List.of(
                new PassSpec(
                        "prepare inputs",
                        root + "prepare_inputs.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.INPUT_MOTION),
                                ImageSlot.sampled(1, ResourceId.INPUT_DEPTH),
                                ImageSlot.sampled(2, ResourceId.SCENE_COLOR),
                                ImageSlot.storage(3, ResourceId.DILATED_MOTION),
                                ImageSlot.storage(4, ResourceId.DILATED_DEPTH),
                                ImageSlot.storage(5, ResourceId.RECONSTRUCTED_DEPTH),
                                ImageSlot.storage(6, ResourceId.INTERMEDIATE),
                                ImageSlot.storage(7, ResourceId.CURRENT_LUMA)),
                        fsrSamplers(),
                        List.of(new BufferSlot(8, BufferId.MAIN)),
                        0),
                new PassSpec(
                        "luma pyramid",
                        root + "luma_pyramid.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.CURRENT_LUMA),
                                ImageSlot.sampled(1, ResourceId.INTERMEDIATE),
                                ImageSlot.storage(2, ResourceId.SPD_ATOMIC),
                                ImageSlot.storage(3, ResourceId.FRAME_INFO),
                                ImageSlot.storageMip(4, 0),
                                ImageSlot.storageMip(5, 1),
                                ImageSlot.storageMip(6, 2),
                                ImageSlot.storageMip(7, 3),
                                ImageSlot.storageMip(8, 4),
                                ImageSlot.storageMip(9, 5),
                                ImageSlot.storage(10, ResourceId.FARTHEST_DEPTH_MIP1)),
                        fsrSamplers(),
                        List.of(new BufferSlot(11, BufferId.MAIN), new BufferSlot(12, BufferId.SPD)),
                        0),
                new PassSpec(
                        "shading-change pyramid",
                        root + "shading_change_pyramid.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.CURRENT_LUMA),
                                ImageSlot.sampled(1, ResourceId.PREVIOUS_LUMA),
                                ImageSlot.sampled(2, ResourceId.DILATED_MOTION),
                                ImageSlot.sampled(3, ResourceId.EXPOSURE),
                                ImageSlot.storage(4, ResourceId.SPD_ATOMIC),
                                ImageSlot.storageMip(5, 0),
                                ImageSlot.storageMip(6, 1),
                                ImageSlot.storageMip(7, 2),
                                ImageSlot.storageMip(8, 3),
                                ImageSlot.storageMip(9, 4),
                                ImageSlot.storageMip(10, 5)),
                        fsrSamplers(),
                        List.of(new BufferSlot(11, BufferId.MAIN), new BufferSlot(12, BufferId.SPD)),
                        0),
                new PassSpec(
                        "shading change",
                        root + "shading_change.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.SPD_MIPS),
                                ImageSlot.storage(1, ResourceId.SHADING_CHANGE)),
                        fsrSamplers(),
                        List.of(new BufferSlot(2, BufferId.MAIN)),
                        0),
                new PassSpec(
                        "prepare reactivity",
                        root + "prepare_reactivity.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.RECONSTRUCTED_DEPTH),
                                ImageSlot.sampled(1, ResourceId.DILATED_MOTION),
                                ImageSlot.sampled(2, ResourceId.DILATED_DEPTH),
                                ImageSlot.sampled(3, ResourceId.REACTIVE_MASK),
                                ImageSlot.sampled(4, ResourceId.TRANSPARENCY_COMPOSITION_MASK),
                                ImageSlot.sampled(5, ResourceId.ACCUMULATION_PREVIOUS),
                                ImageSlot.sampled(6, ResourceId.SHADING_CHANGE),
                                ImageSlot.sampled(7, ResourceId.CURRENT_LUMA),
                                ImageSlot.sampled(8, ResourceId.EXPOSURE),
                                ImageSlot.storage(9, ResourceId.DILATED_REACTIVE),
                                ImageSlot.storage(10, ResourceId.NEW_LOCKS),
                                ImageSlot.storage(11, ResourceId.ACCUMULATION_CURRENT)),
                        fsrSamplers(),
                        List.of(new BufferSlot(12, BufferId.MAIN)),
                        0),
                new PassSpec(
                        "luma instability",
                        root + "luma_instability.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.EXPOSURE),
                                ImageSlot.sampled(1, ResourceId.DILATED_REACTIVE),
                                ImageSlot.sampled(2, ResourceId.DILATED_MOTION),
                                ImageSlot.sampled(3, ResourceId.FRAME_INFO),
                                ImageSlot.sampled(4, ResourceId.LUMA_HISTORY_PREVIOUS),
                                ImageSlot.sampled(5, ResourceId.FARTHEST_DEPTH_MIP1),
                                ImageSlot.sampled(6, ResourceId.CURRENT_LUMA),
                                ImageSlot.storage(7, ResourceId.LUMA_HISTORY_CURRENT),
                                ImageSlot.storage(8, ResourceId.INTERMEDIATE)),
                        fsrSamplers(),
                        List.of(new BufferSlot(9, BufferId.MAIN)),
                        0),
                new PassSpec(
                        "accumulate",
                        root + "accumulate.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.EXPOSURE),
                                ImageSlot.sampled(1, ResourceId.DILATED_REACTIVE),
                                ImageSlot.sampled(2, ResourceId.DILATED_MOTION),
                                ImageSlot.sampled(3, ResourceId.INTERNAL_UPSCALED_PREVIOUS),
                                ImageSlot.sampled(4, ResourceId.LANCZOS_LUT),
                                ImageSlot.sampled(5, ResourceId.FARTHEST_DEPTH_MIP1),
                                ImageSlot.sampled(6, ResourceId.CURRENT_LUMA),
                                ImageSlot.sampled(7, ResourceId.INTERMEDIATE),
                                ImageSlot.sampled(8, ResourceId.SCENE_COLOR),
                                ImageSlot.storage(9, ResourceId.INTERNAL_UPSCALED_CURRENT),
                                ImageSlot.storage(10, ResourceId.FSR_OUTPUT),
                                ImageSlot.storage(11, ResourceId.NEW_LOCKS)),
                        fsrSamplers(),
                        List.of(new BufferSlot(12, BufferId.MAIN)),
                        0),
                new PassSpec(
                        "RCAS",
                        root + "rcas.comp.spv",
                        List.of(
                                ImageSlot.sampled(0, ResourceId.EXPOSURE),
                                ImageSlot.sampled(1, ResourceId.INTERNAL_UPSCALED_CURRENT),
                                ImageSlot.storage(2, ResourceId.FSR_OUTPUT)),
                        fsrSamplers(),
                        List.of(
                                new BufferSlot(3, BufferId.MAIN),
                                new BufferSlot(4, BufferId.RCAS)),
                        0));
    }

    private static PassSpec debugPassSpec(boolean fp16) {
        return new PassSpec(
                "debug view",
                shaderRoot(fp16) + "debug_view.comp.spv",
                List.of(
                        ImageSlot.sampled(0, ResourceId.DILATED_REACTIVE),
                        ImageSlot.sampled(1, ResourceId.DILATED_MOTION),
                        ImageSlot.sampled(2, ResourceId.DILATED_DEPTH),
                        ImageSlot.sampled(3, ResourceId.INTERNAL_UPSCALED_CURRENT),
                        ImageSlot.sampled(4, ResourceId.EXPOSURE),
                        ImageSlot.storage(5, ResourceId.FSR_OUTPUT)),
                fsrSamplers(),
                List.of(new BufferSlot(6, BufferId.MAIN)),
                0);
    }

    private static String shaderRoot(boolean fp16) {
        return "/prime/shaders/fsr3/" + (fp16 ? "fp16/" : "fp32/")
                + "ffx_fsr3upscaler_";
    }

    private static PassSpec displayPassSpec() {
        return new PassSpec(
                "display transform",
                HdrSupport.isActive()
                        ? (HdrSupport.usesRec2020Primaries()
                            ? "/prime/shaders/fsr_display_rec2020.comp.spv"
                            : "/prime/shaders/fsr_display_hdr.comp.spv")
                        : "/prime/shaders/fsr_display.comp.spv",
                List.of(
                        ImageSlot.sampled(0, ResourceId.FSR_OUTPUT),
                        ImageSlot.storage(1, ResourceId.DISPLAY_OUTPUT)),
                List.of(),
                List.of(),
                20);
    }

    private static PassSpec offlinePassSpec() {
        return new PassSpec(
                "Prime offline running mean",
                "/prime/shaders/offline_accumulate.comp.spv",
                List.of(
                        ImageSlot.storage(0, ResourceId.FSR_OUTPUT),
                        ImageSlot.storage(1, ResourceId.OFFLINE_MEAN)),
                List.of(),
                List.of(),
                12);
    }

    /** Immutable temporal inputs chosen before ray generation plus submission bookkeeping. */
    public static final class FrameToken {
        private final RtestFsr3Upscaler owner;
        private final RtestFsrCamera camera;
        private final long sceneResetRevision;
        private final long atlasView;
        private final long atlasSampler;
        private final int frameIndex;
        private final RtestFsrSettings.Jitter jitter;
        private final RtestFsrSettings.Jitter previousJitter;
        private final boolean reset;
        private final boolean cameraCut;
        private final float deltaSeconds;
        private final long frameNanos;
        private boolean recorded;
        private boolean submitted;

        private FrameToken(
                RtestFsr3Upscaler owner,
                RtestFsrCamera camera,
                long sceneResetRevision,
                long atlasView,
                long atlasSampler,
                int frameIndex,
                RtestFsrSettings.Jitter jitter,
                RtestFsrSettings.Jitter previousJitter,
                boolean reset,
                boolean cameraCut,
                float deltaSeconds,
                long frameNanos) {
            this.owner = owner;
            this.camera = camera;
            this.sceneResetRevision = sceneResetRevision;
            this.atlasView = atlasView;
            this.atlasSampler = atlasSampler;
            this.frameIndex = frameIndex;
            this.jitter = jitter;
            this.previousJitter = previousJitter;
            this.reset = reset;
            this.cameraCut = cameraCut;
            this.deltaSeconds = deltaSeconds;
            this.frameNanos = frameNanos;
        }

        public int frameIndex() {
            return this.frameIndex;
        }

        public RtestFsrSettings.Jitter jitter() {
            return this.jitter;
        }

        public boolean reset() {
            return this.reset;
        }

        public boolean cameraCut() {
            return this.cameraCut;
        }
    }

    private static final class Resources implements Destroyable {
        private final RtestVulkanImage sceneColor;
        private final RtestVulkanImage inputMotion;
        private final RtestVulkanImage inputDepth;
        private final RtestVulkanImage displayOutput;
        private final RtestVulkanImage dilatedMotion;
        private final RtestVulkanImage dilatedDepth;
        private final RtestVulkanImage reconstructedDepth;
        private final RtestVulkanImage intermediate;
        private final RtestVulkanImage[] luma;
        private final RtestVulkanImage spdMips;
        private final RtestVulkanImage spdAtomic;
        private final RtestVulkanImage frameInfo;
        private final RtestVulkanImage farthestDepthMip1;
        private final RtestVulkanImage shadingChange;
        private final RtestVulkanImage reactiveMask;
        private final RtestVulkanImage transparencyCompositionMask;
        private final RtestVulkanImage exposure;
        private final RtestVulkanImage[] accumulation;
        private final RtestVulkanImage newLocks;
        private final RtestVulkanImage dilatedReactive;
        private final RtestVulkanImage[] lumaHistory;
        private final RtestVulkanImage[] internalUpscaled;
        private final RtestVulkanImage lanczosLut;
        private final RtestVulkanImage fsrOutput;
        private final RtestVulkanImage offlineMean;
        private final List<RtestVulkanImage> ownedImages;
        private boolean destroyed;

        private Resources(
                RtestVulkanImage sceneColor,
                RtestVulkanImage inputMotion,
                RtestVulkanImage inputDepth,
                RtestVulkanImage displayOutput,
                RtestVulkanImage dilatedMotion,
                RtestVulkanImage dilatedDepth,
                RtestVulkanImage reconstructedDepth,
                RtestVulkanImage intermediate,
                RtestVulkanImage[] luma,
                RtestVulkanImage spdMips,
                RtestVulkanImage spdAtomic,
                RtestVulkanImage frameInfo,
                RtestVulkanImage farthestDepthMip1,
                RtestVulkanImage shadingChange,
                RtestVulkanImage reactiveMask,
                RtestVulkanImage transparencyCompositionMask,
                RtestVulkanImage exposure,
                RtestVulkanImage[] accumulation,
                RtestVulkanImage newLocks,
                RtestVulkanImage dilatedReactive,
                RtestVulkanImage[] lumaHistory,
                RtestVulkanImage[] internalUpscaled,
                RtestVulkanImage lanczosLut,
                RtestVulkanImage fsrOutput,
                RtestVulkanImage offlineMean,
                List<RtestVulkanImage> ownedImages) {
            this.sceneColor = sceneColor;
            this.inputMotion = inputMotion;
            this.inputDepth = inputDepth;
            this.displayOutput = displayOutput;
            this.dilatedMotion = dilatedMotion;
            this.dilatedDepth = dilatedDepth;
            this.reconstructedDepth = reconstructedDepth;
            this.intermediate = intermediate;
            this.luma = luma;
            this.spdMips = spdMips;
            this.spdAtomic = spdAtomic;
            this.frameInfo = frameInfo;
            this.farthestDepthMip1 = farthestDepthMip1;
            this.shadingChange = shadingChange;
            this.reactiveMask = reactiveMask;
            this.transparencyCompositionMask = transparencyCompositionMask;
            this.exposure = exposure;
            this.accumulation = accumulation;
            this.newLocks = newLocks;
            this.dilatedReactive = dilatedReactive;
            this.lumaHistory = lumaHistory;
            this.internalUpscaled = internalUpscaled;
            this.lanczosLut = lanczosLut;
            this.fsrOutput = fsrOutput;
            this.offlineMean = offlineMean;
            this.ownedImages = List.copyOf(ownedImages);
        }

        private static Resources create(
                RtestVulkanContext context,
                int renderWidth,
                int renderHeight,
                int displayWidth,
                int displayHeight,
                RtestVulkanImage sceneColor,
                RtestVulkanImage inputMotion,
                RtestVulkanImage inputDepth,
                RtestVulkanImage reactiveMask,
                RtestVulkanImage transparencyCompositionMask,
                RtestVulkanImage displayOutput) {
            ArrayList<RtestVulkanImage> owned = new ArrayList<>();
            try {
                int halfWidth = Math.max(1, renderWidth / 2);
                int halfHeight = Math.max(1, renderHeight / 2);
                int spdWidth = Math.max(32, halfWidth);
                int spdHeight = Math.max(32, halfHeight);
                RtestVulkanImage dilatedMotion = own(owned, context.createImage2D(
                        renderWidth, renderHeight, VK12.VK_FORMAT_R16G16_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR dilated motion"));
                RtestVulkanImage dilatedDepth = own(owned, context.createImage2D(
                        renderWidth, renderHeight, VK12.VK_FORMAT_R32_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR dilated depth"));
                RtestVulkanImage reconstructed = own(owned, context.createImage2D(
                        renderWidth, renderHeight, VK12.VK_FORMAT_R32_UINT,
                        COMMON_IMAGE_USAGE, "RTest FSR reconstructed previous depth"));
                RtestVulkanImage intermediate = own(owned, context.createImage2D(
                        renderWidth, renderHeight, VK12.VK_FORMAT_R16_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR intermediate R16"));
                RtestVulkanImage[] luma = new RtestVulkanImage[] {
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR temporal luma 0")),
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR temporal luma 1"))
                };
                RtestVulkanImage spdMips = own(owned, context.createMipmappedImage2D(
                        spdWidth, spdHeight, 6, VK12.VK_FORMAT_R16G16_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR SPD mips"));
                RtestVulkanImage spdAtomic = own(owned, context.createImage2D(
                        1, 1, VK12.VK_FORMAT_R32_UINT,
                        COMMON_IMAGE_USAGE, "RTest FSR SPD atomic"));
                RtestVulkanImage frameInfo = own(owned, context.createImage2D(
                        1, 1, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR frame info"));
                RtestVulkanImage farthest = own(owned, context.createImage2D(
                        halfWidth, halfHeight, VK12.VK_FORMAT_R16_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR farthest depth mip 1"));
                RtestVulkanImage shading = own(owned, context.createImage2D(
                        halfWidth, halfHeight, VK12.VK_FORMAT_R8_UNORM,
                        COMMON_IMAGE_USAGE, "RTest FSR shading change"));
                RtestVulkanImage exposure = own(owned, context.createImage2D(
                        1, 1, VK12.VK_FORMAT_R32G32_SFLOAT,
                        SAMPLED_CLEAR_USAGE, "RTest FSR default exposure"));
                RtestVulkanImage[] accumulation = new RtestVulkanImage[] {
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R8_UNORM,
                            COMMON_IMAGE_USAGE, "RTest FSR accumulation 0")),
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R8_UNORM,
                            COMMON_IMAGE_USAGE, "RTest FSR accumulation 1"))
                };
                RtestVulkanImage newLocks = own(owned, context.createImage2D(
                        displayWidth, displayHeight, VK12.VK_FORMAT_R8_UNORM,
                        COMMON_IMAGE_USAGE, "RTest FSR new locks"));
                RtestVulkanImage dilatedReactive = own(owned, context.createImage2D(
                        renderWidth, renderHeight, VK12.VK_FORMAT_R8G8B8A8_UNORM,
                        COMMON_IMAGE_USAGE, "RTest FSR dilated reactive masks"));
                RtestVulkanImage[] lumaHistory = new RtestVulkanImage[] {
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR luma history 0")),
                    own(owned, context.createImage2D(
                            renderWidth, renderHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR luma history 1"))
                };
                RtestVulkanImage[] internalUpscaled = new RtestVulkanImage[] {
                    own(owned, context.createImage2D(
                            displayWidth, displayHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR internal upscaled 0")),
                    own(owned, context.createImage2D(
                            displayWidth, displayHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                            COMMON_IMAGE_USAGE, "RTest FSR internal upscaled 1"))
                };
                RtestVulkanImage lanczos = own(owned, context.createImage2D(
                        128, 1, VK12.VK_FORMAT_R16_SNORM,
                        SAMPLED_CLEAR_USAGE, "RTest FSR Lanczos LUT"));
                RtestVulkanImage fsrOutput = own(owned, context.createImage2D(
                        displayWidth, displayHeight, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest FSR linear HDR output"));
                RtestVulkanImage offlineMean = own(owned, context.createImage2D(
                        displayWidth, displayHeight, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                        COMMON_IMAGE_USAGE, "RTest Prime offline running mean"));
                return new Resources(
                        sceneColor,
                        inputMotion,
                        inputDepth,
                        displayOutput,
                        dilatedMotion,
                        dilatedDepth,
                        reconstructed,
                        intermediate,
                        luma,
                        spdMips,
                        spdAtomic,
                        frameInfo,
                        farthest,
                        shading,
                        reactiveMask,
                        transparencyCompositionMask,
                        exposure,
                        accumulation,
                        newLocks,
                        dilatedReactive,
                        lumaHistory,
                        internalUpscaled,
                        lanczos,
                        fsrOutput,
                        offlineMean,
                        owned);
            } catch (RuntimeException | Error exception) {
                for (int index = owned.size() - 1; index >= 0; index--) {
                    owned.get(index).destroy();
                }
                throw exception;
            }
        }

        private static RtestVulkanImage own(List<RtestVulkanImage> owned, RtestVulkanImage image) {
            owned.add(image);
            return image;
        }

        private RtestVulkanImage image(ResourceId id, int parity) {
            return switch (id) {
                case SCENE_COLOR -> this.sceneColor;
                case INPUT_MOTION -> this.inputMotion;
                case INPUT_DEPTH -> this.inputDepth;
                case DISPLAY_OUTPUT -> this.displayOutput;
                case DILATED_MOTION -> this.dilatedMotion;
                case DILATED_DEPTH -> this.dilatedDepth;
                case RECONSTRUCTED_DEPTH -> this.reconstructedDepth;
                case INTERMEDIATE -> this.intermediate;
                case CURRENT_LUMA -> this.luma[parity];
                case PREVIOUS_LUMA -> this.luma[parity ^ 1];
                case SPD_MIPS -> this.spdMips;
                case SPD_ATOMIC -> this.spdAtomic;
                case FRAME_INFO -> this.frameInfo;
                case FARTHEST_DEPTH_MIP1 -> this.farthestDepthMip1;
                case SHADING_CHANGE -> this.shadingChange;
                case REACTIVE_MASK -> this.reactiveMask;
                case TRANSPARENCY_COMPOSITION_MASK -> this.transparencyCompositionMask;
                case EXPOSURE -> this.exposure;
                case ACCUMULATION_PREVIOUS -> this.accumulation[parity];
                case ACCUMULATION_CURRENT -> this.accumulation[parity ^ 1];
                case NEW_LOCKS -> this.newLocks;
                case DILATED_REACTIVE -> this.dilatedReactive;
                case LUMA_HISTORY_PREVIOUS -> this.lumaHistory[parity];
                case LUMA_HISTORY_CURRENT -> this.lumaHistory[parity ^ 1];
                case INTERNAL_UPSCALED_PREVIOUS -> this.internalUpscaled[parity];
                case INTERNAL_UPSCALED_CURRENT -> this.internalUpscaled[parity ^ 1];
                case LANCZOS_LUT -> this.lanczosLut;
                case FSR_OUTPUT -> this.fsrOutput;
                case OFFLINE_MEAN -> this.offlineMean;
            };
        }

        private long view(ImageSlot slot, int parity) {
            RtestVulkanImage image = this.image(slot.resource, parity);
            return slot.mipLevel >= 0 ? image.mipView(slot.mipLevel) : image.view();
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            for (int index = this.ownedImages.size() - 1; index >= 0; index--) {
                this.ownedImages.get(index).destroy();
            }
        }
    }

    private static final class Pass implements Destroyable {
        private final RtestVulkanContext context;
        private final PassSpec spec;
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long[] descriptorSets;
        private final long pipelineLayout;
        private final long pipeline;
        private boolean destroyed;

        private Pass(
                RtestVulkanContext context,
                PassSpec spec,
                long descriptorSetLayout,
                long descriptorPool,
                long[] descriptorSets,
                long pipelineLayout,
                long pipeline) {
            this.context = context;
            this.spec = spec;
            this.descriptorSetLayout = descriptorSetLayout;
            this.descriptorPool = descriptorPool;
            this.descriptorSets = descriptorSets;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
        }

        private static Pass create(
                RtestVulkanContext context,
                PassSpec spec,
                Resources resources,
                long pointSampler,
                long linearSampler,
                RtestVulkanBuffer mainConstants,
                RtestVulkanBuffer spdConstants,
                RtestVulkanBuffer rcasConstants) {
            long setLayout = 0L;
            long descriptorPool = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int bindingCount = spec.images.size() + spec.samplers.size() + spec.buffers.size();
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
                int bindingIndex = 0;
                for (ImageSlot slot : spec.images) {
                    bindings.get(bindingIndex++)
                            .binding(slot.binding)
                            .descriptorType(slot.descriptorType)
                            .descriptorCount(1)
                            .stageFlags(COMPUTE_STAGE);
                }
                for (SamplerSlot slot : spec.samplers) {
                    bindings.get(bindingIndex++)
                            .binding(slot.binding)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                            .descriptorCount(1)
                            .stageFlags(COMPUTE_STAGE);
                }
                for (BufferSlot slot : spec.buffers) {
                    bindings.get(bindingIndex++)
                            .binding(slot.binding)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(1)
                            .stageFlags(COMPUTE_STAGE);
                }
                VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pBindings(bindings);
                LongBuffer pointer = stack.mallocLong(2);
                RtestVulkanContext.check(
                        VK12.vkCreateDescriptorSetLayout(context.vkDevice(), setInfo, null, pointer),
                        "create RTest FSR " + spec.label + " descriptor layout");
                setLayout = pointer.get(0);

                VkPushConstantRange.Buffer pushRange = null;
                if (spec.pushConstantSize > 0) {
                    pushRange = VkPushConstantRange.calloc(1, stack)
                            .stageFlags(COMPUTE_STAGE)
                            .offset(0)
                            .size(spec.pushConstantSize);
                }
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(setLayout));
                if (pushRange != null) {
                    pipelineLayoutInfo.pPushConstantRanges(pushRange);
                }
                pointer.clear();
                RtestVulkanContext.check(
                        VK12.vkCreatePipelineLayout(
                                context.vkDevice(), pipelineLayoutInfo, null, pointer),
                        "create RTest FSR " + spec.label + " pipeline layout");
                pipelineLayout = pointer.get(0);

                long shader = createResourceShaderModule(context, stack, spec.shaderResource);
                try {
                    VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(COMPUTE_STAGE)
                            .module(shader)
                            .pName(stack.UTF8("main"));
                    VkComputePipelineCreateInfo.Buffer pipelineInfo =
                            VkComputePipelineCreateInfo.calloc(1, stack);
                    pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                    pointer.clear();
                    RtestVulkanContext.check(
                            VK12.vkCreateComputePipelines(
                                    context.vkDevice(), 0L, pipelineInfo, null, pointer),
                            "create RTest FSR " + spec.label + " pipeline");
                    pipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shader, null);
                }

                VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
                long sampledCount = spec.images.stream()
                        .filter(slot -> slot.descriptorType == VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .count() * 2L;
                long storageCount = spec.images.stream()
                        .filter(slot -> slot.descriptorType == VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .count() * 2L;
                poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE)
                        .descriptorCount(Math.max(1, (int) sampledCount));
                poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(Math.max(1, (int) storageCount));
                poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                        .descriptorCount(Math.max(1, spec.samplers.size() * 2));
                poolSizes.get(3).type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(Math.max(1, spec.buffers.size() * 2));
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .maxSets(2)
                        .pPoolSizes(poolSizes);
                pointer.clear();
                RtestVulkanContext.check(
                        VK12.vkCreateDescriptorPool(context.vkDevice(), poolInfo, null, pointer),
                        "create RTest FSR " + spec.label + " descriptor pool");
                descriptorPool = pointer.get(0);

                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default()
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(setLayout, setLayout));
                pointer.clear();
                RtestVulkanContext.check(
                        VK12.vkAllocateDescriptorSets(context.vkDevice(), allocateInfo, pointer),
                        "allocate RTest FSR " + spec.label + " descriptor sets");
                long[] descriptorSets = new long[] {pointer.get(0), pointer.get(1)};
                for (int parity = 0; parity < 2; parity++) {
                    updateDescriptorSet(
                            context,
                            stack,
                            spec,
                            descriptorSets[parity],
                            parity,
                            resources,
                            pointSampler,
                            linearSampler,
                            mainConstants,
                            spdConstants,
                            rcasConstants);
                }
                return new Pass(
                        context,
                        spec,
                        setLayout,
                        descriptorPool,
                        descriptorSets,
                        pipelineLayout,
                        pipeline);
            } catch (RuntimeException | Error exception) {
                if (descriptorPool != 0L) {
                    VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
                }
                if (pipeline != 0L) {
                    VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
                }
                if (pipelineLayout != 0L) {
                    VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
                }
                if (setLayout != 0L) {
                    VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), setLayout, null);
                }
                throw exception;
            }
        }

        private static void updateDescriptorSet(
                RtestVulkanContext context,
                MemoryStack stack,
                PassSpec spec,
                long descriptorSet,
                int parity,
                Resources resources,
                long pointSampler,
                long linearSampler,
                RtestVulkanBuffer mainConstants,
                RtestVulkanBuffer spdConstants,
                RtestVulkanBuffer rcasConstants) {
            int writeCount = spec.images.size() + spec.samplers.size() + spec.buffers.size();
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            VkDescriptorImageInfo.Buffer imageInfos =
                    VkDescriptorImageInfo.calloc(spec.images.size() + spec.samplers.size(), stack);
            VkDescriptorBufferInfo.Buffer bufferInfos =
                    VkDescriptorBufferInfo.calloc(spec.buffers.size(), stack);
            int writeIndex = 0;
            int imageIndex = 0;
            for (ImageSlot slot : spec.images) {
                imageInfos.get(imageIndex)
                        .imageView(resources.view(slot, parity))
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(writeIndex++)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(slot.binding)
                        .descriptorCount(1)
                        .descriptorType(slot.descriptorType)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(imageIndex).address(), 1));
                imageIndex++;
            }
            for (SamplerSlot slot : spec.samplers) {
                imageInfos.get(imageIndex).sampler(slot.linear ? linearSampler : pointSampler);
                writes.get(writeIndex++)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(slot.binding)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_SAMPLER)
                        .pImageInfo(VkDescriptorImageInfo.create(
                                imageInfos.get(imageIndex).address(), 1));
                imageIndex++;
            }
            int bufferIndex = 0;
            for (BufferSlot slot : spec.buffers) {
                RtestVulkanBuffer buffer = switch (slot.buffer) {
                    case MAIN -> mainConstants;
                    case SPD -> spdConstants;
                    case RCAS -> rcasConstants;
                };
                long range = switch (slot.buffer) {
                    case MAIN -> MAIN_CONSTANT_SIZE;
                    case SPD -> SPD_CONSTANT_SIZE;
                    case RCAS -> RCAS_CONSTANT_SIZE;
                };
                bufferInfos.get(bufferIndex)
                        .buffer(buffer.handle())
                        .offset(0L)
                        .range(range);
                writes.get(writeIndex++)
                        .sType$Default()
                        .dstSet(descriptorSet)
                        .dstBinding(slot.binding)
                        .descriptorCount(1)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.create(
                                bufferInfos.get(bufferIndex).address(), 1));
                bufferIndex++;
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
        }

        private void record(
                VkCommandBuffer commandBuffer,
                int parity,
                int dispatchX,
                int dispatchY,
                ByteBuffer pushConstants) {
            VK12.vkCmdBindPipeline(
                    commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VK12.vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        this.pipelineLayout,
                        0,
                        stack.longs(this.descriptorSets[parity]),
                        null);
            }
            if (pushConstants != null) {
                VK12.vkCmdPushConstants(
                        commandBuffer,
                        this.pipelineLayout,
                        COMPUTE_STAGE,
                        0,
                        pushConstants);
            }
            VK12.vkCmdDispatch(commandBuffer, dispatchX, dispatchY, 1);
        }

        @Override
        public void destroy() {
            if (this.destroyed) {
                return;
            }
            this.destroyed = true;
            VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
            VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
            VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
            VK12.vkDestroyDescriptorSetLayout(
                    this.context.vkDevice(), this.descriptorSetLayout, null);
        }
    }

    private static long createResourceShaderModule(
            RtestVulkanContext context, MemoryStack stack, String resourceName) {
        byte[] bytes;
        try (InputStream input = RtestFsr3Upscaler.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IllegalStateException("Missing shader resource " + resourceName);
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read shader resource " + resourceName, exception);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            RtestVulkanContext.check(
                    VK12.vkCreateShaderModule(context.vkDevice(), createInfo, null, pointer),
                    "create " + resourceName);
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
