package com.rtest.client;

import org.slf4j.Logger;

/** Low-frequency CPU frame timing for the RT path; GPU phase timing is logged by the Vulkan pass when supported. */
final class RayTracingFrameTiming {
    enum Segment {
        RESOURCE_MATCH("resource_match"),
        RESOURCE_REBUILD("resource_rebuild"),
        GEOMETRY_UPDATE("geometry_update"),
        DYNAMIC_UPDATE("dynamic_update"),
        PBR_SYNC("pbr_sync"),
        COMMAND_RECORD("command_record"),
        FENCE_WAIT_CPU("fence_wait_cpu"),
        READBACK("readback");

        private final String logName;

        Segment(String logName) {
            this.logName = logName;
        }
    }

    private static final long LOG_INTERVAL_FRAMES = 120L;
    private final long[] nanos = new long[Segment.values().length];

    void reset() {
        java.util.Arrays.fill(this.nanos, 0L);
    }

    void add(Segment segment, long startNanos) {
        this.nanos[segment.ordinal()] += System.nanoTime() - startNanos;
    }

    void log(Logger logger, String scope, long frame, boolean success) {
        if (frame <= 0L || frame % LOG_INTERVAL_FRAMES != 0L) {
            return;
        }
        logger.info(
            "RTest frame_timing scope={} frame={} success={} resource_match_us={} resource_rebuild_us={} geometry_update_us={} dynamic_update_us={} pbr_sync_us={} command_record_us={} fence_wait_cpu_us={} readback_us={} gpu_timing=unavailable",
            scope,
            frame,
            success,
            micros(Segment.RESOURCE_MATCH),
            micros(Segment.RESOURCE_REBUILD),
            micros(Segment.GEOMETRY_UPDATE),
            micros(Segment.DYNAMIC_UPDATE),
            micros(Segment.PBR_SYNC),
            micros(Segment.COMMAND_RECORD),
            micros(Segment.FENCE_WAIT_CPU),
            micros(Segment.READBACK)
        );
    }

    private long micros(Segment segment) {
        return this.nanos[segment.ordinal()] / 1_000L;
    }
}
