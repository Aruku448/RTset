package com.rtest.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vulkan command recording is thread-affine on RADV. Minecraft 26.2 can bake GUI glyphs from a
 * worker and call writeToTexture there (opening the inventory makes this path very likely). Queue
 * that upload on the render thread instead of letting RADV receive vkCmdCopyBufferToImage from a
 * worker thread.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandThreadGuardMixin {
    @Inject(method = "writeToTexture", at = @At("HEAD"), cancellable = true)
    private void rtest$serializeTextureUpload(
            GpuTexture texture,
            ByteBuffer data,
            int width,
            int height,
            int offsetX,
            int offsetY,
            int mipLevel,
            int pixelFormat,
            CallbackInfo callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            return;
        }

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        minecraft.execute(() -> {
            try {
                ((VulkanCommandEncoder)(Object)this).writeToTexture(
                    texture, data, width, height, offsetX, offsetY, mipLevel, pixelFormat);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });
        try {
            completed.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while serializing Vulkan texture upload", interrupted);
        }
        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(throwable);
        }
        callbackInfo.cancel();
    }
}
