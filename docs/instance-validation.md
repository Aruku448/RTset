# 实例验证记录

日期：2026-09-12
平台：Minecraft 26.2 / NeoForge 26.2.0.84 / Vulkan / RADV

本文按时间追加记录；历史段落中的“待验证/尚未完成”只表示当时状态，不能覆盖后续记录。

## 最新验证（2026-09-15）

- 用户确认 RT/FSR3 Vulkan 渲染可以正常开启并显示画面。
- 该结论只覆盖基础渲染链路；视觉质量、长时间稳定性和专项动态对象测试仍需分别记录，不能由一次成功启动推断。

## 已验证

- F9 设置界面中文翻译正常。
- F9 设置描述与默认值提示正常。
- Vulkan RT 能启动并输出画面。
- FSR3 输出正常，日志分辨率为 `2560x1404 -> 2560x1404`。
- RT 单次渲染日志约 `7--9 ms`。
- 水和岩浆在 `fluidRtEnabled=true` 时正常显示。
- 关闭 `fluidRtEnabled` 后流体消失/出现空洞，说明回退开关确实生效。
- 重新开启 `fluidRtEnabled` 后流体恢复正常。

## 当前结论

Fluid/Lava 垂直切片通过基础实例验证。建议保持：

```toml
fluidRtEnabled = true
```

关闭流体捕获只作为兼容性诊断回退，不作为正常画面配置。

## 尚未完成

- 固定场景下的原生与 RT 完整画质 A/B 截图；
- 粒子、云、雨雪恢复后的实机验证；
- 实体 MVP 开关与真实模型画面验证；
- HDR/SDR、resize/fullscreen 对比；
- GPU 总帧时间、显存和长期稳定性测试。

## 区块视距窗口修正（2026-09-13；核心路径已通过实机复测）

- 原因：初始 `loadedSectionOrigins` 只在启动/整场景捕获时取样，摄像头移动到新 chunk 后没有对 RT 场景做窗口差分；`onChunkLoaded` 不能覆盖已预加载区块。
- 修正：`RayTracingProbe` 记录已发布的 camera chunk window，跨 chunk 后计算 `SceneWindowDelta`；新增 Section 进入 `pendingCaptureSections` 并分帧捕获，离开有效视距的 Section 只从场景增量移除。
- 保持约束：不调用每帧 `waitIdle()`，不因普通摄像头移动整场景重捕获；区块/Section 修改仍按 Section 粒度处理。
- 验收：在同一视角与固定视距下沿 X/Z 跨越 chunk 边界，检查 RT 日志的 `camera-window update`、新增建筑出现、离开视距的建筑消失，以及延迟加载区块最终进入 RT。

## 阶段 0 实测记录（2026-09-13）

