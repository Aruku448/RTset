// Prime licensing and additional permissions: see third_party/prime-atmosphere-26.3/LICENSE
// and LICENSE-EXCEPTIONS. Adapted from AtmospherePipeline at 3ab5f75; transport is unchanged.
package com.rtest.client;

import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.rtest.client.atmosphere.AtmosphereMedium;
import com.rtest.client.atmosphere.AtmospherePrecomputation;
import com.rtest.client.atmosphere.SkyLutHistory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK12.*;

/** Owns one immutable medium generation and its seven LUT kernels; callers retire frame fences before close(). */
public final class RayTracingAtmosphere implements AutoCloseable {
    private static final String[] KERNELS = {
        "transmittance", "directions", "incident", "moments", "multi_scattering", "ground", "sky"
    };
    private static final int READ_STAGES = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR;
    private final VulkanDevice device;
    private final SkyLutHistory history = new SkyLutHistory();
    private final SkyLutHistory moonHistory = new SkyLutHistory();
    private final Image[] images = new Image[12];
    // optical, bank zero (source/mean/ground/high), bank one, sun sky, camera T, moon sky.
    private final NativeBuffer[] scratch = new NativeBuffer[3];
    private final long[] pipelines = new long[7];
    private final long[] sets = new long[4];
    private NativeBuffer medium;
    private long sampler, setLayout, pool, pipelineLayout;
    private boolean closed;
    private boolean bootstrapRetired = true;

    private RayTracingAtmosphere(VulkanDevice device) { this.device = device; }

    public static RayTracingAtmosphere create(VulkanDevice device) {
        return create(device, com.rtest.client.atmosphere.AtmosphereSettings.DEFAULT_STEPS);
    }

