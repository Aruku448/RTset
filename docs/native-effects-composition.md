# RT 输出后的原生效果组合设计参考

> 审计对象：Minecraft 26.2、NeoForge 26.2.0.84、当前 RTest 源码。
> 本文记录 Caustica 风格的迁移边界。当前已实现第一步：`AfterOpaqueFeatures` 只负责原生实体/场景捕获，RT/NRD/FSR3 在 `GameRenderer.renderLevel` 的 before-hand seam 执行。独立 overlay target 与完整 Frame Graph composite 仍是后续阶段。
>
> 本文是边界/审计参考，不是当前开发计划。当前接手入口和优先级以 [`HANDOFF.md`](HANDOFF.md) 为准。

## 1. 结论

当前 RTest 在 `RenderLevelStageEvent.AfterOpaqueFeatures` 中只完成捕获；RT/NRD/FSR3 结果随后在 `GameRenderer.renderLevel` 完成原生 LevelRenderer（包括 shader transparency、云、天气）之后、第一人称手部之前拷贝回 `Minecraft.gameRenderer.mainRenderTarget()`。这采用了 Caustica 的关键 before-hand seam，避免 RT 输出再被 MC transparency chain 覆盖。

推荐总体策略：

1. Section 方块和流体继续由 RT 表示；不要为原生粒子、云、天气强行加入当前 TLAS。
2. 粒子、云、雨雪、雷电、火焰/烟雾优先保留 vanilla raster 实现，作为 RT 后 overlay。
3. 下界/末地天空的远场辐射进入 RT miss/environment；原生天空几何若仍需保留，则作为单独 overlay，而不是继续依赖 RT 前的原生 sky pass。
4. 水下、岩浆、传送门、失明、夜视、溺水、受伤、望远镜等屏幕效果保留原生 raster screen effect，放在 RT 输出之后。
5. `entity outline` 和 `GameRenderer` 原生 post chain 暂时保留在 Minecraft 当前的 RT 后阶段；将来若改为显式 Frame Graph pass，应保持其相对顺序。
6. 只有稳定、可获取动态实例数据且确实影响 RT 光照的对象，才考虑转换为 RT 几何/光源。当前阶段不建议把粒子或云加入 TLAS。

## 2. 当前 before-hand seam 的精确覆盖范围

调用链为：

```text
GameRenderer.render
└─ renderLevel
   ├─ LevelRenderer.render
   │  └─ clear → sky → main → entity_outline chain → clouds
   │     → weather → transparency chain → always_on_top
   ├─ RenderLevelStageEvent.AfterOpaqueFeatures
   │  └─ RayTracingProbe.onRenderLevelAfterOpaqueFeatures（仅捕获）
   ├─ AfterLevel / LevelRenderer 完成
   ├─ GameRenderer before-hand seam
   │  └─ RayTracingProbe.renderRtAtBeforeHand
   │     └─ RayTracingSmokeTest.run
   │        └─ RayTracingVulkanPass.dispatch
   │           └─ RT → NRD → FSR3 → display copy(main)
   ├─ hand / held item
   ├─ screen effects
   ├─ 3D crosshair
   ├─ levelRenderer.doEntityOutline
   ├─ GameRenderer post chain
   └─ GUI/HUD
```

入口位于 `src/main/java/com/rtest/client/RayTracingProbe.java:45`。它传入的是 `minecraft.gameRenderer.mainRenderTarget()`；`RayTracingSmokeTest` 最终把 FSR display 图像写回该 target。

### 会被 RT 颜色覆盖的效果

这些效果已经在 `LevelRenderer.render` 返回前完成，随后被 RTest 写入 `main` 的 RT 结果替换：

