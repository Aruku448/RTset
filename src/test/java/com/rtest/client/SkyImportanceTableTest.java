package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/** Normalization and estimator checks use the actual serialized sampling table. */
public final class SkyImportanceTableTest {
    public static void main(String[] args) {
        checkAlias(new double[] {0, 0, 0, 0});
        checkAlias(new double[] {0, 0, 5, 0});
        checkAlias(new double[] {1, 2, 10000, 3, 0, .00001});
        double[] weights = new double[SkyImportanceTable.COUNT];
        for (int f = 0; f < 6; f++) for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            SkyImportanceTable.accumulate(weights, f, x, y, 32, 32, 0xffffffff);
        }
        checkAlias(weights);
        for (int axis = 0; axis < 6; axis++) {
            double[] directional = SkyImportanceTable.directionalWeights(weights, axis);
            checkAlias(directional);
            double hemisphereWeight = java.util.Arrays.stream(directional).sum();
            if (Math.abs(hemisphereWeight * 4.0 / (32 * 32) - Math.PI) > .004)
                throw new AssertionError("Cube orientation/hemisphere measure: " + axis);
        }
        ByteBuffer directionalGpu = ByteBuffer.allocate(SkyImportanceTable.BYTES).order(ByteOrder.nativeOrder());
        SkyImportanceTable.writeDirectional(weights, directionalGpu);
        if (directionalGpu.position() != SkyImportanceTable.BYTES) throw new AssertionError("Directional GPU stride");
        SkyImportanceTable table = new SkyImportanceTable(weights);
        // Integrate constant radiance over the sphere using the UV->solid-angle PDF.
        ByteBuffer gpu = ByteBuffer.allocate(SkyImportanceTable.BYTES).order(ByteOrder.nativeOrder());
        table.write(gpu);
        Random rng = new Random(39);
        double integral = 0;
        int samples = 300000;
        for (int s = 0; s < samples; s++) {
            double scaled = rng.nextDouble() * weights.length;
            int bucket = (int)scaled;
            double residual = scaled - bucket, threshold = gpu.getFloat(bucket * 16);
            int index;
            if (residual < threshold) { index = bucket; residual /= threshold; }
            else {
                index = (int)gpu.getFloat(bucket * 16 + 4);
                residual = (residual - threshold) / (1 - threshold);
            }
            int cell = index % 1024;
            double u = 2.0 * ((cell % 32) + residual) / 32 - 1;
            double v = 2.0 * ((cell / 32) + rng.nextDouble()) / 32 - 1;
            double pdf = gpu.getFloat(index * 16 + 8) * 256 * Math.pow(1 + u * u + v * v, 1.5);
            if (!(pdf > 0 && Double.isFinite(pdf))) throw new AssertionError("Invalid directional PDF");
            integral += 1 / pdf;
        }
        integral /= samples;
        if (Math.abs(integral - 4 * Math.PI) > .025) throw new AssertionError("Sphere estimator: " + integral);
        // Small bright patch fixture: equal-budget discrete variance, not a GPU speed measurement.
        double[] hdr = new double[1024];
        java.util.Arrays.fill(hdr, 1);
        hdr[397] = 10000;
        SkyImportanceTable bright = new SkyImportanceTable(hdr);
        double sum = java.util.Arrays.stream(hdr).sum(), uniformSecond = 0, mixedSecond = 0;
        for (int i = 0; i < hdr.length; i++) {
            uniformSecond += hdr[i] * hdr[i] * hdr.length;
            mixedSecond += hdr[i] * hdr[i] / (.5 / hdr.length + .5 * bright.probability[i]);
        }
        double ratio = (uniformSecond - sum * sum) / (mixedSecond - sum * sum);
        if (ratio < 100) throw new AssertionError("Bright-region variance not reduced");
        String shader = RayTracingShaders.RAYGEN_SHADER;
        for (String token : new String[] {"binding = 38", "skyNeePdf(previousSurfaceNormal, rayDirection)",
                "skyNeePdf(normal, sampledSkyDirection)", "skyCosine / skyPdf", "if (mixture <= 0.0) return cosinePdf;"}) {
            if (!shader.contains(token)) throw new AssertionError("Missing sky sampling wiring: " + token);
        }
        System.out.printf("Sky importance: alias normalization passed; sphere integral %.6f (4pi=%.6f); synthetic HDR variance ratio %.1fx (not GPU).%n",
            integral, 4 * Math.PI, ratio);
    }

    private static void checkAlias(double[] weights) {
        SkyImportanceTable t = new SkyImportanceTable(weights);
        ByteBuffer data = ByteBuffer.allocate(weights.length * 16).order(ByteOrder.nativeOrder());
        t.write(data);
        double[] actual = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            double q = data.getFloat(i * 16);
            int alias = (int)data.getFloat(i * 16 + 4);
            if (!(q >= 0 && q <= 1) || alias < 0 || alias >= weights.length) throw new AssertionError("Alias bounds");
            actual[i] += q / weights.length;
            actual[alias] += (1 - q) / weights.length;
        }
        double total = 0;
        for (int i = 0; i < weights.length; i++) {
            double pdf = data.getFloat(i * 16 + 8);
            if (Math.abs(actual[i] - pdf) > 1e-7) throw new AssertionError("Sample/PDF mismatch");
            total += pdf;
        }
        if (Math.abs(total - 1) > 1e-7) throw new AssertionError("Probability normalization");
    }
}
