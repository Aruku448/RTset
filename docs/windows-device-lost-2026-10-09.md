# RTX 5070 Ti 实例崩溃调查（2026-10-09）

## 基线与证据

- 上游 main：`9a4a1f65e595096987b8715ced26d4b36538e27c`，包含无符号 Vulkan limit、AS 输入和 SBT 对齐修正。
- 测试实例：Minecraft 26.2 / NeoForge 26.2.0.88，Java 25.0.1，RTX 5070 Ti，NVIDIA 616.92，Vulkan 后端。
- 最近报告：`crash-2026-10-09_15.28.03-client.txt`；日志首先在 `waitForPreviousFrame` 检出 DEVICE_LOST，随后 `close` 再次等待失败，最终崩溃报告记录的是第二次异常。
- baseline、TLAS UPDATE、NRD 和体积光开启。15:27:56 发布 7 个区块更新；15:28:00 检出设备丢失。时间相关性不能证明区块发布或某条 GPU 命令是根因。

## 本地修正

1. 动态 BLAS 重建后，即使实例地址和变换不变，也刷新 TLAS，避免继续沿用动画前的包围范围。保留 BLAS → TLAS → trace 的已有依赖。
2. 场景发布后复用 TLAS 分配，但通过 BUILD 重新建立静态区块及空槽的 BLAS 引用；正常逐帧变换仍可 UPDATE。没有永久打开 `forceTlasBuild`，没有关闭画质功能。
3. 设备丢失时不再尝试重放图像或回退到同一个已丢失设备的原版渲染。清理异常作为 suppressed 保留，重新抛出最初的设备丢失异常。其他 RT 失败的清理异常也不再覆盖原始诊断。

## 验证与边界

`compileJava`、`accelerationStructureSynchronizationTest`、`vulkanResourceLifecycleTest`、`playerBlasCommandTest`、`vulkanUnsignedLimitTest`、`verifyPrimeAtmosphereResources` 和 `jar` 通过。生命周期测试遍历 TLAS 刷新条件的 16 种组合，包含只有 BLAS 动画变化的回归案例。

这些是 CPU/命令结构检查，不是 Vulkan runtime validation 或 GPU 稳定性验证。上述修正尚不能证明已经消除该实例的 DEVICE_LOST；需完全重启实例，在原配置和原场景按 F8 复测，检查区块更新及动态实体动画。若继续失败，优先采集 Vulkan validation / 同步验证或 GPU 故障命令证据，不把降低画质或延长 TDR 当成修复。

## 15:36 实例复测：仍然失败

- 最新报告为 `crash-2026-10-09_15.36.48-client.txt`。实例 JAR 与本地构建 SHA-256 均为 `2DA5429D7CDAB721AC621FA91D167C2E98D613DD69FAEB93AAA79F3F4C4ECE1B`，确认运行的是本轮修复版。
- 15:36:40 请求 RT，15:36:44 完成初始化并开始 RT 渲染，15:36:47 在 `waitForPreviousFrame:2912` 检出 `VK_ERROR_DEVICE_LOST: Failed to wait for semaphore`。
- 本次失败前没有 dirty-section 发布记录，不能再将区块增量发布当作复现必要条件。动态快照仅有玩家，无 living mesh。
- 最终报告已保留 `waitForPreviousFrame` 为原始故障，`close` 的再次等待失败变为 suppressed；异常保留修正生效，但 GPU 崩溃未解决。
- 日志没有可用于归因的 Vulkan validation 或 GPU 分阶段完成采样。队列 breadcrumb 停在 `entity_translucent`，只能作为最后记录的标记，不能据此断定该原版绘制是出错命令。
