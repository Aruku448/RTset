package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.rtest.client.fsr.DlssRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDlssLifecycleMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtest$attachDlss(CallbackInfo ci) { DlssRuntime.attach((VulkanDevice)(Object)this); }
    // Encoder retirement has completed before SDK destruction, while VkDevice is still alive.
    @Inject(method = "close", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaDestroyAllocator(J)V"))
    private void rtest$shutdownDlss(CallbackInfo ci) { DlssRuntime.shutdown(); }
}
