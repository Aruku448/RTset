# RTest 交接文档

更新时间：2026-09-19

本文是当前工作区的唯一开发交接入口。它只记录已经由源码、测试或实机日志确认的状态；设计参考和历史调查不自动等同于待办事项。

## 当前基线

- Minecraft `26.2` / NeoForge `26.2.0.84` / Java `25` / Gradle `9.5`。
- 目标路径是 Linux + AMD RADV/Vulkan；项目仍是 RT 原型，不是完整 Minecraft renderer 替换。
- F8 启停 RT；F9 打开 RTest 设置界面。
- RT 捕获在 `AfterOpaqueFeatures` 收集原生最终提交数据；LevelRenderer 完成后、第一人称手部前执行 RT → NRD/Sundial → FSR3，并把 display image 写回 Minecraft 的 `main` target。手部、屏幕效果、outline、后处理和 HUD/UI 继续由原生路径绘制。
- 静态 Section 优先消费编译期 `MeshData` 快照，不适用时走 CPU fallback；含水/岩浆的 Section 走 fluid-aware CPU capture。Section 更新和 camera-window 移动是增量路径，不应因普通移动整场景重捕获。

## 已落地的动态路径

`dynamicEntityMvpEnabled=true` 时，动态对象使用固定 64 个 slot、稳定 identity/generation、current/previous transform、history reset 和每实例可变 BLAS/TLAS：

- 玩家 body：从 vanilla 已执行的 `ModelFeatureRenderer` 最终顶点 tee capture；玩家 skin 使用独立 texture descriptor。
- 生物及其它能提交可解析 opaque/cutout model 的实体：复用最终 pose、可见性和纹理快照。
- 第三人称手持物：追加到玩家动态 mesh；普通掉落物：消费成功捕获的 item mesh。捕获失败时保留原生路径，不发布诊断 placeholder。
- 第一人称 body/item：有独立 capture family 和 camera-relative item mesh；不应把它们误写成“完全未接入”。
- 方块实体：白名单/可解析的最终 model layer 进入动态 BLAS/TLAS；未知特殊 renderer、文字、物品子模型、portal/beam 等仍保留原生或 fallback。
- 透明 eyes/cape、名称牌、translucent custom geometry、复杂特殊 renderer 和完整 baked item layer 仍不是 RT 全覆盖范围。

Vulkan 资源由 `RayTracingVulkanPass` 独占。当前安全基线是 submission-scoped `GpuFence`：同一提交内完成 BLAS → TLAS → trace，待 fence 完成后再允许下一帧覆盖动态资源。不要把它描述成已经完成的多帧异步 retire；正常帧也不要重新引入 `graphicsQueue().waitIdle()`。

## 当前配置基线

以 `src/main/java/com/rtest/client/RayTracingClientConfig.java` 为准：

```toml
fsrQuality = "balanced"
fluidRtEnabled = true
dynamicEntityMvpEnabled = true
nativeEffectsOverlayEnabled = false
nrdEnabled = true
nrdStrength = 1.0
sundialDenoiserEnabled = false
```

用户实例中已有的 `config/rtest-client.toml` 可以保留自己的值；上表是源码默认值，不是对既有用户配置的覆盖指令。当前路径是 FSR3.1.5 Vulkan，不是 FSR4。

## 已确认与未确认

已由实机记录确认：RT/FSR3 能启动并显示；F9 配置界面；流体开关；camera-window 增量捕获；视距 `5 ↔ 8` 切换不再崩溃或出现旧的 `centerPixel=0x0` 空档；区块加载期间保留上一张已完成 RT 画面。

仍需专项记录：

1. 玩家、生物、第一人称 body/item、白名单方块实体的真实画面，以及未捕获对象是否正确回退、是否出现重复绘制。
2. 固定场景的 vanilla/RT A-B 画质、HDR/SDR、resize/fullscreen、长期稳定性和显存数据。
3. 视距切换和 camera-window 发布期的长帧。历史记录出现约 `68--797 ms` 峰值；保持 `SECTIONS_PER_FRAME=2`，先拆分几何发布、PBR 同步和 TLAS 更新耗时，不要只把分帧预算翻倍。
4. 粒子、云、雨雪、雷电等 native overlay。`nativeEffectsOverlayEnabled` 在没有独立 overlay target 和合成 seam 前保持关闭，不要通过再次调用 LevelRenderer 进行补救。
5. 动态透明层、特殊 block-entity geometry、portal/beam/text，以及真正的多帧 fence/timeline retire。

## 接手时先做什么

1. 阅读本文、`docs/DEVELOPMENT.md`、`docs/MAINTENANCE.md` 和 `docs/instance-validation.md`；源码事实优先于历史调查。
2. 运行 `./gradlew test --no-configuration-cache`，需要发布级检查时运行 `./gradlew clean build --no-configuration-cache`。
3. 做动态对象实机验证时开启 `dynamicEntityMvpEnabled`，检查日志中的动态 admission/fallback、BLAS UPDATE、TLAS/trace 顺序，并把结果追加到 `docs/instance-validation.md`。
4. 做性能工作时保留上一张 RT display image、固定 slot 和 fence 安全约束；先用已有 `RayTracingFrameTiming`/Vulkan 日志定位阶段，再改调度。

## 不要继续执行的旧方向

- 不要把 FSR3 SPIR-V 或 Proton/DX12 DLL 包装成 FSR4。
- 不要把历史“只做玩家 placeholder/CPU-only block entity/未接入动态 Vulkan”的阶段描述当作当前状态。
- 不要用重新渲染原生 LevelRenderer 的方式恢复粒子、云、天气；这会重复提交并破坏当前 before-hand seam。
- 不要为了追求异步而取消 fence、直接覆盖 GPU 仍在使用的资源，或把 `waitIdle()` 带回每帧路径。

## 文档边界

- 当前实现与扩展入口：[`DEVELOPMENT.md`](DEVELOPMENT.md)
- 资源/同步/故障排查：[`MAINTENANCE.md`](MAINTENANCE.md)
- 实机证据：[`instance-validation.md`](instance-validation.md)
- 当前降噪实现：[`RT-DENOISING-OVERVIEW.md`](RT-DENOISING-OVERVIEW.md)
- 原生渲染 seam 审计：[`vanilla-render-audit.md`](vanilla-render-audit.md)
- FSR4、NRD、Prime 等文件只作调查或比较参考，不能直接转成执行计划。

已移除过时的 `entity-rt-plan.md`、`dynamic-tlas-plan.md` 和早期 agent 输出；如需恢复历史背景，应从版本历史或外部记录查找，不在当前交接入口重新维护第二套计划。
