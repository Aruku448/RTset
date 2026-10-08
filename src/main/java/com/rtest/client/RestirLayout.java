package com.rtest.client;

/** CPU/shader contract for optional, ping-ponged ReSTIR storage. */
final class RestirLayout {
    static final int DIRECT = 1;
    static final int SUFFIX = 2;
    static final int HISTORY_PERIOD = 8;
    static final int PARAMETER_BYTES = 32;

    private RestirLayout() {}

    static int stride(int mode) {
        if ((mode & ~3) != 0) throw new IllegalArgumentException("ReSTIR mode");
        return Integer.bitCount(mode) * 2; // Each sample + supporting geometry is two vec4s.
    }

    static long bytes(int width, int height, int mode) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("ReSTIR extent");
        return Math.max(16L, Math.multiplyExact(Math.multiplyExact((long) width, height), stride(mode) * 16L));
    }

    static boolean historyUsable(boolean ready, boolean reset, long previousRevision,
                                 long revision, int frame) {
        // Global expiry is independent of the selected sample; per-sample age rejection is not.
        return ready && !reset && previousRevision == revision && frame % HISTORY_PERIOD != 0;
    }
}
