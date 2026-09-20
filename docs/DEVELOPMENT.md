# RTest 开发文档

本文档说明 RTest 的模块职责、渲染数据流、扩展方式和验证方法。RTest 当前是 Minecraft 26.2 NeoForge 的 Vulkan Ray Tracing 原型，不是完整的 Minecraft 渲染器替换方案。

## 1. 开发环境

- Minecraft：`26.2`
- NeoForge：`26.2.0.84`
- Java：`25`
- Gradle：`9.5`
- Vulkan：AMD RADV / RX 7800 XT
- Mod ID：`rtest`

源码目录与游戏实例分离。默认开发实例为：

```text
/home/aruku/.minecraft/versions/RTest
```

## 2. 模块结构

### `RTest`

注册 NeoForge Client Config：

```text
src/main/java/com/rtest/RTest.java
```

### `RayTracingProbe`

Minecraft/NeoForge 事件适配器：

- 处理 `F8` 启停 RT；
- 处理 `F9` 设置界面；
- 在 `RenderLevelStageEvent.AfterOpaqueFeatures` 中只捕获原生实体/场景；按照 Caustica 的 before-hand seam，在 `GameRenderer.renderLevel` 完成 LevelRenderer（包括 transparency、云和天气）后运行 RT，使手持物、屏幕效果和 GUI 继续由原生路径绘制；
- 读取原生视距和 `IRenderableSection`；
- 触发场景重新捕获。

该类不应直接管理 Vulkan Handle。

### `RayTracingSmokeTest`

对外的浅接口，只负责：

- 检查当前 RenderTarget 和 Vulkan 类型；
- 创建、替换和关闭 `RayTracingVulkanPass`；
- 将失败转换为日志和 `false` 返回值。

### `RayTracingScene`

负责 CPU 侧场景捕获：

- `SectionCompilerCaptureMixin` 在 vanilla `SectionCompiler` 完成异步编译时复制 `MeshData`，`CaptureSession` 优先消费该快照，避免重复遍历 `BlockState/Quad`；尚未编译或格式不兼容的 Section 继续使用 CPU fallback；
- 编译网格快路径保留 vanilla 顶点位置、UV 和法线，但忽略其中携带面着色/AO 的 `Color`，也不读取 `UV2` 的 Block/Sky Light；由于 `MeshData` 不携带 sprite/material ID，该路径使用保守默认 PBR，需 LabPBR/精确材质时仍由 CPU fallback 提供；包含发光方块的 Section 会强制走 CPU fallback；缓存为有上限的 LRU，避免大视距无限保留 MeshData 快照；
- 遍历 Minecraft 原生可见 Section；
- 读取 `BlockStateModel` 和 `BakedQuad`；
- 读取方块纹理 UV、Tint、面法线、透明层；
- 不读取 Block/Sky Light 或 vanilla baked ambient；Minecraft `lightEmission` 只作为独立的可见材质 emission 输入，并使用统一的平方等级标定；
- LabPBR `_s` 的 alpha `0..254` 作为 authored emission 覆盖值，`255` 是未指定 sentinel；它仍只写入现有 surface emission 标量，不建立局部光源；
- 捕获水体和岩浆表面/侧面，并根据相邻 FluidState 高度生成流体表面法线；
- 为水、岩浆、玻璃、染色玻璃写入 IOR 和吸收系数；
- 含流体的 Section 强制走流体感知 CPU 路径，避免 compiled MeshData 丢失流体材质和高度；无流体 Section 继续使用 compiled MeshData 快路径；
- 生成相对摄像机的三角形数据和材质数据；
- 校验顶点、三角形和材质数据长度。
- 方块修改进入 dirty Section 队列，只重捕获当前 Section 及边界相邻 Section，然后替换现有场景几何；区块加载/卸载按相关 Section 与 camera-window delta 增量处理，只有显式场景失效事件才请求完整重捕获。增量捕获复用同一个 PBR 材质表，避免材质索引漂移。

捕获范围来自原生 `IRenderableSection`，因此高度、视锥剔除、遮挡可见性、空 Section 和视距由 Minecraft 原生渲染器决定。

### `DynamicInstanceRegistry`

动态对象的数值快照与 RT 实例生命周期：

