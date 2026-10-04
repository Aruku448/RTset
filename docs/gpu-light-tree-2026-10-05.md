# GPU 光源树构建（2026-10-05）

## 实现

- `gpuLightTreeEnabled` 默认开启。少于 1,024 个有效发光三角形保留原 CPU 路径。
- CPU 提取发光三角形与功率代理，生成归一化 10 位/轴 Morton 编码，并执行四趟稳定基数排序。排序为 O(N)，同编码维持源顺序。
- 将 Morton 顺序按 DFS 填入完全二叉堆的叶槽，保证各子树对应连续空间编码区间。节点数为 `2N−1`，适用于非 2 次幂 N，没有零功率填充叶。
- GPU 并行生成叶 AABB、功率、父索引、正向链接和反向叶表；随后按堆深度自底向上归约内部 AABB 与功率。CPU 不再递归扫描/排序子树，不生成内部节点对象。
- 保持 8 字头、8 字节点、16 字 emitter、相邻左右子节点、材质查找和 reverse MIS ABI。采样与 PDF 均使用实际生成的树；不同树划分可改变噪声分布，但不改变估计器的目标能量。

## 发布和生命周期

- compute 管线、描述符池与着色器由 RT pass 持有并复用，关闭 pass 时释放。
- 构建前 host→compute 屏障；全局归约层之间 compute→compute 屏障；顶部工作组内使用 shared barrier；结束 compute→RT 屏障。
- transient command 显式 `encoder.execute(command)` 后提交，等待 fence 完成才发布 RT 描述符。
- GPU seed 与设备上的完成树不是同一内容。变更时分配新缓冲，不使用 CPU seed 对 GPU 结果进行页差分写入或回滚。完全相同的 seed 可复用已完成设备缓冲。
- 初始化/构建失败时，该 pass 停用 GPU 构建并以相同堆拓扑在 CPU 上归约。超时或清理失败先等待队列，避免写入/释放仍被 GPU 使用的缓冲。
- 可在 `rtest-client.toml` 设置 `gpuLightTreeEnabled = false` 回退原 CPU 空间划分；下一次 scene merge 切换构建模式。

## 验证

- `./gradlew check jar --offline` 全部通过（60 个任务）。
- Java 检查 1,024、1,025、1,537、2,047、2,048、2,049、5,003 个光源：层间依赖、每个节点边界/功率、材质映射、反向叶映射、完整概率质量及正反向 PDF 一致。
- Shaderc 以与运行路径相同的 Vulkan 1.2 目标编译 compute SPIR-V。
- 独立 Vulkan 硬件校验：RX 7800 XT / RADV，执行上述 Shaderc 二进制；5,003 个光源、10,005 个节点，190,112 个数据字与 CPU 参考结果逐字相同，差异为 0。校验包括保持不变的 emitter 与材质区。
- 80,000 个随机分布发光三角形，4 次预热、9 次采样：原 CPU 完整建树中位数 **58.923 ms**，新路径 CPU 准备中位数 **9.780 ms**（约 6.02 倍）。该测量不含上传、GPU 执行或发布等待，不能解释为 FPS 提升。

结果记录：`docs/profiling/2026-10-05-gpu-light-tree.json`。

### 重现独立 GPU 校验

```bash
./gradlew gpuLightTreeMathTest -Pgpu_tree_dump=/tmp/rtest-light-tree --offline
cc -O2 -Wall -Wextra -Werror tools/gpu_light_tree_smoke.c -lvulkan -o /tmp/rtest-gpu-light-tree-smoke
/tmp/rtest-gpu-light-tree-smoke /tmp/rtest-light-tree.spv /tmp/rtest-light-tree.seed /tmp/rtest-light-tree.expected
```

## 仍需游戏测量

发光体提取、材质打包和上传仍在 CPU。GPU 建树在发布时等待 fence；本次没有声称消除了同步卡顿。新增 `RTest GPU light tree` 日志记录 `build_submit_wait_ms`（CPU 总耗时，非 GPU timestamp），可与现有 `light_pbr_upload_ms`、scene merge 和帧时间联合比较。

Morton 编码是空间近似，树划分不同于原最长轴二分；仍需实际灯光场景检查采样方差和游戏帧时间。此处的独立 Vulkan 校验不等于 Minecraft 完整渲染与崩溃回归。

## 部署

已在客户端退出时替换 RTest mod，配置开启 GPU 建树。

- JAR SHA256：`f5afa45ff1ae79e8765398d11f52f7da1962d2f2624eaa7d01d9c6045ea62ad7`
- 旧 JAR 备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-gpu-light-tree-20261005-020630`

后续的输入上传裁剪、顶部共享内存归约和临时映射优化见 `docs/gpu-tree-followup-optimization-2026-10-05.md`。
