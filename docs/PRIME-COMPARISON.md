# Prime 与 RTest 方案对比

> 调研基线：Prime 仓库 `c9ac55b9546abbb54416c6fe4eb4d9db603f659e`，2026-09-14；RTest 为当前工作区源码和 `docs/instance-validation.md` 的实机记录。
>
> 本文只借鉴架构和契约，不复制 Prime 源码。Prime README 明确说明项目仍处于早期开发阶段，因此“文档目标”“源码已实现”和“同硬件实机验证”分开记录。本文是对比与可选设计参考，不是 RTest 当前开发计划。

## 结论

两者不是简单的“谁更快”关系：

- **RTest 当前更像一个已在 RX 7800 XT/RADV 上验证的窄纵向原型**：Minecraft 原生 Section 捕获、流体特殊路径、摄像机窗口增删、FSR3/NRD、玩家/物品动态模型和上一帧画面复用已经串通；后续仍应以实机稳定性和可归因数据为准。
- **Prime 的强项是把场景流送、纯 CPU 翻译、GPU 发布和资源退休做成完整系统**：4×4×4 cluster 原子替换、worker 回压、generation/cancellation、资源 epoch、prepare/accept、延迟退休、静态 BLAS compaction 和大量契约测试共同解决了 RTest 当前的长帧与生命周期风险。
- **当前最值得参考的不是 Prime 的所有效果，而是它的所有权和异步发布边界**。如果继续优化 RTest，应先用 profiling 区分全量 CPU merge、同步 GPU fence 和动态 PBR 热路径，再决定是否引入 cluster；本文不规定当前实现顺序。

没有相同场景、视角、内部/显示分辨率、GPU 和驱动的对照基准，不能据此声称 Prime 的最终 GPU 性能优于 RTest。

## 方案定位矩阵

