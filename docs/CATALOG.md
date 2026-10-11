# 文档完整目录（按需查阅）

> 仅在追溯来源、旧版本行为或历史验证时查阅本目录；按主题选一份记录。当前开发入口见 [文档导航](README.md)。

整理日期：2026-10-11。文档按用途分类；已被后续实现取代的 ReSTIR 报告移入 `history/restir/`，仓库内链接同步更新。源码和配置定义是实现事实；有日期的日志、部署和测试只证明对应版本/环境。此次归档仅修改文档，没有运行构建、测试或新实机基准。

## 维护入口

| 文档 | 用途 |
|---|---|
| [接手说明](HANDOFF.md) | 基线、阅读顺序与当前边界 |
| [开发说明](DEVELOPMENT.md) | 模块和数据流入口 |
| [维护与排障](MAINTENANCE.md) | 资源生命周期与排障 |
| [实机证据入口](instance-validation.md) | 早期实机记录及后续证据导航，不是最新基准 |
| [ReSTIR 实现](restir-implementation.md) | 当前已实现算法、配置与未修复限制 |
| [Prime 大气集成](prime-atmosphere-integration.md) | 当前接线与功能边界 |
| [Terrain traversal ABI](terrain-traversal-abi.md) | 接口约定；修改布局必须同步 writer 和 shader |

## 状态含义

- **维护入口**：应随实现修改更新；核对日期不等于重跑历史测试。
- **阶段变更/实施记录**：保留该次修改、部署和验证证据，不能作为当前配置或完整功能规范。
- **历史测量/排障记录**：仅适用于指定设备、版本、配置和日志窗口；文件名 latest 不表示全仓库最新结果。
- **研究/审计快照**：保留固定来源和推导；文中建议不自动成为待办，也不能当成已落地功能。
- **部分已被替代**：顶部列出后续说明；原文保留作追溯，不应重复实施已经解决的项目。
- **已归档**：过时报告移至历史目录，顶部列出已解决项与未完成边界；归档不代表整篇建议全部完成。

## 本次纠正的过时表述

| 旧说法或入口 | 处理 |
|---|---|
| 接手/开发/排障入口仍引用 Sundial 降噪与已删除类 | 更新为 OFF / NRD / DLSS_RR 与 FSR3 / DLSS，区分参考 Sundial 的后处理 |
| README 物理天空默认关闭、没有 Moon SkyView | 对照配置与 RayTracingAtmosphere 修正；大气旧阶段和安装 hash 单独保留历史 |
| GPU terrain traversal 默认关闭 | 对照配置改为 true，并区分默认值与实际激活 |
| ReSTIR 旧审查仍列全路径重跑、旧候选数为当前问题 | 标记已被部分替代，链接当前实现和续读审计 |
| 旧等价优化列表仍像待办清单 | 标明 AOV/BSDF/POM 已有后续实现，旧 Sundial 模式不适用 |
| ReSTIR 初期四项测试失败像当前失败清单 | 移至历史摘录；当前说明引用对应日期的验证，并单独记录 hit-distance 的发现及后续修正 |
| 2026-10-08 默认值快照、旧 latest 性能报告 | 明确时点，不能覆盖当前源码或实例配置 |
| GPU 回滚、恢复和诊断记录交错 | 顶部交代阶段关系，保留故障证据和后续链接 |

## 专题阅读建议

- ReSTIR：先读[实现说明](restir-implementation.md)和[最新阶段实施](restir-follow-up-implementation-2026-10-11.md)；旧审计、已解决项与未完成方向统一见[ReSTIR 归档索引](history/restir/README.md)。
- 地形兼容：区分 [Sodium 阶段实现](sodium-compatibility-2026-10-10.md)、[DH 可行性研究](distant-horizons-compatibility-2026-10-10.md)、[Voxy 参考研究](voxy-local-pipeline-research-2026-10-10.md)；研究对象存在不代表已经接入。
- Windows 故障：按记录日期和部署版本追踪，不把早期失败外推为当前必现，也不把短期成功写成所有 GPU 稳定。

## 分类记录

