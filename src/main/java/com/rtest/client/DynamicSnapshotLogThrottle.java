package com.rtest.client;

import java.util.Objects;

/** Rate-limits changing dynamic-entity diagnostics so per-frame snapshots cannot flood disk logs. */
final class DynamicSnapshotLogThrottle {
    private final long intervalNanos;
    private String lastLoggedSummary;
    private long lastLoggedNanos;
    private boolean hasLogged;

    DynamicSnapshotLogThrottle(long intervalNanos) {
        if (intervalNanos <= 0L) {
            throw new IllegalArgumentException("intervalNanos must be positive");
        }
        this.intervalNanos = intervalNanos;
    }

    boolean shouldLog(String summary, long nowNanos) {
        Objects.requireNonNull(summary, "summary");
        if (summary.equals(lastLoggedSummary)) {
            return false;
        }
        if (hasLogged && nowNanos - lastLoggedNanos < intervalNanos) {
            return false;
        }
        lastLoggedSummary = summary;
        lastLoggedNanos = nowNanos;
        hasLogged = true;
        return true;
    }

    void reset() {
        lastLoggedSummary = null;
        lastLoggedNanos = 0L;
        hasLogged = false;
    }
}
