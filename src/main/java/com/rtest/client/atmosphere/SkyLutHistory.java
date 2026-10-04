package com.rtest.client.atmosphere;

/** CPU-only transaction: keys become reusable only after the submission fence retires. */
public final class SkyLutHistory {
    private boolean valid;
    private int eye, sun, candidateEye, candidateSun;
    private long sequence, pending;

    /** Azimuth is deliberately absent: the sky LUT is oriented relative to the sun. */
    public long prepare(float eyeRadiusKm, float sunElevationY) {
        if (pending != 0L) throw new IllegalStateException("Sky LUT transaction is pending");
        if (!Float.isFinite(eyeRadiusKm) || eyeRadiusKm < 6360.0F || eyeRadiusKm >= 6480.0F
            || !Float.isFinite(sunElevationY) || Math.abs(sunElevationY) > 1.0F) {
            throw new IllegalArgumentException("Sky LUT requires a finite shell radius and normalized sun elevation");
        }
        int nextEye = Float.floatToIntBits(eyeRadiusKm);
        int nextSun = Float.floatToIntBits(sunElevationY);
        if (valid && eye == nextEye && sun == nextSun) return 0L;
        candidateEye = nextEye;
        candidateSun = nextSun;
        if (++sequence == 0L) ++sequence;
        return pending = sequence;
    }

    public void completed(long token) {
        if (token == 0L) return;
        requirePending(token);
        eye = candidateEye;
        sun = candidateSun;
        valid = true;
        pending = 0L;
    }

    /** Only for a recording which was never submitted. */
    public void abandon(long token) {
        if (token == 0L) return;
        requirePending(token);
        pending = 0L;
    }

    private void requirePending(long token) {
        if (pending == 0L || token != pending) {
            throw new IllegalArgumentException("Sky LUT token does not belong to the pending transaction");
        }
    }
}
