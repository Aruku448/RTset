# 当前光追阶段开销：2026-10-01

## 结论

本次日志样本中，主要已测成本是 `vkCmdTraceRaysKHR` 内的路径追踪：实时原生分辨率窗口平均 **42.417 ms**，占已测 RT→结果拷贝区间的 **97.25%**。后处理平均 **1.176 ms**。这不是整帧 GPU 时间，也不能将 CPU 等待再加上去。

几何合并与发布另有事件级长耗时；现有日志不能独立给出主光线、GI、阴影、PBR/POM、体积光、BLAS/TLAS、NRD、FSR 每项的 GPU 毫秒数。

## 数据与采样口径

- 来源：`/home/aruku/.minecraft/versions/RTest/logs/latest.log`；读取时 1544 行，末尾 16:43:44。
- 分析快照：`/tmp/rtest-performance-snapshot.log`，仅在本机临时目录保留；报告不复制聊天、登录信息或整个日志。
- 全日志 122 条独立 GPU 计时、253 条 CPU scope 计时。
- 现有日志每 120 帧输出一次当前样本，不是 120 帧平均。下表均为已记录样本统计，不是完整帧分布；P95 为 nearest-rank，小样本下等于最大值。
- GPU 结果异步读取，帧号、日志时刻及模式切换不能逐条直接对齐；因此选择远离主要切换的时间窗口。
- 当前配置：`2560×1404 → 2560×1404`、`fsrQuality=native_aa`、`giBounces=4`、NRD/Sundial 关闭、体积光开启且 quality=3、POM 开启、terrain LOD 关闭。最后成功发布的静态场景为 1,634,560 三角形。
- 已安装 JAR 与 `build/libs/rtest-0.1.0.jar` SHA256 相同：`1f9d11efabb0a0773c47369358ad82ab349781a740850b06591353705e5dd917`。

## 实时原生分辨率：16:42:37–16:43:29

这段在 16:42:33 离线累积停止之后、16:43:31 再次启动之前。GPU 9 个样本，CPU 每个 scope 8 个样本。单位均为 ms。

|阶段|平均|中位数|样本 P95|说明|
|---|---:|---:|---:|---|
|CPU 动态实例/几何更新|0.112|0.099|0.207|`dynamic_update_us`，不是 GPU AS 执行时间|
|CPU 命令录制|0.100|0.087|0.191|录制 Vulkan 命令，不能代替 GPU 耗时|
|CPU 等待上一提交|8.008|1.183|30.926|与 GPU 工作重叠，不是额外 GPU 分项|
|GPU 路径追踪|42.417|43.103|46.651|`rt_ms`，主/次光线与相关着色全部包含|
|GPU 后处理|1.176|1.174|1.192|`post_rt_ms`，包含启用的降噪/合成/FSR 等；本窗口降噪关闭|
|GPU 后处理后的屏障与结果拷贝|0.025|0.025|0.027|逐样本 `total_ms - rt_ms - post_rt_ms` 推导|
|GPU 已测区间合计|43.619|44.319|47.851|`total_ms`，已包含上面三个 GPU 分项|

资源匹配、重建、geometry 更新、PBR 同步、诊断读回在这个采样窗口显示 0；它只表示这些采样帧未执行或低于整数微秒记录精度，不代表对应操作免费。`smoke_run_cpu` 外包 `vulkan_dispatch_cpu`，不能相加。

`terrain_traversal_ms=0` 与当前 LOD 关闭一致，不能据此认为 BLAS/TLAS 更新耗时为零。

## 离线累积窗口：16:41:26–16:42:28

同为原生分辨率、降噪关闭；GPU 7 个样本、CPU 每个 scope 6 个样本。

|阶段|平均 ms|中位数 ms|样本 P95 ms|
|---|---:|---:|---:|
|GPU 路径追踪|83.078|83.056|83.497|
|GPU 后处理|1.416|1.416|1.423|
|GPU 已测区间合计|84.529|84.511|84.953|
|CPU 等待上一提交|76.520|77.627|79.898|

不同时间段不是同一固定相机的控制变量实验；不能把差值直接归因于离线累积自身。

## 非每帧事件：整个日志

|事件|次数|平均 ms|中位数 ms|范围 ms|线程|
|---|---:|---:|---:|---:|---|
|几何 CPU merge|7|163.286|187|111–204|`rtest-geometry-merge` 后台线程|
|几何 publish|11|28.727|38|7–53|Render thread|

