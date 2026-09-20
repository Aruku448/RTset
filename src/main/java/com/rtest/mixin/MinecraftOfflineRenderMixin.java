// Adapted from Prime 26.3 MinecraftMixin screenshot-mode shortcut.
package com.rtest.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.rtest.client.OfflineRenderController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftOfflineRenderMixin {
    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void rtest$offlineRenderShortcut(
            InputConstants.Key key,
            boolean controlDown,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        Minecraft minecraft = (Minecraft)(Object)this;
        if (OfflineRenderController.handleShortcut(minecraft, key)) {
            callbackInfo.setReturnValue(true);
        }
    }
}
