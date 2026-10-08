package com.rtest.client;

import com.rtest.client.experimental.RadianceCacheExperiment;
import com.rtest.client.experimental.RadianceCacheExperiment.Key;
import com.rtest.client.experimental.RadianceCacheExperiment.Sample;
import com.rtest.client.experimental.RadianceCacheExperiment.Query;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.lwjgl.util.shaderc.Shaderc;

/** Analytic transport contracts + fixture generator for actual Vulkan compute/readback. */
public final class RadianceCacheExperimentTest {
    private static final int GENERATION = 17;
    private static Query query(Key key, int generation, int flags) {
        return new Query(key, generation, flags, .2f, .5f, .8f, .3f, .6f, .9f);
    }
    private static Key key(int i) { return new Key(i, -i, i * 3, 0x200ffe00, 41); }
    public static void main(String[] args) throws Exception {
        transportContracts();
        var cases = smallFixture();
        int out = cases.expected()[7];
        // Valid black, normal, rejected collision, missing, stale, dynamic, rough/spec,
        // refractive, high variance, insufficient count, invalid target, invalid query.
        boolean[] expectedHits = {true, true, false, false, false, false, false, false,
            false, false, false, false, true};
        for (int i = 0; i < expectedHits.length; i++) {
            boolean hit = cases.expected()[out + i * 4 + 3] == Float.floatToRawIntBits(1);
            if (hit != expectedHits[i]) throw new AssertionError("fallback contract case " + i);
        }
        if (cases.expected()[out] != 0) throw new AssertionError("valid black estimate");
        float actual = Float.intBitsToFloat(cases.expected()[out + 4]);
        double expected = .3 * .2 * 4 / Math.PI;
        near(actual, expected, 1e-7, "beta/albedo/pi counted once");
        // At the input bound all samples occupy one slot: neither first nor second moment may overflow.
        var saturation = new ArrayList<Sample>();
        for (int i = 0; i < 32768; i++) saturation.add(new Sample(key(12), GENERATION, 7, 64, 64, 64));
        var bound = RadianceCacheExperiment.fixture(saturation, List.of(query(key(12), GENERATION, 7)), 2, GENERATION, 8, .25f);
        int c = bound.expected()[6] + RadianceCacheExperiment.slot(key(12), 2) * 8;
        if (Integer.toUnsignedLong(bound.expected()[c + 2]) != 2147483648L
            || Integer.toUnsignedLong(bound.expected()[c + 5]) != 2147483648L)
            throw new AssertionError("uint atomic sum bound");
        if (bound.expected()[bound.expected()[7] + 3] == 0) throw new AssertionError("bounded HDR target rejected");
        byte[] shader = compile();
        var analytic = analyticFixture();
        if (args.length > 0) {
            String prefix = args[0];
            int queryCount = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            var fixture = queryCount > 0 ? benchmarkFixture(queryCount) : cases;
            Files.write(Path.of(prefix + ".spv"), shader);
            dump(prefix + ".seed", fixture.seed()); dump(prefix + ".expected", fixture.expected());
            // Separate maximal hot-slot case is also GPU verifiable.
            Files.write(Path.of(prefix + "-bound.spv"), shader);
            dump(prefix + "-bound.seed", bound.seed()); dump(prefix + "-bound.expected", bound.expected());
            Files.write(Path.of(prefix + "-analytic.spv"), shader);
            dump(prefix + "-analytic.seed", analytic.seed()); dump(prefix + "-analytic.expected", analytic.expected());
            System.out.println("Radiance cache GPU fixture: slots=" + fixture.seed()[1]
                + " training=" + fixture.seed()[2] + " queries=" + fixture.seed()[3]);
        }
        System.out.println("Radiance cache: analytic tails, factorization, fallback, collisions, uint bounds and shader compile PASS");
    }

