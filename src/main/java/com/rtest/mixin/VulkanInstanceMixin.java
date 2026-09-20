package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.rtest.client.HdrSupport;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Adds the optional swapchain color-space extension before VkInstance creation. */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @Shadow
    @Final
    private Set<String> enabledExtensions;

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/system/MemoryStack;callocPointer(I)Lorg/lwjgl/PointerBuffer;",
            ordinal = 1
        ),
        index = 0
    )
    private int rtest$addHdrExtension(int capacity) {
        if (!HdrSupport.canEnableSwapchainColorspace()) {
            return capacity;
        }
        if (this.enabledExtensions.add(HdrSupport.SWAPCHAIN_COLORSPACE_EXTENSION)) {
            return capacity + 1;
        }
        return capacity;
    }
}
