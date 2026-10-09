package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

/** Checks compiled resource declarations against the exact production layout, then optionally creates real pipelines. */
public final class RayTracingPipelineContractTest {
    public static void main(String[] args) { verify(null); }

    static void verify(VkDevice device) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        int variants = 0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            for (var profile : new RayTracingCostAudit.Profile[] {
                    RayTracingCostAudit.Profile.BASELINE, RayTracingCostAudit.Profile.OPAQUE_TRAVERSAL}) {
                for (int mode : profile == RayTracingCostAudit.Profile.BASELINE ? new int[] {0, 1, 2, 3} : new int[] {0}) {
                    for (boolean atmosphere : new boolean[] {false, true}) {
                        String raygen = RayTracingShaders.RAYGEN_SHADER;
                        if (atmosphere) raygen = raygen.replace("#version 460", "#version 460\n#define RTEST_ATMOSPHERE_LUT 1");
                        raygen = RayTracingCostAudit.raygen(RayTracingRestirShader.variant(raygen, mode), profile);
                        String[] sources = {raygen, RayTracingShaders.MISS_SHADER, RayTracingShaders.SHADOW_MISS_SHADER,
                            RayTracingCostAudit.closestHit(RayTracingShaders.CLOSEST_HIT_SHADER, profile),
                            RayTracingShaders.ANY_HIT_SHADER, RayTracingShaders.SHADOW_CLOSEST_HIT_SHADER,
                            RayTracingShaders.SHADOW_ANY_HIT_SHADER, SkyImportanceShader.MISS, SkyImportanceShader.HIT};
                        int[] kinds = {Shaderc.shaderc_glsl_raygen_shader, Shaderc.shaderc_glsl_miss_shader,
                            Shaderc.shaderc_glsl_miss_shader, Shaderc.shaderc_glsl_closesthit_shader,
                            Shaderc.shaderc_glsl_anyhit_shader, Shaderc.shaderc_glsl_closesthit_shader,
                            Shaderc.shaderc_glsl_anyhit_shader, Shaderc.shaderc_glsl_miss_shader,
                            Shaderc.shaderc_glsl_closesthit_shader};
                        String label = profile.key() + "/mode" + mode + "/atmosphere=" + atmosphere;
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            var bindings = RayTracingPipelineContract.bindings(stack, atmosphere, true);
                            var stages = RayTracingPipelineContract.stages(stack, new long[9]);
                            long[] modules = new long[9];
                            long descriptorLayout = 0, pipelineLayout = 0, pipeline = 0;
                            try {
                                for (int i = 0; i < sources.length; i++) {
                                    var text = MemoryUtil.memUTF8(sources[i], false);
                                    long result;
                                    try { result = Shaderc.shaderc_compile_into_spv(compiler, text, kinds[i],
                                        stack.UTF8("pipeline-contract.glsl"), stack.UTF8("main"), options); }
                                    finally { MemoryUtil.memFree(text); }
                                    try {
                                        if (Shaderc.shaderc_result_get_compilation_status(result) != 0)
                                            throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
                                        var code = Shaderc.shaderc_result_get_bytes(result);
                                        reflect(code, bindings, stages.get(i).stage(), label + "/stage" + i);
                                        reflect(code, RayTracingPipelineContract.bindings(stack, atmosphere, false),
                                            stages.get(i).stage(), label + "/diagnostics=false/stage" + i);
                                        if (variants == 0 && i == 6) {
                                            // Reproduce the exact former shadow-any-hit visibility omission.
                                            var camera = bindings.get(2);
                                            int original = camera.stageFlags();
                                            boolean rejected = false;
                                            try {
                                                camera.stageFlags(original & ~KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                                                reflect(code, bindings, stages.get(i).stage(), "former shadow layout");
                                            } catch (AssertionError expected) {
                                                rejected = expected.getMessage().contains("binding2");
                                            } finally { camera.stageFlags(original); }
                                            if (!rejected) throw new AssertionError("Former ANY_HIT binding2 defect was not detected");
                                        }
                                        if (device != null) {
                                            var shaderInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
                                            var handle = stack.callocLong(1);
                                            check(VK10.vkCreateShaderModule(device, shaderInfo, null, handle));
                                            modules[i] = handle.get(0);
                                        }
                                    } finally { Shaderc.shaderc_result_release(result); }
                                }
                                if (device != null) {
                                    var handle = stack.callocLong(1);
                                    check(VK10.vkCreateDescriptorSetLayout(device,
                                        VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle));
                                    descriptorLayout = handle.get(0);
                                    check(VK10.vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                                        .pSetLayouts(stack.longs(descriptorLayout)), null, handle));
                                    pipelineLayout = handle.get(0);
                                    var info = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack).sType$Default()
                                        .pStages(RayTracingPipelineContract.stages(stack, modules))
                                        .pGroups(RayTracingPipelineContract.groups(stack))
                                        .maxPipelineRayRecursionDepth(1).layout(pipelineLayout);
                                    check(KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(device, 0, 0, info, null, handle));
                                    pipeline = handle.get(0);
                                    var properties = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                                    var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(properties);
                                    VK11.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
                                    var handles = stack.malloc(7 * properties.shaderGroupHandleSize());
                                    check(KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, 7, handles));
                                }
                                variants++;
                                if (device != null) System.out.println("GPU production RT pipeline passed: " + label);
                            } finally {
                                if (device != null) {
                                    if (pipeline != 0) VK10.vkDestroyPipeline(device, pipeline, null);
                                    if (pipelineLayout != 0) VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
                                    if (descriptorLayout != 0) VK10.vkDestroyDescriptorSetLayout(device, descriptorLayout, null);
                                    for (long module : modules) if (module != 0) VK10.vkDestroyShaderModule(device, module, null);
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("Production RT descriptor reflection passed: " + variants + " pipelines, " + variants * 9 + " shader stages");
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void check(int result) {
        if (result != VK10.VK_SUCCESS) throw new AssertionError("RT pipeline Vulkan result=" + result);
    }

    /** Minimal SPIR-V resource reflection: set/binding, descriptor kind/count and stage visibility. */
    static void reflect(ByteBuffer input, VkDescriptorSetLayoutBinding.Buffer layout, int stage, String label) {
        var words = input.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Map<Integer, int[]> types = new HashMap<>();
        Map<Integer, Integer> bindings = new HashMap<>(), sets = new HashMap<>(), constants = new HashMap<>();
        Map<Integer, int[]> variables = new HashMap<>();
        for (int offset = 5; offset < words.limit();) {
            int instruction = words.get(offset), count = instruction >>> 16, opcode = instruction & 65535;
            if (count == 0 || offset + count > words.limit()) throw new AssertionError("Invalid SPIR-V");
            int[] data = new int[count - 1];
            for (int i = 0; i < data.length; i++) data[i] = words.get(offset + i + 1);
            if ((opcode >= 25 && opcode <= 32) || opcode == 5341) types.put(data[0], prepend(opcode, data));
            if (opcode == 43 && data.length >= 3) constants.put(data[1], data[2]);
            if (opcode == 59) variables.put(data[1], data);
            if (opcode == 71 && data.length >= 3) {
                if (data[1] == 33) bindings.put(data[0], data[2]);
                if (data[1] == 34) sets.put(data[0], data[2]);
            }
            offset += count;
        }
        for (var entry : bindings.entrySet()) {
            int id = entry.getKey(), binding = entry.getValue();
            if (sets.getOrDefault(id, -1) != 0) throw new AssertionError(label + ": unexpected descriptor set");
            int[] variable = variables.get(id), pointer = types.get(variable[0]);
            int storage = variable[2], count = 1;
            int[] type = types.get(pointer[3]);
            while (type[0] == 28) { count *= constants.get(type[3]); type = types.get(type[2]); }
            int descriptor = storage == 12 ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                : storage == 2 ? VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                : type[0] == 5341 ? KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
                : type[0] == 27 ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : type[0] == 26 ? VK10.VK_DESCRIPTOR_TYPE_SAMPLER
                : type[0] == 25 && type[7] == 2 ? VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                : VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            VkDescriptorSetLayoutBinding found = null;
            for (int i = 0; i < layout.limit(); i++) if (layout.get(i).binding() == binding) found = layout.get(i);
            if (found == null || found.descriptorType() != descriptor || found.descriptorCount() < count
                || (found.stageFlags() & stage) == 0) {
                throw new AssertionError(label + ": incompatible set0 binding" + binding + ", type=" + descriptor
                    + ", count=" + count + ", stage=" + stage);
            }
        }
    }

    private static int[] prepend(int opcode, int[] data) {
        int[] result = new int[data.length + 1]; result[0] = opcode;
        System.arraycopy(data, 0, result, 1, data.length); return result;
    }
}
