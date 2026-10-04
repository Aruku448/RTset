# RT 表面输运数学审计（2026-10-04）

范围：当前 `src/main/java/com/rtest/client/RayTracingShaderRaygen.java` 的 BSDF、NEE、MIS、Sobol、roulette、折射与草木响应。只读运行源码；本报告没有修改、构建或部署 mod。行号以本次检查时为准。不能据此声称 GPU 实测加速。

## 优先结论

1. **严格零 throughput 的路径可以立即终止，数学期望不变。** 在 1940、1955 行，无效方向可能令 throughput 全通道为零；1427 行的下一段仍 trace，1962–1968 行只在 bounce>0 做 RR，且零能量仍有 5% 存活。已累计的 radiance、primary guides 和后续独立相机体积积分必须保留。只允许“全通道严格为零”早退；用亮度阈值截断属于有偏近似。这是比通用 pow 替换更直接的射线数量优化。
2. **当前 GGX 是 NDF proposal，可改 VNDF 改善采样效率。** 901–914 行采样只依赖 normal/roughness，没用 viewDirection；851 行 PDF 正好是 NDF 的 `D(h) cos(h)/(4 |wi·h|)`。改成可见法线采样必须同时更新所有 NEE 和 continuation PDF，不能只替换 sampleGgx。
3. **先修粗糙透明材质的太阳 MIS 配对。** 1668–1670 行太阳评价把透明镜面 proposal 概率设为 1；1636–1653 行真实 continuation 对可折射材质使用 Fresnel 分支概率。1527 行的 miss 权重又用真实 mixture PDF。粗糙透明面可到达同一太阳方向时，两个 power weights 不再互补。roughness=0 是 delta，另一套规则，不能据此说所有玻璃都错。
4. **全反射分支能量值得先修。** 1632–1634 行 refract 失败后，roughness=0 的 specularProbability=1；1925–1929 行依然乘 Schlick Fresnel，未置反射能量为 1。例如从 IOR 1.5 向空气、cos(theta)=0.5，发生全反射，但 Schlick 为 0.07，只保留约 7% 能量。该例是当前公式的数学反例，不是截图验证。

## 已有结构，不能重复计收益

- 815–832：receiver-dependent GGX 能量表和 mixture probability 已准备一次，主循环传给各光源。
- 937–941：光树分支已把 `P_left/d_left²` 与 `P_right/d_right²` 的比较交叉相乘，避免两次除法。
- 1662–1676、1709、1527：太阳 N 个采样使用 `n*p` 的 MIS；末次顶点太阳/月光没有 continuation，因此 NEE weight=1。这个结构是合理的。
- 1078–1110：三角形 sqrt-barycentric 均匀面积采样，转换方向 PDF 为 `p_select*d²/(area*cos_light)`。
- 1118–1137：体积局部灯使用 tree/uniform 各一半混合，两个分支均用完整 mixture selection PDF，uniform 分支保证树权重为零的灯仍有支持。
- 1280–1331 与 1864–1865：面积灯使用独占 NEE（权重 1），非 delta BSDF 命中的已注册 emitter 排除发光；这不是“少乘了 MIS”的直接 bug。其正确性依赖注册灯与可见 emissive transport 是否一致、光树对所有非零 emitter 有支持。CPU `RayTracingLightTree.java:132–141` 另做 area-normalized emission，明显是艺术标定；不能称完全物理相同的表面辐亮度。
- 1791–1849 与 1515–1518：天空只对 primary 做 cosine NEE，只有 bounce=1 的环境 miss 做配对 MIS。其它 bounce 没有天空 NEE，miss weight=1。不是在所有顶点均双采样天空。
- 1898–1908：delta transmission 的分支概率为 1-F，已在抽样概率中承担反射/透射划分，throughput 不应再次除以 1-F。
- 1960–1968：scatter 之后的 RR 是合法的期望不变随机终止；当前 luminance 系数是 sRGB/BT.709 系数，transport 为 Rec.2020。改变 RR scalar 不改变无偏性，但会改变彩色路径方差与存活效率，不能把这个系数本身叫颜色能量 bug。

## 数学等价的候选

| 候选 | 依据 | 实施边界 |
|---|---|---|
| 零 beta 早退 | beta 为 0，后续乘任何有限辐亮度均为 0 | 保留 primary guides；单独处理非有限值，不把 epsilon 当零 |
| 太阳 `Li/pdf` 化简 | 1711–1713 同一个有效 solid angle 在分子和分母相消 | `sampledSunRadiance = sunResponse * intensity * MIS`；PDF 仍用于 MIS |
| 天空 cosine/pdf 化简 | 1795–1796，1848：正 cosine 下 `cos/(cos/pi)=pi` | 保留 PDF 用于 MIS；保持 horizon/support gate |
| 同一命中点预计算 BRDF 静态项 | 843、847、850、868：view cosine、alpha、f0、G1(view)、diffuse normalization 对所有灯相同 | 增加跨 trace 活跃状态可能反而增寄存器压力，必须比较 GPU 时间/寄存器 |
| 太阳 basis/角度移出 N 样本循环 | 365–378 的 center/tangent/bitangent 和 radius 对同一源不变，当前每样本重算 | 观察编译器是否已提取，不宣称源码 FLOP 减少等于 GPU 提速 |
| opaque 不做折射接口计算 | 1616–1634 对所有 opaque 也算 IOR/Fresnel/refract，最后 canTransmit=false | 保留 opaque probability 与相同 Sobol domain；编译器可能已做条件消除 |

