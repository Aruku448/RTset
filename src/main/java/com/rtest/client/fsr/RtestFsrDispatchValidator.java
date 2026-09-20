package com.rtest.client.fsr;

/** Small API-independent equivalent of the FSR integration checks Prime bypasses with its direct Vulkan path. */
public final class RtestFsrDispatchValidator {
    private RtestFsrDispatchValidator() {
    }

    public static void validate(
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            RtestFsrSettings.Jitter jitter,
            float exposure,
            float motionScaleX,
            float motionScaleY) {
        if (renderWidth <= 0 || renderHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("FSR extents must be positive");
        }
        if (renderWidth > displayWidth || renderHeight > displayHeight) {
            throw new IllegalArgumentException("FSR render extent must not exceed the display extent");
        }
        if (jitter == null
                || !Float.isFinite(jitter.x())
                || !Float.isFinite(jitter.y())
                || Math.abs(jitter.x()) > 0.5F
                || Math.abs(jitter.y()) > 0.5F) {
            throw new IllegalArgumentException("FSR jitter must be finite and inside one source pixel");
        }
        if (!Float.isFinite(exposure) || exposure <= 0.0F) {
            throw new IllegalArgumentException("FSR exposure must be finite and positive");
        }
        if (!Float.isFinite(motionScaleX)
                || !Float.isFinite(motionScaleY)
                || motionScaleX == 0.0F
                || motionScaleY == 0.0F) {
            throw new IllegalArgumentException("FSR motion-vector scale must be finite and non-zero");
        }
    }
}
