package com.rtest.mixin;

import com.mojang.blaze3d.platform.GLX;
import com.rtest.client.HdrSupport;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets the HDR path use the native Wayland surface instead of Minecraft's X11 default. */
@Mixin(GLX.class)
public abstract class GlxMixin {
    @Redirect(
        method = "_initGlfw",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwInitHint(II)V")
    )
    private static void rtest$preferWayland(int hint, int value) {
        if (hint == GLFW.GLFW_PLATFORM && HdrSupport.shouldPreferWayland()) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_WAYLAND);
        } else {
            GLFW.glfwInitHint(hint, value);
        }
    }
}
