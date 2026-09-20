package com.rtest.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.lwjgl.util.shaderc.Shaderc;

/** Verifies Sundial's split-signal ABI and compiles its source shader. */
public final class SundialDenoiserContractTest {
    private SundialDenoiserContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String shader;
        try (InputStream input = SundialDenoiserContractTest.class
                .getResourceAsStream("/prime/shaders/sundial_denoiser.comp")) {
            if (input == null) throw new AssertionError("Sundial shader source is missing");
            shader = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(shader, "binding = 10, rgba16f) uniform readonly image2D directDiffuse");
        require(shader, "binding = 11, rgba16f) uniform readonly image2D emission");
        require(shader, "binding = 9, rgba16f) uniform image2D outputColor");
        require(shader, "deterministicDirect + deterministicEmission");
        require(shader, "vec3 rawColor(ivec2 pixel)");
        require(shader, "if (currentDepth(pixel) > 60000.0)");
        require(shader, "imageStore(outputColor, pixel, currentOutput);");

        Path nrdSourcePath = Path.of("src/main/java/com/rtest/client/fsr/NrdDenoiser.java");
        if (!Files.isRegularFile(nrdSourcePath)) {
            throw new AssertionError("NRD image barrier source is missing: " + nrdSourcePath);
        }
        String nrdSource = Files.readString(nrdSourcePath, StandardCharsets.UTF_8);
        require(nrdSource, "RayGen writes all of these images on every dispatch.");
        require(nrdSource, "this.directDiffuse, this.indirectDiffuse, this.emission");

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(
                compiler, shader, Shaderc.shaderc_glsl_compute_shader,
                "rtest_sundial_denoiser.comp", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError("Sundial shader compilation failed: "
                    + Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("Sundial denoiser contract passed");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Sundial denoiser contract is missing: " + fragment);
        }
    }
}
