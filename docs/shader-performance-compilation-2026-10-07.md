# Shader performance compilation

Enabled shaderc_optimization_level_performance in RayTracingVulkanPass.ShaderModule.create. This covers shaders compiled through that runtime helper, including ray stages and the runtime light-tree compute shader. Packaged precompiled SPIR-V assets are not rebuilt by this change. The active-stage compilation contract uses the same performance option.

Passed `./gradlew rayTracingShaderContractTest jar --console=plain`. Active ray-generation, miss, closest-hit, any-hit, shadow and sky-CDF stages compile successfully with optimization. Installed jar SHA256: d7258a28ab88fdf0f37bedc114c75ec84dde0cd1c9410fd16dd3b0c485744d46.

Restart is required. No GPU speedup percentage or visual equivalence has been measured. Compare rt_ms/post_rt_ms/total_ms at identical camera, resolution, scene, denoiser/upscaler settings after warmup. Historical samples in docs/profiling/2026-10-07-shader-performance-baseline.json have uncontrolled scene/camera and cannot establish improvement.

Further specialization and pipeline cache changes are deferred until the optimized build has a measured baseline. Performance optimization can increase compilation time and does not change scene capacity or material addressing limits.