| 维度 | RTest 当前事实 | Prime 源码/文档事实 | 判断 |
|---|---|---|---|
| 场景成员 | 读取有效视距内已加载、非空的原生 Section，不依赖 `visibleSections()`；相机跨 chunk 后计算 added/removed Section delta | 不依赖原版可见 Section、遮挡图或 raster 编译队列；以对齐的 4×4×4 Section cluster 为原子单位 | RTest 的场景选择方向正确且更新更细；Prime 的 resident 组织和流送边界更完整 |
| 捕获预算 | `SECTIONS_PER_FRAME=2`，捕获仍发生在 render 回调；过渡期不会一次吃掉全部帧 | render thread 只捕获不可变输入，worker 编译；有 worker 上限、完成队列上限、优先级和 staging 上传预算 | RTest 的“分帧”保护了正确性但仍阻塞主渲染线程；Prime 更接近可持续流送 |
| 脏更新 | `markSectionDirty()` 扩展到边界邻居；chunk load/unload 也处理相邻 chunk；pending 集合无明确容量上限 | `BoundedDirtyClusters` 合并跨线程通知，超过上限升级为 full invalidation；在 cluster 单位去重 | RTest 的邻域语义可用，但应补有界队列和显式 overflow 策略 |
| CPU 几何 | 优先复用已编译 MeshData；流体 Section 走 CPU；未命中时逐 block/model 遍历 | `VanillaClusterCompiler` 先捕获 6×6×6 halo，再由纯 `ClusterSceneTranslator` 生成 4×4×4 cluster payload | RTest 初始接入成本低；Prime 的输入快照、边界解析和翻译可测试性更好 |
| CPU 合并 | `SceneGeometry.replaceSections()` 仍需重建整个 Section map 并 flatten resident vertices/materials，但现在已精确计算总长度、一次分配并逐 Section copy | cluster 结果不可变，单 cluster 原子替换，已发布 resident 不被修改 | RTest 已去掉 merge 阶段的反复扩容；全量 resident flatten 仍是发布长帧的首要结构性嫌疑之一 |
| 几何/BLAS | 一个非空 Section 一个 BLAS；`BlasCache` 按坐标、顶点 fingerprint 和三角形数复用 | 普通 cluster 一个 BLAS；可共享不可变 voxel BLAS；静态 `BUILD_ONLY` 和动态 `MOTION` 生命周期分开 | Section 粒度更新范围小但 TLAS 条目多；Prime 条目少且有明确共享规则，但单次脏更新可能重建 64 Sections |
| TLAS 发布 | 一个 TLAS；几何发布时原地写入容量足够的 instance/material buffer，Section 数变化时新建 TLAS；变更后分批 BLAS 构建 | 3 个 TLAS slot，候选 TLAS 取得空闲 slot 后 prepare/build/publish；旧 TLAS 按完成时间线退休 | Prime 更适合 GPU 与 CPU 重叠；RTest 仍是同步 correctness baseline |
| 资源退休 | 当前每次 dispatch 等待自己的 `GpuFence`，所以 `updateGeometry()` 直接关闭旧资源暂时安全；`DynamicVulkanResourceSlice` 目前只是规则 seam，尚未接入主 pass | `TerrainUpdateTransaction` 管理未发布资源；`VulkanContext.defer()` 在真实提交完成后销毁；失败区分提交前/后 | RTest 不是“没有同步”，而是把安全性换成 render-thread stall；未来异步化前不能继续直接 `close()` |
| staging/上传 | 主要使用 host-visible `NativeBuffer`，材质和 instance 常有整段映射写入 | `StagingArena` 固定页、可复用、按容量准入；静态上传每帧 8 MiB/4 cluster，超大原子项可单独推进 | Prime 的容量预算是内存和回压边界，不等同于任意提高每帧工作量 |
| 材质记录 | 每三角形 28 个 `float`（112 B）；MeshData 快路径无法带 sprite/material ID，使用保守默认 PBR；LabPBR 按 sprite 首次采样时懒加载 | `TextureId`/`MaterialId` 稳定；静态 primitive 32 B，材质 core 集中存储，关系 sidecar 处理 overlay/bilateral/boundary | RTest 的显存、带宽和材质准确性都明显落后；但这是较大的 ABI/Shader 迁移，不宜和阶段 0 混做 |
| PBR 资源 | `RayTracingPbrMaterials.sample()` 可能在 Section capture 中执行 resource lookup 和 PNG 解码；map 增长后 `synchronizePbrMaterials()` 整体上传新 SSBO | 资源 generation 在 reload 边界建立 catalog/page；正常帧只处理实际动画变化，worker 持有 generation lease | RTest 已记录约 214--216 个集合、约 106--110 ms 的动态 PBR 同步，应该优先移出正常帧 |
| 动态对象 | 当前实际路径主要是玩家模型和物品；固定 64 slot；同一提交内 BLAS UPDATE → TLAS UPDATE → trace | 动态场景以不可变 `DynamicSceneFrame` 统一实体、方块实体、粒子、物品等，并有稳定 motion key、dynamic resident 和 unique fallback | RTest 的 fixed slot 是正确方向，但覆盖面和异步生命周期还不完整 |
| 追踪算法 | 当前是递归深度 1 的 RayGen 路径循环、直接太阳阴影、FSR3/NRD 原型；当前仍以稳定性为先 | Prime 有完整 wavefront/continuation、OpenPBR compact material、透明关系和更完整的 reconstruction 契约 | Prime 在渲染算法和 ABI 成熟度上领先；RTest 不应在场景发布未稳定前同时追赶全部算法 |
| 验证体系 | 有 Java/ABI/资源契约测试和用户实机日志；已验证视距切换、加载闪烁修复和部分动态路径，但 GPU 时间拆分仍在补 | JUnit 状态/翻译/生命周期、真实 shader property、native ABI、GPU/抓帧和基准分层；Prime 自身仍声明早期开发 | RTest 需要吸收验证分层，不应把编译通过或单次平均 FPS 当作完成 |

## RTest 已经做对的地方

