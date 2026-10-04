# 区块/RT 捕获缓慢：调度回退错误（2026-10-01）

## 观测反馈

用户报告当前区块构建缓慢。实例日志快照存于 `/tmp/rtest-chunk-build-current.log`，仓库只保存不含聊天/凭据的统计。

可重复执行的诊断命令：

```sh
python3 scripts/profile_rt_capture_log.py /tmp/rtest-chunk-build-current.log \
  --since 21:33:10 --until 21:34:05 --max-capture-seconds 2
```

已执行：exit=1，单个 full capture 的日志周期从 21:33:10 到 21:34:05，4273 sections，约 55 秒，最终 stale discard。全窗口 21:32:00–22:22:19：166 completed cycles，48 stale，42 cycles 超过诊断阈值 2 秒。

这是**观察反馈**：测量 start→apply/discard 的墙钟周期，包含逐帧切片、等待、CPU 捕获/worker、主线程调度；日志仅秒级，不能报告为纯 CPU compute time。只统计有 start/end 的周期，取消而无终点和窗口外 start 不计。

原始日志不可因代码改动而变绿；必须用重启后新的同条件日志确认实际周期改善。阈值 2 秒仅为本次响应性诊断指标，不是所有场景的已承诺 SLA。

近期窗口 22:21:00–22:22:19，CPU merge 21 samples 最大1ms；geometry publish 39 samples，median2ms、max3ms。日志的 publish 不是所有 GPU AS 构建时长。GPU RT 19稀疏samples median21.061ms、max76.272ms，配置/场景存在变化，不当作固定配置的性能对比。

## 核实的调度错误

真实捕获控制在 `RayTracingProbe.prepareLevelRender()`，不是仅提供 Vulkan 调用的 `RayTracingSmokeTest`。

旧路由：

```text
有快照 && 非全量请求 && 有dirty && batchReady → incremental
否则 fullCaptureRequested || sceneDirty || 无快照 → full
```

`markChunkDirty`、`markSectionDirty` 和 camera-window delta 会设置 `sceneDirty=true`、保留 pending dirty/capture section 集合，并启动 batching timer。若 batch 尚未达到大小/最大年龄/idle flush 条件，旧路由不会等待，反而因为 sceneDirty 进入全量捕获。

结果：普通增量事件就触发大量 CPU sections 重采。普通后续事件推进 sceneGeneration，非 activation 的全量发布又可能判定 generation stale，将完成的结果丢弃。日志中连续小场景 full90sections→stale→full90sections 与这种调度相符。不能由日志单独断言每条 dirty callback 的来源，也不能把全部55秒只归因这一处。

## 本轮修复

新增内部 `RtCaptureSchedule`，从 Probe 提取**实际使用**的路由，先忠实保留旧行为，再用红测试锁住错误，最后修正为：

- 没有首个快照，或有显式 full request：`FULL`。
- 有现有快照、pending dirty 且 batch ready：`DIRTY`。
- dirty batch 尚未 ready，或没有 pending dirty：`NONE`，保留队列等待，不全量重采。

`RayTracingProbe` 用同一决策调用原有 full/dirty CaptureSession 和 worker。sceneDirty 是脏状态，不能独自成为全量重建理由。删除相邻的 fullCaptureRequested 自我赋值，校正文不符实的注释。

**保留**：

- 世界/资源 reload、视距变化的 full request 与 stop/reset 路径。
- 全量发布对 world、render distance、generation/full recapture 的兼容性判定，以及已有 activation policy。
- worker 只读取完成的捕获会话，主线程唯一发布引用。
- stop 时先 await geometry futures 再关闭 PBR NativeImages。
- 现有4/2 sections-per-frame预算、所有画质/采样/LOD设置、GPU fences/barriers/资源退役。

没有简单放行 stale geometry，也没有为提速扩大渲染线程预算。

## 回归与剩余边界

`./gradlew rtCaptureSchedulingTest --offline`：提取旧路由后真实红，`young dirty batch started whole-scene capture at frame 0`。修复后，模拟连续19个未成熟dirty帧全部NONE，batch ready 后一次DIRTY，零full starts；首个快照和显式full请求仍FULL。

这是直接调用生产路由的回归，不是假造GPU时间；Probe调用点、world/resource/viewport guards 和 worker retirement 另有集成源码契约。不是完整 Minecraft/world/异步worker端到端重放，因此仍需游戏内验证。

`./gradlew test jar --offline`：通过，包括 incremental geometry、window delta、activation、shader/NRD及资源生命周期契约。`python3 -m unittest discover -s scripts/tests -v`：4个日志分析测试通过。

本轮只修普通dirty等待期错误启动full的路径。真正的全量捕获仍受每帧4/2section预算、CPU fallback与GPU/帧率限制，合法全量过程中的generation变化也仍可触发现有stale策略；不能声称首次大世界捕获从55秒降到某个确定值。

## 后续验收

重启后保持原分辨率和画质设置：进入已捕获场景、慢走跨chunk边界、做少量方块更新。比较新日志的 full/partial counts、queued capture section数、stale数与时间；期望普通更新多数进入dirty batch，不再不断启动full。

独立测试首次大世界静止捕获的耗时。如果仍慢，再增加明确的capture step耗时/sections进度探针，区分4096-block CPU fallback、帧间预算等待和PBR packing。`loaded N PBR companion texture sets` 可能是缓存统计，不能仅凭反复出现就断言每次都重复磁盘加载。

统计文件：
- `docs/profiling/2026-10-01-chunk-capture-baseline.json`
- `docs/profiling/2026-10-01-chunk-capture-recent-timing.json`

## 部署记录

已安装：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`。

SHA256：`37efd2eb6307e1e9edba49950bba5e500f8875dd7c53c81729e5971f7b5f254e`。

旧JAR/配置备份：`/home/aruku/.minecraft/versions/RTest/backups/chunk-schedule-20261001-225412/`。确认实例停止后原子替换；本轮实例配置逐字节保持不变，需要重新启动。
