# GPU 光线追踪实现检查（2026-10-08）

## 结论及证据边界

主要负担在一次整屏 ray dispatch 内的算法工作量。上一轮 1009×1199、NRD 开启、ReSTIR mode 0 场景的 trace 中位数 31.586 ms，GPU 总计 34.515 ms（91.5%）。该 query 区间包括 BVH 遍历、any-hit、closest-hit、raygen 光照、体积、信号输出，**不能解释为 RT 相交单元独占 31.586 ms**。本轮审阅没有切换游戏配置、修改生产代码或替换 JAR，也没有取得新的 GPU 帧测量。

确认一项正确性风险、两项明确额外工作和若干待硬件计数验证的候选。shader 能编译、GPU busy 98% 都不等于达到硬件峰值。

## P1：透射 any-hit 累乘不具有重复调用安全性

`RayTracingShaderStages.java:410` 执行 `shadowTransmittance *= transmissionFilter` 后忽略命中，以继续穿过玻璃。`VulkanAccelerationResources.java:166` 的 BLAS 创建和 `:298` 的实际 build/update 几何均 flags=0，缺少 `VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR`。

[Khronos 几何标志定义](https://docs.vulkan.org/refpages/latest/refpages/source/VkGeometryFlagBitsKHR.html) 规定该标志保证每个图元仅一次 any-hit；未设置时实现允许重复调用。乘法不是幂等操作：一个过滤率 (0.5,0.8,0.9) 的面若被同一射线重复回调，会从正确的一次过滤变成 (0.25,0.64,0.81)。这会重复压暗/染色透射阴影，并可能增加 shader 工作。

这是源码与规范之间的可移植性缺口，不是已复现的本机驱动故障、性能根因或 Vulkan API 非法调用。证据脚本已验证生产源码缺少唯一调用标志及乘法操作；算术结果保存在 `tmp/gpu-implementation-review/transmission-invocation-check.json`，没有伪称 GPU 重现。

修复应让 BLAS 创建、尺寸查询、BUILD 和 UPDATE 的几何标志一致；可统一使用唯一 any-hit 标志，也可分类几何，但不能只改创建处。该标志可能限制 BVH 构建策略，需复测性能。它不会去重模型自身的重叠面，也不代表不同物理界面只能过滤一次。

## P2：表面/灯光体积的零阴影强度仍追踪可见性

`RayTracingShaderRaygen.java:1992` 的太阳、`:2063` 月亮、`:2150` 天空、`:1238` 发光体可见性都未以 `camera.settings.y == 0` 排除查询，随后再 `mix(1,T,strength)`。发光体 helper 也被局部灯光体积调用。零强度时输出可见性恒为 1，static-only 可见性也为 1，辐射动态差值为零，但查询和材质读取仍执行。

物理大气积分 `:736`、`:762` 已有 shadowStrength!=0 判断，这部分无需重复修正。旧 legacy 大气及所有 helper 使用者需一起审阅。

这在关闭阴影的配置下是确定额外工作；默认 strength=1 时不产生该项收益。当前遮挡标志还影响 NRD 的动态标记，跳过查询需定义零强度下的标记行为并做输出验证，不能直接声称所有 AOV 完全相同。也不能用 epsilon 跳过很小的非零强度，除非明确接受画质变化。

## P2：不透明静态几何未使用硬件 opaque 分类

目前所有三角形几何 flags=0，普通不透明候选也能进入 any-hit。主 any-hit 虽已有无 alpha 早返回，仍有 shader 转换和 metadata 读取；阴影 any-hit 无 alpha/无透射时也读取若干材质字段再返回。

[Khronos opaque 定义](https://docs.vulkan.org/refpages/latest/refpages/source/VkGeometryFlagBitsKHR.html) 允许不透明几何绕过 any-hit。但源码现有 `opaqueTerrain` 不是实际纹理无孔洞的证明，资源包、文字、树叶、AW alpha 和玻璃不能全局强制 opaque。当前一种 BLAS geometry 混合不同材质，分类还需调整组织、缓存键、材质索引和重建一致性。

尤其动态几何不能直接 opaque：any-hit 负责 `shadowDynamicOccluder` 标记和 static-only 排除，shadow closest-hit 当前仅清零 T。跳过 any-hit 会改变动态差值语义。应先分类“实际纹理覆盖已证明、无透射的静态几何”，保留其他几何逻辑。未测 any-hit 占比，无法给收益百分比。

## 执行链和计算预算

正常帧 `recordAccelerationStructuresAndDispatch` 使用 Minecraft frame-owned encoder，追加批量动态 BLAS、TLAS、光追和 FSR/NRD/后处理；`RayTracingVulkanPass.java:3701` 一次 `vkCmdTraceRaysKHR`。命令缓冲区数量、BLAS BUILD 数量、光线数量和 queue submit 次数是不同指标。`:3198` 的即时 submit 在重放路径的条件分支，不是正常每个 BLAS 单独提交。

| 步骤 | 源码及预算 | 检查结果 |
|---|---|---|
| BLAS/TLAS 输入与构建 | VulkanPass:3626 起；批量 BUILD/UPDATE | HOST/compute 输入至构建、BLAS→TLAS、build→trace 依赖已有；不得为减少耗时移除同步。最新 BLAS .149 ms、TLAS .334 ms，当前静止场景次于 trace |
| 主射线及命中 | Raygen:1701；ShaderStages:238/closest-hit | 1 条主射线，材质 7 vec4/triangle；cutout 重构 UV/取 alpha，closest-hit 再取完整材质；缓存收益需要硬件证据 |
| 路径续追 | Raygen:1616/2254/2328 | GI 1–4，最多 2–5 段路径；RR 在 bounce>0 的散射后，已有真实零吞吐终止，保持估计器与 MIS 的对应关系 |
| 太阳 NEE | Raygen:1955 | 主顶点默认 4 次，后续每个顶点 1 次；5 段最多 8 次基础太阳阴影，余弦/太阳能量等条件会减少实际数量 |
| 月亮、面光源、天空 NEE | Raygen:2063/2107/2149 | 月亮每顶点最多 1、面光每个非透射顶点最多 1、天空只主表面；硬件 sky CDF 另有采样射线，不等于场景阴影 |
| 动态阴影差值 | Raygen:1244 | 仅动态遮挡且 guide mode 开启时追 static-only；使用相同 SECONDARY mask，在 any-hit 忽略动态候选，有 mask 分类空间，但当前静态/动态共享 mask，不能直接替换 |
| 相机体积积分 | Raygen:547/2595 | quality=1/2/3 对应 4/8/16 积分点；预算0=所有点，sky residual 强制全部点，太阳与月亮分别追踪。稀疏预算只适用于表面段，不能直接套到 signed sky residual |
| 局部灯光体积 | Raygen:1313 | quality=1/2/3 对应 1/2/4 光源样本；已有可见性精确零筛选后再算 medium/光谱 transport，不能误删 static-only baseline |
| 输出 | Raygen:2749 起 | FSR 颜色/运动/深度/reactive/transparency；NRD guide 关闭时已有裁剪；诊断 uint pixels 仍整屏写 |

### 旧消融测试说明算法优先级

854×1074、NRD 关闭的旧对照中 trace 基线约 19.8 ms：去除全部阴影、GI 全部后续工作、相机体积分别约 5.20/4.74/4.63 ms 边际差；去除面光源、天空 NEE 分别约 2.24/2.74 ms。它们有交集，且动态频率/游戏时刻变化，不能相加，也不能直接套用到最新 NRD 开启场景。详见 [成本报告](ray-tracing-cost-audit-2026-10-07.md)。

同轮完整主命中 3.045 ms，最小主命中约 3.027 ms，说明该视角的材质不是已证明大项；不能泛化至复杂 POM 场景。优先控制阴影、体积、后续顶点的查询/目标函数次数，而不是继续仅削减主命中字段或提交调用。

## ReSTIR 与寄存器/带宽候选

- `RayTracingRestirShader.variant` 在 mode 0 编译时替换 controls/stride 为常量0，并分别裁剪关闭的 DI/suffix 分支。不能仅凭生成 GLSL 很大声称关闭时仍承担完整 ReSTIR 存储/计算。
- DI 对多个新旧候选评估目标函数，再对选中样本做可见性；新候选数量不是同数量阴影射线。复用资格只涵盖静态、粗糙、非透射主表面。
- suffix 最多4个 gather prefix，每个含新后缀和最多5个历史后缀；因此理论上限24次路径求值（条件/历史有效性会降低）。已缓存同 invocation 的相机命中和同 prefix 的第二段命中，并从第一散射后恢复状态，非末次 proposal/prefix 提前返回，不反复写最终 AOV/体积。仍重算后续路径及其 NEE，开启不天然更便宜。
- PathPayload 源码字段为6 vec4+2 uint（104逻辑字节）。这不是驱动分配的寄存器/stack 大小。suffix 保存较大的 prefix 状态，加上阴影调用前后的存活变量，是寄存器压力候选。NRD off 的输出分支是运行时 gate，也不能仅凭 gate 推断寄存器成本完全消失。
- [AMD RGP](https://gpuopen.com/manuals/rgp_manual/events_windows/) 支持查看 RT 事件、寄存器、wave 等信息。本轮没有 VGPR/scratch/active-lanes/RT 单元/缓存计数，不能确认 occupancy、带宽或 RT 单元饱和；SPIR-V 指令数量也不是运行时间。
- 诊断 `result.pixels` 每像素4 B，在1009×1199为4,839,164 B/帧；CPU每120帧只读中心，且 invalidate 全 allocation（VulkanPass:2952）。可缩小诊断结果与读回范围，但没有证据证明这4.84 MB是31.6 ms主因；HOST_COHERENT时失效操作也可能实际无开销。旧 readback 周期采样错位仍在。

## 验证与下一步

已执行：

```
./gradlew accelerationStructureSynchronizationTest rayTracingShaderContractTest restirConditionalMathTest rayTracingFrameTimingContractTest --offline --console=plain
```

BUILD SUCCESSFUL，8秒。覆盖生产 shader 编译、相关数学/材质/信号契约、ReSTIR集成变体、AS访问类别和计时契约。日志 `/tmp/rt-gpu-implementation-review.log`。这些是 CPU/编译级检查，不是 runtime validation-layer、GPU 图像一致性或硬件计数证明；本轮未重跑完整98变体矩阵。

建议先修复透射唯一 any-hit 契约；随后处理零阴影强度下的查询和诊断整屏写，再以同场景数据判断静态 opaque 分类、稀疏体积估计和 shader 状态拆分。对画质保持优化要同时验证 RGB 透射、动态 residual、sky signed residual、MIS与 NRD/FSR 输入，避免以删除通道换取表面速度。

## 本轮后续修复

已实现唯一any-hit契约、太阳/月亮/天空可见性提前与精确零裁剪、零阴影强度查询跳过、中心诊断单uint和独立读回事件计时。测试、98模块编译/校验、实例部署和测量限制见 [GPU传输优化记录](gpu-transport-optimization-2026-10-08.md)。静态opaque分类和GPU硬件计数仍未完成，不能宣称全局最优或已测得帧率提升。
