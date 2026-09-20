package com.rtest.mixin;

import com.rtest.client.ItemModelGeometryAdapter;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures vanilla item quads for item entities and player hand layers at their final submit point. */
@Mixin(SubmitNodeCollection.class)
public final class ItemFeatureRendererCaptureMixin {
    @Inject(method = "submitItem", at = @At("HEAD"))
    private void rtest$captureItemSubmit(PoseStack pose, ItemDisplayContext displayContext,
                                         int lightCoords, int overlayCoords, int outlineColor,
                                         int[] tintLayers, List<BakedQuad> quads,
                                         ItemStackRenderState.FoilType foilType, CallbackInfo callback) {
        if (outlineColor == 0) {
            ItemModelGeometryAdapter.captureSubmit(pose, lightCoords, tintLayers, quads);
            // Preserve the fallback node until RT has succeeded. The post-hand RT copy replaces
            // it on successful frames; on failure or with F8 off vanilla remains visible.
        }
    }
}
