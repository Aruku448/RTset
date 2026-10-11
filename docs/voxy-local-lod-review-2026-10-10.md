# 本机 Voxy LOD 实现与 RTest 对照

> **文档状态：研究/审计快照** · 整理日期：2026-10-11。
> 来源版本与本地行号只对应记录时点；建议不是自动生效的待办，实施情况需对照源码和后续记录。 [分类索引](README.md)

日期：2026-10-10。仅检查本地源码和制品元数据；未修改生产代码、构建、安装或运行测试。性能结论为代码机制分析，没有 Voxy 与 RTest 同场景帧时间实测。

## 检查对象

| 本地仓库 | 版本与分支 | 状态 |
|---|---|---|
| `/home/aruku/voxy` | Forge 1.20.1，`forge-1.20.1-sp`，0.2.11-alpha；HEAD `8285ecccf3d296f40d9b500a70aab2ca90affe2a` | 有本地修改，分析的是工作区 |
| `/home/aruku/voxy-NHblock714` | NeoForge 1.21.1，`neoforge-1.21.1`，0.4.11；HEAD `bba98562261c43d02ce9e5bb6de080412e8d93c4` | 工作区干净 |

本机 `RiaFst/mods/voxy-riafst.jar` 对应前者版本；`桌面/Minecraft开发与配置/客户端mod/voxyneoforge-alpha.jar` 元数据为后者版本。本报告不把这两份实现等同于上游 Minecraft 26.2 分支，也不能由此认定本地 JAR 能在 RTest 26.2 直接加载。

## 1. 数据层是真正的逐级 mip

NH 的输入为原生 16³ 方块体素，一个体素用 long 编码方块、群系与光照。`WorldVoxilizedSectionMipper.mipSection` 在同一数组中生成 **16³ → 8³ → 4³ → 2³ → 1³**，每个父体素读取八个子体素，不需要各级重新扫描原始三角形。

来源：[mip 生成](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper.java:23)、[体素编码](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/other/Mapper.java:92)。

`Mipper` 选择具有代表性的方块状态，并考虑空气、不透明度、流体、顶部位置和光照；不是把八个 RGB 简单平均。相同方块有快速路径，临时状态与元数据通过线程局部 Scratch 复用。对 RTest 可借鉴的是“保留占据与材质语义后再归并”，不能把预烘焙光照当作光追材质。

来源：[代表体素选择](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/other/Mipper.java)。

持久世界节点固定存储 32³ 体素，层级 0–4 的体素尺度分别为 1、2、4、8、16 块，节点覆盖边长分别为 32、64、128、256、512 块。不同层级有自己的数据，视点选择与数据构建相互独立。`WorldUpdater` 更新受影响区域并比较旧值，仅实际内容或子节点存在性改变时通知 dirty；它仍检查后续各层以修复陈旧父数据，并非下层没变就立即退出。

来源：[节点与数组池](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/WorldSection.java)、[层级上限](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/WorldEngine.java:15)、[增量写入与失效](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/WorldUpdater.java:30)。

## 2. 网格层：位掩码找外露面，扫描线合并

`RenderDataFactory` 复用 32³ 体素、六个邻接面和 opaque/nonopaque/fluid 掩码等缓冲，通过相邻占据位掩码与模型遮挡规则找到需要绘制的面；不同透明/流体类别分别处理。`ScanMesher2D` 横向归并同 payload 的连续面，再纵向归并相同跨度，每个方向的合并尺寸上限为 16。

来源：[网格生成](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java)、[扫描线合并](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/util/ScanMesher2D.java:7)。

一个 quad 编码为一个 64 位值，光栅 shader 重建顶点。RTest 当前一个输出 quad 展开成两个三角形，CPU 顶点和材质数组共 `(18 + 56) × 4 = 296` 字节。两者不是同一渲染表示：8 字节不能直接供 Vulkan 三角形 BLAS 使用，不能据此声称总显存减少 37 倍或速度提升 37 倍。能直接迁移的收益来源是减少面数、复用构建缓冲；紧凑 quad 则适合用作中间结果，发布时再展开。

## 3. RTest 当前关键差异

| 项目 | 本机 NH Voxy | RTest 当前 |
|---|---|---|
| 数据入口 | 方块体素，保留占据和模型身份 | 原生网格三角形及材质快照 |
| 父级生成 | 八子体素逐级归并 | 每级重新扫描原生三角形并做 triangle/AABB SAT |
| 粗化尺度 | 1 → 2 → 4 → 8 → 16 块 | L1 为 32/8=4 块，L2 为 64/16=4 块 |
| 相邻面合并 | 有扫描线合并 | 逐体素输出外露 quad，无相邻共面合并 |
| 发布控制 | NH 有字节与 copies 准入预算 | 已有任务数量控制，但缺少等效发布字节/成本预算 |

