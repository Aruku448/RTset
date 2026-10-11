# ReSTIR 论文指导优化（2026-10-11）

> **文档状态：已归档；已解决项见下表/说明** · 归档日期：2026-10-11。
> 当前行为见 [实现说明](../../restir-implementation.md) 与 [后续实施记录](../../restir-follow-up-implementation-2026-10-11.md)。[归档索引](README.md)

## 归档结论（2026-10-11）

**本阶段已完成：** 重复 tape 合并、后续前缀 borrowed-only gather、主表面 area-light 共享、DI target 缓存。

**后续已解决：** 本阶段尚未共享的 sun/moon/sky NEE，以及后续审计发现的 UCW 加权 hit distance、resampling 中间溢出风险和 DI/GI 历史过宽绑定，均已有实现；数值修正仍受最终 float UCW 范围限制。单 donor gather 另设默认关闭的开关。

下文是首轮实施及当时编译证据。当前行为、最新 JAR 和剩余限制以维护入口及后续实施记录为准。

## 原始记录（保留当时语境）

本轮针对现有直接光 RIS 和随机种子后缀重放实现，减少重复目标评价、重复路径和 gather 开销。配置默认值及开关不变；ReSTIR 仍按原有 F9 设置启用。实现不增加持久 GPU 缓冲或 descriptor。

后续论文精读和代码审计见 [ReSTIR 论文续读与适用性核查](restir-papers-follow-up-2026-10-11.md)，其中记录了审计当时的 NRD 命中距离加权及优化候选；后续落地状态见[实施记录](../../restir-follow-up-implementation-2026-10-11.md)。

## 论文依据与适用范围