- 输出与 RT 分辨率：`2560x1404 -> 2560x1404`；初始完整捕获 1 次，随后记录到至少 19 次 Section 增量更新。
- 增量窗口正常工作：日志出现 `camera-window update`，单次示例为移除 306、捕获 433 个 Section；没有因摄像头移动触发完整场景重捕获。
- 运行期间场景三角形数量约为 `797750--1077254`；流体路径持续使用 CPU fluid-aware capture，PBR companion texture sets 从 169 增长到 200。
- 发现回归：每次 `applied dirty-section update` 后紧跟一条 `centerPixel=0x0` 的渲染日志，随后下一帧恢复非零 RT 输出；视觉表现为加载区块时短暂退出 RT 并闪烁回原版画面。
- 根因定位：Section 增量更新会暂时将 `topLevelBuilt` 置为 `false`；BLAS/TLAS 构建未完成时 `dispatch()` 直接返回，导致 before-hand RT seam 不提交 RT/FSR 图像。
- 修复：AS 构建期间复用上一张已完成的 FSR display image 拷回主目标，不调用每帧 `waitIdle()`。
- 复测结果：用户确认加载区块时不再短暂退出 RT 或闪烁；延迟加载和窗口淘汰核心路径已通过复测，区块事件卸载仍需单独覆盖。
- 固定场景基线（2026-09-14，截图：`/tmp/Spectacle.QfhrBx/屏幕截图_20260914_015130.png`）：输出/RT 为 `2560x1404 -> 2560x1404`，完整捕获 1 次、随后至少 25 次 Section 增量更新；增量更新期间 `centerPixel` 保持非零，未再出现旧版的 `0x0` 空档。
- 基线日志中稳定 RT 单帧约 `7--13 ms`；Section 更新帧约 `43--79 ms`。场景三角形数量约 `887430--894240`，PBR companion texture sets 为 160，fluid-aware capture 持续启用。
- 当时固定场景仍记录到前几秒的同窗口 Section 延迟加载，最终趋于稳定；该次记录尚未覆盖区块卸载和视距变更。截图显示 RT 已正常输出，但当时 `nrdEnabled=false`、`giBounces=4` 配置下噪声和高亮过曝仍属于画质观察项。后续视距变更记录见下文。
- 延迟加载/窗口淘汰复测（2026-09-14，`latest.log` 01:55:26--01:56:43）：摄像头从 `-26,13` 移动到 `-19,-3`，日志记录了移除旧 Section、捕获新 Section，单次最高约移除 527、捕获 674 个 Section，未出现 RT smoke-test failure。
- 复测期间每次 `applied dirty-section update` 后仍有正常 RT 输出；`centerPixel` 保持有效值（包括场景真实黑色的 `0xff000000`），没有再次出现表示跳过 RT 的 `0x0`。窗口淘汰/延迟加载核心路径通过；该条记录当时尚未覆盖区块事件卸载和视距设置变更，后续视距切换已在下文复测。
- 本次移动测试增量更新峰值约 258 ms，场景三角形数量约 `186206--1158932`，PBR companion texture sets 达到 212；这些加载期长帧属于阶段 0 性能优化记录，不判为闪烁回归。

## 视距变更崩溃（2026-09-14）

- 操作：在 RT 运行期间将视距从 `5` 改为 `8`。
- 结果：客户端在 Render Frame 崩溃，崩溃报告为 `crash-2026-09-14_01.58.36-client.txt`。
- 根因：`RayTracingProbe.onRenderLevelAfter` 检测到 `smokeGeometry.renderDistanceChunks` 变化时，`captureSession` 可能已经是 `null`，但仍无条件调用 `captureSession.close()`。
- 修复：关闭捕获会话前增加 `captureSession != null` 保护；视距变更仍保留完整捕获请求。回归契约已加入 `SceneWindowDeltaTest`。
- 复测结果（`latest.log` 02:01:23--02:03:35）：视距 `8 → 5` 和 `5 → 8` 均完成完整捕获，分别记录约 `1117986` 和 `1260330` 个三角形；期间无异常、无崩溃，RT 输出持续存在。
- 结论：当前表现是加载较慢而不是不加载。8 chunk 视距的 1491 Section 捕获约耗时 14 秒，原因是 `SECTIONS_PER_FRAME=2` 且使用 CPU fallback capture；加载期间继续显示旧 RT 场景，完成后替换为新场景。
- 第二轮计时复测（02:12:26--02:15:15）：`5 → 8` 捕获 1297 Section，约 15 秒后完成并得到 `1444660` 三角形；`8 → 5` 捕获 997 Section，约 11 秒后完成并得到 `1118526` 三角形。两次切换期间均无 RT 崩溃、无 `centerPixel=0x0`；启动初始化阶段出现的 `centerPixel=0x0` 不属于视距切换空档。
- `SECTIONS_PER_FRAME=4` A/B 复测（02:18:21--02:18:32）：`5 → 8` 捕获 1289 Section，约 11 秒完成并得到 `1381062` 三角形，但期间出现多次约 `71--242 ms` 的 RT 帧耗时，用户确认帧生成时间过长。因此已恢复 `SECTIONS_PER_FRAME=2`，不采用简单翻倍预算。
- 恢复 `SECTIONS_PER_FRAME=2` 后复测（02:22:01--02:22:15）：`5 → 8` 捕获 1289 Section，约 14 秒完成并得到 `1379498` 三角形；过程中仍出现约 `68--245 ms` 的长帧。说明长帧主要来自捕获完成后的整场景几何/TLAS 更新，而不只是 Section 分帧预算；这是历史性能观察，不是当前执行计划。
- 第一轮发布优化已完成：`RayTracingVulkanPass.updateGeometry` 在现有 host-visible buffer 容量足够时复用 `instanceBuffer`/`materialBuffer`，改为原地写入，避免每次 Section 更新重新分配并销毁这两类资源；后续复测仍观察到发布期长帧。
- 复测结果（02:30:40 起）：`5 → 8` 完整捕获 1289 Section，约 12 秒完成；但随后包含摄像头移动和窗口增量更新的阶段仍出现最多约 `797 ms` 长帧，30 次 partial capture 持续产生。该结果表明仅复用 buffer 仍不足，需用几何发布、PBR 同步和 TLAS 更新耗时日志继续定位。

