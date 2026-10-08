# 论文作者实现对照（2026-10-07）

源码：NVlabs/conditional-restir-prototype，检查固定提交 2188c2049aaa27b2576ce6486a51785d74db7bff。只读检出位于 /tmp/conditional-restir-author-review，没有编译、运行作者程序或将作者代码复制进项目。参考 PDF：/home/aruku/文档/kettunen2023conditional.pdf，第5–7页算法1及公式18–23，第10页图3–4。

## 实际结构

1. 保存 supporting prefix，使用时间重投影与GRIS选择前缀。前缀目标是它自身吞吐 fp，不依赖后缀亮度，避免联合UCW的依赖问题。
2. 在已选前缀上产生canonical suffix，执行时间和空间条件RIS；这一步建立后缀缓存。
3. 独立生成integration prefixes，用世界空间邻近搜索找到supporting prefixes，借用其后缀。
4. 采用hybrid shift：可重连接处复用重连接顶点之后的贡献，在不适合连接的光滑/近场区域可需要随机重放。论文说明前缀延长到连续两个高粗糙度顶点，因此不能把固定次级交点当作完整hybrid实现。
5. 连接时重新计算两端BSDF/PDF、几何项、Jacobian比值和连接段可见性。缓存不是单纯颜色插值，也不是复用旧阴影。
6. Final gather默认只给integrationPrefixId==0生成canonical suffix，其余前缀通过MIS和权重补偿复用后缀。论文公式23解释这一roulette估计器；不能简单删掉其它canonical路径而仍沿用旧平均权重。

## 源码入口

- [ConditionalReSTIRPass.cpp](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/ConditionalReSTIRPass.cpp)：分阶段调度；约745行起按前缀生成/查询/重追踪/积分；hasCanonicalSuffix控制新后缀。
- [PathReservoir.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/PathReservoir.slang)：储存prefix UCW、交点、方向、rcIrrad、rcJacobian、路径标记和随机状态，远超过当前seed+UCW。
- [Shift.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/Shift.slang)：shiftPathReconnection重新计算连接两端与Jacobian、遮挡；支持hybrid验证。
- [PrefixResampling.cs.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/PrefixResampling.cs.slang)：supporting prefix的重采样阶段。
- [SuffixResampling.cs.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/SuffixResampling.cs.slang)：条件后缀重采样及最终积分。
- [PrefixNeighborSearch.cs.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/PrefixNeighborSearch.cs.slang)：通过supporting endpoint的搜索点BVH进行世界空间KNN，也提供屏幕空间与方向搜索路径。
- [RetraceWorkloadQueue.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/RetraceWorkloadQueue.slang)：WaveActiveSum/WavePrefixSum聚合工作，以wave为单位原子追加队列。
- [ConditionalReSTIR.slang](https://github.com/NVlabs/conditional-restir-prototype/blob/2188c2049aaa27b2576ce6486a51785d74db7bff/Source/Falcor/Rendering/ConditionalReSTIR/ConditionalReSTIR.slang)：默认4个integration prefixes、1个gather suffix、generateCanonicalSuffixForEachPrefix=false。

## 与当前RT的差距

| 项目 | 作者原型 | 当前RT |
|---|---|---|
| supporting prefix | GRIS时间复用 | 每帧新BSDF前缀，无prefix GRIS |
| 后缀表示 | 重连接交点、方向、缓存后缀贡献、Jacobian及随机状态 | 随机seed、UCW、支持交点/法线 |
| shift | 几何重连接与必要重放结合 | 相同seed重新追踪完整路径 |
| gather搜索 | 世界空间KNN/其它可选搜索 | 上帧重投影屏幕窗口并作几何阈值检查 |
| canonical开销 | 可每像素只1条，公式23补偿 | 每个prefix都追踪1条 |
| MIS | 跨域target代理、Jacobian、正反shift与pairwise/Talbot配置 | 满支持seed域等权MIS |
| GPU结构 | 多pass与Compact重追踪队列 | raygen内串行嵌套完整积分器 |

“作者避免每个候选重跑完整路径”不能理解为完全没有retrace：源码明确有PrefixPathRetrace/SuffixPathRetrace与工作队列。收益依赖可连接长度、重放条件、邻居搜索、MIS双向评估等实际成本。

## 性能证据的含义

论文实验使用RTX4090、1920×1080、最长12段路径，和本项目最多5段的场景不同。图4在实验设置中通过canonical roulette减少最多约80%射线，不能作为本项目加速80%的承诺。图6中8个integration prefixes原型74 ms，增加候选的ReSTIR PT约76 ms；32个前缀207 ms、128个736 ms。目标是降低相关噪声及展示条件RIS能力，作者也说明原型昂贵，并非现成低开销解决方案。

## 适合此项目的后续步骤

先将现有积分器切为可保存的前缀状态与从次级交点继续的后缀状态，避免每次重放主射线/主表面NEE；这只是前缀去重，仍不是论文重连接。接着引入稳定几何/光源身份、重连接交点和缓存suffix contribution，实现两端BSDF、Jacobian与新可见性。然后处理hybrid replay和支持域MIS，最后再应用公式23的canonical roulette及紧凑队列。顺序应让正确性验证先于射线删减。

本次只查看和比较，没有改动渲染器、游戏开关或实例JAR。
