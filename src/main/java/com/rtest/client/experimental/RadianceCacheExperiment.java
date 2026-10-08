package com.rtest.client.experimental;

import java.util.List;

/** CPU oracle and input ABI for an isolated Vulkan diffuse-irradiance cache experiment.
 * No production rendering call sites. Positions must use a stable caller-owned spatial anchor.
 * Generation represents scene/material/light validity, not render-frame number.
 */
public final class RadianceCacheExperiment {
    public static final int STATIC_OPAQUE_DIFFUSE = 7;
    public static final int MAX_TRAINING_SAMPLES = 32768;
    public record Key(int x, int y, int z, int normal, int material) { }
    public record Sample(Key key, int generation, int flags, float r, float g, float b) { }
    public record Query(Key key, int generation, int flags, float ar, float ag, float ab,
                        float br, float bg, float bb) { }
    public record Fixture(int[] seed, int[] expected) { }

    private RadianceCacheExperiment() { }

    public static Key key(double x, double y, double z, double cellSize,
                          double nx, double ny, double nz, int material) {
        if (!Double.isFinite(cellSize) || cellSize <= 0) throw new IllegalArgumentException("cell size");
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!Double.isFinite(length) || length < 1e-10) throw new IllegalArgumentException("normal");
        int normal = normalComponent(nx / length) | normalComponent(ny / length) << 10
            | normalComponent(nz / length) << 20;
        return new Key(cell(x, cellSize), cell(y, cellSize), cell(z, cellSize), normal, material);
    }

    private static int normalComponent(double x) { return (int)Math.floor((x * .5 + .5) * 1023 + .5); }
    private static int cell(double x, double size) {
        double value = Math.floor(x / size);
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("cell coordinate overflow");
        return (int)value;
    }

    public static int slot(Key k, int slots) {
        if (slots <= 0 || (slots & (slots - 1)) != 0) throw new IllegalArgumentException("slots");
        int h = 0x811c9dc5;
        for (int v : new int[]{k.x, k.y, k.z, k.normal, k.material}) h = (h ^ v) * 16777619;
        h ^= h >>> 16; h *= 0x7feb352d; h ^= h >>> 15; h *= 0x846ca68b; h ^= h >>> 16;
        return h & (slots - 1);
    }

    /** Per-vertex remaining radiance B_i = L_i + W_i B_(i+1), without camera throughput.
     * The caller supplies matching diffuse-only local contributions and continuation weights.
     * It must not feed a full PBR closure, volume term or already camera-weighted pixel here.
     */
    public static double[][] tails(double[][] local, double[][] scatter) {
        if (local.length != scatter.length) throw new IllegalArgumentException("path length");
        double[][] result = new double[local.length][3];
        for (int i = local.length - 1; i >= 0; i--) {
            if (local[i].length != 3 || scatter[i].length != 3) throw new IllegalArgumentException("RGB");
            for (int c = 0; c < 3; c++) {
                if (!Double.isFinite(local[i][c]) || local[i][c] < 0
                    || !Double.isFinite(scatter[i][c]) || scatter[i][c] < 0)
                    throw new IllegalArgumentException("nonfinite/negative transport");
                result[i][c] = local[i][c] + (i + 1 < local.length ? scatter[i][c] * result[i + 1][c] : 0);
                if (!Double.isFinite(result[i][c])) throw new IllegalArgumentException("tail overflow");
            }
        }
        return result;
    }

    /** Lambertian outgoing radiance -> irradiance; zero albedo cannot identify incident energy. */
    public static double[] irradiance(double[] outgoing, double[] albedo) {
        if (outgoing.length != 3 || albedo.length != 3) throw new IllegalArgumentException("RGB");
        double[] result = new double[3];
        for (int c = 0; c < 3; c++) {
            if (!Double.isFinite(outgoing[c]) || outgoing[c] < 0 || !Double.isFinite(albedo[c])
                || albedo[c] <= 1e-4 || albedo[c] > 1) throw new IllegalArgumentException("unidentifiable irradiance");
            result[c] = outgoing[c] * Math.PI / albedo[c];
            if (!Double.isFinite(result[c])) throw new IllegalArgumentException("irradiance overflow");
        }
        return result;
    }

    public static Fixture fixture(List<Sample> samples, List<Query> queries, int slots,
                                   int generation, int minimumSamples, float varianceLimit) {
        slot(new Key(0, 0, 0, 0, 0), slots);
        if (samples.size() > MAX_TRAINING_SAMPLES || minimumSamples < 1
            || !Float.isFinite(varianceLimit) || varianceLimit < 0) throw new IllegalArgumentException("limits");
        int train = 16, query = Math.addExact(train, Math.multiplyExact(samples.size(), 16));
        int cache = Math.addExact(query, Math.multiplyExact(queries.size(), 16));
        int output = Math.addExact(cache, Math.multiplyExact(slots, 8));
        int[] w = new int[Math.addExact(output, Math.multiplyExact(queries.size(), 4))];
        w[0] = 0x52434331; w[1] = slots; w[2] = samples.size(); w[3] = queries.size();
        w[4] = train; w[5] = query; w[6] = cache; w[7] = output; w[8] = generation;
        w[9] = minimumSamples; w[10] = Float.floatToRawIntBits(varianceLimit);
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i); int p = train + i * 16;
            input(w, p, s.key, s.generation, s.flags);
            rgb(w, p + 7, s.r, s.g, s.b);
        }
        for (int i = 0; i < queries.size(); i++) {
            Query q = queries.get(i); int p = query + i * 16;
            input(w, p, q.key, q.generation, q.flags);
            rgb(w, p + 7, q.ar, q.ag, q.ab); rgb(w, p + 10, q.br, q.bg, q.bb);
        }
        int[] expected = w.clone();
        for (int i = 0; i < slots; i++) expected[cache + i * 8] = -1;
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (!valid(s, generation)) continue;
            int c = cache + slot(s.key, slots) * 8;
            if (expected[c] == -1) expected[c] = i;
        }
        for (Sample s : samples) {
            if (!valid(s, generation)) continue;
            int c = cache + slot(s.key, slots) * 8, owner = expected[c];
            if (!s.key.equals(samples.get(owner).key)) continue;
            expected[c + 1]++;
            float[] values = {s.r, s.g, s.b};
            for (int j = 0; j < 3; j++) {
                expected[c + 2 + j] += (int)Math.floor(values[j] * 1024f + .5f);
                expected[c + 5 + j] += (int)Math.floor(values[j] * values[j] * 16f + .5f);
            }
        }
        for (int i = 0; i < queries.size(); i++) {
            Query q = queries.get(i); int c = cache + slot(q.key, slots) * 8;
            int owner = expected[c], n = expected[c + 1];
            if (q.generation != generation || q.flags != STATIC_OPAQUE_DIFFUSE || owner == -1
                || n < minimumSamples || !q.key.equals(samples.get(owner).key)) continue;
            float[] albedo = {q.ar, q.ag, q.ab}, beta = {q.br, q.bg, q.bb}, mean = new float[3];
            boolean valid = true;
            for (int j = 0; j < 3; j++) {
                mean[j] = Integer.toUnsignedLong(expected[c + 2 + j]) / (1024f * n);
                float moment = Integer.toUnsignedLong(expected[c + 5 + j]) / (16f * n);
                float uncertainty = .03125f + (2 * mean[j] + .00048828125f) * .00048828125f;
                float variance = Math.max(moment - mean[j] * mean[j], 0) + uncertainty;
                valid &= variance <= varianceLimit * (mean[j] * mean[j] + 1)
                    && Float.isFinite(albedo[j]) && albedo[j] >= 0 && albedo[j] <= 1
                    && Float.isFinite(beta[j]) && beta[j] >= 0 && beta[j] <= 65504;
            }
            if (!valid) continue;
            for (int j = 0; j < 3; j++) expected[output + i * 4 + j] =
                Float.floatToRawIntBits(beta[j] * albedo[j] * mean[j] * (1f / (float)Math.PI));
            expected[output + i * 4 + 3] = Float.floatToRawIntBits(1);
        }
        return new Fixture(w, expected);
    }

    private static boolean valid(Sample s, int generation) {
        return s.generation == generation && s.flags == STATIC_OPAQUE_DIFFUSE
            && Float.isFinite(s.r) && Float.isFinite(s.g) && Float.isFinite(s.b)
            && s.r >= 0 && s.g >= 0 && s.b >= 0 && s.r <= 64 && s.g <= 64 && s.b <= 64;
    }
    private static void input(int[] w, int p, Key k, int generation, int flags) {
        w[p] = k.x; w[p + 1] = k.y; w[p + 2] = k.z; w[p + 3] = k.normal;
        w[p + 4] = k.material; w[p + 5] = generation; w[p + 6] = flags;
    }
    private static void rgb(int[] w, int p, float r, float g, float b) {
        w[p] = Float.floatToRawIntBits(r); w[p + 1] = Float.floatToRawIntBits(g);
        w[p + 2] = Float.floatToRawIntBits(b);
    }
}
