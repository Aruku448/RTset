# RTest

Minecraft 26.2 / NeoForge 26.2 的 Vulkan 光追实验 Mod。它是原型，不是完整渲染器替换。代码和当前默认值是实现事实的唯一来源；实机表现只以 [`docs/instance-validation.md`](docs/instance-validation.md) 中带日期的记录为准。

## 当前功能概览

- F8 切换 RT，F9 打开设置；运行链为场景捕获 → Vulkan RT → 可选 NRD 降噪（或原始 RT） → FSR 3.1.5 → 原生手部/UI。
- 静态地形支持增量 Section 捕获与 terrain LOD；动态实体、方块实体及部分粒子走动态实例路径。未捕获或超容量对象由原生栅格路径回退。
- 可选 ReSTIR 直接光照和条件后缀重放/final gather，默认关闭，F9 路径追踪分类中切换；算法范围、显存与验证边界见 [`docs/restir-implementation.md`](docs/restir-implementation.md)。
- 材质支持 PBR companion map、流体捕获、太阳/天空和体积光等实验功能。各功能的开关及默认值见 `RayTracingClientConfig`。
- Prime 26.3 物理天空、方向太阳透射和四波长有限段空气透视已接入实验路径，F9 开关默认关闭；仍待实机验证，非完整 epipolar 移植，月亮已添加（八阶段月相、反射/折射、月光直射与有限段散射），完整天气和月光天空 LUT 尚未移植，见 [`docs/prime-atmosphere-integration.md`](docs/prime-atmosphere-integration.md)。
- FSR4 不属于当前 Vulkan 实现。保留的 FSR/NRD/Voxy 文档是有日期的研究资料，不是当前实现规范或任务清单。

## 环境与构建

- Minecraft `26.2`，NeoForge `26.2.0.84`，Java `25`，Gradle wrapper。
- Linux 默认开发实例：`/home/aruku/.minecraft/versions/RTest/`。命令行属性可指定其他实例。

```bash
./gradlew build
./gradlew test --no-configuration-cache
./gradlew runClient -Pgame_directory=/path/to/instance -Pgraphics_backend=vulkan
./gradlew installToInstance -Pinstance_directory=/path/to/instance/mods
```

Windows 新实例可使用 `setup-windows-instance.ps1`；NRD bridge 的构建方式见 [`native/nrd/README.md`](native/nrd/README.md)。Vulkan 启动需要 NeoForge early window control 关闭；项目的 Gradle 任务和 Windows helper 会处理该设置。

## 许可与源码交付

当前组合发行按 **GPL-3.0-only** 标识，见 `LICENSE`。第三方代码/资产保留各自许可；Prime 的限定附加许可只适用于其声明覆盖的代码，不自动扩展到 Sky Tracer 或其它组件。已经按 MIT 发布的历史副本不受本次标识变更追溯影响。

大气来源固定为 Prime `26.3` commit `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`；来源、移植修改及验证边界见 `third_party/prime-atmosphere-26.3/PROVENANCE.md`。发布 mod JAR 时同时提供对应源码及构建/安装脚本，可运行 `./gradlew correspondingSource` 生成源码归档；第三方 SDK 仍受其自身分发条款约束，归档不是免除这些义务的许可。

## 文档

- [`docs/HANDOFF.md`](docs/HANDOFF.md)：简短接手说明与当前边界。
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)：主要模块、数据流和修改入口。
- [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md)：资源生命周期与排障入口。
- [`docs/instance-validation.md`](docs/instance-validation.md)：有限的实机验证证据。
- `docs/` 下其余审计/ABI 文档按标题范围使用；`research/` 为历史研究，须重新对照当前源码。
