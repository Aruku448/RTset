package com.rtest.client;

import com.rtest.client.RayTracingScene.SceneGeometry.SectionGeometry;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Deterministic packed-light-tree differential fixtures and an opt-in CPU kernel benchmark. */
public final class TerrainBuildMathTest {
    private static volatile int sink;
    private static final String[] EXPECTED = {
        "d8d4ee989efccc6c509487a9170ecfc208ff9ae9315a10f9c6ce00a5594066a4",
        "3c1e233093de2b05bc8a1c5c0d7039ddfa765fb86a8c6dd01885bd7e5294b369",
        "f99f0ced37da1c8fe464a1fe9acc43c44f5168173f7b1881a20c45ccf3f00db1"
    };

    public static void main(String[] args) throws Exception {
        boolean record = args.length != 0 && args[0].equals("record");
        boolean bench = args.length != 0 && args[0].startsWith("bench");
        if (bench) {
            double first = benchmark(512, 128, 71);
            double second = benchmark(9000, 8, 73);
            if (args[0].equals("bench-gate") && (first > 50 || second > 50)) {
                throw new AssertionError("synthetic light-tree kernel exceeded diagnostic 50 ms budget");
            }
            return;
        }
        for (int i = 0; i < 3; i++) {
            List<SectionGeometry> input = fixture(16 + i * 16, 32, 17 + i);
            String hash = sha(RayTracingLightTree.build(input, 0, 0, 0).words());
            if (record) System.out.println("GOLDEN " + i + " " + hash);
            else if (!EXPECTED[i].equals(hash)) throw new AssertionError("packed light tree changed: fixture " + i + " " + hash);
        }
        if (!record) {
            assertFloatKeyOrder();
            System.out.println("Terrain math packed-word differential passed (not GPU or live terrain timing)");
        }
    }

    private static void assertFloatKeyOrder() throws Exception {
        var key = RayTracingLightTree.class.getDeclaredMethod("floatSortKey", float.class);
        key.setAccessible(true);
        Float[] expected = new Float[8192];
        int[] specials = {0, 0x80000000, 0x7f800000, 0xff800000,
            0x7fc00001, 0xffc00002, 0x7f800001, 1, 0x80000001};
        Random random = new Random(91);
        for (int i = 0; i < expected.length; i++) {
            expected[i] = Float.intBitsToFloat(i < specials.length ? specials[i] : random.nextInt());
        }
        Float[] actual = expected.clone();
        // Precompute keys so the comparator itself does no reflection.
        java.util.IdentityHashMap<Float, Integer> keys = new java.util.IdentityHashMap<>();
        for (Float value : actual) keys.put(value, (int) key.invoke(null, value.floatValue()));
        Arrays.sort(expected, (a, b) -> Double.compare(a.doubleValue(), b.doubleValue()));
        Arrays.sort(actual, (a, b) -> Integer.compareUnsigned(keys.get(a), keys.get(b)));
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) throw new AssertionError("float sort order or stable NaN tie changed at " + i);
        }
    }

    static List<SectionGeometry> fixture(int sectionCount, int triangles, long seed) throws Exception {
        Constructor<SectionGeometry> constructor = SectionGeometry.class.getDeclaredConstructor(
            int.class, int.class, int.class, float[].class, float[].class);
        constructor.setAccessible(true);
        Random random = new Random(seed);
        List<SectionGeometry> result = new ArrayList<>(sectionCount);
        for (int section = 0; section < sectionCount; section++) {
            float[] vertices = new float[triangles * 9];
            float[] materials = new float[triangles * 28];
            for (int t = 0; t < triangles; t++) {
                int v = t * 9, m = t * 28;
                // Many identical centers exercise stable tie ordering, interspersed with 3D
                // random faces. All triangle data is constructed before any timed operation.
                float x = t % 3 == 0 ? 1 : random.nextInt(256) / 16.0F;
                float y = t % 3 == 0 ? 1 : random.nextInt(256) / 16.0F;
                float z = t % 3 == 0 ? 1 : random.nextInt(256) / 16.0F;
                for (int p = 0; p < 3; p++) {
                    vertices[v + p * 3] = x;
                    vertices[v + p * 3 + 1] = y;
                    vertices[v + p * 3 + 2] = z;
                }
                int normalAxis = t % 3;
                int firstAxis = (normalAxis + 1) % 3, secondAxis = (normalAxis + 2) % 3;
                vertices[v + 3 + firstAxis] += 0.5F;
                vertices[v + 6 + secondAxis] += 0.5F;
                materials[m] = 0.1F + random.nextFloat() * 0.9F;
                materials[m + 1] = 0.1F + random.nextFloat() * 0.9F;
                materials[m + 2] = 0.1F + random.nextFloat() * 0.9F;
                materials[m + 3] = 1;
                materials[m + 4 + normalAxis] = 1;
                materials[m + 15] = t % 2;
                materials[m + 22] = 0.1F + random.nextFloat() * 2;
                materials[m + 27] = 1;
            }
            result.add(constructor.newInstance((section % 32 - 16) * 16,
                (section / 1024 - 4) * 16, (section / 32 % 32 - 16) * 16, vertices, materials));
        }
        return result;
    }

    private static double benchmark(int sections, int triangles, long seed) throws Exception {
        List<SectionGeometry> input = fixture(sections, triangles, seed);
        RayTracingMaterialBuffer.Layout layout = RayTracingMaterialBuffer.Layout.compact(input);
        for (int warmup = 0; warmup < 5; warmup++) consume(RayTracingLightTree.build(input, 0, 0, 0, layout));
        long[] times = new long[9];
        RayTracingLightTree.Data data = null;
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            data = RayTracingLightTree.build(input, 0, 0, 0, layout);
            times[i] = System.nanoTime() - start;
            consume(data);
        }
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT,
            "MATH_BENCH sections=%d emitters=%d median_ms=%.3f min_ms=%.3f max_ms=%.3f sha256=%s%n",
            sections, data.emitterCount(), times[4] / 1e6, times[0] / 1e6, times[8] / 1e6, sha(data.words()));
        return times[4] / 1e6;
    }

    private static void consume(RayTracingLightTree.Data data) {
        sink = data.words()[0] ^ data.words()[data.words().length - 1];
    }

    private static String sha(int[] words) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[4096];
        int cursor = 0;
        for (int word : words) {
            for (int shift = 0; shift < 32; shift += 8) chunk[cursor++] = (byte)(word >>> shift);
            if (cursor == chunk.length) { digest.update(chunk); cursor = 0; }
        }
        digest.update(chunk, 0, cursor);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
