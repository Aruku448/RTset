# 22:35 GPU 崩溃与同步规范审计

## 实际故障

RTest 于 2026-10-07 22:35:45 抛出 `VK_ERROR_DEVICE_LOST: Failed to present image`。内核同秒记录 `Illegal opcode in command stream`、gfx ring timeout，归属游戏 Java PID 1033114 / Render thread，并执行 ring reset。present 是故障检测位置，不能确定就是故障源。清理期 semaphore 等待失败是 device-lost 后续症状。

崩溃 JAR SHA256：`c74f9340c5b2e57fd281d96b70899559443932229ffc1caeed7c6e8cf15a0990`。最后阶段出现 dirty-section CPU merge、静态 geometry publish、重用 TLAS/实例/材质缓冲及静态材质增量写入；最后记录 GPU 样本为 dynamic BLAS 0.129 ms、TLAS 0.346 ms、trace 11.736 ms。日志中的 GPU 样本是在等待前一提交完成后读取，不能当作崩溃提交的每阶段执行证明。

原始证据保存在 `tmp/crash-audit-20261007-223545/`：latest.log、crash report、kernel.log、修复前 pass 源码。未读取 root-only GPU devcoredump，未获得执行级 fault 地址或 validation-layer 运行报告。

## 发现并修复的明确同步缺陷

| 路径 | 原目标访问 | 修复目标访问 |
|---|---|---|
| 初始化/增量 TLAS BUILD → ray tracing | SHADER_READ | AS_READ + SHADER_READ |
| 常规 TLAS UPDATE → ray tracing | SHADER_READ | AS_READ + SHADER_READ |
| 仅动态 BLAS BUILD → ray tracing | SHADER_READ | AS_READ + SHADER_READ |
| terrain compute 写 instance → TLAS BUILD | AS_READ | SHADER_READ |
| 两处显式 HOST 写 instance → TLAS BUILD | AS_READ | SHADER_READ |

Vulkan 对资源访问区分如下：AS 存储的 traversal/source AS 读取使用 `VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR`；AS 构建的 vertex/index/instance/transform 输入缓冲使用 `VK_ACCESS_SHADER_READ_BIT`；scratch 使用 AS_READ + AS_WRITE。普通 SHADER_READ 不能替代 traversal 的 AS_READ。

依据：[Khronos Ray Tracing Synchronization](https://docs.vulkan.org/guide/latest/extensions/ray_tracing.html)、[Synchronization and Cache Control](https://docs.vulkan.org/spec/latest/chapters/synchronization.html)。这些错误在本次批处理优化前已有部分存在；前两处 HOST 输入屏障前还有覆盖 SHADER_READ 的公共屏障，但单独声明也应与其输入资源匹配。terrain GPU traversal 当前关闭，不能把该路径错误认定为本次崩溃原因。

修复保留所有算法版本及每帧更新频率，只改变同步访问范围，避免同时回退 shader 使后续归因混乱。**缺陷已经确认；是否导致本次非法命令流尚未通过复现验证。**

## 其他规范检查

- 批次 scratch 使用设备地址对齐的前缀和，各区间互不重叠；独立 BLAS 分别拥有 storage，TLAS 在批次之后单独构建，批次/TLAS 复用 scratch 前有 AS read/write 屏障。native geometry/range 数据在批调用返回前存活。已有随机布局/native struct 测试通过，但没有 GPU validation-layer 对目标 storage 区间做运行时检查。
- 规范要求同批 destination AS 和 scratch 等范围不得重叠：[vkCmdBuildAccelerationStructuresKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html)，尤其 VUID 03702–03704。静态审计未找到导致同批重叠的明确证据，不把其宣称为根因。
- 几何发布在改写/销毁资源前等待上一 frame fence；动态旧 BLAS 延后至相关 TLAS 工作完成后释放。静态退休资源在增量构建提交的 fence 完成后释放。未发现最近新增绕过 fence 的源码路径，但无法仅凭源码排除 encoder/fence 实际执行错误。
- VMA 接收相对 allocation 的刷新范围，并负责 nonCoherentAtomSize 对齐；按三角形行提供未对齐 range 是允许的：[VMA vmaFlushAllocation](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/group__group__alloc.html)。这不能证明 GPU 运行中没有别的内存问题。
- 最新大气 hint 的初值为 0/1，表长固定 40；相邻移动有边界检查，大跳变回退二分。差分 oracle 及 98 个 SPIR-V 校验此前通过。SPIR-V 校验不检查运行期 descriptor、同步或驱动故障，不能用它证明游戏不会崩溃。

## 回归与运行验证

新增 `AccelerationStructureSynchronizationTest` 检查实际 recorder 的三条 build→trace、一个 compute→instance build 和四个 HOST→AS input 屏障。修复前用旧 access mask 运行失败：`AS build -> trace lacks AS_READ`；修复后通过。

已通过：`accelerationStructureSynchronizationTest`、`dynamicUploadBatchTest`、`playerBlasCommandTest`、`mappedUploadMathTest`、`dynamicTlasInstanceContractTest`、`dynamicVulkanSliceContractTest`、`atmosphereHeightCoherenceTest`、JAR 构建及 `git diff --check`。本次未修改 shader，无需重新把之前 98 组合编译当作新证据。

当前工具环境未找到 Vulkan validation layer 配置，未完成运行时 Vulkan validation。修复版仍需要实际重启、开启光追、触发地形增量更新并持续运行，检查游戏日志和内核是否再次出现 GPU reset。**不能声明所有实现完全符合规范或本次崩溃已彻底修复。** 若再次崩溃，继续按同步修复版 / 上一个正常版本做受控隔离，并补充 GPU 运行验证。

已安装修复版 SHA256 `3e3887529cbd13ec2d4d55497a99967f1f88cb03df60df68c9f574428ef0da88`，崩溃版备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261007-224256-device-lost-sync/rtest-0.1.0.jar`。
