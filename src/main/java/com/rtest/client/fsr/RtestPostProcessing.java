package com.rtest.client.fsr;

import static org.lwjgl.vulkan.VK12.*;

import com.rtest.client.HdrSupport;
import com.rtest.client.RayTracingClientConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LightLayer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * Owns display history and lens/bloom resources; does not alter FSR or NRD
 * histories.
 */
final class RtestPostProcessing implements AutoCloseable {
    private static final int PARAMETER_BYTES = 176;
    private final RtestVulkanContext context;
    private final int width, height, levels;
    private final RtestVulkanImage scene, motion, depth, output;
    private final ArrayList<RtestVulkanImage> owned = new ArrayList<>();
    private RtestVulkanImage state, dof, optics, bloom, graded, coc, bloomScratch;
    private RtestVulkanBuffer parameters;
    private long setLayout, layout, pipeline, pool, sampler;
    private long[] sets;
    private boolean initialized, lastEnabled;

    RtestPostProcessing(RtestVulkanContext context, RtestVulkanImage scene, RtestVulkanImage motion,
        RtestVulkanImage depth, RtestVulkanImage output) {
        this.context = context;
        this.scene = scene;
        this.motion = motion;
        this.depth = depth;
        this.output = output;
        width = scene.width();
        height = scene.height();
        int bw = Math.max(1, width / 2), bh = Math.max(1, height / 2);
        levels = Math.min(7, 1 + (31 - Integer.numberOfLeadingZeros(Math.max(bw, bh))));
        try {
            state = image(1, 1, VK_FORMAT_R32G32B32A32_SFLOAT, "post exposure/focus");
            coc = image(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, "post signed CoC spread");
            dof = image(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, "post DOF");
            optics = image(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, "post motion blur");
            graded = image(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, "post display grade");
            bloom = context.createMipmappedImage2D(bw, bh, levels, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                "post bloom");
            owned.add(bloom);
            bloomScratch = context.createMipmappedImage2D(bw, bh, levels, VK_FORMAT_R16G16B16A16_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                "post bloom Gaussian scratch");
            owned.add(bloomScratch);
            parameters = context.createBuffer(PARAMETER_BYTES,
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false,
                "post parameters");
            createPipeline();
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }
    private RtestVulkanImage image(int w, int h, int format, String name) {
        var image = context.createImage2D(w, h, format,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, name);
        owned.add(image);
        return image;
    }
    private static int type(int binding) {
        return switch (binding) {
            case 0, 1, 2, 5, 13 -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case 8 -> VK_DESCRIPTOR_TYPE_SAMPLER;
            case 10 -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            default -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        };
    }
    private void createPipeline() {
        try (var stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(15, stack);
            for (int i = 0; i < 15; i++)
                bindings.get(i).binding(i).descriptorCount(1).descriptorType(type(i)).stageFlags(
                    VK_SHADER_STAGE_COMPUTE_BIT);
            var out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.vkDevice(),
                VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null,
                out));
            setLayout = out.get(0);
            var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(16);
            check(vkCreatePipelineLayout(context.vkDevice(),
                VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayout))
                    .pPushConstantRanges(push),
                null, out));
            layout = out.get(0);
            String resource = output.format() == VK_FORMAT_R8G8B8A8_UNORM
                ? "/rtest/shaders/post_processing.comp.spv"
                : "/rtest/shaders/post_processing_hdr.comp.spv";
            long module = 0;
            ByteBuffer code = null;
            try (var in = RtestPostProcessing.class.getResourceAsStream(resource)) {
                byte[] bytes = java.util.Objects.requireNonNull(in, resource).readAllBytes();
                code = MemoryUtil.memAlloc(bytes.length).order(ByteOrder.nativeOrder());
                code.put(bytes).flip();
                check(vkCreateShaderModule(context.vkDevice(),
                    VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out));
                module = out.get(0);
                var stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                                .sType$Default()
                                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                                .module(module)
                                .pName(stack.UTF8("main"));
                var info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().stage(stage).layout(layout);
                check(vkCreateComputePipelines(context.vkDevice(), 0, info, null, out));
                pipeline = out.get(0);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            } finally {
                if (module != 0)
                    vkDestroyShaderModule(context.vkDevice(), module, null);
                if (code != null)
                    MemoryUtil.memFree(code);
            }
            var sample = VkSamplerCreateInfo.calloc(stack)
                             .sType$Default()
                             .minFilter(VK_FILTER_LINEAR)
                             .magFilter(VK_FILTER_LINEAR)
                             .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                             .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                             .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                             .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                             .minLod(0)
                             .maxLod(levels - 1);
            check(vkCreateSampler(context.vkDevice(), sample, null, out));
            sampler = out.get(0);
            var sizes = VkDescriptorPoolSize.calloc(4, stack);
            int[] types = {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK_DESCRIPTOR_TYPE_SAMPLER, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER};
            int[] counts = {5, 8, 1, 1};
            for (int i = 0; i < 4; i++) sizes.get(i).type(types[i]).descriptorCount(counts[i] * levels);
            check(vkCreateDescriptorPool(context.vkDevice(),
                VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(levels).pPoolSizes(sizes),
                null, out));
            pool = out.get(0);
            var handles = stack.mallocLong(levels);
            var layouts = stack.mallocLong(levels);
            for (int i = 0; i < levels; i++) layouts.put(i, setLayout);
            check(vkAllocateDescriptorSets(context.vkDevice(),
                VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(
                    layouts),
                handles));
            sets = new long[levels];
            for (int level = 0; level < levels; level++) {
                sets[level] = handles.get(level);
                var writes = VkWriteDescriptorSet.calloc(15, stack);
                for (int i = 0; i < 15; i++) {
                    var write = writes.get(i)
                                    .sType$Default()
                                    .dstSet(sets[level])
                                    .dstBinding(i)
                                    .descriptorCount(1)
                                    .descriptorType(type(i));
                    if (i == 10)
                        write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(parameters.handle())
                                .range(PARAMETER_BYTES));
                    else {
                        var info =
                            VkDescriptorImageInfo.calloc(1, stack).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        if (i == 8)
                            info.sampler(sampler);
                        else
                            info.imageView(switch (i) {
                                case 0 -> scene.view();
                                case 1 -> motion.view();
                                case 2 -> depth.view();
                                case 3 -> state.view();
                                case 4 -> optics.view();
                                case 5 -> bloom.view();
                                case 6 -> bloom.mipView(level);
                                case 7 -> graded.view();
                                case 9 -> output.view();
                                case 11 -> dof.view();
                                case 12 -> coc.view();
                                case 13 -> bloomScratch.view();
                                case 14 -> bloomScratch.mipView(level);
                                default -> throw new AssertionError();
                            });
                        write.pImageInfo(info);
                    }
                }
                vkUpdateDescriptorSets(context.vkDevice(), writes, null);
            }
        }
    }
    void disabled() {
        lastEnabled = false;
    }
    void record(VkCommandBuffer cmd, float deltaSeconds, boolean reset, int frameIndex) {
        record(cmd, deltaSeconds, reset, frameIndex, ignored -> {});
    }

    void record(VkCommandBuffer cmd, float deltaSeconds, boolean reset, int frameIndex,
                java.util.function.IntConsumer milestone) {
        try (var stack = MemoryStack.stackPush()) {
            reset |= !initialized || !lastEnabled;
            lastEnabled = true;
            if (!initialized) {
                var barriers = VkImageMemoryBarrier.calloc(owned.size(), stack);
                for (int i = 0; i < owned.size(); i++) {
                    var image = owned.get(i);
                    barriers.get(i)
                        .sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                    barriers.get(i)
                        .subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .levelCount(image.mipLevels())
                        .layerCount(1);
                    image.markInitialized();
                }
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barriers);
                initialized = true;
            }
            // The previous frame may still have read this device-local uniform buffer.
            var updateBarrier = VkMemoryBarrier.calloc(1, stack)
                                    .sType$Default()
                                    .srcAccessMask(VK_ACCESS_UNIFORM_READ_BIT)
                                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                updateBarrier, null, null);
            var data = constants(stack, deltaSeconds, reset, frameIndex);
            vkCmdUpdateBuffer(cmd, parameters.handle(), 0, data);
            var barrier = VkMemoryBarrier.calloc(1, stack)
                              .sType$Default()
                              .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                              .dstAccessMask(VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT
                                  | VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
            milestone.accept(31);
            dispatch(cmd, stack, 0, 0, 1, 1);
            computeBarrier(cmd, stack);
            milestone.accept(32);
            dispatch(cmd, stack, 6, 0, width, height);
            computeBarrier(cmd, stack);
            milestone.accept(33);
            dispatch(cmd, stack, 1, 0, width, height);
            computeBarrier(cmd, stack);
            milestone.accept(34);
            dispatch(cmd, stack, 5, 0, width, height);
            computeBarrier(cmd, stack);
            milestone.accept(35);
            for (int level = 0; level < levels; level++) {
                dispatch(cmd, stack, 2, level, Math.max(1, (width / 2) >> level),
                    Math.max(1, (height / 2) >> level));
                computeBarrier(cmd, stack);
                dispatch(cmd, stack, 7, level, Math.max(1, (width / 2) >> level),
                    Math.max(1, (height / 2) >> level));
                computeBarrier(cmd, stack);
                dispatch(cmd, stack, 8, level, Math.max(1, (width / 2) >> level),
                    Math.max(1, (height / 2) >> level));
                computeBarrier(cmd, stack);
            }
            milestone.accept(36);
            dispatch(cmd, stack, 3, 0, width, height);
            computeBarrier(cmd, stack);
            milestone.accept(37);
            dispatch(cmd, stack, 4, 0, width, height);
            computeBarrier(cmd, stack);
            milestone.accept(38);
        }
    }
    private ByteBuffer constants(MemoryStack stack, float dt, boolean reset, int frameIndex) {
        var s = RayTracingClientConfig.INSTANCE.post;
        var b = stack.calloc(PARAMETER_BYTES).order(ByteOrder.nativeOrder());
        put(b, 0, s.exposureEV.get(), s.autoExposureStrength.get(), s.centerWeight.get(),
            s.exposureTendency.get());
        put(b, 16, s.saturation.get(), s.contrast.get(), s.blackTightness.get(), s.gamma.get());
        put(b, 32, s.minimumBrightness.get(), s.colorTemperature.get(), s.agxMinEV.get(), s.agxMaxEV.get());
        put(b, 48, s.focalLength.get(), s.apertureScale.get(), s.manualFocusDepth.get(),
            s.autofocus.get() ? 1 : 0);
        put(b, 64, s.depthOfField.get() ? 1 : 0, s.motionStrength.get(), 0, s.maxBlurRadius.get());
        int tone = switch (s.toneMapping.get()) {
            case "aces" -> 1;
            case "agx" -> 2;
            case "prime" -> 3;
            default -> 0;
        };
        put(b, 80, s.vignetteStrength.get(), s.bloomIntensity.get(), s.sharpenStrength.get(), tone);
        put(b, 96, s.chromaticR.get(), s.chromaticG.get(), s.chromaticB.get(),
            com.rtest.client.PrimeRgbReinhardOutput.parameters(1).curvePeak());
        float rain = 0, sky = 0, water = 0;
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            rain = mc.level.getRainLevel(0);
            sky = mc.level.getBrightness(LightLayer.SKY, mc.player.blockPosition()) / 15F;
            water = mc.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)
                ? 0.6F
                : (mc.player.isEyeInFluid(net.minecraft.tags.FluidTags.LAVA) ? 1.6F : 0);
        }
        put(b, 112, s.rainBloomFog.get() ? s.rainBloomFogDensity.get() : 0, rain, sky, water);
        put(b, 128, Math.clamp(dt, 0F, .25F), .05, 60000, reset ? 1 : 0);
        b.putInt(144, width)
            .putInt(148, height)
            .putInt(152, HdrSupport.isActive() ? (HdrSupport.usesRec2020Primaries() ? 2 : 1) : 0)
            .putInt(156, frameIndex);
        put(b, 160, s.agxLook.get(), s.dofSamples.get(), s.motionSamples.get(), s.distortion.get());
        return b;
    }
    private static void put(ByteBuffer b, int at, double x, double y, double z, double w) {
        b.putFloat(at, (float) x)
            .putFloat(at + 4, (float) y)
            .putFloat(at + 8, (float) z)
            .putFloat(at + 12, (float) w);
    }
    private void dispatch(VkCommandBuffer cmd, MemoryStack stack, int phase, int level, int w, int h) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(
            cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(sets[level]), null);
        var push = stack.calloc(16)
                       .order(ByteOrder.nativeOrder())
                       .putInt(0, phase)
                       .putInt(4, level)
                       .putInt(8, levels);
        vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
        vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
    }
    private static void computeBarrier(VkCommandBuffer cmd, MemoryStack stack) {
        var memory = VkMemoryBarrier.calloc(1, stack)
                         .sType$Default()
                         .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                         .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, memory, null, null);
    }
    private static void check(int result) {
        RtestVulkanContext.check(result, "post processing Vulkan operation");
    }
    @Override
    public void close() {
        var vk = context.vkDevice();
        if (pipeline != 0)
            vkDestroyPipeline(vk, pipeline, null);
        if (layout != 0)
            vkDestroyPipelineLayout(vk, layout, null);
        if (pool != 0)
            vkDestroyDescriptorPool(vk, pool, null);
        if (setLayout != 0)
            vkDestroyDescriptorSetLayout(vk, setLayout, null);
        if (sampler != 0)
            vkDestroySampler(vk, sampler, null);
        if (parameters != null)
            parameters.close();
        for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
        owned.clear();
        pipeline = layout = pool = setLayout = sampler = 0;
    }
}
