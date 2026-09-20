# Dynamic Block Entity Geometry

## 结论

`BlockEntityRenderDispatcher` 的原生路径是：

1. `LevelRenderer`/`GameRenderer` 收集可见 `BlockEntity`；
2. dispatcher 为实体查找 `BlockEntityRenderer`；
3. `tryExtractRenderState(entity, partialTick, crumblingOverlay, ...)` 提取渲染状态；
4. `LevelRenderer.submitBlockEntities` 调用 `dispatcher.submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`；
5. 每个 renderer 通过 `SubmitNodeCollector.submitModel(...)` 提交延迟命令；
6. `FeatureRenderDispatcher.prepareFrame` 之后才真正执行 `Model.renderToBuffer`。

因此 `BakedModel` 不是可用的几何来源：箱子的 block-state model 只有 particle texture，没有箱体几何。

## 当前实现（箱子首版 → 通用模型通道）

RT 捕获发生在**最终顶点提交点**，而不是 `submit` 阶段：

```text
BlockEntityRenderDispatcher.submit        ← BlockEntityRenderDispatcherMixin 建立 owner 作用域
  └─ ChestRenderer/ShulkerBoxRenderer/... submit
       └─ SubmitNodeCollector.submitModel  ← SubmitModelOwnerMixin 记录 pose → owner
FeatureRenderDispatcher.prepareFrame
  └─ ModelFeatureRenderer.prepareModel
       └─ Model.renderToBuffer             ← PlayerModelCaptureMixin 复制最终顶点
```

- `BlockEntityModelGeometryAdapter` 是唯一 owner/几何适配器。
- owner 来自 dispatcher，因此不需要逐 renderer 的 mixin。
- 捕获使用 vanilla 已经完成的 `setupAnim`、模型选择、朝向变换、双箱左右模型和可见性。
- 顶点减去 `camera - blockPos`，得到 block-local BLAS 顶点；TLAS 只写 `translation(blockPos)`。
- 原版顶点流始终转发，原版 raster 绘制不被取消。
- 每层纹理在捕获时写入动态 texture descriptor slot（`optical.x`），并使用 atlas sprite 的 `getU/getV` 映射；delegate 仍收原始 UV，vanilla 只映射一次。

### 已知模型拓扑（`BlockEntityModelGeometryAdapter.SUPPORTED_MODELS`）

| 模型类 | 通道 | 拓扑 |
| --- | --- | --- |
| `ChestModel` | chest / trapped / ender / copper | `CHEST_TOPOLOGY` |
| `ShulkerBoxRenderer$ShulkerBoxModel` | shulker box | `SHULKER_TOPOLOGY` |
| `BellModel` | bell | `BELL_TOPOLOGY` |
| `BookModel` | lectern / enchanting table | `BOOK_TOPOLOGY` |
| `CopperGolemStatueModel` | copper golem statue | `STATUE_TOPOLOGY` |
| `BannerModel`, `BannerFlagModel` | banner / wall banner | `BANNER_TOPOLOGY`, `BANNER_FLAG_TOPOLOGY` |
| `SkullModel`, `PiglinHeadModel`, `DragonHeadModel` | skull / head | `SKULL_TOPOLOGY`, ... |

已知模型按类名使用固定拓扑；未列入表格的模型按完整类名哈希生成独立拓扑，因此仍然进入 RT，不会回退到原版路径。

### 捕获策略

`isSupportedLayer` 接受的层：

- 所有带可解析材质的模型；
- 非 outline，非 `sheetedDecalPose`；
- 有可解析的动态纹理；
- `RenderPipelines.BANNER_PATTERN`，或者
- 非 blending 且不是 `ENTITY_TRANSLUCENT*` 的 pipeline。

banner 是唯一的例外：vanilla 用 `bannerPattern`（`TRANSLUCENT` blend + `ALPHA_CUTOUT`）绘制染色旗面和图案层，其贴图是二值 alpha 像素画，因此按 alpha cutout 捕获可以得到正确的染色旗和图案，而不需要独立的半透明 RT pass。

被拒绝的层会转发给原版并在日志中记录一次（`RTest block-entity layer has no RT equivalent yet`）。

### PBR

捕获时如果 `RayTracingPbrMaterials` 已安装，则每个三角形按 sprite 与三角形 atlas UV 采样 LabPBR companion（`_n` / `_s`），写入既有 seven-vec4 material ABI：

| 索引 | 含义 |
| --- | --- |
| `lighting.w` (19) | PBR map index |
| 20 | roughness |
| 21 | metallic |
| 22 | emission（LabPBR `_s` alpha） |
| 23 | reflectivity |

没有 companion 贴图时保持原默认值，行为与之前一致。

## 身份、BLAS 与 TLAS

- `stableIdentity(dimension, type, pos)`：48-bit hash 加 `0x8001` 高位命名空间，与 32-bit entity id 不相交。
- 每个 block entity 一个 mutable BLAS；开盖等纯顶点动画使用 BLAS `MODE_UPDATE`，不重建 TLAS。
- 单箱/双箱或拓扑变化时先成功建立替换 BLAS，再切换 TLAS address。
- 消失时先把 TLAS mask 写 0，再经 registry retire 窗口回收。
- 固定容量溢出、descriptor 不可用或 BLAS 仍在构建队列时，对应实例 mask 为 0，不写非法 device address。

## RT 所有权

方块实体模型在 submit 阶段直接转为动态 BLAS，并由 TLAS 实例化；RT display copy 后不再执行
`BlockEntityRasterFallback` 或任何原版方块实体回放。

```text
LevelRenderer.submitBlockEntities        ← 捕获最终模型提交
FeatureRenderDispatcher.prepareFrame     ← 生成 RT 动态模型输入
动态 BLAS/TLAS + LabPBR SSBO
RT display copy（覆盖 main color）
hand / screen effects / HUD
```

- 固定动态槽位溢出会记录为 RT admission failure，不会回退到原版渲染并覆盖 RT 图像。
- 模型材质沿用 7×vec4 ABI，PBR 法线、金属度、粗糙度和发光数据进入同一个 PBR SSBO。

## 仍然缺失

- 粒子、云、天气、世界边界和文本仍属于独立通道，尚未进入实体动态 TLAS。
- beam / portal / conduit / spawner / vault 等非模型自定义几何尚未有 RT adapter。

## 边界与可选方向（不是当前执行计划）

1. 把粒子、云、天气、世界边界接入 RT frame graph。
2. 按同一 owner seam 增加 decorated pot、piston head、shelf、campfire 等模型通道（含 `submitModelPart` 与物品子模型）。
3. 完成 beacon/conduit/portal/sign 的自定义几何与文本路径。
4. 在 RT 内实现真正的半透明 pass，替代当前动态模型的 alpha-cutout 近似。
5. 为直接 entity PNG 增加独立 LabPBR companion 纹理寻址。
