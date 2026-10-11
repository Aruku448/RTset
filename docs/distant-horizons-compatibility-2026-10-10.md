# Distant Horizons 与 RTest LOD 兼容评估

> **文档状态：兼容性研究，尚未实施适配** · 整理日期：2026-10-11。
> 本篇未接入 DH；固定版本与可行性推断不构成安装即兼容的承诺。与已落地的 Sodium bridge 区分阅读。[分类索引](README.md)

## 判断

**可做适配，但当前不能直接安装即得到正确的 RT 远景。** 最有价值的方向是把 DH 当成远景数据／简化网格提供者，RTest 保持原生近景与 Sodium 捕获，接收 DH 几何后建立 BLAS/TLAS，让远景参加主射线、反射、遮挡及同一套曝光／大气。

这是一项版本受控的几何与材质适配，不是仅让两套 raster 画面叠加。DH 可能减少 RTest 重复简化远景的 CPU 工作，但不会消除 RT 几何上传、BLAS 构建、远景射线及 DH 自身生成／数据库工作的成本；不能承诺修复全部 LOD 卡顿。

本轮只读调研并记录文档，没有安装 DH、修改生产代码或运行兼容性测试。

## 固定源码与版本

- 官方主仓库：`f567c3476af6e65eb19854b2942d6fa08c1bcb91`，本地 `/tmp/rtest-distant-horizons`。
- 锁定 core 子模块：`f5788b79f0cc4d751a6c1c7d759ac926df917206`，本地 `/tmp/rtest-distant-horizons/coreSubProjects`。
- 该 main 为开发源码，不能把 API 7.3.0 接口存在等同于用户手里的任意发布版具有该接口。更详尽 API 调查见同目录 `distant-horizons-api-research-2026-10-10.md`。

官方 [26.2.0.properties](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/versionProperties/26.2.0.properties) 定义 MC 26.2、Java 25、Fabric/NeoForge 构建目标；这与 RTest 的 MC/Java/加载器方向匹配，不证明某个已发布 JAR 已通过运行验证。

