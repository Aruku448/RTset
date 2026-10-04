package com.rtest.client;

import java.util.Random;

/** CPU ray intersection against the actual uploaded triangles, compared to CDF inversion. */
public final class SkyCdfGeometryTest {
    public static void main(String[] args) {
        double[] weights = new double[SkyImportanceTable.COUNT];
        java.util.Arrays.fill(weights, 1);
        weights[397] = 10000;
        check(new SkyCdfGeometry(weights));
        java.util.Arrays.fill(weights, 0);
        check(new SkyCdfGeometry(weights));
        weights[397] = 1;
        check(new SkyCdfGeometry(weights));
        String shader = RayTracingShaders.RAYGEN_SHADER;
        if (!shader.contains("traceRayEXT(skyCdfAS") || !shader.contains("skyCdfRecover(offset, xi)"))
            throw new AssertionError("Hardware CDF path missing");
        System.out.println("Sky CDF: actual ribbon triangle intersections match inverse CDF, PDF mass and zero/bright distributions (CPU, not GPU).");
    }

    private static void check(SkyCdfGeometry geometry) {
        Random rng = new Random(3939);
        int count = SkyImportanceTable.COUNT, side = SkyImportanceTable.SIDE;
        for (int axis = 0; axis < 6; axis++) {
            double total = 0;
            for (int cell = 0; cell < count; cell++) {
                int b = (axis * count + cell) * 4;
                float y0 = geometry.bounds[b], y1 = geometry.bounds[b + 1];
                float z0 = geometry.bounds[b + 2], z1 = geometry.bounds[b + 3];
                double mass = (double)(y1 - y0) * (z1 - z0);
                if (!(y0 >= 0 && y1 >= y0 && y1 <= 1 && z0 >= 0 && z1 >= z0 && z1 <= 1))
                    throw new AssertionError("CDF bounds");
                if (Math.abs(mass - geometry.tables[axis].probability[cell]) > 1e-7)
                    throw new AssertionError("Geometry/alias PDF mismatch");
                total += mass;
            }
            if (Math.abs(total - 1) > 1e-6) throw new AssertionError("Geometry total mass: " + total);
            for (int s = 0; s < 100; s++) {
                double y = rng.nextDouble(), z = rng.nextDouble();
                int expected = geometry.invert(axis, y, z) - axis * count;
                double best = Double.POSITIVE_INFINITY;
                int found = -1;
                float[] v = geometry.vertices[axis];
                for (int p = 0; p < v.length; p += 9) {
                    double ay = v[p+1], az = v[p+2];
                    double by = v[p+4]-ay, bz = v[p+5]-az;
                    double cy = v[p+7]-ay, cz = v[p+8]-az;
                    double det = by*cz-bz*cy;
                    if (Math.abs(det) < 1e-30) continue;
                    double b = ((y-ay)*cz-(z-az)*cy)/det;
                    double c = (by*(z-az)-bz*(y-ay))/det;
                    if (b < 0 || c < 0 || b+c > 1) continue;
                    double x = v[p]+b*(v[p+3]-v[p])+c*(v[p+6]-v[p]);
                    if (x < best) { best=x; found=p/18; }
                }
                if (found != expected) throw new AssertionError("Ribbon hit mismatch: " + found + " / " + expected);
                int b = (axis * count + expected) * 4;
                double jitter = (y - geometry.bounds[b]) / (geometry.bounds[b+1] - geometry.bounds[b]);
                double expectedX = ((expected % side) + jitter) / side;
                if (Math.abs(best-expectedX) > 1e-6) throw new AssertionError("Ray distance inversion mismatch");
            }
        }
    }
}
