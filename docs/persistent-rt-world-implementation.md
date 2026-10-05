# Persistent RT World：独立世界照明更新原型

本阶段替换原先 inline、带主表面权重的尾部缓存。世界照明和显示追踪现在使用两个独立 RT dispatch，仍在同一 Vulkan 队列顺序执行。默认关闭，通过 F9 → 路径追踪 → 持久 RT 缓存（实验）开启。

## 当前实现

```text
当前相机 → primary + direct + 第一条 secondary 可见性
                        ├─ 成熟世界缓存 → 当前 PBR 权重 × incident radiance
                        └─ 缺失/过期 → 完整实时 continuation
                        ↓ 发布世界射线种子
独立世界 dispatch（默认间隔 50 ms，最多 8192 seeds）
    → 轮询持久任务表 → 静态多跳追踪 → 写入下一快照
    → 下一帧发布，不依赖任务仍在屏幕内
```

缓存目标是世界坐标位置沿某个入射方向的 `Li`。显示路径使用当前视角的 `f * cos / proposalPdf` 权重，世界任务不包含主表面的观察方向或吞吐权重。天空/发光面作为第一条 secondary 命中时继续实时计算，保留主表面 MIS；实体遮挡第一条 secondary 时也不复用静态估计。镜面 proposal、透明、水下、实体主表面和诊断视图继续实时。

独立任务从种子的第一条 secondary 开始追踪，最多 `giBounces` 个路径段。当前主表面直接照明不包含在缓存内，因此不会重复累加 NEE。世界任务忽略动态二次几何及阴影；更深层动态间接照明仍未覆盖。查询的第一条 secondary 可见性保留实时，命中后省去该表面的着色和后续路径，而非省去全部 secondary traversal。

世界任务的大气查询使用世界顶点高度与共享介质、消光和多重散射表，避免采样显示相机高度的天空 LUT。天空源按单位太阳响应分别计算太阳与月亮，再应用照明强度。使用 8 步大气积分，地表天空边界闭合仍是近似；与显示天空 LUT 不保证逐像素相等。天空 CDF 可以继续作为重要性采样 proposal，它的 PDF 配套使用，不作为世界照明目标。

## 资源与同步

Binding 41：64-word 头、两个 65,536 × 24-word 照明 bank、一个同大小任务 bank、65,536 个紧凑任务索引；约 18.25 MiB。禁用时只使用 256-byte dummy。

1. 上一 GPU 帧退役后，CPU 更新控制头；几何或照明失效时准备清空。
2. 刷新帧复制 read → write（6 MiB），非刷新帧不复制、不交换 bank。
3. 显示 dispatch 只查询旧照明快照，CAS 发布每槽最多一个世界种子。新槽一次性加入紧凑索引表。
4. RT write → RT read/write barrier 后执行世界 dispatch，按 cursor 轮询任务。每槽每批最多一次写入；过期任务跳过，且不会产生额外追踪。
5. 提交成功后选择新 read bank，下一帧 fence 保证写入完成。每个种子独立递增 Sobol 序号，避免轮询间隔冻结低位序列；任务键被碰撞替换时重置该序号。世界任务在图像、NRD guides、motion、大气视线合成之前返回。

世界种子最后发现时间超过 10 秒后不再训练，但索引槽保留以避免重复入队。最多累计 16 个更新样本，然后使用 1/16 EMA；非有限、负值或任一通道大于 8192 的目标被拒绝。有效黑值允许命中。

键：0.5-block 空间单元、量化法线、量化入射方向、精确主表面 RGB/roughness/reflectivity。完整键比较防止 hash 碰撞误命中。观察方向不在键内。空间/方向量化仍可能漏光或偏差；远世界坐标的 float 精度仍有限。

相机位姿变化不全局失效。相同静态区块仅重打包、相对几何原点变化时可以保留缓存；真实区块增删、顶点/材质改变、场景 revision、大气资源或照明状态改变仍全量清空。区块流入流出会影响预热，这是尚未实现区域失效的限制。太阳方向/强度等采用有限量化容差，其他相关照明参数精确比较。

## NRD 与显示合成

缓存估计借用已有 unfiltered composition 通道，不反复充当 NRD 的新 Monte Carlo 样本。新直接照明和实时 fallback 继续原 NRD AOV；实体直接阴影 signed residual 保持独立。缓存目标在大气前生成，当前视线仍按 `surface * T_new + L_new` 合成。

命中与未命中的 AOV 分配、量化键、EMA 会带来闪烁或收敛差异，尚需游戏内相同轨迹对照。

## 设置和测量

```toml
persistentRtEnabled = true
persistentRtStatistics = true
rtEvaluationMode = "full"
persistentRtUpdateIntervalMs = 50
persistentWorldTrainingBudget = 8192
persistentWorldMaxAgeMs = 2000
persistentRtMinimumSamples = 4
```

F9 可直接设置世界更新预算和寿命。旧 `persistentRtTrainingBudget`、`persistentRtMaxAgeMs` 保留配置兼容，独立任务不再使用它们。预算 0 暂停训练，缺失/过期缓存仍走实时 fallback。

默认 8192 种子、65536 个已发现槽时，一轮约 400 ms，4 次更新约需 1.6 秒；任务增长、低帧率及区块失效会延长预热。预算是任务数量上限，不是 GPU 时间上限。

`world_lighting_ms` 独立报告世界 dispatch；`rt_ms` 报告显示 dispatch。`rt_pipeline_ms` 包含两者及相关处理。统计中的 sampled_primary/secondary 仅显示路径；sampled_world_segments 单列世界路径，active_jobs 是已发现槽数。每 256 个 invocation 抽样，shadow/volume 未包括，不是完整射线数。exact_reservation_attempts/exact_admitted 是所有世界任务预留数。

## 验证

- `./gradlew check jar --offline`：62 个任务，实际 raygen 的两种大气 variant 编译及现有数学/NRD/GUI 契约检查通过。
- 相机位姿不进入光照 identity、uint 毫秒环绕、相同世界区块重打包保留、顶点改变/区块移动和删除失效。
- 实际 runtime GLSL 的 Vulkan 回读：RX 7800 XT，旧缓存 4 个 fixture 和新增世界任务 3 个 fixture 全部无不合格差异；整数/键精确，浮点平均最大 1 ULP（上限 2）。
- 世界 fixture 检验并发重复入队、紧凑索引唯一性、每种子连续序号、独立 load/reserve/store dispatch、旧快照隔离、有效黑值、过期、样本不足、full-key 碰撞、NaN 拒绝、预算 2/0。
- 大世界 fixture 比较 4,785,415 words，包含 6 MiB snapshot copy 的 synthetic enqueue/consume/query median 约 0.371 ms；不是游戏 RT 训练耗时或 FPS 收益。

复现：

```bash
./gradlew persistentRtMathTest -Ppersistent_rt_dump=/tmp/persistent-world --offline
cc -O2 -Wall -Wextra tools/gpu_persistent_rt_smoke.c -lvulkan -o /tmp/gpu_persistent_rt_smoke
/tmp/gpu_persistent_rt_smoke /tmp/persistent-world-world.{spv,seed,expected}
```

## 还没有实现

异步计算队列、独立后台世界时钟、广 FOV 可见性缓存、depth reprojection、hole repair、高频纯重建显示帧。当前只分离世界照明更新与显示追踪；每个显示帧仍追踪 primary 和直接照明。世界任务由已观察表面发现，并非完整未知世界探针。游戏内画质、相机运动和净性能收益未验证，不能宣称已实现 120–240 Hz 重建显示。
