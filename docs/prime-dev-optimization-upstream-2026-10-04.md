# Prime dev 优化提交审查（2026-10-04）

## 范围与证据

用户指定 [dev 提交列表](https://github.com/bWFuanVzYWth/prime/commits/dev/)。本轮使用独立只读 clone `/tmp/prime-dev-upstream-20261004` 核对实际历史和 diff，未 fetch 项目内的 `third_party`，未修改运行代码或部署。

核对时 dev HEAD：[`dc708f52c4e4f68b4a7a148e73e3ff224142003c`](https://github.com/bWFuanVzYWth/prime/commit/dc708f52c4e4f68b4a7a148e73e3ff224142003c)，2026-10-03。审查覆盖初始 2026-09-26 至 HEAD 的 69 条提交，重点检查 2026-09-30—10-03 的性能相关 diff。下文机制由实际 diff 与 owning 源码交叉核对；未运行 Prime，也没有可与本机 RTest 直接比较的同场景 FPS 数据。

本地兼容性由主任务交叉核对：RTest 使用 `vkCmdTraceRaysKHR` / closest-hit / any-hit，Prime 新 dev 使用 compute 内联 `RayQuery`；本机驱动没有 opacity micromap 扩展。现有 RTest 已有末顶点停止、AOV 按启用写出、section 捕获预算等机制，不能重复计为新收益。

## 主要提交与可迁移性

| 提交 | 实际机制 | RTest 适用程度与限制 |
| --- | --- | --- |
| [`aacf17c`](https://github.com/bWFuanVzYWth/prime/commit/aacf17c55c11a131a4d890c2be7cf7de30307cc3) | 将 committed query 的 ID、bary、距离、front 和动态变换在贴图/材质工作前消费完；零初始化 miss；发光复用刚读过的过滤 specular alpha | 借鉴“最后使用点”与贴图复用。RTest 没有同一种 query 对象，不能原样搬；要检查真正跨 TraceRay 的活跃变量和 payload，而非仅改 C/GLSL struct 字节数 |
| [`8026f75`](https://github.com/bWFuanVzYWth/prime/commit/8026f7554dfd034f5d3a156bb9e450f07cfe6af6) | raw SurfaceHit、纹理样本与 emitter metadata 在嵌套 scope 结束，之后仅 canonical vertex/介质进入阴影；收窄 BSDF 输入；Offline 单样本 specialization；history 在 trace 后读取；末顶点跳过无消费者的 continuation | 适合拆分接收表面准备与逐光源 BSDF evaluate，避免每次太阳/月亮/天空/局部光反复构造同一状态。RTest 末顶点 break 已实现；Offline history 延后不应作为当前实时主要优化 |
| [`29181d3`](https://github.com/bWFuanVzYWth/prime/commit/29181d31e188e12c676b6c950922be346dd4db67) | 去掉 LightSample 未消费的 light ID 与 cosine out；静态灯源用 translation-only 重建代替通用矩阵；清理重复 MIS helper；精简源 ownership 检查 | 可审查 RTest static/dynamic 分支、记录字段与构造成本。源代码少了字段不证明 allocated registers 或整帧下降 |
| [`3292bbf`](https://github.com/bWFuanVzYWth/prime/commit/3292bbf81acd169e8390fc5a82a75b2121cd5a04) | 先算太阳 radiance，为零则不发 shadow ray；RGB transmittance 中普通 opaque blocker 使用 ACCEPT_FIRST_HIT_AND_END_SEARCH，光学边界不 commit，仍完整处理透射；改进源编译与上传 | 同样可在 RTest 保留 RGB T 的情况下省空贡献射线。须检查 RTest 是否已经 terminate opaque any-hit；不能在第一块玻璃上终止 |
| [`2452914`](https://github.com/bWFuanVzYWth/prime/commit/24529142e2afaca6e2986e70b7ff7974225b0f31) | 16 m cell / R24，75% local + 25% global 的 alias 混合；稳定灯身份与增量更新；正反向都用完整混合 PDF | 可替换或作为本地 light tree 的候选，降低选择成本及无效远光样本。alias 单次表项选择是常数工作，但找 cell 有 hash probe、反向 local PDF 有二分；不能称全流程严格 O(1)。改变分布意味着噪声、收敛率、维护内存均需实测，不会消灭阴影射线 |
| [`53fa71d`](https://github.com/bWFuanVzYWth/prime/commit/53fa71db863ca415c26ed6041b00be714508ed9a) | 从第二次有效 scatter 做 eta-aware roulette，p=clamp(maxRGB(beta)*etaScale,0,1)，透射 etaScale *= relativeEta²；存活重加权 beta/p；默认预算改为 12 顶点 | RTest 短 GI=4 时可省的尾段有限；可作保持无偏期望的路径终止方案，玻璃不可忽略 etaScale。默认 12 本身增加上限，不是优化；不能按源码变化推断比 RTest 4 段更快 |
| [`45253ae`](https://github.com/bWFuanVzYWth/prime/commit/45253ae344363edf055b363e493fbb8157d844dd) | Realtime 拆 K1 主查询/delta/guides → K2 transport → post；first rough hit landing 已完成纹理/Beer/cone/发光，K2 从 NEE 开始，不重复主查询 | 可考虑把体积/合成/AOV/显示移出 RTest raygen，降低跨 RT 活跃值；属于较大重构。Prime 是两个 compute megakernel，不是逐 bounce wavefront；多 pass 增加 handoff 全图读写和 barrier，Prime 文档也不宣称已证整帧收益 |
| [`a1d13a2`](https://github.com/bWFuanVzYWth/prime/commit/a1d13a2eb34e96f08a204fb63dda308849b14b40) | 在资源代次为 full-sprite 1/2/4 tile、D4 UV 模板烘焙并 deduplicate OMM，地形更新只引用 global index；动画全集证明；unknown 回退 alpha shader | 对大量树叶/草、所有太阳与体积阴影射线理论很有价值，但当前本机驱动不支持，不能作为可立即交付优化。RTest 的 atlas UV、阈值、动画必须单独证明，不能搬 Prime 阈值或把 RGB 透射玻璃当 alpha cutout |
| [`ee21aed`](https://github.com/bWFuanVzYWth/prime/commit/ee21aedf5549c3faf4b32cef8269cdaeac8ab0ca) | CPU section 编译与后段静态 cell 构建分开预算，默认每帧各 8 cell，可调 1–128；cell=4³ sections；公平待办与真实 publication 后 ack | RTest 已有 source capture budget，剩余重点在 GPU 准备/AS 发布预算与完成驱动退休。Prime 明确预算限制工作数量而非毫秒；不会改善静止时 PT 42 ms |
| [`2213664`](https://github.com/bWFuanVzYWth/prime/commit/2213664e394484f3aaa45d4aefbbb1fe20c28258) | 静态 BLAS ALLOW_COMPACTION + compacted-size query；提交完成后无 WAIT 读结果；budget 最多 32 copies / 8 MiB target；TLAS 换地址、旧源保持到末消费者完成 | RTest 可做，主要预期是 AS 存储/缓存改善，移动时复制和临时双份内存是新成本。须先消除稳态强制 CPU fence wait；不能仅由 compacted 字节推导 FPS。HDR/FG/Reflex 不是降低同画质原生 RT 计算成本 |
| [`9f5bafe`](https://github.com/bWFuanVzYWth/prime/commit/9f5bafe8c9c99f47eb5d5112e24d92029c175112) | 生命周期边界准备 sprite/LabPBR/shape 等不可变资源，共享 CPU worker pool，typed ABI | 可改善跑图/资源准备尖峰，需匹配 RTest 当前线程池及资源快照语义；不宜仅为这些机制整体移植 Rust 引擎 |

## 大气缓存是独立的一类优化

[`393ddc6`](https://github.com/bWFuanVzYWth/prime/commit/393ddc63b9145b48245f793b94ae5e6a0a0613dd) 引入缓存大气与极线空气透视；这比单个 shader 状态收窄更接近 RTest 每像素 8 步体积积分的成本来源。

固定 HEAD 的 owning 源码：

- [缓存更新与资源规格](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/src/atmosphere/mod.rs)
- [五级遮挡请求](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/atmosphere/shadow_source.inc.slang)
- [遮挡列补齐](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/atmosphere/shadow_resolve.slang)
- [实际 scalar blocker 查询](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/ray_query.slang#L110-L131)
- [消费与失效契约](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/docs/atmosphere.md)

机制：SkyView 256²；Camera-T 8193×1；Aerial-S 128×256×128，约 32 MiB；Aerial-T 128×64×128，约 8 MiB；五级 512² blocker columns 约 20 MiB；profile history 约 1.5 MiB。太阳基与相机输入一致时复用；仅场景变化可逐极线精确比较有效 blocker depth 再重算不同 slice。消费端查 T 一次、S 两个相邻极线各一次径向/深度过滤，避免像素内完整积分。

限制：

1. 这是标量 nearest-blocker 深度与可见长度模型，不含完整 RGB 透射阴影或局部灯发光体积，不能直接替代 RTest 当前闭环。
2. 相机旋转/FOV/高度变化更新空气透视，太阳运动更新 SkyView/Aerial-S；不是任意移动下零成本缓存。
3. 真实遮挡变化会整体失效 blocker cache，目前无空间局部失效；动态物体、粒子、alpha 动画可能增加更新。
4. 增加 passes、约 61.5 MiB screen/visibility 资源及在途副本；缓存收益需在静止、旋转、跑图、动态覆盖分别测。

迁移建议：优先评估把 RTest 的均匀介质 T/无局部遮挡的物理散射独立缓存，并将需要遮挡的太阳/月亮/天空/局部体积项放低分辨率 RGB froxel/屏幕积分，深度边缘重建或校正；这是根据其缓存思想提出的 RTest 设计，不是 Prime 已有 RGB froxel 实现。必须保留局部段 L/T 和阴影颜色，避免天空盒、常数雾倍率重新成为大气替代路径。

## 固定 HEAD 源码核查入口

- [最近交点重建、过滤发光复用](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/trace/closest.slang#L451-L530)
- [RGB 可见性查询](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/ray_query.slang#L53-L107)
- [local/global grid 采样与双向 PDF](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/grid_sampling.slang)
- [eta-aware roulette](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/math/transport.slang#L95-L112)
- [实时 transport](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/realtime/transport.slang)
- [BLAS compaction 状态和预算](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/src/compaction.rs)
- [生产 OMM 证明与未知退路](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/docs/opacity-micromaps.md)
- [PT 生命周期与性能证据边界](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/docs/pt-state-design.md)

## 结论

可以借鉴相似机制，但不存在由 Prime 提交数量推算的确定提速百分比。当前最合适的是：收窄跨阴影/续接存活状态、复用同一 receiver 的 BSDF 预计算、避免零贡献阴影查询、按实际瓶颈把体积积分独立缓存或降低积分像素数量；其次处理 GPU AS 同步与发布尖峰，再评估局部 alias 和 BLAS compaction。OMM 当前本机不可用，DLSS/FG 属于另一种内部分辨率/呈现策略，应与同分辨率同预算的 RT 优化单列。

RTest 已有末顶点 break、可选 AOV、源捕获预算不能重复算收益。本轮完成源码审查；未执行 Prime 基准，未修改运行代码、配置或部署。
