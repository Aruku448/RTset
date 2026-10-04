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
        require(shader, "linearToYCoCg(sanitize(rawDiffuse.rgb / max(diffuseFactor, vec3(0.02))))");
        require(shader, "linearToYCoCg(sanitize(rawSpecular.rgb / max(specularFactor, vec3(0.02))))");
        assertDemodulationBounds();
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

    // CPU contract examples, not GPU output comparisons. Sanitization must happen after division.
    private static void assertDemodulationBounds() {
        float[] radiances = {0.0F, 0.001F, 1.0F, 2000.0F, 65504.0F,
            Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY};
        float[] factors = {0.02F, 0.1F, 0.5F, 1.0F};
        for (float radiance : radiances) {
            for (float factor : factors) {
                float value = radiance / factor;
                float sanitized = Float.isFinite(value) ? Math.clamp(value, 0.0F, 65504.0F) : 0.0F;
                if (!Float.isFinite(sanitized) || sanitized < 0.0F || sanitized > 65504.0F) {
                    throw new AssertionError("demodulated radiance cannot be stored in FP16");
                }
                if (Float.isFinite(value) && value >= 0.0F && value <= 65504.0F
                        && sanitized != value) {
                    throw new AssertionError("normal-range lighting must not be modified");
                }
            }
        }
        if (2000.0F / 0.02F <= 65504.0F) {
            throw new AssertionError("HDR regression fixture no longer exceeds FP16");
        }
    }

    private static void require(String shader, String fragment) {
        if (!shader.contains(fragment)) {
            throw new AssertionError("NRD motion shader contract is missing: " + fragment);
        }
    }
}
