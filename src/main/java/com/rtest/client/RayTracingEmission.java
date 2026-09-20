package com.rtest.client;

/** Prime-calibrated emission mapping for Minecraft block light levels. */
final class RayTracingEmission {
    private RayTracingEmission() {
    }

    static float fromMinecraftLevel(int level) {
        int clamped = Math.max(0, Math.min(15, level));
        return 1.5F * clamped * clamped / 225.0F;
    }
}
