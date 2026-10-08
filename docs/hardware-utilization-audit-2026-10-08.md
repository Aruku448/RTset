# 硬件利用与全链路审阅（2026-10-08）

## 结论

**当前测试场景主要受 GPU 执行成本限制，GPU 并未明显等待 CPU 供给；但这不等于每个硬件单元都已高效利用。** 本轮进一步确认 CPU 模型累计路径的 O(n²) 数组复制，是此前 dispatch 计时没有覆盖的浪费。GPU 的主问题仍在 trace 内部；不能把高 busy 或功耗直接解释成 ALU、RT 遍历或带宽达到峰值。

生产代码、实例 JAR、画面配置、时钟和功耗上限均未修改。只新增被动采样脚本并运行性能记录，没有结束旧游戏进程。

## 采样条件与实测

用户确认进入场景后被动采样约 90 秒，前 12 秒剔除。稳定区间 00:05:40–00:06:57，75 个约 1 Hz 的硬件样本、17 个 GPU 阶段样本、18 个 CPU dispatch 样本。GPU 日志覆盖的子区间关联 69 个硬件样本。1009×1199，baseline，ReSTIR mode=0，NRD 已启用；与之前 854×1074、NRD 关闭的报告不构成优化前后对照。

| 指标 | 中位数 | 解释 |
|---|---:|---|
| 整卡 GPU busy | 98% | 整卡忙碌时间，不是有效算力占比 |
| 当前游戏 DRM gfx 引擎活动占比 | 95.2% | 稳定区间累计引擎时间差/墙钟时间；说明主要 GPU 负载来自当前游戏 |
| 核心时钟（sysfs） | 2373 MHz | 自动调频，没有改频率设置 |
| 功耗/预算（驱动传感器） | 211 / 212 W | 已接近该传感器对应的功耗预算，不等于证明全部计算单元饱和 |
| 显存使用/容量 | 约 6.1 / 16 GiB | 整卡读数，未见容量耗尽 |
| mem_busy | 22% | 活动时间指标，不能当成峰值 GB/s 的 22% |
| edge / hotspot | 58 / 83°C | 未记录本采样区间的 thermal/GPU reset/fault 日志；不等于排除所有温度/功耗调频因素 |
| PCIe | 当前与最大均为 16.0 GT/s ×16 | 未见链路宽度或代际下降 |
| CPU-visible VRAM aperture | 约 16 GiB | 可见窗口覆盖显存，不是小 BAR 窗口配置 |

