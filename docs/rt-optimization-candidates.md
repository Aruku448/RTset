# 光追数学与算法优化候选

## 审计范围与结论

只读检查当前 shader、CPU 几何更新/LOD/material 流程、FSR 调度与灯光树，未修改渲染实现。此前实时原生分辨率窗口 `rt_ms=42.417 ms`、`post_rt_ms=1.176 ms`、已测合计 `43.619 ms`；这是样本均值，不是整帧时间。当前 GI=4、体积质量=3、POM 开启、NRD/Sundial/LOD 关闭。

原则：先消除无人消费的输出、重复计算和重复构建，再改采样策略。数学等价不保证 IEEE 浮点逐位相同；无偏只保证期望，不保证单帧同画质。下列收益均未实测。

源码前缀：`src/main/java/com/rtest/client/`。

## 第一批：功能等价或浮点容差内等价

### 1. 两种降噪均关闭时，裁掉 NRD 专用 AOV

**位置**：`RayTracingShaderRaygen.java:1740–1847`、`fsr/RtestFsr3.java:106–110,287–293,308–349`、`fsr/NrdDenoiser.java:1117–1147`。

RayGen 无条件计算并写 noisy diffuse/specular、normal/roughness、viewZ、NRD motion、primary position、material/specular material、direct/indirect/emission 共 11 张图。两降噪均关闭时，`recordAfterRayTracing()` 不消费这些图；FSR 的 scene color、motion、depth、reactive、transparency 是另外的输入，仍须保留。

这是计算图中的死输出消除：若输出节点没有活跃消费者，其专属计算与写入可删。按当前图像格式，11 张 AOV 合计逻辑写入 88 B/像素；2560×1404 下约 **301.64 MiB/帧**。这是格式推算的逻辑流量，不是实测显存带宽，压缩、缓存、调度会影响真实收益。

建议捕获一次 frame feature mask，选择轻量 raygen variant/specialization，使编译器裁掉 AOV 及专属算术；避免 shader 和后处理分别读取可变配置导致同帧不一致。NRD/Sundial pipeline/history 可进一步按需创建，但这涉及 descriptor 有效性、图像 layout、启用后的 history reset 与 fence 退休，不能直接删除对象。

验证：固定场景/种子对比最终线性 HDR 与 FSR 输入；测试关闭→开启→关闭、resize、透明体、动态实体。不得裁掉 FSR motion/depth，也不得省掉重新启用所需的初始化。

### 2. 缓存同一个着色顶点的 BSDF 不变量

**位置**：`RayTracingShaderRaygen.java:583–633,958–977,1238–1262`，以及 continuation 分支。

同一命中点的 normal、view direction、roughness、metallic、reflectivity 不变；多个 helper 重复求 `primeDefaultGgxDirectionalEnergy`、能量补偿与 lobe probability。可构造局部 `BsdfContext`，复用视向相关能量；但入射方向相关的 N·L、half vector、Fresnel 不能一起缓存。

写成 `E=E(N·V,roughness)`，太阳、面积灯和 continuation 共用同一 E；只裁掉无消费者的 PDF 计算，不能省太阳/continuation MIS 需要的 PDF。透明界面的 lobe probability 与不透明材料不同，不得混用。

验证：先检查生成 SPIR-V/驱动反汇编是否已经做 CSE。手工缓存可能增加寄存器占用，反而降低 occupancy；必须同时测 shader 执行时间和寄存器/溢出。固定随机种子、材质覆盖下对比 HDR/AOV。

### 3. POM 零 UV 位移时直接返回

**位置**：`RayTracingShaderCommon.java:73–157`，活跃调用 `RayTracingShaderStages.java:143–144`。

当前最多 32 次高度步进加 5 次细化。若最终 `parallaxDelta=(0,0)`，所有采样位置不变，UV 结果已经确定，可免后续重复高度读取。适用于零深度或正对表面的精确零切向分量。

必须保留原有早退语义：通过现有条件后返回 `vec2(u0,v0)+wrappedCoord*span`，不能无条件返回原 atlasUv；边界 wrapping 可能不同。将“等于零”扩展为“很小”则变为近似优化，需以投影 UV 误差而不是任意 epsilon 判断。

验证：零深度、正视、sprite 边缘、动画贴图和 alpha cutout；比较颜色、最终 UV 及高度读取次数。