以下记录均有顶部状态说明；同主题内按文件名列出，顺序不是优先级或实施顺序。

### ReSTIR 与采样

| 记录 | 状态 |
|---|---|
| [ReSTIR 续读优化实施（2026-10-11）](restir-follow-up-implementation-2026-10-11.md) | 阶段实施与编译记录 |
| [ReSTIR 归档与事项状态](history/restir/README.md) | 4 份历史报告；注明已解决项与剩余边界 |

### 大气、天空与体积

| 记录 | 状态 |
|---|---|
| [当前大气性能与体积接线检查](atmosphere-performance-2026-10-02.md) | 历史测量/排障记录 |
| [不透明度与太阳光照等级曲线](daylight-curves-2026-10-04.md) | 阶段变更记录 |
| [太阳软阴影与日照强度调整](daylight-soft-shadows-2026-10-04.md) | 阶段变更记录 |
| [四波长有限段空气透视实现](prime-atmosphere-finite-aerial.md) | 早期阶段实施记录 |
| [Prime 26.3 大气系统源码参考](prime-atmosphere-reference.md) | 上游研究与旧本地对照 |
| [半透明天空盒与物理大气混合](prime-atmosphere-skybox-blend.md) | 阶段实现与曲线记录 |
| [RTest 逐步统一到物理大气：替换边界与顺序](prime-atmosphere-unified-migration-plan.md) | 历史规划与阶段进度 |
| [Prime 统一大气：实现闭环与不可直接替代的边界](prime-atmosphere-unified-migration-research.md) | 研究/审计快照 |
| [月盘、地面月光与有限段月光散射](prime-atmosphere-visible-moon.md) | 早期阶段实施记录 |
| [Prime 体积光雾对 RTest26.2 的适配性评估（2026-10-02）](prime-volume-suitability-2026-10-02.md) | 研究/审计快照 |
| [论文第 39 章：硬件 CDF 反演已接入](sky-cdf-hardware-paper-39-2026-10-04.md) | 阶段变更记录 |
| [CDF 构建提交遗漏导致的 GPU 故障修正](sky-cdf-submit-crash-fix-2026-10-04.md) | 阶段变更记录 |
| [根据 Ray Tracing Gems II 第 39 章优化天空采样](sky-importance-paper-39-2026-10-04.md) | 阶段变更记录 |
| [暂停旧天空盒贴图的诊断版](skybox-texture-diagnostic.md) | 已被后续方案替代 |
| [树冠光束缺失：已确认缺口与修复边界](tyndall-sky-shadow-fix-2026-10-03.md) | 阶段变更记录 |
| [体积与太阳采样算法优化（2026-10-07）](volume-algorithm-optimization-2026-10-07.md) | 阶段变更记录 |

### 地形、LOD 与第三方兼容

| 记录 | 状态 |
|---|---|
| [区块/RT 捕获缓慢：调度回退错误（2026-10-01）](chunk-capture-diagnosis-2026-10-01.md) | 阶段变更记录 |
| [Distant Horizons 官方 API 兼容调查（2026-10-10）](distant-horizons-api-research-2026-10-10.md) | 研究/审计快照 |
| [Distant Horizons 与 RTest LOD 兼容评估](distant-horizons-compatibility-2026-10-10.md) | 兼容性研究，尚未实施适配 |
| [GPU 光源树构建（2026-10-05）](gpu-light-tree-2026-10-05.md) | 阶段变更记录 |
| [GPU 建树后续优化（2026-10-05）](gpu-tree-followup-optimization-2026-10-05.md) | 阶段变更记录 |
| [当前瓶颈：跳过等价光源树重建（2026-10-02）](light-tree-reuse-2026-10-02.md) | 阶段变更记录 |
| [LOD 构建期间的发布卡顿](lod-build-stutter-2026-10-10.md) | 阶段变更记录 |
| [参考本机 Voxy 的 LOD 性能完善](lod-voxy-performance-2026-10-10.md) | 阶段变更记录 |
| [Persistent RT World：RTset 可行性分析](persistent-rt-world-feasibility-2026-10-05.md) | 研究/审计快照 |
| [几何发布与光源复用优化](publication-incremental-optimization-2026-10-05.md) | 阶段变更记录 |
| [Sodium terrain compatibility (2026-10-10)](sodium-compatibility-2026-10-10.md) | 阶段变更记录 |
| [地形构建数学/算法加速（2026-10-02）](terrain-build-math-2026-10-02.md) | 阶段变更记录 |
| [Terrain LOD 表面体素代理修复](terrain-lod-voxel-proxy.md) | 阶段变更记录 |
| [区块构建与地形遍历数学复查](terrain-math-followup-2026-10-05.md) | 阶段变更记录 |
| [本机 Voxy LOD 实现与 RTest 对照](voxy-local-lod-review-2026-10-10.md) | 研究/审计快照 |
| [本机 Voxy LOD 异步流水线检查（2026-10-10）](voxy-local-pipeline-research-2026-10-10.md) | 研究/审计快照 |

