# Persistent RT World：首阶段运行实现

日期：2026-10-05。研究分支：`persistent-rt-world-research`。

## 实际交付

每个显示帧继续计算当前 primary visibility、材质、直接太阳/月光/天空/面光源、实体和动态阴影。仅在静态、非发光、不透明、非草木、非金属、roughness >= 0.8 的主表面选择 diffuse proposal 时，查询持久的间接路径尾部缓存。缓存失效或未成熟时执行原来的 continuation。镜面 proposal、透明、水下、实体主表面、特殊材质和诊断视图仍走实时路径。

缓存目标是**带主表面 PBR 权重、以 diffuse proposal 为条件的路径尾部贡献**，保存 diffuse/specular 两个分量。它不是通用 irradiance，也不是完整屏幕颜色。保留原来的混合 BSDF PDF、RR 和介质吞吐权重，不除以反照率，不重复累加主表面 NEE。specular proposal 完整追踪，因此缓存的条件期望只替换对应 proposal 的尾部。

训练的尾部忽略动态二次几何及其阴影；实体 primary/direct 和直接动态阴影继续按当前帧计算。**动态实体对间接照明的影响尚未实现**。启用实验会改变这部分间接照明，不能将它当作与完整实时参考完全等价的优化。

## 资源与同步

- Binding 41：32 个 uint 的控制/统计头，两个 bank，每个 bank 65,536 个 24-word 槽。约 12 MiB；禁用时只分配 128-byte dummy。
- 当前 GPU 帧退役后 CPU 更新头；仅训练刷新帧将 read bank 复制到独立 write bank，RT 只查询 read bank；其余帧不复制也不交换 bank。
- 在追踪尾部前，每个槽每个训练帧最多一个 invocation 通过 CAS 获得写入权；唯一槽位预留使用原子 ticket，接纳数严格不超过配置预算（默认 4096）。其他 invocation 不读取该 write 槽的键和值。跨帧发布依赖现有提交、barrier 和 fence，而非槽 owner 字段。
- 训练帧提交成功后选择下一读 bank；下一帧使用它前等待上一帧结束。场景或光照失效时清空两个 bank。
- 最多累计 16 个更新样本，随后使用 1/16 EMA；样本非有限、负值或任一通道超过 8192 时拒绝训练。有效零值可命中。
- 每个槽最低默认 4 个更新样本才可复用。默认最大年龄 500 ms，按实际单调时钟计算，支持 uint 毫秒环绕。更新批次默认间隔 50 ms，低显示帧率会延迟批次；成熟槽在更新帧按屏幕像素和 epoch 轮换选择约 1/16 强制刷新。

键包含 0.5-block 空间单元、量化法线、量化观察方向、精确 RGB/roughness/reflectivity。观察方向属于键，承认目标的 PBR 视角依赖；正常相机移动不全局清空缓存，新键未命中后补路径。Hash 冲突进行完整键比较，不将碰撞值当作命中。空间、方向量化会带来偏差，且相邻表面存在漏光风险；仍需要游戏内对照。

几何对象替换（包括保持 temporal revision 的增量区块更新）、大气 LUT 对象替换、启停及照明状态改变触发清空。方向、太阳强度、天气和天空盒不透明度采用有限量化容差；其余相关 camera 照明/材质参数精确比较。新视线的大气仍按当前帧计算。

## NRD 合同

缓存命中绕过 NRD，借用已有 unfiltered emission/composite 通道累加复用贡献，不将同一个缓存估计反复作为新 Monte Carlo 观测。新直接照明和真正新采样的路径仍保留原来的 NRD AOV；实体直接阴影的 signed residual 保持现有独立处理。缓存目标在大气前生成，当前视线合成仍为 `surface * T_new + L_new`。切换评估模式或启停实验请求屏幕历史重置。

命中与未命中的 AOV 分配不同、条件 proposal 选择、离散键与 EMA 均可能造成闪烁或收敛差异。这一合同通过编译和源契约检查，尚未获得游戏内运动画质验证。

## 使用与测量

按 F9，进入“路径追踪”，第一项“持久 RT 缓存（实验）”可直接开关。返回游戏后生效，关闭设置界面沿用原有配置保存流程。开启时选择 `full` 渲染模式，避免成本探针模式禁用缓存。

`rtest-client.toml` 的 RT 配置区：

```toml
persistentRtEnabled = true
persistentRtStatistics = true
rtEvaluationMode = "full"
persistentRtUpdateIntervalMs = 50
persistentRtMaxAgeMs = 500
persistentRtMinimumSamples = 4
persistentRtTrainingBudget = 4096
```

默认 `persistentRtEnabled = false`、`persistentRtStatistics = false`。此次不替换正式实例 mod。

成本对照模式：

| 模式 | 用途 |
| --- | --- |
| `full` + cache off | 原完整路径参考 |
| `full` + cache on | 持久间接尾部实验 |
| `current_direct` | 当前 primary + direct，无 continuation 的成本探针 |
| `current_visibility` | 当前 primary + 材质，无 direct/continuation/大气的成本探针 |

后两者是成本探针，终止路径会改变 MIS 和图像，不作为画质等价参考。`current_visibility` 仍有材质计算、后处理和相关资源更新，并不等于孤立 primary traversal benchmark。

