# Distant Horizons 官方 API 兼容调查（2026-10-10）

> **文档状态：研究/审计快照** · 整理日期：2026-10-11。
> 来源版本与本地行号只对应记录时点；建议不是自动生效的待办，实施情况需对照源码和后续记录。 [分类索引](README.md)

## 调查边界

只读分析官方源码，未运行 DH、未修改 RT 渲染代码、未安装 DH。固定上游：

- 主仓库 `https://gitlab.com/distant-horizons-team/distant-horizons`，main `f567c3476af6e65eb19854b2942d6fa08c1bcb91`（2026-10-04），本地 `/tmp/rtest-distant-horizons`。
- 主仓库 `.gitmodules` 指定的官方 core 子模块 `https://gitlab.com/jeseibel/distant-horizons-core`，锁定 `f5788b79f0cc4d751a6c1c7d759ac926df917206`，本地 `/tmp/rtest-distant-horizons/coreSubProjects`。
- 当前 main 自报 DH `3.3.5-dev`、API `7.3.0`；这是开发分支调查，不能把接口存在性等同于已发布版具备。来源：[版本定义](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/gradle.properties)。

## 已证实：目标平台与渲染后端

1. `versionProperties/26.2.0.properties` 明确 Minecraft `26.2`、Java 25、Fabric/NeoForge 构建（NeoForge 配置 `1-beta`）。有此源码构建目标，不代表已经验证 RT 与 DH 组合启动。来源：[26.2 配置](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/versionProperties/26.2.0.properties)。
2. 默认渲染引擎在 1.21.11 及之前是 OPEN_GL，之后是 BLAZE_3D。26.2 的 Blaze 实现从 Minecraft `RenderSystem.getDevice().getDeviceInfo().backendName()` 判别 Vulkan 或 OpenGL，并跟随 Minecraft 后端。因此“DH 固定 OpenGL，所以 Vulkan 无法兼容”不符合当前源码。来源：[VersionConstants](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/wrappers/VersionConstants.java#L114)、[MinecraftRenderWrapper](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper.java#L559)、[BlazeDhRenderApiDefinition](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhRenderApiDefinition.java#L71)。
3. 26.2 Blaze 路径使用 reverse Z、0..1 深度。接入自有渲染仍要核对投影/深度和执行时序。来源：[深度约定](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhRenderApiDefinition.java#L35)。

## 已证实：公开地形读取 API

`DhApi.Delayed` 提供 terrainRepo 等延迟初始化对象。应在 `DhApiAfterDhInitEvent` 后使用，借助 level/world load/unload 管理世界世代；可通过 getApiMajorVersion/MinorVersion 与 getModVersion 做明确的版本门控。API 包声明是公开安全接口，core 内部类不享有该承诺。来源：[DhApi](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/DhApi.java)、[初始化事件](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiAfterDhInitEvent.java)、[公开包说明](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/package-info.java)。

公开 repo 能读单点、整列、chunk、region、指定区域尺度，并提供 CPU raycast。API 数据点包含上下 Y、blockState wrapper、biome wrapper、block/sky light；不包含最终 LOD mesh、面颜色、纹理 UV、PBR 或自发光材质。block wrapper 能识别 air/solid/liquid/opacity/materialId，可解包 Minecraft 对象但将引入 Minecraft 版本依赖。来源：[Repo 接口](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/data/IDhApiTerrainDataRepo.java)、[数据点](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/objects/data/DhApiTerrainDataPoint.java)、[Block wrapper](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/block/IDhApiBlockStateWrapper.java)。

### 两个容易误用的实现细节

- `getAllTerrainDataAtDetailLevelAndPos(detailLevel,...)` 的 detailLevel 定义**读取区域宽度**，当前实现分配 `2^detailLevel × 2^detailLevel` 数组，并逐个 BLOCK_DETAIL_LEVEL=0 的列读取。它不是返回渲染器当前选择的粗 LOD：detail 9 会读取 512×512 个块列。若直接把它当远景粗网格接口，会引入明显分配和查询开销。来源：[Repo 实现 L143-L181](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/api/external/methods/data/DhApiTerrainDataRepo.java#L143)。
- 数据点类的 bottomY 注释称相对最小高度，但实际导出函数先执行 `bottomY = storedBottom + minLevelHeight`，topY=bottomY+height。当前返回值已经是绝对世界 Y，不能再加一次 minHeight。来源：[导出实现](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/util/DhApiTerrainDataPointUtil.java)。

## 已证实：阻塞、缓存与事件约束

- cache miss 调用 `level.getFullDataProvider().getAsync(sectionPos).get()`，会阻塞调用线程。当前实现拒绝 null cache 和自定义 cache。API5+ `createSoftCache()` 可创建官方 SoftReference cache。不得在 RT render thread 每帧读取；应有界后台队列、去重、世代校验、缓存失效。来源：[Repo 实现 L210-L272](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/api/external/methods/data/DhApiTerrainDataRepo.java#L210)、[Repo 接口缓存说明](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/data/IDhApiTerrainDataRepo.java)。
- cache map 的 add/get/clear 用 ReentrantLock，但 get 后离锁，clear 会 close 数据源。不能据此断言读数据源与clear并发安全；适配器应自行串行化读取和清理，或每worker独立cache，切世界等在任务停止后close。公开 cache 不提供单项移除，因此细粒度失效需要额外管理。来源：[官方 cache 实现](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/api/external/methods/data/DhApiTerrainDataCache.java)、[cache 接口](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/data/IDhApiTerrainDataCache.java)。
- `DhApiChunkModifiedEvent` 有level+chunk坐标，在full data更新后触发，render data仍可能滞后；可将其视为适配器的dirty提示，但不应同步读DB/构建BLAS。实际触发位于数据库更新回调，不能假设主线程。来源：[事件](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiChunkModifiedEvent.java)、[触发实现](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/level/AbstractDhLevel.java#L225)。
- `DhApiChunkProcessingEvent` 是每个block/biome变化的高频处理钩子，明确可多线程并发，参数可复用，不适合作“每节点mesh准备完成”事件。来源：[事件线程和性能说明](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiChunkProcessingEvent.java)。

## 已证实：公开渲染接口不能直接读 CPU mesh

API7.3.0（接口注释 2026-10-02）加入 TerrainRenderer、TerrainBufferContainer、VertexBufferWrapper。容器可拿 opaque/transparent wrappers，VertexBufferWrapper只有uploadVertexBuffer、uploadIndexBuffer、getVertexCount、close，没有 CPU ByteBuffer readback。`DhApiBeforeBufferRenderEvent`仅给modelPos与渲染参数，无法取得mesh。因而“公开API直接拿已构建的CPU顶点”目前未成立。来源：[TerrainBufferContainer](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/objects/IDhApiTerrainBufferContainer.java)、[VertexBufferWrapper](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/objects/IDhApiVertexBufferWrapper.java)、[BeforeBufferRenderEvent](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeBufferRenderEvent.java)。

可通过可取消的 `DhApiBeforeRenderEvent` 阻止 DH 自己画当前帧，避免同一远景双画；但主流程取消时会跳过 LodRenderer.render，其中可能包含refresh/build/upload，因此CPU mesh复用方案不能简单取消整个render，必须仅替换实际绘制阶段。来源：[BeforeRenderEvent](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeRenderEvent.java)。

## 新 API7.3.0 的重要替代路线：自定义渲染定义捕获上传

公开 `DhApi.Delayed.renderProxy.setRenderDefinitionOverrideAsync(renderDef, displayName)` 允许设置自定义 `AbstractDhApiRenderDefinition`，其factory `createVboWrapper(name)` 可以返回自己的wrapper，从 `uploadVertexBuffer(ByteBuffer,int)` **在上传时**取得CPU字节；自有TerrainRenderer收到已经frustum cull的容器列表，顺序由DH确定。这是绕过DB全量重建、复用DH现成粗网格的可行接口基础。来源：[RenderProxy接口](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderProxy.java#L123)、[RenderDefinition](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/AbstractDhApiRenderDefinition.java)、[TerrainRenderer](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/IDhApiTerrainRenderer.java)、[Factory调用](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/dataObjects/render/bufferBuilding/LodBufferContainer.java#L295)。

限制如下：

- 必须使用setRenderDefinitionOverrideAsync，不能直接DhApi.overrides.bind；官方注释指出旧renderdata和新renderer格式不匹配会崩溃。设置实现先后清空renderdata，并验证renderer API与Minecraft一致。来源：[定义说明](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/AbstractDhApiRenderDefinition.java#L14)、[切换实现](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/wrappers/DependencySetup.java#L193)。
- 自定义wrapper无法无条件委托给内置BlazeTerrainRenderer，因为后者强制cast到内部 `BlazeVertexBufferWrapper`；可继承内部类拦截上传，但失去纯公开API边界。源码还需要根据index共享策略与OS/backend适配。来源：[Blaze绘制强制cast](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java#L336)、[Blaze wrapper](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/wrappers/buffer/BlazeVertexBufferWrapper.java)。
- 公开TerrainBufferContainer缺少origin/detail等元数据；UniformBufferWrapper也仅有tryUpload(container)和close，不能直接获取定位。需要内部container/uniform桥接或请求上游补接口。BeforeBufferRenderEvent的modelPos由内置绘制中触发，自定义renderer不画内置pass时不会自然获得这个事件。来源：[uniform接口](https://gitlab.com/jeseibel/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/api/src/main/java/com/seibel/distanthorizons/api/interfaces/render/renderDef/objects/IDhApiTerrainContainerUniformBufferWrapper.java)、[事件触发](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/f567c3476af6e65eb19854b2942d6fa08c1bcb91/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java#L352)。
- 上传ByteBuffer需要在回调内复制或同步解码为自有不可变快照，不能持有DH池化buffer异步使用；close/重新上传应移除旧RT资源。具体布局、局部坐标、方向烘焙明暗、block/sky light都需要独立解码核对。不能把已经烘焙方向明暗的颜色直接当RT albedo。

因此目前最有潜力的性能路线是 **API7.3.0自定义renderDefinition + 有限版本内部元数据桥**，而非公开DB遍历。该判断仍需固定JAR的组合运行验证。

## 判断与待确认

**基于源码的判断：可以做数据层兼容。** 公开数据源路线拥有较清楚的稳定边界，但需要 RT 自己合并/降采样、生成mesh和材质，且不能同步批量读远景区域。若目的是复用 DH 已完成的粗网格和选择结果，可优先研究上面的 API7.3.0 上传拦截路线；目前仍需固定DH版本的内部定位/格式桥，或向上游请求补充公开元数据接口。

以下尚未证实：安装的具体DH发布版及API版本；26.2 NeoForge构建在用户驱动上的实际启动；关闭DH绘制后的完整缓存/后台生成生命周期；任意远距离缺失数据是否会按目标配置主动生成；代理网格透明/水体/发光/资源包材质保真；组合使用CPU时间、GPU显存/BLAS规模和卡顿改善。需要用固定JAR与用户场景另行验证，不能从源码存在性给出性能提升保证。
