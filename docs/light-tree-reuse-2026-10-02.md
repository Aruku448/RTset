# 当前瓶颈：跳过等价光源树重建（2026-10-02）

## 实机基线与选择

部署材质/生命周期修复后读取 `RTest/logs/latest.log`，快照 `/tmp/rtest-performance-0333.log`。统计窗口 `[03:30:00, 03:33:00)`：

- 104 次 CPU 合并：总耗时中位数 51.5 ms；layout 1 ms；lightTree 50 ms。
- dirty≤3 的 13 次合并：总 59 ms、layout 1 ms、lightTree 56 ms。
- 102 次发布，其中 7 次 `reuseLightData=true`，说明有部分昂贵构建最终产出完全相同的数据；并非所有场景都可复用。
- 原 GPU profiler 的 inclusive 03:30:00–03:33:00 窗口，81 个采样：RT 中位数 13.989 ms，post 1.909 ms，total 15.779 ms。其 total 不覆盖整个 Minecraft frame。
- 移动时仍有材质 allocation 增长导致 130 ms 左右全量发布；本轮不通过盲目预留大块显存隐藏该峰值。

因此先消除严格等价输入的重复光源树构建，而不是继续优化已仅约 1 ms 的材质布局，或把 fence 等待误当独立 CPU 算力瓶颈。

## 实现及数学条件

`RayTracingLightTree.buildOrReuse` 只从调用者的前一不可变 Scene 快照复用 `Data`，不引入全局世界/设备缓存。`replaceSections` 和 `compose` 把前一完整场景传给构造器；其它完整捕获照旧构建。

复用要求：

1. 场景 origin 的三个 raw double bits 相同。
2. material high-water 相同，旧树 material-map 长度等于 high-water（排除未构建光源树的 partial delta）。
3. 按原始 section 顺序筛选 `emissiveTriangles` 非空的 section，两序列逐个匹配；不按位置重排。这保留 stable-sort ties、初始 emitterIndex、浮点 power 累加顺序。
4. 每个候选 section 的 material base 相同，**在对象身份快捷检查之前验证**。
5. 非同一对象时，section origin、候选 triangle 索引序列、每个候选的九个 vertex raw float bits 及 CPU 树读取的八个 material 字段 raw bits 相同。

八字段为 tint 0/1/2、normal 4/5/6、texture kind 15、emission 22。UV、roughness 等字段不进入 CPU build 数学，因此仅这些字段变化时完整重建原本也会产生完全一样的树；GPU 材质仍按原路径更新。

任何不满足均回退到未修改的完整 `build`，不改变 radix/median 排序、包布局、PDF、MIS、采样数、NRD/FSR 失效、scene revision、取消/陈旧拒绝、资源退役或同步。树中不保存 Vulkan handles。

成本从每次 O(E log E + M) 的完整构建变为复用时 O(S + C)：S 为 section 数，C 为非同一对象的 emissive 候选数乘固定字段数；不分配 emitter、node 或 material reverse-map。Data 与旧快照共享，只是延长既有不可变数据生命周期，没有新增 per-emitter cache。未命中多一次线性 guard 后仍走原构建。高水位变化、发光 section 顺序变化或有效字段变化会保守拒绝，不承诺移动世界都加速。

追加 merge log 字段 `lightTreeReused={}`，供下一轮实机统计命中率和实际耗时。

## 回归与配对基准

红灯命令 `./gradlew lightTreeReuseMathTest --offline`：

`AssertionError: non-emissive edit rebuilt identical light tree`

测试执行真实 `SceneGeometry.replaceSections` 和 `compose`，并把结果所有 packed words 与完整 build 逐字对照。覆盖非发光编辑、等价发光 payload、真正 emission 修改、全部九个 vertex 和八个 material 输入、UV-only 编辑、origin/±0、NaN、128 候选 radix 边界、排序源顺序、material base/high-water 变化、partial delta、空光源树和 150 轮随机实际合并。原有 packed SHA golden 测试也不变并通过。

基准：4001 个 section、80000 个 emitter，重复等价非发光编辑；输入及旧完整树预构造，Java25、heap1g、5 次预热/9 次测量。计时为真实 replaceSections，输出哈希计算在计时之外。用旧 JAR 放在同一 runtime classpath 首位与新实现配对，不回退工作区文件。

| 实现 | median | min / max |
|---|---:|---:|
| 原始完整重建 | 34.308 ms | 31.827 / 65.975 ms |
| 严格复用 | 4.092 ms | 3.841 / 5.949 ms |

配对中位数约 8.38×，两个完整 words 的 Java hash 都为 `1168139004`；正确性另由逐 word differential 保证，不以 hash 作为复用条件。这是**可复用编辑场景的 CPU 合成合并**，不是一般移动场景或 FPS 8.38×。真实基线只有 7/102 次 light data 相同，因此整体收益取决于新日志命中率。

日志：`/tmp/rtest-light-reuse-{red,guard,full}.log`、`/tmp/rtest-light-reuse-paired-{baseline,optimized}.log`。优化前源与 JAR：`/tmp/rtest-light-reuse-before/`。

## 验证及后续

- `./gradlew test jar --offline` 成功（55 tasks）。
- 原 terrain math packed-word golden、新 differential、材质/lifetime tests、shader contracts 通过。
- Python profiling tests 4/4；`git diff --check` 通过。
- 独立只读审查复用数学条件，特别指出 base 检查要先于身份快捷、partial delta 不可复用；均已处理。该审查不是 GPU 验证。

重启后固定窗口尺寸和质量设置，比较小块编辑/静态场景的 `lightTreeReused=true` 时耗时，以及跨区块移动的未命中耗时和发布尖峰。若未命中仍主导，再分离“真实 emitter 变化”和“仅 material indices/map extent 变化”后评估增量更新，不能直接采用旧树拓扑或旧 PDF。

## 部署

确认 Minecraft 未运行、旧安装 JAR hash 匹配后备份并原子替换，当前配置与本次备份逐字节一致；不恢复过去的配置。

- 安装 SHA256：`8fca869406606438ecc2cf161be01cef4574fb6bebdee0cf4449d21e0a1696e0`。
- 备份：`/home/aruku/.minecraft/versions/RTest/backups/light-tree-reuse-20261002-132312/`。
- 本次保持不变的配置 SHA256：`7a7d39a5f021dfe9423328acc012955dd348a5239b98acf451ec425e8f09366f`。