### 降噪、重建与后处理

| 记录 | 状态 |
|---|---|
| [降噪方案研究记录](DENOISER-RESEARCH.md) | 历史选型研究 |
| [FSR4 / AMD FSR Upscaling 研究记录](FSR4-RESEARCH.md) | 历史 SDK 支持调查 |
| [当前画面参数设为默认（2026-10-08）](current-visual-defaults-2026-10-08.md) | 2026-10-08 参数快照 |
| [2026-10-01：细节优先的采样 → NRD 质量修正](denoiser-quality-2026-10-01.md) | 阶段变更记录 |
| [DLSS / DLSS Ray Reconstruction 接入记录（2026-10-09）](dlss-integration-2026-10-09.md) | 阶段变更记录 |
| [FSR Radiance Caching 官方支持边界核实](fsr-radiance-cache-official-support-2026-10-05.md) | 历史 SDK 支持调查 |
| [FSR Radiance Cache 与 RTset 接入评估](fsr-radiance-cache-rtset-feasibility-2026-10-05.md) | 研究/审计快照 |
| [NRD 贡献覆盖检查](nrd-contribution-coverage-2026-10-04.md) | 阶段变更记录 |
| [实体阴影与 NRD 照明分离](nrd-entity-shadow-split-2026-10-04.md) | 阶段变更记录 |
| [PBR 照明黑色采样绕过 NRD 修复](nrd-zero-sample-fix-2026-10-05.md) | 阶段变更记录 |
| [太阳高亮与曝光稳定性](post-processing-exposure-2026-10-10.md) | 阶段变更记录 |
| [Sundial 参考后处理：RTset main 实现](sundial-post-processing-implementation-2026-10-06.md) | 阶段实施记录 |
| [Sundial 2026-07-31 后处理源码核对](sundial-post-processing-research-2026-10-06.md) | 研究/审计快照 |
| [删除 Sundial 降噪](sundial-removal-2026-10-02.md) | 历史删除记录 |

### 性能与平台故障

