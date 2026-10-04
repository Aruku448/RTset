package com.rtest.client;

/** Material classification for thin foliage sunlight. Ground grass stays ordinary terrain. */
final class RayTracingVegetation {
    private RayTracingVegetation() { }

    static int kind(String blockPath, boolean leavesTag) {
        if (leavesTag || blockPath.endsWith("_leaves")) return 2;
        return switch (blockPath) {
            case "grass", "short_grass", "tall_grass", "fern", "large_fern",
                "short_dry_grass", "tall_dry_grass", "bush" -> 1;
            default -> 0;
        };
    }
}
