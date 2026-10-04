// Prime licensing and additional permissions: see LICENSE and LICENSE-EXCEPTIONS.
// RTest adaptation of Prime 26.3 AtmosphereCoordinates (3ab5f75e33de3f6947e96f4a4dd1406588e9e98b):
// inline the pinned scalar ABI constants and reject non-finite camera coordinates.
package com.rtest.client.atmosphere;

/** Pure mapping from absolute Minecraft height to Prime's physical atmosphere shell. */
public final class AtmosphereCoordinates {
    public static final float WORLD_GROUND_Y = -64.0F;
    public static final float WORLD_UNIT_SCALE_KM = 0.001F;
    private static final float BOTTOM_RADIUS_KM = 6360.0F;
    private static final float TOP_RADIUS_KM = 6480.0F;

    private AtmosphereCoordinates() {
    }

    public static float eyeRadiusKm(double worldY, AtmosphereSettings settings) {
        if (!Double.isFinite(worldY)) {
            throw new IllegalArgumentException("Atmosphere world height must be finite");
        }
        float radius = BOTTOM_RADIUS_KM + worldAltitudeKm(worldY, settings);
        return Math.max(BOTTOM_RADIUS_KM, Math.min(TOP_RADIUS_KM - WORLD_UNIT_SCALE_KM, radius));
    }

    public static float worldAltitudeKm(double worldY, AtmosphereSettings settings) {
        return (float)((worldY - WORLD_GROUND_Y) * WORLD_UNIT_SCALE_KM
            + settings.altitudeOffsetMeters() * 0.001);
    }
}
