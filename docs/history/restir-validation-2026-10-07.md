# ReSTIR 初期验证记录（2026-10-07）

> **文档状态：历史摘录** · 整理日期：2026-10-11。
> 从维护入口分离的原始记录；不代表当前实现、测试状态或安装版本。[当前入口](../README.md) [分类索引](../README.md)

以下是初期实现当时的编译、测试及失败记录，不能据此判断当前工作区测试状态。当前算法说明见 [ReSTIR 实现](../restir-implementation.md)。

## Verification and limits

`RestirConditionalMathTest` exhaustively checks finite-domain reservoir outcomes under changing conditional targets, joint prefix/suffix UCWs, signed contributions, fresh visibility, loss of support with zero targets and recovery with positive targets. It also checks area/solid-angle conversion, buffer sizes and history gates. This validates the estimator algebra, not a proof of unbiasedness of the complete floating-point, pseudorandom Minecraft renderer.

The actual integrated shader compiles in all four modes for both legacy and physical atmospheres. Optimized off shaders are checked for removal of optional bindings. The eight generated modules passed `spirv-val --target-env vulkan1.2`. Existing active-stage compilation and NRD output contracts pass.

Final targeted command: `./gradlew restirConditionalMathTest rayTracingShaderContractTest rayTracingAtmosphereShaderTest jar --offline --console=plain` passed. The eight final mode/atmosphere modules passed SPIR-V validation. `git diff --check` passed.

`./gradlew test jar --continue --offline` completed the remaining checks and built the JAR, but the overall suite failed at four existing contracts:

| Task | Failure |
|---|---|
| `entityRasterFallbackContractTest` | Missing source fragment `new SubmitNodeStorage()` |
| `sceneGeometryMergeContractTest` | Missing source fragment `MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY = 10_000_000;` |
| `playerAnimationContractTest` | Hand map custom submit did not enter camera-local RT capture |
| `vulkanResourceLifecycleTest` | Missing source fragment `DYNAMIC_BLAS_UPDATE_INTERVAL_FRAMES = 4` |

All four were reproduced with the pre-ReSTIR main Java files compiled into an isolated classpath and identical existing entity/scene inputs; the shared checkout was not reverted. These failures were not changed by this work.

Artifact: `build/libs/rtest-0.1.0.jar`.

No game frame timing, visual equivalence or GPU speedup is established by compiler/finite-domain tests. Installation into an instance does not update code already loaded by running game processes.
