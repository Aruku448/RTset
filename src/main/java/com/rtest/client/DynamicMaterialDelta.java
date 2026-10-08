package com.rtest.client;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.function.BiConsumer;

/** Writes maximal consecutive runs of changed triangle rows; no approximate/hash equality. */
final class DynamicMaterialDelta {
    static long write(FloatBuffer target, int baseTriangle, float[] previous, float[] current,
                      boolean force, BiConsumer<Long, Long> dirtyRange) {
        return write(target, baseTriangle, previous, current, force, dirtyRange, null);
    }

    record Upload(long bytes, float[] snapshot) { }

    /** Retain an owned snapshot, updating only the rows already copied to the mapped target. */
    static Upload writeAndRemember(FloatBuffer target, int baseTriangle, float[] previous,
                                   float[] current, boolean force, BiConsumer<Long, Long> dirtyRange) {
        float[] snapshot = previous != null && previous.length == current.length
            ? previous : new float[current.length];
        long bytes = write(target, baseTriangle, previous, current, force, dirtyRange, snapshot);
        return new Upload(bytes, snapshot);
    }

    private static long write(FloatBuffer target, int baseTriangle, float[] previous, float[] current,
                              boolean force, BiConsumer<Long, Long> dirtyRange, float[] snapshot) {
        int stride = RayTracingDynamicInstances.MATERIAL_FLOATS_PER_TRIANGLE;
        if (baseTriangle < 0 || current.length % stride != 0)
            throw new IllegalArgumentException("Invalid material triangle layout");
        int base = Math.multiplyExact(baseTriangle, stride);
        if (base > target.limit() || current.length > target.limit() - base)
            throw new IllegalArgumentException("Material write exceeds buffer");
        boolean full = force || previous == null || previous.length != current.length;
        long written = 0;
        int cursor = 0;
        while (cursor < current.length) {
            int start = cursor;
            int end = current.length;
            if (!full) {
                // Skip a whole unchanged prefix with the JDK array comparison intrinsic.
                int mismatch = Arrays.mismatch(previous, cursor, current.length,
                    current, cursor, current.length);
                if (mismatch < 0) break;
                start = cursor + (mismatch / stride) * stride;
                end = start + stride;
                while (end < current.length && !Arrays.equals(previous, end, end + stride,
                    current, end, end + stride)) end += stride;
            }
            int count = end - start;
            long byteOffset = (long)(base + start) * Float.BYTES;
            long byteCount = (long)count * Float.BYTES;
            // Register coverage before writing so exceptional exits still flush it.
            dirtyRange.accept(byteOffset, byteCount);
            target.position(base + start);
            target.put(current, start, count);
            if (snapshot != null) System.arraycopy(current, start, snapshot, start, count);
            written += byteCount;
            cursor = end;
        }
        return written;
    }
}
