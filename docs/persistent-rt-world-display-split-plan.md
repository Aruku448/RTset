# 高频光栅显示与低频世界 RT：改造设计

状态：已实现第一版实验运行管线，默认关闭。基线 `f4c4929`，研究分支 `persistent-rt-world-research`。下文“已有代码与阻塞点”记录改造前基线。

## 目标合同

高频显示调用不得发出路径追踪、ray query 或 BLAS/TLAS 构建任务。相机运动只改变投影、可见性、材质的观察方向和显示合成。低频世界任务负责照明场与可选动态照明修正。显示读最后完成的版本，未成熟照明先用明确的廉价近似，不以显示帧同步追踪补洞。

这要求保存世界几何和可查询照明，而非固定相机的颜色图。光栅可见性仍有顶点处理、片元着色、深度测试的成本；240 FPS 是否达标要用 GPU/CPU critical path 测量。

## 已有代码与阻塞点

- `RayTracingShaderRaygen.java` 的 main 仍每显示帧发 primary/direct/first-secondary，并产生深度、法线、材质、运动和 NRD AOV。世界 worker 可独立 dispatch，但显示不能跳过 RT。
- `PersistentRtLighting.java` 已有快照复制/发布和预算；调度仍在显示帧同队列中，无法保证世界更新不阻塞显示。
- `persistent_indirect_cache.glsl` 存位置/法线/入射方向的 Li；方向样本分散，不能把一次查询结果当作半球 irradiance。原缓存可保留作完整 RT 参考，不直接改名字后供光栅着色。
- `estimateAreaDirect`、太阳/月光和天空 NEE 已分 full/static visibility 与 signed entity delta。delta 已包含当前 BSDF/路径 throughput，且是随机屏幕样本，不能直接成为持久世界阴影层。
- `LevelRendererMixin` 会取消世界光栅，`RayTracingProbe.shouldCancelVanillaLevelRenderer` 假设整幅 RT 替换。仅解除取消会恢复原版烘焙照明，不会得到读取 RT 世界照明的显示管线。
- `CachedBlas.vertexBuffer` 已有每区块 XYZ 三角形，`RayTracingMaterialBuffer` 有每三角形 UV/材质布局。可研究复用 GPU 数据做 vertex pulling；需要暴露带寿命的绘制快照，并验证所需 storage/vertex usage，不能依赖私有 BLAS 资源裸指针。
- `RtestFsr3.recordAfterRayTracing` 把 NRD、当前视线大气和 FSR 串在 RT 输出后。应让 raster/display 也能提供匹配的 depth/motion/reactive 和 scene color。

## 四个模块

| 模块 | 接口职责 | 不应依赖 |
|---|---|---|
| WorldGeometry | 发布静态 section draw 数据、材料、generation；实体独立几何/变换 | 显示相机姿态 |
| WorldLighting | 更新世界照明、置信度并发布完成快照；提供查询描述符 | 当前显示像素坐标 |
| DynamicLighting | 发布实体可见性/动态灯状态与可选低频修正 | 静态缓存整体失效 |
| WorldDisplay | 用 latest camera、完成的世界快照和实体状态生成画面/FSR guides | 同步路径追踪 |

优先建立函数与资源契约，再提取类；保留现有 full RT 显示作为对照 adapter。新显示模块在资源未准备好时整帧回退到原路径，进入实验显示后不因单个缓存 miss 触发显示 RT。

## 贡献拆分

### 间接照明

先建立可供任意表面和移动实体读取的世界 irradiance probes：位置格点、方向性系数（例如低阶 SH）、样本数、confidence、generation、必要的距离/可见性信息。低频 RT 积分入射光，显示按法线求 E(n)。Lambert 分量为 albedo × E(n) / π；现有草木能量曲线、金属度和反射能量分配需要共享同一材质着色函数，不能重复乘反照率或直接把 Li 当 E。

初版共用体积 probes；后续对薄墙、草木和表面细节增加附着表面的细粒度缓存。薄叶正反面需要单独响应。probe 插值须有可见性约束，否则跨墙漏光。实体以当前位置/法线查询 probes，无需把实体自身像素写入静态表面缓存。

probe 由区块加载、变更与预算生成任务，不再只由当前屏幕散射方向发现。未成熟区域显示低频环境近似并记录 confidence，等待世界任务填充。

