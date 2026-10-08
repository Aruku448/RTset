# 光追其他逻辑审阅（2026-10-07）

本轮审阅动态数据发布、面光源和物理体积路径。未修改生产代码或替换实例 JAR；下面区分已经复现的正确性缺陷与尚未测量的优化候选。前轮性能报告中的光照/体积消融只是定位依据，不能把它们的耗时差视为这些修改的预期收益。

后续状态：四项已在本日下一轮修复，行为与验证见 [修复报告](lighting-logic-repair-2026-10-07.md)。下面保留修复前审阅与复现记录。

## P1：粒子变化无法单独触发材质映射

`DynamicEntityGeometry.collect` 将粒子 mesh 存入 `livingMeshes`，以 revision 注册到 changedGeometry。`RayTracingDynamicInstances.updateDynamicInstances` 将 PARTICLE 与 LIVING_BODY 一起处理，使用 livingModelBlas 并准备材质上传；但 `hasChangedDynamicMaterials` 的 PARTICLE 分支直接 continue。

触发条件：完整几何发布后的强制材质写入已完成，场景中只有粒子新出现或材质变化，没有其他家族的变化触发材质映射。此时 `materialUploadNeeded=false`，`materials=null`，粒子顶点/BLAS 可以发布，但材质行不写入，可能读到空行或该 slot 留下的旧材质。其他动态实体的材质变化或完整几何发布会偶然掩盖这一问题。

位置：`RayTracingDynamicInstances.java:734`。建议将 PARTICLE 纳入与 LIVING_BODY 相同的预检查分支，并验证首次出现、仅材质变化、消失重现和未变化四种情况。

已执行真实方法的 CPU 最小复现：

```sh
./gradlew -I tmp/logic-review-20261007/probe.init.gradle particleMaterialPreflightProbe --offline --console=plain
```

输出：`particle-only changed material, no cache: needsUpload=false`；断言失败：`New particle mesh must request material mapping`。探针构造一个 active PARTICLE、changedGeometry 含该 identity、livingMeshes 含一行材质，缓存为空，以反射调用真实私有方法，不创建 Vulkan 设备。源码和失败日志保留在 `tmp/logic-review-20261007/`。这是预检查遗漏的复现，不是游戏粒子画面或 GPU 稳定性验证。

## P2：全遮挡物理体积样本仍完成全部介质计算

位置：`RayTracingShaderRaygen.java:1333–1355`。接受光源后先计算采样点 medium 和 viewT，实际遮挡查询后无条件计算 phase、sourceT、transport，最后才查询静态可见性。

每个接受的样本有 1 次采样点 medium + 4 次 viewT 积分 medium + 4 次 sourceT 积分 medium，共 9 次 medium 评估，两个四波段 exp。即使实际和静态可见性最终都为零，这些工作仍执行。质量三时最多四个样本，即 36 次 medium 评估、8 个向量 exp；这是源码执行量上限，不是实测 GPU 指令或时间。

候选顺序：光源接受 → 实际可见性 → 必要时静态可见性 → 判断两种贡献是否均为零 → medium、phase、viewT、sourceT。这样也可减少 medium/viewT 跨 trace 保留。不能只以实际可见性为零早退：动态物体遮挡时静态可见性可能非零，动态阴影差值仍需计算。`camera.settings.y` 混合后部分可见性、彩色透射也必须保留。

## P2：BSDF 与贡献结果跨第二次阴影查询保留

位置：`RayTracingShaderRaygen.java:1427–1441`。`estimateAreaDirect` 在静态可见性查询前计算完整 BsdfValue、scale 和实际 diffuse/specular，查询返回后又读取 BSDF 计算动态差值。

候选顺序：两种可见性先完成 → BSDF 一次求值 → 实际贡献/动态差值。其目的在于缩短变量存活区间，减少潜在的 trace continuation 保存、寄存器或 scratch 压力，并为双零可见性跳过 BSDF 提供机会。现有代码只评估一次 BSDF；这里不是减少调用次数。编译器可能已优化存活区间，没有 GPU register/scratch counters 和 A/B，不能声称存在确定的额外搬运量或加速。

## P2：动态 BLAS 替换重复复制完整顶点快照

位置：`RayTracingDynamicInstances.java:333`、`:463`、`:530`。replacement 构造 DynamicCachedBlas 时先 copyOf 一份 topologyVertices，外层随后又 copyOf 同一 mesh.vertices 到 uploadedVertices。因此每次替换至少有两份内容相同的 Java 顶点快照，另有必要的 native 顶点输入写入。

候选：刚创建的资源以其独立拥有的 topologyVertices 同时作为初始 uploadedVertices；后续 update 若要修改快照，重新赋值独立数组，不能原地改写 topologyVertices。需要覆盖捕获数组变化、BLAS UPDATE、替换与失败路径。源码中 replacement 用于规避曾出现的 RADV 不稳定，不能为减少复制直接改成原地 BUILD 或取消 retirement/fence。

## 优先级与验证

先修粒子预检查正确性，再独立实施物理体积的双可见性早退与计算顺序调整，最后处理顶点快照复用。每项分别构建/运行，避免与刚修复的 device lost 混在一起归因。GPU 候选需要固定场景、设置、窗口与频率记录进行前后对照；本轮没有新 GPU 耗时、寄存器计数或游戏稳定性结论。
