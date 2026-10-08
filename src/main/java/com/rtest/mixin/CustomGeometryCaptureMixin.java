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

/** Captures world custom geometry with its submission owner, excluding outline duplicates. */
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
        if (renderType.isOutline()) return;
        if (com.rtest.client.ItemModelGeometryAdapter.isWorldItemCaptureActive()) {
            com.rtest.client.ItemModelGeometryAdapter.captureCustomSubmit(
                (com.mojang.blaze3d.vertex.PoseStack.Pose)args.get(0), renderType, renderer);
            return;
        }
        if (com.rtest.client.ItemModelGeometryAdapter.isFirstPersonCaptureActive()) {
            com.rtest.client.ItemModelGeometryAdapter.captureCustomSubmit((com.mojang.blaze3d.vertex.PoseStack.Pose)args.get(0), renderType, renderer);
            return;
        }
        if (BlockEntityModelGeometryAdapter.hasBlockEntityOwner()) {
            args.set(2, BlockEntityModelGeometryAdapter.wrapCustom(renderType, renderer));
        } else {
            args.set(2, LivingEntityGeometryAdapter.wrapCustom(renderType, renderer));
        }
    }
}