- 复制 entity/block-entity 的数值快照，不持有可复用的原生 render state；
- 以 topology/material key 标记几何变化；
- 提供稳定 slot、generation、active mask 和 current/previous transform，供动态 BLAS/TLAS 与 motion 使用；
- 对消失对象保留有限 retire window；Vulkan BLAS/TLAS 和 GPU 资源仍由 `RayTracingVulkanPass` 管理。

### 原生玩家几何与动画

`LevelPlayerCaptureMixin` 将捕获限定在原版世界 feature 准备阶段；`PlayerModelCaptureMixin` 在 `ModelFeatureRenderer` 已完成 `setupAnim` 和 visibility 处理后，以 tee `VertexConsumer` 复制同一次 `renderToBuffer` 的输出。`PlayerModelGeometryAdapter` 不构造模型、不计算 limb pose、不再除以 16；只将最终 quad 三角化并转为 entity-local 坐标，`DynamicEntityGeometry` 消费数值快照。

`RayTracingVulkanPass` 为每个玩家独立持有可变 vertex buffer/BLAS。新模型 BUILD，顶点变化 UPDATE；可见部件数量变化只替换该玩家 BLAS。同一 submission 的 BLAS → TLAS → trace 使用现有 fence 完成后再允许下一帧覆写，scratch 按最大需求分配并串行复用。每个动态 slot 预留 512 个三角形材质，Section 索引不变，玩家法线随动画上传。

`LivingEntityGeometryAdapter` 在原版 `ModelFeatureRenderer` 完成动画、可见性和变换后 tee 可解析的 opaque/cutout 生物模型层，包含玩家 body、怪物主体、盔甲、披风和自定义 model layer；纹理从对应 `RenderType` 提取并写入共享的 64 项 living-entity descriptor array。第三人称手持物继续通过 `submitItem` 捕获并合并到玩家 BLAS；第一人称 body/item 有独立 capture family。translucent eyes/cape、名称牌和 translucent custom geometry 仍留在原生 feature/overlay 阶段。

### 原生方块实体几何（model 通道）

`BlockEntityRenderDispatcherMixin` 在 vanilla `BlockEntityRenderDispatcher.submit` 前后建立 owner 作用域，因此不需要逐 renderer 的 mixin：任何 renderer 提交的模型都能归属到自己的 `BlockPos`。`SubmitModelOwnerMixin` 把 owner 关联到 `ModelFeatureRenderer.Submit` 持有的 copied pose（延迟的 `Submit.state` 只有 renderer 自定义状态，例如箱子开盖的 `Float`）；`PlayerModelCaptureMixin` 继续在唯一安全的 `Model.renderToBuffer` seam tee 最终顶点。

`BlockEntityModelGeometryAdapter` 白名单只接受已审计的 vanilla model 类型：`ChestModel`、`ShulkerBoxRenderer$ShulkerBoxModel`、`BellModel`、`BookModel`、`CopperGolemStatueModel`、`BannerModel`/`BannerFlagModel`、`SkullModel`/`PiglinHeadModel`/`DragonHeadModel`。白名单按类名匹配，因为部分模型是私有嵌套类；未列入白名单的模型只记录一次日志并继续走原版路径。

捕获策略：非 outline、非 `sheetedDecalPose`、有可解析的动态纹理，且 pipeline 为 `BANNER_PATTERN` 或非 blending 的 entity pipeline。banner 是刻意保留的例外——vanilla 用 `bannerPattern`（`TRANSLUCENT` blend + `ALPHA_CUTOUT`）提交染色旗面和图案层，贴图为二值 alpha 像素画，按 alpha cutout 捕获即可得到正确的染色旗与图案。其余透明层、glint、未知层全部不捕获。

所有几何都来自 vanilla 已执行的最终 pose 与 `setupAnim`，不读取 baked block model，也不重做动画。单箱/左右双箱、朝向、开盖、shulker 开合、banner 朝向、skull/animation 等一律复用原版结果。每个捕获层把动态 texture descriptor slot 写入 triangle material 的 `optical.x`，UV 由 `PlayerModelGeometryAdapter.Capture` 按 `TextureAtlasSprite` 做同一次 atlas remap；tee 始终向原版 consumer 转发，未取消原版渲染。

