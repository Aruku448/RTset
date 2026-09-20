package com.rtest.client.fsr;

import java.util.Arrays;
import java.util.Optional;

/** A complete FSR render-resolution preset and its matching temporal sampling contract. */
public enum RtestFsrQualityMode {
    NATIVE_AA("native_aa", 1.0F),
    QUALITY_75("quality_75", 4.0F / 3.0F),
    QUALITY("quality", 1.5F),
    BALANCED("balanced", 1.7F),
    PERFORMANCE("performance", 2.0F),
    ULTRA_PERFORMANCE("ultra_performance", 3.0F);

    private final String id;
    private final float upscaleRatio;
    private final int jitterPhaseCount;
    private final float mipBias;

    RtestFsrQualityMode(String id, float upscaleRatio) {
        this.id = id;
        this.upscaleRatio = upscaleRatio;
        this.jitterPhaseCount = Math.max(1, (int) (8.0F * upscaleRatio * upscaleRatio));
        this.mipBias = (float) (Math.log(1.0 / upscaleRatio) / Math.log(2.0) - 1.0);
    }

    public String id() {
        return this.id;
    }

    public float upscaleRatio() {
        return this.upscaleRatio;
    }

    public int jitterPhaseCount() {
        return this.jitterPhaseCount;
    }

    public float mipBias() {
        return this.mipBias;
    }

    public Extent renderExtent(int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("FSR display dimensions must be positive");
        }
        return new Extent(
                Math.max(1, (int) (displayWidth / this.upscaleRatio)),
                Math.max(1, (int) (displayHeight / this.upscaleRatio)));
    }

    public RtestFsrSettings.Jitter jitter(int frameIndex) {
        int phase = Math.floorMod(frameIndex, this.jitterPhaseCount) + 1;
        return new RtestFsrSettings.Jitter(
                halton(phase, 2) - 0.5F,
                halton(phase, 3) - 0.5F);
    }

    /**
     * Packs the primary-ray pixel-cone spread and this preset's FSR mip bias as half2.
     *
     * <p>These values must come from the same immutable mode as the FSR resources. Reading a
     * newly selected global mode while an older resource bundle is still rendering would make
     * texture filtering and temporal reconstruction disagree for one frame.
     */
    public int packedRayCone(float projectionM00, float projectionM11, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Ray-cone render dimensions must be positive");
        }
        float x = 2.0F / (width * Math.abs(projectionM00));
        float y = 2.0F / (height * Math.abs(projectionM11));
        float spread = Math.max(x, y);
        if (!Float.isFinite(spread) || spread <= 0.0F) {
            throw new IllegalArgumentException("Ray-cone projection must be finite and non-zero");
        }
        int low = Float.floatToFloat16(spread) & 0xffff;
        int high = Float.floatToFloat16(this.mipBias) & 0xffff;
        return low | high << 16;
    }

    public static Optional<RtestFsrQualityMode> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(id))
                .findFirst();
    }

    public static RtestFsrQualityMode fromId(String id) {
        return findById(id).orElse(QUALITY);
    }

    public record Extent(int width, int height) {
    }

    private static float halton(int index, int base) {
        float result = 0.0F;
        float fraction = 1.0F;
        int value = index;
        while (value > 0) {
            fraction /= base;
            result += fraction * (value % base);
            value /= base;
        }
        return result;
    }
}