1. **保留了正确的非屏幕场景选择方向。** `RayTracingScene.SceneGeometry.loadedSectionOrigins()` 使用已加载的 `FULL` chunk 和非空 Section，而不是直接借用 raster 可见列表。这与 Prime 关于“光追不能依赖栅格可见性”的判断一致。
2. **Section window delta 已经实测通过。** `RayTracingProbe.queueCameraWindowUpdate()` 计算当前 resident 与目标窗口的差集；新增 Section 分帧捕获，离开窗口只增量移除。当前记录显示 `5 → 8`、`8 → 5` 可完成捕获，跨 chunk 加载也不再出现上一帧/原版画面闪烁。
3. **partial capture 已加入 generation/window acceptance。** 如果 dirty 或 camera window 在 partial session 飞行期间变化，旧结果不会推进已发布 window，而是保留 pending 更新等待下一代处理；full capture 仍沿用现有先发布、后重捕获语义。
4. **保守地保留了 `SECTIONS_PER_FRAME=2`。** `4` 虽能缩短捕获时长，却制造约 `71--242 ms` 长帧；没有为了“更快完成”而破坏交互帧时间，这是正确的性能决策。
5. **捕获快路径有现实价值。** 复制 vanilla 已编译 MeshData 可以避免第二次 block/model 遍历；流体 Section 禁用这个快路径，保留了液面高度、邻域和材质语义。这个折中适合当前 AMD 实机原型。
6. **本轮先降低 merge 分配噪声，而没有伪装成真正增量发布。** `replaceSections()` 现在一次分配最终数组并逐 Section copy；它减少了 growing accumulator 的中间数组，但未改变当前 Shader ABI 所要求的 resident 全量 flatten。
7. **动态路径的基本依赖顺序是正确的。** 当前同一提交可执行动态 BLAS UPDATE、TLAS UPDATE，再进入 trace，并用上一帧 transform 生成 motion metadata；这比把动态对象永久烘焙进静态场景更容易继续演进。
8. **已用上一张 FSR display image 解决 AS 增量构建时闪烁。** 这保留了用户可见的最后一个完整 RT 结果，而不是在几何未完成时落回原版画面。

这些是 RTest 当前的实际优势，不应因为 Prime 的架构更完整而回滚。

## RTest 当前最关键的不足

### 1. “Section 增量”在发布端仍然变成了“全场景重排”

捕获只处理少量 Section，但 `replaceSections()` 会重新遍历所有 resident Section 并复制完整 vertices/materials；随后 `updateGeometry()` 至少要重新写 instance buffer 和完整 material payload，并重新计算/发布一套场景状态。这样会造成：

```text
小范围 Section dirty
  → 小范围 CPU capture
  → 全量 CPU flatten
  → 全量 instance/material rewrite
  → 可能的 TLAS/BLAS 发布
```

因此不能只用“每帧捕获 2 个 Section”解释长帧。当前实测的最高约 `797 ms` 长帧和 30 次 partial capture，正说明发布路径仍未真正增量化。

**建议：** 先引入不可变 `SceneSnapshot`/`SectionRecord` 表，保留每个 Section 的 geometry、triangle base、BLAS key 和 material range；只为变化项建立候选记录。若 Shader 仍要求连续 material buffer，则把这个限制明确成下一步 ABI 任务，而不是继续用全量数组复制掩盖它。

### 2. fence 是安全基线，不是最终调度

`RayTracingVulkanPass.dispatch()` 提交后立即 `awaitCompletion()`，因此 GPU 还没完成时 CPU 不能准备下一帧；`buildAccelerationStructuresIncrementally()` 也对每批 BLAS 构建同步等待。当前这样做可以解释为什么旧资源可以直接关闭，但它会把 GPU build、RT、FSR 和读取 center pixel 的时间全部压到 render thread。

`graphicsQueue().waitIdle()` 只应保留在 `close()`；不过“没有每帧 waitIdle”并不等于已经异步。Prime 的正确借鉴点是：候选 scene、TLAS、staging 和旧资源分别有提交前、host accept 后、queue complete 后的状态。

