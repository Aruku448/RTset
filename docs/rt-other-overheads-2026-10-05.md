# 其他性能开销检查（2026-10-05）

本轮检查已推送的 `569424f`，结合最新客户端 02:53:14–02:57:21 的日志与独立 Vulkan 微基准。没有修改运行代码、游戏配置或部署。

## 运行证据

客户端 02:57:24 正常退出。最新窗口共 46 个 GPU 定时样本、23 次场景发布和 CPU merge；13 次实际 GPU 光源树构建。窗口内 NRD 有开关变化，不能把整段视为固定配置对照。

最后日志输出分辨率 2541×718，输出与显示尺寸相同。保存配置为 native_aa、GI=3、太阳样本=4、体积质量=2、POM 开启、LOD 关闭；其中体积质量与 NRD 状态有运行日志确认，其余保存配置不证明整个窗口都未改变。

| 开销 | 取样范围 | 中位数 | 最大值 |
| --- | --- | ---: | ---: |
| RT trace | 02:55:12–02:56:37，NRD OFF，场景规模稳定，19 样本 | 36.339 ms | 37.004 ms |
| RT 后处理（所有后处理，不是单独 FSR） | 同上 | 0.701 ms | 0.712 ms |
| RT trace | 02:56:47–02:57:21，NRD ON，7 样本 | 41.556 ms | 43.193 ms |
| RT 后处理（含 NRD） | 同上 | 3.160 ms | 3.483 ms |
| 场景发布，渲染线程 | 最新窗口，23 次 | 28 ms | 133 ms |
| 场景缓冲阶段 | 同上 | 13.480 ms | 14.345 ms |
| 材质写入阶段 | 同上 | 5.094 ms | 105.493 ms |
| 光源/PBR 上传阶段 | 同上 | 6.948 ms | 12.842 ms |
| CPU merge，worker | 同上 | 56 ms | 96 ms |
| lightTree 阶段，worker，包含提取/排序/打包/复用检查 | 同上 | 55 ms | 94 ms |
| GPU 建树提交与等待，CPU 总耗时 | 13 次 | 2.956 ms | 3.185 ms |

**判断**：稳态最大项仍是 trace；移动/更新卡顿主要关注发布和材质写入。worker 的 lightTree 时间不等于 GPU 建树时间，也不能直接加到渲染线程帧时间。

NRD 两个窗口的相机、动态内容与场景规模不同，不能把 RT 或后处理差值全部归因于 NRD。

## 1. 优先：映射实例写入的读改写

`RayTracingVulkanPass.writeInstanceBuffer()` 逐实例调用 `instanceCustomIndex`、`mask`、`instanceShaderBindingTableRecordOffset` 和 `flags`。

检查实际依赖 LWJGL 3.4.1 的源码后确认：每个 setter 会先读取其 32 位 packed word，再掩码合并后写回；一次完整实例至少四次这样的 mapped-memory 读改写。

`NativeBuffer.create()` 对 host-visible 缓冲使用 `VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE`，没有要求 HOST_CACHED。CPU 读取不可缓存、映射到显存的内存可能非常慢；这一点应与“Java 创建了很多 wrapper”的猜测区分。

独立 RX 7800 XT / RADV 微基准选用 DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT（flags=0x7）缓冲。4,096 个 64 字节实例，5 次预热、31 次测量：

| 写入方式 | CPU 中位数 |
| --- | ---: |
| 模拟同样四次 packed-word 读改写及矩阵/地址写入 | 16.506932 ms |
| 预打包后 memcpy 到 mapped 区（这里只计复制） | 0.011941 ms |

两条路径均包含 store completion fence，最终字节内容相同；CPU 预打包发生在计时之外。该微基准未使用 Minecraft 的实际 VMA allocation，因此不证明可以从游戏中直接省下 16.49 ms；但与持续的 13–14 ms `scene_buffers_ms` 有明确对应机制。

建议下一步：对静态实例在普通内存一次打包 64 字节记录，再批量复制；至少改成完整 packed word 直接写入，避免对 mapped word 的读取。随后针对实例写入、scratch 检查、map/flush 分别计时，确认真实游戏收益。动态实例 writer 已使用 `putInt` 打包字段，不应误认为它也有同样四个 setter。

复现：

```bash
./gradlew gpuLightTreeMathTest -Pgpu_tree_dump=/tmp/rtest-light-tree --offline
cc -O2 -Wall -Wextra -Werror tools/profiling/instance_write_probe.c -lvulkan -o /tmp/rtest-instance-write-probe
/tmp/rtest-instance-write-probe /tmp/rtest-light-tree.spv /tmp/rtest-light-tree.seed /tmp/rtest-light-tree.expected
```

## 2. 材质上传尖峰与容量增长

23 次发布仅 1 次扩容，容量复用已生效。02:54:26 扩容时材质写入 105.493 ms，发布 133 ms；02:57:16 未扩容但增量写入仍耗时 87.150 ms，发布 109 ms。因此剩余尖峰不能全部解释为重新分配。

当前静态材质缓冲容量达到 645,788,224 B（约 616 MiB），live range 最高 583,166,080 B。稀疏 writer 对每个对象身份变化的 section 写完整 material span；捕获 512 个 dirty section 的批次可能复制很大一批材料，即使部分数据事实上不变。

