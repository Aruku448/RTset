package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.rtest.client.RayTracingSupport;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    private static final String CREATE_DEVICE_TARGET =
        "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;";

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
    }
}
