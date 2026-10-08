# GPU 路径逻辑检查与动态材质修复（2026-10-08）

本次基于已完成移动/转视角观察的版本 A。保留太阳、月亮、天空的光照调度优化，保留原诊断缓冲和原 AS 几何标志。上一轮 134 组 GPU 计时、204 次地形发布未观察到重置；这只是该窗口的结果，不是长期稳定性保证。

## 确认并修复的问题

`RayTracingDynamicInstances.hasChangedDynamicMaterials()` 原先在 `changedGeometry` 为空时直接返回，且只检查集合中的对象。但 GPU 模型缓存和 CPU 实例注册表的生命周期不同：对象未被捕获时，GPU 模型缓存会释放；注册表仍保留对象身份、几何键及槽位。对象再次出现且几何键相同时，注册表设置历史重置，但不报告几何变化。

因此更新循环会创建新的 BLAS 缓存，而预检查可能不映射材质缓冲，跳过其材质上传和快照初始化。旧槽位中的残留内容有时恰好正确，其他对象触发映射也可能掩盖问题，但它们不能代替当前缓存完成初始化。

修复将缓存存在及材质快照已初始化作为跳过上传的前提。活动对象缺少缓存或快照时需要上传；正常缓存仍只对几何变化对象比较材质。非活动对象及超出当前 GPU 槽位容量的对象跳过检查。没有增加 Vulkan 提交次数，没有强制每帧上传，也没有调整动画更新频率。

## 检查结果与边界

- 独立 BLAS 批次使用不重叠 scratch 区间，复用 arena 前有 AS 读写屏障。单独构建路径也有逐次屏障，本次没有修改。
- 地形发布先等待上一帧 fence，再改写复用缓冲和描述符；替换 BLAS 经 TLAS 构建完成后的退役路径释放。本次没有发现需要补充的同步缺口。
- 材质描述符使用 live 字节数，没有把预留容量误作材质读取范围。本次未将容量增长认定为故障原因。
- 新 JAR 中 shader、GPU recorder、AS、NativeBuffer 相关 14 个 class 与实测版本 A 逐字节一致。

这些检查没有证明最初 gfx 队列超时/显卡重置的具体触发项。小诊断缓冲、any-hit 标志及其组合仍未完成实际隔离测试，本次没有重新启用它们。动态材质修复不能被当作该重置的已证实根因修复。

## 验证

`LightingLogicRepairTest` 用真实实例注册表执行出现、缺失、相同几何重新出现的三帧过程，确认重新出现时 `changedGeometry` 为空而历史重置为真。旧逻辑在“缓存释放后必须初始化材质”断言失败，修复后通过。测试覆盖全部七类动态对象，也检查缓存存在但快照未初始化、稳定快照不上传、非活动对象不上传和材质变化上传。

通过的检查：`lightingLogicRepairTest`、`dynamicUploadBatchTest`、`modelCaptureBatchTest`、`accelerationStructureSynchronizationTest`、`rayTracingShaderContractTest`、`restirConditionalMathTest`、`customEntityGeometryTest`、`dynamicModelChunksTest`、`dynamicContractTest`、`dynamicTlasInstanceContractTest`、`dynamicVulkanSliceContractTest`、`vulkanResourceLifecycleTest`，以及 `jar` 构建。

生命周期测试曾保留已回退的四帧更新间隔断言。本次将其修正为当前已恢复的一帧更新契约，再次运行通过；没有为满足旧断言修改生产代码。初始材质回归失败日志 `/tmp/rt-logic-reactivation-red.log`，修复验证日志 `/tmp/rt-logic-repair-green.log`、`/tmp/rt-logic-repair-families.log`、`/tmp/rt-logic-repair-lifecycle-final.log`。

新 JAR SHA-256：`933269295457f735fa59cca31a94aa0afa921afdee9ad9e75b891a59ead0753d`。运行中的游戏继续使用其已加载类，实例文件替换后需要重启才能使用本次修复；新版本尚未完成游戏内运行验证。

已替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar` 并核对 SHA-256 一致。替换前的实测版本 A 在实例 `mod-backups/*-material-reactivation/rtest-0.1.0.jar` 中保留。