    public static RayTracingAtmosphere create(VulkanDevice device, int aerosolDensitySteps) {
        com.rtest.client.atmosphere.AtmosphereSettings.densityScale(aerosolDensitySteps);
        RayTracingAtmosphere result = new RayTracingAtmosphere(device);
        try {
            long started = System.nanoTime();
            result.allocate(aerosolDensitySteps);
            result.bootstrap();
            result.releaseSolverResources();
            com.mojang.logging.LogUtils.getLogger().info(
                "Prime atmosphere bootstrap completed: dispatches=290, fenced_batches=10, wall_ms={}",
                (System.nanoTime() - started) / 1_000_000L);
            return result;
        } catch (Throwable failure) {
            // If retirement itself failed, keep GPU-owned allocations alive rather than racing them.
            if (!result.bootstrapRetired) {
                throw new UnretiredWorkException("Atmosphere bootstrap work has not retired", failure);
            }
            try { result.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public long skyView() { requireOpen(); return images[9].view; }
    public long moonSkyView() { requireOpen(); return images[11].view; }
    public long cameraTransmittanceView() { requireOpen(); return images[10].view; }

    // Readonly physical finite-segment interfaces. The medium stays pinned from AtmosphereMedium
    // (four-wave coefficients, bank zero), and these views resolve to the final precomputed bank.
    public long mediumBuffer() { requireOpen(); return medium.buffer; }
    public long mediumSize() { requireOpen(); return medium.size; }
    public long sampler() { requireOpen(); return sampler; }
    public long opticalDepthView() { requireOpen(); return images[0].view; }
    public long scatteringSourceView() { requireOpen(); return images[1].view; }
    public long incidentMeanView() { requireOpen(); return images[2].view; }
    public long groundView() { requireOpen(); return images[3].view; }
    public long highView() { requireOpen(); return images[4].view; }

    public long recordSky(VkCommandBuffer cmd, float eyeRadiusKm, float sunElevationY) {
        return recordSky(cmd, eyeRadiusKm, sunElevationY, false);
    }

    public long recordMoonSky(VkCommandBuffer cmd, float eyeRadiusKm, float moonElevationY) {
        return recordSky(cmd, eyeRadiusKm, moonElevationY, true);
    }

    private long recordSky(VkCommandBuffer cmd, float eyeRadiusKm, float sourceElevationY, boolean moon) {
        requireOpen();
        SkyLutHistory selectedHistory = moon ? moonHistory : history;
        long token = selectedHistory.prepare(eyeRadiusKm, sourceElevationY);
        if (token == 0L) return 0L;
        int skyImage = moon ? 11 : 9;
        int descriptorSet = moon ? 3 : 2;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Both sky kernels produce direction-transmittance. They execute in order and share
            // the same source-independent camera T image, with a barrier between each writer.
            imageBarrier(cmd, stack, skyImage, skyImage + 1, VK_IMAGE_LAYOUT_GENERAL, READ_STAGES,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
            imageBarrier(cmd, stack, 10, 11, VK_IMAGE_LAYOUT_GENERAL, READ_STAGES,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
            ByteBuffer push = stack.calloc(128).order(ByteOrder.nativeOrder());
            push.putFloat(64, eyeRadiusKm).putFloat(84, sourceElevationY);
            dispatch(cmd, stack, 6, sets[descriptorSet], 1, 256, push);
            imageBarrier(cmd, stack, skyImage, skyImage + 1, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, READ_STAGES, VK_ACCESS_SHADER_READ_BIT);
            imageBarrier(cmd, stack, 10, 11, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT, READ_STAGES, VK_ACCESS_SHADER_READ_BIT);
            return token;
        } catch (Throwable failure) {
            selectedHistory.abandon(token);
            throw failure;
        }
    }

    /** Call only after the actual frame fence has completed, not merely after submission. */
    public void completed(long token) { requireOpen(); history.completed(token); }
    public void abandon(long token) { requireOpen(); history.abandon(token); }
    public void moonCompleted(long token) { requireOpen(); moonHistory.completed(token); }
    public void moonAbandon(long token) { requireOpen(); moonHistory.abandon(token); }
    private void requireOpen() { if (closed) throw new IllegalStateException("Atmosphere is closed"); }

    private void allocate(int aerosolDensitySteps) {
        requireFormat(VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
            | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        requireFormat(VK_FORMAT_R32G32B32A32_SFLOAT,
            VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT);
        images[0] = image(512, 128, VK_FORMAT_R16G16B16A16_SFLOAT, true);
        for (int bank = 0; bank < 2; bank++) {
            int base = 1 + bank * 4;
            images[base] = image(3200, 240, VK_FORMAT_R16G16B16A16_SFLOAT, true);
            images[base + 1] = image(160, 40, VK_FORMAT_R32G32B32A32_SFLOAT, true);
            images[base + 2] = image(160, 1, VK_FORMAT_R32G32B32A32_SFLOAT, true);
            images[base + 3] = image(800, 21, VK_FORMAT_R32G32B32A32_SFLOAT, true);
        }
        images[9] = image(256, 256, VK_FORMAT_R32G32B32A32_SFLOAT, false);
        images[10] = image(8193, 1, VK_FORMAT_R16G16B16A16_SFLOAT, false);
        images[11] = image(256, 256, VK_FORMAT_R32G32B32A32_SFLOAT, false);
        for (int i = 0; i < 3; i++) {
            scratch[i] = NativeBuffer.create(device, 4L * 160 * (i == 2 ? 7 : 1536) * 16,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false);
        }
        byte[] bytes = AtmosphereMedium.load(aerosolDensitySteps);
        medium = NativeBuffer.create(device, bytes.length, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
        try (NativeBuffer.Mapped mapped = medium.map()) { mapped.buffer().put(bytes); }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocLong(1);
            var samplerInfo = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxAnisotropy(1.0F);
            check(vkCreateSampler(device.vkDevice(), samplerInfo, null, pointer), "create sampler");
            sampler = pointer.get(0);
            var bindings = VkDescriptorSetLayoutBinding.calloc(33, stack);
            for (int binding = 0; binding < 33; binding++) {
                bindings.get(binding).binding(binding).descriptorCount(1)
                    .descriptorType(descriptorType(binding)).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            var layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
            check(vkCreateDescriptorSetLayout(device.vkDevice(), layoutInfo, null, pointer), "create descriptor layout");
            setLayout = pointer.get(0);
            var range = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(128);
            var pipelineInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(range);
            check(vkCreatePipelineLayout(device.vkDevice(), pipelineInfo, null, pointer), "create pipeline layout");
            pipelineLayout = pointer.get(0);
            var sizes = VkDescriptorPoolSize.calloc(3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(8);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(16);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(108);
            var poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT).maxSets(4).pPoolSizes(sizes);
            check(vkCreateDescriptorPool(device.vkDevice(), poolInfo, null, pointer), "create descriptor pool");
            pool = pointer.get(0);
            var allocation = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(stack.longs(setLayout, setLayout, setLayout, setLayout));
            var handles = stack.mallocLong(4);
            check(vkAllocateDescriptorSets(device.vkDevice(), allocation, handles), "allocate descriptor sets");
            for (int i = 0; i < 4; i++) { sets[i] = handles.get(i); writeSet(stack, i); }
        }
        for (int i = 0; i < KERNELS.length; i++) pipelines[i] = createPipeline(KERNELS[i]);
    }

    private void requireFormat(int format, int requiredFeatures) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), format, properties);
            if ((properties.optimalTilingFeatures() & requiredFeatures) != requiredFeatures) {
                throw new IllegalStateException("Prime atmosphere LUT format features unavailable: " + format);
            }
        }
    }

    /** Static solver temporaries have no references in the final sky set and are now fenced. */
    private void releaseSolverResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            check(vkFreeDescriptorSets(device.vkDevice(), pool, stack.longs(sets[0], sets[1])),
                "retire solver descriptor sets");
            sets[0] = sets[1] = 0L;
        }
        for (int i = 0; i < scratch.length; i++) {
            scratch[i].close();
            scratch[i] = null;
        }
        for (int i = 5; i < 9; i++) {
            images[i].close();
            images[i] = null;
        }
        for (int i = 0; i < 6; i++) {
            vkDestroyPipeline(device.vkDevice(), pipelines[i], null);
            pipelines[i] = 0L;
        }
    }

