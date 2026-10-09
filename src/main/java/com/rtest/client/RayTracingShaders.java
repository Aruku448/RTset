package com.rtest.client;

/** Stable aliases for shader stages consumed by the Vulkan pipeline builder and contracts. */
final class RayTracingShaders {
    static final String CONTROL_RAYGEN_SHADER = """
        #version 460
        #extension GL_EXT_ray_tracing : require
        layout(set=0, binding=1, std430) buffer Result { uint pixels[]; } result;
        void main() { result.pixels[0] = 0x52545052u; }
        """;
    static final String CONTROL_COMPUTE_SHADER = """
        #version 460
        layout(local_size_x=1, local_size_y=1, local_size_z=1) in;
        layout(set=0, binding=1, std430) buffer Result { uint pixels[]; } result;
        void main() { result.pixels[1] = 0x434f4d50u; }
        """;
    static final String PBR_FUNCTIONS = RayTracingShaderCommon.PBR_FUNCTIONS;
    static final String PLAYER_SKIN_FUNCTIONS = RayTracingShaderCommon.PLAYER_SKIN_FUNCTIONS;
    static final String RAYGEN_SHADER = RayTracingShaderRaygen.RAYGEN_SHADER;
    static final String MISS_SHADER = RayTracingShaderStages.MISS_SHADER;
    static final String CLOSEST_HIT_SHADER = RayTracingShaderStages.CLOSEST_HIT_SHADER;
    static final String SHADOW_MISS_SHADER = RayTracingShaderStages.SHADOW_MISS_SHADER;
    static final String SHADOW_CLOSEST_HIT_SHADER = RayTracingShaderStages.SHADOW_CLOSEST_HIT_SHADER;
    static final String ANY_HIT_SHADER = RayTracingShaderStages.ANY_HIT_SHADER;
    static final String SHADOW_ANY_HIT_SHADER = RayTracingShaderStages.SHADOW_ANY_HIT_SHADER;
}
