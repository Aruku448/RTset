package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanInstance;
import com.rtest.client.HdrSupport;
import com.rtest.client.NvidiaRayTracingValidation;
import java.util.Set;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;
import org.lwjgl.vulkan.VkInstance;

/** Adds the optional swapchain color-space extension before VkInstance creation. */
@Mixin(VulkanInstance.class)
public abstract class VulkanInstanceMixin {
    @Shadow
    @Final
    private Set<String> enabledExtensions;
    @Shadow public abstract VkInstance vkInstance();
    @Unique private NvidiaRayTracingValidation rtest$validation;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtest$registerValidation(CallbackInfo ci) {
        if (NvidiaRayTracingValidation.requested()) {
            this.rtest$validation = new NvidiaRayTracingValidation(this.vkInstance());
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void rtest$closeValidation(CallbackInfo ci) {
        if (this.rtest$validation != null) {
            this.rtest$validation.close();
            this.rtest$validation = null;
        }
    }

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
        int before = this.enabledExtensions.size();
        com.rtest.client.fsr.DlssRuntime.addInstanceRequirements(this.enabledExtensions);
        capacity += this.enabledExtensions.size() - before;
        if (NvidiaRayTracingValidation.requested()
                && this.enabledExtensions.add("VK_EXT_debug_utils")) {
            capacity++;
        }
        if (!HdrSupport.canEnableSwapchainColorspace()) {
            return capacity;
        }
        if (this.enabledExtensions.add(HdrSupport.SWAPCHAIN_COLORSPACE_EXTENSION)) {
            return capacity + 1;
        }
        return capacity;
    }
}
