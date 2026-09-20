# Minecraft 原生世界渲染审计

> 审计对象：Minecraft 26.2 / NeoForge 26.2.0.84，当前 RTest 源码。
> 本文记录审计结果及 Caustica 风格 seam 的落地状态。

## 1. 结论摘要

RTest 当前采用 Caustica 的关键 before-hand seam：`AfterOpaqueFeatures` 只捕获数据，RT 插入在原生世界 Frame Graph 完成之后、手部/屏幕效果之前的覆盖式输出：

```text
GameRenderer.render
└─ renderLevel
   ├─ LevelRenderer.render
   │  └─ 原生 Frame Graph：clear → sky → main → entity_outline → clouds → weather → transparency → always_on_top
   ├─ RenderLevelStageEvent.AfterOpaqueFeatures
   │  └─ RayTracingProbe（捕获实体/场景，不执行 RT）
   ├─ AfterLevel / LevelRenderer 完成
   │  └─ GameRenderer before-hand seam → RayTracingProbe → RayTracingSmokeTest → RT/FSR 输出拷贝到 GameRenderer.mainRenderTarget()
   ├─ hand：手持物品
   ├─ screenEffects：火焰、传送门/水等屏幕效果
   └─ 3D crosshair
└─ doEntityOutline
└─ GameRenderer post effect（creeper/spider/Enderman/模组后处理）
└─ GUI
```

因此：

- RTest 静态 RT 几何来自原生可见 Section 的方块/流体捕获；`dynamicEntityMvpEnabled` 默认开启时，普通生物主体、玩家 body、第三人称手持物、掉落物和白名单方块实体会通过独立快照进入动态 BLAS/TLAS；复杂 entity layer 和特殊 block entity 仍不在 RT 中。
- 原生天空、云、天气、粒子、实体、方块实体等在 RT seam 之前已经绘制到 `main` 或中间目标；RT 随后把自己的结果拷贝到 `main`，已接入的动态实例由 RT 重新表示，未接入的对象在 RT 输出后按身份过滤回退到原生渲染。
- before-hand seam 之后仍继续绘制的内容不会被 RT 覆盖：手持物品、屏幕效果、3D 准星、实体轮廓合成、GameRenderer 后处理和 GUI/HUD。
- Entity outline 的实体本体会被 RT 覆盖，但 outline 后处理在 `AfterLevel` 之后执行，因此轮廓可能仍叠加在 RT 画面上。

本文中的“已覆盖”指已由 RTest RT 输出实际表示；“被覆盖”指原生已经绘制但随后被 RT 的 `main` 输出替换；“完全缺失”指当前 RT 输出既没有捕获，也没有在 RT 之后由原生重新绘制。

## 2. 准确事件与调用顺序

NeoForge `RenderLevelStageEvent` 在 `RenderLevelStageEvent.java` 中声明的顺序是：

1. `AfterSky`
2. `AfterOpaqueBlocks`
3. `AfterOpaqueFeatures`
4. `AfterTranslucentFeatures`
5. `AfterTranslucentBlocks`
6. `AfterTranslucentParticles`
7. `AfterWeather`
8. `AfterLevel`

`AfterLevel` 的准确位置是 `GameRenderer.renderLevel(DeltaTracker)`：

```java
this.minecraft.levelRenderer.render(...);
NeoForge.EVENT_BUS.post(new RenderLevelStageEvent.AfterLevel(...));
// hand
this.renderItemInHand(...);
// screenEffects
this.screenEffectRenderer.submit(...);
this.featureRenderDispatcher.renderAllFeatures(...);
// 3D crosshair
```

所以 `AfterLevel` 不是 `LevelRenderer` Frame Graph 内部的一个 pass。它是在 `LevelRenderer.render(...)` 返回、Frame Graph 已执行完毕之后触发的最后一个世界阶段事件。

RTest 对应入口为：

- `com.rtest.client.RayTracingProbe.onRenderLevelAfterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures)`（捕获）
- `com.rtest.mixin.GameRendererMixin`（before-hand seam）
- `RayTracingProbe.renderRtAtBeforeHand()`
- `RayTracingSmokeTest.run(...)`
- `RayTracingVulkanPass.dispatch(...)`

