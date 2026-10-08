# RTest 分阶段性能测量（2026-10-07）

## 测量状态

已取得 21:01:53–21:03:31 的 41 组有效 GPU 采样，均为 854×480、ReSTIR mode=1。期间 F8 关闭后重新开启，且动态对象数量、场景几何发生变化，因此全程不能作为单一静止基准。以下另外列出最后 30 秒窗口（21:03:01–21:03:31，10 组），反映这次测试末段负载，不保证所有场景状态完全相同。

设备：AMD Radeon RX 7800 XT，RADV NAVI32，16 GiB 显存。

实例当前设置：ReSTIR DI 开启、suffix 关闭、11 个新候选、2 个空间邻居；GI 4 层、太阳阴影 4 次、体积质量 2；FSR native AA、NRD 关闭。suffix 的 gatherPrefixes=2 当前不参与渲染。

## 本次实测结论

末段 GPU 命令总耗时中位数 **19.477 ms**，P95 **20.130 ms**。光追 dispatch 为 **16.837 ms**，逐样本占总时间的比例中位数 **86.22%**。动态 BLAS **1.763 ms**，TLAS/同步 **0.486 ms**；FSR 各步骤逐帧求和后中位数 **0.152 ms**，后处理 **0.265 ms**。这些只是本 mod 队列区间，不包含 Minecraft 全部渲染和呈现等待，不能直接换算为游戏 FPS。

本轮能定位到主要 GPU 成本集中于光追，另有显著的动态几何更新成本。**尚不能把光追成本归因为硬件限制，或单独归因为 ReSTIR。** 没有关闭 DI 的同场景对照；GI、材质、太阳、体积和 DI 仍在同一 dispatch 内。

| 连续 GPU 阶段 | 末段中位数 ms | 末段 P95 ms |
|---|---:|---:|
| atmosphere | 0.000 | 0.000 |
| terrain | 0.000 | 0.000 |
| rt_inputs | 0.000 | 0.000 |
| dynamic_blas | 1.763 | 1.857 |
| tlas_sync | 0.486 | 0.512 |
| rt_bind_sync | 0.000 | 0.000 |
| trace | 16.837 | 17.302 |
| rt_compute_sync | 0.014 | 0.014 |
| denoise | 0.000 | 0.000 |
| aerial_composite | 0.016 | 0.017 |
| fsr_prepare | 0.007 | 0.008 |
| fsr_inputs | 0.019 | 0.019 |
| fsr_luma_pyramid | 0.006 | 0.006 |
| fsr_shading_pyramid | 0.023 | 0.024 |
| fsr_shading_change | 0.003 | 0.003 |
| fsr_prepare_reactivity | 0.019 | 0.020 |
| fsr_luma_instability | 0.009 | 0.009 |
| fsr_accumulate | 0.051 | 0.053 |
| fsr_rcas | 0.013 | 0.014 |
| debug_offline | 0.000 | 0.000 |
| post_display | 0.265 | 0.272 |
| output_copy | 0.005 | 0.006 |
| command_total | 19.477 | 20.130 |

0.000 表示日志毫秒保留三位后的值，不保证没有任何指令或等待。大气 LUT 在稳定帧可能复用缓存；terrain GPU traversal 本次关闭；NRD 关闭。

| 后处理内部阶段（已包含在 post_display 中） | 末段中位数 ms | 末段 P95 ms |
|---|---:|---:|
| setup | 0.001 | 0.001 |
| exposure_focus | 0.019 | 0.020 |
| dof_coc | 0.049 | 0.051 |
| dof | 0.096 | 0.099 |
| motion | 0.009 | 0.021 |
| bloom_pyramid_blur | 0.058 | 0.060 |
| grade_tonemap | 0.023 | 0.024 |
| sharpen_dither | 0.009 | 0.010 |

