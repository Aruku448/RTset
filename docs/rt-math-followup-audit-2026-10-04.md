# 渲染数学复查：输运、采样与重建

日期：2026-10-04。复查上一轮数学优化后的当前源码。此轮只增加审计文档和数值反例脚本，没有修改运行代码、配置或部署。

数值反例：运行 `python3 scripts/audit_render_math_followup.py`；结果为 [JSON](profiling/2026-10-04-render-math-followup.json)。脚本确认相应源码调用条件仍存在，再对合成输入计算反例；它不是实际 Vulkan 场景回放，不能用其数值声称当前截图或 GPU 像素必然如此。

## 优先修正

### 1. 面积灯阴影段可能包含目标光源自身

`RayTracingShaderRaygen.java` 的局部体积灯 ray 从 `volumePosition + light.direction*0.002` 发射，但 tMax 仍为偏移前的 `light.distance`。表面灯亦从 `surfacePosition + normal*0.002` 发射并使用旧距离。CPU LightTree 存储原始三角形坐标，没有把目标移离自身几何；Shadow AnyHit 对不透明灯面接受命中，ClosestHit 将 RGB T 写为 0，没有按目标 emitter 排除。

几何反例：体积点 z=0、目标不透明灯面 z=10、样本在三角形内部、ray 沿 +z。偏移后 origin.z=0.002，目标 t=9.998，tMin=0.001、tMax=10，因此灯自身在遮挡区间内。表面 normal 与 ray 同向时同样成立。该反例证明当前阴影线段构造有问题；实际遮暗范围取决于场景、面朝向、浮点误差与采样点。

修正应基于**偏移后的起点**重算到目标的方向/距离，并在目标之前结束线段，使用与坐标尺度相称的端点误差处理。不要全局忽略所有发光几何：其它灯和目标背面的几何仍可能是合法遮挡。光源 PDF/BSDF 应继续针对原 receiver 与原采样点求值，visibility 的偏移不应随意改变估计目标。

### 2. 粗糙透明反射的面积灯、天空估计策略没有配对

当前面积灯 NEE 只在 `!transmission` 时运行；天空 NEE 只在 `bounce==0 && !transmission` 时运行。然而：

- 粗糙透明材质选择 GGX reflection 后 `previousWasDelta=false`；下一次命中 registered emitter 的发光仍被 `bounce>0 && !previousWasDelta && registered` 清零。前一顶点没有面积灯 NEE，该项没有任何有效估计器，故漏掉反射灯光。
- 同一粗糙反射 miss 到天空时，bounce=1 仍给 BSDF miss 乘与 cosine sky proposal 配对的 power weight。此前天空 NEE 没运行，这个权重不是有效的双策略 MIS。pBSDF=0.1、pSky=0.2 的反例只保留 0.2，而独占 BSDF 应为 1。

平滑玻璃的 delta 分支不受这两条 continuous 条件影响。修正应携带“前一顶点实际启用了哪个 NEE 策略”，再决定 emitter 排除和 sky miss 权重，或完整开放透明反射的对应 NEE。不能只按 previousWasDelta 推断 NEE 是否存在。这也适用于以后增删灯光采样策略。

### 3. 新体积控制变量遇到最终截零会偏亮

上一轮控制变量对**线性有限步和**的期望验证仍成立，RGB shadow、区间逆概率与 solar/lunar 源项没有在此被推翻。但 aerial composite 最终将 scene color 截到 [0,65504]：E[max(X,0)] 一般不等于 max(E[X],0)。

反例：同一区间直接源贡献 a=[10,1]，两步完全遮挡，K=1、均匀选择，无遮挡 baseline=11。两种残差估计为 −9 和 +9，均值 0，正确遮挡贡献为 0；若场景底色为 0，截零后均值变为 4.5。多重散射/表面底色够亮时未必触发下界，但不能据此宣称完整显示链无偏。当前没有大气独立时间滤波，该偏差和噪声会直接进入后续 FSR。

这是新方案的重要限制，应优先解决。可先恢复 `volumetricShadowSamples=0` 的完整遮挡模式作为基线，再比较非负直接积分估计、具有支持的源项重要性采样和独立体积时间重建。单纯把负 L 更早截零、提高 clamp 或塞入 surface NRD 历史都不能证明消除该偏差。

### 4. 天空体积补光与已有多重散射的高阶源项重叠

原 `integratePhysicalAtmosphereSegment` 已加 `physicalAtmIndirect`。固定 Prime 源码中：

- `atmosphere_incident.compute.slang` 通过完整大气 ray integration 构造 incident radiance，后续迭代启用 multiple。
- `atmosphere_multi_scattering.compute.slang` 用 incident radiance × 相函数角向积分求 scattering source。
- `integrate.slang` 在 direct 后加 atmIndirect。