## 改估计器：低方差与成本一起衡量

### GGX VNDF

用可见法线 proposal 替换 NDF，反射方向 PDF 改为 `D(h) G1(wi) |wi·h| / |n·wi| / (4 |wi·h|)`，并乘对应的 mixture probability。BRDF 不需要因此改变。保持 diffuse/specular 整体 mixture PDF、primary lobe partition 和 celestial MIS 同步。VNDF 并不保证每个反射方向都在上半球；仍要零 beta 早退。

用随机种子 104、每组 20,000 样本直接代入现有 NDF 公式，仅统计 **镜面 proposal** 产生下半球方向的比例：

| roughness | view cosine 1 | 0.5 | 0.1 |
|---|---:|---:|---:|
| 0.30 | 0.68% | 2.88% | 25.72% |
| 0.60 | 11.30% | 24.36% | 44.21% |
| 0.88 | 38.16% | 43.55% | 48.31% |

这不是总路径比例或加速百分比；当前 dielectric/diffuse mixture 的镜面选择概率、场景和 GPU 占用都未计入。

### 天空/灯重要性采样

cosine 天空 proposal 适合 diffuse，但未利用天空亮斑。可建立按方向辐亮度×solid angle 权重的分布，并与 cosine 混合，所有天空 NEE/miss 权重必须统一 mixture PDF。增加 LUT/distribution 维护、查表与状态成本；只能通过同等噪声下减少射线得到收益，不能直接减少当前固定射线的时间。

面积灯可做 receiver-dependent BSDF/方向界重要性权重以改善方差，必须保留正支持且 sampler 与 emitterSelectionPdf 使用同一函数。alias/uniform 替换 tree 改变 proposal，未必适合数千个远近不均匀灯。

## 艺术模型与其它正确性边界

- 883–898、1717–1726 草木响应是 ITRP 风格艺术模型。普通 BRDF 由 NEE+BSDF 共同估计，额外 `(foliageTarget - ordinaryBRDF)` 仅通过 NEE 积分；这项不应再乘 ordinary MIS。它是控制差分形式，期望上不是重复减 BRDF，但局部单样本修正可能为负并增加方差。它不是参与介质 SSS。
- 118–139、1620–1630、1906–1909、1943–1946 是三通道 hero 近似，只在首次 dispersive interface 选 channel 并乘 3。它不是完整光谱。interface direct-light Fresnel、粗糙 transmission model 与 continuation 不完全统一，不能把“hero multiplier 无偏”推广为整套玻璃光谱输运正确。
- 1589、1911–1920 的介质状态只有 insideMedium bool + 一组 IOR/absorption；嵌套玻璃/水无法表达 medium stack，且将下一次 transmission 视为离开当前介质。属于模型限制，不能用代数简化修复。
- 折射 delta 没有 radiance transport 的 eta² 项；对于闭合同介质进出界面可能抵消，但相机在介质内部、不同介质出口及 rough transmission 不满足简单抵消。增加 eta 项后 RR 也应按 etaScale 调整，先明确单位与 transport mode。
- 1710 粗糙透明太阳 PDF 与真实 continuation 不一致需要数值配对测试；月光使用真实 diffuseProbability/specularProbability（1751–1756），可作为一致性对照。
- 1326、1940、1955 把 PDF 下限固定 1e-6，不是严格零保护：真实正 PDF 更小时会缩小权重，属于有偏数值截断。不要为了“加速”进一步抬高门限。
- 284–323 的 Sobol/Burley 以 effect/vertex/domain 隔离随机数，1424–1426 保持时间索引连续。减少某光源样本不会把其它随机流向后移；这支持同场景 A/B。需测试样本直方图、低差异覆盖而非只看 shader 字符串。

## 验证建议

先做零 beta 早退和配对 PDF 修复；建立 NDF/VNDF 积分一致性、TIR=1 和 MIS 权重和=1 的数值反例/回归。然后比较固定相机、同设置同随机 epoch 的 GPU 时间与噪声，分别报告“每帧成本”与“达到同等误差的成本”。寄存器复用和代数提取优先检查 SPIR-V/驱动结果，避免重复编译器已有优化。

## 外部依据

- [PBRT v4：可见法线分布与采样](https://www.pbr-book.org/4ed/Reflection_Models/Roughness_Using_Microfacet_Theory)：支持 VNDF proposal/PDF 必须匹配的要求。
- [PBRT v4：路径追踪 MIS 与 RR](https://www.pbr-book.org/4ed/Light_Transport_I_Surface_Reflection/A_Better_Path_Tracer)：支持发光命中/NEE配对和 RR 权重处理。
- [PBRT v4：介电 BSDF](https://www.pbr-book.org/4ed/Reflection_Models/Dielectric_BSDF)：支持全反射及 radiance/importance transport 的 eta 因子处理。