**建议：** 先建立 2--3 个 frame/scene slot 和 `submitted → completed` 的非阻塞回收，再移除同步等待。迁移前不得把 `BlasCache.acquire()`、`trim()`、`updateGeometry()` 和 dynamic cache 中的直接 `close()` 改成假异步；它们都必须进入 fence/timeline 保护的 retire 队列。

### 3. PBR 资源仍在错误的时间域准备

RTest 的 PBR sampler 首次遇到 sprite 时直接查 ResourceManager 并读取 PNG；之后 map 数量变化会重新 pack 全部 map 像素并替换 SSBO。这正好对应日志中的动态 PBR 同步长帧。

**建议：**

- 在 smoke test 开始或资源 reload apply 阶段预解析 sprite 身份和 PBR map；
- 用稳定的 `TextureId`/generation，而不是依赖本次遍历顺序；
- 资源未准备好时阻止该 generation publish，或明确报告 fallback，不在 trace 前临时解码；
- 普通帧只上传实际变化的动画矩形/页，静态 catalog 不进入 `dispatch()` 热路径。

Prime 目前仍保留 Minecraft atlas 作为 albedo external backing 的迁移基线，所以不应把它的材质链描述成完全没有过渡成本；但它已经把“资源准备”和“正常帧消费”分开。

### 4. 材质数据重复且无法充分表达源身份

RTest 的 112 B/triangle 记录复制了颜色、法线、UV、光照、roughness、metallic、optical 等数据。MeshData 快路径又明确没有 sprite/material ID，只能采用保守默认值。这会同时增加：CPU flatten 复制量、host-visible 映射量、GPU 带宽、材质 SSBO 显存和 PBR 误差。

Prime 的 `MaterialId`/32 B primitive/固定 material core 方案值得作为长期目标，但需要先冻结 RTest 的表面关系和 Shader ABI。不要在当前阶段为了压缩记录而重新引入不稳定的洞穴剔除、屏幕裁剪或“看不见就不捕获”的语义。

### 5. 动态对象还不是统一 scene domain

RTest 目前对玩家和物品做了实际适配，但其他实体主要计为 fallback；方块实体、粒子、天气等还没有与静态地形共享一个明确的动态 resident/生命周期。动态 cache 中的 BLAS 被移除时也沿用立即释放模型，这在当前 fence 串行条件下可行，在异步 ring 中不成立。

Prime 的可借鉴顺序是：先定义 `DynamicSceneFrame`、稳定 identity、generation、current/previous transform 和 topology/material change，再增加对象种类；不要先增加更多动态模型而没有统一的 slot reuse、history reset 和 retire 规则。

## 不建议直接照搬 Prime 的部分

### 不要现在就把 Section 改成 4×4×4 cluster

Prime 的 cluster 有三个明显收益：

- resident 的 TLAS instance 数量显著减少；
- 一个 cluster 内可以统一做面关系、材质 remap、light tree 和 BLAS 发布；
- worker 任务和上传事务数量更容易有界。

但它也有确定代价：一个 Section 的修改会让包含它的 64-Section 原子 cluster 重新捕获/翻译；Prime 自己的 `TerrainScene` 对静态内容变化仍会建立 replacement TLAS。若继续优化 RTest，应优先区分“局部捕获后全量 flatten”和“发布同步”的实际成本，而不是单纯减少逻辑 key 数；可以据此做独立 A/B：

```text
A: Section resident + 真正增量的 SectionRecord/instance/material 发布
B: 4×4×4 cluster resident + worker/transaction 发布
```

比较 CPU merge、BLAS/TLAS GPU 时间、峰值显存、加载完成时间、单 Section 修改长帧 P95/P99，而不是只看稳定 RT ms。

### 不要把 Prime 文档目标当成已验证性能

Prime README 自己标注早期开发；其文档中的 `8 MiB/4 cluster`、GPU timer、完整 wavefront 和测试门禁是设计/实现证据，不是 RTest 当前 RX 7800 XT/RADV 环境下的同场景结果。Prime 的硬件支持说明也以 NVIDIA RTX + DLSS RR 为首要路径，RTest 的 AMD/Linux 验证优势不能被抹掉。