`RayTracingProbe` 将 `minecraft.gameRenderer.mainRenderTarget()` 传入 RTest；`RayTracingSmokeTest` 最终让 FSR display 图像拷贝到这个目标。

## 3. 原生 `LevelRenderer.render` Frame Graph

`LevelRenderer.render(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean)` 的建图和执行顺序如下：

### 3.1 建图阶段

1. `targets.main = frame.importExternal("main", gameRenderer.mainRenderTarget())`
2. 若启用 shader transparency，创建内部目标：
   - `translucent`
   - `item_entity`
   - `particles`
   - `weather`
   - `clouds`
3. `ClientHooks.fireFrameGraphSetup(...)`
4. `targets.entityOutline = frame.importExternal("entity_outline", entityOutlineTarget)`
5. 添加 `clear` pass
6. 条件添加 `sky` pass
7. 添加 `main` pass
8. 条件添加 `entity_outline` PostChain
9. 条件添加 `clouds` pass
10. 添加 `weather` pass
11. 条件添加 `transparency` PostChain
12. 条件添加 `always_on_top` pass
13. `frame.execute(...)`

### 3.2 实际 pass 顺序及内容

#### `clear`

清除 `main` 的颜色和深度：

- color：`fogColor`，alpha 为 `0`
- depth：`0.0`（Minecraft 使用 reversed depth）

#### `sky`：`LevelRenderer.addSkyPass(...)`

在非粉雪、非熔岩、非阻断天空的情况下绘制。使用 `SkyRenderer` 或维度/模组的 `customSkyboxRenderer`，包括：

- `SkyRenderer.renderSkyDisc`
- `renderSunriseAndSunset`
- `renderSunMoonAndStars`
- `renderDarkDisc`
- End sky / End flash

`AfterSky` 在天空绘制回调结束时触发。

#### `main`：`LevelRenderer.addMainPass(...)`

同一个 FramePass 内部顺序为：

1. `chunkSectionsToRender.renderGroup(OPAQUE)`：原生固体/ cutout Section
2. `AfterOpaqueBlocks`
3. 清除 `entity_outline`（当启用实体轮廓）
4. `featureFrame.executeSolid()`
5. `AfterOpaqueFeatures`
6. 从 `main` 复制深度到 `translucent`、`item_entity`、`particles`（目标存在时）
7. `featureFrame.executeTranslucent()`
8. `AfterTranslucentFeatures`
9. `featureFrame.executeOutline()`
10. `chunkSectionsToRender.renderGroup(TRANSLUCENT)`：原生半透明 Section
11. `AfterTranslucentBlocks`
12. `featureFrame.executeTranslucentAfterTerrain()`
13. `AfterTranslucentParticles`

#### `entity_outline` PostChain

当 `featureFrame.hasAnyOutline()` 且存在 `entity_outline` 后处理链时添加。输入包括：

- `main`
- `entity_outline`

它在云和天气之前执行，但其结果随后仍会经历云、天气、transparency 和 RTest 的 RT 覆盖。

#### `clouds`：`LevelRenderer.addCloudsPass(...)`

条件：云选项非 `OFF` 且 `cloudColor` alpha 大于零。

- 有 shader transparency 时写入内部 `clouds` 目标。
- 否则直接读写 `main`。
- 使用 `CloudRenderer.render(...)`，或 `LevelRenderState.customCloudsRenderer`。

当前 `AfterLevel` 在该 pass 之后，因此原生云会被 RTest 的 main 输出覆盖。

#### `weather`：`LevelRenderer.addWeatherPass(...)`

使用 `WeatherEffectRenderer.render(...)` 绘制雨雪；之后触发 `AfterWeather`，最后绘制 `WorldBorderRenderer`。

- 有 shader transparency 时使用内部 `weather` 目标。
- 否则直接读写 `main`。
- `AfterWeather` 位于天气之后、世界边界之前。

当前 RT 输出会覆盖天气和世界边界在 `main` 中的结果。

#### `transparency` PostChain

