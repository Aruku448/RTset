package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.rtest.client.HdrSupport;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Selects the HDR surface format when the surface exposes one. */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
    @Unique
    private int rtest$colorSpace = KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

    /**
     * Prefers a linear Rec.2020 surface so the RT wide gamut reaches the compositor, then falls back
     * to linear scRGB, then to SDR. Both HDR candidates need the same 16-bit float format.
     */
    @Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
    private void rtest$pickHdrFormat(
            VkSurfaceFormatKHR.Buffer formats,
            CallbackInfoReturnable<VkSurfaceFormatKHR> callbackInfo) {
        HdrSupport.logOfferedFormats(formats);
        if (!HdrSupport.requested() || !HdrSupport.canEnableSwapchainColorspace()) {
            HdrSupport.setMode(HdrSupport.OutputMode.SDR);
            return;
        }

        boolean wideGamut = HdrSupport.wideGamutPreferred();
        VkSurfaceFormatKHR rec2020 = null;
        VkSurfaceFormatKHR scRgb = null;
        for (int index = 0; index < formats.limit(); index++) {
            VkSurfaceFormatKHR format = formats.get(index);
            if (format.format() != HdrSupport.FORMAT) {
                continue;
            }
            if (wideGamut && format.colorSpace() == EXTSwapchainColorspace.VK_COLOR_SPACE_BT2020_LINEAR_EXT) {
                rec2020 = format;
                break;
            }
            if (format.colorSpace() == HdrSupport.COLOR_SPACE_SCRGB) {
                scRgb = format;
            }
        }

        VkSurfaceFormatKHR chosen = rec2020 != null ? rec2020 : scRgb;
        if (chosen == null) {
            HdrSupport.setMode(HdrSupport.OutputMode.SDR);
            return;
        }
        this.rtest$colorSpace = chosen.colorSpace();
        HdrSupport.setMode(rec2020 != null
            ? HdrSupport.OutputMode.HDR_BT2020_LINEAR
            : HdrSupport.OutputMode.HDR_SCRGB);
        callbackInfo.setReturnValue(chosen);
    }

    @ModifyArg(
        method = "configure",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"
        ),
        index = 0
    )
    private int rtest$useHdrColorSpace(int ignored) {
        return this.rtest$colorSpace;
    }
}
