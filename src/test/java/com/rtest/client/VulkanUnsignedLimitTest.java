package com.rtest.client;

public final class VulkanUnsignedLimitTest {
    public static void main(String[] args) {
        if (VulkanUnsignedLimit.below(-1, 27)
                || VulkanUnsignedLimit.below(Integer.MIN_VALUE, 128)
                || VulkanUnsignedLimit.below(27, 27)
                || VulkanUnsignedLimit.below(16384, 8193)
                || !VulkanUnsignedLimit.below(8192, 8193)
                || !VulkanUnsignedLimit.below(26, 27)
                || !VulkanUnsignedLimit.below(0, 1)) {
            throw new AssertionError("Vulkan uint32 limit comparison failed");
        }
        System.out.println("Vulkan unsigned limit tests passed");
    }
}
