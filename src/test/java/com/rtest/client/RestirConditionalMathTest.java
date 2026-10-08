package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.util.shaderc.Shaderc;

/** Exhaustive finite-domain UCW oracle and compilation of the actual integrated shader. */
public final class RestirConditionalMathTest {
    private record Outcome(int sample, double weight, double probability) {}
    private RestirConditionalMathTest() {}

    public static void main(String[] args) throws Exception {
        layoutAndHistory();
        conditionalReplay();
        supportAndVisibility();
        areaToSolidAngle();
        prefixResumeState();
        shaderVariants(args.length == 0 ? null : Path.of(args[0]));
        System.out.println("ReSTIR: exact conditional UCW, support, visibility, layout/history and integrated shader variants passed (not game GPU validation)");
    }

    // Every live local across the primary/suffix seam must survive continuation. This catches
    // omitted state (ray direction, medium, MIS, AOVs, dynamic residuals), not just shader syntax.
    private static void prefixResumeState() {
        String shader = RayTracingShaders.RAYGEN_SHADER;
        int start = shader.indexOf("void runRestirPath()");
        int resume = shader.indexOf("int firstBounce = 0;", start);
        int loop = shader.indexOf("for (int bounce = firstBounce;", resume);
        int sample = shader.indexOf("sampleBase.vertexIndex = uint(bounce);", loop);
        String declarations = shader.substring(start, resume);
        String restore = shader.substring(resume, loop);
        String save = shader.substring(loop, sample);
        var matcher = java.util.regex.Pattern.compile(
            "(?m)^\\s*(bool|vec[234]|uvec[234]|float|int|uint|AreaLightSample|PrimeSampleBase) (\\w+)(?:\\s*=|;)").matcher(declarations);
        var recomputed = java.util.Set.of("pixelIndex", "jitteredPixel", "pixel", "ndc",
            "cameraInWater", "giBounces", "maxPathSegments", "sampleBase");
        int fields = 0;
        while (matcher.find()) {
            String name = matcher.group(2);
            if (recomputed.contains(name)) continue;
            if (!save.contains("restirPrefixState." + name + " = " + name + ";")
                || !restore.contains(name + " = restirPrefixState." + name + ";")) {
                throw new AssertionError("Prefix continuation lost live state: " + name);
            }
            fields++;
        }
        if (fields < 40) throw new AssertionError("Prefix state parser missed the integrator seam");
        if (!save.contains("restirProposalIndex == 0u && bounce == 1")
            || !restore.contains("restirProposalIndex > 0u && restirPrefixStateReady")) {
            throw new AssertionError("Borrowed suffix must resume only a canonical prefix");
        }
        // The suffix tape is reconstructed from each proposal, rather than copied from canonical.
        if (!shader.contains("sampleBase.sampleEpoch = restirSuffixSeed;")) {
            throw new AssertionError("Borrowed proposal lost its independent suffix tape");
        }
    }

    private static List<Outcome> canonical(int size) {
        List<Outcome> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add(new Outcome(i, 1, 1.0 / size));
        return result;
    }

    // Identity random-tape shifts with full support: uniform MIS, Equations 13-15.
    private static List<Outcome> merge(List<Outcome> a, List<Outcome> b, double[] target) {
        List<Outcome> result = new ArrayList<>();
        for (Outcome x : a) for (Outcome y : b) {
            double wx = .5 * target[x.sample] * x.weight;
            double wy = .5 * target[y.sample] * y.weight;
            double sum = wx + wy;
            double probability = x.probability * y.probability;
            if (wx > 0) result.add(new Outcome(x.sample, sum / target[x.sample], probability * wx / sum));
            if (wy > 0) result.add(new Outcome(y.sample, sum / target[y.sample], probability * wy / sum));
        }
        return result;
    }

    private static double integral(List<Outcome> distribution, double[] function) {
        double value = 0;
        for (Outcome x : distribution) value += x.probability * x.weight * function[x.sample];
        return value;
    }

    private static void conditionalReplay() {
        List<Outcome> reservoir = canonical(3);
        double[][] targets = {{1e-6, 9, .2}, {4, 1e-6, 3}, {.01, 10, 1e-6}, {9, .3, 2}};
        for (double[] target : targets) {
            reservoir = merge(canonical(3), reservoir, target);
            // Strongly changing supporting-prefix targets must not change the UCW measure.
            for (int sample = 0; sample < 3; sample++) {
                double[] delta = new double[3]; delta[sample] = 1;
                near(integral(reservoir, delta), 1.0 / 3, "conditional UCW support mass");
            }
        }
        double[][] contributions = {{3, 0, 7}, {0, 11, .2}};
        double[] prefixProbability = {.8, .2};
        double joint = 0, reference = 0;
        for (int prefix = 0; prefix < 2; prefix++) {
            // Prefix throughput/UCW is determined before suffix resampling; no shared draws.
            double prefixUcw = .5 / prefixProbability[prefix];
            joint += prefixProbability[prefix] * prefixUcw * integral(reservoir, contributions[prefix]);
            for (double f : contributions[prefix]) reference += f / 6;
        }
        near(joint, reference, "joint prefix/suffix integral");
        near(integral(reservoir, new double[] {-3, 4, -8}), -7.0 / 3, "signed dynamic residual");
        double[] f = {7, 1, 0};
        double finalGather = .5 * integral(canonical(3), f) + .5 * integral(reservoir, f);
        near(finalGather, 8.0 / 3, "full-canonical final gather");
    }