### 直接照明与实体阴影

太阳/月亮分开保存或生成直接源信息和静态 RGB 可见性。显示用当前材质/观察方向计算 diffuse/specular，动态遮挡只作用于对应光源。

已有 signed delta 原理可保留：L_total = L_static + (L_full - L_static)。但两项必须采用同一光源、采样域与材质合同；不能把上一视角 RGB delta 直接叠到新视角。

首版实体太阳阴影可使用独立光栅 shadow map，实体几何/姿态可以高频更新，不要求每显示帧更新 RT 动态 BVH。透明实体的 RGB 透射不能由单张标量深度图表达，需要独立 RGB 透射方案或标记近似。有限面积光源的 mean(V_static × V_dynamic) 一般不等于 mean(V_static) × mean(V_dynamic)；简单乘两个软阴影平均值不能声称等价，应对共享光源采样或完整总可见性重建。

静态自发光灯具与动态/手持灯分开。局部灯过多时需要光源分组/clustered lists，不能在每个 probe 永久保存所有光源分量。初版先支持太阳与天空，再加入静态灯具和动态灯；关闭未支持贡献必须显式标明实验范围。

### 实体影响世界

拆成三条：实体自身受光、实体投射阴影、实体发光/间接遮挡。前两者走高频光栅查询与动态层；最后一条由低频局部 RT 修正处理，不能仅把实体从 RT 排除就宣称已覆盖。移动实体不全量失效静态世界缓存。

### 反射、透明、大气

镜面需要方向性辐射信息或 probe/cubemap 查询；低阶 irradiance 不够。透明需要独立前后层及介质合成，且玻璃背景可见性不能只从一个 opaque depth 得到。初版可保留标明的廉价近似，后续增加低频 RT reflection/transmission 缓存，不在高频显示路径强制发射射线。

天空按当前方向采样；大气视线 L/T 不能直接复用旧相机已合成颜色。高频显示通过 LUT/有界步数近似读取低频介质与体积光场，保持 surface × T + L。局部体积阴影/散射随实体变化的部分单独低频更新。

## 落地顺序与验收

1. 新增实验 raster visibility pass。先输出 depth、normal、material、world position、entity ID/motion，使用固定测试照明，验证地形、草木 alpha-test、实体遮挡与材质属性。此阶段相机显示 traceCalls 必须为 0，不声称已恢复全部 RT 光照。
2. 接入世界 irradiance probes 与低频 RT 更新。冻结照明更新后，摄像机旋转/平移应仍产生新的几何可见性；实体移动读取现有照明。world snapshot generation 在单纯相机移动时保持不变。
3. 拆太阳/天空/静态灯贡献，接动态实体阴影。验证实体只遮挡相关直接源，天空/GI 不跟着整体变暗；用完整 RT 路径对照受光能量。
4. 拆后处理时钟与快照寿命。world samples 仅在新 RT 样本到达时更新历史；高频显示只做 display reconstruction/FSR，不能把复用快照作为 NRD 新观测。现有屏幕 NRD 不能直接降噪 SH/probe 数据，需要世界时间累计或专用滤波。
5. 处理镜面/透明、局部发光、体积贡献和局部失效，再扩大适用场景。

资源发布至少要覆盖照明双/三缓冲、完成版本、geometry generation、显示正在使用的版本引用与延迟退役。高频显示不得等待正在写入的世界快照。异步队列需要设备实际创建并支持相应队列，还会竞争 GPU 算力；单纯增加线程不会消除 GPU 阻塞。首版可小批次同队列更新并反馈预算，随后再评估异步队列。

关键指标：display visibility/shading/composite CPU/GPU ms、world RT update ms、display ray/AS-build call count、publish wait ms、cache coverage/confidence、snapshot age、entity-shadow latency、整帧 CPU/GPU critical path。240 Hz 对应 4.17 ms 的显示预算；同队列 RT 超长批次仍会破坏它。

## 2026-10-05 实验实现

F9 的实验世界光栅显示开关对应 `worldRasterDisplayEnabled`，默认 false。启用后：