建议记录实际 changed spans、written bytes、flush ranges、map/copy/flush 分阶段耗时，再判断是否需要：

- 复用同一份正向/回滚差分计划，避免 hasChanged 与 writer 两次建表/扫描；
- 对实际输入等价的 section 保留不可变身份，减少无效材料上传；
- 对扩容采用 GPU copy 保留旧 spans，再上传差异，而非重写全部 live 数据；
- 用 staging 或分批发布限制上传尖峰（需保持资源发布原子性）。

87 ms 样本可能包含大数据复制、flush、OS 调度或 GC；日志没有分项与 GC 信息，不能单独指定原因。

## 3. 动态 TLAS 更新频率与统计缺口

场景规模稳定且 NRD OFF 的窗口里，计数器跨度 2,160 个 frame，TLAS updates 同样增加 2,160；BLAS build commands 只增加 109。说明动态 TLAS 更新几乎每帧发生。

`RayTracingDynamicInstances` 初始化 tlasChanged 时遍历所有 registry instance 的 transform/historyReset，包括随后没有 BLAS 地址、被写成 UNTRACED_INSTANCE_MASK 的对象。日志持续出现 player-mesh-missing。未表示对象的变化可能制造额外更新；当前 writer 仍写入它们的真实 transform，因此不能只删掉 changed 判定而不统一未表示实例的写入规则。

候选：将未表示实例规范化成固定、有效且不被任何 RT ray mask 命中的 dummy record，然后比较实际可追踪实例的 transform/地址/材质 base/slot/mask。保持 Vulkan UPDATE 的实例活跃性规则和首次/替换 TLAS 的强制写入。此项尚未做反事实执行测试，不能断言所有逐帧更新都可删除。

**测量缺口已确认**：

- timestamp 0 在动态 BLAS/TLAS 命令之后、trace 之前；`total_ms` 是 0→3，因此不包含此前 AS 构建，也不包含先前天空 LUT。
- timestamp 4→5 覆盖的区间含 guide preparation、动态 BLAS/TLAS 和可选 terrain traversal；名称 `terrain_traversal_ms` 并非纯地形计时。
- `terrainTraversalEnabled=false` 时 4→5 被强制报告为 0，动态 AS GPU 耗时就被隐藏。

下一次应分别报告动态 AS 与 terrain traversal，另给出 RT pass 从首命令到末命令的总区间。CPU fence wait 中位数约 34.71 ms 是等待已有 GPU 工作，不应与 GPU `total_ms` 再相加。

## 4. 稳态 trace 与其他 GPU 开销

GI、多太阳样本、RGB 透明阴影、面积灯/天空 NEE、POM、物理体积积分均在同一个 trace 中，现有日志不能精确拆分各自耗时。体积质量=2 对应 8 个积分点，局部体积光 2 个样本；树采样深度、cutout Any Hit 和多个 bounce 会放大每像素工作量。

POM 的整数包裹约简、动画预处理、平坦高度早退，以及 OFF 时裁剪降噪 AOV 已实现，不应重复列为缺失优化。实体阴影的静态差分查询也不是每条射线无条件翻倍，只在动态遮挡命中等条件下发生。

下一轮 trace 应做固定相机/世界/种子与相同分辨率的功能开关对照，优先区分物理体积、POM、太阳样本和 GI；仅关掉功能得到的收益不等于等价优化收益。

天空 LUT 在稳定 OFF 窗口中位数 0.575 ms，最终 NRD 窗口约 1.126 ms。当前 history 按 eye radius 和 source elevation 的精确 float bits 缓存，太阳持续变化时不能复用。可考虑输出误差有界的时间/角度更新预算；未经误差验证不应简单量化 key 或直接删除月光 LUT。

## 5. 全局光源输入仍有更新开销

13 次变更均为 13 dispatch，host 输入 36.5–41.6 MB。每次变更新分配独立完成树缓冲，GPU allocation reuse=false，符合当前回滚安全规则。

worker 仍提取所有 emitters、建立 Morton keys、排序并创建完整 seed；部分只改 1–2 个 section 的 merge 也出现 58–59 ms lightTree，而完成 seed 的 packed words 最终与旧树相同、渲染端可复用。说明“输入判定/复用机会、CPU seed 重复生成”仍值得优化；没有细分数据不能把这全部归因于排序。

分 section 的 emitter cache、物理等价输入的规范化、两级光源树、仅 material map 变化时 device-copy 树 prefix，是候选。双/多缓冲复用也可降低分配，但必须保护未完成帧与发布失败回滚。

## 优先顺序

1. 预打包静态实例，消除 mapped 内存读改写；补动态 AS GPU 计时。
2. 精确统计并削减材料 changed spans、写入量与扩容全量复制。
3. 对未表示动态实例做规范化，减少无效 TLAS 更新。
4. 固定场景拆分 trace 开销，再决定体积/POM/光源采样优化。
5. 增量 emitter cache 与完成树双缓冲。

原始样本与统计：`docs/profiling/2026-10-05-other-overheads.json`。
