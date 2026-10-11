# RTest 接手说明

> **文档状态：维护入口** · 整理核对：2026-10-11。当前事实以源码为准；旧验证不自动延续。[文档索引](README.md)

源码优先。配置默认值以 `RayTracingClientConfig` 为准；运行时结构以代码为准。按当前任务选择[文档入口](README.md)；需要历史证据时再按主题查[完整目录](CATALOG.md)。

## 基线

- Minecraft 26.2 / NeoForge 26.2.0.84 / Java 25；目标为 Vulkan RT 原型。
- F8 切换 RT，F9 打开设置。
- RT 显示插入在世界渲染完成后、手部与 UI 前。当前支持 NRD/ReBLUR 或原始 RT，再走 FSR 3.1.5 / DLSS；DLSS RR 使用原始 RT 并替代 NRD。Sundial 降噪已移除，参考 Sundial 的后处理是另一项功能。
- 静态 terrain capture、LOD 和动态实例均在演进中。动态 capture/admission 失败时保留原生 raster fallback；不要把旧 placeholder 阶段记录当现状。
- Vulkan 对象由 `RayTracingVulkanPass` 管理；同步与退休必须遵从当前提交/fence 实现。不可据旧研究描述推断资源所有权。

## 接手流程

1. 按任务查相关源码；需要模块导航时读 [`DEVELOPMENT.md`](DEVELOPMENT.md)，需要排障时读 [`MAINTENANCE.md`](MAINTENANCE.md) 的相关章节。
2. 检查 `git status`，避免覆盖已有工作区改动。
3. 只有用户要求验证或变更确有必要时才运行构建/测试；GPU 画面效果需单独实机验收。
4. 新增实机结果时只记录操作、环境、观察结果和适用范围；不要把未测事项写成实现缺陷或既定计划。

## 核对范围

源码默认值、实例配置与实际 GPU 激活分别核对。实机结论携带版本、设备、配置和日志范围；任务交接只保留本轮相关事实与未完成工作。
