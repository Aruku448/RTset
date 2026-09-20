package com.rtest.client.fsr;

import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;

/** VMA-backed 2D image with a full view and optional per-mip storage views. */
final class RtestVulkanImage implements AutoCloseable {
    private final long allocator;
    private final VkDevice device;
    private final long image;
    private final long allocation;
    private final long view;
    private final long[] mipViews;
    private final int format;
    private final int width;
    private final int height;
    private boolean initialized;
    private boolean destroyed;

    RtestVulkanImage(long allocator, VkDevice device, long image, long allocation, long view,
                     long[] mipViews, int format, int width, int height) {
        this.allocator = allocator;
        this.device = device;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.mipViews = mipViews.clone();
        this.format = format;
        this.width = width;
        this.height = height;
    }

    long image() {
        return this.image;
    }

    long view() {
        return this.view;
    }

    long mipView(int level) {
        return this.mipViews[level];
    }

    int mipLevels() {
        return this.mipViews.length;
    }

    int width() {
        return this.width;
    }

    int height() {
        return this.height;
    }

    int format() {
        return this.format;
    }

    boolean initialized() {
        return this.initialized;
    }

    void markInitialized() {
        this.initialized = true;
    }

    void destroy() {
        close();
    }

    @Override
    public void close() {
        if (!this.destroyed) {
            this.destroyed = true;
            for (int level = this.mipViews.length - 1; level >= 0; level--) {
                if (this.mipViews[level] != this.view) {
                    VK12.vkDestroyImageView(this.device, this.mipViews[level], null);
                }
            }
            VK12.vkDestroyImageView(this.device, this.view, null);
            Vma.vmaDestroyImage(this.allocator, this.image, this.allocation);
        }
    }
}
