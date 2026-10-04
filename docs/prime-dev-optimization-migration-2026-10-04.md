# Prime dev 优化迁移评估（2026-10-04）

## 范围与结论

用户指定 dev 提交历史。独立只读 clone `/tmp/prime-dev-upstream-20261004`，检查时 dev HEAD 固定为 `dc708f52c4e4f68b4a7a148e73e3ff224142003c`（2026-10-03）。当前 dev 已有 Rust 场景/Vulkan核心和Slang inline ray-query compute管线，不是本项目 Java+GLSL vkCmdTraceRaysKHR 的同构实现；不能直接cherry-pick后期待相同速度。

**能迁移多项机制，但不能承诺相同百分比提升。** 本地最近实时6个样本RT均值42.51ms、占已测GPU时间96.69%，后处理1.40ms。优先针对RT本身；当前客户端已退出，无固定场景A/B。本轮只修改研究文档。

## 提交与本地对应

|上游提交|实际机制|本地结论|
|---|---|---|
|[3292bbf](https://github.com/bWFuanVzYWth/prime/commit/3292bbf81acd169e8390fc5a82a75b2121cd5a04)|先取得太阳源radiance，全部零时不查阴影；普通opaque blocker首命中终止|零源早退可迁移。RTest太阳在RGB阴影之后才查询sourceT，局部灯在阴影之后才evaluateEmitter；opaque首命中终止已有。需要保留alpha孔洞、RGB透射和动态历史语义。|
|[aacf17c](https://github.com/bWFuanVzYWth/prime/commit/aacf17c55c11a131a4d890c2be7cf7de30307cc3)|提前消费ray-query getter，结束query活跃区间；复用过滤后的specular alpha算发光|RTest不是inline query，因此getter改法不能照搬；可借鉴短作用域和已解析纹理值复用。closest-hit已samplePbr，不能另造重复读取来“优化”。|
|[8026f75](https://github.com/bWFuanVzYWth/prime/commit/8026f7554dfd034f5d3a156bb9e450f07cfe6af6)、[29181d3](https://github.com/bWFuanVzYWth/prime/commit/29181d31e188e12c676b6c950922be346dd4db67)|窄PT顶点状态、分离查询前后消费、末顶点跳过采样和RR；减少纹理owner工作|本地长生命周期radiance/AOV/primaryAreaLight等仍跨路径循环与嵌套shadow。可先缩小状态和编译变体，不改算法。末顶点break已有；同一evaluateBsdf内共用energy已有，跨8次太阳和moon/sky/area仍可能复用，但必须检查寄存器/溢出，缓存不必然更快。|
|[45253ae](https://github.com/bWFuanVzYWth/prime/commit/45253ae344363edf055b363e493fbb8157d844dd)|K1 primary及delta前缀、K2 transport、post三阶段；两个compute megakernel|可获得相似的状态生命周期分离。不是逐bounce wavefront或SER。RTest需定义新的阶段资源、depth/hit数据及同步；额外全屏写读可能抵消寄存器收益。|
|[2452914](https://github.com/bWFuanVzYWth/prime/commit/24529142e2afaca6e2986e70b7ff7974225b0f31)|cell16/R24，75%局部+25%全局alias光采样，稳定身份与增量更新|能迁移到太阳之外的局部emitter NEE和体积点采样。完整混合PDF必须同时用于选择、反向MIS和体积评价；局部alias无支持的远灯通过global分量保留。不是全流程O(1)：含查格和reversePDF。当前783 emitters，收益须测，不解决太阳/天空阴影成本。|
|[ee21aed](https://github.com/bWFuanVzYWth/prime/commit/ee21aedf5549c3faf4b32cef8269cdaeac8ab0ca)|每帧terrain构建预算、独立chunk管理|本地已有CPU分帧section capture预算，仍有增量AS提交后立即fence等待和发布尖峰。迁移预算要覆盖GPU BLAS/TLAS、上传及退休，可改善移动卡顿，不能直接归因于42ms trace成本。|
|[2213664](https://github.com/bWFuanVzYWth/prime/commit/2213664e394484f3aaa45d4aefbbb1fe20c28258)|BLAS compaction、显示与Streamline frame pipeline|BLAS compaction原则可迁移，先获得compact size、复制到新AS、替换TLAS地址、等待GPU末消费者后退休旧AS。主要直接效果是显存占用，遍历加速不确定。显示/Streamline对当前RT主瓶颈优先级较低，不能假设AMD能使用DLSS路径。|
|[a1d13a2](https://github.com/bWFuanVzYWth/prime/commit/a1d13a2eb34e96f08a204fb63dda308849b14b40)|资源代次共享cutout opacity micromaps，移动时不重复烘焙|机制适合草叶及多条阴影查询，但本机当前驱动不暴露任何micromap扩展，暂不可落地。Prime阈值0.1，本地0.5；动画、UV、透射光学边界都必须重新证明，不能搬mask数据。|
|[393ddc6](https://github.com/bWFuanVzYWth/prime/commit/393ddc63b9145b48245f793b94ae5e6a0a0613dd)|物理大气的epipolar L/T、shadow-column共享及缓存|这是既有大气架构建设，不只是近期小优化。对本地每像素8步积分最有结构性潜力；必须把scalar shadow改造成支持RGB透明阴影，并补月光/局部体积来源、遮挡变更失效。8026仅减少epipolar采样的重复dimensions/range计算，不能直接消掉本地积分。|

## 不应当直接当作提速的提交

[53fa71d](https://github.com/bWFuanVzYWth/prime/commit/53fa71db863ca415c26ed6041b00be714508ed9a)加入eta-aware roulette并把默认路径预算设为12个顶点。它涉及折射路径的估计器与采样质量，预算增大可能增加工作，不能当成纯速度优化。RTest的透明传输/throughput约定与上游完整BSDF并不完全相同，应先核对eta平方权重和概率再迁移roulette；不应一起把本地GI预算改成12。

## 本机能力检查

通过本机 `libvulkan.so.1` 创建只读枚举实例并枚举physical-device扩展，device0支持`VK_KHR_ray_tracing_pipeline`、`VK_KHR_ray_query`，包含micromap的扩展为空。未创建设备、改变游戏设置或部署。这里只证明当前驱动暴露情况，不推断硬件永远不支持OMM。

## 建议顺序

1. **较小的等价改动**：源能量严格为零时跳过太阳/月光/局部阴影；缩短primary与材质临时值跨trace存活范围。必要时进一步按physical/legacy、NRD on/off编译特化。保留负值散射修正语义，不能把所有`<=0`都误删为无贡献；physical颜色变换可能含负通道，应以整个源确实零/明确无能量为判据。
2. **独立测量**：固定视角、时间、天气、分辨率与采样身份，对照GPU时间、编译后的寄存器/溢出、线性HDR及NRD/FSR输入。减少变量源码数量不等于减少物理寄存器数。
3. **较大收益候选**：将相机段体积积分/遮挡查询从surface megakernel分离并建立共享缓存；保留RGB T、太阳/月光、局部source及动态遮挡历史。相机段L/T在降噪后合成，避免二次T或双重L。不能把Prime scalar shadow替代彩色玻璃透射。
4. **移动帧尖峰**：GPU构建预算、不可变frame-owned更新资源和基于实际完成的退休。不能直接删fence；当前buffer/descriptor/query/FSR历史复用依赖它。
5. **局部灯采样与BLAS compact**：分别测大量emitter场景与显存/遍历收益后实施。OMM等待支持的驱动或另行研究几何coverage方案。

降低GI/阴影样本或分辨率可作归因实验，不能当成同画质优化。上游提交验证与性能描述只能作为其对应环境证据，没有可直接套用到RTest的固定场景加速倍数。

## Owning源码

上游固定HEAD下：
- [`ray_query.slang`](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/ray_query.slang)：可见性/透射与光源查询。
- [`trace/closest.slang`](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/trace/closest.slang)：HEAD的最近交点与过滤发光实现（已从早期ray_query移出）。
- [`transport.slang`](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/crates/prime-vulkan/shaders/transport.slang)：NEE、末顶点、roulette。
- [`pt-state-design.md`](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/docs/pt-state-design.md)：K1/K2和状态消费边界。
- [`opacity-micromaps.md`](https://github.com/bWFuanVzYWth/prime/blob/dc708f52c4e4f68b4a7a148e73e3ff224142003c/docs/opacity-micromaps.md)：阈值、动画、支持证明与退路。

本地对应：RayTracingShaderRaygen.java（太阳循环、evaluateAreaLight、evaluateBsdf、末顶点break、integratePhysicalAtmosphereSegment、samplePhysicalVolumeEmitter）；RayTracingShaderStages.java（RGB shadow any-hit）；RayTracingVulkanPass.java（AS build/trace/fence）；RayTracingProbe.java（CPU捕获预算）；RayTracingLightTree.java（power tree及复用）。