全程 41 样本的总区间中位数 12.648 ms / P95 22.969 ms，光追 10.574 / 19.076 ms；光追范围 3.976–19.650 ms。全程与末段差异不能解释成固定算法加速或降频。39 个查询结果有效，无负区间；逐帧主区间求和与日志总数最大误差 0.004 ms，符合三位小数舍入。

### 可确认的实现成本

- 日志 21:03:11–21:03:21 中，BLAS build commands 从 41914 增到 49834，对应 360 帧，恰好 **22 次/帧**；TLAS 从 1941 增到 2301，恰好 **1 次/帧**。`RayTracingVulkanPass` 对新建或 pendingUpdate 的动态 BLAS 执行 BUILD；不是 Vulkan UPDATE/refit。大量动态更新是可确认的工作量来源，但并未证明全部更新是冗余的。
- `DynamicCachedBlas.updateVertices` 已有逐数组相等检查；不能声称所有不变对象都无条件重建。需进一步定位哪些动画/模型产生了变化，以及它们是否适合 UPDATE 或局部坐标下仅更新实例变换。
- 末段 CPU 动态更新中位数 1.217 ms / P95 1.516 ms，命令录制 0.234 / 0.253 ms。fence 等待中位数 0.009 ms / P95 5.334 ms，最大 10.657 ms。不能与 GPU 总数相加。
- 场景几何在 21:03:04 和 21:03:23 仍发布并触发 temporalReset，日志每次约 2 ms CPU。这种非每帧工作可能打断时域积累，不能只看抽样 CPU 中位数为零就忽略。
- DI 保持 11 个新候选、2 个邻居，suffix 关闭。两 bank 总 25.02 MiB；本场景不是先前高分辨率的 219.375 MiB。不能拿容量当作带宽消耗。

### 硬件归因仍缺证据

硬件文件采样时间是 20:33–20:37，游戏有效测量是 21:01–21:03，**没有重叠**。当前报告没有游戏运行时的频率、功耗、温度或忙碌率；不能排除降频、竞争负载、功耗限制，也不能确认显存带宽瓶颈。上一次采样不能套用到此次测试。

后续应优先做固定场景 DI 开/关及候选 11→1 的对照，并同步覆盖整个运行期的硬件采样；再分别改变 GI、太阳及体积预算。动态 BUILD 的 22 次/帧则应增加按模型分类的更新统计，确认必要性后再优化。

实测逐项数据保存在 `docs/profiling/2026-10-07-gpu-step-measurements.json`，原始游戏日志备份在 `tmp/profiling/measured-latest.log`。

## 历史采样能证明什么

来源：`tmp/profiling/previous-latest.log`，每 120 帧输出一次。

| 模式及尺寸 | 样本 | 光追中位数 | 光追后整段中位数 | 原计时总段中位数 |
|---|---:|---:|---:|---:|
| DI 开，854×480 | 99 | 7.469 ms | 0.430 ms | 7.900 ms |
| DI 关，854×480 | 27 | 7.039 ms | 0.421 ms | 7.463 ms |
| DI 开，2560×1404 | 7 | 49.066 ms | 3.186 ms | 52.144 ms |

这些记录包含相机、场景和窗口变化，不能把两组差值解释为 DI 的净成本。2560×1404 时成本主要集中在光追 dispatch 内部；不能仅凭这个数字进一步区分遍历、材质计算、寄存器压力或显存延迟。

原 `total_ms` 从光追开始到输出复制结束，未包含前面的大气、地形遍历、BLAS/TLAS 工作。原 `terrain_traversal_ms` 实际混入输入准备和加速结构更新，不能作为单独地形耗时。

CPU 历史 133 个抽样中，动态更新中位数 0.340 ms / P95 0.977 ms，命令录制 0.105 / 0.224 ms，fence 等待 0.010 / 10.297 ms。CPU 与 GPU 可重叠，不能直接把两者相加；fence 等待也不是新增 GPU 算法成本。

## 新版本计时边界

