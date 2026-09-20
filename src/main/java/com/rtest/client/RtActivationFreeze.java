package com.rtest.client;

/** Pure policy for the scene snapshot barrier used while RT is entering. */
final class RtActivationFreeze {
    private RtActivationFreeze() {
    }

    static boolean mayPublishFullSnapshot(
        boolean frozen,
        boolean sameWorld,
        boolean sameRenderDistance,
        boolean generationCurrent,
        boolean recaptureRequested
    ) {
        return sameWorld
            && sameRenderDistance
            && (frozen || (generationCurrent && !recaptureRequested));
    }

    static boolean holdIncrementalUpdates(boolean frozen, boolean hasPublishedSnapshot) {
        return frozen && hasPublishedSnapshot;
    }
}
