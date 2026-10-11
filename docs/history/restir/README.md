# ReSTIR 归档与事项状态

归档日期：2026-10-11。这里保留旧实现对照、审计推导和阶段编译证据；文内源码行号、默认值和“当前”均属于原记录时点。

当前入口：[ReSTIR 实现](../../restir-implementation.md) · [最新阶段实施记录](../../restir-follow-up-implementation-2026-10-11.md) · [全项目文档索引](../../README.md)

## 已归档记录

| 文档 | 已解决或完成 | 保留的边界 |
|---|---|---|
| [作者实现对照（2026-10-07）](restir-author-implementation.md) | 前缀续追、首跳工作共享、后续 prefix 减少 canonical | 不是作者 hybrid shift 或公式 23 的完整移植 |
| [性能实现检查（2026-10-07）](restir-performance-review.md) | 重复主射线/照明、DI target 重算、DI/GI 历史绑定 | 显存开销仍在，旧日志不能证明新性能 |
| [首轮论文优化实施（2026-10-11）](restir-paper-optimization-2026-10-11.md) | tape 合并、borrowed-only gather、area cache、DI target cache | 保留当时构建证据，最新行为见后续实施 |
| [论文续读审计（2026-10-11）](restir-papers-follow-up-2026-10-11.md) | hit distance、主表面 NEE、缩放累加、历史拆分、可选单 donor | 部分诊断与架构建议仍未实施 |

## 尚未完成的事项

- 固定场景、相机和配置的 GPU 耗时、等时间画质、时域稳定性对照；未取得寄存器/stack 压力数据。
- 运行中的 donor 接受率、重复率、reset 原因、fresh 入选率、年龄及回退统计。已有 spare-lane 诊断不等于完整遥测。
- 世界空间支持前缀索引；尚未量化当前屏幕邻域搜索损失。
- 几何重连接与 hybrid shift、双向支持域/Jacobian、当前帧 reciprocal spatial pass、prefix GRIS、紧凑工作队列。
- ReSTIR PG、duplication feedback 与 reservoir 存储压缩。研究建议尚未成为实现承诺。
- 最终线性 float UCW 和 HDR 辐射量的可表示范围限制。缩放累加解决中间范围问题，不证明完整渲染器无偏或有限方差。

归档不代表上述事项全部解决。单 donor 模式默认关闭，启用后的方差及 NRD 表现仍需实测。
