# 开发说明

本文只提供源码导航和稳定的数据流概览。类职责、绑定布局、配置范围等易变细节以代码为准，避免在文档维护第二份实现。

## 环境

Minecraft 26.2，NeoForge 26.2.0.84，Java 25。使用仓库 Gradle wrapper。Vulkan RT 需要受支持的 Vulkan 后端/设备；编译通过不等于运行或画面通过。

## 主要入口

| 区域 | 入口 | 责任 |
|---|---|---|
| Mod/config | `RTest.java`, `RayTracingClientConfig.java` | 注册与客户端参数；默认值在配置源码 |
| 生命周期/捕获 | `RayTracingProbe.java`, `RayTracingScene.java` | 启停、可见 Section、增量捕获与发布协调 |
| 地形 LOD | `RayTracingTerrainLod.java`, `RayTracingTerrainLodScheduler.java`, `RayTracingTerrainProxyStore.java` | 构建/调度代理几何和选择；能力开关及限制以实现为准 |
| 动态几何 | `DynamicEntityGeometry.java`, `DynamicInstanceRegistry.java`, `DynamicTlasInstanceWriter.java` | 快照、admission、实例身份与 TLAS 数据 |
| RT/Vulkan | `RayTracingVulkanPass.java`, `VulkanAccelerationResources.java` | Vulkan 资源、pipeline、dispatch、同步和清理 |
| Shader | `RayTracingShaders.java`, `RayTracingShader*.java` | RT shader 源码；其他 compute shader 在 `src/main/resources` |
| Denoise/upscale | `fsr/NrdDenoiser.java`, `fsr/SundialDenoiser.java`, `fsr/RtestFsr3Upscaler.java` | NRD/Sundial 与 FSR3 compute 流程 |
| Render hooks | `src/main/java/com/rtest/mixin/` | Minecraft/NeoForge seam；修改前核对目标方法调用时序 |

## 渲染概览

1. F8 请求启用/停用。Probe 收集原生可见场景数据，并分帧处理需要的捕获工作。
2. 静态地形/材质与动态实例数据提交给 RT pass；场景、视距和 LOD 更新按实现接受并发布。
3. RT 输出进入所选降噪路径，再进入 FSR3 reconstruction/upscale 和显示拷贝。
4. 原生后续阶段继续负责未替换的内容，包括手部与 UI。对象无法进入动态 RT 时由其 raster fallback 策略处理。

细节随代码变化；不要从本页推导 descriptor binding、buffer stride、具体模型白名单或帧预算。

## 修改与核对

- 新配置放在 `RayTracingClientConfig`，界面映射见 `RayTracingSettingsScreen`。
- 修改 shader/CPU ABI 时同时检查对应 writer、descriptor、shader 声明和已有 contract tests。
- 修改捕获 seam 时核对 Mixin 目标、原版调用顺序及失败回退；避免重复绘制或吞掉原生对象。
- 修改 GPU 资源时检查创建、异常清理、重建、关闭和 submission 生命周期。
- Terrain traversal ABI 如有修改，更新 `docs/terrain-traversal-abi.md`；其中 GPU traversal 是集成于 RT pass 的实验路径，默认关闭。

按需运行 `./gradlew test --no-configuration-cache` 或 `./gradlew build`。测试不替代 Vulkan GPU 与画面验收。实机证据写入 `instance-validation.md`，记录日期和测试范围。
