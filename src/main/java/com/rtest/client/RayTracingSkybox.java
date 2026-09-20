package com.rtest.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.system.MemoryUtil;

/** Owns the six-face cube texture used by the RT miss shader. */
final class RayTracingSkybox implements AutoCloseable {
    // Source VTF skyboxes use the opposite Z face naming from the Vulkan cube-map convention.
    private static final String[] FACES = {"right", "left", "up", "down", "back", "front"};
    private final VulkanGpuTexture texture;
    private final VulkanGpuTextureView view;
    private boolean closed;

    private RayTracingSkybox(VulkanGpuTexture texture, VulkanGpuTextureView view) {
        this.texture = texture;
        this.view = view;
    }

    static RayTracingSkybox create(VulkanDevice device, ResourceManager resourceManager) {
        NativeImage[] images = new NativeImage[FACES.length];
        VulkanGpuTexture texture = null;
        VulkanGpuTextureView view = null;
        VulkanCommandEncoder encoder = null;
        RayTracingSkybox result = null;
        try {
            int width = -1;
            int height = -1;
            for (int i = 0; i < FACES.length; i++) {
                Identifier location = Identifier.fromNamespaceAndPath("rtest", "skybox/" + FACES[i] + ".png");
                try (InputStream input = resourceManager.getResourceOrThrow(location).open()) {
                    images[i] = NativeImage.read(input);
                }
                if (width < 0) {
                    width = images[i].getWidth();
                    height = images[i].getHeight();
                } else if (images[i].getWidth() != width || images[i].getHeight() != height) {
                    throw new IllegalStateException("Skybox faces must have identical dimensions");
                }
            }

            GpuTexture createdTexture = device.createTexture(
                "rtest_skybox",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_CUBEMAP_COMPATIBLE,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                6,
                1
            );
            if (!(createdTexture instanceof VulkanGpuTexture vulkanTexture)) {
                createdTexture.close();
                throw new IllegalStateException("RTest skybox requires a Vulkan texture");
            }
            texture = vulkanTexture;
            // createCommandEncoder() returns the device-owned Minecraft encoder. Use a private
            // encoder here because this upload is explicitly destroyed in the finally block.
            encoder = new VulkanCommandEncoder(device);
            for (int layer = 0; layer < images.length; layer++) {
                NativeImage image = images[layer];
                java.nio.ByteBuffer rgba = MemoryUtil.memAlloc(width * height * 4);
                try {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int pixel = image.getPixel(x, y);
                            rgba.put((byte)((pixel >> 16) & 0xff));
                            rgba.put((byte)((pixel >> 8) & 0xff));
                            rgba.put((byte)(pixel & 0xff));
                            rgba.put((byte)((pixel >>> 24) & 0xff));
                        }
                    }
                    rgba.flip();
                    encoder.writeToTexture(texture, rgba, 0, layer, 0, 0, width, height);
                } finally {
                    MemoryUtil.memFree(rgba);
                }
            }
            try (GpuFence fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(5_000_000_000L)) {
                    throw new IllegalStateException("Timed out waiting for RTest skybox upload");
                }
            }
            var createdView = device.createTextureView(texture);
            if (!(createdView instanceof VulkanGpuTextureView vulkanView)) {
                createdView.close();
                throw new IllegalStateException("RTest skybox requires a Vulkan texture view");
            }
            view = vulkanView;
            result = new RayTracingSkybox(texture, view);
            return result;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to load RTest skybox", exception);
        } finally {
            try {
                if (encoder != null) {
                    // This upload encoder owns command pools, transient staging memory and a
                    // submission semaphore. It is temporary and must not survive the skybox upload.
                    // Destroy it before failure cleanup so a timed-out upload cannot race texture
                    // destruction.
                    encoder.destroy();
                }
            } finally {
                if (result == null) {
                    if (view != null) {
                        view.close();
                    }
                    if (texture != null) {
                        texture.close();
                    }
                }
                for (NativeImage image : images) {
                    if (image != null) {
                        image.close();
                    }
                }
            }
        }
    }

    long imageView() {
        return view.vkImageView();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            view.close();
        } finally {
            texture.close();
        }
    }
}