每个捕获三角形同时按 sprite 与 atlas UV 采样 LabPBR companion 贴图，把 map index、roughness、metallic、emission、reflectivity 写入既有 seven-vec4 material ABI（`lighting.w` = map index）。无 companion 贴图时保持原默认值。

方块实体使用与 32-bit entity id 不相交的 block-entity identity namespace，并以 `BLOCK_ENTITY` family 安全复用动态 registry 的稳定 slot、每实例可变 BLAS/TLAS 和 512-triangle 材质段。

实体和方块实体现在都在 vanilla submit 阶段捕获最终模型顶点，写入动态 BLAS/TLAS，并复用静态场景的 LabPBR 材质 SSBO。RT display copy 后不再执行原版实体回放；无法捕获的对象会作为 RT admission failure 记录，避免出现“看似 RT、实际原生覆盖”的混合路径。

### `RayTracingVulkanPass`

Vulkan 资源所有者，负责：

- Vertex、Material、Camera、Output Buffer；
- BLAS/TLAS；
- Descriptor Set/Layout/Pool；
- RT Pipeline 和 Shader Modules；
- SBT；
- Command Buffer、Barrier、Trace、Copy；私有 encoder 只用于 pass 自有 AS/build 资源；
- 在 GameRenderer before-hand seam 追加显示拷贝时借用 Minecraft 的 device-owned shared frame encoder，绝不销毁或独立抢先提交该 encoder；
- 幂等资源释放。

静态场景的 BLAS/TLAS 只在场景资源创建后的首次 Dispatch 构建，摄像机更新不会重新构建。

### `RayTracingShaders`

集中保存 Ray Generation、Miss、Closest Hit、Any Hit 和 Shadow Hit Shader 源码。Shader 当前由 `Shaderc` 在运行时编译，不依赖资源目录中的 GLSL 文件。

### `RayTracingClientConfig` / `RayTracingSettingsScreen`

- `RayTracingClientConfig`：NeoForge Client Config，保存到 `config/rtest-client.toml`；`giBounces` 在 1--4 之间控制 GI continuation 数量（Primary ray 不计入；1=Debug、2=Performance、3=Balanced/Default、4=Quality），路径 throughput 不再乘任意 GI 强度标量；`fsrQuality` 选择 FSR3 质量档位（含 `quality_75` 的 75% 线性内部尺寸档），`fluidRtEnabled` 控制水/岩浆的流体感知捕获；NRD/ReBLUR 默认启用且源码默认 `nrdStrength=1.0`，Sundial 与 NRD 互斥；
- `RayTracingSettingsScreen`：F9 打开的独立设置界面。

## 3. 渲染流程

