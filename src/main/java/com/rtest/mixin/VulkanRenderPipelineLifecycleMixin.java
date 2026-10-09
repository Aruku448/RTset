package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 26.2 destroy() omits its third, depth/stencil graphics pipeline. */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineLifecycleMixin {
    @Shadow public abstract VulkanDevice device();
    @Shadow public abstract long withDepthStencilPipeline();
    @Unique private boolean rtest$depthStencilDestroyed;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtest$destroyDepthStencilPipeline(CallbackInfo ci) {
        long handle = withDepthStencilPipeline();
        if (handle != 0 && !rtest$depthStencilDestroyed) {
            VK10.vkDestroyPipeline(device().vkDevice(), handle, null);
            rtest$depthStencilDestroyed = true;
        }
    }
}
