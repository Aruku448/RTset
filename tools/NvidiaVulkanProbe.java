import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Read-only driver/capability probe. Run with LWJGL core, Vulkan and platform natives on classpath. */
public class NvidiaVulkanProbe {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--instance-marker")) {
            System.out.println("markerRequested=" + com.rtest.client.WindowsDiagnosticEnvironment.prepare());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK12.VK_API_VERSION_1_2);
            var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            PointerBuffer result = stack.mallocPointer(1);
            check(VK10.vkCreateInstance(create, null, result));
            var instance = new VkInstance(result.get(0), create);
            try {
                var count = stack.callocInt(1);
                check(VK10.vkEnumerateInstanceLayerProperties(count, null));
                var layers = VkLayerProperties.calloc(count.get(0), stack);
                check(VK10.vkEnumerateInstanceLayerProperties(count, layers));
                for (var layer : layers) System.out.println("layer=" + layer.layerNameString());
                check(VK10.vkEnumeratePhysicalDevices(instance, count, null));
                var physicals = stack.mallocPointer(count.get(0));
                check(VK10.vkEnumeratePhysicalDevices(instance, count, physicals));
                for (int i = 0; i < physicals.remaining(); i++) {
                    var device = new VkPhysicalDevice(physicals.get(i), instance);
                    var props = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(device, props);
                    System.out.println("device=" + props.deviceNameString() + " vendor=" + props.vendorID());
                    var driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
                    var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
                    var deviceProperties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver);
                    driver.pNext(subgroup.address());
                    VK12.vkGetPhysicalDeviceProperties2(device, deviceProperties);
                    System.out.println("driver=" + driver.driverNameString() + " info=" + driver.driverInfoString()
                        + " subgroupSize=" + subgroup.subgroupSize());
                    var baseFeatures = VkPhysicalDeviceFeatures.calloc(stack);
                    VK10.vkGetPhysicalDeviceFeatures(device, baseFeatures);
                    System.out.println("supportedRobustBufferAccess=" + baseFeatures.robustBufferAccess());
                    var limits = props.limits();
                    System.out.println("maxStorageBufferRange=" + Integer.toUnsignedLong(limits.maxStorageBufferRange())
                        + " maxPerStageStorageBuffers=" + Integer.toUnsignedString(limits.maxPerStageDescriptorStorageBuffers())
                        + " maxPerStageStorageImages=" + Integer.toUnsignedString(limits.maxPerStageDescriptorStorageImages()));
                    check(VK10.vkEnumerateDeviceExtensionProperties(device, (String)null, count, null));
                    try (var extensions = VkExtensionProperties.calloc(count.get(0))) {
                    check(VK10.vkEnumerateDeviceExtensionProperties(device, (String)null, count, extensions));
                    boolean nv = extensions.stream().anyMatch(e -> e.extensionNameString().equals("VK_NV_ray_tracing_validation"));
                    System.out.println("NV_ALLOW_RAYTRACING_VALIDATION=" + System.getenv("NV_ALLOW_RAYTRACING_VALIDATION")
                        + " NV_validation_extension=" + nv);
                    if (nv) {
                        var validation = VkPhysicalDeviceRayTracingValidationFeaturesNV.calloc(stack).sType$Default();
                        var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(validation);
                        VK12.vkGetPhysicalDeviceFeatures2(device, features);
                        System.out.println("rayTracingValidation=" + validation.rayTracingValidation());
                    }
                    if (extensions.stream().anyMatch(e -> e.extensionNameString().equals("VK_KHR_ray_tracing_pipeline"))) {
                        var rt = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                        var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rt);
                        VK12.vkGetPhysicalDeviceProperties2(device, properties);
                        System.out.println("handle=" + rt.shaderGroupHandleSize() + " handleAlign=" + rt.shaderGroupHandleAlignment()
                            + " baseAlign=" + rt.shaderGroupBaseAlignment() + " maxStride=" + rt.maxShaderGroupStride()
                            + " maxRecursion=" + rt.maxRayRecursionDepth()
                            + " maxRayDispatchInvocations=" + Integer.toUnsignedLong(rt.maxRayDispatchInvocationCount()));
                        var as = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                        properties.pNext(as);
                        VK12.vkGetPhysicalDeviceProperties2(device, properties);
                        System.out.println("scratchAlign=" + as.minAccelerationStructureScratchOffsetAlignment()
                            + " maxInstances=" + as.maxInstanceCount() + " maxPrimitives=" + as.maxPrimitiveCount());
                    }
                    }
                }
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        }
    }
    private static void check(int result) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException("Vulkan result=" + result);
    }
}
