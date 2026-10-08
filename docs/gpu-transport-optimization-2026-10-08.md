# GPU 光照传输与诊断数据优化（2026-10-08，已撤回）

**部署状态：已因实际GPU重置撤回，当前实例和源码恢复至此前CPU模型合并版。下文是撤回版本的实现记录，不能作为当前已上线效果。** 见 [故障与回退记录](gpu-reset-rollback-2026-10-08.md)。

## 优化依据

[实现检查](gpu-ray-tracing-implementation-review-2026-10-08.md) 指向一次 ray dispatch 内的阴影、光照、体积、路径求值，而不是正常帧重复 queue submit。此前 trace 为 GPU 总时间约91.5%，旧消融的阴影、体积、GI、天空 NEE 有较大边际变化。本轮以保持采样、MIS、RGB透射和动态残差为约束，先消除不产生有效辐射的计算与跨查询状态，再减少无用诊断搬运。

## 已实现

### 1. 可见性查询先完成，BSDF 与天空辐射延后

太阳、月亮、天空 NEE 原先计算 BSDF，动态遮挡时再发 static-only 阴影查询，BSDF/天空辐射状态跨越该查询存活。现在先保存实际动态遮挡标志，完成实际和 static-only 查询；随后仅在任一通道非精确零时计算 BSDF/MIS/天空纹理与LUT。

两个通道均零时，直接光及 signed dynamic residual 都为零，跳过后续计算。只检查实际可见性会误删实体遮挡的静态基线，故必须同时检查。保留已捕获的动态标记，避免 static query 重置 shadow payload 后丢失原标记。不使用 epsilon，不跳过非零 RGB 分量或微弱透射。此前面光源和物理局部灯光体积已采用此顺序，本轮补齐太阳/月亮/天空。

正常 strength=1 场景也能命中该优化；遮挡比例低时收益较少。shader 状态生命周期缩短不等于已证明实际 VGPR 更少，需驱动计数确认。未改源能量、PDF、MIS、Sobol维度、路径预算、RR和体积采样预算。

### 2. 零阴影强度不发无效可见性射线

太阳、月亮、天空、发光体 helper、static-only helper、legacy太阳/月亮体积可见性在 shadowStrength=0 时跳过查询；阴影 payload 初始化保持 T=1、dynamic=0，直射/体积辐射仍正常计算。物理大气已有 shadowStrength判断，本轮保留。诊断模式里直接展示原始遮挡的探针仍追踪，避免改变诊断含义。

零强度下，mix(1,T,0)=1，动态辐射差值为零；动态阴影标记不再由无效查询置位。此配置下 RGB 辐射保持，标记/历史行为可能更稳定，但仍需游戏验证。默认strength=1的收益来自第1项，不来自该分支。

### 3. 透射阴影唯一 any-hit 契约

BLAS 尺寸查询/创建及实际 BUILD/UPDATE 两处 triangle geometry统一设置 `VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR`，TLAS instance geometry不改。避免同一图元的透射乘法因规范允许的重复回调被重复应用。

这是一项正确性修复，不是声称驱动以前实际重复回调。若此前发生重复过滤，玻璃阴影会恢复正确亮度，属于预期修正。该标志可能约束驱动BVH构建策略，必须实测收益/代价，不能把它当作免费提速。保留 any-hit、alpha孔洞、有色过滤和动态排除，没有全局强制opaque。

### 4. 诊断结果从整屏缩为一个 uint

显示一直使用FSR图像；CPU仅每120帧读中心像素。raygen现在仅中心 invocation写 `result.pixels[0]`，buffer和descriptor range为4字节；CPU从offset0读，VMA invalidate只请求4字节，并将该映射设为无host写入，close不再flush。

