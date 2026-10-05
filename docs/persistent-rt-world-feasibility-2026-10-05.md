# Persistent RT World：RTset 可行性分析

日期：2026-10-05。范围：架构分析，没有修改渲染代码或部署。

**范围更新**：本文件保留早期“深度重投影 + 按需补射线”的可行性分析。用户后续明确要求摄像机自由运动和实时实体，研究主路线已改为 [持久世界照明 + 当前视图多频渲染](persistent-rt-world-multirate-design.md)。下文关于旧深度、宽 FOV 的限制主要适用于早期重投影方案；GPU 预算、同步、光照失效和视角相关贡献的限制仍然适用。

## 判断

静态世界、固定照明、小幅摄像机运动的原型可行；作为 RTset 后续架构方向也成立。但“旧深度重投影 + hole 补 primary rays”不是完整的可见性与着色闭环；当前不能据此承诺 120–240 Hz。需要分别维护世界状态、可见表面历史与显示图像，并把昂贵工作做成有预算的分批更新。

## 1. 修正状态分层

| 状态 | 应放置的位置 |
| --- | --- |
| 静态几何、材质身份、BVH、空间 GI/辐射缓存 | 持久世界状态，按场景/光照变化更新 |
| 几何表面世界坐标、几何法线、材质 | 可以作为世界属性持久化；但其在图像中的采样和可见性随相机变化 |
| 屏幕 Depth / World Position / Normal / Material 图像 | 某一相机的表面样本历史，不能仅因值在 world space 就称为视角无关缓存 |
| Motion | 明确两个时刻/相机之间的映射，不能作为不随时间变化的世界属性 |
| Confidence | 至少区分几何覆盖、照明有效性、采样质量、视角变化和历史年龄 |
| 镜面、透明、POM、视线介质积分 | 新视角下重算或保守失效；静态世界也可能持续变化 |

建议模型为：Persistent Scene/Lighting + Surface History + Display Reconstruction。最终颜色的可复用性小于几何/漫反射照明的可复用性。

## 2. 旧深度无法证明新可见性

旧表面点正向投影到新相机，做深度竞争，只能选出“旧样本中最近的表面”。它无法证明没有先前被遮挡、从未进入旧缓存的表面在新视角下挡住该点。因此，有颜色覆盖不代表正确，也不能只对空洞补射线。

首版有两种可验证选择：

1. **当前静态几何 raster depth/G-buffer**：每个显示帧获得当前可见性，重投影旧照明，比较深度/法线/材质与代次，再对无效照明做 RT 修补。Alpha-test 植被必须匹配材质覆盖。
2. **当前全屏廉价 primary rays**：不追后续 GI、阴影和体积，仅取得当前表面；按需更新昂贵照明。它仍然追踪了所有主射线，但可能大幅减少次级射线。需要先测主射线与完整着色成本差异。

只用旧深度的路线可以做纯旋转演示，平移时必须将遮挡边缘及保守风险区纳入 repair，并接受没有严格可见性保证的局限。单层深度对于玻璃、水和多个遮挡层不够；首版暂排除这些区域。

主射线修补只得到新表面，还需要材质和照明：有空间缓存时查询漫反射，缓存未命中时补直接光/GI，不能以 primary hit 完成判定整像素修复。

## 3. 性能预算

根据 `docs/rt-other-overheads-2026-10-05.md` 的不同窗口，RT trace 中位数为 36.339 / 41.556 ms；不是同场景 NRD A/B，但足以说明数量级。

完整追踪 30 次/秒，仅 trace 就需要 1090–1247 GPU ms/秒，尚未计入重投影、重建、BVH、显示及其它工作。完整追踪 60 Hz 更不成立。必须降低每次更新成本、降低完整关键帧频率、缩小更新区域，或者复用世界照明避免周期性完整更新。

单 GPU 的理想吞吐预算可写为：

`f_key * C_key + (f_display - f_key) * (C_warp + C_visibility + C_repair + C_reconstruct) + W_background <= 1000 ms/s`

这里避免把关键帧与非关键显示帧的完整修补重复计费。多队列可重叠部分工作，但不产生免费的 GPU 算力；上式仍只是粗略串行预算，不能替代实际并发与排队测量。

120 Hz 每帧 8.33 ms；240 Hz 每帧 4.17 ms。平均利用率符合预算也不保证截止时间：一次长达 36 ms 的不可及时让出的 RT 工作会阻塞显示。需要 tile/batch 调度、短提交、已完成快照和适当同步，而不只是隔几帧调用原 trace。硬件/驱动是否及时抢占不得假定。

## 4. 宽 FOV 的隐藏成本

固定宽高比、同时保持中心角采样密度时，扩大指定水平或垂直 FOV，需要两维同步增加采样数：

`N_rt / N_display = [tan(FOV_rt/2) / tan(FOV_display/2)]^2`

以显示 90° 为例：110° 约 2.04 倍像素，120° 3 倍，130° 4.60 倍。这里假设等中心采样密度的针孔投影；若保持 RT 分辨率，代价变成中心画质/采样密度下降，而不是免费边界覆盖。

建议先使用较小 guard band、外围较低采样密度或局部补块，再衡量覆盖收益。FOV 变化还会改变采样足迹与细节需求；旧样本覆盖不意味着放大后的高频纹理已经足够。