当 `GameRenderState.useShaderTransparency()` 为真时，`LevelRenderer.getTransparencyChain()` 使用 `LevelTargetBundle.SORTING_TARGETS`：

```text
main, translucent, item_entity, particles, weather, clouds
```

该链在云、天气之后、always-on-top 之前执行。它负责把各中间目标合成回 `main`。RTest 在整个 `LevelRenderer.render` 返回后才写入 `main`，所以该链的结果同样会被覆盖。

#### `always_on_top`

由 `featureFrame.hasAnyAlwaysOnTop()` 决定是否存在。它清除 `main` 深度后执行 `featureFrame.executeAlwaysOnTop()`，属于原生 `LevelRenderer` 内最后的世界 Frame Graph pass；仍早于 `AfterLevel`，因此也会被 RT 覆盖。

## 4. 特征内容的阶段归属

### 4.1 实体与方块实体

状态提交发生在 `LevelRenderer.submitFeatures(...)`：

```text
submitEntities(...)
submitBlockEntities(...)
submitBlockDestroyAnimation(...)
particlesRenderState.submit(...)
submitBlockOutline(...)
```

实体和方块实体随后由 `FeatureRenderDispatcher` 按 `RenderType` 分组：

- 不透明实体/方块实体：`PreparedFrame.executeSolid()`，位于 `AfterOpaqueBlocks` 之后、`AfterOpaqueFeatures` 之前。
- 半透明实体/方块实体：`PreparedFrame.executeTranslucent()` 内的
  - `shadows`
  - `translucentModels`
  - name tags / texts
  - custom geometry
  等阶段，位于 `AfterOpaqueFeatures` 之后、`AfterTranslucentFeatures` 之前。
- 半透明方块/物品和破坏覆盖：`translucentBlocksAndItems`、`breakingOverlay`、`waterMask`，也由 `executeTranslucent()` 执行。
- 轮廓：`executeOutline()`，位于半透明 feature 和半透明 terrain 之间。
- `alwaysOnTop`：位于 `LevelRenderer` Frame Graph 末尾。

**审计分类：**

- 原生实体/方块实体：**被 RT 表示或输出后回退**（原生先画，RT 输出替换 `main`，未接入对象随后按身份重绘）。
- RTest RT 中的玩家、生物、第一人称 body/item 和成功捕获的 item model：动态开关启用时 **部分已覆盖**；顶点来自 vanilla 实际 draw 的 tee capture，未捕获对象保留原生 fallback。
- 其他实体、复杂 entity layer 和特殊 block entity：**RT 缺失但保留可见**；未接入对象在 RT 输出后回退到原生渲染，当前仍没有通用特殊 renderer 的 Vulkan 几何适配。
- 原生实体轮廓：实体本体可能由上述动态路径重新表示，但 `GameRenderer.render` 在 AfterLevel 后调用 `levelRenderer.doEntityOutline()`，所以轮廓合成属于 **仍保留的原生阶段**。

### 4.2 方块 Section 与流体

`RayTracingScene` 使用原生可见 Section，当前 RT 支持：

- 固体方块模型
- cutout / Alpha Cutout
- 部分半透明方块
- 水体表面和侧面
- 方块/天空光、tint、法线、UV、PBR 参数

**审计分类：** **已覆盖（但不是原生 GPU 结果的直接复用）**。

限制：未进入 RT 捕获的原生额外 feature、移动方块、破坏覆盖、方块实体和部分完整半透明排序不属于该分类。原生对应画面会在 RT 拷贝时被替换。

### 4.3 粒子

粒子状态在 `submitFeatures` 中调用 `levelRenderState.particlesRenderState.submit(...)`。`SubmitNodeCollection.submitQuadParticleGroup(...)` 同时提交到：

- `solid`
- `afterTerrain`

`FeatureRenderDispatcher.PreparedFrame` 中：

- `solid` 粒子随 `executeSolid()` 绘制。
- `afterTerrain` 粒子随 `executeTranslucentAfterTerrain()` 绘制。
- `AfterTranslucentParticles` 在后者之后触发。

有 shader transparency 时，`particles` 是独立内部目标并在 transparency chain 中合成；无该选项时使用 `main` 路径。

