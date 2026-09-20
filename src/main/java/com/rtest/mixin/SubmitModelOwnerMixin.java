package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtest.client.BlockEntityModelGeometryAdapter;
import net.minecraft.client.model.Model;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Carries block-entity ownership across deferred preparation without replacing Submit.state. */
@Mixin(targets = "net.minecraft.client.renderer.SubmitNodeCollection")
public final class SubmitModelOwnerMixin {
    @ModifyArgs(method = "submitModel", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$Submit;<init>("
            + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/model/Model;"
            + "Ljava/lang/Object;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V"))
    private void rtest$rememberBlockEntityOwner(Args args) {
        BlockEntityModelGeometryAdapter.registerSubmittedPose(
            (PoseStack.Pose)args.get(1), (Model<?>)args.get(2));
    }

    @Inject(method = "submitBlockModel", at = @At("HEAD"))
    private void rtest$captureBlockEntityBakedModel(PoseStack pose, RenderType renderType,
                                                     List<BlockStateModelPart> parts, int[] tintLayers,
                                                     int light, int overlay, int outline,
                                                     CallbackInfo callbackInfo) {
        if (!renderType.isOutline()) {
            BlockEntityModelGeometryAdapter.captureBlockModel(pose, parts, tintLayers);
        }
    }

    /** NeoForge's animated block models use this independent path; it does not call submitBlockModel. */
    @Inject(method = "submitMultiLayerBlockModel", at = @At("HEAD"))
    private void rtest$captureBlockEntityMultiLayerModel(PoseStack pose,
                                                          List<BlockStateModelPart> parts,
                                                          boolean translucent,
                                                          int[] tintLayers,
                                                          int light, int overlay, int outline,
                                                          CallbackInfo callbackInfo) {
        BlockEntityModelGeometryAdapter.captureBlockModel(pose, parts, tintLayers);
    }
}
