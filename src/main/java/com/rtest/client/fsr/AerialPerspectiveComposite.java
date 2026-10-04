package com.rtest.client.fsr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Post-NRD additive composite for the physical finite-segment solar in-scatter AOV (L).
 *
 * <p>Runs after {@code NrdDenoiser}'s own composite and before FSR upscaling, at the same entry
 * point for OFF and legacy modes. The {@code enabled} push constant gates the read so an
 * uninitialized L image is never consumed; depth <= 0 (sky) never receives finite-segment fog.
 */
public final class AerialPerspectiveComposite implements AutoCloseable {
    private static final int COMPUTE_STAGE = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
    private static final int BINDING_COUNT = 3;
    private static final int PUSH_SIZE = 4;

    private final RtestVulkanContext context;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private boolean destroyed;

    private AerialPerspectiveComposite(
            RtestVulkanContext context,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline) {
        this.context = context;
        this.descriptorSetLayout = descriptorSetLayout;
        this.descriptorPool = descriptorPool;
        this.descriptorSet = descriptorSet;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    public static AerialPerspectiveComposite create(
            RtestVulkanContext context,
            RtestVulkanImage sceneColor,
            RtestVulkanImage fsrDepth,
            RtestVulkanImage physicalAerialL) {
        long descriptorSetLayout = 0L;
        long descriptorPool = 0L;
        long descriptorSet = 0L;
        long pipelineLayout = 0L;
        long pipeline = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < BINDING_COUNT; index++) {
                bindings.get(index).binding(index)
                        .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(COMPUTE_STAGE);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer pointer = stack.mallocLong(1);
            RtestVulkanContext.check(VK12.vkCreateDescriptorSetLayout(
                    context.vkDevice(), layoutInfo, null, pointer),
                    "create aerial perspective composite descriptor layout");
            descriptorSetLayout = pointer.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(COMPUTE_STAGE).offset(0).size(PUSH_SIZE);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descriptorSetLayout)).pPushConstantRanges(pushRange);
            RtestVulkanContext.check(VK12.vkCreatePipelineLayout(
                    context.vkDevice(), pipelineLayoutInfo, null, pointer),
                    "create aerial perspective composite pipeline layout");
            pipelineLayout = pointer.get(0);

            long shaderModule = createShaderModule(context, stack);
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType$Default().stage(COMPUTE_STAGE).module(shaderModule).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
                pointer.put(0, 0L);
                int result = VK12.vkCreateComputePipelines(
                    context.vkDevice(), 0L, pipelineInfo, null, pointer);
                pipeline = pointer.get(0);
                RtestVulkanContext.check(result, "create aerial perspective composite pipeline");
            } finally {
                VK12.vkDestroyShaderModule(context.vkDevice(), shaderModule, null);
            }

            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack)
                    .type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDING_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(1).pPoolSizes(poolSize);
            pointer.clear();
            RtestVulkanContext.check(VK12.vkCreateDescriptorPool(
                    context.vkDevice(), poolInfo, null, pointer),
                    "create aerial perspective composite descriptor pool");
            descriptorPool = pointer.get(0);

            VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout));
            pointer.clear();
            RtestVulkanContext.check(VK12.vkAllocateDescriptorSets(
                    context.vkDevice(), allocateInfo, pointer),
                    "allocate aerial perspective composite descriptor set");
            descriptorSet = pointer.get(0);

            RtestVulkanImage[] descriptorImages = {sceneColor, fsrDepth, physicalAerialL};
            VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(BINDING_COUNT, stack);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDING_COUNT, stack);
            for (int index = 0; index < BINDING_COUNT; index++) {
                infos.get(index).imageView(descriptorImages[index].view())
                        .imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(index).sType$Default().dstSet(descriptorSet).dstBinding(index)
                        .descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(VkDescriptorImageInfo.create(infos.get(index).address(), 1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            return new AerialPerspectiveComposite(context, descriptorSetLayout, descriptorPool,
                    descriptorSet, pipelineLayout, pipeline);
        } catch (RuntimeException | Error exception) {
            if (pipeline != 0L) VK12.vkDestroyPipeline(context.vkDevice(), pipeline, null);
            if (pipelineLayout != 0L) VK12.vkDestroyPipelineLayout(context.vkDevice(), pipelineLayout, null);
            if (descriptorPool != 0L) VK12.vkDestroyDescriptorPool(context.vkDevice(), descriptorPool, null);
            if (descriptorSetLayout != 0L) {
                VK12.vkDestroyDescriptorSetLayout(context.vkDevice(), descriptorSetLayout, null);
            }
            throw exception;
        }
    }

    public void record(VkCommandBuffer commandBuffer, int width, int height, boolean enabled) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);
            VK12.vkCmdBindDescriptorSets(commandBuffer, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.pipelineLayout, 0, stack.longs(this.descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_SIZE).order(ByteOrder.nativeOrder());
            push.putFloat(enabled ? 1.0f : 0.0f).flip();
            VK12.vkCmdPushConstants(commandBuffer, this.pipelineLayout, COMPUTE_STAGE, 0, push);
            VK12.vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
        }
    }

    private static long createShaderModule(RtestVulkanContext context, MemoryStack stack) {
        byte[] bytes;
        try (InputStream input = AerialPerspectiveComposite.class.getResourceAsStream(
                "/prime/shaders/aerial_perspective_composite.comp.spv")) {
            if (input == null) {
                throw new IllegalStateException("Missing aerial perspective composite SPIR-V");
            }
            bytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read aerial perspective composite SPIR-V", exception);
        }
        if (bytes.length < 20 || bytes.length % 4 != 0
                || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0x07230203) {
            throw new IllegalStateException("Invalid aerial perspective composite SPIR-V");
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try {
            code.put(bytes).flip();
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(code);
            LongBuffer pointer = stack.mallocLong(1);
            RtestVulkanContext.check(VK12.vkCreateShaderModule(
                    context.vkDevice(), createInfo, null, pointer),
                    "create aerial perspective composite module");
            return pointer.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    @Override
    public void close() {
        destroy();
    }

    public void destroy() {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;
        VK12.vkDestroyDescriptorPool(this.context.vkDevice(), this.descriptorPool, null);
        VK12.vkDestroyPipeline(this.context.vkDevice(), this.pipeline, null);
        VK12.vkDestroyPipelineLayout(this.context.vkDevice(), this.pipelineLayout, null);
        VK12.vkDestroyDescriptorSetLayout(this.context.vkDevice(), this.descriptorSetLayout, null);
    }
}