### 4. 相同有效场景组合不重复 build

**位置**：`RayTracingProbe.java:1501–1514,1585–1590,1767–1787,1982–2006`。

未被选中的 LOD 节点完成也会推进 change serial，触发 composition 或废弃正在计算的结果。按实际函数输入去重：source 身份、实际 mesh/section 身份与顺序、所有消费配置、GPU 模式及 horizon 等不变，就有 `F(x)=F(x)`，不应再求一次。

不能只比较 selected NodeKey 集合：同键 mesh 可替换；也不能只依赖可能碰撞的内容 hash。保留原资源 join/退休语义，future cancel 不等于 GPU/worker 已停止使用资源。

验证输出 section 序列、material spans、scene revision/temporal reset 与原实现一致，统计重复 composition 和 stale discard。

### 5. 已有变更差分只求一次

**位置**：`RayTracingVulkanPass.java:1580–1593`、`RayTracingMaterialBuffer.java:57–100`。

当前先建旧索引检查，再建索引全扫执行，失败时又反向求差。一次生成变更 span 计划，保存正向/回滚数据，能省重复 O(N) 扫描。若上游提供实际 D 个变更且布局不压缩，可进一步降到 O(D)。布局 compact、dynamic base 迁移及 high-water placeholder 的 flush 仍需处理。

验证 SSBO 逐字节一致；注入发布失败，检查回滚与 flush 范围。主要改善更新尖峰，不应声称改善主要 trace 时间。

## 第二批：LOD 与灯光树规模算法

### 6. 远代理/native 重叠用祖先集合查询

**位置**：`RayTracingProbe.java:1535–1542,1866–1880,1933–1947`。

当前每个代理扫描全部 native section：O(P·N)。在 section 与节点都严格按 16·2^level 网格对齐时，正体积重叠等价于“存在 section 的该级祖先键等于代理键”。构造各级祖先集合 O(N·L)，P 次查询 O(P)，总计 O(N·L+P)。

集合必须包括透明与流体 section；只收 opaque 会改变防重叠规则。负坐标必须 floorDiv，边界相接不算正体积重叠。若输入不对齐，这个等价关系不成立。当前 LOD 关闭，因此它不是当前 trace 开销的解释。

其他增量路径：`RayTracingProbe.java:1208–1287` 应直接传递增删/opaque 状态变化差分，并按父键分桶，将 O(N)+O(A·D) 的发现/分桶降至 O(D·L)；父指纹成本仍需保留。chunk dirty 查找 `:885–891` 可用 chunk→section 索引，O(N) 扫描改为中心与四邻 chunk 的 O(5H) 查询。均需集合与顺序回归对照。

### 7. 灯光树 median 分裂只需分区，不必每层完整排序

**位置**：`RayTracingLightTree.java:270–286,293–312`。

每节点完整 sort 子数组。最坏比较量满足 `T(E)=2T(E/2)+O(E log E)`，为 O(E log²E)；实际 TimSort 可能利用已排序子序列，不能据此断言现状总是达到最坏上界。若每层用平均线性的中位数选择/partition，平均 T(E)=O(E log E)。边界扫描自身仍是 O(E log E)。

必须保持完整叶支持、反向 material→emitter/leaf 映射以及 forward/reverse PDF 一致。相等中心的稳定 tie-breaking、树形和累加顺序变化可能改变固定种子结果及浮点权重；它保持有效采样而不保证逐帧位级一致。先用实际 emitter 规模做构建 benchmark，不优先于 GPU 死输出消除。

较小代数化简：`:88–90` 中 `emitterEmission=emission*Aref/A`、`power=A*pi*emitterEmission*importance`，实数域可写 `power=pi*emission*Aref*importance`。后续着色仍需 emitterEmission，不能删掉面积归一化；这里只是小计算优化，收益很可能有限且浮点顺序不同。

## 第三批：体积光积分与估计器

### 8. 零阴影强度与常介质的确定性快路径

**位置**：`RayTracingShaderRaygen.java:453–515`。

体积每点查询太阳可见性后做 `mix(1,T,shadowStrength)`。shadowStrength 精确为 0 时，有限数值下结果恒为 1；体积路径不消费 dynamic-occluder 标记，可直接跳过查询。当前 shadowStrength≈0.9795，此分支不覆盖当前通常场景。表面路径的动态标记可能被 AOV 消费，不能照搬。

