package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtest.client.ItemModelGeometryAdapter;
import com.rtest.client.PlayerModelGeometryAdapter;
import com.rtest.client.LivingEntityGeometryAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates vanilla item/player render states with ids and scopes item-model capture to world draws. */
@Mixin(EntityRenderDispatcher.class)
public final class ItemEntityCaptureMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void rtest$rememberItemState(Entity entity, float partialTick,
                                         CallbackInfoReturnable<EntityRenderState> callback) {
        EntityRenderState state = callback.getReturnValue();
        LivingEntityGeometryAdapter.registerState(state, entity.getId());
        if (entity instanceof ItemEntity item && state instanceof ItemEntityRenderState itemState) {
            ItemModelGeometryAdapter.registerState(itemState, item.getId());
        } else if (state instanceof AvatarRenderState avatarState) {
            // AvatarRenderer is also used by non-Player avatar implementations in 26.2.
            // The render state is the authoritative owner of the player feature layers.
            ItemModelGeometryAdapter.registerState(avatarState, entity.getId());
        }
    }

    @Inject(method = "submit", at = @At("HEAD"))
    private void rtest$beginEntityItemCapture(EntityRenderState state, CameraRenderState camera,
                                        double x, double y, double z, PoseStack pose,
                                        SubmitNodeCollector collector, CallbackInfo callback) {
        LivingEntityGeometryAdapter.beginEntity(state, camera.pos);
        if (state instanceof ItemEntityRenderState item) {
            ItemModelGeometryAdapter.beginItem(item, camera.pos);
        } else if (state instanceof AvatarRenderState player) {
            // Entity submission owns the authoritative camera for both the player body and hand
            // feature. Do not clear the body queue here; only update its camera-relative origin.
            PlayerModelGeometryAdapter.updateWorldCamera(camera.pos);
            ItemModelGeometryAdapter.beginPlayer(player, camera.pos);
        }
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void rtest$endEntityItemCapture(EntityRenderState state, CameraRenderState camera,
                                      double x, double y, double z, PoseStack pose,
                                      SubmitNodeCollector collector, CallbackInfo callback) {
        if (state instanceof ItemEntityRenderState) {
            ItemModelGeometryAdapter.endItem();
        } else if (state instanceof AvatarRenderState) {
            ItemModelGeometryAdapter.endPlayer();
        }
        LivingEntityGeometryAdapter.endEntity();
    }
}
