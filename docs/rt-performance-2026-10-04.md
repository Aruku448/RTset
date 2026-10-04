# 当前性能瓶颈检查（2026-10-04）

## 实测结论

数据来自 RTest 最近运行会话的 latest.log。检查时客户端已退出（唯一 Java 进程为 Gradle daemon），因此本轮没有现场控制变量实验。没有修改运行代码、mod 或配置。

实时窗口 17:59:01–17:59:33：6 个稀疏 GPU 样本。分辨率 2560×1404 → 2560×1404。

|范围|均值 ms|中位数 ms|最大 ms|
|---|---:|---:|---:|
|GPU 路径追踪|42.511|38.633|65.571|
|GPU 后处理|1.405|1.366|1.620|
|GPU 已测合计|43.968|40.049|67.245|
|CPU 等待前次提交|38.474|—|40.483|
|CPU 动态更新|0.028|—|0.036|
|CPU 命令录制|0.071|—|0.080|

路径追踪占已测 GPU 区间约 **96.69%**。CPU fence 等待与 GPU 工作重叠，不能再相加。已测区间不含 trace 前 AS 构建，也不等于 Minecraft 整帧 GPU 时间。不能据此直接宣称实际 FPS。

17:59:44 开始离线累积；其后 17:59:49、17:59:58 两个 GPU 样本的 RT 平均 67.978 ms、后处理 1.645 ms、合计 69.679 ms。它们不属于实时模式，不能混入实时平均，也不能把与实时差值归因到某一个功能。

日志每 120 帧记录一个当前样本，不是每 120 帧平均。实时最大值 65.571 ms 保留在统计中；不是固定相机实验，不能擅自删除为异常值。样本不足以给出完整帧 P95。

## 当前配置快照

- GI continuation=4；太阳主表面软阴影=8 samples，角半径=1°。
- physical atmosphere=true；volume=true，quality=2（主段积分8步，局部体积/天空积分2点）。
- NRD=false；FSR=native_aa。
- PBR CPU capture=true；POM=true，entity POM=true。
- terrain LOD=false。日志最近全量场景约201,166三角形，178个CPU捕获section，783个灯光树emitter。

配置文件是检查时快照，不能证明每一个历史样本的配置完全相同；最近日志可确认原生分辨率、NRD关闭、离线切换。不能用当前配置推定历史窗口中的每一次参数修改。

## RT 内部候选，尚未独立计时

1. **体积积分及其阴影查询**。RayTracingShaderRaygen.integratePhysicalAtmosphereSegment 在每像素8步中查询介质、四波长相位/多重散射、太阳透射与 RGB 阳光可见性；月光有效时再处理对应来源。samplePhysicalVolumeEmitter 另取2个距离点，每点含一次天空可见性查询及可选局部emitter阴影。实体和物理天空背景均可能进入，因而不是只对有地形的像素执行。理论上主段最多8次太阳和8次月光阴影，再加2次天空、最多2次局部阴影；实际受来源高度、路径长度和emitter有效性控制。这是源码上限，不是测得的射线数量。预测：固定场景关闭volume进行归因会明显减少rt_ms；当前未执行。
2. **太阳软阴影+GI**。每个主表面最多8条太阳阴影射线，后续表面每段1条；GI=4允许最多5个路径顶点。各顶点还有材质/BSDF、灯光树NEE、可能的月光阴影；主表面另有天空NEE。预测：固定相机分别对照太阳8/1、GI4/1能区分两项。不能根据合计时间给出各项毫秒数。
3. **POM与纹理查询**。closest-hit中的POM高度步进以及alpha-tested树叶/草引起的any-hit纹理读取会放大植被场景中的RT负担。POM调用位于closest-hit，不能错误声称每条shadow any-hit都在完整跑POM。预测：固定素材/相机仅切换POM可测收益，未执行。

这些候选的顺序反映源码上的工作量，尚不代表实测占比排序。所有功能共处一个vkCmdTraceRaysKHR；现有GPU timestamp只给出整个dispatch。

## CPU 卡顿与同步

同一个实时窗口12次后台geometry merge：平均3.583 ms、最大40 ms（其中layout39 ms）；12次Render thread发布：平均3.833 ms、最大9 ms。频繁dirty-section/camera-window更新还会重置时序历史。这是移动/场景变化的卡顿和噪声恢复问题，不能摊成每帧固定成本。全量激活需要约秒级资源建立，另有启动卡顿。

RayTracingVulkanPass.awaitPendingFrame 等待上一次提交；增量AS构建路径还有submit后立即等待。它们限制CPU/GPU重叠，但当前主要GPU时间依然是RT dispatch。异步化可能改善流水线与尖峰，不能据此承诺消除约38 ms的GPU着色成本。AS构建当前不在total_ms内；traversal=0并不能证明AS免费。

## 草木调节影响

新草木模拟增加太阳采样中的Burley运算，并让原来背光、directCosine=0的草木也进行必要的RGB阴影查询，因此可能增加植被像素射线工作量。当前没有替换前后同场景A/B，不能量化其回退。PBR CPU capture当前已开启，草木section强制CPU捕获的备用分支不会额外改变此配置的捕获方式。

## 优先优化与下一轮测量

- 先做固定视角、天气、时间、分辨率的受控采样；每次只变volume、sunShadowSamples、GI、POM中的一项，测完恢复原配置。测试降采样仅用于归因。
- 如果体积项占主导，优先研究空间共享的体积积分/阴影缓存；必须保留RGB透明阴影、太阳/月光及局部来源，不能直接替换为Prime标量shadow。
- 如果8条太阳阴影占主导，评估带动态遮挡失效保护的历史复用或自适应采样，避免单纯降低样本造成半影噪声。
- 单独增加AS、NRD、FSR GPU计时，现有计时不能给出各项精确开销。NRD当前关闭，不是当前后处理主瓶颈。

## 可重复统计

```bash
python3 scripts/profile_rt_log.py /home/aruku/.minecraft/versions/RTest/logs/latest.log --since 17:59:01 --until 17:59:33
python3 scripts/profile_rt_log.py /home/aruku/.minecraft/versions/RTest/logs/latest.log --since 17:59:43 --until 18:00:10
```

原始统计保存于docs/profiling/2026-10-04-realtime-rt-timing.json和2026-10-04-current-rt-timing.json（后者为离线窗口）。只保存计时统计与日志hash，未复制聊天或认证信息。
