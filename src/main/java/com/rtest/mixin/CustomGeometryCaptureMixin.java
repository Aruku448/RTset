package com.rtest.mixin;

import com.rtest.client.BlockEntityModelGeometryAdapter;
import com.rtest.client.LivingEntityGeometryAdapter;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Wraps solid custom geometry submitted by a living entity while leaving translucent geometry native. */
@Mixin(targets = "net.minecraft.client.renderer.SubmitNodeCollection")
public final class CustomGeometryCaptureMixin {
    @ModifyArgs(
        method = "submitCustomGeometry",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/feature/CustomFeatureRenderer$Submit;<init>(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V")
    )
    private void rtest$wrapSolidCustomGeometry(Args args) {
        RenderType renderType = args.get(1);
        SubmitNodeCollector.CustomGeometryRenderer renderer = args.get(2);
        if (BlockEntityModelGeometryAdapter.hasBlockEntityOwner()) {
            args.set(2, BlockEntityModelGeometryAdapter.wrapCustom(renderType, renderer));
        } else {
            args.set(2, LivingEntityGeometryAdapter.wrapCustom(renderType, renderer));
        }
    }
}