    private static RadianceCacheExperiment.Fixture smallFixture() {
        int slots = 128;
        Key zero = key(0), normal = key(1);
        // Find a distinct exact key colliding with the normal key; election must keep the first.
        int next = 2;
        while (RadianceCacheExperiment.slot(key(next), slots) != RadianceCacheExperiment.slot(normal, slots)) next++;
        Key collision = key(next);
        var keys = new ArrayList<Key>(); keys.add(zero); keys.add(normal);
        for (int candidate = next + 1; keys.size() < 8; candidate++) {
            Key k = key(candidate); boolean unused = true;
            for (Key previous : keys) unused &= RadianceCacheExperiment.slot(k, slots) != RadianceCacheExperiment.slot(previous, slots);
            if (unused) keys.add(k);
        }
        var samples = new ArrayList<Sample>();
        for (int i = 0; i < 16; i++) samples.add(new Sample(zero, GENERATION, 7, 0, 0, 0));
        for (int i = 0; i < 16; i++) samples.add(new Sample(normal, GENERATION, 7, 4, 8, 12));
        for (int i = 0; i < 16; i++) samples.add(new Sample(collision, GENERATION, 7, 63, 63, 63));
        for (int i = 0; i < 16; i++) samples.add(new Sample(keys.get(2), GENERATION, 7, i % 2 == 0 ? 0 : 64, 0, 0));
        samples.add(new Sample(keys.get(3), GENERATION, 7, 2, 2, 2));
        for (int i = 0; i < 16; i++) {
            samples.add(new Sample(keys.get(4), GENERATION, 7, Float.NaN, 1, 1));
            samples.add(new Sample(keys.get(4), GENERATION, 7, Float.POSITIVE_INFINITY, 1, 1));
            samples.add(new Sample(keys.get(4), GENERATION, 7, -1, 1, 1));
            samples.add(new Sample(keys.get(4), GENERATION, 7, 65, 1, 1));
            samples.add(new Sample(keys.get(5), GENERATION - 1, 7, 1, 1, 1));
            samples.add(new Sample(keys.get(6), GENERATION, 15, 1, 1, 1));
            samples.add(new Sample(keys.get(7), GENERATION, 7, 3.1234f, 4.4321f, 2.3456f));
        }
        var queries = List.of(query(zero, GENERATION, 7), query(normal, GENERATION, 7), query(collision, GENERATION, 7),
            query(keys.get(5), GENERATION, 7), query(normal, GENERATION - 1, 7), query(normal, GENERATION, 15),
            query(normal, GENERATION, 2), query(normal, GENERATION, 3), query(keys.get(2), GENERATION, 7),
            query(keys.get(3), GENERATION, 7), query(keys.get(4), GENERATION, 7),
            new Query(normal, GENERATION, 7, Float.NaN, .5f, .8f, 1, 1, 1), query(keys.get(7), GENERATION, 7));
        return RadianceCacheExperiment.fixture(samples, queries, slots, GENERATION, 8, .25f);
    }

    private static RadianceCacheExperiment.Fixture benchmarkFixture(int count) {
        if (count < 1 || count > 4_000_000) throw new IllegalArgumentException("query count");
        int slots = 65536;
        var samples = new ArrayList<Sample>(); var keys = new ArrayList<Key>();
        // Sparse training: 1024 occupied surface cells, 16 samples each, realistic collisions retained.
        for (int i = 0; i < 1024; i++) {
            Key k = key(i); keys.add(k);
            for (int n = 0; n < 16; n++) samples.add(new Sample(k, GENERATION, 7,
                2 + i % 13 + (n % 2) * .02f, 4 + i % 7, 1 + i % 11));
        }
        var queries = new ArrayList<Query>(count);
        for (int i = 0; i < count; i++) {
            // 3/4 hit candidates, 1/4 cold cells; distribution is synthetic, not a Minecraft capture.
            Key k = i % 4 == 0 ? key(50000 + i) : keys.get(i % 1024);
            queries.add(query(k, GENERATION, 7));
        }
        return RadianceCacheExperiment.fixture(samples, queries, slots, GENERATION, 8, .25f);
    }

