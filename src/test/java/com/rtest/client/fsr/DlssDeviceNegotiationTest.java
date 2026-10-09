package com.rtest.client.fsr;

import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.lang.foreign.*;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Optional GPU test of the production Java negotiation and fixed-width FFM bindings. */
public final class DlssDeviceNegotiationTest {
    public static void main(String[] args) throws Exception {
        DlssRuntime.bootstrap();
        if(!org.lwjgl.glfw.GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            Set<String> instanceExtensions=new LinkedHashSet<>();
            DlssRuntime.addInstanceRequirements(instanceExtensions); instanceExtensions.add("VK_EXT_debug_utils");
            var presentationExtensions=org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if(presentationExtensions==null) throw new AssertionError("Missing platform presentation extensions");
            for(int i=0;i<presentationExtensions.remaining();i++) instanceExtensions.add(org.lwjgl.system.MemoryUtil.memUTF8(presentationExtensions.get(i)));
            var pointers=stack.mallocPointer(instanceExtensions.size());
            for(String name:instanceExtensions) pointers.put(stack.UTF8(name)); pointers.flip();
            var info=VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(VkApplicationInfo.calloc(stack)
                .sType$Default().apiVersion(VK12.VK_API_VERSION_1_2)).ppEnabledExtensionNames(pointers)
                .ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
            var out=stack.mallocPointer(1); check(VK12.vkCreateInstance(info,null,out));
            var instance=new VkInstance(out.get(0),info);
            var errors=new java.util.concurrent.atomic.AtomicInteger();
            var callback=VkDebugUtilsMessengerCallbackEXT.create((severity,type,data,user)-> {
                errors.incrementAndGet(); System.err.println(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString()); return VK12.VK_FALSE;
            });
            var debugInfo=VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT).messageType(1|2|4).pfnUserCallback(callback);
            var debug=stack.mallocLong(1); check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance,debugInfo,null,debug));
            VkDevice device=null;
            try {
                var count=stack.callocInt(1); check(VK12.vkEnumeratePhysicalDevices(instance,count,null));
                var adapters=stack.mallocPointer(count.get(0)); check(VK12.vkEnumeratePhysicalDevices(instance,count,adapters));
                VkPhysicalDevice selected=null;
                var properties=VkPhysicalDeviceProperties.calloc(stack);
                for(int i=0;i<count.get(0);i++) {
                    var candidate=new VkPhysicalDevice(adapters.get(i),instance);
                    VK12.vkGetPhysicalDeviceProperties(candidate,properties);
                    if(properties.vendorID()==0x10de) { selected=candidate; break; }
                }
                if(selected==null) throw new AssertionError("NVIDIA GPU required for this explicitly requested test");
                System.out.println("DLSS Java negotiation adapter="+properties.deviceNameString());
                try(var physical=new VulkanPhysicalDevice(selected)) {
                    List<String> extensions=new ArrayList<>(); Set<VulkanFeature> features=new LinkedHashSet<>();
                    com.rtest.client.RayTracingSupport.addDeviceRequirements(extensions,features,physical);
                    extensions.add("VK_KHR_synchronization2");
                    features.add(new VulkanFeature(com.mojang.blaze3d.vulkan.VulkanBackend.SYNC2_FEATURES_STRUCT,
                        "synchronization2",VkPhysicalDeviceSynchronization2Features.SYNCHRONIZATION2));
                    DlssRuntime.addDeviceRequirements(extensions,features,physical);
                    if(features.stream().noneMatch(f->f.name().equals("privateData"))) throw new AssertionError(DlssRuntime.unavailableReason());
                    if(extensions.contains("VK_EXT_buffer_device_address")) throw new AssertionError("Legacy BDA conflict");
                    var family=physical.graphicsQueueFamilyAndIndex();
                    var queues=VkDeviceQueueCreateInfo.calloc(1,stack); queues.get(0).sType$Default().queueFamilyIndex(family.leftInt())
                        .pQueuePriorities(stack.callocFloat(family.rightInt()+1));
                    var root=VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                    for(var feature:features) feature.set(root,true,stack);
                    pointers=stack.mallocPointer(extensions.size());
                    for(String extension:extensions) pointers.put(stack.UTF8(extension)); pointers.flip();
                    var create=VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(root.pNext()).pEnabledFeatures(root.features())
                        .pQueueCreateInfos(queues).ppEnabledExtensionNames(pointers);
                    DlssRuntime.inspectDeviceFeatures(create);
                    check(VK12.vkCreateDevice(selected,create,null,out)); device=new VkDevice(out.get(0),selected,create);
                    DlssRuntime.check("FFM attach",DlssRuntime.invoke("attach",instance.address(),selected.address(),device.address(),family.leftInt(),family.rightInt()));
                    if((int)DlssRuntime.invoke("capabilities")!=3) throw new AssertionError("SR and RR support missing on test adapter");
                    try(Arena arena=Arena.ofConfined()) {
                        var dimensions=arena.allocate(8,4); var handle=arena.allocate(ValueLayout.ADDRESS);
                        for(int mode=1;mode<=2;mode++) {
                            DlssRuntime.check("FFM extent",DlssRuntime.invoke("size",mode,1920,1080,1,dimensions));
                            int width=dimensions.get(ValueLayout.JAVA_INT,0),height=dimensions.get(ValueLayout.JAVA_INT,4);
                            if(width<=0||height<=0) throw new AssertionError("Invalid SDK size");
                            DlssRuntime.check("FFM create",DlssRuntime.invoke("create",mode,1920,1080,1,width,height,handle));
                            MemorySegment owner=handle.get(ValueLayout.ADDRESS,0);
                            if(owner.address()==0) throw new AssertionError("Empty SDK owner");
                            DlssRuntime.check("FFM destroy",DlssRuntime.invoke("destroy",owner));
                            System.out.println("Java FFM mode="+mode+" SDK extent="+width+"x"+height+" owner retired");
                        }
                    }
                }
                check(VK12.vkDeviceWaitIdle(device)); DlssRuntime.shutdown();
                if(errors.get()!=0) throw new AssertionError("Java negotiation validation errors="+errors.get());
                System.out.println("Production Java Vulkan 1.2 negotiation / final feature chain / FFM owners passed; validation errors=0");
            } finally {
                if(device!=null) { VK12.vkDeviceWaitIdle(device); DlssRuntime.shutdown(); VK12.vkDestroyDevice(device,null); }
                EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance,debug.get(0),null); callback.free(); VK12.vkDestroyInstance(instance,null);
            }
        } finally { org.lwjgl.glfw.GLFW.glfwTerminate(); }
    }
    private static void check(int status) { if(status!=VK12.VK_SUCCESS) throw new AssertionError("Vulkan status="+status); }
}