    private static int descriptorType(int binding) {
        if (binding < 2) return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        if (binding == 7 || binding >= 29 && binding <= 31) return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        return VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }

    private void writeSet(MemoryStack stack, int set) {
        int bank = set >= 2 ? AtmospherePrecomputation.finalBank() : set;
        int base = 1 + bank * 4;
        int[] readBindings = {0, 1, 2, 3, 4, 23, 24};
        int[] readImages = {0, base, base + 1, base + 2, set == 3 ? 11 : 9, 10, base + 3};
        for (int i = 0; i < readBindings.length; i++) writeImage(stack, sets[set], readBindings[i], readImages[i]);
        writeBuffer(stack, sets[set], 7, medium);
        // Final read-only set never points at scratch or the opposite bank.
        if (set < 2) {
            int opposite = 1 + (1 - bank) * 4;
            for (int i = 0; i < 4; i++) writeImage(stack, sets[set], 25 + i, opposite + i);
            for (int i = 0; i < 3; i++) writeBuffer(stack, sets[set], 29 + i, scratch[i]);
            writeImage(stack, sets[set], 32, 0);
        }
    }

    private void writeImage(MemoryStack stack, long set, int binding, int image) {
        var info = VkDescriptorImageInfo.calloc(1, stack).imageView(images[image].view)
            .imageLayout(VK_IMAGE_LAYOUT_GENERAL).sampler(binding < 2 ? sampler : 0L);
        var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding)
            .descriptorType(descriptorType(binding)).descriptorCount(1).pImageInfo(info);
        vkUpdateDescriptorSets(device.vkDevice(), write, null);
    }

    private void writeBuffer(MemoryStack stack, long set, int binding, NativeBuffer buffer) {
        var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.buffer).offset(0L).range(buffer.size);
        var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(binding)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(info);
        vkUpdateDescriptorSets(device.vkDevice(), write, null);
    }

    /** Classpath-only asset seam, also usable without a Vulkan device. */
    public static byte[] readKernel(String suffix) {
        if (!java.util.Arrays.asList(KERNELS).contains(suffix)) throw new IllegalArgumentException("Unknown kernel " + suffix);
        String path = "/prime/atmosphere/shaders/atmosphere_" + suffix + ".comp.spv";
        try (InputStream stream = RayTracingAtmosphere.class.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing " + path);
            byte[] bytes = stream.readAllBytes();
            if (bytes.length < 20 || bytes.length % 4 != 0
                    || ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt() != 0x07230203) {
                throw new IllegalStateException("Invalid SPIR-V " + path);
            }
            return bytes;
        } catch (IOException failure) { throw new IllegalStateException("Read " + path, failure); }
    }

    private long createPipeline(String suffix) {
        byte[] bytes = readKernel(suffix);
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            code.put(bytes).flip();
            var pointer = stack.mallocLong(1);
            var shader = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            check(vkCreateShaderModule(device.vkDevice(), shader, null, pointer), "create " + suffix + " module");
            long module = pointer.get(0);
            try {
                var info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                pointer.put(0, 0L);
                int result = vkCreateComputePipelines(device.vkDevice(), 0L, info, null, pointer);
                if (result != VK_SUCCESS && pointer.get(0) != 0L) vkDestroyPipeline(device.vkDevice(), pointer.get(0), null);
                check(result, "create " + suffix + " pipeline");
                return pointer.get(0);
            } finally { vkDestroyShaderModule(device.vkDevice(), module, null); }
        } finally { MemoryUtil.memFree(code); }
    }

    private void bootstrap() {
        VulkanCommandEncoder encoder = new VulkanCommandEncoder(device);
        GpuFence fence = null;
        Throwable failure = null;
        try {
            var plan = AtmospherePrecomputation.plan();
            for (int start = 0; start < plan.size(); start += 32) {
                VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
                boolean ended = false;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (start == 0) {
                        imageBarrier(cmd, stack, 0, images.length, VK_IMAGE_LAYOUT_UNDEFINED,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
                    }
                    ByteBuffer push = stack.calloc(16).order(ByteOrder.nativeOrder());
                    for (int i = start; i < Math.min(start + 32, plan.size()); i++) {
                        var step = plan.get(i);
                        push.putInt(0, step.firstHeight()).putInt(4, step.heightCount()).putInt(8, step.iteration());
                        dispatch(cmd, stack, step.stage().ordinal(), sets[step.bank()], step.x(), step.y(), push);
                        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    }
                    if (start + 32 >= plan.size()) {
                        // Final static fields are now sampled by RT as well as the Sky compute.
                        memoryBarrier(cmd, stack, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_WRITE_BIT, READ_STAGES, VK_ACCESS_SHADER_READ_BIT);
                    }
                    check(vkEndCommandBuffer(cmd), "end atmosphere bootstrap command buffer");
                    ended = true;
                    encoder.execute(cmd);
                } finally { if (!ended) vkEndCommandBuffer(cmd); }
                fence = encoder.createFence();
                bootstrapRetired = false;
                encoder.submit();
                if (!fence.awaitCompletion(30_000_000_000L)) {
                    throw new IllegalStateException("Atmosphere bootstrap fence timed out");
                }
                bootstrapRetired = true;
                GpuFence retiredFence = fence;
                fence = null;
                retiredFence.close();
            }
        } catch (Throwable primaryFailure) {
            failure = primaryFailure;
        } finally {
            // A timeout is not retirement. Preserve it as the primary failure rather than
            // replacing it with an exception from a finally block or a second idle attempt.
            if (!bootstrapRetired) {
                try {
                    retireBootstrap();
                } catch (Throwable retirementFailure) {
                    failure = appendFailure(failure, retirementFailure);
                }
            }
            if (bootstrapRetired) {
                failure = closeCapturing(fence, failure);
                try {
                    encoder.destroy();
                } catch (Throwable cleanupFailure) {
                    failure = appendFailure(failure, cleanupFailure);
                }
            }
        }
        rethrow(failure);
    }

    private void retireBootstrap() {
        int result = vkDeviceWaitIdle(device.vkDevice());
        bootstrapRetired = result == VK_SUCCESS || result == VK_ERROR_DEVICE_LOST;
        if (result == VK_ERROR_DEVICE_LOST) {
            // Device loss terminates the work, permitting cleanup, but never permits rendering fallback.
            throw new DeviceLostException("Device lost while retiring atmosphere bootstrap");
        }
        if (!bootstrapRetired) {
            throw new UnretiredWorkException("Failed to retire atmosphere bootstrap: Vulkan result " + result, null);
        }
    }

    private void dispatch(VkCommandBuffer cmd, MemoryStack stack, int pipeline, long set,
                          int x, int y, ByteBuffer push) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[pipeline]);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
        vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(cmd, x, y, 1);
    }

    private static void memoryBarrier(VkCommandBuffer cmd, MemoryStack stack, int srcStage, int srcAccess,
                                      int dstStage, int dstAccess) {
        var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, barrier, null, null);
    }

    private void imageBarrier(VkCommandBuffer cmd, MemoryStack stack, int first, int end, int oldLayout,
                              int srcStage, int srcAccess, int dstStage, int dstAccess) {
        var barriers = VkImageMemoryBarrier.calloc(end - first, stack);
        for (int i = first; i < end; i++) {
            var barrier = barriers.get(i - first).sType$Default().image(images[i].image)
                .oldLayout(oldLayout).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        }
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barriers);
    }

    private Image image(int width, int height, int format, boolean sampled) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_STORAGE_BIT | (sampled ? VK_IMAGE_USAGE_SAMPLED_BIT : 0))
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            var allocationInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var handle = stack.mallocLong(1);
            var allocation = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(device.vma(), info, allocationInfo, handle, allocation, null), "create LUT image");
            long image = handle.get(0), memory = allocation.get(0);
            try {
                var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device.vkDevice(), viewInfo, null, handle), "create LUT view");
                return new Image(image, memory, handle.get(0));
            } catch (Throwable failure) { Vma.vmaDestroyImage(device.vma(), image, memory); throw failure; }
        }
    }

    private static void check(int result, String operation) {
        if (result == VK_ERROR_DEVICE_LOST) throw new DeviceLostException(operation + ": Vulkan result " + result);
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + ": Vulkan result " + result);
    }

    /** Caller must have retired every frame which references these LUTs. */
    @Override public void close() {
        if (closed) return;
        if (!bootstrapRetired) throw new IllegalStateException("Bootstrap is still GPU-owned");
        closed = true;
        if (pool != 0L) vkDestroyDescriptorPool(device.vkDevice(), pool, null);
        for (int i = pipelines.length - 1; i >= 0; i--) {
            if (pipelines[i] != 0L) vkDestroyPipeline(device.vkDevice(), pipelines[i], null);
        }
        if (pipelineLayout != 0L) vkDestroyPipelineLayout(device.vkDevice(), pipelineLayout, null);
        if (setLayout != 0L) vkDestroyDescriptorSetLayout(device.vkDevice(), setLayout, null);
        if (sampler != 0L) vkDestroySampler(device.vkDevice(), sampler, null);
        Throwable failure = null;
        for (int i = images.length - 1; i >= 0; i--) failure = closeCapturing(images[i], failure);
        for (int i = scratch.length - 1; i >= 0; i--) failure = closeCapturing(scratch[i], failure);
        failure = closeCapturing(medium, failure);
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Atmosphere cleanup failed", failure);
    }

    /** Explicit ownership failure: callers must not classify it as a legacy-sky fallback. */
    static final class UnretiredWorkException extends IllegalStateException {
        UnretiredWorkException(String message, Throwable cause) { super(message, cause); }
    }

    static final class DeviceLostException extends IllegalStateException {
        DeviceLostException(String message) { super(message); }
    }

    private static Throwable appendFailure(Throwable previous, Throwable failure) {
        if (previous == null) return failure;
        previous.addSuppressed(failure);
        return previous;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure != null) throw new IllegalStateException("Atmosphere bootstrap failed", failure);
    }

    private static Throwable closeCapturing(AutoCloseable resource, Throwable previous) {
        if (resource == null) return previous;
        try {
            resource.close();
        } catch (Throwable failure) {
            if (previous == null) return failure;
            previous.addSuppressed(failure);
        }
        return previous;
    }

    private final class Image implements AutoCloseable {
        final long image, allocation, view;
        private boolean closed;
        Image(long image, long allocation, long view) { this.image = image; this.allocation = allocation; this.view = view; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                vkDestroyImageView(device.vkDevice(), view, null);
            } finally {
                Vma.vmaDestroyImage(device.vma(), image, allocation);
            }
        }
    }
}
