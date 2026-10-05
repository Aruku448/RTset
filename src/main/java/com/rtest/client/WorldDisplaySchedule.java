package com.rtest.client;

/** A camera-independent world clock. Completion advances it, not display sampling. */
final class WorldDisplaySchedule {
    private long nextUpdateNanos;
    private boolean lastRaster;
    boolean begin(boolean raster, boolean invalidated, long nowNanos, int intervalMs) {
        if (raster != lastRaster) { nextUpdateNanos = 0; lastRaster = raster; }
        return raster && (invalidated || nextUpdateNanos == 0 || nowNanos >= nextUpdateNanos);
    }
    void submitted(long nowNanos, int intervalMs) {
        nextUpdateNanos = nowNanos + (long)intervalMs * 1_000_000L;
    }
    void invalidate() { nextUpdateNanos = 0; }
}
