package com.rtest.mixin;

import com.rtest.client.ItemModelGeometryAdapter;
import com.rtest.client.RayTracingProbe;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the current first-person item and composites RT before screen effects/UI. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @org.spongepowered.asm.mixin.Shadow
    private net.minecraft.client.Minecraft minecraft;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void rtest$beginRenderFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callbackInfo) {
        RayTracingProbe.beginRenderFrame();
    }

    @Inject(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V",
            shift = At.Shift.AFTER
        )
    )
    private void rtest$renderRtAfterHandCapture(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        // The hand submission above produced the current frame's PBR item mesh. RT now consumes
        // that mesh and overwrites the temporary vanilla hand pixels before screen effects/UI.
        // If RT fails, those vanilla pixels remain as the complete fallback.
        RayTracingProbe.renderRtAfterHandCapture();
    }

    @Inject(
        method = "renderItemInHand",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V")
    )
    private void rtest$beginFirstPersonItemCapture(CameraRenderState cameraState, float deltaPartialTick,
                                                    Matrix4fc modelViewMatrix, CallbackInfo callbackInfo) {
        if (minecraft.player != null && RayTracingProbe.shouldCaptureFirstPersonItem()) {
            ItemModelGeometryAdapter.beginFirstPerson(minecraft.player.getId(), cameraState.pos);
        }
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void rtest$finishFirstPersonItemCapture(CameraRenderState cameraState, float deltaPartialTick,
                                                     Matrix4fc modelViewMatrix, CallbackInfo callbackInfo) {
        // The enclosing renderLevel hook consumes this capture immediately after this method
        // returns, so mesh pose and camera state belong to the same RT frame.
        if (ItemModelGeometryAdapter.isFirstPersonCaptureActive()) {
            ItemModelGeometryAdapter.endFirstPerson();
        }
    }
}