1. 玩家按 `F8`；
2. 下一次 `RenderLevelStageEvent.AfterOpaqueFeatures` 到达时捕获原生可见 Section 和动态生物快照；
3. 在 LevelRenderer 完成、第一人称手部开始前执行 RT/NRD/FSR3；
4. `RayTracingScene` 生成相对摄像机的三角形；
4. `RayTracingVulkanPass` 创建 Vulkan RT 资源；
5. `RtestFsr3` 创建输入、历史和显示图像，并选择质量档位；
6. 每帧更新 Camera UBO：摄像机、太阳角度、天空光/天空颜色、时间、雨天/雷暴/夜晚状态、RT 参数、上一帧相机和 jitter；太阳默认沿 Minecraft 的东升西落方向运动，并锁定到 `ClientLevel` 的 20 TPS 游戏时钟（不使用渲染帧率推进时间）；`sunAngleOffset` 调整时间相位，`sunAzimuthOffset` 绕竖直轴旋转轨迹（90° 可恢复北升南落）；
7. 首帧构建 BLAS/TLAS；
8. Ray Generation Shader 建立带 FSR jitter 的相机射线，并在同一个 shader 内执行可配置的 1--4 个 GI continuation（Primary 不计入）；
9. 每次命中只允许选择一个 continuation：diffuse、specular 或 transmission，随后最多再执行一次 `traceRayEXT`；不会并行发射反射、折射和 GI 分支；
10. Closest Hit Shader 只负责采样 Minecraft Block Atlas、Quad UV、Tint、法线、LabPBR 和材质参数，并通过 payload 返回命中位置、法线、颜色、光学参数及光照数据；不执行递归追踪；
11. Ray Generation Shader 对每个 bounce 单独执行一次直接太阳光 Shadow Ray；该射线使用 `TerminateOnFirstHit`，Shadow Any Hit 处理 Alpha Cutout/transmissive，Shadow Closest Hit 标记不透明遮挡；
12. Ray Generation Shader 根据 Fresnel、金属度和透明度选择唯一的 diffuse/specular/transmission continuation，并按选择概率更新 throughput；同时写入 FSR scene color、reversed depth、camera motion、reactive/transparency masks，以及 NRD 的 raw diffuse/specular、primary guides 和独立 AOV scratch；
13. Primary miss 在 Ray Generation Shader 中采样天空盒并结束当前路径；天空盒保留自身 RGB，只受天空亮度、昼夜和天气可见度影响；当前六面资源为 4096²；`nrd_motion.comp` 在 NRD 前将 raw signal 解调并生成 normal/roughness、view-Z、2.5D motion 和 FSR depth，`NrdDenoiser` 随后运行 NRD 4.17.3 `REBLUR_DIFFUSE_SPECULAR` compute dispatch 和 emission-safe HDR composite，最后 `RtestFsr3Upscaler` 运行 FSR 3.1.5 的 prepare/luma/reactivity/accumulate/RCAS/display compute passes；
14. FSR 显示图像的 copy/barrier 追加到 Minecraft 的 device-owned shared frame encoder；它位于 vanilla `LevelRenderer` frame graph 之后、手持物品和 UI 之前，再由 Minecraft 继续绘制原生 HUD/UI。RT 不销毁该共享 encoder。

### HDR 色彩契约

`HdrSupport` 按优先级协商三种输出：

| 模式 | swapchain color space | main 内容 | RT 3D 色域 |
| --- | --- | --- | --- |
| `HDR_BT2020_LINEAR` | `BT2020_LINEAR_EXT` | linear Rec.2020 | **保持广色域**（`fsr_display_rec2020.comp` 只做 NaN/负值清理） |
| `HDR_SCRGB` | `EXTENDED_SRGB_LINEAR_EXT` | linear BT.709 | 收窄到 BT.709（`fsr_display_hdr.comp` 做 Rec.2020→BT.709） |
| `SDR` | `SRGB_NONLINEAR_KHR` | sRGB 编码 | 不适用 |

两种 HDR 模式都以 1.0 = reference white。RT 场景内部始终是 scene-referred linear Rec.2020：在 Rec.2020 表面上直接输出即保留广色域；在 scRGB 表面上必须先转到 BT.709。

`config hdrWideGamutEnabled` 默认 **false**。它只在 compositor 真的对 `VK_COLOR_SPACE_BT2020_LINEAR_EXT` 做 linear→显示编码时才有意义。

实测：KWin 会把该 color space 当作 **sRGB 直通**，于是已经被 decode 成线性的原生内容不再被还原，整帧明显偏暗（天空 73,79,212 而非 133,151,243）。反推可验证底层原版帧是正确的白天场景，因此这是 compositor 行为，不是渲染 bug。在这个平台上只能用 scRGB：HDR 动态范围可用，但 3D 色域被收窄到 BT.709。

协商时会打印 surface 提供的全部 format/colorSpace 组合以及最终选择，便于判断为何落到 SDR。

`NativeColorManagement` 在编译期把原生 fragment shader 的 `fragColor` 赋值包上 sRGB→linear 解码：

- 覆盖**所有**产生显示颜色的 fragment shader（entity、item、particle、clouds、weather、text、GUI、screen effects、block entity 等），不再只是 GUI 白名单；
- 排除数据类 shader：`core/lightmap`、`core/rendertype_water_mask`、`core/animate_sprite_blit`、`core/animate_sprite_interpolate`、`core/rendertype_outline`；
- 只在 `HdrSupport.isActive()` 时生效，SDR 会话完全不变；
- 主色转换随模式变化：scRGB 下只做 sRGB→linear BT.709；Rec.2020 表面下再做 BT.709→Rec.2020（与 RT 材质路径同一矩阵），这样 SDR 内容保持原有外观，而 3D 场景仍在广色域中。

