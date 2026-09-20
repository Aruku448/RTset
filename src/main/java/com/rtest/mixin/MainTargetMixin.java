package com.rtest.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.rtest.client.HdrSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes Minecraft's main render target match the negotiated scRGB swapchain. */
@Mixin(MainTarget.class)
public abstract class MainTargetMixin {
    @Inject(method = "<init>(IIZ)V", at = @At("RETURN"))
    private void rtest$logMainTargetFormat(int width, int height, boolean useStencil, CallbackInfo callbackInfo) {
        com.mojang.logging.LogUtils.getLogger().info("RTest main target color format: {} (HDR active={})",
            ((MainTarget)(Object)this).getColorTexture().getFormat(), HdrSupport.isActive());
    }

    @ModifyArg(
        method = "<init>(IIZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;<init>(Ljava/lang/String;ZZLcom/mojang/blaze3d/GpuFormat;)V"
        ),
        index = 3
    )
    private static GpuFormat rtest$useHdrMainTarget(GpuFormat format) {
        return HdrSupport.isActive() ? GpuFormat.RGBA16_FLOAT : format;
    }
}