## 5. RTset 当前接入条件

- `RayTracingVulkanPass.java:3486` 全屏 `vkCmdTraceRaysKHR`，随即执行 `fsr.recordAfterRayTracing`。需要新增不依赖全屏 trace 的显示路径及稀疏 repair 列表；不能仅在 raygen 对大多数像素 return 就假定成本按比例下降。
- `RayTracingVulkanPass.java:2835` 附近等待上一提交，使用 Minecraft 帧拥有的 encoder 和 fence。原接口没有独立后台 RT 队列/完成快照机制；不能贸然绕过等待，因为 mapped buffers、TLAS 和帧图资源依赖它。
- `:2982` 的 replayLastDisplay 只是复制旧显示图，没有按最新 pose 重投影。
- `RayTracingShaderRaygen.java:2455` 附近已有当前/上一相机、主命中位置、反向深度和运动向量，可作为原型基础。
- `fsr/RtestFsr3.java:314` 将 raygen -> NRD -> aerial -> FSR 串接。需独立 RT采样序号、照明更新序号、显示序号，以及 history epoch/pose/generation。
- `fsr/RtestFsr3Upscaler.java:304` 每次更新历史、jitter 和上一相机。不能每个显示帧把重投影旧照明作为全新独立采样，提高虚假的样本置信度。
- `prime/shaders/nrd_motion.comp` 假设当前主命中对应当前相机。需要以新可见性生成每个显示/更新所用 guides，不能混用旧 depth 与新 motion。
- 最近的 `RadianceCacheExperiment` 每次清空，使用合成目标，尚未采集真实路径，也没有跨帧世界状态；只是后续缓存 kernel 的算法基础。

## 6. NRD、大气与视角相关贡献

NRD 可用于降噪新的照明样本，但不直接接收任意稀疏有效 mask 就自动正确重建。官方 reduced-resolution/checkerboard 路径是明确布局合同，不能等同于不规则 repair list。首版可在真实照明更新时降噪并保存可重投影结果；快速显示帧做自己的有效性/重建，而不反复把旧采样当成新证据。[NRD 官方合同](https://github.com/NVIDIA-RTX/NRD)

显示运动向量须描述相邻显示 pose，真实采样 history 也需要独立的 sample age。FSR 要求当前深度、当前颜色和配套 motion/reactive 数据；重建图若进入 FSR，需要一致的合成当前状态与低可信 mask，并验证双重历史滤波/相位问题，不能沿用旧 token。[FSR 输入合同](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-temporal/)

复用最终带雾颜色会把旧路径的大气带到新相机。应保存表面照明，并按新视线合成 `surface * T_new + L_new`。镜面/反射、透明、草木特殊散射、POM 和高频遮挡另有失效条件。实体阴影仍需保持现有独立处理要求，首版冻结/排除复杂动态影响。

## 7. 建议的原型路线

1. **阶段 A：静态、固定照明、纯旋转。** 重投影一套已完成的 surface history；分离 HUD/手持物；较小 guard band。对照完整当前视角结果，验证采样密度与 pose 延迟。
2. **阶段 B：平移与当前可见性。** 加 raster G-buffer 或廉价全屏 primary，检查新遮挡、空洞、法线/材质差异；compact repair list，trace/shade 必要区域。照明缺失也算待修补。
3. **阶段 C：预算调度。** 将 RT 工作切成小批次，使用至少 read / write / ready 快照，显示只消费已完成资源。超过预算的修补必须记录拖欠和历史年龄，不能靠延后掩盖错误。
4. **阶段 D：真实空间照明缓存。** 采集尾部目标，处理 generation 与局部失效；再逐项增加特殊材质、环境变化及动态影响。

## 8. 验收指标

保留用户的 New Ray Ratio，但至少分成：primary visibility ratio、expensive shading repair ratio、实际 traced rays 总数（按 primary/shadow/GI/volume 分类）。像素百分比不会反映每像素不同射线成本，稀疏随机访问也可能降低 GPU 利用率。

另外测量：每阶段 GPU time；快照年龄 p50/p95/max；输入采样到显示延迟；ready-wait/queue delay；missed display deadline；真实错误覆盖率与空洞率分别统计；repair backlog；静止时误判重追踪率；图像边缘/室内漏光；NRD confidence 与更新计数。比较完整当前视角参考、简单旧帧重投影、完整实验三组。

首轮以固定 120 Hz 截止时间作为目标逐步验证；240 Hz 留作测量结果支持后的目标。静态旋转成功不证明平移、更新和动态场景成立。

## 相关一手研究

- [NVIDIA Post-Render Warp with Late Input Sampling](https://research.nvidia.com/index.php/publication/2020-07_post-render-warp-late-input-sampling-improves-aiming-under-high-latency)：支持把最新输入用于后置相机 warp 的可行性，不能外推为视角无关完整路径追踪。
- [VRWorks Context Priority](https://developer.nvidia.com/vrworks/headset/contextpriority)：显示 warp 的调度与优先级是单独问题；该 NVIDIA 接口不代表 RX 7800 XT/RADV 有相同调度保证。

最终建议：推进静态原型，但优先实现“当前可见性 + 持久漫反射照明 + 有预算的补充着色”，再逐步减少主可见性开销。高显示频率是调度与重建的目标，不能直接换算为同等频率的全新光照信息。