`RTest gpu_steps` 输出以下连续队列区间，逐帧相加等于 `command_total_ms`：

| 日志字段 | 工作及归因边界 |
|---|---|
| atmosphere | 天空/月亮大气 LUT |
| terrain | 地形 Hi-Z、可见性遍历；重置时工作不同 |
| rt_inputs | 光追输入/guide 资源准备 |
| dynamic_blas | 动态 BLAS 构建及前置依赖 |
| tlas_sync | TLAS 更新及同步 |
| rt_bind_sync | 光追资源绑定、reservoir 依赖及 SBT 准备 |
| trace | 整个光追 dispatch：相交、材质、光照、GI、体积与 ReSTIR |
| rt_compute_sync | 光追写入到后续 compute 的依赖 |
| denoise | NRD；关闭时没有降噪工作 |
| aerial_composite | 大气透视合成 |
| fsr_prepare | FSR 资源及常量准备 |
| fsr_inputs | FSR 输入准备 |
| fsr_luma_pyramid | 亮度金字塔 |
| fsr_shading_pyramid | shading 金字塔 |
| fsr_shading_change | shading change |
| fsr_prepare_reactivity | reactivity 准备 |
| fsr_luma_instability | luma instability |
| fsr_accumulate | FSR 时域累积 |
| fsr_rcas | RCAS |
| debug_offline | 调试视图及离线累积，普通模式不执行这些工作 |
| post_display | 全部后处理/显示变换 |
| output_copy | 输出复制及相关依赖 |

`RTest gpu_post_steps` 是 `post_display` 内部的子区间：setup、exposure_focus、dof_coc、dof、motion、bloom_pyramid_blur、grade_tonemap、sharpen_dither。**不能再把这些子区间加到主表总计。** 后处理关闭时子查询仍写入以保证结果可读，但对应算法没有执行。

计时采用队列阶段完成时间，包含区间内依赖等待，不是各执行单元的独占忙碌时间。没有额外插入 barrier；query 本身仍存在测量开销。采样每 120 帧一次，P95 是被采样帧的分位数，不是所有呈现帧的 P95。

## 算法成本与硬件限制的判别

现有代码可以确认的算法因素：DI 的 11 个新候选增加目标函数/材质读取工作，另有时空 reservoir 读取，但不等于 11 条可见性阴影射线；当前 suffix 关闭，不应把 suffix replay 开销算到当前帧。高分辨率也增加路径数量和 reservoir 容量；2560×1404 的 DI 双 bank 共 219.375 MiB，这只是分配容量，不能推导每帧显存带宽。

需要固定相机、分辨率和场景后分别降低 DI 候选、GI 层数、太阳采样及体积质量，记录边际耗时变化。这些实验会改变质量且存在交互项，差值不能当作完全可加的每条 shader 指令耗时。

硬件采样同时记录设备级 GPU busy、VRAM busy、显存容量、核心/显存频率、温度和功耗。设备忙碌率不能区分 RT 单元、ALU、缓存及寄存器瓶颈；VRAM busy 也不是直接的 GB/s。只有固定场景数据才能判断是否存在持续低频、容量压力或接近功耗限制。本次硬件采样未覆盖游戏负载，不能据此判定显卡限制。

光追 dispatch 内部各材质/太阳/GI/体积子步骤目前没有独立 GPU query 边界，需要受控消融或 shader/vendor profiler；本轮不会虚构这些步骤的毫秒值。

## 采样文件与复算

硬件数据：`tmp/profiling/gpu-hardware.jsonl`。

```bash
python3 tools/profiling/summarize_gpu_steps.py \
  /home/aruku/.minecraft/versions/RTest/logs/latest.log \
  --hardware tmp/profiling/gpu-hardware.jsonl --date 2026-10-07
```

脚本按尺寸和 ReSTIR 模式分组，统计各阶段中位数/P95，检查负区间及总和误差。相同分组内的设置/场景变化仍需人工隔离，不会自动变成受控实验。
