package com.rtest.client;

import java.util.ArrayList;
import java.util.List;

/** Aligned prefix sums give independent builds disjoint scratch; batches reuse the arena. */
final class BlasBuildBatch {
    static final int MAX_BUILDS = 32;
    static final long SCRATCH_BUDGET = 16L * 1024 * 1024;

    record Batch(int first, long[] offsets, long bytes) {
        int count() { return offsets.length; }
    }
    record Plan(List<Batch> batches, long bytes) { }

    static Plan plan(long[] sizes, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Scratch alignment must be a positive power of two");
        List<Batch> batches = new ArrayList<>();
        long maximum = 0;
        int first = 0;
        while (first < sizes.length) {
            long[] offsets = new long[Math.min(MAX_BUILDS, sizes.length - first)];
            long end = 0;
            int count = 0;
            while (count < offsets.length) {
                long size = sizes[first + count];
                if (size <= 0) throw new IllegalArgumentException("Scratch size must be positive");
                long offset = Math.addExact(end, alignment - 1) & -alignment;
                long next = Math.addExact(offset, size);
                // A single oversized build must still run, without an animation delay.
                if (count > 0 && next > SCRATCH_BUDGET) break;
                offsets[count++] = offset;
                end = next;
            }
            batches.add(new Batch(first, java.util.Arrays.copyOf(offsets, count), end));
            maximum = Math.max(maximum, end);
            first += count;
        }
        return new Plan(List.copyOf(batches), maximum);
    }
}
