# GPU 建树后续优化（2026-10-05）

## 本轮修改

1. **只上传 CPU 输入**：GPU 模式下，仅写 8 字头和 emitter/材质查找区。节点、正反向链接、反向叶表由 compute 完整生成，避免先在 host 写零再由 GPU 覆盖。原 CPU 路径仍上传完整数据；CPU 回退也会补写完整树。只 flush 实际 host 写入范围。
2. **顶部八层合并归约**：最低层含 128 个节点的顶部 255 节点，合并到单个 128 线程工作组；使用 8,160 字节 shared memory 保存边界/功率，各层之间全部线程统一执行 barrier。底部仍按层全局 dispatch，并在进入顶部之前建立 compute 内存屏障。避免七次额外 dispatch/全局屏障，且顶部父节点不再从 SSBO 读取子结果。
3. **省掉临时材质映射**：GPU 打包直接写最终材质索引表，不再分配和填充同长度的中间 `int[]`。少于 1,024 个 emitter 的 CPU 回退仍生成原格式的索引表。
4. 日志新增 `dispatches` 与 `host_upload_bytes`，保留 `build_submit_wait_ms`，用于下一轮游戏发布开销测量。

## 验证

- 实际上传函数测试：先把整块映射区域填入非零哨兵，核验 host 写入与 flush 仅覆盖输入。旧完整上传实现会在节点字 8 触发断言，优化后通过。
- 新调度的 CPU 依赖检查覆盖 1,024、1,025、1,537、2,047、2,048、2,049、5,003 个光源，验证每一层的子节点先完成。
- CPU 小树回退（1、31、1,023 光源）与关闭 GPU 时的完整 packed words 相同。
- 独立 Vulkan 测试也以非零值预填 GPU 输出区，只上传上述两个输入范围；使用 Shaderc 生成的实际 SPIR-V，在 RX 7800 XT / RADV 上读取全部数据，1,024 与 5,003 个 emitter 均与 CPU 参考逐字一致，差异为 0。包括材质表、forward/reverse 链接与反向叶表。
- 顶部聚合顺序保持左子功率加右子功率、原 min/max 运算，正反向采样 PDF 无需修改。
- `./gradlew check jar --offline` 通过（60 个任务）。

## 对照测量

| 项目 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 独立 GPU 建树 timestamp 中位数，5,003 光源 | 0.077920 ms | 0.061000 ms |
| 同场景 dispatch 次数 | 14 | 7 |
| 80,000 光源、同数量材质查找项的 host 逻辑上传量 | 12,159,992 B | 5,440,032 B |
| 中间材质映射临时内存 | 材质 high-water × 4 B | GPU 路径不分配 |

GPU 统计 5 次预热、31 次测量，耗时减少约 **21.7%**。上传字节减少约 **55.3%**，实际非一致性 flush 的边界对齐、缓存与显存带宽不等于上述逻辑字节量。

另外一轮完整 CPU 对照（80,000 光源，4 次预热、9 次测量）：旧 CPU 树中位数 52.768 ms，新 GPU seed 准备 8.474 ms。这是当次同进程对照，不是游戏帧时间，也不能用跨运行的 9.780→8.474 ms 单独证明临时数组优化的收益。

详细记录：`docs/profiling/2026-10-05-gpu-tree-followup.json`。

## 限制

本轮仍在发布时等待 GPU build fence，保留 CPU emitter 提取和上传。独立 GPU timestamp 不含 Minecraft 的发布、描述符更新或完整 trace；游戏帧率和卡顿需要客户端运行后的日志核验。保留既有 CPU 回退和旧资源退休逻辑。

## 部署

客户端退出后已替换 RTest mod。

- SHA256：`f03fc0e83c948fa7ddf4351190b1f5df57da255061f3d37e45695665210e9d3a`
- 旧版本备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-gpu-tree-followup-20261005-022636`
