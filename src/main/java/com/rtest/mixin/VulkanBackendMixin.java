package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.rtest.client.RayTracingSupport;
import com.rtest.client.NvidiaRayTracingValidation;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    private static final String CREATE_DEVICE_TARGET =
        "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;";

    @Inject(method = "checkBackendAvailable", at = @At("HEAD"))
    private static void rtest$prepareValidationBeforeAvailability(
            CallbackInfoReturnable<com.mojang.blaze3d.systems.BackendCreationException> ci) {
        NvidiaRayTracingValidation.requested();
        com.rtest.client.fsr.DlssRuntime.bootstrap();
    }

    @Inject(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;", at = @At("HEAD"))
    private void rtest$prepareValidationBeforeDevice(CallbackInfoReturnable<com.mojang.blaze3d.systems.GpuDevice> ci) {
        NvidiaRayTracingValidation.requested();
    }

    @ModifyArgs(
        method = "createVma(Lorg/lwjgl/vulkan/VkDevice;)J",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I")
    )
    private static void rtest$enableBufferDeviceAddress(Args args) {
        VmaAllocatorCreateInfo createInfo = args.get(0);
        createInfo.flags(createInfo.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
    }

    @ModifyArgs(
        method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
        at = @At(value = "INVOKE", target = CREATE_DEVICE_TARGET)
    )
    private static void rtest$enableRayTracing(Args args) {
        RayTracingSupport.addDeviceRequirements(
            args.get(0),
            args.get(2),
            args.get(1)
        );
        com.rtest.client.fsr.DlssRuntime.addDeviceRequirements(args.get(0), args.get(2), args.get(1));
    }

    @ModifyArgs(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static void rtest$inspectFinalDeviceFeatures(Args args) {
        RayTracingSupport.logDeviceCreationFeatures(args.get(1));
        com.rtest.client.fsr.DlssRuntime.inspectDeviceFeatures(args.get(1));
    }
}
