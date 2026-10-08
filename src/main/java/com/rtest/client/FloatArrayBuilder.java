package com.rtest.client;

/** Primitive float accumulator used by per-draw capture paths. */
final class FloatArrayBuilder {
    private float[] values;
    private int size;
    private long copiedFloats;

    FloatArrayBuilder() { this(256); }

    FloatArrayBuilder(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive initial capacity required");
        this.values = new float[capacity];
    }

    int size() { return this.size; }
    long copiedFloats() { return this.copiedFloats; }
    void reserve(int additional) { ensureCapacity(Math.addExact(this.size, additional)); }

    void add(float value) {
        ensureCapacity(this.size + 1);
        this.values[this.size++] = value;
    }

    void addAll(float[] source) {
        ensureCapacity(Math.addExact(this.size, source.length));
        System.arraycopy(source, 0, this.values, this.size, source.length);
        this.copiedFloats += source.length;
        this.size += source.length;
    }

    float[] toArray() {
        if (this.size == this.values.length) return this.values;
        float[] result = java.util.Arrays.copyOf(this.values, this.size);
        this.copiedFloats += this.size;
        return result;
    }

    private void ensureCapacity(int required) {
        if (required <= this.values.length) {
            return;
        }
        int capacity = this.values.length;
        while (capacity < required) {
            capacity = Math.max(required, Math.multiplyExact(capacity, 2));
        }
        float[] grown = java.util.Arrays.copyOf(this.values, capacity);
        this.copiedFloats += this.values.length;
        this.values = grown;
    }
}
