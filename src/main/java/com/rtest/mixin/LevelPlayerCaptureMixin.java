package com.rtest.mixin;

import com.rtest.client.BlockEntityModelGeometryAdapter;
import com.rtest.client.FirstPersonCaptureStorage;
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
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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
    @Unique private final SubmitNodeStorage rtest$isolatedFirstPersonBody = new SubmitNodeStorage();

    @Override
    public SubmitNodeStorage rtest$firstPersonCaptureStorage() {
        return this.rtest$isolatedFirstPersonBody;
    }

    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void rtest$submitFirstPersonPlayerBody(
        PoseStack poseStack, LevelRenderState renderState, SubmitNodeCollector output, CallbackInfo callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.getCameraType().isFirstPerson() || minecraft.player == null) {
            return;
        }
        // In first person vanilla intentionally omits the local body. Queue a copy in storage
        // that is prepared only by RTest's capture seam and is never passed to vanilla raster.
        // Putting this node in LevelRenderer's normal output made the camera render inside the
        // player's head whenever RT had not yet replaced the frame.
        this.rtest$isolatedFirstPersonBody.getSubmitsPerOrder().clear();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var playerState = entityRenderDispatcher.extractEntity(minecraft.player, partialTick);
        if (playerState instanceof AvatarRenderState avatar) {
            entityRenderDispatcher.submit(avatar, renderState.cameraRenderState,
                avatar.x - renderState.cameraRenderState.pos.x,
                avatar.y - renderState.cameraRenderState.pos.y,
                avatar.z - renderState.cameraRenderState.pos.z,
                poseStack, this.rtest$isolatedFirstPersonBody);
        }
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
