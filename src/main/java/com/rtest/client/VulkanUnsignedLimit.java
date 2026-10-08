package com.rtest.client;

/** VkPhysicalDeviceLimits uint32_t values may appear negative in LWJGL's Java int API. */
public final class VulkanUnsignedLimit {
    private VulkanUnsignedLimit() {}

    public static boolean below(int reported, int required) {
        if (required < 0) {
            throw new IllegalArgumentException("Required limit must be nonnegative");
        }
        return Integer.compareUnsigned(reported, required) < 0;
    }
}
