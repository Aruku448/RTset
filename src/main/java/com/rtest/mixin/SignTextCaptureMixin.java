package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtest.client.WorldTextGeometry;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carries glow-ink semantics separately from ambient lightmap brightness. */
@Mixin(AbstractSignRenderer.class)
public final class SignTextCaptureMixin {
    @Inject(method = "submitSignText", at = @At("HEAD"))
    private void rtest$beginText(SignRenderState state, PoseStack pose, SubmitNodeCollector output,
        SignText text, CallbackInfo callback) {
        WorldTextGeometry.setGlowingSign(text.hasGlowingText());
    }
    @Inject(method = "submitSignText", at = @At("RETURN"))
    private void rtest$endText(SignRenderState state, PoseStack pose, SubmitNodeCollector output,
        SignText text, CallbackInfo callback) {
        WorldTextGeometry.endSign();
    }
}