两个必须遵守的约束：

1. `Identifier.getPath()` 带目录（`core/gui`、`pipeline/gui`），**不能**用裸名比较；旧实现比较 `"gui"`，导致转换从未生效。
2. Minecraft 的 shader 缓存键是 `(shader id, stage, defines)`，不含 pipeline；转换结果必须只依赖 fragment shader 本身。`core/position_tex_color` 被 10 个不同 blend 的 pipeline 共用，因此不能按 pipeline 选择 premultiplied 形式。

## 4. Vulkan 数据接口

### Descriptor Bindings

| Binding | 类型 | 用途 |
|---:|---|---|
| `0` | Acceleration Structure | TLAS |
| `1` | Storage Buffer | RT 输出像素 |
| `2` | Uniform Buffer | Camera / Sun / Environment 参数 |
| `3` | Storage Buffer | 每三角形材质数据 |
| `4` | Combined Image Sampler | Minecraft Block Texture Atlas |
| `5` | Storage Buffer | Packed LabPBR normal/specular pixels and per-sprite metadata |
| `6` | Combined Image Sampler | RTest six-face Arknights skybox cubemap |
| `7` | Storage Image (`rgba16f`) | FSR 3 scene-linear input |
| `8` | Storage Image (`rg16f`) | FSR 3 motion vectors |
| `9` | Storage Image (`r32f`) | FSR 3 reversed depth |
| `10` | Storage Image (`rgba8`) | FSR 3 reactive mask |
| `11` | Storage Image (`rgba8`) | FSR 3 transparency composition mask |
| `12` | Storage Image (`rgba16f`) | NRD noisy diffuse + hit distance |
| `13` | Storage Image (`rgba16f`) | NRD noisy specular + hit distance |
| `14` | Storage Image (`A2B10G10R10`) | NRD normal/roughness guide |
| `15` | Storage Image (`r32f`) | NRD linear view-Z |
| `16` | Storage Image (`rgba16f`) | NRD 2.5D motion |
| `17` | Combined Image Sampler array (64) | Player skin textures |
| `18` | Combined Image Sampler | Item atlas for dynamic item geometry |
| `19` | Storage Buffer | Dynamic instance current/previous transforms and history flags |
| `20` | Storage Image (`rgba16f`) | Auxiliary primary albedo + roughness guide (not fed to NRD yet) |
| `21` | Storage Image (`rgba16f`) | Deterministic direct diffuse AOV |
| `22` | Storage Image (`rgba16f`) | Indirect diffuse AOV (including secondary-surface and sky contribution) |
| `23` | Storage Image (`rgba16f`) | Emission AOV |
| `24` | Storage Image (`rgba32f`) | Primary hit position scratch for NRD motion preparation |
| `25` | Storage Image (`rgba16f`) | Specular material scratch for NRD motion preparation |

### Camera UBO

每个字段为 `vec4`，逻辑 payload 大小为 `272` bytes；Vulkan buffer/range 按当前实现分配 `288` bytes：

| Offset | 字段 | 内容 |
|---:|---|---|
| `0` | `origin` | 相对场景原点的摄像机位置 |
| `16` | `forward` | 摄像机前方向 |
| `32` | `right` | 摄像机右方向 |
| `48` | `up` | 摄像机上方向 |
| `64` | `parameters` | `x=tanHalfFov`, `y=aspect`, `z=continuation strength`, `w=GI continuation count (Primary excluded)` |
| `80` | `sun` | `xyz=太阳方向（默认东升西落，`sunAzimuthOffset` 可绕 Y 轴旋转）`, `w=最大 RT 距离` |
| `96` | `settings` | `x=太阳强度`, `y=阴影强度`, `z=环境光`, `w=高光强度` |
| `112` | `environment` | `x=天空光因子`, `yzw=Minecraft 天空颜色` |
| `128` | `random` | `x=frame counter bits`, `y=固定 seed`，用于 pixel/frame/bounce PCG |
| `144` | `previousOrigin` | 上一帧相对场景原点的摄像机位置 |
| `160` | `previousForward` | 上一帧前方向 |
| `176` | `previousRight` | 上一帧右方向 |
| `192` | `previousUp` | 上一帧上方向 |
| `208` | `previousParameters` | 上一帧 `tanHalfFov` 和 `aspect` |
| `224` | `jitter` | 当前 FSR Halton jitter |
| `240` | `environmentState` | `x=时间(0--1)`, `y=雨强`, `z=雷暴强度`, `w=夜晚因子` |
| `256` | `dynamicParameters` | `x=dynamic material start`, `y=slot stride`, `z=slot capacity`, `w=signal-split AOV writes enabled only when NRD is scheduled` |

