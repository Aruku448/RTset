# Voxy `2622` LOD 与 RTest RT 场景比较

- 调查日期：2026-09-22
- Voxy：分支 `2622`，commit [`91c96528bc458be0646ddda2564aa90baaf639e2`](https://github.com/MCRcortex/voxy/tree/91c96528bc458be0646ddda2564aa90baaf639e2)
- RTest：当前工作树基线 commit `6a05a394f72be16d93dd9e2eec71d5aac10c8d3e`（本地仓库；RTest 源码链接见下文）
- 范围：只读 Voxy 源码/README 和 RTest 源码；没有修改生产 Java/GLSL。

## 结论摘要

1. **Voxy 的 LOD 思路适合显著降低 RTest 的远景几何量，但不能直接接入 RTest 的 Vulkan BLAS/TLAS。** Voxy 是 Minecraft 方块的体素化、层级 section、GPU raster/meshlet 路径；RTest 是原生 Section 捕获后把三角形放入 Vulkan BLAS，并由每三角形 material ABI 着色。两者的几何格式、坐标/实例模型、材质语义和更新协议都不同。
2. **最有价值的可复用部分是“层级场景选择 + 不连续 LOD 节点按需流式生成”，不是 Voxy 的 GL buffer 或 quad shader。** 建议在 RTest 中保留近处原生三角形 Section；远处使用独立、保守的 LOD RT representation，再进入自己的 BLAS/TLAS/material ABI。
3. **视距方面，Voxy 可以在更大距离以较低 RT 几何/AS 成本工作，但不能保证较低总帧时开销。** 它把选择、Hi-Z、请求和异步 meshing 放到 GPU/后台线程；RTest 若逐个 LOD 节点重建 BLAS，AS build、TLAS 更新、材质/光照重建可能抵消收益。必须先做 GPU timestamp 和显存 A/B。
4. **预计收益应以三角形数而不是“视距倍数”估算。** RTest 当前每三角形至少需要 36 B 顶点 + 112 B material（148 B，不含 BLAS/分配/临时 buffer）；TLAS 每实例为 `VkAccelerationStructureInstanceKHR.SIZEOF`（通常 64 B）。Voxy 的远景 mesh quad 是 8 B、section metadata 是 32 B，但该格式不能直接用于 RTest hit shader。若远景表面三角形数降到当前的 10%--30%，RTest 的 vertex/material 常驻量可近似降到同区间；BLAS 节省量通常小于/大于该比例取决于驱动 AS 内部压缩和 LOD 节点实例数，不能仅凭源码承诺具体 MiB。

## 1. Voxy 的数据层和层级

### 1.1 section/LOD/父子关系

Voxy `WorldEngine.MAX_LOD_LAYER = 4`，见 [`WorldEngine.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/world/WorldEngine.java)。[`WorldSection.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/world/WorldSection.java) 将一个 section 定义为 **32×32×32 的 detail region**，以 `(lvl,x,y,z)` 和 `nonEmptyChildren` 表示层级节点。section 的 `long[] data` 在 Java 侧固定为 `32*32*32` 个 long；它是逻辑世界/缓存数据，不等同于最终 GPU mesh。

层级节点的空间尺度由 shader 使用 `1 << (lodLevel + 5)` 计算（32、64、128…… block），见 [`traversal_dev.comp`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp)。因此它不是把同一张近景 mesh 缩放，而是每一级有自己的 section/mesh，远处节点可以替代八个子节点。

### 1.2 voxel brick 与 mip

导入/体素化阶段使用 [`VoxelizedSection.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/voxelization/VoxelizedSection.java)：一个 16³ 的 LOD0 voxel 区域，加上 8³、4³、2³、1³ 的 mip 数据，存储总数是 `16³ + 8³ + 4³ + 2³ + 1` 个 long。[`WorldVoxilizedSectionMipper.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper.java) 对每个 2×2×2 八邻域逐级生成 mip。

这可以视作固定大小的 voxel brick + 多级 mip，而不是通用稀疏 virtual texture。父级 world section 仍由 `WorldSection` 层级树管理，GPU 只接收当前渲染节点和 mesh metadata。

### 1.3 mipper 的视觉近似和限制

[`Mipper.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/world/other/Mipper.java) 当前策略是：八个输入中选 opacity 较高的非空气 block；若全为空则平均 block/sky light。源码明确留下了按 opacity、保留可见性/visual bounding box、分级保真度改进的 TODO。因此这是用于远景近似的离散代表 block，不是几何精确的体积/材质合并。

这对 RTest 很重要：直接把 Voxy 远景 LOD 当作有正确 UV、法线、透明度和 PBR 的 Minecraft 三角形是错误的；需要定义“远景材质代表”和保守的光照/透明策略。

## 2. Voxy 的 meshing、GPU buffer 和更新

### 2.1 mesh 格式

[`RenderDataFactory.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java) 把 section mesh 编成 64-bit quad，每个 quad 8 B。一个 section 的 quad 按 8 个类别分段：translucent、double-sided、以及六个方向；`BuiltSection` 保存 `MemoryBuffer geometryBuffer`、8 个 `offsets`、AABB 和可选 occupancy。

quad 位域见 [`quad_format.glsl`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/quad_format.glsl)：位置、二维尺寸、face、model/state id、biome id、light id 都是压缩字段。GPU 使用 section metadata（32 B）中的位置、AABB、quad 起点和各 segment count，见 [`section.glsl`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646aa90e2/src/main/resources/assets/voxy/shaders/lod/section.glsl) 和 [`BasicSectionGeometryManager.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicSectionGeometryManager.java)。

`BasicSectionGeometryData` 为几何创建一个预定容量的 GL buffer 和 `maxSectionCount * 32` 的 metadata buffer；支持 sparse buffer workaround，但“capacity”与实际 committed/used bytes 是两个概念，见 [`BasicSectionGeometryData.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2)。

### 2.2 CPU/GPU 异步更新

[`RenderGenerationService.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java) 在后台生成 `BuiltSection`。[`AsyncNodeManager.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java) 将 geometry update、node update、child update 和 top-level-node 增删聚合，再在 render thread 以 upload stream/compute copy 发布；源码还限制每轮上传量、剩余 geometry 空间和每帧 2 MiB 左右的同步工作。

这是可借鉴的发布边界：后台线程只生成不可变结果，GPU 侧用局部 buffer/metadata 更新，树节点在发布时交换；不是可直接搬到 Vulkan 的 API。

### 2.3 hierarchy traversal、屏幕空间和 Hi-Z

[`HierarchicalOcclusionTraverser.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java) 为层级树准备 request/render queues、node buffer、queue scratch buffer 和 visibility tracker。实际选择在 [`traversal_dev.comp`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp)：

- 先做 frustum 和 Hi-Z occlusion；
- 根据 `shouldDecend()` 的屏幕空间阈值决定继续向子节点走还是渲染当前节点；
- 没有 children mesh 时加入 request queue；
- 缺 mesh 时按策略渲染父 mesh 或继续子节点，避免空洞；
- 以 `closestPointToCamera`/`furthestPointToCamera` 和 `renderDistance` 做距离裁剪。

`renderDistance` 在 shader 中是 XZ 平方距离，Java 在 `HierarchicalOcclusionTraverser.uploadUniform()` 中以 `sectionRenderDistance*16*32` 转换。它不是简单的 `(2r+1)²` 全量遍历，而是对层级节点做屏幕尺寸和遮挡驱动选择。

## 3. Voxy 的错误、裂缝、透明和光照语义

### 3.1 缺 mesh/流式期间

shader 对缺失 node mesh 会发 request；若有 children 则下钻，否则尽可能渲染自己的 mesh。源码注释承认 leaf 应有 mesh，否则是图结构错误；这说明它有“父级/子级 fallback”思路，但不是严格保证无缝的 geomorph。

Voxy 的 section metadata 有 child existence，node cleaner/visibility tracker 处理长期未见节点；geometry manager 删除旧 mesh、上传新 mesh，再更新 metadata。该设计能减少空白，但在 LOD 边界仍可能出现不同细节级别的 T-junction。调查到的 `2622` 源码没有发现针对 RTest 需要的 RT 射线不连续专门处理，也没有看到 geomorph/skirt 作为 Vulkan RT 几何契约。

RTest 若采用该方案，应至少做：父节点保底、LOD 边界 skirt/封口，或让相邻节点选择规则保证同级邻接；同时在切换期间保留旧 BLAS/TLAS，不能先把实例置空。

### 3.2 实体和透明

Voxy 的核心数据是 voxel/block model 的远景 raster LOD。`ModelFactory` 的 metadata 有 face existence/occlusion、translucent 分类和模型纹理；`RenderDataFactory` 也区分 translucent/double-sided quad。但这不等于它支持 RTest 所需的实体动画、透明 shadow payload、alpha test、折射/透射和动态物体 BLAS。调查的 LOD pipeline 没有实体/方块实体的统一 Vulkan RT representation。

因此：不应把 Voxy LOD 当作实体 LOD；动态实体继续使用 RTest 自己的 dynamic BLAS/TLAS/fallback。透明远景需要另行规定 alpha-test 或保守 opacity，不能使用 Voxy 的 raster translucent 分类直接填 RTest material ABI。

### 3.3 光照

mip 阶段只携带压缩 block/sky light，并以近似规则选择/平均（`Mipper.mip`）。quad 也编码 light id；这是 Voxy raster LOD 的顶点/像素光照输入，不是 RTest 的完整太阳盘、shadow ray、NRD、LabPBR 或 `RayTracingLightTree` emitter hierarchy。发光块在远景 mipper 里也没有与 RTest emissive triangle/light-tree 等价的采样保证。

## 4. RTest 当前实际内存和更新模型

### 4.1 CPU/scene snapshot/material

RTest [`RayTracingScene.java`](../src/main/java/com/rtest/client/RayTracingScene.java) 的 `SceneGeometry.SectionGeometry` 每三角形有：

- `vertices`: 9 floats = 36 B；
- `materialData`: 28 floats = 112 B；
- 合计 148 B/triangle，尚未包含 Java array/object、对齐、VMA buffer、BLAS 和 scratch。

`SceneGeometry` 还拥有 flattened `vertices`、flattened `materialData`、`pbrData`、section list 和整场景 `RayTracingLightTree.Data`。`replaceSections()` 即使只替换 dirty sections，也要按剩余 sections 计算长度、重新分配并 flatten 两个全场景数组（源码注释明确指出这是当前限制）。这既是 CPU 峰值内存，也是发布时可能出现旧/新 snapshot 同时存在的原因。

`RayTracingPbrMaterials.java` 固定创建 64 MiB host-visible PBR SSBO（`GPU_BUFFER_BYTES = 64 * 1024 * 1024`），最多 8192 maps；这项内存与 LOD 是否减少三角形无关，除非同时改变 PBR map ABI。`RayTracingLightTree.java` 为发光三角形建立 node/emitter/material reverse map；远景合并材质后必须重新定义 emitter，否则旧 light tree 不能正确采样。

### 4.2 BLAS/TLAS

RTest [`RayTracingVulkanPass.java`](../src/main/java/com/rtest/client/RayTracingVulkanPass.java)：

- 每个非空静态 Section 一个 `CachedBlas`，按 section key、vertex fingerprint、triangle count 复用；每个 BLAS 有自己的 vertex buffer；
- 每个 dynamic object/slot 有独立动态 BLAS，动画可 BLAS UPDATE；
- 一个 TLAS 包含静态 section instances 和最多 64 个 dynamic slots；源码注释说明用 instance mask 区分 primary/secondary visibility，以避免第二个 TLAS；
- `instanceBuffer` 大小为 instance 数乘 `VkAccelerationStructureInstanceKHR.SIZEOF`；
- `materialBuffer` 按场景 triangle 数加动态预留区分配，静态 material 仍是一 triangle 一记录；
- `scratchBuffer` 至少覆盖 TLAS/BLAS build scratch；
- 几何发布会候选化 BLAS、instance buffer、TLAS、material/light/PBR buffer，成功后提交；当前仍只有一个 pending frame fence，下一次覆盖/回收要等 fence。

这意味着 RTest 的远景压力不只是 vertex/material：section 数会带来更多 BLAS 对象、TLAS instance、build/update 命令和 scratch 峰值。把 Voxy 的大量层级 section 原样变成 BLAS 会适得其反。

### 4.3 capture/window/视距

[`RayTracingProbe.java`](../src/main/java/com/rtest/client/RayTracingProbe.java) 当前用 `getEffectiveRenderDistance()`，整块 XZ 窗口遍历 FULL chunks；[`RayTracingScene.SceneGeometry.collectSectionOrigins`](../src/main/java/com/rtest/client/RayTracingScene.java) 以 `radius = max(2, renderDistanceChunks)` 枚举 `(2r+1)²` chunk，并按 loaded/non-empty section 捕获。相机移动时只增量加入/移除 section origin；dirty section 也分帧处理。

CPU fallback capture 约束为 `SECTIONS_PER_FRAME = 2`，单事务 8 sections；这降低尖峰但不降低最终 resident geometry。视距增加后，RTest 仍按近似逐 section/逐三角形捕获，没有 Voxy 的“远处用父节点替代大量子节点”的选择。

## 5. 兼容性评估

### 可复用

1. **World/section 层级坐标和 generation 思路**：可以在 RTest 建立 `LodNodeKey(level,x,y,z)`，保存 children existence、mesh state、generation 和 last published BLAS。
2. **屏幕空间 + Hi-Z + frustum + distance 选择**：可移植算法思想。对于 RT，Hi-Z 可用于 primary visibility/历史结果的候选裁剪，但不能把 raster Hi-Z 当作所有 secondary ray 的有效遮挡证明。
3. **bounded request queue 和后台 meshing**：Voxy 的 request queue、`RenderGenerationService`、`AsyncNodeManager` 的“请求—后台生成—局部发布”边界适合 RTest 的 capture/merge worker。
4. **父级 fallback 与延迟退休**：LOD 切换期间保持上一份有效几何；将新 BLAS/TLAS 完成并通过 fence 后再切换，符合 RTest 当前 `pendingFrameFence` 安全模型。
5. **压缩 quad/mesh 数据的思路**：可以为远景设计自己的 compact triangle/quad IR，减少 vertex 和 material 带宽；不能直接让 RTest shader 读取 Voxy 的 64-bit quad。

### 不能直接复用

1. **GL buffer/VAO/compute upload**：Voxy 使用 OpenGL buffer、GLSL 绑定、`UploadStream`、compute copy；RTest 使用 Mojang Vulkan device、VMA `NativeBuffer`、Vulkan device address 和 acceleration structures。
2. **Voxy quad shader 作为 RT geometry**：Voxy quad 依靠 raster vertex shader 解释 position/size/face/model id；Vulkan BLAS build 需要三角形 AABB 输入和 device address，不能把 quad buffer 直接作为 triangles。
3. **Voxy section metadata 作为 RTest material**：32 B section metadata 没有三角形 UV、normal、optical、PBR map index、emission 或 dynamic motion。RTest hit shader 的 28-float ABI 不能由它替代。
4. **Voxy mip block 作为精确 Minecraft material**：`Mipper.mip()` 选代表 block，远景一个 voxel 可能包含多种纹理/透明/发光/法线；直接映射会改变反射、阴影和发光。
5. **raster occlusion 直接用于 secondary rays**：Voxy Hi-Z/face occlusion 是 raster 选择优化；RTest shadow/GI/reflection rays 仍须使用正确 TLAS 几何，否则会产生漏光和错误遮挡。
6. **同一树同时承载动态实体**：Voxy 这套 LOD 数据不覆盖 RTest 的 entity model、skin/item texture、motion metadata、history reset 和透明 fallback。

## 6. 对“降低显存、低开销、更大视距”的判断

### 显存

**有条件地可行，且对 RTest 有较大潜力。** 当前 RTest 的远景每三角形 148 B 的显式 payload，加上每 Section BLAS vertex buffer、AS 内部存储、material/light data 和 TLAS instances。若 LOD 远景将三角形数量压缩到 10%--30%，显式 vertex/material 常驻内存大致也压到 10%--30%；TLAS instance 由“每 native Section”变为“每选中 LOD node”，通常也会下降。

但有三个反例：

- 若为每个 Voxy node 建 BLAS，节点数过多会吞掉收益；应按较大的 LOD cluster 合并 BLAS，或让多个 node 共享/复用 BLAS；
- RTest 的固定 64 MiB PBR buffer、camera/output/FSR/NRD 图像不会因地形 LOD 自动减少；
- replacement TLAS/BLAS/scratch 和旧 scene fence 可能造成瞬时峰值，短时间显存反而上升。

因此当前不能声称固定“节省 X GiB”。应测量：静态 vertex/material bytes、BLAS allocation size、TLAS size、scratch peak、old+new publication peak、总 dedicated GPU memory。

### 性能

**RT trace 阶段大概率受益，发布阶段存在风险。** 更少的 triangles/BLAS instances 会减少 AS traversal candidate、material fetch 和 memory traffic；层级选择还避免将不可见/过细节节点送入 trace。另一方面，Voxy 的 GPU traversal 和异步 meshing 自身也有成本，RTest 新增 LOD 后还会承担：LOD node selection、mesh generation、BLAS build/update、TLAS rebuild/update、材质/light-tree remap、历史 reset。

低开销设计的关键不是照搬 Voxy，而是：近景 Section BLAS 保持不变；远景 LOD 以有限数量的 coarse cluster BLAS 发布；无变化帧只更新 TLAS transform/instance selection；LOD 变化用 request budget/backpressure；新旧资源用 timeline/fence 延迟退休。

### 画质与正确性

Voxy 当前 mipper 的 opacity/light 近似不足以承诺 RTest 的 PBR、透明、发光和软阴影正确。推荐的初版范围是：

- 只对远处不透明、非实体地形启用 LOD；
- 远景使用 opaque alpha-tested/保守不透明材质，禁用复杂折射和 per-pixel normal map；
- emissive block 只进入一个聚合/保守 emissive proxy，或在远景关闭 direct-light sampling；
- 保留近景真实 Section geometry 作为 overlap band，LOD 边界采用父级 fallback/封口；
- 动态实体、透明水/玻璃、block entity 继续走 RTest 现有路径或 vanilla fallback。

## 7. 推荐的 RTest 接入路线（不改变当前生产代码）

### 阶段 A：只做测量

1. 在 `RayTracingVulkanPass` 记录每个距离环的 triangle count、static Section count、BLAS bytes、TLAS instance count、material bytes、scratch bytes。
2. 记录 full/dirty/window publication 的 old+new peak；用 Vulkan timestamp 分离 capture/merge、BLAS build、TLAS build/update、trace、FSR/NRD。
3. 固定一个世界和相机路径，比较当前 RTest、只减少 capture window、以及离线生成的 coarse mesh；不要先改动态实体。

### 阶段 B：RTest-native LOD IR

1. 新建独立 LOD node/brick snapshot，不引入 Voxy GL 类；每 node 记录 `level/key/bounds/generation/meshState/materialClass`。
2. 从 `ClientLevel`/已捕获 section 输入生成 opaque coarse surface；不要直接使用可变 Minecraft 对象跨 worker。
3. 定义 RTest compact RT vertex/material ABI：至少 position、normal/face normal、UV/texture/material ID、opacity/alpha-test、emission class、light proxy。
4. 先把多个远景 node 合并成一个或少数 BLAS cluster，控制 TLAS instance 数；确保新 cluster 成功后再原子替换旧 cluster。

### 阶段 C：选择与生命周期

1. 复用 Voxy 的屏幕空间阈值思想，但同时加入 RT primary/secondary 可见性规则；不要用一次 raster Hi-Z 结果裁剪所有 ray。
2. 用父级 fallback 防止 mesh request/BLAS build 期间出现空洞；用 LOD hysteresis 防止相机移动时频繁切换。
3. 使用 RTest 已有 generation/window acceptance 和 fence；未来异步化必须把旧 BLAS、TLAS、instance/material buffer 放入 submission-scoped retire，不能直接 close。
4. 只有在 A/B 证明 trace 与 AS build 均下降后，才扩大远景距离。

## 8. 最终建议

Voxy `2622` 值得借鉴，但推荐把它当作**层级空间数据与流式发布参考实现**，而不是 RTest 的 RT 几何后端。最现实的目标是“近景原生三角形 + 远景 RTest-native coarse BLAS”，而非把 Voxy 的 section quad buffer 接到 Vulkan TLAS。

在没有实机 profiling 前，合理的预期是：

- 静态远景显式几何/材质显存：可能减少约 70%--90%（仅当 coarse triangle ratio 达到 10%--30%，且不被 AS/固定 PBR buffer 抵消）；
- TLAS instance 数：可能明显减少，但取决于 cluster 粒度；
- RT traversal/material bandwidth：大概率降低；
- 首次加载、LOD 切换和区块更新长帧：可能变差，除非沿用 Voxy 式 bounded streaming + RTest fence-safe publication；
- 实体、透明、复杂光照：不能由 Voxy LOD 直接解决，必须保留 RTest/vanilla 专用路径。

所以答案是：**能作为架构方向降低 RTest 显存并支持更大视距，但不能直接复用；需要独立的 Vulkan LOD IR、cluster BLAS、材料降级契约和可测量的异步发布实现。**

## 主要源码索引

### Voxy `2622`

- [`README.md`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/README.md)
- [`WorldEngine.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/world/WorldEngine.java)
- [`WorldSection.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/world/WorldSection.java)
- [`VoxelizedSection.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/voxelization/VoxelizedSection.java)
- [`WorldVoxilizedSectionMipper.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper.java)
- [`Mipper.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/common/world/other/Mipper.java)
- [`RenderDataFactory.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java)
- [`BasicSectionGeometryManager.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicSectionGeometryManager.java)
- [`BasicSectionGeometryData.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicSectionGeometryData.java)
- [`AsyncNodeManager.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java)
- [`HierarchicalOcclusionTraverser.java`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java)
- [`traversal_dev.comp`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp)
- [`quad_format.glsl`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/quad_format.glsl)
- [`section.glsl`](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90e2/src/main/resources/assets/voxy/shaders/lod/section.glsl)

### RTest 当前工作树

- [`RayTracingScene.java`](../src/main/java/com/rtest/client/RayTracingScene.java)
- [`RayTracingVulkanPass.java`](../src/main/java/com/rtest/client/RayTracingVulkanPass.java)
- [`RayTracingProbe.java`](../src/main/java/com/rtest/client/RayTracingProbe.java)
- [`RayTracingPbrMaterials.java`](../src/main/java/com/rtest/client/RayTracingPbrMaterials.java)
- [`RayTracingLightTree.java`](../src/main/java/com/rtest/client/RayTracingLightTree.java)
- [`docs/MAINTENANCE.md`](../docs/MAINTENANCE.md)
- [`docs/PRIME-COMPARISON.md`](../docs/PRIME-COMPARISON.md)

## 15. RTest 当前 MVP 实现状态

本次研究后已加入一个默认关闭的 RTest-native MVP：

- `RayTracingTerrainLod`：稳定 level-1（2×2×2 Section）coarse node、opaque 过滤、确定性空间 bin 和距离滞回选择；
- `RayTracingTerrainLodScheduler`：有界优先队列、token/generation/fingerprint 校验、异步生成和 render-thread poll；
- `RayTracingProbe`：保留 native source geometry，远景 coarse node 完成后才在 render boundary 合成/发布，过期结果丢弃；
- `RayTracingScene`：将 coarse world-space mesh 转回 Section-local geometry，复用现有 material ABI、PBR table 和 LightTree；
- `terrainLodEnabled=false` 默认关闭，透明、流体、发光、block/entity 动态几何保持原生路径。

当前仍不是 Voxy 的完整多级 GPU traversal：level-2 及 Hi-Z GPU 选择尚未接入，视距仍受 Minecraft 已加载 chunk 窗口限制。启用前应使用固定场景测量 BLAS/TLAS/显存和 LOD 边界质量。
