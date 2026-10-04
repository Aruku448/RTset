package com.rtest.client;

/** Ensures changing per-frame entity metrics cannot generate unbounded INFO logs. */
public final class DynamicSnapshotLogThrottleTest {
    private DynamicSnapshotLogThrottleTest() { }

    public static void main(String[] args) {
        DynamicSnapshotLogThrottle throttle = new DynamicSnapshotLogThrottle(5_000_000_000L);
        if (!throttle.shouldLog("entities=3 changed=1", 10L)) {
            throw new AssertionError("first dynamic snapshot was not logged");
        }
        if (throttle.shouldLog("entities=3 changed=2", 1_000_000_000L)) {
            throw new AssertionError("a per-frame metric change bypassed the log interval");
        }
        if (!throttle.shouldLog("entities=3 changed=2", 5_000_000_010L)) {
            throw new AssertionError("changed summary was not logged after the interval");
        }
        if (throttle.shouldLog("entities=3 changed=2", 11_000_000_000L)) {
            throw new AssertionError("unchanged summary was logged repeatedly");
        }
        throttle.reset();
        if (!throttle.shouldLog("entities=3 changed=2", 20L)) {
            throw new AssertionError("reset did not allow the next summary");
        }
        System.out.println("Dynamic snapshot log throttle contracts passed");
    }
}
