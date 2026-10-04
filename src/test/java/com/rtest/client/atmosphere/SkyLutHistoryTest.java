package com.rtest.client.atmosphere;

/** Pure submission-state coverage; not a Vulkan test. */
public final class SkyLutHistoryTest {
    public static void main(String[] args) {
        SkyLutHistory history = new SkyLutHistory();
        rejects(IllegalArgumentException.class, () -> history.prepare(Float.NaN, 0.5F));
        rejects(IllegalArgumentException.class, () -> history.prepare(6361F, Float.POSITIVE_INFINITY));
        long first = history.prepare(6361F, 0.5F);
        check(first != 0L, "initial table must be dirty");
        rejects(IllegalStateException.class, () -> history.prepare(6361F, 0.5F));
        rejects(IllegalArgumentException.class, () -> history.completed(first + 1));
        rejects(IllegalArgumentException.class, () -> history.abandon(first + 1));
        history.abandon(first);
        long retry = history.prepare(6361F, 0.5F);
        check(retry != 0L && retry != first, "abandon must permit same-input retry");
        rejects(IllegalArgumentException.class, () -> history.completed(first));
        history.completed(retry);
        rejects(IllegalArgumentException.class, () -> history.completed(retry));
        check(history.prepare(6361F, 0.5F) == 0L, "completed same key must be a no-op");
        // Azimuth is not an input to the public API: different lookup orientation shares this key.
        for (float azimuth : new float[] {0F, 1F, 3F}) {
            check(history.prepare(6361F, 0.5F) == 0L, "azimuth must not invalidate sky: " + azimuth);
        }
        long eye = history.prepare(6362F, 0.5F);
        history.abandon(eye);
        check(history.prepare(6361F, 0.5F) == 0L, "abandon must preserve previously completed key");
        long sun = history.prepare(6361F, -0F);
        history.completed(sun);
        long positiveZero = history.prepare(6361F, 0F);
        check(positiveZero != 0L, "key must compare float bits, including signed zero");
        history.abandon(positiveZero);
        history.completed(0L);
        history.abandon(0L);
        System.out.println("Sky LUT fence-commit history passed (CPU only)");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); } catch (RuntimeException failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