水平/向下、从相机起点出发的积分段，其代码中钳制高度可能恒为 0；若消光/散射系数与可见性都恒定，单步透射 t、贡献 b 的 N 步累积可折叠为：

`T=t^N`，`I=b * (1-t^N)/(1-t)`，t→1 时比例→N。

这是原离散递推的代数折叠，而不是擅自替换物理模型。必须保留代码的低光学厚度 clamp，以及小量数值稳定分支。若日照遮挡沿段变化，不能假设可见性常量。

### 9. 体积可见性查询改为重要性抽样/控制变量

**位置**：同上；quality=3 当前最多 16 个太阳可见性查询/适用像素。

先确定性计算 16 个权重，把太阳项写成 `I=sum(a_i V_i)`。选取正概率 p_i，从 k 个样本估计：

`I_hat=(1/k) sum(a_j V_j/p_j)`。

它对当前 **16 点离散和无偏**，不是对连续积分精确。用 `p_i ∝ norm(a_i)` 改善重要性分布；零权重可免查询，非零项不能失去支持。无遮挡可见性 1 可以作控制变量：确定性算 `sum(a_i)`，只采样 `sum(a_i*(V_i-1))`。

k=2–4 时太阳查询数理论上减少 75%–87.5%，不代表整帧时间同幅下降。NRD 关闭下噪声可能更明显；窄光束、强遮挡是高方差场景。目标应是更低的“同等时间 MSE”，不是仅更少射线。用线性 HDR 多帧均值/方差、等时间参考比较；不能默认替换成低采样。

### 10. 修正体积发光复用样本的 PDF

**位置**：`RayTracingShaderRaygen.java:887–912,1650–1661`。

样本来自表面位置 xs 的分布，却在体积位置 xv 重新调用 `emitterSelectionPdf(xv, emitter)` 作为分母。样本没有重新抽取，分母不应凭评价位置改变。

应携带真实选择概率 `q_surface` 与面积 A：`q_A=q_surface/A`；体积方向密度为 `q_omega=q_A*r_v²/c_v`。同时传递已求出的 emitter radiance，可免体积处的光树遍历和重复纹理查询。

这是纠正估计器 PDF 错配，不与当前画面等价；输出能量可能变化。表面朝向拒绝可能让体积需要的源失去支持，复用表面可见性也不是体积点可见性，因此不能宣称修这一处后整体体积估计就无偏。需对照独立体积采样参考、双灯/遮挡场景验证。

## 不建议直接删的逻辑

- 不能直接去掉 fence/wait：当前原地 buffer、descriptor、query、FSR 历史复用依赖它。旧 BLAS 退休还需替换 TLAS 的构建 fence。真正异步化须有独立 frame-owned 资源与严格退休机制，尤其已出现 resize device-lost。
- 不能把 native_aa 当作“无需 FSR”：相同分辨率仍有时域抗锯齿、重投影及锐化语义。
- 不能将 GI=4 改1、体积16点改4点、关POM宣称同画质等价；它们仅是 A/B 实验或画质预算变化。
- 不应凭路径追踪总时间推算各项占比。微小除法/平方根简化可能早已被编译器消除，增加寄存器生命周期也可能更慢。

## 优先级与验证

1. 独立 counters/timestamps 先确认体积阴影数、POM 高度读取、AOV 带宽、AS/NRD/FSR 时间；固定相机、配置、随机序列和预热。
2. 第一轮实现等价低风险项：AOV specialization、BSDF 不变量复用、零位移 POM；逐项比较 HDR/FSR 输入及寄存器与 GPU 时间。
3. 改善更新尖峰：有效 composition 去重、材质 span 单次计划；LOD 开启时再测祖先索引与差分。
4. 在独立画质实验中处理体积 PDF，再试重要性抽样/控制变量；保留参考路径。

按已有已测区间，RT 占 f≈0.9725；Amdahl 上界 `S=1/((1-f)+f/s)`。若 RT 真能减半，已测区间理论吞吐提升约 1.95 倍；即使将当前 post 段全部抹掉，也仅约 1.028 倍。这些是已测区间的数学上界，不是实际 FPS 预测，也不证明任何候选能达到该收益。
