package com.rtest.client;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** One precision for both the slider's stored value and its visible value. */
final class SettingValueFormat {
    private SettingValueFormat() {}

    static int decimals(double minimum, double maximum, double fallback, boolean integer) {
        if (integer) return 0;
        int required = Math.max(scale(minimum), scale(fallback));
        int baseline = minimum >= 100 && maximum >= 100 ? 0 : maximum - minimum <= 0.5 ? 3 : 2;
        return Math.min(6, Math.max(baseline, required));
    }

    private static int scale(double value) {
        return Math.max(0, BigDecimal.valueOf(value).stripTrailingZeros().scale());
    }

    static double snap(double value, double minimum, double maximum, int decimals) {
        double rounded = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
        return Math.clamp(rounded, minimum, maximum);
    }

    static String format(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }
}