**RTest 的 L2 当前扩大覆盖范围，没有进一步降低空间采样分辨率。** L2 体素总数为 L1 的八倍，且两层都回读原生三角形；因此高层级并不天然意味着更少的构建工作。这是从源码可确认的结构差异，不是对某次卡顿唯一原因的断言。

来源：[RTest 层级配置](/home/aruku/桌面/Minecraft开发与配置/RT/src/main/java/com/rtest/client/RayTracingTerrainLod.java:25)、[逐节点构建](/home/aruku/桌面/Minecraft开发与配置/RT/src/main/java/com/rtest/client/RayTracingTerrainLod.java:403)、[三角形采样](/home/aruku/桌面/Minecraft开发与配置/RT/src/main/java/com/rtest/client/RayTracingTerrainLod.java:653)。

不能简单让 L2 读取当前 L1 输出三角形来冒充 mip：L1 是表面代理，部分原始占据、薄面和材质信息已丢失。可靠的父级复用需要保存独立的体素中间数据，定义占据、六方向表面及材质的聚合规则，再生成 mesh。若接入方块状态快照，还需要处理自定义模型与非完整方块。

## 4. 发布、缓存和阻塞边界

- NH `AsyncNodeManager` 接收新 mesh 前按约 2MiB / 400 copies 扣额度，未消费的已发布结果也计入；几何、metadata、节点引用作为一组交给渲染线程。预算按接纳前累计值判断，允许一个完整 section 超额，并不是严格上传硬上限或 GPU 毫秒保证。
- Forge 的 2MiB 是接纳一批后的背压，区别于 NH 的准入限制。两份 fork 的调度和并发修复不相同。
- Forge 声明了 1GiB `GeometryCache`，但生产源码没有 `geometryCache.put`，退场 mesh 缓存没有接通。有效的复用包括世界数据缓存、数组池、worker 缓冲和驻留 GPU mesh；不能宣称已实现退场 mesh 重用。
- 排队去重不等于世代校验。worker 开始构建后，同 key 还能产生新任务；RT 应继续验证内容版本，防止旧 mesh 替换新数据。
- Forge staging 空间不足有 `glFinish` 回退，Voxy 本身也存在构建潮引发等待的边界。

完整证据和两 fork 的细节见：[流水线检查报告](/home/aruku/桌面/Minecraft开发与配置/RT/docs/voxy-local-pipeline-research-2026-10-10.md)。

另一个注释与实际实现不符处：NH `acquireNeighborData` 注释声称邻居读取为 cache-only，但实际调用 `WorldEngine.acquireIfExists`，后者调用 `ActiveSectionTracker.acquire(key, true)`；这里 true 表示空数据返回 null，缓存未命中仍能执行 `loader.load`。专用 cache-only 方法另有实现。故该路径不能作为“绝不触发存储读取”的示范。

来源：[邻居读取](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java:487)、[acquireIfExists](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/WorldEngine.java:84)、[实际 loader 调用](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/common/world/ActiveSectionTracker.java:201)。

## 建议迁移顺序

1. **先限制发布成本**：在几何、材质写入、BLAS 构建进入发布事务前按字节和预计成本排队，成功后切换引用；等待期间保留旧代理或原生覆盖。此前 RTest 日志显示发布可达 53ms、材质写入为主要阶段，因此仅优化后台 mesh 不足以解决这部分卡顿。
2. **建立可复用的体素中间层**：原生数据只采样一次，内容与邻居依赖有版本；父级从子体素归并，尺度真正逐级变粗。选择格子尺度时保留质量可配置性，不直接把 L2 bins 改小就视为完成。
3. **合并兼容的共面面片并复用 worker 缓冲**：合并条件必须包含材质、法线、UV 映射/重复方式等，避免拉伸纹理、跨材质误合并及薄面丢失。
4. **完善有写入路径的缓存**：分别观测体素、mesh 与 BLAS 命中率，区分冷构建、内容变更和邻居变更造成的失效。

Voxy 的 OpenGL 渲染器和仅面向相机可见性的 GPU 遍历不能直接替代 RTest Vulkan 光追路径；反射、阴影等二次射线仍需保守场景覆盖。可以复用的核心是数据组织、mip、合并及发布策略，具体实现应适配 RTest 的材质与加速结构契约。
