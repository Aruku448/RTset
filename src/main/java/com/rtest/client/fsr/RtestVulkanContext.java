package com.rtest.client.fsr;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

/** Uses Minecraft's already-created VMA allocator; it does not create a second Vulkan device. */
final class RtestVulkanContext {
    private final VulkanDevice device;
    private final long uniformBufferOffsetAlignment;

    RtestVulkanContext(VulkanDevice device) {
        this.device = device;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
            this.uniformBufferOffsetAlignment = properties.limits().minUniformBufferOffsetAlignment();
        }
    }

    VulkanDevice device() {
        return this.device;
    }

    VkDevice vkDevice() {
        return this.device.vkDevice();
    }

    long uniformBufferOffsetAlignment() {
        return this.uniformBufferOffsetAlignment;
    }

    static long alignUp(long value, long alignment) {
        if (alignment <= 0L) {
            return value;
        }
        return Math.addExact(value, alignment - 1L) / alignment * alignment;
    }

    RtestVulkanBuffer createBuffer(long size, int usage, boolean hostVisible, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(hostVisible ? Vma.VMA_MEMORY_USAGE_AUTO_PREFER_HOST : Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostVisible) {
                allocationInfo.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                        | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer bufferPointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            VmaAllocationInfo mappedInfo = VmaAllocationInfo.calloc(stack);
            check(Vma.vmaCreateBuffer(this.device.vma(), bufferInfo, allocationInfo,
                    bufferPointer, allocationPointer, mappedInfo), "create " + label);
            long handle = bufferPointer.get(0);
            long allocation = allocationPointer.get(0);
            try {
                VkBufferDeviceAddressInfo addressInfo = VkBufferDeviceAddressInfo.calloc(stack)
                        .sType$Default().buffer(handle);
                long address = VK12.vkGetBufferDeviceAddress(this.vkDevice(), addressInfo);
                return new RtestVulkanBuffer(this.device.vma(), allocation, handle,
                        address, hostVisible ? mappedInfo.pMappedData() : 0L, size);
            } catch (Throwable throwable) {
                Vma.vmaDestroyBuffer(this.device.vma(), handle, allocation);
                throw throwable;
            }
        }
    }

    RtestVulkanImage createImage2D(int width, int height, int format, int usage, String label) {
        return createImage(width, height, 1, format, usage, label);
    }

    RtestVulkanImage createMipmappedImage2D(int width, int height, int mipLevels, int format,
                                            int usage, String label) {
        return createImage(width, height, mipLevels, format, usage, label);
    }

    private RtestVulkanImage createImage(int width, int height, int mipLevels, int format,
                                         int usage, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imagePointer = stack.mallocLong(1);
            PointerBuffer allocationPointer = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(this.device.vma(), imageInfo, allocationInfo,
                    imagePointer, allocationPointer, null), "create " + label + " image");
            long image = imagePointer.get(0);
            long view = 0L;
            long[] mipViews = null;
            int created = 0;
            try {
                mipViews = new long[mipLevels];
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default().image(image).viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(format);
                viewInfo.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
                LongBuffer viewPointer = stack.mallocLong(1);
                check(VK12.vkCreateImageView(this.vkDevice(), viewInfo, null, viewPointer),
                        "create " + label + " view");
                view = viewPointer.get(0);
                if (mipLevels == 1) {
                    mipViews[0] = view;
                } else {
                    for (int level = 0; level < mipLevels; level++) {
                        VkImageViewCreateInfo mipInfo = VkImageViewCreateInfo.calloc(stack)
                                .sType$Default().image(image).viewType(VK12.VK_IMAGE_VIEW_TYPE_2D).format(format);
                        mipInfo.subresourceRange().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(level).levelCount(1).baseArrayLayer(0).layerCount(1);
                        viewPointer.clear();
                        check(VK12.vkCreateImageView(this.vkDevice(), mipInfo, null, viewPointer),
                                "create " + label + " mip view");
                        mipViews[level] = viewPointer.get(0);
                        created++;
                    }
                }
                return new RtestVulkanImage(this.device.vma(), this.vkDevice(), image,
                        allocationPointer.get(0), view, mipViews, format, width, height);
            } catch (Throwable throwable) {
                if (mipViews != null) {
                    for (int level = created - 1; level >= 0; level--) {
                        VK12.vkDestroyImageView(this.vkDevice(), mipViews[level], null);
                    }
                }
                if (view != 0L) {
                    VK12.vkDestroyImageView(this.vkDevice(), view, null);
                }
                Vma.vmaDestroyImage(this.device.vma(), image, allocationPointer.get(0));
                throw throwable;
            }
        }
    }

    static void check(int result, String operation) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with Vulkan result " + result);
        }
    }
}
