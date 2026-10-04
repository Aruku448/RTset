package com.rtest.client;

import com.rtest.client.atmosphere.AtmosphereCoordinates;
import com.rtest.client.atmosphere.AtmosphereSettings;

/** Physical asset/coordinate boundary: known kilometre datum, not scene-relative coordinates. */
public final class AtmosphereCoordinatesTest {
    public static void main(String[] args) {
        var settings = AtmosphereSettings.defaults();
        equal(6360.3F, AtmosphereCoordinates.eyeRadiusKm(-64.0, settings));
        equal(6360.428F, AtmosphereCoordinates.eyeRadiusKm(64.0, settings));
        equal(6360.0F, AtmosphereCoordinates.eyeRadiusKm(-10000.0, settings));
        equal(6479.999F, AtmosphereCoordinates.eyeRadiusKm(200000.0, settings));
        equal(6360.0F, AtmosphereCoordinates.eyeRadiusKm(-64.0, new AtmosphereSettings(100, 0)));
        equal(6370.0F, AtmosphereCoordinates.eyeRadiusKm(-64.0, new AtmosphereSettings(100, 10000)));
        try {
            AtmosphereCoordinates.eyeRadiusKm(Double.NaN, settings);
            throw new AssertionError("non-finite camera height accepted");
        } catch (IllegalArgumentException expected) {
            // Non-finite world coordinates cannot become a LUT key or GPU push constant.
        }
        System.out.println("AtmosphereCoordinatesTest passed");
    }

    private static void equal(float expected, float actual) {
        if (Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
            throw new AssertionError("eye radius: expected " + expected + ", got " + actual);
        }
    }
}
