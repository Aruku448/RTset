# ReSTIR 论文续读与适用性核查（2026-10-11）

> **文档状态：已归档；已解决项见下表/说明** · 归档日期：2026-10-11。
> 当前行为见 [实现说明](../../restir-implementation.md) 与 [后续实施记录](../../restir-follow-up-implementation-2026-10-11.md)。[归档索引](README.md)

## 审计事项关闭状态（2026-10-11）

| 审计事项 | 结论 |
|---|---|
| hit distance 被 UCW 加权、DI 距离回退 | **已解决：** 按独立 prefix/lobe 记录几何距离；缺失 guide 保持 0 |
| 主表面 sun/moon/sky NEE 重复评价 | **已解决：** 符合条件的主表面在像素 invocation 内共享 |
| resampling 中间乘积/累加溢出风险 | **已实施防护：** 指数缩放累加；最终 float UCW 不可表示时 reservoir 失效，任意 HDR 溢出仍未全面解决 |
| DI/GI 历史失效条件绑定 | **已解决：** 独立有效位，保留 generation、原点和全局 reset 门控 |
| 后续前缀遍历全部 donor 的可选开销 | **已实现：** 单 donor 概率补偿模式，默认关闭，可能增加方差 |
| donor 读取/接受/group 数与数值状态诊断 | **部分完成：** spare lanes 供 buffer 检查；无 CPU readback、运行统计或全套相关性指标 |
| 世界空间搜索、duplication feedback、hybrid shift、PG、reciprocal spatial pass | **未实施：** 保留为研究方向 |
| 固定场景 GPU 性能与画质对照 | **未完成：** 编译通过不等于运行收益已验证 |

下文源码行号、“当前缺陷”和“建议顺序”保留审计时点语境；已关闭项不应重复作为待办。当前未完成事项汇总见 [归档索引](README.md)。

## 原始记录（保留当时语境）

本轮只做论文精读和实现核查，不修改生产代码、不新增或运行测试。本文的“论文结果”来自一手正文；“项目推断”是对当前实现的判断，尚未经过 GPU 性能或等时间画质验证。

本轮最优先的发现是 **NRD 命中距离被后缀 UCW 加权**，其次是主表面太阳/月亮/天空的重复工作、权重乘积的数值风险，以及 DI/GI 历史失效条件过度绑定。后文“当前项目代码审计”给出源码位置、适用前提与实施顺序。

## 阅读来源

