# Voxy `2622` 项目概览

> **文档性质：参考资料/阶段性快照。** 不作为当前实现规范、能力清单或待办计划。涉及 RTest 的描述可能已过时或不完整；请以源码与 `docs/HANDOFF.md` 为准，使用前逐项复核。

调查日期：2026-09-22。以下针对分支 `2622` 当时的提交 [`91c96528bc458be0646ddda2564aa90baaf639e2`](https://github.com/MCRcortex/voxy/tree/91c96528bc458be0646ddda2564aa90baaf639e2)，不是对后续版本的承诺。

## 用途与定位

Voxy 是 Fabric 平台的 Minecraft 远景 LOD 渲染模组。仓库 README 仅作了这一句简短定义；模组元数据同样称其为“利用 LOD 的远距离渲染模组”。它的目标是为远处地形另建可渲染的低细节表示，而不是提高原版近景区块网格的精度。[README](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/README.md) · [fabric.mod.json](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/resources/fabric.mod.json)

## 主要数据流

1. **摄取与降采样**：`WorldConversionFactory` 从 Minecraft 区块取得方块、群系和光照数据；`VoxelizedSection` 保有 16³ 体素及逐级 mip，`WorldVoxilizedSectionMipper` 构造较粗层级。[WorldConversionFactory](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/voxelization/WorldConversionFactory.java) · [VoxelizedSection](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/voxelization/VoxelizedSection.java) · [WorldVoxilizedSectionMipper](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/voxelization/WorldVoxilizedSectionMipper.java)
2. **世界数据与持久化**：`WorldEngine` / `WorldSection` 管理带 LOD 层级和子节点信息的 32³ section；数据可经 `SectionStorage` 保存，默认配置是 RocksDB 加 ZSTD 压缩。仓库还实现了 LMDB、Redis 与内存后端；它们是可配置实现，不代表默认同时启用。[WorldEngine](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/world/WorldEngine.java) · [WorldSection](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/world/WorldSection.java) · [StorageConfigUtil](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/StorageConfigUtil.java) · [storage 目录](https://github.com/MCRcortex/voxy/tree/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/common/config/storage)
3. **异步建模与上传**：`RenderGenerationService` 在服务线程上生成 section 渲染数据；`RenderDataFactory` 编码 quad；`AsyncNodeManager` 汇总节点和几何更新，再交给 GPU 资源。`VoxyRenderSystem` 是这些部件的装配点。[RenderGenerationService](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java) · [RenderDataFactory](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java) · [AsyncNodeManager](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java) · [VoxyRenderSystem](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java)
4. **选择与绘制**：GPU 层级遍历按视锥、屏幕空间尺寸和 Hi-Z 遮挡选择节点，并为缺失节点发请求；当前装配的绘制后端是 `MDICSectionRenderer`，走 OpenGL 间接绘制路径。`RenderPipelineFactory` 按条件选择普通或 Iris 管线。[traversal_dev.comp](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/resources/assets/voxy/shaders/lod/hierarchical/traversal_dev.comp) · [VoxyRenderSystem](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/VoxyRenderSystem.java) · [MDICSectionRenderer](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java) · [RenderPipelineFactory](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/RenderPipelineFactory.java)

简化关系：`Minecraft 区块 → 体素化/mip → WorldSection/存储 → 后台 quad 建模 → GPU 节点遍历/Hi-Z → 间接绘制`。这是根据上述源码整理的数据流，不是仓库提供的正式架构图。

## 构建和运行条件

- 该提交的 Gradle 属性固定 Minecraft `26.2`、Fabric Loader `0.19.3`、Fabric API `0.152.2+26.2`、Sodium `mc26.2-0.9.1-beta.3-fabric`；构建脚本使用 Fabric Loom，并指定 Java 25 编译。`fabric.mod.json` 声明 Fabric API 与 Sodium 为运行依赖，兼容版本由构建时替换。[gradle.properties](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/gradle.properties) · [build.gradle](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/build.gradle) · [fabric.mod.json](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/resources/fabric.mod.json)
- 客户端启动时检查 OpenGL compute 与 indirect draw parameters 等能力，检查不通过会标记系统不支持。NVIDIA mesh shader、sparse buffer 等能力也被探测，但不能据此推断均为硬性前提。[VoxyClient](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/VoxyClient.java) · [Capabilities](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/java/me/cortex/voxy/client/core/gl/Capabilities.java)

## 成熟度与阅读建议

仓库在这个提交把版本标为 `0.2.17-alpha`；README 没有详细安装或 API 文档，源码中仍有若干 TODO。因此适合当作实际运行的远景 LOD 实现来研究，但不能把内部类视为稳定的公共接口。源码授权元数据为 `All-Rights-Reserved`，复用代码前应另行确认授权。[gradle.properties](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/gradle.properties) · [README](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/README.md) · [fabric.mod.json](https://github.com/MCRcortex/voxy/blob/91c96528bc458be0646ddda2564aa90baaf639e2/src/main/resources/fabric.mod.json)

本文只概述固定提交的 Voxy 源码，不再维护与 RTest 当前实现的对照；RTest 现状以主开发文档和源码为准。