    /** Independent analytic environment L(w)=a+b*cos(theta); E=pi*(a+2b/3).
     * Training and holdout queries use independent cosine-hemisphere Monte Carlo samples.
     * This measures estimator noise, not geometric leakage or a real Minecraft frame.
     */
    private static RadianceCacheExperiment.Fixture analyticFixture() {
        var random = new Random(0x51454341);
        var samples = new ArrayList<Sample>(); var queries = new ArrayList<Query>();
        double[] reference = new double[1024], uncached = new double[1024];
        for (int i = 0; i < 1024; i++) {
            Key k = key(i); double a = 1 + (i % 11) * .2, b = .5 + (i % 7) * .1;
            // Query R channel beta * albedo = .06. Other channels deliberately differ.
            reference[i] = .06 * (a + 2 * b / 3);
            uncached[i] = .06 * (a + b * Math.sqrt(random.nextDouble()));
            for (int j = 0; j < 16; j++) {
                float target = (float)(Math.PI * (a + b * Math.sqrt(random.nextDouble())));
                samples.add(new Sample(k, GENERATION, 7, target, target * .8f, target * .6f));
            }
            queries.add(query(k, GENERATION, 7));
        }
        var f = RadianceCacheExperiment.fixture(samples, queries, 65536, GENERATION, 8, .25f);
        double rawSquared = 0, cachedSquared = 0; int hits = 0;
        int out = f.expected()[7];
        for (int i = 0; i < queries.size(); i++) {
            if (f.expected()[out + i * 4 + 3] == 0) continue;
            hits++;
            double rawError = uncached[i] - reference[i];
            double cachedError = Float.intBitsToFloat(f.expected()[out + i * 4]) - reference[i];
            rawSquared += rawError * rawError; cachedSquared += cachedError * cachedError;
        }
        double rawRmse = Math.sqrt(rawSquared / hits), cachedRmse = Math.sqrt(cachedSquared / hits);
        if (hits < 950 || !(cachedRmse < rawRmse * .4)) throw new AssertionError("analytic cache estimator regression");
        System.out.printf(java.util.Locale.ROOT,
            "Analytic Lambertian environment: cells=1024 hit_cells=%d raw_1spp_rmse=%.9f cache_16spp_rmse=%.9f ratio=%.6f%n",
            hits, rawRmse, cachedRmse, cachedRmse / rawRmse);
        return f;
    }

    private static void transportContracts() {
        var random = new Random(0x52434331);
        for (int trial = 0; trial < 1000; trial++) {
            int n = 1 + random.nextInt(5);
            double[][] local = new double[n][3], scatter = new double[n][3];
            for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) {
                local[i][c] = random.nextDouble() * 16;
                scatter[i][c] = random.nextDouble() * 1.2;
            }
            double[][] tails = RadianceCacheExperiment.tails(local, scatter);
            for (int start = 0; start < n; start++) for (int c = 0; c < 3; c++) {
                double sum = 0, beta = 1;
                for (int i = start; i < n; i++) { sum += beta * local[i][c]; beta *= scatter[i][c]; }
                near(tails[start][c], sum, 1e-10, "backward path tail vs explicit transport");
            }
            double[] albedo = {.2, .5, .8};
            double[] incoming = RadianceCacheExperiment.irradiance(tails[0], albedo);
            for (int c = 0; c < 3; c++) near(incoming[c] * albedo[c] / Math.PI, tails[0][c], 1e-10, "factorization");
        }
        var a = RadianceCacheExperiment.key(-.001, 0, .499, .5, 0, 1, 0, 2);
        if (a.x() != -1 || a.z() != 0) throw new AssertionError("negative floor cell");
        if (!a.equals(RadianceCacheExperiment.key(-.001, 0, .499, .5, 0, 12, 0, 2)))
            throw new AssertionError("normal normalization");
        reject(() -> RadianceCacheExperiment.key(Double.NaN, 0, 0, .5, 0, 1, 0, 2));
        reject(() -> RadianceCacheExperiment.key(0, 0, 0, 0, 0, 1, 0, 2));
        reject(() -> RadianceCacheExperiment.irradiance(new double[]{1, 1, 1}, new double[]{0, .5, .5}));
        reject(() -> RadianceCacheExperiment.fixture(List.of(), List.of(), 3, 0, 8, .25f));
        reject(() -> RadianceCacheExperiment.fixture(java.util.Collections.nCopies(32769,
            new Sample(key(1), GENERATION, 7, 1, 1, 1)), List.of(), 2, GENERATION, 8, .25f));
    }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid input accepted");
    }
    private static void near(double a, double b, double error, String name) {
        if (!Double.isFinite(a) || Math.abs(a - b) > error) throw new AssertionError(name + ": " + a + " != " + b);
    }
    private static void dump(String path, int[] w) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(w.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(w); Files.write(Path.of(path), buffer.array());
    }
    private static byte[] compile() throws Exception {
        String source;
        try (var stream = RadianceCacheExperimentTest.class.getResourceAsStream("/rtest/shaders/experimental/radiance_cache.comp")) {
            if (stream == null) throw new AssertionError("shader resource missing");
            source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize(), result = 0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_compute_shader, "radiance_cache.comp", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer binary = Shaderc.shaderc_result_get_bytes(result);
            byte[] data = new byte[binary.remaining()]; binary.get(data); return data;
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