- Lin、Kettunen、Wyman，**ReSTIR PT Enhanced**，I3D 2026：已读 [正文](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced.pdf) §§3–6、7–8，以及 [补充材料](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced_supplemental.pdf) §§1–8；正文 19 页、补充 13 页。
- Zeng 等，**ReSTIR PG**，SIGGRAPH Asia 2025：已读 [官方页](https://research.nvidia.com/labs/rtr/publication/zeng2025restirpg/)和[作者提供的同篇正文](https://cseweb.ucsd.edu/~ravir/zhengsiga.pdf) §§3.3、4–6，11 页。NVIDIA 版 PDF 体积较大，采用作者机构站点的压缩版本。
- Hedstrom 等，**Stochastic Pairwise MIS**，Eurographics 2026：已读[正文](https://research.nvidia.com/labs/rtr/publication/hedstrom2026stochastic/hedstrom2026stochastic.pdf) §§3–5、6.3–6.5、7，12 页。
- 对照 [NVIDIA RTXDI 的 ReSTIR PT 集成文档](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/RestirPT.md)，用于区分论文 hybrid shift 与当前 replay 的数据要求。
- 同时复核 [Conditional RIS](https://research.nvidia.com/labs/rtr/publication/kettunen2023conditional/) §§4.1、5、7–9，以及 [GRIS 正文与补充](https://graphics.cs.utah.edu/research/projects/gris/sig22_GRIS.pdf) §§5.4–5.7、6 和相关性讨论；去噪输入以项目锁定的 [NRD v4.17.3](https://raw.githubusercontent.com/NVIDIA-RTX/NRD/v4.17.3/README.md) 约定为准。

当前实现边界：直接光在光源面积域做 RIS；后缀 reservoir 保存 seed 和 UCW，并用相同随机 tape 在当前支持前缀和当前场景中完整重放。后缀 shift 为种子域恒等映射，没有几何重连，也没有 pairwise MIS 的反向 shift。当前已在单次前缀内合并同 seed 的重复评价，保持原始来源数和 UCW 总质量；已使用向量 gather 着色和首表面 area-light 缓存。详见[上一轮实现说明](restir-paper-optimization-2026-10-11.md)。

## 1. Reciprocal neighbor selection：当前没有可直接消除的反向 shift

**论文结果。** Enhanced §3 以互选配对复用 pairwise MIS 的双向 shift；§7 的 pre-pass 仍有开销，整段 spatial pass 没有恰好减半。[正文 §§3、7](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced.pdf)

**项目推断。** 当前 uniform MIS 利用全支持种子域，不计算 A→B 的逆向目标；且空间候选读取上一帧 bank，目的端是本帧几何。把随机邻居换成配对纹理，不会自动让两个 invocation 的 replay 成为可共享的同一计算。要移植论文的省算机制，需要当前帧 spatial pass、双向 shift 结果缓冲与同步，并改变 reservoir 管线；以现有 2 个空间候选的预算，这不是优先项。邻居距离分布可以另行调整，但其收益应独立于“互选减半”结论评价。

## 2. Duplication map：适合诊断相关性，论文的反馈控制明确有偏

**论文结果。** Enhanced §5 以 17×17 邻域的重复 seed 比例 D 调整下一帧 temporal confidence cap：

```
cCap = lerp(cCapDefault, cCapMin, pow(D, alpha))
```

论文使用 `cCapMin=1`、`alpha=0.1`，承认样本相关 confidence 破坏 MIS 权重分割；Kitchen 示例偏差为 3.25%。[正文 §§5、8](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced.pdf)

**项目推断。** 现在的相同 tape 合并只节省函数评价，不改变候选质量，不能消除跨像素相关性。低风险的下一步是增加诊断：历史 seed 重复率、唯一 seed 数、fresh 入选率、历史年龄及回退率。先证明何处存在拖尾/块状相关性，再决定是否加入明确标注的有偏实时模式。当前没有论文的累计 confidence 字段，不能把 UCW 当作 confidence 随意截断或降低；UCW 控制积分质量。

即使使用完全无偏的随机 donor 子采样，也只能降低评价成本，并不天然去相关。多个来源复制同 seed 时，随机挑其中一个仍会得到同一个后缀。

## 3. Footprint reconnection：必须先有完整 hybrid shift

**论文结果。** Enhanced §4 Eq.5 的重连条件为：

```
min(1 / (p_prev * G_forward), 1 / (p_rc * G_reverse)) >= (c / 100) * R_primary^2
```

默认 `c=0.02`，仍有粗糙度防护。[正文 §4](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced.pdf) 补充推导假设局部平面、位移有界、密度平滑及 glossy PDF 近似互易；使用 marginal PDF 替代 lobe 条件 PDF 还可能过早重连。[补充 §§2–4](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced_supplemental.pdf)

**项目推断。** 当前每个借用 seed 都完整重放，Jacobian=1，加入 footprint reject 本身不会省掉路径后半段。真正移植需要保存重连顶点实例/primitive/重心坐标、方向、入射辐射、重连深度、相关 PDF/Jacobian 数据，完成双向可逆检查和新可见性；还要重新证明 canonical 支持域和 MIS。此时不能继续无条件沿用“后续前缀只靠历史，恒等 Jacobian 为 1”的分支。

Enhanced 的 reservoir 从 88 B 压到 64 B（补充 §8），主要压缩重连数据。当前每模式每像素每 bank 只有 32 B，已是不同的数据模型；该压缩比例不能作为当前实现的直接节省量。

## 4. 后续 gather 随机只评价一个 donor：有直接的随机 MIS 依据

**论文结果。** Stochastic Pairwise MIS §3.1 Eq.13–15 在全部输入样本及 UCW 已给定的条件下，用新的抽样随机数估计确定性 MIS 权重。选择概率 P(i) 可以依赖输入样本及 UCW；必须用出现次数 `K_i / (N_tilde * P(i))` 补偿，并让 P(i) 覆盖原 MIS 的所有正质量。论文 §4 再把一般公式专门化为 pairwise MIS。[正文 §§3.1、4](https://research.nvidia.com/labs/rtr/publication/hedstrom2026stochastic/hedstrom2026stochastic.pdf)

**项目推断及具体公式。** 对当前后续前缀、n 个完整支持历史来源，原估计为

```
F = sum_j W_j * f(u_j) / n
```

抽 `J ~ q` 后可改为

```
F_one = W_J * f(u_J) / (n * q_J)
```

条件于全部来源，`E_J[F_one] = F`。均匀抽原始来源时 `q_J=1/n`，结果就是 `W_J*f(u_J)`，每个后续前缀只需要一次 replay。若已把相同 seed 合成组 g、组内总 UCW 为 U_g，抽组概率为 q_g，则使用 `U_g*f(seed_g)/(n*q_g)`；n 必须仍是原始来源数。按该组的原始来源数 k_g 选组（q_g=k_g/n）时，贡献是 `(U_g/k_g)*f(seed_g)`。

适用条件：维持完整种子支持域；精确知道实际 q；新增抽样随机数有正确条件分布；不因当前 suffix 评价结果偷偷删选来源；首前缀仍负责 fresh 和 reservoir 更新；n=0 保留 fresh 回退。q 可以依赖已知的旧样本/权重，但概率为零的来源必须有严格的零质量证明。

风险是额外的选源方差。论文 §4.3 提醒少量候选会产生 dark pepper noise；其将非 canonical confidence 缩成 `N_tilde/M` 的办法依赖 pairwise MIS 公式，**不能把这个系数直接乘到本项目 uniform UCW 上**。建议作为可选择的速度档，与完整 gather 做等时间噪声比较，而非直接替换默认质量档。[正文 §§4.3、6.3、6.5、7](https://research.nvidia.com/labs/rtr/publication/hedstrom2026stochastic/hedstrom2026stochastic.pdf)

## 5. ReSTIR PG：能改善候选，但目前的数据和路径语义不匹配

**论文结果。** PG 用最终选中路径的每跳几何/材质/方向记录，无辐射权重地训练四个 vMF lobe，再以 0.5 概率混合 BSDF 与 guide 并做 MIS。其方向分布推导假设 ReSTIR 接近贡献分布，glossy 的方向边缘化是近似。[正文 §§3.3、4、6](https://cseweb.ucsd.edu/~ravir/zhengsiga.pdf)

§5 的存储并不轻：1080p 路径记录约 102.8 MB/每跳；每个有效 cell 的四个 lobe 和统计量为 152 B，示例 scene 总量约 5–76 MB。论文关闭 RR、按场景设最大跳数；这与当前有 RR 的 integrator 不同。[正文 §5](https://cseweb.ucsd.edu/~ravir/zhengsiga.pdf)

**项目推断。** 当前 reservoir 只保存 seed+UCW，一条 tape 的目标还聚合多个顶点的 NEE/发光等贡献；它不是 PG 存储的单条、被选择的全光路径。直接把所有重放中的方向等权 splat，不能套用 PG 的理想分布推导。若未来尝试，宜先在 diffuse、有限跳数的小范围做候选引导，先明确训练样本的选择语义和存储预算，再联动修改 mixture PDF、BSDF/NEE MIS、delta/透射处理。fresh 与 replay 必须使用当前帧内一致的 guide 模型和正确 PDF。恒等 seed replay 可以重新执行确定性的当前 guide 映射，但历史分布与当前 guide 的差异可能降低复用效率，不能保证论文中的改善幅度。

## 6. RR、数值权重和已有优化的边界

- **RR 不能只在 borrowed replay 中关闭。** Enhanced §6.2.4、补充 §6 先从 PSS 去掉 RR 维度，再把初始源密度改为生存概率乘积 `p(u)=product(q_i)`。当前 tape integrand 包含 RR 决策及生存补偿，fresh/replay 同一 seed 必须表示同一函数。只关闭借用重放的 RR 会破坏现有恒等映射证明。[补充 §6](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced_supplemental.pdf)
- **颜色噪声：当前已使用论文同方向做法。** Enhanced §6.3 以向量加权和着色、标量 target 更新下一帧 reservoir；当前 gather 正在累计每个 suffix 的 RGB/AOV。除非减少候选评价改变成本，不能把这一点再次算作尚未获得的优化。[正文 §6.3](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/lin2026restirptenhanced.pdf)
- **浮点防溢出需要独立设计。** Enhanced §6.2.1/补充 §8 描述等价分支化简、压缩及预乘 Jacobian 项，没有给出任意高 UCW 下的通用 resampling 防溢出算法。对本项目，`target>=1e-6` 只保证支持域，不保证 `target*W`、多候选 sum、最终 UCW 或 RGB 贡献有限。应分别核查乘积、和与最终除法；若要保留数学质量，可研究公因子缩放/指数分离的 reservoir 累加。单纯删掉溢出来源、截断大权重或缩小它们不能称为无偏修复。此项是工程推断，不是论文已证明方案。
- **主表面其余 NEE 可以继续核查共享。** 当前 area cache 的依据是同一 invocation 中不随 gather prefix 改变的采样维度和几何。若太阳、月亮、天空也满足相同输入与随机维度，可共享其 BSDF/透明可见性计算；需要同时保留动态阴影差值、AOV 和 MIS 状态。这是项目级消除重复计算，不依赖 Enhanced 的 reciprocal pairing。

## 7. 保留 suffix 历史：先分清“旧场景引用”与“旧提案分布”

**项目推断，尚未完成条件证明。** 当前 seed-only reservoir 不保存 emitter/primitive 引用，重放访问的是当前场景与当前 light tree。旧 UCW 若仍对同一个完整种子域有效，则场景更新只是把被积分函数换为当前 F；从这一点看，DI emitter 重编号不应自动等同于 suffix 种子域失效。可以研究把 DIRECT/SUFFIX 的 history 有效性分开。

但实施前须检查：随机维度布局和域不变；旧 UCW 的条件语义不被当前 sample-dependent 选源破坏；世界原点/坐标重置后支持几何不被错误比较；旧 bank 只读且版本匹配；新旧场景的映射变化未引入部分支持域；camera reset/disocclusion 有明确回退。旧 proposal 的效率可能很差，仍需年龄、重复率和响应速度指标。论文的 GRIS/条件 UCW 原理支持“提案分布不同可以复用”的研究方向，并没有直接证明本项目所有 geometry 发布都能保留历史。

## 建议顺序

1. 先检查 estimator 与去噪器 AOV 契约，以及源质量和数值范围；这些影响正确性。
2. 消除主表面其它 NEE 的重复评价，并把 DI 与 suffix 的 history 失效条件独立审计。
3. 将后续前缀单 donor 随机 MIS 作为可选速度档，保留完整 gather 比较。
4. 加 duplication 诊断，再讨论相关性控制是否接受偏差。
5. hybrid reconnection、当前帧 spatial pass 和 PG 属于更大的架构改造；先明确收益瓶颈、存储和支持域证明。

论文报告的性能与画质针对其特定 baseline、场景、GPU 和完整算法组合，不能视为本项目可获得的收益。

## 当前项目代码审计与实施顺序

本节对照 2026-10-11 工作区中的随机 tape 后缀实现。它是代码审计和后续方案，**本次没有修改渲染实现，也没有运行构建、测试或游戏基准**。上一轮已有的 tape 合并、后续前缀减少 canonical 和主表面面光源缓存仍保留。

### 优先级概览

| 顺序 | 项目 | 证据与性质 | 建议 |
|---|---|---|---|
| 1 | NRD hit distance 被 UCW 加权 | 确认的输入约定冲突 | 先修正几何引导，避免把去噪不稳定误判为采样不足 |
| 2 | 主表面 sun / moon / sky 重复计算 | 确认的重复工作 | 当前像素内共享一次主表面照明及 MIS 元数据 |
| 3 | 非有限 resampling 权重 | 确认缺少乘积/累加防护，尚未观测实际溢出 | 先记录范围，再设计保持归一化的数值缩放 |
| 4 | DI 与 GI 共用历史失效条件 | 确认存在过宽失效；保留 GI 是基于种子域的推导 | 拆分有效位，保留全局期限与必要 reset |
| 5 | 后续前缀遍历全部 donor | 可以保持条件期望的随机子采样方案 | 作为质量档位，比较等时间去噪质量 |
| 6 | 支持前缀搜索局限于主表面屏幕邻域 | 确认的搜索限制，实际损失未量化 | 先统计命中率，再考虑世界空间索引 |

### 1. NRD 命中距离必须与辐射亮度估计分开

位置：`RayTracingShaderRaygen.java:2362–2388、2411–2412、2833–2883`。

当前 `gatherWeight = sourceUCW / (K × prefixCount)` 同时乘在 radiance 和 `diffuseHitDistance / specularHitDistance` 上；`K` 为历史来源数加上存在时的 canonical 来源。单个前缀的各次后缀 replay 共享首个间接交点，因此距离本来都是同一个几何量 `d`，在最终前缀平均之前却变成：

```text
guide_for_prefix = d × sum(sourceUCW) / K
```

例如某个 borrowed-only 前缀的历史 UCW 都为 4，该前缀的距离贡献会变成正常值的 4 倍；即使权重归一化因子在期望上为 1，也不满足每帧几何引导的约定。之后 `nrd_motion.comp:76–83` 只是归一化和截断距离，没有去除 UCW 的影响。原始 specular 距离还会流向 RR 输出分支，但这里没有据此宣称验证过 RR 接口。

项目锁定 NRD **v4.17.3**。该版本要求 hit distance 不按 PDF/BRDF 做辐射亮度式补偿；多路径 diffuse guide 按有效 diffuse 路径计数，specular 则使用专门聚合方法。对应版本的 helper 当前取**最小正距离**，所有输入均为零时输出零，不能直接改成 specular 算术均值。[版本文档](https://raw.githubusercontent.com/NVIDIA-RTX/NRD/v4.17.3/README.md)、[版本 helper 源码](https://raw.githubusercontent.com/NVIDIA-RTX/NRD/v4.17.3/Shaders/NRD.hlsli)

实施要点：

- 每个独立主表面 scatter 前缀记录一次首个间接 hit distance，不重复按 replay 数计数，也不乘历史 UCW。
- diffuse 用有效 lobe 的路径数聚合；specular 使用上述版本 helper 的等价 GLSL 逻辑。
- 保留未采到对应 lobe 时的零值。当前 `primaryDiffuseShare` 是连续 BSDF 分量拆分，需要一起审计 lobe 接受规则，不能简单把每个距离都填写到两个通道。
- 另外审计 `:2866–2869` 的直接光距离回退：选中的 DI 发光体距离未必适合作为 specular 几何 guide。

这是优先级最高的正确性问题；尚未做画面对照，不能把具体拖影或亮暗异常直接归因于它。

### 2. 继续共享主表面照明

位置：`RayTracingShaderRaygen.java:1927–2046` 的 receiver 准备与太阳 NEE，`:2047–2094` 的月亮 NEE，`:2153–2220` 的天空 NEE。已有 `AreaDirectSplit` 缓存仅覆盖 `:2107–2124`。

不同 gather 前缀的主表面位置、材质、视向及上述 NEE 随机域相同；只有 continuation 的 `scatterBase.pathIndex` 在 `:2272` 改为前缀编号。因此这些查询目前重复计算相同 BSDF、遮挡、RGB 透过率和静态/动态阴影差值。

建议先在可复用的不透明前缀上共享主表面 sun / moon / sky 结果，再考虑把主表面照明阶段与多个 continuation 分开。缓存必须包含 diffuse/specular 分量、带符号的动态阴影差值、直接距离和后续 MIS 所需的 NEE 样本数/启用标志，不能只缓存最终 RGB。

若前缀数为 `P`、主表面太阳采样数为 `S`，其太阳采样循环可从 `P×S` 次变成 `S` 次；月亮、天空可从 `P` 次变成一次。这是代码工作量上限，实际发射 shadow ray 还受各类门控影响，**不是整帧提速比例**。当前 prefix 状态已经很大，新增 cache 必须关注寄存器、spill 和 continuation 存储。

### 3. 有限输入不保证权重乘积和累加有限

位置：DI 在 `RayTracingRestirShader.java:225` 检查旧 UCW 有限，但 `:234–235` 的 `oldTarget × oldUCW` 和累加，以及 `:243` 的最终 UCW，没有相同防护。suffix 在 `RayTracingShaderRaygen.java:2371–2372` 也存在 `target × sourceUCW × MIS` 与累加；tape 合并时的有限检查只覆盖了 UCW 相加。

目标下限 `1e-6` 避免零支持，并不提供足以保证浮点安全的权重上界。GRIS §§5.4–5.7 还分别要求控制贡献/权重范围和相关性；不能从正目标或恒等 shift 推导整个渲染器的有限方差、长期收敛或无 firefly。[GRIS 正文与补充](https://graphics.cs.utah.edu/research/projects/gris/sig22_GRIS.pdf)

建议先记录非有限乘积、累计和、输出 UCW 的次数和最大范围，定位是否真实触发。随后考虑带显式 scale 的累加、在恢复 UCW 时补回 scale，或在设备/性能条件允许时使用更宽的关键累加器。重排乘法只能缓解部分中间溢出。直接截断 UCW、丢弃亮样本或照搬作者代码中的非有限丢弃分支，都不能宣称保持无偏。

### 4. 拆分 DI 与随机种子 GI 的历史有效性

位置：`RayTracingVulkanPass.java:2023–2025` 在每次静态几何 publication 时统一清空历史，原因是 emitter index 可能改变；`:2659–2668` 把同一个有效位传给两种模式。DI 在 `RayTracingRestirShader.java:212` 使用此位，suffix 在 `:288` 使用此位。

DI 的旧记录含 emitter index，失效理由成立。当前 GI 旧记录仅含 seed、UCW 和 supporting geometry，replay 重新查询当前场景，不会解引用旧 emitter index。**推导：**若条件 UCW、前缀/后缀随机维度独立和完整种子支持域的前提成立，单纯 DI 编号变化不改变 GI 的积分种子测度，因而不必自动丢弃 GI。

建议以 `history.x` 的不同 bit 表示 DI/GI 有效性，并分别管理 ready/revision。先保留相机 reset、分辨率/模式变化及每 8 帧全局 expiry；对 origin rebase 必须同步平移或清除 supporting geometry。材质、采样器语义变化仍需单独审计。

DI 也有更保守的机会：只有 emitter 顺序、几何、相关材质与候选索引语义被证明稳定时保留。现有 `RayTracingLightTree.sameLightInputs` 和 `Data.withMaterialMapLength` 已提供部分等价性信息；仅暗材质 lookup span 改变时 emitter 地址和 PDF 可保持不变。不能只用几何 revision 相等或散列相同作为证明。

此处保留 GI 是针对本项目种子重放的条件推导，不是论文已经测量的收益。先统计每类 reset 的频率，才能判断 streaming 中收益有多大。

### 5. 后续前缀只随机评价一个历史来源

对固定的历史输入集合，当前 borrowed-only 前缀估计为：

```text
F = sum_j W_j f(u_j) / n
```

额外随机选择 `J ~ q`，只评价：

```text
F_hat = W_J f(u_J) / (n q_J)
```

则对这次选择随机性取条件期望，恰好得到原来的 `F`。均匀选原始来源时 `q_J=1/n`，简化为 `W_J f(u_J)`。这借鉴 Stochastic Pairwise MIS 的随机估计原则，是对当前 uniform/full-support estimator 的单独推导，并没有把本项目改成 pairwise MIS。[论文项目页](https://research.nvidia.com/labs/rtr/publication/hedstrom2026stochastic/)

若已经合并重复 seed，组权重 `U_g = sum W_j`，应使用 `U_g f(u_g)/(n q_g)`；分母 `n` 仍是原始来源数，不能改成 unique seed 数。所有有正原始质量的组必须有正选择概率。选择使用独立的前缀随机域，不能破坏 suffix tape 的复现。

首个前缀先保持现有 fresh + 所有 donor 的 reservoir 更新，仅在后续前缀应用该模式：

| 前缀数 P | 历史来源数 n，seed 均不同 | 当前后缀评价次数 | 后续前缀单来源模式 |
|---:|---:|---:|---:|
| 2 | 3 | 4+3=7 | 4+1=5 |
| 4 | 3 | 4+3×3=13 | 4+3=7 |
| 4 | 5 | 6+3×5=21 | 6+3=9 |

重复 seed 已被合并时收益会更少。随机选择保持条件期望，但通常会增加条件方差，可能产生更明显的暗点；不能把论文特有的 confidence 缩放直接加到这里。需要等 GPU 时间、经过 NRD 的质量对照。

### 6. 先量化支持前缀搜索，再加世界空间索引

`RayTracingRestirShader.java:290` 用**主表面**重投影位置寻找屏幕邻居，`:302–303` 再要求**次级支持交点**距离不超过 2、法线相近。这是屏幕邻域内的近似搜索，不能发现所有世界空间相近的支持前缀。增加邻居数量可能只增加拒绝读。

Conditional RIS §8 的原型讨论了世界空间 final gather 的优势及搜索开销。可先记录候选读取数、几何接受数、unique seed 数和 disocclusion 下的回退率；若接受率持续低，再评估 secondary-position spatial hash 或其他邻域结构的建表/带宽成本。[Conditional RIS](https://research.nvidia.com/labs/rtr/publication/kettunen2023conditional/)

### 历史期限与后续测量

保留 `RestirLayout.HISTORY_PERIOD=8` 的现有全局期限作为第一阶段边界。按所选样本年龄淘汰会引入样本依赖的选择问题；简单把全局重置改成像素交错重置，也不能保证 lineage 不会在邻居间循环延寿。GRIS 关于 M-cap 和相关性的结果不能直接变成这里的无偏年龄规则。

后续实际实施的推荐顺序是：**hit distance → 主表面照明缓存 → 权重诊断/稳定累加 → DI/GI 历史拆分 → 可选单 donor gather**。结构性重连、duplication map、路径引导放在这些之后。

测量应固定场景、相机、分辨率、GI 跳数、太阳采样及 NRD 设置，记录 mode 0/1/2/3 的真实 GPU 执行模式；对比等 GPU 时间的去噪后误差、时域闪烁及运动稳定性，同时记录 suffix 段数、donor 接受率、重复率、reset 原因、UCW 范围及寄存器/stack 压力。现有 mode 0 的旧 ablation 或静态 SPIR-V 指令数不能替代 ReSTIR 的 GPU 数据。
