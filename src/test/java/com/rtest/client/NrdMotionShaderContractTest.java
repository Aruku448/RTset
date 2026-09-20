package com.rtest.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.lwjgl.util.shaderc.Shaderc;

/** Compiles and checks the raw-signal to NRD-guide preparation pass. */
public final class NrdMotionShaderContractTest {
    private NrdMotionShaderContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String shader;
        try (InputStream input = NrdMotionShaderContractTest.class
                .getResourceAsStream("/prime/shaders/nrd_motion.comp")) {
            if (input == null) {
                throw new AssertionError("NRD motion shader resource is missing");
            }
            shader = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(shader, "layout(set = 0, binding = 10, rg16f) uniform readonly image2D raygenMotion;");
        require(shader, "linearToYCoCg(sanitize(rawDiffuse.rgb)");
        require(shader, "settings.currentClipToWorld");
        require(shader, "settings.previousWorldToClip");
        require(shader, "imageStore(nrdMotion, pixel, vec4(screenMotion, previousViewZ - currentViewZ, 0.0));");
        require(shader, "imageStore(material, pixel, vec4(hit ? diffuseFactor : vec3(1.0), hit ? roughness : -1.0));");

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(
                    options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(
                    compiler,
                    shader,
                    Shaderc.shaderc_glsl_compute_shader,
                    "rtest_nrd_motion.comp",
                    "main",
                    options);
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError("NRD motion shader compilation failed: "
                        + Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("NRD motion shader contract passed");
    }

    private static void require(String shader, String fragment) {
        if (!shader.contains(fragment)) {
            throw new AssertionError("NRD motion shader contract is missing: " + fragment);
        }
    }
}