| 记录 | 状态 |
|---|---|
| [AMD / NVIDIA 崩溃差异：15:50 复测](amd-nvidia-crash-comparison-2026-10-09.md) | 历史测量/排障记录 |
| [排错更改复核](debug-change-review-2026-10-09.md) | 研究/审计快照 |
| [22:35 GPU 崩溃与同步规范审计](device-lost-conformance-audit-2026-10-07.md) | 研究/审计快照 |
| [首次使用与 Windows 11 兼容性审查](first-use-compatibility-audit-2026-10-08.md) | 研究/审计快照 |
| [GPU 路径逻辑检查与动态材质修复（2026-10-08）](gpu-logic-repair-2026-10-08.md) | 阶段变更记录 |
| [GPU 光线追踪实现检查（2026-10-08）](gpu-ray-tracing-implementation-review-2026-10-08.md) | 研究/审计快照 |
| [GPU 优化版本重置故障与撤回（2026-10-08）](gpu-reset-rollback-2026-10-08.md) | 中间回滚记录 |
| [GPU 重置根因调查进度（2026-10-08）](gpu-reset-root-cause-investigation-2026-10-08.md) | 阶段变更记录 |
| [GPU 光照传输与诊断数据优化（2026-10-08，已撤回）](gpu-transport-optimization-2026-10-08.md) | 含回滚和恢复的阶段记录 |
| [硬件利用与全链路审阅（2026-10-08）](hardware-utilization-audit-2026-10-08.md) | 历史测量/排障记录 |
| [NVIDIA 渲染失败：代码与规范核对（2026-10-09）](nvidia-render-failure-audit-2026-10-09.md) | 历史测量/排障记录 |
| [游戏退出诊断：systemd-oomd 杀进程](oom-crash-diagnosis-2026-10-04.md) | 历史测量/排障记录 |
| [Prime dev 优化迁移评估（2026-10-04）](prime-dev-optimization-migration-2026-10-04.md) | 上游优化迁移研究 |
| [Prime dev 优化提交审查（2026-10-04）](prime-dev-optimization-upstream-2026-10-04.md) | 研究/审计快照 |
| [RT 进入失败：Prime dev 对照与 Vulkan 管线检查](prime-dev-vulkan-audit-2026-10-09.md) | 研究/审计快照 |
| [Prime 机制性能优化第一批（2026-10-04）](prime-performance-optimizations-2026-10-04.md) | 阶段变更记录 |
| [第二批RT性能优化（2026-10-04）](prime-performance-optimizations-second-2026-10-04.md) | 阶段变更记录 |
| [推送前验证（2026-10-08）](push-validation-2026-10-08.md) | 阶段变更记录 |
| [光追内部成本审计（2026-10-07）](ray-tracing-cost-audit-2026-10-07.md) | 历史测量/排障记录 |
| [光追数学与算法优化候选](rt-optimization-candidates.md) | 部分已被实施的旧候选 |
| [第一批光追等价优化](rt-optimization-implemented.md) | 历史实施记录 |
| [其他性能开销检查（2026-10-05）](rt-other-overheads-2026-10-05.md) | 性能审计快照 |
| [当前光追阶段开销：2026-10-01](rt-performance-2026-10-01.md) | 历史测量/排障记录 |
| [当前性能瓶颈检查（2026-10-04）](rt-performance-2026-10-04.md) | 历史测量/排障记录 |
| [RTest 分阶段性能测量（2026-10-07）](rt-performance-breakdown.md) | 历史测量/排障记录 |
| [最近一次运行性能瓶颈](rt-performance-latest-2026-10-05.md) | 历史测量记录 |
| [RT 热路径与死代码清理（2026-10-01）](rt-runtime-cleanup-2026-10-01.md) | 阶段变更记录 |
| [RT startup scene limit: 50 million triangles](rt-startup-limit-2026-10-07.md) | 阶段变更记录 |
| [Shader performance compilation](shader-performance-compilation-2026-10-07.md) | 阶段变更记录 |
| [Windows RT 启动、性能与 DEVICE_LOST 调查（2026-10-08）](windows-device-lost-2026-10-08.md) | 历史测量/排障记录 |
| [RTX 5070 Ti 实例崩溃调查（2026-10-09）](windows-device-lost-2026-10-09.md) | 历史测量/排障记录 |
| [Windows 体积光／体积雾性能调查](windows-volume-performance-2026-10-10.md) | 历史测量/排障记录 |

### 材质、光照与动态捕获

