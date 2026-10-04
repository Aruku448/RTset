package com.rtest.client;

/** A completed copy/BLAS-only submission does not retire references in the old TLAS. */
final class RtBlasRetirement {
    private boolean pendingTlasBuild;

    void submitted(boolean tlasBuildRecorded) {
        this.pendingTlasBuild = tlasBuildRecorded;
    }

    /** Called only after the associated fence completed; never on timeout or without a fence. */
    boolean completed() {
        boolean retire = this.pendingTlasBuild;
        this.pendingTlasBuild = false;
        return retire;
    }
}
