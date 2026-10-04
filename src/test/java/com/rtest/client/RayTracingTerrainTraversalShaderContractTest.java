package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.util.shaderc.Shaderc;

/** Compiles and structurally checks the standalone GPU terrain traversal shader. */
public final class RayTracingTerrainTraversalShaderContractTest {
    private RayTracingTerrainTraversalShaderContractTest() { }

    public static void main(String[] args) {
        String shader;
        try (var stream = RayTracingTerrainTraversalShaderContractTest.class.getClassLoader()
                .getResourceAsStream("rtest/shaders/terrain_traversal.comp")) {
            if (stream == null) throw new AssertionError("terrain traversal shader resource is missing");
            shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new AssertionError("could not read terrain traversal shader", exception);
        }
        require(shader, "max(depthNear, depthFar) >= 0.0 && min(depthNear, depthFar) <= 1.0");
        require(shader, "vec2 rectMin = vec2(3.402823e38);");
        require(shader, "layout(local_size_x = 64");
        require(shader, "vec4 boundsMinAndLodError");
        require(shader, "uvec4 hierarchy");
        require(shader, "uvec4 draw");
        require(shader, "TERRAIN_INSTANCE_MASK = 0x3fu");
        require(shader, "UNTRACED_INSTANCE_MASK = 0x80u");
        require(shader, "node.draw.y & 0x00ffffffu");
        require(shader, "FACING_CULL_DISABLE << 24u");
        require(shader, "completeChildren");
        require(shader, "pathAllows");
        require(shader, "texelFetch(hizDepth");
        require(shader, "aggregate < rect.depth.x - DEPTH_EPSILON");
        require(shader, "aggregate > rect.depth.x + DEPTH_EPSILON");

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(shader, false);
        ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memASCII("rtest_terrain_traversal.comp", true);
        ByteBuffer entryPoint = org.lwjgl.system.MemoryUtil.memASCII("main", true);
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes,
                Shaderc.shaderc_glsl_compute_shader, fileName, entryPoint, options);
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError("terrain traversal shader compilation failed: "
                    + Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            org.lwjgl.system.MemoryUtil.memFree(fileName);
            org.lwjgl.system.MemoryUtil.memFree(entryPoint);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        compileShaderResource("rtest/shaders/terrain_hiz.comp");
        System.out.println("Terrain traversal shader contract passed");
    }

    private static void compileShaderResource(String resource) {
        String shader;
        try (var stream = RayTracingTerrainTraversalShaderContractTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) throw new AssertionError("shader resource is missing: " + resource);
            shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new AssertionError("could not read shader resource: " + resource, exception);
        }
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(shader, false);
        ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memASCII(resource, true);
        ByteBuffer entryPoint = org.lwjgl.system.MemoryUtil.memASCII("main", true);
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes,
                Shaderc.shaderc_glsl_compute_shader, fileName, entryPoint, options);
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(resource + " compilation failed: "
                    + Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            org.lwjgl.system.MemoryUtil.memFree(fileName);
            org.lwjgl.system.MemoryUtil.memFree(entryPoint);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) throw new AssertionError("missing shader contract: " + fragment);
    }
}