- Kettunen 等，[*Conditional Resampled Importance Sampling and ReSTIR*](https://research.nvidia.com/labs/rtr/publication/kettunen2023conditional/)，§§5.1–5.2 的条件 UCW、支持域与公式 14，以及 §7 的 final gather。公式 23 提供减少 canonical 后缀的方向，但本轮使用当前实现特有的完整随机种子支持域来重新归一化 MIS，**没有声称实现该公式的一般几何重连 roulette**。
- Lin 等，[*ReSTIR PT Enhanced*](https://research.nvidia.com/labs/rtr/publication/lin2026restirptenhanced/)，强调减少复用工程开销及处理重复样本。本轮只在单个当前前缀内部合并相同 tape 的计算；这不是论文的跨像素 duplication map，也不实现 reciprocal neighbor selection、footprint reconnection 或统一 DI/GI reservoir。

## 实现

### 完整支持域下的后续前缀 gather

接受的历史 reservoir 保存归一化种子域的 UCW。移位是相同 32 位种子的恒等映射，Jacobian 为 1；目标函数保持严格正值，因此旧样本在新前缀上保留整个种子支持域。候选集合根据已建立的支持交点、法线及历史有效性确定，早于当前后缀随机采样。

若接受 n 个历史来源，首个前缀仍计算：

```
F_first = (f(u_fresh) + sum_j W_j * f(u_j)) / (n + 1)
```

后续前缀在 n > 0 时计算：

```
F_later = sum_j W_j * f(u_j) / n
```

没有可用历史时使用 `F_later = f(u_fresh)`。所有前缀最后仍除以 gather 前缀数量。在现有条件 UCW 和前缀/后缀随机维度独立的假设下，每个历史 UCW 都积分同一个完整种子测度，所以两种估计器的期望相同；无需按前缀数放大首个 canonical 项。首个前缀持续生成 fresh tape 并更新下一帧 reservoir。动态、低粗糙度、透射、水下或无有效历史的支持前缀保留新路径回退。

后续前缀的样本分布发生变化，可能改变噪声及相关性；数学期望相同不代表每帧图像相同。如果未来加入几何重连或其它部分支持域 shift，必须重新设计此处的 canonical/MIS 策略。

### 重复 tape 合并

多个历史像素可存同一个种子，但带不同 UCW。相同当前前缀、当前场景及 tape 产生相同后缀函数值，因此合并为 `U_s = sum W_j` 后只评价一次 `f(s)`。MIS 分母仍使用合并前的来源数，避免把重复样本当作增加能量或减少积分域。RIS reservoir 的逐种子概率质量也保持一致；浮点求和顺序可能不同。临时队列直接保存种子及权重，重放时无需再次读取历史 reservoir。

若两个有限 UCW 的和溢出，则保留两个单独 replay 项。合并只减少相同 tape 的重复计算，不能据此声称降低跨像素相关性。不同 seed 即使偶然产生相同几何路径也不会合并。

### 共享主表面面光源结果

现有首跳 scatter 使用 gather-prefix index，主表面面光源采样及直接 RIS 随机维度不使用该 index。因此不同 gather 前缀之前会重复计算相同 light-tree 候选、目标函数、BSDF、RGB 透明可见性和 static-only 动态阴影差值。

现在 `AreaDirectSplit` 只在当前像素 invocation 内计算并缓存一次，之后各前缀使用相同结果。缓存包含无效或零贡献结果。它不跨帧保存，不借用旧遮挡；后续顶点阴影仍按当前场景追踪。太阳、月亮及天空的主表面计算仍沿用原路径。

直接 RIS 若历史灯光样本与当前选中样本的 emitter 和重心坐标完全一致，复用已算好的 target；每个来源仍独立保留其 UCW、接受计数和 reservoir 抽样。重投影中心与直接光几何半径也移出候选循环。

## 可以减少的工作量

下表假定每个前缀都接受 temporal 加两个 spatial 来源，未发生提前终止。数字是后缀函数评价次数，**不是毫秒或整帧提速**。

| 前缀数 | 旧实现 | 新实现，三个历史 seed 各不相同 | 新实现，三个历史 seed 相同 |
|---:|---:|---:|---:|
| 1 | 4 | 4 | 2 |
| 2 | 8 | 7 | 3 |
| 4 | 16 | 13 | 5 |

无历史时每个前缀仍评价一个新后缀。主表面面光源计算从每像素每前缀一次变成每像素一次。新增 private cache 和短权重数组可能增加寄存器/continuation 存储，实际收益必须以驱动和游戏 GPU 计时确定。

## 验证

- Java 25 编译全部 152 个主源码文件通过；随后 Gradle `compileJava` 和 `jar` 成功。
- LWJGL shaderc 3.4.1，以 Vulkan 1.2 和 performance 优化级别编译实际 raygen：关闭、直接光、后缀、两者同时启用，分别搭配 legacy/physical 大气，共 8 个变体全部通过。
- 上述 8 个模块全部通过 `spirv-val --target-env vulkan1.2`。
- 打包时 DLSS/Streamline 资源及七个 Prime 大气内核的现有构建校验通过；`git diff --check` 通过。
- 未运行测试套件、游戏内画质对照或 GPU 性能基准；尚不能确认等时间噪声、寄存器压力或整帧速度收益。

构建环境的 Gradle 远程解析/离线缓存不完整。本轮用 `build/restir-build-cache/` 下的临时本地 Maven 仓库提供已有依赖，并补齐官方 NeoForge universal JAR；跳过 `createMinecraftArtifacts`，复用已生成、版本匹配的 Minecraft 26.2 / NeoForge 26.2.0.84 artifacts。主源码由 Gradle 重新编译。没有为此改动项目构建配置。

使用的打包命令：

```bash
./gradlew jar -x createMinecraftArtifacts \
  -I build/restir-build-cache/offline.init.gradle \
  --offline --no-configuration-cache \
  -Dorg.gradle.java.home=/home/aruku/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 \
  --console=plain
```

产物为 `build/libs/rtest-0.1.0.jar`，包含工作区已有的其它未提交改动。本轮没有替换游戏实例 JAR；着色器编译产物位于 `build/restir-paper-optimization/shaders/`。
