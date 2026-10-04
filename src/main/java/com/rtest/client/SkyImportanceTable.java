package com.rtest.client;

import java.nio.ByteBuffer;

/** Compact luminance times solid-angle proposal; sampling PDF is independent of approximation error. */
final class SkyImportanceTable {
    static final int SIDE = 32;
    static final int COUNT = 6 * SIDE * SIDE;
    static final int BYTES = COUNT * 16 * 6;
    private static final double[] LINEAR = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            LINEAR[i] = s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4);
        }
    }
    final float[] threshold;
    final int[] alias;
    final float[] probability;

    SkyImportanceTable(double[] weights) {
        if (weights.length == 0) throw new IllegalArgumentException("Empty distribution");
        int n = weights.length;
        threshold = new float[n];
        alias = new int[n];
        probability = new float[n];
        double sum = 0;
        for (double w : weights) {
            if (!Double.isFinite(w) || w < 0) throw new IllegalArgumentException("Invalid weight");
            sum += w;
        }
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Invalid total");
        double[] scaled = new double[n];
        int[] small = new int[n], large = new int[n];
        int ns = 0, nl = 0;
        for (int i = 0; i < n; i++) {
            double p = sum > 0 ? weights[i] / sum : 1.0 / n;
            probability[i] = (float)p;
            scaled[i] = p * n;
            if (scaled[i] < 1) small[ns++] = i; else large[nl++] = i;
        }
        while (ns > 0 && nl > 0) {
            int s = small[--ns], l = large[--nl];
            threshold[s] = (float)scaled[s];
            alias[s] = l;
            scaled[l] = scaled[l] + scaled[s] - 1;
            if (scaled[l] < 1) small[ns++] = l; else large[nl++] = l;
        }
        while (nl > 0) { int i = large[--nl]; threshold[i] = 1; alias[i] = i; }
        while (ns > 0) { int i = small[--ns]; threshold[i] = 1; alias[i] = i; }
    }

    static void accumulate(double[] weights, int face, int x, int y, int width, int height, int argb) {
        int cx = Math.min(x * SIDE / width, SIDE - 1), cy = Math.min(y * SIDE / height, SIDE - 1);
        double u = 2.0 * (x + .5) / width - 1, v = 2.0 * (y + .5) / height - 1;
        double luminance = .2126 * LINEAR[(argb >>> 16) & 255]
            + .7152 * LINEAR[(argb >>> 8) & 255] + .0722 * LINEAR[argb & 255];
        double q = 1 + u * u + v * v;
        weights[(face * SIDE + cy) * SIDE + cx] += luminance / (q * Math.sqrt(q));
    }

    void write(ByteBuffer buffer) {
        for (int i = 0; i < threshold.length; i++) {
            buffer.putFloat(threshold[i]).putFloat(alias[i]).putFloat(probability[i]).putFloat(0);
        }
    }

    static double[] directionalWeights(double[] weights, int axis) {
        double[] result = new double[COUNT];
        for (int i = 0; i < COUNT; i++) {
            int face = i / (SIDE * SIDE), local = i % (SIDE * SIDE);
            double u = 2.0 * ((local % SIDE) + .5) / SIDE - 1;
            double v = 2.0 * ((local / SIDE) + .5) / SIDE - 1;
            double[] d = switch (face) {
                case 0 -> new double[] {1, -v, -u};
                case 1 -> new double[] {-1, -v, u};
                case 2 -> new double[] {u, 1, v};
                case 3 -> new double[] {u, -1, -v};
                case 4 -> new double[] {u, -v, 1};
                default -> new double[] {-u, -v, -1};
            };
            double cosine = d[axis / 2] * (axis % 2 == 0 ? 1 : -1) / Math.sqrt(1 + u * u + v * v);
            result[i] = weights[i] * Math.max(cosine, 0);
        }
        return result;
    }

    static void writeDirectional(double[] weights, ByteBuffer buffer) {
        for (int axis = 0; axis < 6; axis++) new SkyImportanceTable(directionalWeights(weights, axis)).write(buffer);
    }
}
