package com.rtest.client;

/** Primitive float accumulator used by per-draw capture paths. */
final class FloatArrayBuilder {
    private float[] values = new float[256];
    private int size;

    void add(float value) {
        ensureCapacity(this.size + 1);
        this.values[this.size++] = value;
    }

    void addAll(float[] source) {
        ensureCapacity(Math.addExact(this.size, source.length));
        System.arraycopy(source, 0, this.values, this.size, source.length);
        this.size += source.length;
    }

    float[] toArray() {
        return this.size == this.values.length
            ? this.values
            : java.util.Arrays.copyOf(this.values, this.size);
    }

    private void ensureCapacity(int required) {
        if (required <= this.values.length) {
            return;
        }
        int capacity = this.values.length;
        while (capacity < required) {
            capacity = Math.max(required, Math.multiplyExact(capacity, 2));
        }
        this.values = java.util.Arrays.copyOf(this.values, capacity);
    }
}