### 不要先复制完整 OpenPBR/wavefront

RTest 当前阶段的主要问题是场景发布和帧时间，不是缺少更多 BSDF 分支。先冻结：

1. scene revision 与 history reset；
2. static/dynamic BLAS lifetime；
3. material/texture identity；
4. non-blocking submission completion；
5. GPU phase timing。

再迁移 wavefront、透明 relation 和完整材质闭包，否则新增算法会把场景 bug、重建 bug 和 GPU 成本混在一起。

## 可选迁移方向（非当前开发计划）

### 方向 A：性能与发布诊断

1. **把现有 instrumentation 升级为可归因数据**：分别记录 CPU capture、CPU merge、geometry publish、PBR pack/upload、BLAS build、TLAS build/update、RT/FSR；GPU 时间使用不阻塞的 timestamp/query，不能用当前 fence 等待时间冒充某一阶段。
2. **继续消除 `replaceSections()` 的全量 flatten**：本轮只去除了 growing accumulator 的中间复制；下一步才是让 Section map、triangle base 和 unchanged payload 真正可复用，先保留 Section 粒度，不改场景语义。
3. **把 PBR 初次发现移出 dispatch**：capture session 只消费已准备的 material snapshot；为 resource reload/缺失资源增加显式状态。
4. **补齐空场景、原生 unload、连续 window move 和资源 reload 回归测试**；尤其检查所有 Section 被移除时不会访问 `sectionBlas.get(0)`，以及 stale capture 不会重新引入已移除 Section。

### 方向 B：异步场景基础

5. **建立 `SceneUpdateTransaction`**：候选 buffer/TLAS/BLAS 先 prepare，提交被 host 接受后 publish，旧资源进入 submission-scoped retire；把现有 `DynamicVulkanResourceSlice` 从抽象规则提升为主 pass 的实际 owner。
6. **引入有限 worker pipeline**：render thread 捕获不可变 Minecraft 快照，worker 只处理快照；增加 generation、cancellation、bounded completed queue 和 priority。不要把可变 `ClientLevel`、`TextureAtlasSprite` 或 GPU handle 直接交给 worker。
7. **在发布路径和 PBR 长帧拆分清楚后，再比较 100/75/66.7/50% internal resolution**。降低 internal resolution 可能只降低 RT 时间，不能解决加载长帧。

### 方向 C：更长期的结构演进

8. **评估 4×4×4 cluster**，以实测更新范围和 TLAS/BLAS 时间决定，而不是预设一定更快。
9. **迁移 compact material IR**：稳定 TextureId/MaterialId、sprite-local UV、共享 material core、关系 sidecar；同步补齐 ABI 和 GPU property tests。
10. **扩展统一动态 resident**：方块实体、粒子、透明物体、天气和其他实体逐类接入，每一类都有 topology、motion、reactive/transparency 和 fallback 契约。
11. **最后再扩大 wavefront、透明路径、NRD AOV 合成和 Frame Generation**，保持每次变更可独立验收。

## 最小验收表

| 迁移项 | 必须证明 |
|---|---|
| SceneRecord 增量发布 | 单 Section 修改不复制整个 resident payload；无修改帧 merge/publish 接近 0 |
| 异步 worker | stale generation 不发布；取消可回收；完成队列有界；Minecraft/GPU 对象不跨线程逃逸 |
| transaction/retire | submit 前失败立即清理；submit 后失败延迟清理；旧 TLAS/BLAS/buffer 不早于最后 GPU 使用释放 |
| PBR generation | 首次资源准备不出现在普通 RT dispatch；ID 不因遍历顺序变化；缺失与未准备状态可区分 |
| dynamic ring | spawn/despawn/teleport/slot reuse 正确 reset history；上一帧资源仍在用时不被覆盖 |
| cluster A/B | 报告 CPU、GPU、P95/P99、峰值显存和视觉结果；不以平均 FPS 单项决定 |