- 原生 `sky`：太阳、月亮、星星、日出/日落、End sky、天空闪光等；
- `main` 中的原生 Section 固体/cutout 和半透明 terrain（RT 对方块/部分流体有自己的表示）；
- 原生实体、方块实体、破坏覆盖和额外 feature；
- 原生粒子：`executeSolid()` 及 `executeTranslucentAfterTerrain()`；
- `clouds` pass；
- `weather` pass：雨、雪及其相关世界边界绘制；
- `entity_outline` 的 `LevelRenderer` 内部准备/后处理链；
- shader transparency 的 `transparency` PostChain，以及其中的 `translucent`、`item_entity`、`particles`、`weather`、`clouds` targets；
- `always_on_top` 世界 feature。

当前 RT 静态场景捕获原生可见 Section 的方块/流体几何；启用 `dynamicEntityMvpEnabled` 时还会接入已捕获的玩家/生物/第一人称/物品/白名单方块实体动态实例。RayGen 的天气状态仅改变环境亮度/可见度，不生成雨雪、云或雷电几何。

### 不会被该 RT 覆盖、仍在之后绘制的效果

以下内容发生在 before-hand RT seam 之后，因此当前仍可叠加到 RT 结果上：

- 手持物品/手臂；
- `ScreenEffectRenderer` 的火焰、传送门、水下等屏幕覆盖；
- 3D crosshair；
- `levelRenderer.doEntityOutline()` 的 outline 合成；
- `GameRenderer` 的 `postEffectId` 后处理链（苦力怕、蜘蛛、末影人或模组效果）；
- GUI/HUD（准星的 2D 部分、快捷栏、文本、菜单等）。

`NativeColorManagement` 在 HDR 激活时对所有产生显示颜色的原生 fragment shader 做 sRGB→linear 解码（数据类 shader 除外，见该类常量）；云、粒子、天气和 transparency 已在 RT seam 之前完成，颜色会被 RT 世界输出替换，尚未进入独立 overlay。

## 3. 各效果的处理决策

