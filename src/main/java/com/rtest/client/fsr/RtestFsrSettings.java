package com.rtest.client.fsr;

import java.util.Objects;

/** Runtime constants for the local FSR 3.1.5 Vulkan integration. */
public final class RtestFsrSettings {
    public static final String SDK_VERSION = "1.1.4";
    public static final String UPSCALER_VERSION = "3.1.5";
    public static final float EXPOSURE = 1.0F;
    public static final float RCAS_SHARPNESS = 0.2F;
    private static volatile RtestFsrDebugView debugView = RtestFsrDebugView.OFF;

    private RtestFsrSettings() {
    }

    public static RtestFsrDebugView debugView() {
        return debugView;
    }

    public static void setDebugView(RtestFsrDebugView value) {
        debugView = Objects.requireNonNull(value, "value");
    }

    public static float rcasLinearSharpness() {
        float stops = -2.0F * RCAS_SHARPNESS + 2.0F;
        return (float) Math.pow(2.0, -stops);
    }

    public record Jitter(float x, float y) {
        public Jitter forFsrDispatch() {
            return new Jitter(-this.x, -this.y);
        }
    }
}
