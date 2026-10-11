# Prime 大气集成说明

> **文档状态：维护入口** · 整理核对：2026-10-11。当前事实以源码为准；旧验证不自动延续。[文档索引](README.md)

本文维护当前源码的功能边界；2026-10-11 核对配置定义、Sun/Moon SkyView 入口及相关接线记录。未新增实机验收。早期阶段、JAR hash 和测试命令已移至[历史接线记录](history/prime-atmosphere-integration-2026-10-03.md)，其中的“当前安装”只指当时。

## 来源与入口

固定上游：Prime 26.3 / `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`。来源、资产和工具链记录见 [PROVENANCE](../third_party/prime-atmosphere-26.3/PROVENANCE.md)。

- `RayTracingAtmosphere`：静态光谱场、独立 Sun/Moon SkyView、方向透射及 GPU 资源/history。
- `RayTracingAtmosphereShader`、`RayTracingAtmosphereSegmentShader`、`RayTracingShaderRaygen`：天空查询与有限段输运。
- `fsr/AerialPerspectiveComposite`、`fsr/RtestFsr3`：散射与重建路径的合成。
- `AtmosphereSettings`、`RayTracingClientConfig`：介质及功能参数；实例已有值可能覆盖源码默认值。

## 当前功能与默认值

- `primeAtmosphereEnabled=true`：物理天空实验路径默认开启；默认开启不等于 GPU 图像已完整验收。
- 已有四波长介质、方向透射、有限段空气透视，以及独立的 Sun/Moon SkyView 图与 history。月光源采用反射太阳光谱近似，不是实测月球光谱模型。
- 月盘、八阶段月相、表面月光 NEE、反射/折射及有限段月光已接线。
- 物理模式的局部发光体积采用当前介质、独立距离/选光采样及新 RGB 可见性；早期“仍复用表面选光”的记录不是当前实现规范。
- `skyboxTextureEnabled=true`，`skyboxDaylightOpacityEnabled=true`；手动不透明度默认 0.25，其使用条件见配置/实际 shader。[天空盒混合记录](prime-atmosphere-skybox-blend.md)保留其曲线与阶段验证背景。
- Sundial 降噪已移除。当前合成需区分 NRD、原始 RT 和 DLSS RR；具体执行顺序以 `RtestFsr3` 为准，不能沿用旧文档中的 Sundial AOV 分工。

## 边界与证据

不是完整 Prime renderer、epipolar 或太阳 shadow clipmap 移植；天气标量处理也不构成完整天气介质系统。介质、源项和场景阴影的近似仍需按具体场景评价。

源码/资源校验、shader 编译与 SPIR-V validation 不证明像素一致、性能改善、跨驱动稳定或 resize 故障恢复。旧部署 hash、当时“未部署”状态和当时测试通过结果都不能证明当前磁盘或运行 JVM 的状态。

## 延伸记录

- [早期有限段实现](prime-atmosphere-finite-aerial.md)：只描述该阶段，后续局部光源与重建接线已变化。
- [统一介质迁移过程](prime-atmosphere-unified-migration-plan.md)：含阶段进度和早期规划，不作为当前待办清单。
- [上游研究](prime-atmosphere-reference.md)：固定来源的研究快照，其中本地代码对照已经过时。
- [Windows 体积性能调查](windows-volume-performance-2026-10-10.md)：历史计时与待验证假设，不能泛化为所有平台的根因。
