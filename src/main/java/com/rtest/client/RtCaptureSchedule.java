package com.rtest.client;

/** Render-thread capture routing; work already in flight is checked by the caller. */
enum RtCaptureSchedule {
    NONE,
    DIRTY,
    FULL;

    static RtCaptureSchedule select(boolean hasSnapshot, boolean fullRequested,
                                    boolean hasDirtySections, boolean dirtyBatchReady) {
        if (fullRequested || !hasSnapshot) return FULL;
        // sceneDirty also covers small incremental notifications. Before their batch is ready,
        // wait rather than rebuilding the whole scene and invalidating that work on the next event.
        if (hasDirtySections && dirtyBatchReady) return DIRTY;
        return NONE;
    }
}