开启统计时每 256 个像素抽样一个 invocation，约每 120 帧日志包含 sampled_queries、sampled_hits、sampled_primary、sampled_secondary、sampled_writes。统计数不是全图射线数；primary/secondary 不包含 shadow/volume，hit ratio 只针对尝试查询的 invocation，所有符合条件的主表面都先查询缓存，强制刷新也计入命中分母。新增 sampled_fallback（查询未命中后继续追踪）、sampled_forced（已命中但获得训练预留）、sampled_claim_losses、sampled_budget_denied；exact_reservation_attempts 与 exact_admitted 是全图唯一预留计数，后者包括随后被拒绝的非有限训练目标。它们不是完整 shadow/GI ray 数。`snapshot_copy_bytes` 直接报告本帧记录的复制字节数，非刷新帧为 0。

GPU 日志新增 `pre_trace_ms`（terrain/AS/缓存准备等）与 `rt_pipeline_ms`（大气预处理至 RT 输出复制结束），另有 `cache_prepare_ms`（transfer 阶段的缓存准备区间，含清空/复制与统计头重置）。后者不包含 Minecraft 全部渲染或显示等待。持续记录命中率和完整 GPU critical path，比较 warmed cache 的相同轨迹；默认开关关闭不代表开启后必定更快。

## 已验证

- `./gradlew check jar --offline`：61 项 task 的构建/检查流程通过。
- actual raygen 两种大气 variant 编译；raygen/miss/hit payload 新字段契约检查通过。
- policy：相机位姿不进入光照 identity、强度容差、强度变化失效、uint age 环绕、评估模式 ABI。
- 实际 runtime cache GLSL 的独立 Vulkan GPU 回读：RX 7800 XT，69 个并发训练 invocation、7 个查询，比较 13,575 words。覆盖重复槽竞争、有效黑值、样本不足、过期、full-key 碰撞、NaN 拒绝，以及训练后仍读取旧快照；零不合格差异，浮点平均最大误差 1 ULP（限 2 ULP），整数与键精确比较。
- 该小 fixture copy/train/query GPU median 约 0.021 ms，**不是 12 MiB 实际缓存成本或游戏加速数据**。

复现 GPU 测试：

```bash
./gradlew persistentRtMathTest -Ppersistent_rt_dump=/tmp/persistent-rt-cache --offline
cc -O2 -Wall -Wextra tools/gpu_persistent_rt_smoke.c -lvulkan -o /tmp/gpu_persistent_rt_smoke
/tmp/gpu_persistent_rt_smoke /tmp/persistent-rt-cache.{spv,seed,expected}
```

## 尚未完成的后续阶段

尚无独立 30–60 Hz RT world worker、异步队列、严格 GPU 更新预算、compacted repair list、区域失效或独立高频显示重建。每帧仍有完整 primary/direct 与部分实时 continuation；静态尾部训练预留有槽位数预算，但刷新帧和其他帧的 live fallback 没有总射线或毫秒预算。当前实现提供运行接缝、可靠快照和成本探针，不能宣称达成 120–240 Hz。

下一阶段优先采集游戏内固定轨迹的数据，验证偏差与缓存收益，再将失效/训练任务 compact 成短批次并加入更新预算。动态间接影响需单独设计，不能简单缓存完整实体阴影。

## 追加：训练预算与完整 bank 回读

刷新前获得槽位和全局 ticket，超出训练预算则：已有有效缓存继续复用；未命中仍走完整 live fallback，不为了满足预算写黑色。抢到槽但训练目标不合法仍消耗该预留，保证额外训练路径数不突破上限。预算为 0 时不会提交训练目标。当前采用先到先得，不保证每个空间槽公平更新；高预算压力下槽可能迟迟未成熟或超过年龄，这需要日志和后续 compact/priority 调度来解决。

预算并不表示总 GI ray budget：未命中时重复像素仍可能执行 live fallback，且每条尾部可含多个 segment/NEE。单帧 GPU deadline 和严格时间预算尚未实现。

GPU 测试扩展为四组：原并发 snapshot、串行确定顺序的预算 2/预算 0、运行尺寸 65,536 槽双 bank。运行尺寸测试比较 3,147,015 words，零不合格差异（浮点最大 1 ULP）；6 MiB read→write copy 加 69 个训练/7 个查询的 median 约 0.36 ms。预算顺序 fixture 故意串行以验证确切接纳对象，不将它的时长当作并行吞吐性能。数据来自独立 Vulkan compute fixture，不包含实际 RT tail 或游戏帧率。

生成 fixtures 后分别运行 `/tmp/persistent-rt-cache`、`/tmp/persistent-rt-cache-budget`、`/tmp/persistent-rt-cache-zero`、`/tmp/persistent-rt-cache-large` 对应的 `.spv`、`.seed`、`.expected` 文件即可。

## F9 设置布局修复

修正 Minecraft 26.2 列表构造 API：传入 viewport 高度与默认行高，避免滚动区域覆盖底部“完成”按钮。面板居中，宽度随 GUI 缩放后的屏幕自适应（最大 1000）。窄屏分类按钮分两行，设置单列；面板宽度达到 600 时设置双列，统一 30 的行高和 10 的列间距，底部保留独立区域。

`settingsLayoutTest` 检查 6572 种 GUI 尺寸下的内容、分类、控件列和 footer 边界，并核对实际列表调用点。编译和 JAR 构建通过，尚无游戏内视觉截图验收。
