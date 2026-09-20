// Adapted from Prime 26.3 dev.prime.render.RgbReinhardOutput.
// Prime licensing and additional permissions: see third_party/PRIME-LICENSE*.txt.
package com.rtest.client;

/** Derived output parameters for Prime's RGB Reinhard display transform. */
public final class PrimeRgbReinhardOutput {
    public static final double COMPRESSION_START = 0.18;
    public static final double HIGHLIGHT_REACH_EV = 8.0;

    private PrimeRgbReinhardOutput() {
    }

    public static Parameters parameters(float requestedHeadroom) {
        if (!Float.isFinite(requestedHeadroom)) {
            throw new IllegalArgumentException("RGB Reinhard headroom must be finite");
        }
        double headroom = Math.clamp((double)requestedHeadroom, 1.0, 10_000.0);
        double reachInput = 0.18 * Math.pow(2.0, HIGHLIGHT_REACH_EV) * headroom;
        double tangentDistance = reachInput - COMPRESSION_START;
        double outputDistance = headroom - COMPRESSION_START;
        double extent = outputDistance * tangentDistance / (tangentDistance - outputDistance);
        return new Parameters((float)headroom, (float)(COMPRESSION_START + extent));
    }

    public record Parameters(float outputPeak, float curvePeak) {
        public Parameters {
            if (!Float.isFinite(outputPeak) || !Float.isFinite(curvePeak)
                    || outputPeak < 1.0F || outputPeak > 10_000.0F || curvePeak <= outputPeak) {
                throw new IllegalArgumentException("Invalid derived RGB Reinhard parameters");
            }
        }
    }
}
