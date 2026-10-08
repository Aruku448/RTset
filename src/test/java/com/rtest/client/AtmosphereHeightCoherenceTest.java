package com.rtest.client;

import java.util.Arrays;
import java.util.Random;

/** Differential check against a sorted-array interval oracle, including reversals and jumps. */
public final class AtmosphereHeightCoherenceTest {
    private record Interval(int lo, int hi, float fraction) { }
    public static void main(String[] args) {
        Random random = new Random(7102026);
        long checked = 0;
        for (int n : new int[] {2, 3, 8, 64, 256}) {
            float[] table = new float[n];
            for (int i = 1; i < n; i++) table[i] = table[i - 1] + .01f + random.nextFloat() * 2;
            Interval previous = new Interval(0, 1, 0);
            // Repeated endpoints test exact table entries and neighboring floats.
            for (int repetition = 0; repetition < 3; repetition++) {
                for (int i = 0; i < n; i++) {
                    int index = repetition % 2 == 0 ? i : n - 1 - i;
                    for (float h : new float[] {Math.nextDown(table[index]), table[index], Math.nextUp(table[index])}) {
                        previous = compare(table, h, previous);
                        checked++;
                    }
                }
            }
            float h = 0;
            for (int step = 0; step < 100000; step++) {
                h = step % 13 == 0 ? (random.nextFloat() * 1.4f - .2f) * table[n - 1]
                    : h + (random.nextFloat() - .5f) * .05f;
                previous = compare(table, h, previous);
                checked++;
            }
            compare(table, -Float.MAX_VALUE, previous);
            compare(table, Float.MAX_VALUE, previous);
        }
        String raygen = RayTracingShaders.RAYGEN_SHADER;
        require(raygen.contains("physicalAtmCoherentIndirectHeight(hp, previousHeight)"), "real loop uses hint");
        require(raygen.contains("previousHeight = indirectHeight;"), "loop advances hint");
        require(raygen.contains("return physicalAtmIndirectHeight(h);"), "large jumps retain binary fallback");
        require(raygen.contains("directCosine == 0.0 && foliageResponse == 0.0"), "zero-response guard");
        int local = raygen.indexOf("vec3 estimatePhysicalVolumeLighting(");
        if (local < 0) local = raygen.indexOf("float eyeHeightKm = eyeRadiusKm - PATM_BOTTOM_KM;");
        int reject = raygen.indexOf("if (any(greaterThan(sourceRadiance, vec3(0.0))))", local);
        int medium = raygen.indexOf("PhysicalAtmMedium medium = physicalAtmMedium(volumeHeightKm);", local);
        int view = raygen.indexOf("vec4 viewT = physicalAtmLocalSpectralTransmittance", local);
        require(reject >= 0 && medium > reject && view > reject, "rejected local light skips medium work");
        System.out.println("Atmosphere coherent-height oracle passed " + checked + " samples (not GPU validation)");
    }
    private static Interval compare(float[] table, float h, Interval previous) {
        Interval actual = coherent(table, h, previous);
        int found = Arrays.binarySearch(table, h);
        int lo = found >= 0 ? found : -found - 2;
        lo = Math.max(0, Math.min(lo, table.length - 2));
        Interval reference = new Interval(lo, lo + 1, fraction(table, h, lo));
        require(actual.equals(reference), "height bracket/interpolation changed: " + h + ": " + actual + " vs " + reference);
        return actual;
    }
    private static float fraction(float[] table, float h, int lo) {
        return Math.max(0, Math.min(1, (h - table[lo]) / (table[lo + 1] - table[lo])));
    }
    private static Interval coherent(float[] table, float h, Interval previous) {
        int lo = previous.lo, hi = previous.hi;
        float h0 = table[lo], h1 = table[hi];
        if (h < h0 && lo > 0) { hi = lo; lo--; h1 = h0; h0 = table[lo]; }
        else if (h >= h1 && hi < table.length - 1) { lo = hi; hi++; h0 = h1; h1 = table[hi]; }
        boolean lowerCovered = h >= h0 || lo == 0;
        boolean upperCovered = h < h1 || hi == table.length - 1;
        if (!(lowerCovered && upperCovered)) {
            lo = 0; hi = table.length - 1;
            while (hi - lo > 1) {
                int mid = (lo + hi) / 2;
                if (table[mid] <= h) lo = mid; else hi = mid;
            }
        }
        return new Interval(lo, hi, fraction(table, h, lo));
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
