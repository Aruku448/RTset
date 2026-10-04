# Terrain LOD 表面体素代理修复

## 问题与复现

2026-10-01 的截图显示粗地形缺面。原 `RayTracingTerrainLod.buildNode()` 每个空间 bin 只选一个三角形，沿该三角形法线扩展成一张平面。同一 bin 中的顶面、侧面因此互相淘汰，粗代理不闭合。只按三角形中心分 bin 还会丢失跨单元大面的覆盖。

回归入口：`./gradlew terrainLodTest --offline`。新增 `testCoarseVoxelClosesAllDirections` 在修改前失败：`coarse voxel lost exterior faces on axis 0`。这复现了缺面网格模式，不能替代截图场景的实机复测。

## 参考与实现

参考本地 `/home/aruku/voxy-NHblock714`：

- `common/world/other/Mipper.java`：代表状态与占用的选择分离。
- `client/core/rendering/building/RenderDataFactory.java` 的 `generateYZOpaqueInnerGeometry()`：从相邻占用差异生成有朝向的外露面，剔除内部面。

RT 输入只有原生 section 的三角形与材质快照，没有完整 block-state 体积。因此这里采用 **保守的表面体素化**，不是移植 Voxy 的 2×2×2 block-state mip，也不推断未捕获的世界体积：

1. 有效不透明三角形朝实心侧微移，避免网格边界面落进相邻空气单元。
2. 用三角形/体素盒的分离轴测试遍历真正相交单元，不只保留中心单元，也不填满整个三角形 AABB。
3. 每个占用单元保留六方向的代表材质，缺少该方向的样本时使用稳定的代表材质。
4. 占用/空单元之间生成面，占用/占用之间不生成面。每个孤立体素六面闭合。
5. 未知相邻节点边界保守保留面；边界处的微小位移限制在当前节点内。
6. 粗面的 UV 在代表源 tile 中铺一次，不对四角单独取模，以免整数重复周期使所有 UV 坍缩为同一纹素。外推后退化的自定义 UV 回退到非退化 tile 四角。
7. 法线和面积在 section-local 坐标计算，世界坐标加法先提升为 double，避免远坐标把一格宽三角形舍入为零面积。

未改变层级选择、native fallback、材质 ABI 或 Vulkan 资源生命周期。

## 验证范围与限制

测试覆盖六方向外露面、内部面剔除、大面覆盖、三角形 AABB 空角、正负坐标与节点边界、±16777216 远坐标、外向 winding、UV tile 范围与面积非退化（含斜 UV），以及输入顺序不影响输出。原 LOD/cache/traversal/scene 契约仍需通过。

表面体素化会把细薄表面扩展到粗单元厚度，无法完全达到完整世界体素 mip 的轮廓和材质精度。未知节点间边界可能保留重合面；未引入跨节点邻接剔面或 greedy meshing。输出面数与 CPU 构建成本可能上升，需要实机检查帧时间和显存。

截图场景画面、远近 LOD 过渡与相机移动仍需实机复测。本修复不声称解决调整窗口大小时的 `VK_ERROR_DEVICE_LOST`。
