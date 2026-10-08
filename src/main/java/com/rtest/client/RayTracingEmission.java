package com.rtest.client;

/** Prime-calibrated emission mapping for Minecraft block light levels. */
final class RayTracingEmission {
    // Historical calibration for fixed fluid/item radiance, independent of user defaults.
    static final float LEGACY_REFERENCE_SCALE = 25.0F;
    private RayTracingEmission() {
    }

    static float fromMinecraftLevel(int level) {
        int clamped = Math.max(0, Math.min(15, level));
        return 1.5F * clamped * clamped / 225.0F;
    }
}