**审计分类：**

- 原生粒子：**被覆盖**。
- RT 粒子：**完全缺失**。当前 `RayTracingScene` 没有捕获 `ParticleEngine`/`ParticlesRenderState`，RayTracingShaders 也没有粒子几何输入。

### 4.4 云

原生位置：`LevelRenderer.addCloudsPass(...)`，在 weather 前、transparency chain 前。

管线：

- `RenderPipelines.CLOUDS`
- `RenderPipelines.FLAT_CLOUDS`
- `CloudRenderer`

**审计分类：**

- 原生云：**被覆盖**。
- RT 云：**完全缺失**。RT miss 只采样 RTest 六面 skybox，不包含 Minecraft 云层体积/几何，也没有云 shadow/动画数据。

### 4.5 天气

原生位置：`LevelRenderer.addWeatherPass(...)`，由 `WeatherEffectRenderer.render(...)` 绘制雨雪，并在之后触发 `AfterWeather`。

管线：

- `RenderPipelines.WEATHER_DEPTH_WRITE`
- `RenderPipelines.WEATHER_NO_DEPTH_WRITE`
- 基于 `RenderPipelines.PARTICLE_SNIPPET`

**审计分类：**

- 原生雨雪：**被覆盖**。
- RT 天气：**完全缺失**。当前 shader 仅使用雨强、雷暴等状态改变天空/光照可见度，不生成雨雪柱体或天气纹理。

### 4.6 天空

原生位置：`LevelRenderer.addSkyPass(...)`，使用 `SkyRenderer`，位于 Frame Graph 最前。

管线：

- `RenderPipelines.SKY`
- `END_SKY`
- `SUNRISE_SUNSET`
- `STARS`
- `CELESTIAL`

**审计分类：**

- 原生天空几何、太阳、月亮、星星、日出日落、End sky：**被覆盖**。
- RT miss skybox：**已覆盖（替代实现）**。RTest 使用 `RayTracingSkybox` 六面 cubemap；Minecraft 的天空颜色、天空亮度、昼夜和天气状态只作为 RT 环境近似参数。

这不是原生天空的逐项覆盖：太阳/月亮/星星的原生几何及原生天体管线没有被 RT 捕获。

### 4.7 后处理

原生后处理分三类：

1. `LevelRenderer` 内：`entity_outline`、`transparency`，都发生在 `AfterLevel` 前，结果被 RT 覆盖。
2. `GameRenderer.render` 中 `levelRenderer.doEntityOutline()`：发生在 `AfterLevel` 后，继续叠加到 RT main。
3. `GameRenderer.render` 中 `postEffectId` 对应的 `PostChain.process(mainRenderTarget, resourcePool)`：发生在实体轮廓之后、GUI 之前，继续处理 RT 输出。

**审计分类：**

- `LevelRenderer` 的 transparency / entity_outline：**被覆盖**。
- `GameRenderer` 的实体后处理和模组后处理：**已覆盖在 RT 输出之上**。
- RTest 自身 FSR3、NRD、display composite：**已覆盖**，是 RT 输出产生 `main` 内容的内部后处理链。

## 5. `RenderTarget` 关系

### 5.1 `main`

`GameRenderer.mainRenderTarget()` 是外部导入的 `LevelTargetBundle.main`，也是 RTest 的最终目标：

```text
原生 clear / sky / world / clouds / weather / post chains
        ↓
AfterLevel
        ↓
RTest RT → NRD → FSR3 → display copy
        ↓
继续绘制 hand / screen effects / outline / GameRenderer post effect / GUI
```

`MainTargetMixin` 在 HDR active 时把 `MainTarget` 的颜色格式改为 `GpuFormat.RGBA16_FLOAT`；`RenderPassMixin` 在 HDR attachment 上把匹配的原生 `RGBA8_UNORM` color target 适配为 `RGBA16_FLOAT`。

### 5.2 `entity_outline`

`LevelRenderer.entityOutlineTarget` 是独立外部目标，创建为：

```java
new TextureTarget("Entity Outline", width, height, true, GpuFormat.RGBA8_UNORM)
```