| 效果 | 当前状态 | 推荐归类 | 推荐位置与理由 |
|---|---|---|---|
| 粒子 | 原生先画，RT 后消失 | **RT 后 raster overlay** | 维持 `ParticleEngine` 的动态生命周期、排序、贴图和 alpha cutout；单独 overlay target，合成到 RT 显示结果后。发光粒子的光照暂不注入 RT。 |
| 云 | 原生 `clouds` pass 被覆盖 | **RT 后 raster overlay** | 云是屏幕/远场体积效果，原生 renderer 已有动画和天气逻辑；放在 RT 后可保留 vanilla 视觉，暂不构造体积 TLAS。 |
| 雨雪 | 原生 `weather` pass 被覆盖 | **RT 后 raster overlay** | 雨雪柱体和雪粒继续由 `WeatherEffectRenderer` raster 绘制；按云→天气顺序合成，并使用 RT/overlay 深度或明确的距离规则。 |
| 雷电 | 原生天气阶段的闪电/雷暴相关效果被覆盖；雷暴状态仍进入 RT 环境参数 | **RT 后 raster overlay**；闪光可作 RT 环境参数 | 闪电可见几何/flash 保留原生天气 overlay。雷电照明暂不生成动态 RT 光源，避免每次雷击重建场景；若后续需要，只传递短生命周期光源 buffer。 |
| 火焰/烟雾 | 世界中的相关粒子被覆盖；屏幕火焰效果在 RT 后仍存在 | **RT 后 raster overlay** | 世界火焰/烟雾按粒子处理；`ScreenEffectRenderer` 的第一人称火焰继续位于 RT 后。未来可把稳定火焰 block/entity 作为 RT emissive geometry，但不是当前阶段要求。 |
| 末地天空 | 原生 End sky 在 RT 前被覆盖；RT 当前使用固定六面 skybox | **RT environment/miss** | 维度天空应作为 miss/environment 的 sky radiance；End flash、特殊屏幕闪光仍走 RT 后 native screen/post overlay。当前固定 skybox 是替代实现，不等价于完整 End sky。 |
| 下界天空 | 原生下界天空在 RT 前被覆盖；当前 miss 仍主要采样固定 skybox | **RT environment/miss**，必要时 overlay | 下界雾、天空颜色和远场辐射应进入 RT environment；特殊天空纹理/动画若无法提供给 RT，则以独立 raster overlay 保留。不要把整个天空重新加入方块 TLAS。 |
| 水下 | 水/流体表面部分已有 RT 几何；水下屏幕扭曲/色调是 RT 前后之外的 screen effect | **RT 后 raster overlay** | RT 负责可见水面/几何，原生水下视野效果在 RT 后处理，避免被 RT copy 清掉。需使用 RT depth/linear depth 时应通过显式 composite 输入。 |
| 岩浆 | 岩浆流体可能由 RT Section 几何表示；岩浆屏幕效果在 RT 后 | **RT 后 raster overlay** | 维持原生岩浆屏幕 tint/扭曲；岩浆发光继续作为已有材质 emission，暂不创建动态光源。 |
| 传送门 | 传送门相关世界几何/特效未完整进入 RT；传送门 screen overlay 在 RT 后 | **RT 后 raster overlay** | 保留原生屏幕效果与动画；稳定的传送门几何未来可单独作为 RT emissive/transmissive geometry。 |
| 失明 | 原生 screen effect 在 RT 后 | **RT 后 raster overlay** | 直接保留原生遮罩/视距效果，不能让 RT sky 或 FSR 绕过它。 |
| 夜视 | 主要是原生后处理/颜色效果 | **RT 后 raster overlay** | 作为最终 scene color 的色调/曝光处理；RT 内的环境光仍可独立使用夜晚状态。注意不要重复施加夜视增益。 |
| 溺水 | 原生 screen effect 在 RT 后 | **RT 后 raster overlay** | 保留呼吸/水下 vignette 的 vanilla 时序。 |
| 受伤 | 原生 hurt overlay 在 RT 后 | **RT 后 raster overlay** | 最终颜色覆盖，不能转换为 RT 光源或几何。 |
| 望远镜 | 原生 spyglass/vignette screen effect 在 RT 后 | **RT 后 raster overlay** | 保留圆形遮罩、缩放和 UI 时序；RT 应提供被放大的场景，而不是复制遮罩到 shader。 |
| entity outline | outline buffer 在 RT 前生成，但 `doEntityOutline()` 在 RT 后合成 | **RT 后 raster overlay** | 当前合成仍可见，但 outline 对应实体本体未进入 RT，轮廓可能悬浮在错误的 RT 场景上；未来需共享 RT depth/ID 或同步实体几何。 |
| 原生 post chain | `LevelRenderer` 内的 chain 被 RT 覆盖；`GameRenderer` post chain 在 RT 后 | **保留为 RT 后 raster post** | 对 `main` 执行的最终 post chain 应继续位于 outline 之后、GUI 之前；需要声明 HDR/scRGB 输入输出格式，避免重复 tone-map。 |

## 4. 推荐 Frame Graph

目标是将“世界 RT”“原生动态 overlay”“屏幕效果”和“UI”分成明确资源，而不是让多个阶段隐式读写同一个 `main`：

```text
A. 原生世界准备
   native clear / sky / entity / terrain / native particles
   └─ 仅收集原生动态数据；其颜色不作为 RT 最终颜色

B. RT scene
   RT Section geometry + RT environment
   → NRD
   → FSR3
   → rt_display (display resolution, scene-linear/HDR)

C. RT 后原生世界 overlay
   rt_display → world_overlay
   ├─ particles (按 solid/after-terrain 语义分层)
   ├─ clouds
   ├─ weather / lightning / world border
   └─ 可选 entity/block-entity raster overlay
   → world_composited

D. 原生 screen/post effects
   world_composited
   ├─ hand / held item
   ├─ water/lava/fire/portal/blindness/night-vision/drowning/hurt/spyglass
   ├─ entity outline composite
   └─ GameRenderer post chain
   → post_composited

E. UI
   post_composited → crosshair / HUD / menus → swapchain
```

### 建议的具体插入点

