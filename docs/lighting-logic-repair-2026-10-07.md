# 光照数据流与粒子上传修复（2026-10-07）

针对 `logic-review-2026-10-07.md` 的四项发现修复。没有修改 AS 屏障、frame fence、退休资源释放、BLAS 更新频率、光源 PDF 或可见性射线数量。

## 最终行为

- 粒子材质预检查与实际上传均走 living mesh/cache 分支。只有粒子新出现或材质变化时也能触发材质映射，避免未写材质行；已上传且未变化、未激活或没有 changedGeometry 的粒子仍跳过。
- 物理体积路径先完成实际/静态可见性，再决定是否评估 medium、viewT、phase、sourceT。只有两个 RGB 向量都精确为零才跳过；动态遮挡时实际为零、静态非零仍计算动态差值。每个跳过样本省去源码中的 9 次 medium 评估及两个向量 exp。动态标记在跳过前已记录，积分样本数和归一化保持不变。
- 面光源先完成两种可见性，再评估一次 BSDF。双零样本不计算 BSDF，但保留 result.valid、采样光源、距离、动态标记与实际/静态可见性，确保主路径元数据仍发布。BSDF 与贡献结果不再跨静态可见性查询存活。
- DynamicCachedBlas 在构造时复制一次独立顶点快照，同时作为初始 topologyVertices 与 uploadedVertices。删除外层第二份快照复制和捕获数组直接赋值。后续 UPDATE 仍重新赋值 uploadedVertices 的独立副本，不原地改写 topologyVertices。刚创建/替换的资源不再重复复制完整 Java 顶点数组；native 顶点输入仍正常写入。

## 验证

新增 `lightingLogicRepairTest`，调用真实粒子预检查方法；没有 Vulkan 设备。修复前执行失败 `new particle needs mapping`，日志 `/tmp/rt-logic-repair-red.log`。

修复后通过：首次粒子出现、已上传未变化、仅材质变化、未激活、无几何变化、移除缓存后重现；顶点快照独立于捕获数组、初始共享缓存存储、替换资源独立，以及 UPDATE 不原地改写拓扑快照。

10 万组有限值输运对照覆盖静态全遮挡、动态全遮挡、彩色/单通道透射与关闭阴影。双零样本输出与原公式一致；测试中仅 20,000 组双零样本跳过，动态阴影差值保留。shader 契约检查实际查询、双零判断和介质/BSDF 计算顺序，数值对照本身不是 GPU 图像验证。

通过命令：

```sh
./gradlew lightingLogicRepairTest dynamicUploadBatchTest emitterDataflowTest accelerationStructureSynchronizationTest rayTracingCostAuditTest rayTracingShaderContractTest restirConditionalMathTest jar --offline --console=plain
```

最终新增测试后再次执行 `lightingLogicRepairTest dynamicUploadBatchTest emitterDataflowTest accelerationStructureSynchronizationTest jar` 通过。相关日志 `/tmp/rt-logic-repair-tests.log`、`/tmp/rt-logic-repair-final-tests.log`。

14 种消融及不同 reservoir/大气组合共 98 个模块编译并独立通过 `spirv-val --target-env vulkan1.2`；`git diff --check` 通过。保留之前的同步修复契约检查。

## 限制

没有本版本的同场景 GPU A/B、寄存器/scratch counters 或长期游戏稳定性结果。介质/BSDF 跳过收益取决于双零遮挡样本比例，新增判断也有执行与分歧成本；变量存活缩短能否减轻驱动生成代码的压力仍需测量。数值对照假设原输入与输运系数有限，不能验证运行期 descriptor、AS 同步或驱动行为。

## 部署

已安装 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，SHA256 `90af03cb7297463c2f9a89d6012e8b2705d7d4bd93c8aa79b2dea7d43e5d2e11`。上一版备份 `/home/aruku/.minecraft/versions/RTest/mod-backups/20261007-233042-lighting-logic-repair/rtest-0.1.0.jar`。需重启加载；测试日志另存 `tmp/logic-repair-before/`。
