package com.rtest.client;

import java.nio.ByteBuffer;

/** Section 39.4 ribbon construction: x is column, y conditional CDF, z marginal CDF. */
final class SkyCdfGeometry {
    static final int ROWS = 6 * SkyImportanceTable.SIDE;
    static final int METADATA_BYTES = SkyImportanceTable.COUNT * 6 * 16;
    final float[] bounds = new float[SkyImportanceTable.COUNT * 6 * 4];
    final float[][] vertices = new float[6][];
    final SkyImportanceTable[] tables = new SkyImportanceTable[6];

    SkyCdfGeometry(double[] weights) {
        int side = SkyImportanceTable.SIDE, count = SkyImportanceTable.COUNT;
        for (int axis = 0; axis < 6; axis++) {
            double[] w = SkyImportanceTable.directionalWeights(weights, axis);
            double total = java.util.Arrays.stream(w).sum();
            if (total == 0) { java.util.Arrays.fill(w, 1); total = count; }
            double[] effective = new double[count];
            float[] v = vertices[axis] = new float[count * 18];
            double rowCumulative = 0;
            for (int row = 0; row < ROWS; row++) {
                double rowWeight = 0;
                for (int col = 0; col < side; col++) rowWeight += w[row * side + col];
                float z0 = (float)(rowCumulative / total);
                rowCumulative += rowWeight;
                float z1 = row == ROWS - 1 ? 1 : (float)(rowCumulative / total);
                double colCumulative = 0;
                for (int col = 0; col < side; col++) {
                    int cell = row * side + col, index = axis * count + cell;
                    float y0 = rowWeight > 0 ? (float)(colCumulative / rowWeight) : (float)col / side;
                    colCumulative += w[cell];
                    float y1 = col == side - 1 ? 1 : rowWeight > 0
                        ? (float)(colCumulative / rowWeight) : (float)(col + 1) / side;
                    int b = index * 4;
                    bounds[b] = y0; bounds[b + 1] = y1; bounds[b + 2] = z0; bounds[b + 3] = z1;
                    effective[cell] = ((double)y1 - y0) * ((double)z1 - z0);
                    float x0 = (float)col / side, x1 = (float)(col + 1) / side;
                    int p = cell * 18;
                    float[] quad = {x0,y0,z0, x1,y1,z0, x1,y1,z1,
                                   x0,y0,z0, x1,y1,z1, x0,y0,z1};
                    System.arraycopy(quad, 0, v, p, 18);
                }
            }
            tables[axis] = new SkyImportanceTable(effective);
        }
    }

    void writeBounds(ByteBuffer target) { for (float f : bounds) target.putFloat(f); }
    void writeTables(ByteBuffer target) { for (SkyImportanceTable table : tables) table.write(target); }

    int invert(int axis, double conditional, double marginal) {
        int side = SkyImportanceTable.SIDE, offset = axis * SkyImportanceTable.COUNT;
        int lo = 0, hi = ROWS - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (marginal < bounds[(offset + mid * side) * 4 + 3]) hi = mid; else lo = mid + 1;
        }
        int row = lo;
        lo = 0; hi = side - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (conditional < bounds[(offset + row * side + mid) * 4 + 1]) hi = mid; else lo = mid + 1;
        }
        return offset + row * side + lo;
    }
}
