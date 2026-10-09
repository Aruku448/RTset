package com.rtest.client.fsr;

import java.util.Locale;

/** Requested temporal reconstruction; effective selection is gated by the attached device. */
public enum RtestUpscalerMode {
    FSR(0), DLSS(1), DLSS_RR(2);
    public final int nativeMode;
    RtestUpscalerMode(int nativeMode) { this.nativeMode = nativeMode; }
    public static RtestUpscalerMode fromId(String id) {
        if (id == null) return FSR;
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "dlss" -> DLSS;
            case "dlss_rr" -> DLSS_RR;
            default -> FSR;
        };
    }
    public static int quality(RtestFsrQualityMode quality) {
        return switch (quality) {
            case NATIVE_AA -> 0;
            case QUALITY_75, QUALITY -> 1;
            case BALANCED -> 2;
            case PERFORMANCE -> 3;
            case ULTRA_PERFORMANCE -> 4;
        };
    }
}