修改布局时，必须同步修改：

1. Java `NativeBuffer` 大小；
2. Descriptor Buffer Range；
3. `updateCamera` 写入偏移；
4. Raygen/Closest Hit Shader 的 UBO 声明。

### Material Buffer

每个三角形占 `7 × vec4 = 28` 个 Float：

| Entry | 内容 |
|---:|---|
| `0` | Tint / Albedo |
| `1` | 面法线 |
| `2` | 第一个 UV、第二个 UV |
| `3` | 第三个 UV、Alpha Cutout 标志、是否使用纹理 |
| `4` | Reserved RT material metadata（前三项保持中性）、PBR map index |
| `5` | Roughness、Metallic、Emission、编码后的 Reflectivity/Translucent 标志（不透明为 `reflectivity`，透明为 `1 + reflectivity`） |
| `6` | Absorption RGB、IOR |

修改材质布局时必须同步修改 `RayTracingScene` 和 Closest/Any Hit Shader 中的 stride。

## 5. 添加一个新的 RT 参数

推荐流程：

1. 在 `RayTracingClientConfig` 增加 `DoubleValue` 或 `BooleanValue`；
2. 在 `RayTracingSettingsScreen` 增加 Slider/Cycle Button；
3. 在 `updateCamera` 写入 UBO；
4. 在 Shader 使用对应 UBO 字段；
5. 更新 `README.md` 和本文档的数据布局表；
6. 使用 F9 修改参数并确认运行时无需重建 Pipeline；`giBounces` 可直接修改客户端 TOML 后重启世界。

如果参数改变了 Descriptor、Shader Interface 或 Buffer 大小，则必须重新创建 `RayTracingVulkanPass`，不能只更新 UBO。

## 6. 构建与安装

使用 Java 25：

```bash
export JAVA_HOME=/home/aruku/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build --no-configuration-cache
```

安装到开发实例：

```bash
./gradlew installToInstance \
  -Pinstance_directory=/home/aruku/.minecraft/versions/RTest/mods \
  --no-configuration-cache
```

`test` 任务会执行 JavaExec 契约测试，包含真实 `PlayerModel` 顶点/动画与 Vulkan 命令结构测试；`vulkanResourceLifecycleTest` 额外检查共享 frame encoder 与 pass 私有 encoder 的所有权边界。这些测试不执行 GPU 光追。每次 Vulkan Shader 或资源生命周期修改后至少运行一次完整 `build`。当前 RT 提交使用 submission-scoped `GpuFence`，`graphicsQueue().waitIdle()` 仅保留在最终资源销毁路径。

## 7. 手动验证清单

1. 启动 Vulkan 后端的 Minecraft；
2. 进入 3D 世界；
3. 按 `F8`，确认 RT 输出出现；
4. 按 `F9`，修改太阳强度、环境光、阴影强度、太阳时刻偏移和太阳轨迹方位角；
5. 调节 `Sun angle offset`，确认光照和阴影方向变化；
6. 首次按 F8，确认 Section 捕获按帧分批进行，而不是卡住单个渲染帧；
7. 等待捕获完成，确认 BLAS/TLAS 按批次构建，期间继续显示上一张已完成的 RT 结果，不出现空白或无意切回原版画面；
8. 修改方块或加载新 Chunk，确认增量场景更新不会重建 RT Pipeline；
9. 修改 Minecraft 原生视距，确认下一轮增量捕获使用新的 Section 集合；
10. 旋转摄像机，确认无需重新捕获几何或重建 BLAS；
11. 打开背包或暂停界面，确认原生 UI 位于 RT 画面之上；
12. 关闭 F8，确认 Vulkan 资源释放且再次按 F8 可以重新启动。
