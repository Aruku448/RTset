package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtest.client.LivingEntityGeometryAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the exact texture selected by the original living renderer for its body model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityTextureCaptureMixin {
    @Shadow public abstract Identifier getTextureLocation(LivingEntityRenderState state);

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtest$rememberLivingTexture(LivingEntityRenderState state, PoseStack pose,
                                              SubmitNodeCollector collector, CameraRenderState camera,
                                              CallbackInfo callback) {
        LivingEntityGeometryAdapter.registerTexture(state, this.getTextureLocation(state));
    }
}
