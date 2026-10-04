package com.rtest.client.fsr;

/** Shared per-frame contract between RayGen guide production and post-process consumption. */
public enum RtestDenoiserMode {
    OFF(-1.0F),
    NRD(1.0F);

    private final float shaderSignal;

    RtestDenoiserMode(float shaderSignal) {
        this.shaderSignal = shaderSignal;
    }

    public float shaderSignal() {
        return this.shaderSignal;
    }

    public boolean needsGuides() {
        return this != OFF;
    }

    public static RtestDenoiserMode select(boolean nrdEnabled, float nrdStrength) {
        if (nrdEnabled && nrdStrength > 0.0001F) return NRD;
        return OFF;
    }
}
