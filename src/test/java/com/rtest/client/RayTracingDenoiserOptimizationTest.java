package com.rtest.client;

import com.rtest.client.fsr.RtestDenoiserMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.util.shaderc.Shaderc;

/** Compiles constant-mode probes of the real RayGen; no Vulkan device or pixel comparison. */
public final class RayTracingDenoiserOptimizationTest {
    private RayTracingDenoiserOptimizationTest() { }

    public static void main(String[] args) {
        assertModeSelection();
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options,
                Shaderc.shaderc_optimization_level_performance);
            // Substitute only the frame-uniform mode. The same GLSL must retain all five
            // FSR outputs when OFF, and the eleven additional guides in NRD mode.
            assertImageWrites(compiler, options, "off",
                Float.toString(RtestDenoiserMode.OFF.shaderSignal()), 5);
            assertImageWrites(compiler, options, "nrd",
                Float.toString(RtestDenoiserMode.NRD.shaderSignal()), 16);
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("Denoiser RayGen output optimization contracts passed");
    }

    private static void assertModeSelection() {
        requireMode(false, 1.0F, RtestDenoiserMode.OFF);
        requireMode(true, 1.0F, RtestDenoiserMode.NRD);
        requireMode(true, 0.0F, RtestDenoiserMode.OFF);
        requireMode(true, 0.0001F, RtestDenoiserMode.OFF);
        requireMode(true, Math.nextUp(0.0001F), RtestDenoiserMode.NRD);
        if (RtestDenoiserMode.OFF.needsGuides() || !RtestDenoiserMode.NRD.needsGuides()) {
            throw new AssertionError("guide production disagrees with the selected consumer");
        }
    }

    private static void requireMode(boolean nrd, float nrdStrength, RtestDenoiserMode expected) {
        if (RtestDenoiserMode.select(nrd, nrdStrength) != expected) {
            throw new AssertionError("wrong effective denoiser mode, expected " + expected);
        }
    }

    private static void assertImageWrites(long compiler, long options, String mode,
                                          String signal, int expected) {
        String source = RayTracingShaders.RAYGEN_SHADER.replace(
            "camera.dynamicParameters.w", signal);
        ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
        ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memASCII("raygen-" + mode, true);
        ByteBuffer entryPoint = org.lwjgl.system.MemoryUtil.memASCII("main", true);
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes,
                Shaderc.shaderc_glsl_raygen_shader, fileName, entryPoint, options);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            org.lwjgl.system.MemoryUtil.memFree(fileName);
            org.lwjgl.system.MemoryUtil.memFree(entryPoint);
        }
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            }
            ByteBuffer words = Shaderc.shaderc_result_get_bytes(result).duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int writes = 0;
            // SPIR-V starts with a five-word header. OpImageWrite is opcode 99.
            for (int offset = 20; offset < words.limit();) {
                int instruction = words.getInt(offset);
                int count = instruction >>> 16;
                if (count == 0) throw new AssertionError("invalid SPIR-V instruction");
                if ((instruction & 0xffff) == 99) writes++;
                offset += count * 4;
            }
            if (writes != expected) {
                throw new AssertionError(mode + " RayGen image writes: " + writes + " != " + expected);
            }
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }
}
