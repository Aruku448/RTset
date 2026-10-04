package com.rtest.client;

/** Artist-authored daylight alpha curves; independent of radiance and surface sunlight. */
final class SkyboxOpacityCurve {
    // Ascending brightness for both tables, even when the afternoon is traversed downward.
    private static final float[] MORNING = {
        0, 0.15F, 1, 0.10F, 2, 0.08F, (8.0F - 4.0F) * (15.0F / 11.0F), 0.09F,
        6, 0.13F, 8, 0.20F,
        9, 0.35F, 10, 0.60F, 11, 0.70F, 15, 0.90F
    };
    private static final float[] AFTERNOON = {
        0, 0.15F, 1, 0.20F, 2, 0.30F, 10, 0.40F, 13, 0.70F, 15, 0.90F
    };

    private SkyboxOpacityCurve() {
    }

    // Minecraft 26.2 retains level 4 at night. Map that baseline to daylight 0.
    // Use the same real world clock as SKY_LIGHT_LEVEL, not the RT sun-angle offset.
    static float resolveOpacity(boolean curveEnabled, float manual, float skyLightLevel, long clockTicks) {
        return curveEnabled ? fromSkyLightLevel(skyLightLevel, clockTicks) : manual;
    }

    static float resolveSunIntensity(boolean curveEnabled, float manual, float peak, float skyLightLevel) {
        if (!curveEnabled) return manual;
        if (!Float.isFinite(skyLightLevel)) return 3.0F;
        float daylight = Math.clamp((skyLightLevel - 4.0F) / 11.0F, 0.0F, 1.0F);
        return 3.0F + (peak - 3.0F) * daylight * daylight * (3.0F - 2.0F * daylight);
    }

    static float fromSkyLightLevel(float skyLightLevel, long clockTicks) {
        long time = Math.floorMod(clockTicks, 24000L);
        boolean afternoon = time >= 6000L && time < 18000L;
        return fromBrightness((skyLightLevel - 4.0F) * (15.0F / 11.0F), afternoon);
    }

    static float fromBrightness(float brightness, boolean afternoon) {
        if (!Float.isFinite(brightness)) {
            return 0.15F;
        }
        float[] points = afternoon ? AFTERNOON : MORNING;
        if (brightness <= points[0]) {
            return points[1];
        }
        for (int i = 2; i < points.length; i += 2) {
            if (brightness <= points[i]) {
                if (brightness == points[i]) {
                    return points[i + 1];
                }
                double t = (brightness - (double)points[i - 2]) / (points[i] - points[i - 2]);
                double weight = 0.5D - 0.5D * Math.cos(Math.PI * t);
                return (float)(points[i - 1] + (points[i + 1] - (double)points[i - 1]) * weight);
            }
        }
        return points[points.length - 1];
    }
}
