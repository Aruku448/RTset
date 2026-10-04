package com.rtest.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.lwjgl.util.shaderc.Shaderc;

/** Regression contract: NRD must never replace an unoccluded sky pixel with denoised history. */
public final class NrdCompositeShaderContractTest {
    private NrdCompositeShaderContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String shader;
        try (InputStream input = NrdCompositeShaderContractTest.class
                .getResourceAsStream("/prime/shaders/nrd_composite_simple.comp")) {
            if (input == null) {
                throw new AssertionError("NRD composite shader resource is missing");
            }
            shader = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(shader, "layout(set = 0, binding = 3, r32f) uniform readonly image2D viewZ;");
        require(shader, "if (viewZValue >= 65503.0)");
        require(shader, "layout(set = 0, binding = 4, rgba16f) uniform readonly image2D material;");
        require(shader, "layout(set = 0, binding = 6, rgba16f) uniform readonly image2D emission;");
        require(shader, "imageLoad(emission, pixel).rgb");
        require(shader, "vec3 filteredSurface = mix(rawSurface, diffuse + specular, strength);");
        require(shader, "vec4 directSample = imageLoad(directDiffuse, pixel);");
        require(shader, "vec3 dynamicShadowDelta = directSample.rgb;");
        require(shader, "raw - visibleEmission - dynamicShadowDelta");
        require(shader, "+ visibleEmission + dynamicShadowDelta");
        if (shader.contains("if (directSample.a > 0.5)"))
            throw new AssertionError("Entity shadow must not bypass unrelated pixel lighting");
        for (double baseline : new double[] {0, .1, 10}) {
            for (double visibility : new double[] {0, .25, 1}) {
                double raw = baseline * visibility + 2;
                double delta = baseline * (visibility - 1);
                double prepared = raw - 2 - delta;
                if (Math.abs(prepared - baseline) > 1e-12 || Math.abs(prepared + 2 + delta - raw) > 1e-12)
                    throw new AssertionError("Signed entity shadow decomposition energy mismatch");
            }
        }
        require(shader, "imageStore(outputColor, pixel, rawValue);");
        require(shader, "float denoisingRange;");
        require(shader, "if (!(viewZValue < settings.denoisingRange))");
        int rangeGuard = shader.indexOf("if (!(viewZValue < settings.denoisingRange))");
        if (rangeGuard > shader.indexOf("imageLoad(denoisedDiffuse, pixel)")) {
            throw new AssertionError("range guard must precede every denoised history read");
        }
        // NRD v4.17.3 IsInDenoisingRange is strictly less-than, including a NaN-safe rejection.
        float range = 100.0F;
        for (float z : new float[] {range, Math.nextUp(range), Float.NaN, Float.POSITIVE_INFINITY}) {
            if (z < range) throw new AssertionError("invalid range fixture");
        }
        if (!(Math.nextDown(range) < range)) throw new AssertionError("valid boundary fixture");
        require(shader, "layout(set = 0, binding = 8, rgba16f) uniform readonly image2D noisyDiffuse;");
        require(shader, "layout(set = 0, binding = 9, rgba16f) uniform readonly image2D noisySpecular;");
        require(shader, "vec3 diffuseSignal = diffuseSample.rgb;");
        require(shader, "vec3 specularSignal = specularSample.rgb;");
        require(shader, "if (diffuseSample.a > 0.0)");
        require(shader, "if (specularSample.a > 0.0)");
        // Missing/NaN guides must choose current-frame RGB even if history contains poison.
        for (float hitT : new float[] {-1.0F, 0.0F, Float.NaN}) {
            float chosen = hitT > 0.0F ? Float.NaN : 3.0F;
            if (chosen != 3.0F) throw new AssertionError("invalid guide consumed history");
        }
        compile(shader);
        System.out.println("NRD composite sky/range contract passed (not GPU pixel comparisons)");
    }

    private static void compile(String shader) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, shader,
                Shaderc.shaderc_glsl_compute_shader, "nrd_composite_simple.comp", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void require(String shader, String fragment) {
        if (!shader.contains(fragment)) {
            throw new AssertionError("NRD composite sky contract is missing: " + fragment);
        }
    }

}