- 当前相机每显示帧光栅化静态世界和独立实体快照，生成颜色、反向深度、法线、材质、位置与运动；FSR 读取颜色、深度和运动。原生第一人称手持物在合成之后绘制。
- 显示路径不调用屏幕路径追踪、ray query 或 NRD；独立世界更新由 `persistentRtUpdateIntervalMs` 定时，默认 50 ms，即最高约 20 Hz（并非 30–60 Hz）。显示时钟不推动世界采样。
- 世界更新以 `worldProbeTrainingBudget` 限额训练探针，默认每次 1024 条起始路径。网格低阶 SH 保存入射辐射，再按表面法线求 irradiance，表面使用 albedo × E / π。双银行复制和发布避免读写同一探针行；有限方向距离矩降低跨墙插值。
- 太阳直射和实体遮挡独立于 GI：静态太阳深度图低频更新，实体深度图随显示更新；实体仅改变太阳直射，不把整幅缓存 GI 一起压暗。实体无需在普通显示帧更新动态 RT BVH。
- 首次或变更后的 BLAS/TLAS 分批构建只进入世界更新帧；构建未完成时探针预算为零，光栅显示使用明确的环境光近似继续更新，不冻结为上一张相机图。
- 进入/退出实验模式重置显示历史；完整 RT 与离线渲染保留原路径。实验开关不会改写原 NRD 配置。

### 当前限制

这是一条可验证的显示与世界任务分离原型，尚未达到原完整 RT 的画质：天空背景目前为固定清屏色，透明表面按实体表面预览；尚未接入物理天空背景、视线大气/体积光、月光直射、PBR 镜面反射及细节材质。太阳为点源硬阴影，深度图不表达 RGB 透明透射。静态发光与天空进入探针路径，但动态/手持发光尚无独立实时直接光层。草木当前只有 alpha 裁剪和普通漫反射。

探针没有实体内部避让/重定位，低阶 SH 和粗网格会丢细节；距离矩不能保证薄墙零漏光。照明或介质 generation 变化会重置场，充分成熟需要多轮训练。太阳颜色当前简化为白色，不能与完整 RT 的大气透射作等价画质比较。

当前仍在同队列提交并等待上一帧完成。更新帧的 RT、首次建树和场景发布仍会阻塞；双银行本身不代表异步计算。静态世界也暂未做专门光栅剔除，几何发布额外复制 vertex-pulling 缓冲；实体缓冲复用但仍上传变换后的顶点。**未测得或承诺稳定 240 FPS。**

### 验证与实机指标

`./gradlew check jar --offline` 覆盖 shader 编译、SH 常量天空 E=πL、一次反照率、实体贡献能量守恒、投影、法线变换与显示/世界时钟。

实际 Vulkan 回读工具：`tools/gpu_world_raster_smoke.c` 检查重叠三角形深度、alpha 裁剪及 MRT；`tools/gpu_world_irradiance_smoke.c` 检查双银行发布、SH、独立采样序号、失效与可见性约束。这些不代替 Minecraft 整帧、手持物或动态场景验证。

日志 `world_display` 报告 `display_trace_calls=0`、世界 trace 次数、探针数、generation 和更新标记。实机应分别测普通显示帧与世界更新帧的 CPU/GPU 时间、字段覆盖率、运动拖影、实体阴影延迟、模式切换和区块重建。下一阶段首先处理并发发布/预算与光栅剔除，再恢复缺失的画质贡献。

本轮验证：`check jar` 65 个任务成功；RX 7800 XT 的实际光栅管线回读通过，世界辐照度 GPU 回读 12 项、0 差异。已备份并替换 RTest 实例 jar，未开启实验配置；Minecraft 实机画面与帧时间尚未验证。

### Y 方向回归修复

完整 RT 的图像行使用 +camera.up 随 Y 增加，最终合成沿用该合同。初版光栅负 viewport 高度增加了额外翻转，造成世界上下颠倒。修复为正 viewport 高度，同步改为 clockwise 正面判定、previous UV 正 Y，并以 fragCoord+jitter 恢复无抖动坐标。数学回归在修复前输出 rasterY=0.3725、RT Y=0.6275，修复后通过；实际 GPU 回读增加了跨行世界位置与非零抖动下静止运动检查，旧 fragment 失败、新 fragment 通过。完整 check/jar 的 65 个任务成功。
