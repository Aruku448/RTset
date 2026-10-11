# RTest

Minecraft 26.2 / NeoForge 26.2 的 Vulkan 光追实验 Mod。它是原型，不是完整渲染器替换。代码和当前默认值是实现事实的来源；实机结论必须对应有日期、版本和环境的记录，入口见 [`docs/instance-validation.md`](docs/instance-validation.md) 与[文档索引](docs/README.md)。

## 当前功能概览

- F8 切换 RT，F9 打开设置；运行链为场景捕获 → Vulkan RT → 可选 NRD 降噪（或原始 RT） → FSR 3.1.5 / DLSS 超分辨率；DLSS 光线重建模式直接重建原始 RT 并替代 NRD。最后合成原生手部/UI。
- DLSS / DLSS RR 为 Windows NVIDIA RTX 实验功能，F9「渲染」中的「时域重建」切换；不支持时使用 FSR3。SDK 推荐尺寸、DLAA、历史重置与回退已接入，GPU 输出及已知验证限制见 [`docs/dlss-integration-2026-10-09.md`](docs/dlss-integration-2026-10-09.md)。
- 静态地形支持增量 Section 捕获与 terrain LOD；动态实体、方块实体及部分粒子走动态实例路径。未捕获或超容量对象由原生栅格路径回退。
- 可选 ReSTIR 直接光照和条件后缀重放/final gather，默认关闭，F9 路径追踪分类中切换；算法范围、显存与验证边界见 [`docs/restir-implementation.md`](docs/restir-implementation.md)。
- 材质支持 PBR companion map、流体捕获、太阳/天空和体积光等实验功能。各功能的开关及默认值见 `RayTracingClientConfig`。
- Prime 26.3 物理天空、方向透射、四波长有限段空气透视及独立 Sun/Moon SkyView 已接入实验路径；物理天空配置默认开启。不是完整 epipolar/天气系统移植，实机验证范围见 [`docs/prime-atmosphere-integration.md`](docs/prime-atmosphere-integration.md)。
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

Windows 新实例无需安装脚本：使用 Minecraft 26.2、对应 NeoForge 和 Java 25，将 Mod JAR 放进实际实例的 `mods`；在该实例的 `config/fml.toml` 设置 `earlyWindowControl = false`，启动器的**游戏参数**添加 `--graphicsBackend vulkan`。完全重启，进入世界按 F8。NRD bridge 构建方式见 [`native/nrd/README.md`](native/nrd/README.md)。

Windows DEVICE_LOST 尚未在所有 GPU 上解决。`config/rtest-audit-client.toml` 的 `forceTlasBuild = true` 可隔离 TLAS UPDATE（默认 false）；`rayCostAuditProfile = "no_volume"` 可隔离体积光，改变画面，不是通用修复。AMD/NVIDIA 实测、性能差异、已修正的对齐/无符号问题及后续验证计划见 [Windows 调查总结](docs/windows-device-lost-2026-10-08.md)。

## 许可与源码交付

当前组合发行按 **GPL-3.0-only** 标识，见 `LICENSE`。第三方代码/资产保留各自许可；Prime 的限定附加许可只适用于其声明覆盖的代码，不自动扩展到 Sky Tracer 或其它组件。已经按 MIT 发布的历史副本不受本次标识变更追溯影响。

大气来源固定为 Prime `26.3` commit `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`；来源、移植修改及验证边界见 `third_party/prime-atmosphere-26.3/PROVENANCE.md`。发布 mod JAR 时同时提供对应源码及构建/安装脚本，可运行 `./gradlew correspondingSource` 生成源码归档；第三方 SDK 仍受其自身分发条款约束，归档不是免除这些义务的许可。

## 文档

- [`docs/README.md`](docs/README.md)：按任务选择当前维护入口。
- [`docs/HANDOFF.md`](docs/HANDOFF.md)：简短接手说明与当前边界。
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)：主要模块、数据流和修改入口。
- [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md)：资源生命周期与排障入口。
- [`docs/CATALOG.md`](docs/CATALOG.md)：仅在历史追溯或证据核查时使用的完整分类目录。
- 有日期的测量、部署、审计和研究记录保留原始证据，不代表当前配置、最新 JAR 或已确认的待办；具体替代关系见文档顶部状态说明。
