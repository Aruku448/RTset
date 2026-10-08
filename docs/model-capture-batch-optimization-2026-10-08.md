# 按开销优化：实体模型捕获与合并（2026-10-08）

## 依据与范围

上一轮静止场景实测 GPU 总计中位数 34.515 ms，其中 trace 31.586 ms，占约 91.5%。独立 20 秒 JFR 的分配权重约 65.2% 落在逐片段模型 append，float[] 占权重约 91.9%。分配权重不是 CPU 耗时；GC 暂停累计约 0.7%，也不是 31.6 ms GPU trace 的解释。

本轮先消除有明确源码和分配证据的 CPU 重复搬运，补齐捕获/收集观测，不声称降低 GPU 光线数量或提高硬件峰值利用率。证据背景见 [硬件审计](hardware-utilization-audit-2026-10-08.md)。

## 修改前后

旧 `LivingEntityGeometryAdapter.publishModel` 每次先复制材质以标记纹理，再将已有模型与新片段合并，重新分配并复制完整前缀，且反复发布 Snapshot。N 个等大片段累积复制量随 N² 增长。

新逻辑每个 owner/frame 使用一个 `PendingModel` 和拥有独立数组的 `ModelMeshAccumulator`。每个片段立即复制进入可几何扩容的存储，在 drain 时完成纹理标记并封装一次 Snapshot，累计复制量为 O(最终数据量)。没有保存可被调用者复用的数组引用；每帧存储独立，完成后禁止追加。扩容和标签分配在修改有效长度前完成，首次片段成功后才发布 owner，失败不会留下空 owner。保留首个片段坐标、末个片段纹理元数据、所有片段顺序及各自纹理槽，仅 selector=2 的行被重新标记。其他路径使用的原 append/retag 方法保留。

单个 TRIANGLES/QUADS 捕获器初始 float 存储按 1/2 个三角形分配：148/296 字节，原先两组各 256 floats 共 2048 字节。常见单面完成时不用裁剪复制，大模型继续正常扩容。未改变可见性、模型容量、动画刷新率、BLAS 构建频率或 Vulkan 同步。

## 新增计时

每 120 次 drain/collect 输出一次，通常静止场景可在一分钟内取得多组：

- `RTest model_capture ... owners=... parts=... copied_bytes=... living_custom_us=... seal_us=...`
- `RTest dynamic_collection ... collect_us=...`

`copied_bytes` 是 accumulator 的 float[] 显式复制统计（输入追加、扩容复制、最终裁剪），不含原始捕获器、标签对象、customMaterial clone、上传缓存和 GPU 拷贝，不是硬件总线流量。`living_custom_us` 包含 wrapCustom 回调中原始 renderer.render、捕获、customMaterial 和发布的墙钟耗时，不是纯额外捕获成本，且不含普通模型捕获。`seal_us` 是 drain 封装耗时；它属于 collect 区间，两者不能相加。低频日志只展示采样帧，不能当全区间 P95。旧 readback 周期错位盲区仍在本轮范围外。

## 验证

新增真实累积/适配器回归覆盖输入数组被修改、selector 选择性标记、全部材质字段保持、片段顺序、交错 owner、首个 transform、末个纹理元数据、缺失纹理、drain 消费、begin/endWorldDraw、clear、后续帧不污染前帧及封装后拒绝追加。

1000 个双三角片段：最终 float payload 296000 B；新累积器实际复制统计 983168 B；旧 append 前缀合并公式 148147704 B（不包含旧 retag 额外复制）。该输入的合并复制量约下降 99.34%，属于算法/复制量测试，**不是游戏 FPS 或实际耗时测试**。

以下通过：modelCaptureBatchTest、customEntityGeometryTest、itemModelGeometryAdapterTest、dynamicModelChunksTest（24593 triangles）、lightingLogicRepairTest、dynamicUploadBatchTest、rayTracingFrameTimingContractTest、jar；`git diff --check` 通过。未修改 GPU shader，无新增 GPU 运行验证。日志 `/tmp/rt-model-batch-tests.log`。

## 部署与测量限制

已原子替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，构建与实例 SHA-256 一致：`2ab6e907b232b94b7e3899ae3cfe8de06c2d6981545be8b9cdc9bd3d552899fe`。

替换前备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261008-002834-model-capture-batch/rtest-0.1.0.jar`。本轮前源文件备份位于 `tmp/model-batch-before/`。

最新测试游戏在 00:19:12 正常退出。本轮尚无新版本同场景运行耗时或分配记录。需重启实例，在相同分辨率、模型、相机、NRD/ReSTIR 等设置下取得新日志，并重采 JFR；不能把 CPU 分配优化换算成此前 31.586 ms trace 的改善。后续 GPU 优化需取得 RT/VALU、VGPR、scratch、active-lanes 等计数后再定位，不用全局强制 opaque 或删除同步来追求表面收益。