| 记录 | 状态 |
|---|---|
| [Armourer's Workshop 26.2 上游资源与 GUI 核查](armourers-workshop-26.2-upstream-audit-2026-10-06.md) | 研究/审计快照 |
| [AW server crash during block removal](aw-block-removal-crash-2026-10-07.md) | 阶段变更记录 |
| [AW custom avatars in the Vulkan RT scene](aw-custom-model-rt-capture-2026-10-06.md) | 阶段变更记录 |
| [AW emissive identification and surface offset](aw-emissive-offset-2026-10-07.md) | 阶段变更记录 |
| [BSDF 数学计算优化（2026-10-07）](bsdf-math-optimization-2026-10-07.md) | 阶段变更记录 |
| [动态 BLAS 批处理与材质差分上传（2026-10-07）](dynamic-blas-batch-optimization-2026-10-07.md) | 阶段变更记录 |
| [材质与发光源数据流优化（2026-10-07）](emitter-material-dataflow-optimization-2026-10-07.md) | 阶段变更记录 |
| [F9 设置界面整理（2026-10-08）](f9-settings-review-2026-10-08.md) | 研究/审计快照 |
| [第一人称玩家与 NRD](first-person-rt-body-2026-10-06.md) | 阶段变更记录 |
| [水/方块接触处跳面修复](fluid-contact-fix-2026-10-01.md) | 阶段变更记录 |
| [iterationRP Alpha 0.8.29 光谱实现检查](iterationrp-0.8.29-spectral-inspection.md) | 研究/审计快照 |
| [ITRP 草木受光模拟（2026-10-04）](iterationrp-vegetation-lighting-2026-10-04.md) | 阶段变更记录 |
| [光照数据流与粒子上传修复（2026-10-07）](lighting-logic-repair-2026-10-07.md) | 阶段变更记录 |
| [光追其他逻辑审阅（2026-10-07）](logic-review-2026-10-07.md) | 研究/审计快照 |
| [材质上传与资源退役：数学检查及优化（2026-10-02）](material-upload-lifetime-math-2026-10-02.md) | 阶段变更记录 |
| [按开销优化：实体模型捕获与合并（2026-10-08）](model-capture-batch-optimization-2026-10-08.md) | 阶段变更记录 |
| [非 PBR 发光强度（2026-10-08）](non-pbr-emission-control-2026-10-08.md) | 阶段变更记录 |
| [PBR 与箱子阴影差异检查](pbr-shadow-review-2026-10-08.md) | 研究/审计快照 |
| [Vulkan 辐射缓存算法实验](radiance-cache-experiment-2026-10-05.md) | 隔离实验，非正式渲染功能 |
| [RT 渲染数学审计与加速顺序](rt-math-acceleration-audit-2026-10-04.md) | 部分已有后续修复的审计 |
| [渲染数学修正与加速实现](rt-math-acceleration-implemented-2026-10-04.md) | 阶段变更记录 |
| [RT CPU / 材质数学审计（2026-10-04）](rt-math-cpu-material-audit-2026-10-04.md) | 研究/审计快照 |
| [渲染数学复查：输运、采样与重建](rt-math-followup-audit-2026-10-04.md) | 部分已有后续修复的审计 |
| [输运数学复查修正](rt-math-followup-fixes-2026-10-04.md) | 阶段变更记录 |
| [RT 表面输运数学审计（2026-10-04）](rt-math-transport-audit-2026-10-04.md) | 研究/审计快照 |
| [草斜面的世界轴明暗接缝](slanted-grass-normal-seam-2026-10-04.md) | 阶段变更记录 |
| [水下橙红修正](underwater-color-fix-2026-10-04.md) | 阶段变更记录 |
| [告示牌文字与地图接入 RT](world-sign-text-map-2026-10-06.md) | 阶段变更记录 |

### 历史摘录

| 记录 | 状态 |
|---|---|
| [Prime 大气早期接线与部署记录（截至 2026-10-03）](history/prime-atmosphere-integration-2026-10-03.md) | 历史摘录 |
| [ReSTIR 初期验证记录（2026-10-07）](history/restir-validation-2026-10-07.md) | 历史摘录 |

## 外部项目研究

- [Voxy 2622 概览（2026-09-22）](../research/voxy-2622-project-overview.md)：固定分支快照；后续本机不同分支对照见上面的 LOD 类记录。

## 维护约定

新功能应先更新对应维护入口；详细实验、测量、构建和部署证据使用带日期记录并加入本索引。新结论覆盖旧说明时，在旧文顶部说明替代关系，保留原始证据。不要把旧测试通过/失败或安装 hash 改写成当前状态；不要因文档整理重新部署或覆盖用户配置。

`profiling/` 和参数 JSON 是原始测量/快照数据，保留原名；解释必须携带来源日期、执行模式和环境。`../research/` 是外部项目历史研究，不能当作本项目实现说明。