DRM 引擎时间按 client-id 与设备去重，含义依据 [Linux DRM client usage stats](https://www.kernel.org/doc/html/latest/gpu/drm-usage-stats.html)。它只表示执行时间，不能提供 shader active-lane、VGPR、缓存命中或 RT 遍历吞吐。

| GPU 步骤 | 中位数 ms | P95 ms |
|---|---:|---:|
| 动态 BLAS | 0.149 | 0.152 |
| TLAS 与同步 | 0.334 | 0.338 |
| 光追 dispatch | 31.586 | 31.915 |
| 降噪 | 1.451 | 1.485 |
| 空中透视合成 | 0.052 | 0.053 |
| FSR 累积 | 0.139 | 0.141 |
| 后处理/显示 | 0.591 | 0.599 |
| GPU 命令总区间 | 34.515 | 34.869 |

光追约占命令区间 91.5%。各步骤中位数不能严格相加；其他 FSR 等步骤在原始 JSON 中。仅这段 GPU 预算约对应 29 次/秒，不是实际完整游戏 FPS。当前优先优化 shader/遍历工作，而不是继续把 0.149 ms 的 BLAS 当成最大问题。

## CPU 与内存

5700X3D，8 核/16 线程。稳定区间当前游戏 CPU 约 85.6%，其中 Render thread 约 70.5%、Server thread 约 6.4%；100% 的口径为一个逻辑 CPU。当前游戏 VmSwap=0，major fault 增量=0。

dispatch 的动态数据更新中位数 0.982 ms，命令录制 0.254 ms，fence 等待 10.340 ms。fence 等待是等待前一帧 GPU 使用共享资源完成，不能与 GPU 区间简单相加或解释成可删除的额外 10 ms。捕获可与前一帧 GPU 重叠，但当前共享资源复用要求等待；要增加 frames in flight，必须先版本化上传、AS 生命周期、query、temporal history 等资源。

其余 5 个旧 RTest 进程在采样时 CPU 各约 0.06–0.09%，未读到 DRM 客户端，主要占用大量 swap。全系统同期有 1,313 页 swap-in、24,018 页 swap-out，但当前游戏没有 major fault，不能据此说它正受换页阻塞。旧进程的存在不等于其他游戏正在抢 GPU。

另外单独执行 15 秒 `perf stat`：用户态约 49.8 G cycles / 73.7 G instructions，IPC≈1.48；generic cache-miss/reference 约 10.5%。这些是进程 CPU 计数，不是 GPU 计数，也不能仅凭 IPC 或 generic cache 事件认定 DRAM 带宽瓶颈。

## 已确认的 CPU 数据构造问题

另行录制 20 秒 JFR profile，不能将其与未开启 JFR 的 GPU 基线直接比较。1428 个渲染线程 execution/native 样本中，299 个栈落在 `ConcurrentHashMap.putVal → LivingEntityGeometryAdapter.publishModel → wrapCustom`；还有矩阵变换、FloatArrayBuilder、copyOf、retag、纹理标识解析等热点。采样栈占比不是精确毫秒，也不能据 putVal 栈认定锁竞争。

真正的算法问题在 `LivingEntityGeometryAdapter.java:364`：每个片段发布后调用 `PlayerModelGeometryAdapter.append(previous.mesh, tagged)`。append 重新分配、复制此前完整的 vertices/materials，再复制新片段。对于等量片段，这使累计数组复制从 O(n) 变成 O(n²)。

JFR allocation sample 的权重估计约 65.9 GiB，其中 append 路径约 43.0 GiB（65.2%）；这是采样权重估计，不能当作精确字节核算。20 秒内 73 次 GC pause，合计约 141.9 ms，单次最大 3.12 ms。主要问题是高速构造/复制大量短命数组，不是 GC 停顿占据了大部分时间。

例：1000 个等量四边形片段（每片 2 个三角形、顶点与材质共 148 字节/三角形），现有从第 2 片起反复 append 的累计复制约 148,147,704 字节，最终数组只有 296,000 字节。这是明确的算法示例，不是当前模型实测片段数。

优先方案：每个 owner 维护分段/可增长累计器，保留各片段已 retag 的材质，drain 时一次形成完整 mesh；同一帧内不反复发布完整累计数组。texture descriptor slot、顺序、不同 owner、帧边界与失败清理需保留。先将纹理 tag 合并到最终写入，再按真实生命周期复用捕获缓冲，不能直接复用仍被上一快照引用的数组。

## GPU 数据流与硬件路径检查

已合理使用的部分：正常帧将 BLAS/TLAS、trace、降噪/FSR/后处理追加到 Minecraft 的 frame-owned encoder，未为每个 BLAS单独 queue submit；ray pipeline 最大递归深度是 1，路径段由 raygen 迭代；大气 LUT 可缓存；NRD 关闭时 11 张 guide 写入已跳过，static-only 可见性也已有模式判断；NativeBuffer 使用 prefer-device，CPU-visible buffer 不意味着必然位于系统内存。全显存可见窗口适合顺序直写，但尚无每个 allocation 的真实 memoryType/heap 日志，不能证明全部热资源都留在 VRAM。[AMD 内存与提交建议](https://gpuopen.com/learn/rdna-performance-guide/)、[VMA 使用模式](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/usage_patterns.html)。

仍需核验的候选：

1. **静态不透明几何没有硬件 opaque fast path。** BLAS geometry 在创建与 buildInfo 中均 flags=0，任何命中都可能进入 any-hit。主 any-hit/阴影 any-hit 即使早返回，也有 material metadata 读取。`VK_GEOMETRY_OPAQUE_BIT_KHR` 可跳过 any-hit，见 [Khronos 定义](https://docs.vulkan.org/refpages/latest/refpages/source/VkGeometryFlagBitsKHR.html)。但不能整体强制 opaque：capture 故意将非透明 quad 标记 alpha-test，以兼容资源包孔洞；现有 opaqueTerrain 判定也不是“保证无 alpha 孔洞”的证明。必须按实际纹理覆盖、cutout、透射、动态差值需求分类，并使创建、缓存键与重建 flags 一致。未测 any-hit 占比，不给收益百分比。
2. **payload 与 raygen 存活变量。** PathPayload 的源码逻辑字段约 104 字节，shader 内同时管理多个光照/AOV/介质状态；这不是实际寄存器或 continuation-stack 字节。需要驱动生成代码的 VGPR、scratch、active lanes、wave occupancy 和指令/缓存计数，再决定是否拆出更小的续追 payload 或分类调度。不能仅以 SPIR-V 指令数量判断。[AMD RGP ray tracing 分析](https://gpuopen.com/manuals/rgp_manual/events_windows/)。
3. **诊断结果写入整屏，消费者只读中心。** raygen 每像素 pack/clamp 并写 result.pixels；outputBuffer 的实际 CPU 消费仅每 120 帧取中心像素，显示路径来自 FSR display image。此分辨率逻辑写量约 4.84 MB/帧，属于可删除的诊断工作，但不是 31.6 ms 的已证明主要原因。可缩为中心像素专用结果，并只 invalidate 该范围。
4. **正常帧与构建帧需分开看。** 初始化/地形增量 AS 和 GPU light tree 路径仍有 submit+wait。这是本轮静止场景未测到的尾部延迟候选，不应把它解释成正常每帧都有多次提交。AS 大多数 prefer-fast-trace+allow-update，静态 BLAS 的 compaction/flags 可单独评估，但当前显存容量充足，不优先为容量问题改动。

## 计时覆盖的盲区

`dynamicEntityGeometry.collect` 及上游自定义渲染捕获发生在 dispatch 之前，现有 dynamic_update_us 不包含这部分；不能用“动态更新只需 1 ms”说明整个模型准备便宜。

READBACK 只在 pendingFrameTimingFrame%120==0 时发生，而 CPU 日志在当前 dispatch frame%120==0 时打印。正常连续帧中前一帧/当前帧错位，使 readback_us 经常一直显示 0。它不是读回不存在的证据。应增加全区间累计/分位统计并显式关联当前与已退休的帧号，避免周期采样漏掉固定周期工作。

本轮未取得 GPU 硬件级 RT/VALU/缓存/寄存器计数，不能给“已发挥峰值的 X%”。下一步按优先级：修复 CPU 逐片 append → 补齐捕获与读回计时 → 采集驱动支持的 RT shader counters → 评估 opaque 分类、payload 和路径分类。当前 frame fence 及同步不得直接删除。

## 原始证据

- `tmp/profiling/hardware-utilization-20261008-active/summary.json`：GPU/CPU/hardware 与进程统计。
- 同目录 `samples.jsonl`、`gpu-cpu.log`、`hardware.jsonl`、`perf-stat.txt`。
- 同目录 `cpu-profile.jfr`、`jfr-summary.json`、`jfr-allocation-summary.json`：独立的 JFR 证据。
- `tmp/profiling/hardware-utilization-20261008-passive/`：较早暂停菜单采样，不用于光追性能结论。
- 采样脚本 `tools/profiling/passive_hardware_audit.py`，仅记录所选 PID 的状态、累计计数与 DRM fdinfo，不输出命令行。已通过 Python 编译检查，实采两轮；`git diff --check` 通过。

## 后续实施

已完成逐片段模型合并的线性累积与单面初始容量调整，补充上游 custom 回调和 collect/drain 采样；测试和实例部署见 [模型捕获优化记录](model-capture-batch-optimization-2026-10-08.md)。尚未取得新版本游戏内性能复测，readback 周期错位仍待单独修正。
