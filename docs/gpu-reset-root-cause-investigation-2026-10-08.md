# GPU 重置根因调查进度（2026-10-08）

## 初始调查结论（后续实测见下）

根因尚未确认。用户确认故障发生在移动或转视角时，回退版尚未完成同操作对照。故障版99组GPU采样来自854×480，114次地形发布；不同视角trace变化很大。用户观察到优化有效，本次将优化保留为隔离候选，不把撤回作为优化失败或根因已经确定的结论。

故障链为gfx队列超时、reset期间非法opcode、GPU重置，游戏随后DEVICE_LOST。仍不能证明哪一个shader/AS/buffer/资源生命周期路径触发。参见 [撤回与证据](gpu-reset-rollback-2026-10-08.md)。

## 已构建的反馈与隔离工具

`tools/profiling/watch_gpu_regression.py` 观察实际游戏的新增计时、地形发布、DEVICE_LOST与内核超时/reset；输出FAULT、OBSERVED_NO_FAULT或INCONCLUSIVE，未运行实际RT/地形发布不会被误判PASS。不自动启动游戏、修改设置或发送输入。存储窄范围telemetry，不存进程命令行。

故障日志回放命令已返回FAULT（退出码1）：

```
python3 tools/profiling/watch_gpu_regression.py --replay tmp/gpu-reset-20261008/latest.log --kernel-replay tmp/gpu-reset-20261008/kernel.log --output tmp/gpu-regression-detector-replay
```

这验证症状识别器，并非重新执行GPU故障。首次实际120秒观察位于 `tmp/gpu-regression-baseline-20261008/result.json`，rt_samples=0、publications=0，结果INCONCLUSIVE，不能推断稳定。

`tools/profiling/hitl_gpu_reset.sh` 依据诊断技能的人机反馈模板创建，等待用户重启回退版进入原场景，之后观察120秒原有移动/转视角/新区块操作。终端会话仍停在进入场景步骤，待用户回复已进入后由agent推进。

`tools/profiling/build_gpu_isolation.py` 从恢复版和故障版源码构建三个候选，并在finally恢复源码：

| 候选 | 变化范围 | 隔离目的 |
|---|---|---|
| lighting-sparse-legacy-buffer | 光照查询/BSDF调度、零强度gate、中心稀疏写；保留原整屏allocation及中心索引读回；原AS flags | 保留主要计算优化，排除小buffer与AS flag因素 |
| unique-any-hit-only | 唯一any-hit几何标志 | 检查构建/遍历行为变化 |
| small-diagnostic-only | 单uint诊断生产/消费者和只读映射 | 检查小buffer/resource契约 |

输出位于 `tmp/gpu-isolation-20261008/manifest.json` 及对应JAR，均通过rayTracingShaderContractTest、restirConditionalMathTest和jar构建；没有游戏运行验证，均未安装。光照候选仍是一组相关改动，若有故障需继续缩为太阳/月亮/天空、gate等更小单项。

实例SHA仍为 `2ab6e907b232b94b7e3899ae3cfe8de06c2d6981545be8b9cdc9bd3d552899fe`；三份GPU源码仍与优化前逐字节一致。优化原始源码、故障JAR和数值测试归档保留。

## 下一步和阻塞条件

必须先在同场景建立回退版运行基线，不能把仅编译通过的候选认定为修复，亦不能把短观察窗口无故障认定长期稳定。当前没有可由agent独立启动并控制原移动场景的入口，需用户重启并进入场景；已通过异步问题请求，无需额外部署授权。

本轮采用 [diagnosing-bugs技能](/home/aruku/.agents/skills/diagnosing-bugs/SKILL.md)，相关要求为“No red-capable command, no Phase 2.”。现有日志识别器不是GPU执行复现，不能据此进入单项根因归因和宣称修复。应等待实际反馈循环，再缩小导致故障的必要改动。用户授权继续调试和保留优化不等于证明某一项为根因。


## 移动实测及当前保留版本

回退版01:21:08–01:21:36正常运行并退出，16组GPU计时、43次地形发布，无DEVICE_LOST；该短窗口不足以证明长期稳定。证据 `tmp/gpu-regression-baseline-user-20261008/`。

随后实际安装 lighting-sparse-legacy-buffer（版本A），SHA-256 `d9b85824c195ffb8e24cff50943188b747497e5bc834812c6a07aa74f6b204f7`。用户确认进入并重复移动/转视角；按用户“已经足够”结束采样。01:23:53–01:26:08获得134组GPU计时、204次地形发布，854×480、ReSTIR mode0；没有DEVICE_LOST，也没有该区间内核队列超时/重置。trace中位数3.9255 ms，但相机移动且场景未锁定，不能与此前不同视角的基线计算提速百分比。证据 `tmp/gpu-regression-lighting-a-user-20261008/result.json`、`telemetry.log`、`kernel-signals.log`。

版本A保留太阳/月亮/天空查询先完成、精确零裁剪与零阴影强度gate，以及只写中心像素；使用原整屏大小诊断buffer和中心offset读取，AS geometry flags恢复原0。主要减少计算和写入的算法仍在，4字节allocation、只读映射更改、额外读回日志及唯一any-hit标志未恢复。源码与实例的三个GPU class字节一致，rayTracingShaderContractTest、restirConditionalMathTest、accelerationStructureSynchronizationTest和jar通过，日志 `/tmp/rt-gpu-isolation-a-workspace.log`。

当前保留A。原完整优化版的故障与A的暂未故障，支持优先核查被移除的小buffer、AS flags及其交互，但**不能单独证明其中任意一项是根因，也不能永久排除A或共同路径的问题**。原故障运行约两分钟，本轮A窗口相近且有更多地形发布，是比仅编译/静止测试更相关的运行证据。用户已要求结束采样，不继续要求重复操作，不部署B/C。

诊断监测脚本的长窗口被人工中断，最后数据由日志/内核独立归档，不将KeyboardInterrupt算作游戏故障；没有丢失本轮分析所用telemetry。若后续继续根因归因，需要单项运行或运行时validation/capture证据。目前根因调查尚未闭合。
