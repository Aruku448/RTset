# RTest 接手说明

源码优先。配置默认值以 `RayTracingClientConfig` 为准；运行时结构以代码为准；实机结论以 [`instance-validation.md`](instance-validation.md) 中有日期和范围的记录为准。其他研究文档不构成待办清单。

## 基线

- Minecraft 26.2 / NeoForge 26.2.0.84 / Java 25；目标为 Vulkan RT 原型。
- F8 切换 RT，F9 打开设置。
- RT 显示插入在世界渲染完成后、手部与 UI 前。当前链路含 FSR 3.1.5；NRD/ReBLUR 与 Sundial 可选，互斥行为以代码为准。
- 静态 terrain capture、LOD 和动态实例均在演进中。动态 capture/admission 失败时保留原生 raster fallback；不要把旧 placeholder 阶段记录当现状。
- Vulkan 对象由 `RayTracingVulkanPass` 管理；同步与退休必须遵从当前提交/fence 实现。不可据旧研究描述推断资源所有权。

## 接手流程

1. 看本页、[`DEVELOPMENT.md`](DEVELOPMENT.md) 和 [`MAINTENANCE.md`](MAINTENANCE.md)，再查相关源码。
2. 检查 `git status`，避免覆盖已有工作区改动。
3. 只有用户要求验证或变更确有必要时才运行构建/测试；GPU 画面效果需单独实机验收。
4. 新增实机结果时只记录操作、环境、观察结果和适用范围；不要把未测事项写成实现缺陷或既定计划。

## 不应沿用的旧说法

- FSR3 与 FSR4 不是一回事；当前项目的 Vulkan 路径是 FSR3。
- “动态对象未接入”“所有未支持对象都进入 RT”“区块更新必须整场重捕获”等旧阶段描述都不可靠。
- `research/` 和注明 audit、comparison、research 的文档是有日期的参考材料，更新前必须核对源码与来源。