`main` pass 开始时清除它，`featureFrame.executeOutline()` 写入它。`LevelRenderer.doEntityOutline()` 在 `AfterLevel` 后将它 blit/blend 到 `main`。

因此：

- outline buffer 本身不是 RTest RT 输入。
- outline 的合成点晚于 RTest，所以可能覆盖/叠加在 RT 图像上。
- RTest 不会自动清除或重建 `entity_outline`。

### 5.3 `translucent`、`item_entity`、`particles`、`weather`、`clouds`

这些是仅在 shader transparency 开启时由 Frame Graph 创建的内部 `RenderTarget`，均使用同一个 `screenSizeTargetDescriptor`：

```java
new RenderTargetDescriptor(
    screenWidth, screenHeight, true,
    mainRenderTarget.useStencil,
    clearColor,
    GpuFormat.RGBA8_UNORM
)
```

它们在 `LevelTargetBundle.SORTING_TARGETS` 中由 transparency PostChain 使用，并在该链完成后合成到 `main`。RTest 不读取这些 target；RTest 直接替换外部 `main`，所以这些 target 中的原生内容不会出现在 RT 最终图像中。

注意：HDR 模式下 `main` 可能是 `RGBA16_FLOAT`，但这些 Frame Graph 内部 target 的 descriptor 仍明确为 `RGBA8_UNORM`。`RenderPassMixin` 只对实际拥有 `RGBA16_FLOAT` attachment 的 render pass 做适配，不会把上述内部 target 自动改成 HDR 格式。

### 5.4 depth

`main` 携带颜色和深度。原生 world pass 使用 reversed depth，并在以下位置清除/复制深度：

- `clear` 清除 `main` depth 为 `0.0`
- translucent/item/particles target 从 `main` 复制 depth
- `always_on_top` 清除 `main` depth 后绘制
- `GameRenderer.renderLevel` 在 hand 前再次清除 `main` depth
- `GameRenderer.render` 在 GUI 前再次清除 `main` depth

RTest 的 FSR depth 是独立的 `R32_SFLOAT` 图像，绑定到 Vulkan descriptor，不是 Minecraft `mainRenderTarget` 的 depth attachment。当前 RT dispatch 不把实体、粒子、云、天气的原生深度合并到该 FSR depth；动态玩家/item 实例使用 RT 自身的命中深度。

## 6. 与审计目标对应的分类总表

| 内容 | 原生阶段 | 原生结果相对 RT | RT 当前状态 |
|---|---|---|---|
| Section 固体/cutout 方块 | `main` / opaque terrain | 被替换 | **已覆盖** |
| Section 半透明方块/流体 | `main` / translucent terrain | 被替换 | **部分已覆盖** |
| 玩家 / 生物 / item model | `executeSolid` / item entity stages | 被替换 | **动态开关启用时部分已覆盖** |
| 其他实体与复杂 entity layers | `executeSolid` / `executeTranslucent` | 被 RT 输出后回退 | **部分缺失（未适配部分回退）** |
| 方块实体 | `executeSolid` / `executeTranslucent` | 被 RT 表示或输出后原生回退 | **部分缺失（白名单模型进入 RT，其余回退）** |
| 粒子 | `executeSolid` / `executeTranslucentAfterTerrain` | 被替换 | **完全缺失** |
| 原生天空 | `sky` | 被替换 | **被替换；原生项完全缺失** |
| RTest skybox | RT miss | 直接产生最终 RT 环境 | **已覆盖（替代实现）** |
| 云 | `clouds` | 被替换 | **完全缺失** |
| 雨/雪 | `weather` | 被替换 | **完全缺失** |
| 世界边界 | `weather` pass 尾部 | 被替换 | **完全缺失** |
| entity outline 合成 | `doEntityOutline`，AfterLevel 后 | 继续叠加 | **已覆盖** |
| GameRenderer 后处理 | `postEffectId`，AfterLevel 后 | 继续处理 | **已覆盖** |
| 手持物品 | `renderItemInHand`，AfterLevel 后 | 继续绘制 | **完全缺失于 RT，但可见** |
| 屏幕效果 | `ScreenEffectRenderer`，AfterLevel 后 | 继续绘制 | **完全缺失于 RT，但可见** |
| 3D 准星 | `DebugCrosshairRenderer`，AfterLevel 后 | 继续绘制 | **完全缺失于 RT，但可见** |
| GUI/HUD | `GuiRenderer.render` | 最后绘制 | **完全缺失于 RT，但可见** |

