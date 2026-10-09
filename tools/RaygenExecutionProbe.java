import java.nio.*;
import org.lwjgl.*;
import org.lwjgl.system.*;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;

/** Standalone one-invocation test; no Minecraft, VMA, AS or scene traversal. */
public class RaygenExecutionProbe {
    static void check(int result) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan result=" + result);
    }
    record Buffer(long buffer, long memory, ByteBuffer mapped) {}
    static Buffer buffer(VkDevice device, VkPhysicalDevice physical, long size, int usage, MemoryStack stack) {
        var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage);
        var result = stack.mallocLong(1);
        check(vkCreateBuffer(device, info, null, result));
        long buffer = result.get(0);
        var req = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buffer, req);
        var props = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physical, props);
        int type = -1;
        for (int i = 0; i < props.memoryTypeCount(); i++) {
            int flags = props.memoryTypes(i).propertyFlags();
            if ((req.memoryTypeBits() & (1 << i)) != 0 &&
                (flags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type = i; break; }
        }
        if (type < 0) throw new IllegalStateException("No coherent host memory");
        var flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default().flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
        var alloc = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(req.size()).memoryTypeIndex(type).pNext(flags);
        check(vkAllocateMemory(device, alloc, null, result));
        long memory = result.get(0);
        check(vkBindBufferMemory(device, buffer, memory, 0));
        var mapped = stack.mallocPointer(1);
        check(vkMapMemory(device, memory, 0, size, 0, mapped));
        return new Buffer(buffer, memory, MemoryUtil.memByteBuffer(mapped.get(0), (int)size));
    }
    static void destroy(VkDevice device, Buffer buffer) {
        vkUnmapMemory(device, buffer.memory());
        vkDestroyBuffer(device, buffer.buffer(), null);
        vkFreeMemory(device, buffer.memory(), null);
    }
    public static void main(String[] args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK12.VK_API_VERSION_1_2);
            var instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
            boolean validation = java.util.Arrays.asList(args).contains("--validation");
            if (validation) instanceInfo.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")))
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_EXT_debug_utils")));
            var ptr = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, ptr));
            var instance = new VkInstance(ptr.get(0), instanceInfo);
            VkDebugUtilsMessengerCallbackEXT callback = null;
            long messenger = 0;
            if (validation) {
                callback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
                    System.err.println("VALIDATION: " + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
                    return VK_FALSE;
                });
                var debug = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default().messageSeverity(0x100 | 0x1000)
                    .messageType(1 | 2 | 4).pfnUserCallback(callback);
                var debugHandle = stack.mallocLong(1);
                check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, debug, null, debugHandle));
                messenger = debugHandle.get(0);
            }
            var count = stack.callocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null));
            var devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices));
            VkPhysicalDevice physical = null;
            for (int i = 0; i < devices.remaining(); i++) {
                var candidate = new VkPhysicalDevice(devices.get(i), instance);
                var props = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, props);
                if (props.vendorID() == 0x10de) { physical = candidate; System.out.println("device=" + props.deviceNameString()); break; }
            }
            if (physical == null) throw new IllegalStateException("No NVIDIA GPU");
            var rtProps = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
            var props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(rtProps);
            VK12.vkGetPhysicalDeviceProperties2(physical, props2);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int family = 0;
            while ((families.get(family).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) family++;
            var queues = VkDeviceQueueCreateInfo.calloc(1, stack);
            queues.get(0).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
            var rt = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().rayTracingPipeline(true);
            boolean nvValidation = java.util.Arrays.asList(args).contains("--nv-validation");
            if (nvValidation) rt.pNext(VkPhysicalDeviceRayTracingValidationFeaturesNV.calloc(stack).sType$Default().rayTracingValidation(true).address());
            var as = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true).pNext(rt.address());
            var bda = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default().bufferDeviceAddress(true).pNext(as.address());
            var deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues).pNext(bda.address())
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_KHR_acceleration_structure"),
                    stack.UTF8("VK_KHR_ray_tracing_pipeline"), stack.UTF8("VK_KHR_deferred_host_operations")));
            if (nvValidation) deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_KHR_acceleration_structure"),
                stack.UTF8("VK_KHR_ray_tracing_pipeline"), stack.UTF8("VK_KHR_deferred_host_operations"), stack.UTF8("VK_NV_ray_tracing_validation")));
            check(vkCreateDevice(physical, deviceInfo, null, ptr));
            var device = new VkDevice(ptr.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, ptr);
            var queue = new VkQueue(ptr.get(0), device);
            var output = buffer(device, physical, 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, stack);
            output.mapped().putInt(0, 0);
            var binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binding.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            var handles = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding), null, handles));
            long setLayout = handles.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)), null, handles));
            long layout = handles.get(0);
            var sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handles));
            long pool = handles.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), handles));
            long set = handles.get(0);
            var bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufInfo.get(0).buffer(output.buffer()).range(4);
            var write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bufInfo);
            vkUpdateDescriptorSets(device, write, null);
            long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize();
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            if (java.util.Arrays.asList(args).contains("--spirv14")) Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_4);
            String source = "#version 460\n#extension GL_EXT_ray_tracing : require\nlayout(set=0,binding=0,std430) buffer Result { uint value; } result;\nvoid main(){result.value=0x52545052u;}";
            if (java.util.Arrays.asList(args).contains("--launch-id")) source = source.replace("0x52545052u", "0x52545052u + gl_LaunchIDEXT.x");
            long compiled = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_raygen_shader, "probe.glsl", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(compiled) != 0) throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(compiled));
            var code = Shaderc.shaderc_result_get_bytes(compiled).order(ByteOrder.nativeOrder());
            System.out.println("SPIRV version=0x" + Integer.toHexString(code.getInt(4)) + " bytes=" + code.remaining());
            for (int word = 5; word < code.remaining() / 4;) {
                int instruction = code.getInt(word * 4), op = instruction & 65535, length = instruction >>> 16;
                if (op == 15 || op == 17 || op == 62) {
                    String line = "SPIRV op=" + op;
                    for (int w = 1; w < length; w++) line += " " + code.getInt((word + w) * 4);
                    System.out.println(line);
                }
                word += length;
            }
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(Shaderc.shaderc_result_get_bytes(compiled)), null, handles));
            long shader = handles.get(0);
            Shaderc.shaderc_result_release(compiled); Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler);
            var stages = VkPipelineShaderStageCreateInfo.calloc(1, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(shader).pName(stack.UTF8("main"));
            var groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(1, stack);
            groups.get(0).sType$Default().type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0)
                .closestHitShader(VK_SHADER_UNUSED_KHR).anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            var pipelineInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(layout);
            if (java.util.Arrays.asList(args).contains("--explicit-stack")) pipelineInfo.get(0).pDynamicState(
                VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_RAY_TRACING_PIPELINE_STACK_SIZE_KHR)));
            check(vkCreateRayTracingPipelinesKHR(device, 0, 0, pipelineInfo, null, handles));
            long pipeline = handles.get(0);
            System.out.println("raygenStack=" + vkGetRayTracingShaderGroupStackSizeKHR(device, pipeline, 0, VK_SHADER_GROUP_SHADER_GENERAL_KHR));
            int stride = rtProps.shaderGroupBaseAlignment();
            var sbt = buffer(device, physical, stride, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, stack);
            var handle = stack.malloc(rtProps.shaderGroupHandleSize());
            check(vkGetRayTracingShaderGroupHandlesKHR(device, pipeline, 0, 1, handle));
            MemoryUtil.memCopy(MemoryUtil.memAddress(handle), MemoryUtil.memAddress(sbt.mapped()), handle.remaining());
            byte[] bytes = new byte[handle.remaining()]; handle.duplicate().get(bytes);
            System.out.println("handle=" + java.util.HexFormat.of().formatHex(bytes));
            long address = VK12.vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(sbt.buffer()));
            System.out.println("sbtAddress=0x" + Long.toHexString(address) + " stride=" + stride);
            if (address == 0) throw new IllegalStateException("SBT address is zero");
            if ((address & (stride - 1)) != 0) throw new IllegalStateException("SBT alignment");
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family), null, handles));
            long cmdPool = handles.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), ptr));
            var cmd = new VkCommandBuffer(ptr.get(0), device);
            check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()));
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            if (java.util.Arrays.asList(args).contains("--explicit-stack")) vkCmdSetRayTracingPipelineStackSizeKHR(cmd, 256);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, layout, 0, stack.longs(set), null);
            var raygen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(address).stride(stride).size(stride);
            var empty = VkStridedDeviceAddressRegionKHR.calloc(stack);
            if (java.util.Arrays.asList(args).contains("--ffm-trace")) {
                var downcall = java.lang.foreign.Linker.nativeLinker().downcallHandle(
                    java.lang.foreign.MemorySegment.ofAddress(device.getCapabilities().vkCmdTraceRaysKHR),
                    java.lang.foreign.FunctionDescriptor.ofVoid(java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.ADDRESS, java.lang.foreign.ValueLayout.ADDRESS,
                        java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                        java.lang.foreign.ValueLayout.JAVA_INT));
                try {
                    downcall.invokeExact(java.lang.foreign.MemorySegment.ofAddress(cmd.address()),
                        java.lang.foreign.MemorySegment.ofAddress(raygen.address()),
                        java.lang.foreign.MemorySegment.ofAddress(empty.address()),
                        java.lang.foreign.MemorySegment.ofAddress(empty.address()),
                        java.lang.foreign.MemorySegment.ofAddress(empty.address()), 1, 1, 1);
                } catch (Throwable e) { throw new RuntimeException(e); }
            } else vkCmdTraceRaysKHR(cmd, raygen, empty, empty, empty, 1, 1, 1);
            var barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            check(vkEndCommandBuffer(cmd));
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handles));
            long fence = handles.get(0);
            var submits = VkSubmitInfo.calloc(1, stack);
            submits.get(0).sType$Default().pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(queue, submits, fence));
            check(vkWaitForFences(device, fence, true, 5_000_000_000L));
            int result = output.mapped().getInt(0);
            System.out.println("readback=0x" + Integer.toHexString(result));
            vkDestroyFence(device, fence, null); vkDestroyCommandPool(device, cmdPool, null);
            destroy(device, sbt); destroy(device, output);
            vkDestroyPipeline(device, pipeline, null); vkDestroyShaderModule(device, shader, null);
            vkDestroyDescriptorPool(device, pool, null); vkDestroyPipelineLayout(device, layout, null); vkDestroyDescriptorSetLayout(device, setLayout, null);
            vkDestroyDevice(device, null);
            if (validation) { EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null); callback.free(); }
            vkDestroyInstance(instance, null);
            if (result != 0x52545052) throw new IllegalStateException("Standalone raygen failed");
            System.out.println("PASS");
        }
    }
}