官方 [BlazeDhRenderApiDefinition](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhRenderApiDefinition.java#L71) 在新版本读取 Minecraft 正在使用的图形 API，已经具有 Blaze3D 路径。因此“DH 只能 OpenGL，Vulkan 一律不兼容”不适用于这里检查的 main。Blaze raster 能调用 Vulkan，不等于 DH 几何已经接入 RTest 光追。

## 现有接入边界

| 部分 | DH 当前源码 | RTest 当前实现 | 适配要求 |
| --- | --- | --- | --- |
| 数据／生成 | 持久化远景、后台数据处理、LOD 简化 | 主要来自已捕获的 native section，远景缓存只保留已观察地形 | DH 保持数据所有权；RTest 消费不可变、带版本的快照 |
| 网格 | 柱状数据生成合并面，再上传紧凑顶点 | float 三角形、每三角形 28 个 material float | CPU 几何转换，不能直接把 DH VBO 当 RT 输入 |
| 空间层级 | 水平 quadtree；section 为 64×64 个给定精度的柱 | 三维 NodeKey，最大 level=2，即节点最大边长 64 块 | 扩展远景节点表示／选择器，或明确重分块；不能只改常量就完成 |
| 材质 | ARGB、粗材料 ID、独立光照字段、当前 main 的 texture tile | PBR、发光体、RGB 透明阴影 | 首阶段用不透明漫反射；恢复未着色颜色，之后单独扩展透明与发光 |
| 发布 | DH 自有异步任务、buffer 生命周期 | generation/fingerprint 缓存、增量场景发布 | 独立有界转换队列、卸载／重载失效、限额与渐进发布 |

DH [DhSectionPos](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/pos/DhSectionPos.java) 明确区分 section detail 与每柱 detail。不能把 DH detail 的整数直接写入 RTest NodeKey.level。

RTest 的 `RayTracingTerrainProxyStore` 可以复用为不可变快照与版本校验边界，但其 `OpaqueNodeMesh` 校验明确拒绝透明、吸收及发光；当前 `RayTracingProbe` 还有仅接收已见地形、按现有 native section 排除重叠和远景半径的策略。要引入 DH 提供的未加载远景，必须正式扩展数据来源契约，不能只把新 mesh 塞进 Map。

## 两条可行路线

### A. 复用 DH 已简化的 CPU 网格：优先候选

在版本锁定的 mesh/buffer 构建与生命周期节点接收面、数据源位置及替换／释放通知，复制成 RTest 自有不可变数据，在后台转换并发布。DH 的距离层级与面合并成果已经存在，避免再次对方块全量体素化。

DH [LodQuadBuilder](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodQuadBuilder.java) 的顶点包含 short XYZ/meta、RGBA、材料 ID、法线 ID、texture tile。转换必须处理位置基准、Y 最小高度、索引拓扑、微偏移及颜色空间，不能直接逐字节套用 RTest 的 float ABI。

尤其 [ColumnBox](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/ColumnBox.java) 在生成面时已经调用 `ColorUtil.applyShade(color, getShade(direction))`。应尽量同时保留更早阶段的未着色材料颜色，不能将最终 vColor 直接当 RT albedo；量化后的方向明暗也不能保证精确反解。block/sky light 不能再次当发光加入物理 RT。

API7.3.0 提供自定义 renderDefinition 与 VBO wrapper 上传回调，理论上可以作为捕获入口，但公开接口没有完整 CPU 顶点回读、origin/detail 访问；Blaze renderer 还会强制转型具体 wrapper。因此需评估自有 renderer 或少量版本门控的内部适配，不宣称全靠稳定公共事件就足够。

若使用该 API，应通过 `setRenderDefinitionOverrideAsync` 切换，不能直接 `DhApi.overrides.bind`：官方要求清理旧 renderdata 并校验 Minecraft 图形 API。自定义 renderer 收到的相机裁剪列表也不能直接作为 RT 全部几何：画外地形仍可能出现在反射或投下阴影。桥接需要在资源预算内保留相应的画外候选与距离层级，而不是每帧仅保留 raster 可见列表。

### B. 使用公开地形数据 API：可用于小范围验证

公开点包含 block state、biome、上下界与光照，可以自行生成 RTest 几何；但这会重做部分颜色／网格构建工作。

`getAllTerrainDataAtDetailLevelAndPos` 的 detailLevel 表示读取区域边长，当前实现循环所有 block 列，并不直接给出所见的粗层级网格。detail=9 可能意味着 512×512 列的数据展开。cache miss 的等待、数据库读取与解压不得放在 render thread；soft cache 的读取和 clear/close 生命周期也需要串行所有权。

这条路线适合先证明世界坐标与远景几何正确性，不应作为无预算的每帧全量读取方案。具体接口与实现依据见 API 调查文档。

## 渲染共存与正确性条件

1. **近远景唯一所有权**：已加载近景继续用 native/Sodium；DH 只填远景。窗口边界、partial coverage 和未就绪回退必须无洞且不重叠。
2. **RT 自己的帧数据**：把远景真正追踪结果写入 RT 深度、法线、运动和辐射信号，让 NRD/FSR 使用一致信息。单独合成 DH raster color/depth 无法保证反射、阴影和历史一致。
3. **保留 DH 数据准备**：不能未经确认就取消 `DhApiBeforeRenderEvent`。当前 [ClientApi](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/api/internal/ClientApi.java#L600) 会因此跳过整个 `LodRenderer.render`；需要把 draw 抑制与数据准备分开核实。RT 未启用／失败时要保留 DH 原渲染能力。
4. **限制长距离与资源增长**：当前最大 trace 距离由 scene.renderDistanceChunks 派生；引入 DH 范围需同步扩展场景 bounds、culling、阴影／大气距离和远坐标精度，而非只延长主射线。上传、BLAS、CPU 快照和显存都有预算。
5. **版本门控与安全回退**：检测 DH/API/内部布局，签名不匹配则停用桥接，不强制注入未知版本；世界／维度切换、资源包改变、DH 重建与卸载都使快照失效。

## 建议的实施顺序

1. 选定一个实际可获取的 MC26.2 NeoForge DH 构建，并固定对应代码与顶点 ABI；先验证 Vulkan 共存。
2. 小范围、全不透明、无预烘焙光照的 DH mesh → RTest 独立远景分区，复用已有增量 BLAS/材质资源；先不冒充水、玻璃或高精度 PBR。
3. 建立近远景 coverage 与版本化替换，再扩大距离并记录 CPU 转换、上传、BLAS、trace 的分项成本。
4. 独立适配水／透明、纹理与材料，以及动态体积／时域历史；对照画面后才评价性能与兼容完成度。

结论：值得做“DH 数据／几何桥接”，尤其是免去 RTest 重复简化远景；当前没有证据支持“安装即兼容”或“复用其 raster 输出即可获得完整 RT LOD”。