## 7. `RenderPipelines` 审计清单

与目标内容直接相关的原生静态管线包括：

- 实体：`ENTITY_SOLID`、`ENTITY_CUTOUT_CULL`、`ENTITY_CUTOUT`、`ENTITY_TRANSLUCENT`、`ENTITY_TRANSLUCENT_CULL`、`ENTITY_TRANSLUCENT_EMISSIVE`、`ARMOR_*`、`END_CRYSTAL_BEAM`、`BANNER_PATTERN`、`BREEZE_WIND`、`ENERGY_SWIRL`、`EYES`、`ENTITY_SHADOW`、`LEASH`
- 方块/地形：`SOLID_TERRAIN`、`CUTOUT_TERRAIN`、`TRANSLUCENT_TERRAIN`、`SOLID_BLOCK`、`CUTOUT_BLOCK`、`TRANSLUCENT_BLOCK`、`BEACON_BEAM_*`、`END_PORTAL`、`END_GATEWAY`、`CRUMBLING`、`WATER_MASK`
- 粒子：`OPAQUE_PARTICLE`、`TRANSLUCENT_PARTICLE`
- 云：`CLOUDS`、`FLAT_CLOUDS`
- 天气：`WEATHER_DEPTH_WRITE`、`WEATHER_NO_DEPTH_WRITE`
- 天空：`SKY`、`END_SKY`、`SUNRISE_SUNSET`、`STARS`、`CELESTIAL`
- 轮廓/边界：`ENTITY_OUTLINE_BLIT`、`OUTLINE_CULL`、`OUTLINE_NO_CULL`、`WORLD_BORDER`
- 世界文本/调试：`TEXT*`、`LINES*`、`DEBUG_*`

RTest 当前没有从 `RenderPipelines` 读取粒子、云或天气 draw command，也没有把这些管线对应的顶点/实例数据普遍转换成 RT geometry；玩家、生物和可支持的 item model 是从 vanilla 实际 `VertexConsumer` draw 旁路复制。仅保留原生管线并不等于这些内容已被 RT 覆盖。

## 8. 最终审计结论

当前实际效果可准确描述为：

```text
原生 Minecraft 世界画面
    ↓（AfterLevel 前完成）
RTest 以 Section 方块几何、独立 skybox 和可选动态 player/item 实例重新生成 main
    ↓
原生 hand / screen effects / entity outline / GameRenderer post effect / GUI 继续叠加
```

所以 RTest 已经覆盖的是“Section 方块 + RT 环境近似 + 可选 player/item 动态实例 + RTest 自身后处理”，而不是完整 Minecraft world frame。复杂实体、方块实体特殊 renderer、粒子、云、天气、原生天空天体和世界边界的 RT 表示仍然缺失；其中多数在 RT 后没有原生重绘，属于最终画面的缺口。

## 9. 证据来源

- 项目：`README.md`
- 项目：`docs/DEVELOPMENT.md`
- 项目：`src/main/java/com/rtest/client/RayTracingProbe.java`
- 项目：`src/main/java/com/rtest/client/RayTracingSmokeTest.java`
- 项目：`src/main/java/com/rtest/client/RayTracingScene.java`
- 项目：`src/main/java/com/rtest/mixin/MainTargetMixin.java`
- 项目：`src/main/java/com/rtest/mixin/RenderPassMixin.java`
- Minecraft 26.2 patched sources：`LevelRenderer.java`、`GameRenderer.java`、`RenderPipelines.java`、`LevelTargetBundle.java`、`FeatureRenderDispatcher.java`、`SubmitNodeCollection.java`、`WeatherEffectRenderer.java`、`ParticleEngine.java`
- NeoForge 26.2.0.84 sources：`RenderLevelStageEvent.java`