1009×1199示例：逻辑diagnostic写量由4,839,164 B/帧降至4 B/帧；不是GPU总写量、实际总线流量或31.6 ms trace节省量。VMA按nonCoherentAtomSize处理对齐；HOST_COHERENT时cache操作可无实际成本。allocation与读取范围一致，仍在fence完成后读回。NRD/FSR颜色、深度、motion和AOV输出照常。

新增 `RTest center_readback retired_frame=... current_frame=... readback_us=...` 直接记录读回事件，不再仅依赖当前帧周期日志，避免之前采样错位误判读回一直为0。原总体CPU日志仍是采样帧值，不是区间分位统计。

## 验证

新增 `gpuTransportOptimizationTest` 先在旧BLAS flags契约上失败，再在旧天空调度顺序上失败，修复后通过。检查真实生产创建/build flags、太阳/月亮/天空查询顺序、diagnostic生产与消费者一致性及只读映射行为。另运行固定种子100000组有限RGB代数比较，覆盖完全遮挡、实际零/静态基线非零、彩色/部分透射、零/部分/完整shadowStrength、guide开启/关闭及signed foliage响应。该数值检查没有执行真实GPU BSDF，不能代替图像对照。

测试命令：

```
./gradlew gpuTransportOptimizationTest accelerationStructureSynchronizationTest rayTracingShaderContractTest restirConditionalMathTest lightingLogicRepairTest rayTracingFrameTimingContractTest rayTracingCostAuditTest jar --offline --console=plain
```

最终focused回归及jar成功日志 `/tmp/rt-gpu-transport-final.log`。98模块编译成功保存在完整矩阵日志；该轮整体失败来自数值测试中对“baseline-only”随机分支数量的过高门槛（实际942组），随后仅调整为至少500组并复跑所有focused回归。实测3820组完全遮挡、942组静态基线独存场景，全部等价断言通过；生产代码未因此更改。

记录：`/tmp/rt-gpu-transport-red.log`、`/tmp/rt-gpu-sky-red.log`、`/tmp/rt-gpu-transport-tests.log`。完整审计矩阵覆盖14 profiles，requested mode=0/1/3、legacy/physical atmosphere，以及closest-hit，共98 SPIR-V模块；ReSTIR数学/集成测试另覆盖其模式变体。本轮为修改后的矩阵重新编译并独立用spirv-val验证。

## 局限及下一步

本轮不宣称已找到全局最优，也不给没有同场景对照的帧率百分比。更大收益候选是静态实际不透明几何分类、体积稀疏估计与payload/路径分类，但现有证据不足以保证画质和吞吐同时改进。默认体积shadow budget=0仍代表全部积分点，不能未经验证变更；signed sky residual不能直接套稀疏采样。

后续测量固定相机、分辨率、天气/时间、场景和NRD/ReSTIR设置，分别比较新旧trace、GPU总时间、频率、功耗与图像，并注意BVH flags更改可能影响遍历成本。需要取得VGPR/scratch/active-lanes/缓存/RT计数后才选择进一步拆分。未新增当前游戏内性能或图像对照结果。

## 部署

已原子替换实例 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，构建与实例SHA-256一致：`918c0c4c00600adf817d33f98ec7e0e088bd8f1fea6f31b5a450be4d2e1299c6`。

上一版本备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261008-004941-gpu-transport/rtest-0.1.0.jar`。修改前源码保存在 `tmp/gpu-transport-before/`。需重启加载新pipeline；当前最后游戏日志00:31:16为正常退出，未取得新版本运行数据。


## 部分优化恢复

目前实例为版本A `d9b85824c195ffb8e24cff50943188b747497e5bc834812c6a07aa74f6b204f7`：保留主要光照计算优化和中心稀疏写，恢复原buffer尺寸/中心读取位置与原AS flags。移动测试134组GPU计时、204次地形发布未观察到故障。原4字节allocation与唯一any-hit标志仍撤回。参见 [根因调查实测](gpu-reset-root-cause-investigation-2026-10-08.md)。这是观察窗口内可运行的版本，不是已确认完整根因或永久稳定。