## 动态 Placeholder TLAS 历史验证

- 开启 `dynamicEntityMvpEnabled` 后，玩家 placeholder 可见；
- placeholder 为纯色不透明立方体，不再透出天空盒或采样 Block Atlas；
- 动态 BLAS/TLAS 与 instance material range 基础契约通过；
- 当前仍未验证真实玩家模型和真实物品模型；长时间 slot 回收需后续压力测试。
- 用户完成了玩家/物品动态快照生命周期验证，未报告异常。
- 早期玩家适配器/动画版本未通过实机验证，不能沿用 placeholder 的通过结论。

## 玩家原生动画修正（2026-09-13；专项画面仍待实机复测）

- 复现：旧适配器重复除以 16，原版模型测试高度仅 0.12792969 格；移除手工缩放后测试高度为 1.9195981 格。
- 改为在原版 `ModelFeatureRenderer` 实际绘制入口旁路复制顶点，复用已经执行的动画、根变换、部件可见性，不再单独调用 `AvatarRenderer` 或 `setupAnim`。仅捕获世界绘制，不捕获背包预览。
- 已构建的玩家 BLAS 有新顶点时，同一 RT submission 内执行 BLAS UPDATE → TLAS UPDATE → trace；UPDATE 指定原 AS 为 source；提交 fence 完成后才清除 pending 标记。沿用现有 submission fence，没有新增每帧 waitIdle。
- 可变 BLAS 按玩家独立持有；各 slot 预留 512 个三角形的独立材质区间并上传实际面法线，避免玩家 body/手持物越界到物品材质；可见部件数量变化只重建该玩家 BLAS。
- 自动测试覆盖原版行走/蹲下/根旋转、顶点转发一致性、模型尺寸、材质 stride 和 Vulkan BUILD/UPDATE 命令字段。测试通过不代表 GPU 画面已通过。
- 复测：完全重启，开启实体 MVP 和 RT，F5 第三人称站立/走动/转身/蹲下；检查日志 `RTest native player animation: ... BLAS UPDATE completed before TLAS/trace`。
- 第一人称原版不提交完整玩家身体，因此不会生成该身体的 RT 实例。该次复测时皮肤采样、手持物、盔甲/披风和物品原生模型仍未接入；粒子时序本轮未修改。

## 第三人称手持物接入（当前源码）

- `ItemEntityCaptureMixin` 同时关联 `AvatarRenderState` 和 `ItemEntityRenderState`，复用 `SubmitNodeCollection.submitItem` 的最终 `BakedQuad` 捕获，不重放 `ItemInHandLayer` 的手部变换。
- 第三人称玩家两只手的物品网格在 `DynamicEntityGeometry` 中追加到同一玩家 body mesh；玩家 skin 材质才写入 skin descriptor slot，item atlas 材质保留 `optical.x=-1`。
- 第一人称手持物仍由 before-hand RT seam 之后的原生 raster overlay 绘制，未进入 RT BLAS。
