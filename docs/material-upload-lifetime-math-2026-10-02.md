# 材质上传与资源退役：数学检查及优化（2026-10-02）

## 范围与边界

检查实际 `RayTracingMaterialBuffer.Layout`、SSBO 写入/flush，以及 `RayTracingVulkanPass.updateGeometry` 的失败路径和动态 BLAS 退役。保留现有 fence、barrier、世界/资源失效、捕获预算和渲染质量；不修改 shader 或配置。本次测试是 CPU 数学、Java 写入和 source contract，不是 GPU 像素、驱动故障注入或整帧性能测量。

## 分配器修复和算法

原始 `Layout.update` 仅收集“本次删除”的空洞，丢失以前快照的空洞及拆分余量。红灯：A=4、B=1；删除 A 后新增 C=2，C 错放在 5，高水位由 5 增至 7。这是保留容量浪费，不等于已证实的 Vulkan 泄漏。

现在不可变快照保存空洞列表；更新先合并相邻区间，分配后留下余量，最后一次性移除零容量区间并裁掉尾部空洞。旧快照不被修改；保留 span 的容量而不是把未使用的保留尾部误当空洞。

- 少于 64 个空洞：地址顺序线性 first-fit。
- 至少 64 个空洞：以地址排序叶子的最大容量索引，下降时优先左子树，严格保留最左 first-fit。拆分余量仍在原区间内，不会改变相对排序，不再每次拆分重新排序。
- 索引构建 O(F)，单次查找/更新 O(log F)。更新约为 O(S + F log F + U log F)，替代原反复排序/扫描约 O(S + U F log F)。S 为 section 数、F 为空洞数、U 为需重新分配数；HashMap 成本按平均情况计。
- 额外内存：不可变空洞记录和每次构建临时最大容量数组；不是持久 GPU 缓存。相邻区间合并可能改变原先未合并算法的地址，这是利用真实可用区间的正确性修复。

`MaterialLifetimeMathTest` 执行真实 Layout，包含跨快照复用、拆分余量、相邻合并、旧快照不变，以及 300 轮独立 occupancy-bitmap first-fit 对照。

### 合成基准

同一 Java 25、堆 1 GiB，输入预先构造，5 次预热/9 次采样；4000 旧 section、6000 新 section、2000 个空洞。旧类由优化前 JAR 放在 classpath 首位运行，无需回退工作区文件。

| 配对运行 | 中位数 | 最小/最大 |
|---|---:|---:|
| 原算法 | 7.936 ms | 6.725 / 10.317 ms |
| 新算法 | 5.273 ms | 3.353 / 15.773 ms |

中位数约 1.50×，但存在 JIT/调度波动；另一次新算法运行中位数 3.946 ms，不挑选最好结果作为结论。两者地址哈希均为 `309704064`，高水位均为 `64000`。这是分配器合成数据，不意味着上传、发布或 FPS 提升 1.50×。

原始日志：`/tmp/rtest-material-math-before.log`、`/tmp/rtest-material-math-comparison-{baseline,optimized}.log`；源/JAR 快照在 `/tmp/rtest-material-math-before/`。

## 上传覆盖及区间合并

稀疏 section 写入原本登记了 section 范围，但高水位变化后的两个共享 placeholder 写入没有登记。CPU 红灯在第一个 placeholder 字节 224：`placeholder write missing from visibility flush at byte 224`。现有后续整 allocation 清零/flush 在部分调用路径可能掩盖此缺口，不能据此声称已经观察到 GPU 材质损坏。

现有修复覆盖正向/回滚 placeholder、所有静态 span 和动态清零：

- 显式 sparse 模式允许零写入、零 flush，不把空范围列表误当整 allocation dirty。
- 按字节 offset 排序，合并重叠和紧邻区间，保留真正的空隙；VMA 继续处理 non-coherent atom 对齐，不移除可见性操作。
- 256 个相邻材质区间合成测试由 256 个范围变成 1 个，覆盖字节完全一致；不是 GPU 耗时测量。
- 范围以 `offset <= size - length` 检查，避免 end 溢出；合并用 checked addition。其它未显式选择 sparse 的映射仍默认整 allocation flush。

`MappedUploadMathTest` 使用真实稀疏 writer 与真实 `Mapped` 范围登记，搭配 Java-owned ByteBuffer，无 VMA allocation/flush。测试覆盖占位写、反向回滚、无写入、越界，以及 300 组独立 bitmap 区间 union 对照。共享占位材质的实际数组长度与预留 item 材质容量不同，测试只要求 flush 实际写入字节，不错误要求全部预留 padding。

## 发布回滚与释放

原失败路径先改 scene descriptors，后续 traversal 写入失败时没有恢复 scene descriptors；traversal dirty 标记又在完整写完后才置位，部分写失败不能恢复。

`RtResourceRollback` 在实际 mutation 前登记补偿，按逆序恢复，并在销毁候选资源之前执行。覆盖 scene/traversal descriptors、部分写入的复用 traversal buffers 和复用 instance inputs。instance 回滚恢复静态输入后使 TLAS 进入待重建状态，下一次 dispatch 强制重新写动态 slot，而不是继续相信被清零的动态输入。清理发生异常仍尝试其它清理，原异常保留、清理异常作为 suppressed；恢复失败并不保证 pass 可继续使用，当前 smoke controller 会 teardown。

非动态 PBR 分支原来按容量足够就复用而不写新内容；现在仅内容相等才复用，变更用新的 immutable allocation，失败时旧内容不受影响。动态 PBR append-only 管理分支不变。

动态 BLAS 原来在“没有 pending fence”时也退役，不能证明旧 TLAS 已不引用它。现在 `RtBlasRetirement` 记录提交是否真的含 TLAS 构建：

1. 无 fence：不退役。
2. copy / BLAS-only fence：不退役旧 TLAS 引用。
3. 实际 TLAS 构建的 frame fence 完成：允许动态退役。
4. 增量构建：只有全部 BLAS 完成、TLAS 已构建且它的 fence 完成后，与静态 cache 同点退役。
5. timeout 保留原 pending fence；关闭仍走现有同步与最终资源清理。

这是一项保守延后释放的修复，不是已经复现或修好的 GPU device-loss 结论。尚需驱动验证 UPDATE 源引用要求、实体替换/消失、部分构建与 GUI 重放；不修改窗口尺寸。

## 验证

- `./gradlew test jar --offline`：54 tasks，成功。
- `MaterialLifetimeMathTest`、`MappedUploadMathTest`、`ResourceLifetimeMathTest` 及已有 `VulkanResourceLifecycleTest` 成功。
- Python capture-log tests：4/4 成功。
- `git diff --check` 成功。

`ResourceLifetimeMathTest` 测试真实补偿/退役 helper 的次序、错误汇总和状态转换，并用 source contract 确认 production wiring；不冒充真实 native descriptor 故障注入。独立只读复核未发现明确新增错误，未执行 GPU 验证。实机待验证：小块编辑与跨区块移动的材质正确性、PBR 更新、动态实体更换/消失、资源 reload/换世界及长期内存走势。

## 部署

确认 Minecraft 未运行且旧安装 hash 匹配后原子替换 JAR，配置逐字节与备份一致。

- 安装 SHA256：`1d5d2481428cf9747da6f0d7015d13d9013517fec9a2ec9ec75074ba1447afe4`。
- 备份：`/home/aruku/.minecraft/versions/RTest/backups/material-upload-lifetime-20261002-031726/`。
- 配置 SHA256（未改）：`9c96c8e220b7dcef042b924ab647acb7d035437f1c603c15857c2e438dd30077`。