    private static void supportAndVisibility() {
        List<Outcome> missing = merge(canonical(3), canonical(3), new double[] {0, 1, 1});
        if (!(integral(missing, new double[] {1, 0, 0}) < 1.0 / 3))
            throw new AssertionError("zero target must expose loss of source support");
        List<Outcome> full = merge(canonical(3), canonical(3), new double[] {1e-6, 1, 1});
        near(integral(full, new double[] {1, 0, 0}), 1.0 / 3, "positive target preserves black/backface reuse");
        // Selection ignores visibility; changing it at the receiver must still integrate correctly.
        near(integral(full, new double[] {0, 6, 0}), 2, "fresh visibility after resampling");
        near(integral(full, new double[] {9, 0, 0}), 3, "source-occluded light visible at destination");
    }

    private static void areaToSolidAngle() {
        for (double area : new double[] {.01, .5, 2})
            for (double w : new double[] {.03, 2, 1000}) {
                double r2 = 11, cosine = .4;
                double selectionPdf = area / w;
                near(selectionPdf * r2 / (area * cosine), r2 / (w * cosine),
                    "UCW area-to-solid-angle identity");
            }
        if (!RayTracingRestirShader.FUNCTIONS.contains("area / sampleData.z"))
            throw new AssertionError("integrated shader must use the area-space UCW conversion");
    }

    private static void layoutAndHistory() {
        if (RestirLayout.bytes(1920, 1080, 0) != 16 || RestirLayout.stride(3) != 4)
            throw new AssertionError("disabled storage/combined ABI");
        if (RestirLayout.bytes(1920, 1080, 1) != 66_355_200L
            || RestirLayout.bytes(1920, 1080, 2) != 66_355_200L
            || RestirLayout.bytes(1920, 1080, 3) != 132_710_400L)
            throw new AssertionError("per-bank storage extent");
        for (int frame = 0; frame < 24; frame++) {
            boolean expected = frame % RestirLayout.HISTORY_PERIOD != 0;
            if (RestirLayout.historyUsable(true, false, 4, 4, frame) != expected)
                throw new AssertionError("sample-independent history expiry");
            if (RestirLayout.historyUsable(true, true, 4, 4, frame)
                || RestirLayout.historyUsable(true, false, 3, 4, frame)
                || RestirLayout.historyUsable(false, false, 4, 4, frame))
                throw new AssertionError("reset, revision, allocation or camera-cut history");
        }
    }

    private static void shaderVariants(Path dump) throws Exception {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            if (dump != null) Files.createDirectories(dump);
            for (boolean physical : new boolean[] {false, true}) for (int mode = 0; mode <= 3; mode++) {
                String source = RayTracingRestirShader.variant(RayTracingShaders.RAYGEN_SHADER, mode);
                if (mode != 0) source = source.replace("restir.controls.x", mode + "u");
                if (physical) source = source.replace("#version 460", "#version 460\n#define RTEST_ATMOSPHERE_LUT 1");
                String name = "restir-" + mode + (physical ? "-physical" : "-legacy");
                ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
                ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memASCII(name, true);
                ByteBuffer entry = org.lwjgl.system.MemoryUtil.memASCII("main", true);
                long result;
                try {
                    result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes,
                        Shaderc.shaderc_glsl_raygen_shader, fileName, entry, options);
                } finally {
                    org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
                    org.lwjgl.system.MemoryUtil.memFree(fileName);
                    org.lwjgl.system.MemoryUtil.memFree(entry);
                }
                try {
                    if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success)
                        throw new AssertionError(name + ": " + Shaderc.shaderc_result_get_error_message(result));
                    ByteBuffer words = Shaderc.shaderc_result_get_bytes(result).duplicate()
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    boolean optionalBinding = false;
                    for (int offset = 20; offset < words.limit();) {
                        int instruction = words.getInt(offset), count = instruction >>> 16;
                        if (count == 0) throw new AssertionError("invalid SPIR-V instruction");
                        // OpDecorate, Decoration Binding. Off specialization must erase the optional ABI.
                        if ((instruction & 0xffff) == 71 && count == 4 && words.getInt(offset + 8) == 33) {
                            int binding = words.getInt(offset + 12);
                            optionalBinding |= binding >= 41 && binding <= 43;
                        }
                        offset += count * 4;
                    }
                    if (optionalBinding != (mode != 0))
                        throw new AssertionError(name + " optional storage specialization");
                    if (dump != null) {
                        ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                        byte[] data = new byte[bytes.remaining()]; bytes.get(data);
                        Files.write(dump.resolve(name + ".spv"), data);
                    }
                } finally { Shaderc.shaderc_result_release(result); }
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void near(double value, double expected, String message) {
        if (!Double.isFinite(value) || Math.abs(value - expected) > 1e-9 * Math.max(1, Math.abs(expected)))
            throw new AssertionError(message + ": " + value + " != " + expected);
    }
}
