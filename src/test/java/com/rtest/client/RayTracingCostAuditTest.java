package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.util.shaderc.Shaderc;

/** Compile the actual audit variants and export SPIR-V for independent validation. */
public final class RayTracingCostAuditTest {
    public static void main(String[] args) throws Exception {
        if (!RayTracingCostAudit.raygen(RayTracingShaders.RAYGEN_SHADER, RayTracingCostAudit.Profile.BASELINE)
                .equals(RayTracingShaders.RAYGEN_SHADER)
            || !RayTracingCostAudit.closestHit(RayTracingShaders.CLOSEST_HIT_SHADER, RayTracingCostAudit.Profile.BASELINE)
                .equals(RayTracingShaders.CLOSEST_HIT_SHADER)) {
            throw new AssertionError("baseline shader source changed");
        }
        for (int mode = 0; mode < 4; mode++) {
            if (RayTracingCostAudit.Profile.BASELINE.effectiveRestirMode(mode) != mode
                || RayTracingCostAudit.Profile.NO_RESTIR.effectiveRestirMode(mode) != 0
                || RayTracingCostAudit.Profile.SUN_VISIBILITY.effectiveRestirMode(mode) != 0
                || RayTracingCostAudit.Profile.NO_GI.effectiveRestirMode(mode) != (mode & 1)
                || RayTracingCostAudit.Profile.NO_AREA.effectiveRestirMode(mode) != (mode & 2)
                || RayTracingCostAudit.Profile.PRIMARY_MATERIAL.effectiveRestirMode(mode) != 0
                || RayTracingCostAudit.Profile.TRAVERSAL_ONLY.effectiveRestirMode(mode) != 0) {
                throw new AssertionError("ablation and reservoir layout disagree");
            }
        }
        String pass = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        if (!pass.contains("this.auditProfile == RayTracingCostAudit.requested()")
            || !pass.contains("int requested = RayTracingRestirShader.requestedMode();")) {
            throw new AssertionError("profile change must rebuild pipeline and use the same effective reservoir mode");
        }
        Path directory = Path.of("tmp/profiling/ray-cost-audit-spv");
        String visibility = RayTracingCostAudit.raygen(RayTracingShaders.RAYGEN_SHADER,
            RayTracingCostAudit.Profile.SUN_VISIBILITY);
        if (!visibility.contains("auditSunVisibility += shadowFactor / float(sunSampleCount);")
            || !visibility.contains("radiance = auditSunVisibility; break;")
            || !visibility.contains("vec4(primaryBaseColor, -1.0)"))
            throw new AssertionError("Visibility probe must show actual shadow payload and bypass NRD");
        var traces = java.util.regex.Pattern.compile("traceRayEXT\\(");
        if (traces.matcher(visibility).results().count()
            != traces.matcher(RayTracingShaders.RAYGEN_SHADER).results().count())
            throw new AssertionError("Visibility probe added shadow queries");
        Files.createDirectories(directory);
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            for (RayTracingCostAudit.Profile profile : RayTracingCostAudit.Profile.values()) {
                for (int requested : new int[] {0, 1, 3}) {
                    for (boolean atmosphere : new boolean[] {false, true}) {
                        String source = RayTracingShaders.RAYGEN_SHADER;
                        if (atmosphere) source = source.replace("#version 460", "#version 460\n#define RTEST_ATMOSPHERE_LUT 1");
                        source = RayTracingRestirShader.variant(source, profile.effectiveRestirMode(requested));
                        source = RayTracingCostAudit.raygen(source, profile);
                        compile(compiler, options, directory.resolve(profile.key() + "-mode" + requested + "-atmo" + atmosphere + ".spv"), source, Shaderc.shaderc_glsl_raygen_shader);
                    }
                }
                compile(compiler, options, directory.resolve(profile.key() + "-hit.spv"),
                    RayTracingCostAudit.closestHit(RayTracingShaders.CLOSEST_HIT_SHADER, profile), Shaderc.shaderc_glsl_closesthit_shader);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("Ray cost audit: " + RayTracingCostAudit.Profile.values().length
            + " profiles, baseline identity, reservoir-mode consistency and "
            + RayTracingCostAudit.Profile.values().length * 7 + " SPIR-V modules passed (not GPU timing)");
    }

    private static void compile(long compiler, long options, Path target, String source, int kind) throws Exception {
        ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
        ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memUTF8(target.getFileName().toString(), true);
        ByteBuffer entry = org.lwjgl.system.MemoryUtil.memASCII("main", true);
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, kind, fileName, entry, options);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            org.lwjgl.system.MemoryUtil.memFree(fileName);
            org.lwjgl.system.MemoryUtil.memFree(entry);
        }
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(target + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            byte[] output = new byte[bytes.remaining()];
            bytes.get(output);
            Files.write(target, output);
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }
}
