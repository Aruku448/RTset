package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import com.mojang.logging.LogUtils;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.NVRayTracingValidation;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingValidationFeaturesNV;

/** Opt-in driver instrumentation; environment or instance marker is prepared before Vulkan starts. */
public final class NvidiaRayTracingValidation implements AutoCloseable {
    private static final boolean REQUESTED = WindowsDiagnosticEnvironment.prepare();
    private static final String EXTENSION = NVRayTracingValidation.VK_NV_RAY_TRACING_VALIDATION_EXTENSION_NAME;
    private static final VulkanFeature FEATURE = new VulkanFeature(new VulkanPNextStruct(
        NVRayTracingValidation.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_VALIDATION_FEATURES_NV,
        VkPhysicalDeviceRayTracingValidationFeaturesNV.SIZEOF), "rayTracingValidation",
        VkPhysicalDeviceRayTracingValidationFeaturesNV.RAYTRACINGVALIDATION);
    private final VkInstance instance;
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private final long messenger;

    public static boolean requested() {
        return REQUESTED;
    }

    public static void addDeviceRequirements(Collection<String> extensions, Set<VulkanFeature> features,
                                              VulkanPhysicalDevice physicalDevice) {
        if (!requested()) return;
        if (!physicalDevice.hasDeviceExtension(EXTENSION)) {
            LogUtils.getLogger().warn("RTest NVIDIA RT validation requested, but driver does not expose {}", EXTENSION);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var validation = VkPhysicalDeviceRayTracingValidationFeaturesNV.calloc(stack).sType$Default();
            var supported = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(validation);
            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), supported);
            if (!validation.rayTracingValidation()) {
                LogUtils.getLogger().warn("RTest NVIDIA RT validation feature unavailable");
                return;
            }
        }
        extensions.add(EXTENSION);
        features.add(FEATURE);
        LogUtils.getLogger().info("RTest NVIDIA driver RT validation ENABLED (diagnostic run)");
    }

    public NvidiaRayTracingValidation(VkInstance instance) {
        this.instance = instance;
        this.callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
            var message = VkDebugUtilsMessengerCallbackDataEXT.create(data);
            if ((severity & EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                LogUtils.getLogger().error("RTest Vulkan diagnostic [{}]: {}", message.pMessageIdNameString(), message.pMessageString());
            } else {
                LogUtils.getLogger().warn("RTest Vulkan diagnostic [{}]: {}", message.pMessageIdNameString(), message.pMessageString());
            }
            // Diagnostics must not request that the Vulkan call be aborted.
            return VK10.VK_FALSE;
        });
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(this.callback);
            var handle = stack.callocLong(1);
            int result = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, info, null, handle);
            if (result != VK10.VK_SUCCESS) {
                this.callback.free();
                throw new IllegalStateException("NVIDIA validation debug messenger creation failed: " + result);
            }
            this.messenger = handle.get(0);
        }
    }

    @Override public void close() {
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(this.instance, this.messenger, null);
        this.callback.free();
    }
}