## 参考来源

### Prime（primary source）

- 仓库与固定提交：<https://github.com/bWFuanVzYWth/prime/tree/c9ac55b9546abbb54416c6fe4eb4d9db603f659e>
- [README.md](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/README.md)：硬件定位、早期开发声明、已知限制和许可证。
- [docs/区块簇场景翻译架构.md](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/docs/%E5%8C%BA%E5%9D%97%E7%B0%87%E5%9C%BA%E6%99%AF%E7%BF%BB%E8%AF%91%E6%9E%B6%E6%9E%84.md)：cluster、capture/compile 分层、epoch、generation、关系翻译和 ABI。
- [docs/场景资源生命周期.md](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/docs/%E5%9C%BA%E6%99%AF%E8%B5%84%E6%BA%90%E7%94%9F%E5%91%BD%E5%91%A8%E6%9C%9F.md)：BLAS 生命周期、上传预算、prepare/accept、退休和 profiling。（若 GitHub URL 的中文路径解析失败，以仓库内同名文件为准。）
- [src/.../TerrainStreamer.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/runtime/terrain/TerrainStreamer.java)：desired/current、bounded queue、worker admission、generation 和上传预算。
- [src/.../TerrainScene.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/vulkan/terrain/TerrainScene.java)：TLAS slot、场景候选状态、world light、publish 和 deferred retire。
- [src/.../TerrainUpdateTransaction.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/vulkan/terrain/TerrainUpdateTransaction.java)：未发布 GPU 资源的提交/回滚状态机。
- [src/.../ResourceEpochCoordinator.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/runtime/terrain/ResourceEpochCoordinator.java)：reload generation 和 reader lease。
- [src/.../ClusterSceneTranslator.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/terrain/ClusterSceneTranslator.java)：纯函数 CPU 翻译入口。
- [src/.../BlasCompactionScheduler.java](https://github.com/bWFuanVzYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/src/client/java/dev/prime/render/vulkan/terrain/BlasCompactionScheduler.java)：compaction 的预算、FIFO 和退休状态。
- [docs/纹理翻译架构.md](https://github.com/bWFuanzusYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/docs/%E7%BA%B9%E7%90%86%E7%BF%BB%E8%AF%91%E6%9E%B6%E6%9E%84.md)：当前 atlas migration baseline、TextureId/page/generation 和正常帧边界。
- [docs/测试与基准验证.md](https://github.com/bWFuanzusYWth/prime/blob/c9ac55b9546abbb54416c6fe4eb4d9db603f659e/docs/%E6%B5%8B%E8%AF%95%E4%B8%8E%E5%9F%BA%E5%87%86%E9%AA%8C%E8%AF%81.md)：测试层次、GPU 验证和基准方法。

### RTest（当前工作区）

- `src/main/java/com/rtest/client/RayTracingProbe.java`：分帧 capture、dirty Section 和 camera window delta。
- `src/main/java/com/rtest/client/RayTracingScene.java`：Section capture、MeshData/CPU fallback 和全量 `replaceSections()` merge。
- `src/main/java/com/rtest/client/CompiledSectionMeshCache.java`：vanilla MeshData 快路径及保守 PBR 说明。
- `src/main/java/com/rtest/client/RayTracingPbrMaterials.java`：lazy LabPBR lookup、全量 packed PBR payload。
- `src/main/java/com/rtest/client/RayTracingVulkanPass.java`：BLAS/TLAS 分批构建、同步 fence、geometry publish、dynamic update 和 close。
- `src/main/java/com/rtest/client/DynamicVulkanResourceSlice.java`：尚未接入主 pass 的 fence/retire 规则 seam。
- `docs/instance-validation.md`：真实实例中的视距、闪烁、捕获、长帧和基础渲染记录。
- `docs/DEVELOPMENT.md`、`docs/MAINTENANCE.md`：当前实现与维护约束。
