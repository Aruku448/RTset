package com.rtest.client;

import java.util.ArrayList;
import java.util.List;

/** Register compensation before mutation: even a partially failing write must be restored. */
final class RtResourceRollback {
    private final List<Runnable> actions = new ArrayList<>();

    void before(Runnable restore) {
        this.actions.add(restore);
    }

    void restore(Throwable failure) {
        for (int i = this.actions.size() - 1; i >= 0; i--) attempt(failure, this.actions.get(i));
        this.actions.clear();
    }

    static void attempt(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }
}