当前 `samplePhysicalVolumeEmitter` 又把 `physicalAtmosphereSky` × local phase scattering × RGB visibility 全量加到相机段。这同样产生“已经散射的天空入射光再次散射”的高阶贡献，与 LUT multiple 存在来源重叠，不应在无遮挡环境中当作全新独立灯项累加。

它不是可以断言的逐像素“精确翻倍”：额外项只有上半球、使用 RGB/太阳谱加权近似、读取相机高度 SkyView，而 LUT 是体积点高度的四波长角向闭环，结果并不相等。若目的是补局部遮挡，应推导相对于 LUT 基线的差分源项（概念上 V−1），或明确替换那部分 angular source，而非重复加入全量 sky。月光同理。需检查开阔无阴影环境的 baseline invariance，再讨论树林中的局部天空贡献。

## 其它数学限制

### 5. 光树正向/反向概率的 FP32 不一致

正向 traversal 用 `pRight=1-pLeft`，反向 `emitterSelectionPdf` 调用交换参数的 score ratio 单独求 pRight。实数中等价，FP32 不保证相同：leftScore=1、rightScore=1e−8 时，前向 pRight=0，反向约 1e−8。极端权重下还会失去正支持，surface 独占 NEE + 清掉 BSDF emitter 的组合可能漏贡献；volume 的 uniform mixture 保留了整体正支持，但 tree 部分依旧不完全配对。

修正应统一节点左右概率的计算定义，反向按固定 child 次序取对应概率；需要正支持时明确 mixture/保底方案，并同步正反 PDF。此处数值反例不是当前场景出现频率的测量。

### 6. 非色散透明界面的 IOR 与 authored F0 没有统一能量闭环

branch Fresnel 根据 IOR，平滑非色散 reflection 实际权重根据 material reflectivity，delta transmission 抽样概率则为 1−FIOR。无吸收/unit tint、IOR=1.5、authored F0=0.5、正入射时，期望反射 0.5、透射 0.96，总能量 1.46。上一轮只修复了 TIR=1，未统一该模型。需要先确定 authored reflectivity 是否覆盖 IOR Fresnel，并让反射评价、透射能量、概率、NEE 和色散使用同一约定。

### 7. 光谱 T 投影后不能当作严格可组合 RGB T

当前太阳谱加权四波长→Rec.2020 投影 F 满足不了 `F(T1*T2)=F(T1)*F(T2)`，脚本反例 red 为 0.2495225 vs 0.2607794。局部体积 `viewT * phaseScattering * sourceT` 的三个分别投影 RGB 因子也不是完整光谱乘积的等价形式。可作为现有 RGB 近似，但不得直接以该 RGB T 的段复用证明“与四波长原算法精确相同”；严格组合应先在四波长中乘/积，再转换实际光谱辐亮度。

### 8. 相机初始介质与 miss 衰减仍有限制

insideMedium 初始为 false，未通过相机所处水/玻璃初始化介质与 IOR。Beer-Lambert 又在几何命中分支里，miss break 之前没有对应的介质段吸收。这是已有介质状态模型限制，会影响相机在介质内、截断场景缺失出口等情况；并非本轮 VNDF 引入。

## 未发现新错误的部分

- VNDF sampler 的视线拉伸/投影/反拉伸与 evaluator 的 `D G1/(4 nDotI)` 同步；已存在的 CPU 积分参考通过，但不是 GPU 全输入证明。
- 体积不等长整数区间覆盖所有索引，inverse probability 为区间长度；太阳/月光分别保留 RGB visibility，源 T 和 multiple 未乘抽样权重。
- solar/sky 的 Li/pdf 与 cosine/pdf 相消保留了相同有效支持和 MIS PDF。
- POM neighbour wrap 对任意正尺寸与负坐标等价；normal width 高位已在实际采样消费点屏蔽。
- NRD 的线性 RGB→YCoCg→RGB 公式互为逆变换，demodulation/remodulation 使用相同材质因子下限，T 在输入上调制后不会在 NRD composite 再乘一次。滤波、sanitize、range、动态阴影 fallback 仍是另外的非线性/时域处理。

## 建议修正顺序

1. 面积灯 visibility 端点、前顶点 NEE 策略状态与发光/天空配对。
2. 恢复体积完整查询对照，解决抽样经截零的亮度偏差。
3. 明确 LUT multiple 与局部天空源的差分关系。
4. 光树概率一致性与透明材质 Fresnel 闭环。
5. 进一步统一光谱组合、相机介质初始化与 NRD/体积重建。

本轮结论不是“运行 shader 全部数学已证明正确”，也不是 FPS 或视觉验证。新的反例说明仍需修正，不能只依赖编译通过和局部积分测试。
