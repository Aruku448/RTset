package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

/** Vulkan ray-tracing capability and device-creation configuration. */
public final class RayTracingSupport {
    public static final String ACCELERATION_STRUCTURE_EXTENSION = "VK_KHR_acceleration_structure";
    public static final String RAY_TRACING_PIPELINE_EXTENSION = "VK_KHR_ray_tracing_pipeline";
    public static final String DEFERRED_HOST_OPERATIONS_EXTENSION = "VK_KHR_deferred_host_operations";

    private static final VulkanPNextStruct ACCELERATION_STRUCTURE_FEATURES_STRUCT = new VulkanPNextStruct(
        VkPhysicalDeviceAccelerationStructureFeaturesKHR.STYPE,
        VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF
    );
    private static final VulkanPNextStruct RAY_TRACING_PIPELINE_FEATURES_STRUCT = new VulkanPNextStruct(
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.STYPE,
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.SIZEOF
    );
    private static final VulkanFeature ACCELERATION_STRUCTURE_FEATURE = new VulkanFeature(
        ACCELERATION_STRUCTURE_FEATURES_STRUCT,
        "accelerationStructure",
        VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE
    );
    private static final VulkanFeature RAY_TRACING_PIPELINE_FEATURE = new VulkanFeature(
        RAY_TRACING_PIPELINE_FEATURES_STRUCT,
        "rayTracingPipeline",
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR.RAYTRACINGPIPELINE
    );
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS_FEATURE = new VulkanFeature(
        VulkanBackend.VK12_FEATURES_STRUCT,
        "bufferDeviceAddress",
        VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS
    );
    private static final VulkanFeature NON_UNIFORM_SKIN_ARRAY_FEATURE = new VulkanFeature(
        VulkanBackend.VK12_FEATURES_STRUCT,
        "shaderSampledImageArrayNonUniformIndexing",
        VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING
    );

    private RayTracingSupport() {
    }

    public static boolean isSupported(VulkanPhysicalDevice physicalDevice) {
        if (!hasRequiredExtensions(physicalDevice)) {
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            VkPhysicalDeviceAccelerationStructureFeaturesKHR acceleration = VkPhysicalDeviceAccelerationStructureFeaturesKHR
                .calloc(stack)
                .sType$Default();
            VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayTracing = VkPhysicalDeviceRayTracingPipelineFeaturesKHR
                .calloc(stack)
                .sType$Default();
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();

            features.pNext(acceleration.address());
            acceleration.pNext(rayTracing.address());
            rayTracing.pNext(vulkan12.address());
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), features);

            return acceleration.accelerationStructure()
                && rayTracing.rayTracingPipeline()
                && vulkan12.bufferDeviceAddress()
                && vulkan12.shaderSampledImageArrayNonUniformIndexing();
        }
    }

    public static void addDeviceRequirements(Collection<String> extensions, Set<VulkanFeature> features, VulkanPhysicalDevice physicalDevice) {
        if (!isSupported(physicalDevice)) {
            return;
        }

        extensions.add(ACCELERATION_STRUCTURE_EXTENSION);
        extensions.add(RAY_TRACING_PIPELINE_EXTENSION);
        extensions.add(DEFERRED_HOST_OPERATIONS_EXTENSION);
        features.add(ACCELERATION_STRUCTURE_FEATURE);
        features.add(RAY_TRACING_PIPELINE_FEATURE);
        features.add(BUFFER_DEVICE_ADDRESS_FEATURE);
        features.add(NON_UNIFORM_SKIN_ARRAY_FEATURE);
    }

    public static Set<String> requiredExtensions() {
        return Set.of(
            ACCELERATION_STRUCTURE_EXTENSION,
            RAY_TRACING_PIPELINE_EXTENSION,
            DEFERRED_HOST_OPERATIONS_EXTENSION
        );
    }

    public static Limits queryLimits(com.mojang.blaze3d.vulkan.VulkanDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = org.lwjgl.vulkan.VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            var pipelineProperties = org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR
                .calloc(stack)
                .sType$Default();
            var accelerationProperties = org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR
                .calloc(stack)
                .sType$Default();
            properties.pNext(pipelineProperties);
            pipelineProperties.pNext(accelerationProperties.address());
            VK12.vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), properties);
            return new Limits(
                pipelineProperties.shaderGroupHandleSize(),
                pipelineProperties.shaderGroupHandleAlignment(),
                pipelineProperties.shaderGroupBaseAlignment(),
                pipelineProperties.maxRayRecursionDepth(),
                accelerationProperties.maxInstanceCount(),
                accelerationProperties.minAccelerationStructureScratchOffsetAlignment()
            );
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public record Limits(
        int shaderGroupHandleSize,
        int shaderGroupHandleAlignment,
        int shaderGroupBaseAlignment,
        int maxRayRecursionDepth,
        long maxInstanceCount,
        int minScratchAlignment
    ) {
    }

    private static boolean hasRequiredExtensions(VulkanPhysicalDevice physicalDevice) {
        return requiredExtensions().stream().allMatch(physicalDevice::hasDeviceExtension);
    }
}
