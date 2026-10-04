package com.rtest.client;

/** Stable aliases for shader stages consumed by the Vulkan pipeline builder and contracts. */
final class RayTracingShaders {
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
