package com.rtest.client.fsr;

import com.mojang.blaze3d.vulkan.Destroyable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Small independent temporal-spatial RT denoiser inspired by the public behavior of Sundial's
 * GI filter. It is not an NRD pipeline and does not reuse shader-pack source code.
 */
final class SundialDenoiser implements Destroyable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int BINDING_COUNT = 12;
    private static final int PUSH_CONSTANT_SIZE = 16;
    private static final int HISTORY_USAGE = VK12.VK_IMAGE_USAGE_STORAGE_BIT
            | VK12.VK_IMAGE_USAGE_SAMPLED_BIT;

    private final RtestVulkanContext context;
    private final int width;
    private final int height;
    private final long pipelineLayout;
    private final long pipeline;
    private final long descriptorPool;
    private final long descriptorSetLayout;
    private final long[] descriptorSets;
    private final RtestVulkanImage[] historyColor;
    private final RtestVulkanImage[] historyMeta;
    private final RtestVulkanImage output;
    private boolean initialized;
    private boolean destroyed;
    private int parity;
    private FrameToken pendingToken;

    private SundialDenoiser(
            RtestVulkanContext context,
            int width,
            int height,
            long pipelineLayout,
            long pipeline,
            long descriptorPool,
            long descriptorSetLayout,
            long[] descriptorSets,
            RtestVulkanImage[] historyColor,
            RtestVulkanImage[] historyMeta,
            RtestVulkanImage output) {
        this.context = context;
        this.width = width;
        this.height = height;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
        this.descriptorPool = descriptorPool;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorSets = descriptorSets;
        this.historyColor = historyColor;
        this.historyMeta = historyMeta;
        this.output = output;
    }

    static SundialDenoiser create(
            RtestVulkanContext context,
            int width,
            int height,
            long noisyDiffuseView,
            long noisySpecularView,
            long normalRoughnessView,
            long viewZView,
            long motionView,
            long directDiffuseView,
            long emissionView,
            RtestVulkanImage output) {
        RtestVulkanImage[] historyColor = new RtestVulkanImage[2];
        RtestVulkanImage[] historyMeta = new RtestVulkanImage[2];
        long descriptorSetLayout = 0L;
        long descriptorPool = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try {
            for (int index = 0; index < 2; index++) {
                historyColor[index] = context.createImage2D(
                        width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        HISTORY_USAGE, "RTest Sundial history color " + index);
                historyMeta[index] = context.createImage2D(
                        width, height, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                        HISTORY_USAGE, "RTest Sundial history metadata " + index);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
                for (int index = 0; index < BINDING_COUNT; index++) {
                    bindings.get(index).binding(index)
                            .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1)
                            .stageFlags(COMPUTE_STAGE);
                }
                VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType$Default().pBindings(bindings);
                LongBuffer pointer = stack.mallocLong(1);
                RtestVulkanContext.check(VK12.vkCreateDescriptorSetLayout(
                        context.vkDevice(), layoutInfo, null, pointer),
                        "create RTest Sundial descriptor layout");
                descriptorSetLayout = pointer.get(0);

                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_CONSTANT_SIZE);
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout))
                        .pPushConstantRanges(pushRange);
                RtestVulkanContext.check(VK12.vkCreatePipelineLayout(
                        context.vkDevice(), pipelineLayoutInfo, null, pointer),
                        "create RTest Sundial pipeline layout");
                pipelineLayout = pointer.get(0);

                long shaderModule = createResourceShaderModule(
                        context, stack, "/prime/shaders/sundial_denoiser.comp.spv");
                try {
                    VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default().stage(COMPUTE_STAGE).module(shaderModule)
                            .pName(stack.UTF8("main"));
                    VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                    pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                    pointer.clear();
                    RtestVulkanContext.check(VK12.vkCreateComputePipelines(
                            context.vkDevice(), 0L, pipelineInfo, null, pointer),
                            "create RTest Sundial pipeline");
                    pipeline = pointer.get(0);
                } finally {
                    VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
                }

                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                        .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(BINDING_COUNT * 2);
                VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType$Default().maxSets(2).pPoolSizes(poolSize);
                pointer.clear();
                RtestVulkanContext.check(VK12.vkCreateDescriptorPool(
                        context.vkDevice(), poolInfo, null, pointer),
                        "create RTest Sundial descriptor pool");
                descriptorPool = pointer.get(0);

                VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType$Default().descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(descriptorSetLayout, descriptorSetLayout));
                LongBuffer setPointer = stack.mallocLong(2);
                RtestVulkanContext.check(VK12.vkAllocateDescriptorSets(
                        context.vkDevice(), allocateInfo, setPointer),
                        "allocate RTest Sundial descriptor sets");
                long[] descriptorSets = {setPointer.get(0), setPointer.get(1)};

                for (int parity = 0; parity < 2; parity++) {
                    RtestVulkanImage currentColor = historyColor[parity];
                    RtestVulkanImage currentMeta = historyMeta[parity];
                    RtestVulkanImage nextColor = historyColor[1 - parity];
                    RtestVulkanImage nextMeta = historyMeta[1 - parity];
                    long[] views = {
                        noisyDiffuseView, noisySpecularView, normalRoughnessView, viewZView, motionView,
                        currentColor.view(), currentMeta.view(), nextColor.view(), nextMeta.view(),
                        output.view(), directDiffuseView, emissionView
                    };
                    VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
                    for (int binding = 0; binding < BINDING_COUNT; binding++) {
                        infos.get(binding).imageView(views[binding])
                                .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                        writes.get(binding).sType$Default()
                                .dstSet(descriptorSets[parity])
                                .dstBinding(binding)
                                .descriptorCount(1)
                                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(infos.get(binding).address(), 1));
                    }
                    VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
                }
                return new SundialDenoiser(
                        context, width, height, pipelineLayout, pipeline,
                        descriptorPool, descriptorSetLayout, descriptorSets,
                        historyColor, historyMeta, output);
            }
        } catch (RuntimeException | Error exception) {
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (descriptorSetLayout != 0L) VK12.vkDestroyDescriptorSetLayout(
                    context.vkDevice(), descriptorSetLayout, null);
            for (int index = 1; index >= 0; index--) {
                if (historyMeta[index] != null) historyMeta[index].destroy();
                if (historyColor[index] != null) historyColor[index].destroy();
            }
            throw exception;
        }
    }

    void prepareForRayTrace(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!this.initialized) {
                VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(4, stack);
                RtestVulkanImage[] images = {
                    this.historyColor[0], this.historyColor[1],
                    this.historyMeta[0], this.historyMeta[1]
                };
                for (int index = 0; index < images.length; index++) {
                    barriers.get(index).sType$Default()
                            .srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                            .srcAccessMask(0L)
                            .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                            .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                            .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                            .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                            .image(images[index].image());
                    barriers.get(index).subresourceRange()
                            .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1);
                    images[index].markInitialized();
                }
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                        VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            } else {
                VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                        .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .srcAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT)
                        .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                        .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                        VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
            }
        }
    }

    FrameToken record(VkCommandBuffer commandBuffer, boolean reset, float strength, int historyFrames) {
        if (this.destroyed || this.pendingToken != null) {
            throw new IllegalStateException("RTest Sundial frame is already pending");
        }
        int readParity = this.parity;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout, 0, stack.longs(this.descriptorSets[readParity]), null);
            ByteBuffer push = stack.malloc(PUSH_CONSTANT_SIZE).order(ByteOrder.nativeOrder());
            push.putFloat(clamp(strength, 0.0f, 1.0f));
            push.putFloat(Math.max(8.0f, Math.min(128.0f, historyFrames)));
            push.putFloat(reset ? 1.0f : 0.0f);
            push.putFloat(0.0f).flip();
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(commandBuffer, (this.width + 7) / 8, (this.height + 7) / 8, 1);

            VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .srcAccessMask(VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
        this.pendingToken = new FrameToken(this, readParity);
        return this.pendingToken;
    }

    void cancel(FrameToken token) {
        if (token == null || token.owner != this || token.cancelled || token != this.pendingToken) {
            throw new IllegalArgumentException("RTest Sundial frame token does not belong to this denoiser");
        }
        token.cancelled = true;
        this.pendingToken = null;
    }

    void cancelPending() {
        if (this.pendingToken != null) {
            this.pendingToken.cancelled = true;
            this.pendingToken = null;
        }
    }

    void submitted(FrameToken token) {
        if (token == null || token.owner != this || token.cancelled || token != this.pendingToken) {
            throw new IllegalArgumentException("RTest Sundial frame token does not belong to this denoiser");
        }
        this.parity = 1 - token.readParity;
        this.pendingToken = null;
    }

    @Override
    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        this.pendingToken = null;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
        for (int index = 1; index >= 0; index--) {
            this.historyMeta[index].destroy();
            this.historyColor[index].destroy();
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long createResourceShaderModule(
            RtestVulkanContext context, MemoryStack stack, String resourceName) {
        byte[] bytes;
        try (InputStream input = SundialDenoiser.class.getResourceAsStream(resourceName)) {
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
                    .sType$Default().pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            RtestVulkanContext.check(VK12.vkCreateShaderModule(
                    context.vkDevice(), createInfo, null, pointer),
                    "create RTest Sundial shader module");
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    static final class FrameToken {
        private final SundialDenoiser owner;
        private final int readParity;
        private boolean cancelled;

        private FrameToken(SundialDenoiser owner, int readParity) {
            this.owner = owner;
            this.readParity = readParity;
        }
    }
}
