package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtest.client.BlockEntityModelGeometryAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens one block-entity owner scope around the dispatcher call, so every model submitted by any
 * renderer can be attributed to its block position without a per-renderer mixin.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public final class BlockEntityRenderDispatcherMixin {
    private static final String SUBMIT = "submit("
        + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V";

    @Inject(method = SUBMIT, at = @At("HEAD"))
    private void rtest$beginBlockEntitySubmit(BlockEntityRenderState state, PoseStack pose,
                                              SubmitNodeCollector collector, CameraRenderState camera,
                                              CallbackInfo callback) {
        BlockEntityModelGeometryAdapter.beginBlockEntity(state, camera);
    }

    @Inject(method = SUBMIT, at = @At("RETURN"))
    private void rtest$endBlockEntitySubmit(BlockEntityRenderState state, PoseStack pose,
                                            SubmitNodeCollector collector, CameraRenderState camera,
                                            CallbackInfo callback) {
        BlockEntityModelGeometryAdapter.endBlockEntity();
    }
}
