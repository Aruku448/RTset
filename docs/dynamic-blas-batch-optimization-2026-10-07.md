# 动态 BLAS 批处理与材质差分上传（2026-10-07）

## 问题与范围

正常 RT 已记录到 Minecraft 的 frame-owned encoder，由整帧统一提交。因此统计中的约 22 次 BLAS BUILD 不是 22 次队列提交。旧实现让每个构建复用同一个 scratch 区间，逐个发出构建及 AS read/write barrier，使独立小 BLAS 串行化。动态材质虽有整 mesh 的变化检查，但一旦变化就写入完整 mesh 并默认刷新整个材质分配。

本次保持每帧动画更新和 BUILD 模式，不恢复此前已回退的每帧一次/多帧一次更新预算，也不更改 GI、ReSTIR、体积或画质。

## 实现

1. `BlasBuildBatch` 使用对齐前缀和：`offset[i] = alignUp(end[i-1], alignment)`，`end[i] = offset[i] + scratchSize[i]`。独立构建的 scratch 不重叠；通常约 22 个小构建合并到一次 `vkCmdBuildAccelerationStructuresKHR` 调用。每批最多 32 个、目标 scratch 上限 16 MiB；单个超过预算的构建独立运行，全部构建仍在同帧完成。
2. 动态更新根据待构建列表预留 arena，并保留原静态/单个构建及 TLAS 所需大小；已经分配的 arena 复用。每批完成后发出 AS read/write barrier，供下批及 TLAS 安全复用 scratch。批量 native structs 的 geometry/range 指针在调用期间保持存活。先前的 fence 和旧 BLAS 延后销毁规则保持。
3. `DynamicMaterialDelta` 精确比较 28-float 三角形行，将连续变化行合并为区间，只写入这些区间。新 mesh、长度变化、场景材质重置时完整写入。原缓存仅在写入后更新，不使用近似阈值或仅 hash 相等。
4. 材质 mapping 显式记录写入范围，调用现有 VMA 刷新范围合并；动态 TLAS 实例和 motion metadata 也只刷新自身写入区间。VMA 负责非 coherent atom 对齐；在 coherent 内存上减少 flush 范围未必带来 GPU 收益。材质行若随动画全部变化，仍需完整 mesh 写入。
5. 调度日志增加累计 `BLAS build batches` 和 `material bytes`。旧 `BLAS build commands` 仍统计逻辑构建数，不能用它判断 API 调用或 queue submit 次数。

规范依据：[Vulkan vkCmdBuildAccelerationStructuresKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdBuildAccelerationStructuresKHR.html)。同一批次的构建没有隐含顺序，scratch 不能重叠；TLAS 仍在 BLAS 完成及同步后单独构建。

## 验证及边界

已通过 `dynamicUploadBatchTest`、`playerBlasCommandTest`、`mappedUploadMathTest`、`dynamicTlasInstanceContractTest`、`dynamicVulkanSliceContractTest`，并构建 JAR。新增测试覆盖 200 组随机 scratch 布局、单构建超预算、对齐/边界/溢出、native struct copy 与 geometry 指针、250 组差分对整段写入比较、未变化/强制/长度变化、signed zero、前后保护区和 dirty range 覆盖。`git diff --check` 通过。

已安装到 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，SHA256 `3ce0175e8bdb18c33d023c3e9ad3faa4ab6eab579186093eabfe493c237feef6`。旧 JAR 备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261007-222225-blas-batch/rtest-0.1.0.jar`。

**尚未完成新 JAR 的实际 GPU 验证，不宣称性能提升。** 旧进程于 22:15 退出，随后 baseline 采样因无新日志超时，配置已自动恢复 baseline。需要重启 RTest 后验证动画、cutout 和新批次统计，保持视角、分辨率、设置一致，再比较 dynamic_blas_ms / TLAS / trace / command_total、CPU dynamic_update/command_record，以及时钟/功耗。此前 854×1074 下 1.320 ms 的动态 BLAS 基线不能直接与之后 2560×1404 的不同场景数据拼成受控 A/B。

该优化首先减少构建 API 调用和串行 scratch 同步，以及有变化材质的写入量。它并不降低每帧逻辑 BLAS 数量、分配替换 BLAS 的数量或 shader 内部光照射线数，也未证实数据搬运是此前约 19.8 ms 光追 dispatch 的主瓶颈。批次扩展会增加保留的 scratch 内存，需实测其收益。

## 用户运行反馈与日志

用户反馈优化版正常。22:23:39 的累计日志为 47384 个逻辑 BLAS 构建、2095 个批次（约 22.6 构建/批）；最近 854×480 的动态 BLAS 时间戳样本为 0.128–0.136 ms。累计材质写入 2191618352 字节，2145 个材质上传帧，说明动画仍可能导致大量材质行变化，差分不会消除所有上传。视角、分辨率、频率未按旧版本固定，不把这些样本当作受控加速比例。
