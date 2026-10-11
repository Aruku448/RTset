# 本机 Voxy LOD 异步流水线检查（2026-10-10）

> **文档状态：研究/审计快照** · 整理日期：2026-10-11。
> 来源版本与本地行号只对应记录时点；建议不是自动生效的待办，实施情况需对照源码和后续记录。 [分类索引](README.md)

本记录仅检查本机现有源码；没有构建、运行测试、修改生产代码或用户配置。路径和行号对应本轮读取时的工作区内容。

## 仓库状态

- `/home/aruku/voxy`：分支 `forge-1.20.1-sp`，HEAD `8285ecccf3d296f40d9b500a70aab2ca90affe2a`。存在未提交修改：构建脚本、渲染系统、Iris/Sodium mixin、兼容引导、日志、shader 和新增 compat 文件等。因此本文引用工作区而非假设 HEAD 原样。
- `/home/aruku/voxy-NHblock714`：分支 `neoforge-1.21.1`，HEAD `bba98562261c43d02ce9e5bb6de080412e8d93c4`，工作区 clean。
- 两仓库内未找到 `AGENTS.md`、`CONTEXT.md`。没有把 README、旧修复记录视为现状证据。

## 调度与去重

Forge 的 `RenderGenerationService` 每个 worker 使用自己的 RenderDataFactory，任务走 PriorityBlockingQueue 和按 section key 索引的 taskMap；enqueue 只让首次出现的 key 进入队列。见 [任务创建与线程本地工厂](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:125)、[入队去重](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:349)。

去重覆盖排队阶段，不是全生命周期锁：worker 取出任务后、真正 generateMesh 前从 taskMap 移除，期间同位置修改可以排入下一次构建；缺模型重试时 putIfAbsent，已有更新任务会接管请求信息。不能把这当作“旧结果绝对不会发布”的 generation token 验证。见 [取出和移除](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:199)、[重试与替代](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:234)。NodeManager 会丢弃当前已不在 activeSectionMap 的结果，但这只验证 active 身份，未验证内容版本。见 [结果接收](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java:173)。

Forge 的优先级以距离桶为主，同桶粗层略优先，重试降级；缺模型请求也沿用相同距离层级。这是该本机 fork 特有机制，不是两份仓库共有。相机改变只影响新建/重试时 updatePriority，未见对现有队列整体重新排序。见 [优先级](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:69)。NH 的优先级仍是 LOD 层级、重试次数与唯一序号，没有此距离桶：见 [NH 优先级](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:42)。

Forge 根据模型烘焙积压暂停 mesh 服务，队列超过 1000 直接停止，较小积压又结合失败次数；NH 此构造器未设置同款 limiter。见 [Forge limiter](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:140)、[NH service](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/building/RenderGenerationService.java:76)。底层统一 ServiceManager 按 job 数 × service weight 抽样选任务，并检查 limiter；工作线程设置 Java priority 3。见 [服务选择](/home/aruku/voxy/src/main/java/me/cortex/voxy/common/thread/ServiceManager.java:66)、[线程配置](/home/aruku/voxy/src/main/java/me/cortex/voxy/common/thread/UnifiedServiceThreadPool.java:25)。

## 异步节点管理与发布

两份代码均把 NodeManager 与几何 arena 分配的管理逻辑放在专门线程，产出一个 SyncResults，渲染线程以原子交换拿到整个结果集，再提交几何复制、metadata 和 node scatter 更新。这让结构修改与引用一起发布，并减少渲染线程上的树操作。见 [Forge 后台管理器](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:103)、[结果集交换](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:427)、[渲染线程应用](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:544)。

Forge 每轮最多接收 300 个 mesh，要求 arena 至少余 50MB；之后看整个结果集是否超过 2MiB、1024 cleaner 操作、4096 scatter 地址、10 个顶层变化，决定下一轮等待渲染线程消费。**2MiB 是发布后背压阈值，不是严格的单帧上传上限**；一次 300 项就可能超出很多。见 [接纳循环](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:293)、[背压](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:532)。

NH 明确增加发布前接纳预算：2MiB 或 400 copies；未消费的上一份发布也计入同帧额度。达到预算后剩余 mesh 留队列，不把最终结果集拆开。这是最直接可借鉴的卡顿治理。见 [预算常量](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:89)、[预算扣除与接纳](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:292)、[已发布统计](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:555)。

