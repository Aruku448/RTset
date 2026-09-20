// Adapted from Prime 26.3 TextureAtlasMixin.
package com.rtest.mixin;

import com.rtest.client.OfflineRenderController;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freezes animated block textures while an offline accumulation is active. */
@Mixin(TextureAtlas.class)
@SuppressWarnings("deprecation")
public abstract class TextureAtlasOfflineMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void rtest$freezeBlockAnimations(CallbackInfo callbackInfo) {
        TextureAtlas atlas = (TextureAtlas)(Object)this;
        if (OfflineRenderController.active()
                && TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
            callbackInfo.cancel();
        }
    }
}
