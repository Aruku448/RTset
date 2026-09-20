package com.rtest.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rtest.client.BlockEntityModelGeometryAdapter;
import com.rtest.client.LivingEntityGeometryAdapter;
import com.rtest.client.PlayerModelGeometryAdapter;
import com.rtest.client.RayTracingProbe;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Captures the original draw after vanilla setupAnim, visibility and renderer transforms. */
@Mixin(ModelFeatureRenderer.class)
public final class PlayerModelCaptureMixin {
    @Redirect(method = "prepareModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void rtest$capturePlayer(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                    int light, int overlay, int color, ModelFeatureRenderer.Submit<?> submit) {
        var camera = PlayerModelGeometryAdapter.worldCamera();
        // The redirect is installed globally on ModelFeatureRenderer. During bootstrap, RT
        // capture and vanilla rendering coexist; without this guard the redirect would consume
        // every model draw even though LevelRenderer was not cancelled for that frame.
        if (camera == null) {
            model.renderToBuffer(pose, buffer, light, overlay, color);
            return;
        }
        boolean suppressVanilla = RayTracingProbe.shouldSuppressVanillaWorldModels();
        if (camera != null && BlockEntityModelGeometryAdapter.captureOrForward(
            model, pose, buffer, light, overlay, color, submit)) {
            if (!suppressVanilla) {
                model.renderToBuffer(pose, buffer, light, overlay, color);
            }
            return;
        }
        if (camera != null && model instanceof PlayerModel
            && submit.state() instanceof AvatarRenderState state) {
            // Player skin uses ENTITY_TRANSLUCENT, so hasBlending() cannot distinguish it from
            // native-only effects. Classify vanilla armor by pipeline instead; armor extensions
            // are allowed to return PlayerModel and submit order is not a body/armor contract.
            var pipeline = submit.renderType().pipeline();
            boolean armorLayer = pipeline == RenderPipelines.ARMOR_CUTOUT_NO_CULL
                || pipeline == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL;
            boolean emissiveLayer = LivingEntityGeometryAdapter.isEmissive(submit.renderType());
            boolean bodyLayer = pipeline == RenderPipelines.ENTITY_SOLID
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT
                || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_CULL
                || pipeline == RenderPipelines.ENTITY_CUTOUT
                || pipeline == RenderPipelines.ENTITY_CUTOUT_CULL
                || pipeline == RenderPipelines.ENTITY_CUTOUT_DISSOLVE
                || pipeline == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET;
            if (submit.renderType().isOutline() || (!bodyLayer && !armorLayer && !emissiveLayer)) {
                if (!suppressVanilla) {
                    model.renderToBuffer(pose, buffer, light, overlay, color);
                }
                return;
            }
            // Vanilla's submitted pose is camera-relative. BLAS vertices must be entity-local;
            // the matching state position is added once by the TLAS, relative to scene origin.
            var mesh = PlayerModelGeometryAdapter.captureDraw(model, pose, buffer, light, overlay, color,
                (float)(camera.x - state.x), (float)(camera.y - state.y), (float)(camera.z - state.z),
                submit.sprite(), RayTracingProbe.pbrSampler(),
                LivingEntityGeometryAdapter.emissionFor(submit.renderType()));
            var fallbackSkin = state.skin == null || state.skin.body() == null
                ? null : state.skin.body().texturePath();
            var layerTexture = LivingEntityGeometryAdapter.textureFor(submit, state);
            if (layerTexture == null) {
                layerTexture = fallbackSkin;
                LivingEntityGeometryAdapter.rememberTextureLocation(layerTexture);
            }
            var tagged = LivingEntityGeometryAdapter.retag(mesh, layerTexture);
            if (bodyLayer && !emissiveLayer) {
                PlayerModelGeometryAdapter.publish(state.id, state.x, state.y, state.z,
                    layerTexture, tagged);
            } else {
                LivingEntityGeometryAdapter.publishModel(state.id, state, layerTexture, tagged);
            }
            if (!suppressVanilla) {
                model.renderToBuffer(pose, buffer, light, overlay, color);
            }
        } else if (camera != null && submit.state() instanceof net.minecraft.client.renderer.entity.state.EntityRenderState state
            && !(model instanceof PlayerModel && state instanceof AvatarRenderState)) {
            Integer entityId = LivingEntityGeometryAdapter.entityId(state);
            if (entityId != null) {
                var mesh = PlayerModelGeometryAdapter.captureDraw(model, pose, buffer, light, overlay, color,
                    (float)(camera.x - state.x), (float)(camera.y - state.y), (float)(camera.z - state.z),
                    submit.sprite(), RayTracingProbe.pbrSampler(),
                    LivingEntityGeometryAdapter.emissionFor(submit.renderType()));
                var texture = LivingEntityGeometryAdapter.textureFor(submit, state);
                LivingEntityGeometryAdapter.publishModel(entityId, state,
                    texture != null ? texture
                        : net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation(), mesh);
                if (!suppressVanilla) {
                    model.renderToBuffer(pose, buffer, light, overlay, color);
                }
                return;
            }
        }
        // A state without an extraction id, unsupported feature node, or non-model submit has no
        // safe RT owner. It must remain visible whenever the current world pass is still native.
        if (!suppressVanilla) {
            model.renderToBuffer(pose, buffer, light, overlay, color);
        }
    }
}
