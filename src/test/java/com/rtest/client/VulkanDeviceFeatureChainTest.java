package com.rtest.client;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Verifies native sType tags and distinct feature nodes in the final device creation chain. */
public final class VulkanDeviceFeatureChainTest {
    private static VulkanFeature feature(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (VulkanFeature)field.get(null);
    }

    public static void main(String[] args) throws Exception {
        VulkanFeature acceleration = feature(RayTracingSupport.class, "ACCELERATION_STRUCTURE_FEATURE");
        VulkanFeature rayTracing = feature(RayTracingSupport.class, "RAY_TRACING_PIPELINE_FEATURE");
        VulkanFeature address = feature(RayTracingSupport.class, "BUFFER_DEVICE_ADDRESS_FEATURE");
        VulkanFeature skin = feature(RayTracingSupport.class, "NON_UNIFORM_SKIN_ARRAY_FEATURE");
        VulkanFeature validation = feature(NvidiaRayTracingValidation.class, "FEATURE");
        VulkanFeature formatWrite = feature(RayTracingSupport.class, "STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE");
        VulkanFeature formatRead = feature(RayTracingSupport.class, "STORAGE_IMAGE_READ_WITHOUT_FORMAT_FEATURE");
        VulkanFeature extendedFormats = feature(RayTracingSupport.class, "STORAGE_IMAGE_EXTENDED_FORMATS_FEATURE");
        VulkanFeature[] features = {acceleration, rayTracing, address, skin, validation, formatWrite, formatRead, extendedFormats};
        int[] types = {
            KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR,
            VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            NVRayTracingValidation.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_VALIDATION_FEATURES_NV,
            VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
        };
        for (int i = 0; i < features.length; i++) {
            if (features[i].struct().sType() != types[i])
                throw new AssertionError("Wrong sType for " + features[i].name() + ": " + features[i].struct().sType());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var root = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (VulkanFeature feature : features) feature.set(root, true, stack);
            var create = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(root.pNext()).pEnabledFeatures(root.features());
            Set<Integer> actual = new HashSet<>();
            for (long node = create.pNext(); node != 0;) {
                var header = VkBaseInStructure.create(node);
                if (!actual.add(header.sType())) throw new AssertionError("Duplicate feature type");
                if (header.sType() == VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    throw new AssertionError("Field offset STYPE was used as a Vulkan structure tag");
                node = header.pNext() == null ? 0 : header.pNext().address();
            }
            if (actual.size() != 4) throw new AssertionError("Feature chain was truncated: " + actual);
            for (VulkanFeature feature : features) {
                if (!feature.get(feature.struct().sType() == VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 ? root.address() : create.address()))
                    throw new AssertionError("Missing enabled " + feature.name());
            }
            if (!create.pEnabledFeatures().shaderStorageImageWriteWithoutFormat()
                    || !create.pEnabledFeatures().shaderStorageImageReadWithoutFormat()
                    || !create.pEnabledFeatures().shaderStorageImageExtendedFormats())
                throw new AssertionError("NRD formatless storage reads/writes were not enabled on the final device");
            // Separate nodes must keep independent booleans, rather than aliasing sType=0/offset=16.
            rayTracing.set(root, false);
            if (rayTracing.get(create.address()) || !acceleration.get(create.address()) || !validation.get(create.address()))
                throw new AssertionError("Ray tracing feature aliases another feature node");
        }
        if (java.util.Arrays.asList(args).contains("--gpu")) verifyDeviceCreation(features);
        System.out.println("Vulkan device feature tags, chain retention and independent enable bits passed");
    }

    private static void check(int result) {
        if (result != VK10.VK_SUCCESS) throw new AssertionError("Vulkan device creation result=" + result);
    }

    /** Optional hardware check using the exact feature descriptors consumed by the mixin. */
    private static void verifyDeviceCreation(VulkanFeature[] features) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(
                VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK12.VK_API_VERSION_1_2))
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_EXT_debug_utils")));
            var handles = stack.mallocPointer(1);
            check(VK10.vkCreateInstance(info, null, handles));
            var instance = new VkInstance(handles.get(0), info);
            var errors = new java.util.concurrent.atomic.AtomicInteger();
            var callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
                errors.incrementAndGet();
                System.err.println("GPU validation error: " + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                return VK10.VK_FALSE;
            });
            var debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(1 | 2 | 4).pfnUserCallback(callback);
            var debugHandle = stack.callocLong(1);
            check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, debugInfo, null, debugHandle));
            try {
                var count = stack.callocInt(1);
                check(VK10.vkEnumeratePhysicalDevices(instance, count, null));
                var devices = stack.mallocPointer(count.get(0));
                check(VK10.vkEnumeratePhysicalDevices(instance, count, devices));
                VkPhysicalDevice physical = null;
                for (int i = 0; i < devices.remaining(); i++) {
                    var candidate = new VkPhysicalDevice(devices.get(i), instance);
                    var properties = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(candidate, properties);
                    if (properties.vendorID() == 0x10de) { physical = candidate; break; }
                }
                if (physical == null) throw new AssertionError("No NVIDIA device for optional hardware test");
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
                VK10.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
                int family = 0;
                while ((families.get(family).queueFlags() & VK10.VK_QUEUE_COMPUTE_BIT) == 0) family++;
                var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                var root = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                for (VulkanFeature feature : features) feature.set(root, true, stack);
                var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(root.pNext())
                    .pEnabledFeatures(root.features())
                    .pQueueCreateInfos(queues).ppEnabledExtensionNames(stack.pointers(
                        stack.UTF8("VK_KHR_acceleration_structure"), stack.UTF8("VK_KHR_ray_tracing_pipeline"),
                        stack.UTF8("VK_KHR_deferred_host_operations"), stack.UTF8("VK_NV_ray_tracing_validation")));
                check(VK10.vkCreateDevice(physical, deviceInfo, null, handles));
                var device = new VkDevice(handles.get(0), physical, deviceInfo);
                try (var nrd = com.rtest.client.fsr.NrdNative.create(64, 64)) {
                    int modules = 0;
                    for (var pipeline : nrd.description().pipelines()) {
                        byte[] bytes = pipeline.spirv();
                        var code = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
                        try (MemoryStack shaderStack = stack.push()) {
                            code.put(bytes).flip();
                            var shaderInfo = VkShaderModuleCreateInfo.calloc(shaderStack).sType$Default().pCode(code);
                            var shaderHandle = shaderStack.callocLong(1);
                            check(VK10.vkCreateShaderModule(device, shaderInfo, null, shaderHandle));
                            VK10.vkDestroyShaderModule(device, shaderHandle.get(0), null);
                            modules++;
                        } finally { org.lwjgl.system.MemoryUtil.memFree(code); }
                    }
                    System.out.println("GPU NRD shader module creation passed: " + modules + " bundled modules");
                    verifyRaygenModules(device);
                    RayTracingPipelineContractTest.verify(device);
                } finally { VK10.vkDestroyDevice(device, null); }
                System.out.println("GPU vkCreateDevice passed using actual RT and NV validation feature descriptors");
                if (errors.get() != 0) throw new AssertionError("GPU validation reported " + errors.get() + " errors");
            } finally {
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugHandle.get(0), null);
                callback.free();
                VK10.vkDestroyInstance(instance, null);
            }
        }
    }

    private static void verifyRaygenModules(VkDevice device) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
        try {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options,
                org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan, org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level(options,
                org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance);
            for (String source : new String[] {RayTracingShaders.CONTROL_RAYGEN_SHADER,
                    RayTracingCostAudit.raygen(RayTracingShaders.RAYGEN_SHADER, RayTracingCostAudit.Profile.OPAQUE_TRAVERSAL),
                    RayTracingShaders.RAYGEN_SHADER}) {
                var text = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
                long compiled;
                try { compiled = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, text,
                    org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_raygen_shader,
                    org.lwjgl.system.MemoryStack.stackGet().UTF8("feature-test.glsl"),
                    org.lwjgl.system.MemoryStack.stackGet().UTF8("main"), options); }
                finally { org.lwjgl.system.MemoryUtil.memFree(text); }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    if (org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(compiled) != 0)
                        throw new AssertionError(org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(compiled));
                    var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                        .pCode(org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes(compiled));
                    var handle = stack.callocLong(1);
                    check(VK10.vkCreateShaderModule(device, info, null, handle));
                    VK10.vkDestroyShaderModule(device, handle.get(0), null);
                } finally { org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(compiled); }
            }
            System.out.println("GPU control/opaque/baseline raygen module creation passed");
        } finally {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
