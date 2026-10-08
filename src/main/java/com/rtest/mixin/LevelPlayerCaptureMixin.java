package com.rtest.mixin;

import com.rtest.client.BlockEntityModelGeometryAdapter;
import com.rtest.client.FirstPersonCaptureStorage;
import com.rtest.client.FirstPersonPlayerCaptureStorage;
import com.rtest.client.ItemModelGeometryAdapter;
import com.rtest.client.LivingEntityGeometryAdapter;
import com.rtest.client.PlayerModelGeometryAdapter;
import com.rtest.client.RayTracingProbe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Limits the tee to world draws, excluding inventory/GUI player previews. */
@Mixin(LevelRenderer.class)
public final class LevelPlayerCaptureMixin implements FirstPersonCaptureStorage {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Shadow @Final private EntityRenderDispatcher entityRenderDispatcher;
    @Unique private final FirstPersonPlayerCaptureStorage rtest$isolatedFirstPersonBody = new FirstPersonPlayerCaptureStorage();

    @Override
    public SubmitNodeStorage rtest$firstPersonCaptureStorage() {
        return this.rtest$isolatedFirstPersonBody;
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void rtest$submitFirstPersonPlayerBody(
        PoseStack poseStack, LevelRenderState renderState, SubmitNodeCollector output, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RayTracingProbe.shouldPrepareDeferredEntityCapture()
            || !minecraft.options.getCameraType().isFirstPerson() || minecraft.player == null) {
            return;
        }
        // Ask the installed renderer for its reflection/shadow model. Keep every model and
        // custom node deferred so replacement mods can finish selecting and hiding body parts
        // before capture. A replacement renderer may return a state other than AvatarRenderState.
        this.rtest$isolatedFirstPersonBody.begin();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var playerState = entityRenderDispatcher.extractEntity(minecraft.player, partialTick);
        entityRenderDispatcher.submit(playerState, renderState.cameraRenderState,
            playerState.x - renderState.cameraRenderState.pos.x,
            playerState.y - renderState.cameraRenderState.pos.y,
            playerState.z - renderState.cameraRenderState.pos.z,
            poseStack, this.rtest$isolatedFirstPersonBody);
    }

    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void rtest$beginPlayerCapture(
        LevelRenderState renderState, SubmitNodeCollector output, boolean renderOutline, CallbackInfo callback) {
        var camera = RayTracingProbe.captureNativePlayers() ? levelRenderState.cameraRenderState.pos : null;
        PlayerModelGeometryAdapter.beginWorldDraw(camera);
        ItemModelGeometryAdapter.beginWorldDraw(camera);
        LivingEntityGeometryAdapter.beginWorldDraw();
        BlockEntityModelGeometryAdapter.beginWorldDraw();
    }

}