此预算仍是近似：先检查 accumulated < budget 再接收完整下一项，因此允许单个 section 的超额；不覆盖全部 metadata/cleaner/request 工作，也不是毫秒级 CPU/GPU 时间保证。渲染线程最终按 scratch arena 大小复制 staging 数据，并不是只搬 currentElemCopyAmount 的活跃字节；预算应结合 scratch 高水位/碎片观测。见 [staging memcpy](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:585)。

Forge 后台 wake 使用 park/unpark，已删除固定 10ms 批处理等待，workCounter 通过 getAndSet(0) 领取避免负计数；NH 当前仍保留 addAndGet(-workDone) 及负值警告，预算满而队列非空时 sleep(2)。因此不能整套照抄任一分支而不保留本机另一分支的并发修复。见 [Forge 计数](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:200)、[NH 计数和让步](/home/aruku/voxy-NHblock714/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:368)。

## mesh 缓存与失效：重要限制

Forge 声明了容量 1GiB 的 GeometryCache，首次 watch 尝试 remove 命中，worldEvent 会 clear。GeometryCache 本身用有序 map 与字节容量淘汰，put 可以保存 BuiltSection；但全 src/main/java **没有调用 geometryCache.put 的生产路径**。NodeManager.removeGeometryCached 仅调用 removeSection；BasicAsyncGeometryManager.downloadAndRemove 直接抛 Not yet implemented。因此目前源码不能证明退场 mesh 真正进入并复用这个 CPU 缓存，不能按注释声称“Voxy 有生效的 1GiB mesh 重用”。NH 的 AsyncNodeManager 没有这套 GeometryCache 成员。

证据：[缓存成员与读入口](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:82)、[缓存实现](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/GeometryCache.java:23)、[移除实现在这里](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java:238)、[下载未实现](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicAsyncGeometryManager.java:128)、[事件失效](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:930)。

实际有效的是已驻留 GPU arena 的 section mesh 与增量替换：section metadata 32 字节、geometry element 8 字节；heapUploads 按目的地址合并，scatter ID 用集合去重，删除会取消尚未提交的上传。见 [arena 管理](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicAsyncGeometryManager.java:18)、[增量替换](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicAsyncGeometryManager.java:45)、[删除](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicAsyncGeometryManager.java:90)。

SectionUpdateRouter 只向 watch 对象发 mesh/child 变化，并使用 16 个锁分片；neighborMask 再按方向触发相邻重建。见 [事件分发](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/SectionUpdateRouter.java:133)、[邻居失效](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java:936)。

## 上传流并非无阻塞保证

Forge 用 64MiB persistent mapped staging，帧 fence 完成才回收；arena 耗尽时明确 glFinish，全管线等待。这是另一处构建潮下会卡顿的边界。见 [不足时回退](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java:103)、[fence 回收](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java:178)、[容量](/home/aruku/voxy/src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java:217)。

## 对 RTest 的建议（源码推断，尚未实现）

1. 优先借鉴 NH 的**发布前准入预算**，把新 LOD 所需 mesh、材质写入、BLAS 构建和 metadata 作为一致事务，再在其进入本帧发布前按字节/任务数/预计成本限量。RTest 当前 updateGeometry 本身有分阶段计时、材质增量写入与 rollback，适合在外层规划事务，不应只在最后截断 buffer。见 [RTest 材质写入决策](/home/aruku/桌面/Minecraft开发与配置/RT/src/main/java/com/rtest/client/RayTracingVulkanPass.java:1855)、[发布耗时日志](/home/aruku/桌面/Minecraft开发与配置/RT/src/main/java/com/rtest/client/RayTracingVulkanPass.java:2063)。
2. 保留旧 LOD/原生节点覆盖，后台备齐子节点再切换引用；上传、BLAS、材质和 TLAS 一起成功后才发布。Voxy 的结果集交换模式可借鉴；它不需要 BLAS/TLAS，数值预算不能直接套给光追。
3. mesh 生成队列做 section 身份去重与近处优先；需要世代号验证，不能直接把 Voxy 的排队去重当成 RTest 可用的 stale 判定。
4. 观测生产和消费两端：排队长度、过期丢弃、构建耗时、活跃复制字节、staging 实际复制字节、材质写入、BLAS/TLAS 用时以及 fence 等待。仅看到后台生成快，不代表发布不卡顿。
5. 真正 mesh 重用应明确接通缓存写入、内容版本及邻居依赖失效。现有本机 GeometryCache 的注释不能作为已验证方案。

此检查没有采集 Voxy 实际帧时间；以上关于是否更快均为机制判断，不是跨项目性能实测。