- **RT 前数据准备点**：在 `LevelRenderer` 的 `submitFeatures`/可见性收集阶段只采集粒子、云、天气、实体 outline 所需的动态 state；不要把这些颜色当作 RT 输入。Section capture 仍按现有增量策略运行。
- **RT dispatch 点**：保持 `RenderLevelStageEvent.AfterLevel` 作为当前兼容入口。它适合验证 RT，但不是理想的 Frame Graph 内部 pass，因为原生 `clouds`、`weather`、`transparency` 已经执行完毕。
- **推荐的 RT 输出点**：将 RT/NRD/FSR3 产物写入独立的 `rt_display`，在 display-resolution、scene-linear 格式下完成 overlay composite；不要直接把所有 overlay 重新写入 RT 输入图像。
- **推荐的 world overlay 点**：位于 FSR3 display 后、`hand` 前；顺序固定为 `particles → clouds → weather/lightning → world border`，并使用独立 overlay target。这样可避免 `AfterLevel` 前的原生颜色被 RT copy 覆盖。
- **推荐的 screen effect 点**：位于 world overlay 后、GUI 前；继续调用原生 `ScreenEffectRenderer`，并使其读取 `rt_display/world_composited`。
- **推荐的 outline/post 点**：outline composite 后执行 `GameRenderer` post chain，最后进入 GUI/HUD。若未来将 `doEntityOutline()` 移入统一 graph，仍需保持“outline → post chain → GUI”的顺序。

由于当前工程没有 overlay target、原生动态 draw command 导出或 RT 与 Minecraft depth 的合并接口，上述“推荐点”是规划边界，不是当前已有实现。当前代码不应通过在 `AfterLevel` 中再次调用原生世界渲染来补救：这会重复提交/绘制世界，并可能破坏深度、transparency 和 HDR attachment 状态。

## 5. 暂不建议的 RT 化项目

- **粒子/雨雪**：生命周期短、数量和排序动态，转 TLAS 的更新成本高，且多数只影响最终可见颜色；优先 raster overlay。
- **云**：云的体积/动画/天气耦合明显，当前 skybox 也不包含云 shadow；先使用原生 overlay。
- **雷电光源**：雷击是瞬时事件，不应触发整场景重捕获；若实现，使用独立动态光源 buffer，不改 Section 材质索引。
- **屏幕效果**：失明、受伤、望远镜等没有必要成为 RT geometry/light；它们本质是最终屏幕合成。
- **原生 post chain**：不应复制 Sundial 或 iterationRP 源码；只需在 graph 中保留兼容的输入/输出边界和顺序。

## 6. 验收清单

1. 粒子、云、雨雪和雷击在 RT 开启时仍可见，且不触发全场景重捕获。
2. 云在雨雪之前，天气不会被 FSR display copy 再次清除。
3. 火焰、传送门、水下、岩浆、失明、夜视、溺水、受伤和望远镜效果只应用一次。
4. entity outline 不再依赖 RT 前的实体颜色；至少明确记录其“轮廓保留、实体本体缺失”的限制。
5. `GameRenderer` post chain 位于 outline 之后、GUI 之前，并能正确读取 HDR/scRGB `main` 或等价 composite target。
6. End/Nether 场景分别验证天空、雾、闪光和颜色；不能把固定六面 skybox 误认为完整原生维度天空。
7. Vulkan 路径不增加每帧 `waitIdle()`，不因动态 overlay 触发 BLAS/TLAS 全量重建。

## 7. 证据文件

- `README.md`
- `docs/DEVELOPMENT.md`
- `docs/vanilla-render-audit.md`
- `src/main/java/com/rtest/client/RayTracingProbe.java`
- `src/main/java/com/rtest/client/RayTracingSmokeTest.java`
- `src/main/java/com/rtest/client/NativeColorManagement.java`
- `src/main/java/com/rtest/mixin/MainTargetMixin.java`
- `src/main/java/com/rtest/mixin/RenderPassMixin.java`
