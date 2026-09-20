package com.rtest.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.rtest.client.FirstPersonCaptureStorage;
import com.rtest.client.RayTracingProbe;
import com.rtest.client.VanillaRenderController;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the LevelRenderer raster phase after its entity/block-entity capture submission. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;
    @Shadow @Final private FeatureRenderDispatcher featureRenderDispatcher;
    @Shadow @Final private LevelRenderState levelRenderState;
    @Inject(
        method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"),
        cancellable = true
    )
    private void rtest$cancelVanillaWorldAfterCapture(
        GraphicsResourceAllocator resourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderOutline,
        CameraRenderState cameraState,
        Matrix4fc modelViewMatrix,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        CallbackInfo callbackInfo) {
        final boolean cancelVanilla;
        try {
            RayTracingProbe.captureParticles(this.levelRenderState.particlesRenderState);
            RayTracingProbe.prepareLevelRender();
            cancelVanilla = RayTracingProbe.shouldCancelVanillaLevelRenderer();
            if (cancelVanilla && RayTracingProbe.shouldPrepareDeferredEntityCapture()) {
                // Prepare the forced local-player body from its isolated queue first. This emits
                // capture callbacks for the reflection-only TLAS instance without ever exposing
                // the node to vanilla's raster playback.
                var firstPersonStorage = ((FirstPersonCaptureStorage)(Object)this)
                    .rtest$firstPersonCaptureStorage();
                var firstPersonFrame = this.featureRenderDispatcher.prepareFrame(firstPersonStorage);
                firstPersonFrame.close();
                firstPersonStorage.getSubmitsPerOrder().clear();
                // Entity model nodes are deferred until prepareFrame. Prepare them once so the
                // capture redirect can copy the final setupAnim/pose output into RT geometry.
                // The prepared frame is immediately closed and never executes a native draw.
                var preparedFrame = this.featureRenderDispatcher.prepareFrame(this.submitNodeStorage);
                preparedFrame.close();
                // Keep the completed queues intact until GameRenderer has submitted this frame's
                // hand item. The post-hand seam then drains world and hand geometry together.
                RayTracingProbe.endDeferredEntityCapture();
            }
        } catch (Throwable throwable) {
            RayTracingProbe.endDeferredEntityCapture();
            // Preparation is an optional replacement. Never let a PBR/model/Vulkan setup failure
            // escape this seam and strand vanilla with its world pass cancelled.
            RayTracingProbe.abortLevelRenderPreparation(throwable);
            return;
        }
        // LevelRenderer pushes exactly one model-view frame before submitFeatures/prepareFrame.
        // This injection is before prepareFrame, so pop that frame before returning; otherwise
        // cancellation leaks the push and the next GameRenderer hand/HUD pass sees the wrong
        // stack depth. Cancellation is gated by a completed presentation from the previous frame.
        if (cancelVanilla) {
            try {
                RenderSystem.getModelViewStack().popMatrix();
            } catch (RuntimeException exception) {
                // A mapping or upstream renderer change can invalidate this seam. Keep vanilla
                // rendering rather than cancelling with an unknown matrix-stack depth.
                com.mojang.logging.LogUtils.getLogger().warn(
                    "RTest kept vanilla LevelRenderer because model-view stack could not be balanced", exception);
                return;
            }
            // submitFeatures has populated this storage, and the capture-only prepared frame has
            // already been closed when dynamic capture is enabled. Discard any remaining deferred
            // nodes so they cannot leak into the next vanilla frame or be replayed after RT copy.
            this.submitNodeStorage.getSubmitsPerOrder().clear();
            VanillaRenderController.INSTANCE.markWorldSkipped();
            callbackInfo.cancel();
        }
    }

    @Inject(
        method = "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            shift = At.Shift.AFTER)
    )
    private void rtest$endDeferredEntityCapture(
        GraphicsResourceAllocator resourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderOutline,
        CameraRenderState cameraState,
        Matrix4fc modelViewMatrix,
        GpuBufferSlice terrainFog,
        Vector4f fogColor,
        boolean shouldRenderSky,
        CallbackInfo callbackInfo) {
        // Deferred model nodes have now been prepared and captured. Release the capture-only
        // state after prepareFrame, not after submitFeatures.
        RayTracingProbe.endDeferredEntityCapture();
    }
}
