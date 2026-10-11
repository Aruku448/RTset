# LOD 构建期间的发布卡顿

> **文档状态：阶段变更记录** · 整理日期：2026-10-11。
> 描述当时修改与验证；旧默认值、命令结果、安装 hash 和“当前/未部署”均限于该阶段，不是当前实现规范。 [分类索引](README.md)

## 当前记录

此次 latest.log 中 83 次 geometry publish：duration 中位数 25 ms、最大 53 ms；material_write_ms 中位数 18.079 ms、最大 44.231 ms。LOD composition 的已发布 workerDuration 多为 0–4 ms。渲染线程上的材质发布尖峰比后台合成更值得优先处理；日志不能覆盖单个 LOD voxel worker 的完整生成耗时。

日志反复出现 ready/selected coarse 节点非零而 active_coarse=0，并在增量流入时发布 gpuTraversal=false 的合成结果。一次线程快照中 render thread 在帧率限制等待，不能据此复现过去的停顿或排除间歇性问题。

## 两处修正

1. 不再因 terrainLodPending 非空将整个场景切换到 CPU traversal。选择器只消费 ready 节点，未就绪父节点的覆盖范围仍由 native leaves 回退；有任务排队不要求切换整个渲染路径。
2. GPU 构建候选遵循 CPU 选择出的同一 cut，不再额外用圆形 farRadius 排除已捕获地形的 coarse 节点。原生 chunk 窗口是方形，其角落可能超出这个半径；远景缓存代理已经在 availableNodes 阶段经过半径筛选。

这两处会减少流式构建导致的 CPU/GPU 模式切换及原生／粗网格反复发布。保留构建预算、网格算法、近景原生地形、透明地形与未就绪回退；没有删除 Vulkan 同步，也未减少实际 LOD 细节等级。

## 验证范围

Java 编译、JAR 和 correspondingSource 构建通过。这次没有运行测试或重启游戏。实际发布次数、材质上传尖峰、active_coarse、移动时的遮挡和边界画面仍需重启后观察；不能承诺消除所有新地形上传导致的卡顿。
