package com.rtest.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;

/** The native layout and shader groups used by both production and pipeline validation. */
final class RayTracingPipelineContract {
    private RayTracingPipelineContract() {}

    static VkDescriptorSetLayoutBinding.Buffer bindings(MemoryStack stack, boolean physicalAtmosphere, boolean diagnostics) {
        VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(physicalAtmosphere ? 44 : 33, stack);
        int restirBindingStart = physicalAtmosphere ? 41 : 30;
        for (int index = 0; index < 3; index++) {
            bindings.get(restirBindingStart + index).binding(41 + index)
                .descriptorType(index == 2 ? VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(physicalAtmosphere ? 39 : 28).binding(39)
            .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(physicalAtmosphere ? 40 : 29).binding(40)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(physicalAtmosphere ? 38 : 27).binding(38)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
            .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(0).binding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                | (diagnostics ? VK10.VK_SHADER_STAGE_COMPUTE_BIT : 0));
        bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            );
        bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            );
        bindings.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
            );
        bindings.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        for (int binding = 7; binding <= 16; binding++) {
            bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(17).binding(17).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(RayTracingVulkanPass.PLAYER_SKIN_DESCRIPTOR_COUNT).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
        bindings.get(18).binding(18).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
        bindings.get(19).binding(19).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        bindings.get(20).binding(20).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        for (int binding = 21; binding <= 25; binding++) {
            bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        bindings.get(26).binding(26).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1).stageFlags(
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                    | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
        if (physicalAtmosphere) {
            for (int binding = 27; binding <= 28; binding++) {
                bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            }
            bindings.get(29).binding(29).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            // Physical finite-segment aerial perspective: L output, pinned medium, the
            // bank-zero optical-depth and scattering-source samplers, and the
            // mean/ground/high multiple-scattering images.
            bindings.get(30).binding(30).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(31).binding(31).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(32).binding(32).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(33).binding(33).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            for (int binding = 34; binding <= 36; binding++) {
                bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            }
            bindings.get(37).binding(37).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
        }
        return bindings;
    }

    static VkPipelineShaderStageCreateInfo.Buffer stages(MemoryStack stack, long[] modules) {
        if (modules.length != 9) throw new IllegalArgumentException("Expected nine RT shader modules");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(9, stack);
        stages.get(0).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(modules[0]).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(modules[1]).pName(stack.UTF8("main"));
        stages.get(2).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(modules[2]).pName(stack.UTF8("main"));
        stages.get(3).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(modules[3]).pName(stack.UTF8("main"));
        stages.get(4).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(modules[4]).pName(stack.UTF8("main"));
        stages.get(5).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(modules[5]).pName(stack.UTF8("main"));
        stages.get(6).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(modules[6]).pName(stack.UTF8("main"));
        stages.get(7).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(modules[7]).pName(stack.UTF8("main"));
        stages.get(8).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(modules[8]).pName(stack.UTF8("main"));
        return stages;
    }

    static VkRayTracingShaderGroupCreateInfoKHR.Buffer groups(MemoryStack stack) {
        VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(7, stack);
        groups.get(5).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(7).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
            .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(6).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(8)
            .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(0).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(0).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
            .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(1).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(1).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
            .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(2).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
            .generalShader(2).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
            .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(3).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(3)
            .anyHitShader(4).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        groups.get(4).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
            .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(5)
            .anyHitShader(6).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
        return groups;
    }
}