这些事件不可摊作每帧固定开销。全量捕获/LightTree/PBR 打包没有完整独立计时；首次激活从 16:32:06 请求捕获、16:32:14 发布场景、到 16:32:16 第一张 RT 帧呈现，只能观察秒级端到端时间，不能当精确分项执行时间。后台还出现多次 `discarded stale full scene finalization`，说明完成结果被判过期；目前无法量化浪费了多少 CPU 时间。

历史降噪开启窗口 16:33:48–16:34:20 的 `post_rt_ms` 平均 5.099 ms（10 样本）；后来降噪关闭窗口 16:34:23–16:35:26 平均 1.301 ms（23 样本）。它们不是控制变量实验，只能作为后处理范围的观察，不能称作“NRD 精确耗时”。

## 实现步骤与未覆盖项

源码路径前缀为 `src/main/java/com/rtest/client/`。

1. **捕获与几何准备**：`RayTracingProbe.java:463–639,1063–1210`。发布此前结果、分帧捕获 section、后台合并/LightTree/PBR 打包。CPU merge 有事件日志；其他阶段未完整分项。
2. **LOD 构建/选择/场景 compose**：`RayTracingProbe.java:1342–1753,1921–1951`。当前关闭，因此本次不能统计 LOD 构建开销。
3. **动态对象捕获**：`RayTracingProbe.java:693,790–818`。snapshot 数量日志不是耗时；后续 Vulkan pass 的 dynamic_update 不能覆盖整个捕获阶段。
4. **尺寸/资源匹配、重建、几何更新**：`RayTracingSmokeTest.java:110–173`。对应 resource_match/resource_rebuild/geometry_update CPU 字段。
5. **等待与上一帧 GPU 查询**：`RayTracingVulkanPass.java:2328–2383`。对应 fence_wait_cpu/readback。
6. **动态实例更新、PBR、录制**：`RayTracingVulkanPass.java:2407–2476,2702–2805`。动态 BLAS/TLAS 的 CPU 录制、上传、GPU 执行不是同一种开销；未分配独立 GPU timestamp。
7. **GPU 前处理**：`RayTracingVulkanPass.java:2945–3009`。输入准备、可用历史的深度/Hi-Z/遍历、动态 BLAS 和 TLAS 更新等。启用 traversal 时 `terrain_traversal_ms=q5-q4` 涵盖这片复合范围，并非纯遍历。当前关闭时该字段输出 0，不能量化其他前处理工作。
8. **GPU 路径追踪**：`RayTracingVulkanPass.java:3028–3030`。`rt_ms=q1-q0` 包围单个 trace dispatch。`RayTracingShaderRaygen.java:1045–1071` 最多主光线加 4 段 GI；太阳阴影、灯光树面积光 NEE、hit/miss、透明处理、PBR/POM 等在该 dispatch 内，均未单独计时。
9. **体积光**：`RayTracingShaderRaygen.java:416–507,1643`。quality=3 使用 16 个积分点，满足日照条件时每点还会发射太阳可见性光线；它也包含在 RT 段中。高 GI、体积光质量、POM 是值得分别 A/B 测量的候选项，但本报告未证明各自占比。
10. **降噪与 FSR**：`RayTracingVulkanPass.java:3031–3033`、`fsr/RtestFsr3.java:297–349`。`post_rt_ms=q2-q1` 包含 RT 后屏障、启用的 NRD/Sundial 与其合成、FSR。各项没有独立 GPU 时间。
11. **输出拷贝**：`RayTracingVulkanPass.java:3034–3065`。`total_ms=q3-q0` 结束在 display→主目标 copy 后，不包含 q3 后最终屏障、Minecraft 其他渲染、手/UI、swapchain blit/present；不包含 q0 之前 AS/遍历。因此不能据此推导实际 FPS。

## 可重复统计

本次未修改渲染器或游戏配置，仅新增统计脚本与数据报告。

```bash
python3 scripts/profile_rt_log.py /tmp/rtest-performance-snapshot.log \
  --since 16:42:37 --until 16:43:29
```

可替换为实例的 `logs/latest.log` 和新的稳定窗口。脚本仅输出计时数据与日志 hash，不保存聊天或凭据。结果保存于 `docs/profiling/2026-10-01-*.json`；all-samples 中混合多个模式，不应将其整体平均当作“当前配置性能”。脚本已验证单位换算、CPU/GPU scope 分离、时间过滤与实际样本数。

要进一步完成“每一步 GPU 开销”，应增加 BLAS、TLAS、Hi-Z、traversal、NRD、合成、FSR 的独立 timestamp；主/阴影/GI/体积光/POM 同属一个 dispatch，还需 shader profiler 或固定相机的独立开关实验，不能从当前总时间反推。
