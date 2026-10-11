# ReSTIR 续读审计后的实施记录

> **文档状态：阶段实施与编译记录** · 2026-10-11。[文档索引](README.md) · [当前维护入口](restir-implementation.md)

依据 [续读与优化审计报告](history/restir/restir-papers-follow-up-2026-10-11.md)，本轮实施以下五项。论文依据沿用该报告的官方原文与章节定位；本记录描述实际代码，不代表完整移植 Enhanced ReSTIR PT。

## 1. 几何 hit distance 与 UCW 分离

- 每个独立 gather prefix 只记录一次几何距离，不乘 reservoir UCW、MIS 权重或 donor 数。
- diffuse 取有效 diffuse proposal 的正距离平均；specular 取有效 specular proposal 的最小正距离。
- guide 按首跳实际选中的 BSDF proposal lobe 分类；辐射量仍按原有 BSDF 分量拆分，两者用途不同。
- 缺失 lobe 的距离保持 0，移除以总光照 DI emitter 距离填充 diffuse/specular guide 的回退。

这部分也改变普通 PT 的 NRD guide 元数据，不只影响 ReSTIR。当前 NRD hit-distance reconstruction 默认 `area_3x3`，用于重建缺失距离；手动关闭重建时，稀疏 lobe guide 的画质需另行观察。未强制修改用户配置。

## 2. 首跳太阳、月亮、天空 NEE 共享

符合 suffix reuse 条件的静态、不透明、非水下粗糙主表面，在一次像素 invocation 内共享首跳 sun/moon/sky NEE 的 diffuse、specular、带符号动态遮挡残差及遮挡标志。

首个 prefix 仍执行当前帧采样与可见性查询；后续 prefix 复用结果。既有 area-light cache 独立保留。采样数、开关和 continuation MIS 仍沿用原路径；不符合条件的主表面继续逐 prefix 求值。

该缓存没有跨帧复用可见性。它减少符合条件像素的重复首跳工作，但增加私有 shader 状态，并改变部分浮点加法次序；寄存器占用与实际 GPU 收益尚未测量。

## 3. 指数缩放的 reservoir 权重累加

DI 与 suffix resampling 现在以 `mantissa × 2^exponent` 表示累加质量。分别分解 target、UCW、domain mass，再对齐指数累加，避免先形成 `target * W` 或大线性和造成的中间溢出。

选择概率及最终归一化公式不变：

```text
mass_i = target_i * W_i * domainMass_i
P(select i) = mass_i / sum(mass)
W_selected = sum(mass) / (target_selected * domainCount)
```

fresh DI 的候选均值先以缩放形式保留，再与历史合并；suffix 同样处理。没有新增 UCW cap。

持久 reservoir ABI 仍存线性 float UCW。最终 UCW 超出可表示范围时，该 reservoir 失效，而不是把无穷值传播到下一帧。这是明确的有限精度失效路径，不能宣称为无偏的任意 HDR 溢出解决方案。浮点舍入、极小质量下溢与 `radiance * W` 溢出仍有边界。

## 4. DI 与 suffix 历史独立失效

历史有效性改为 DI/GI 两个 bit，沿用原 32 字节参数块。

- 相机切换、全局 reset、参数/模式变化、buffer 重建、scene generation 变化、坐标原点重定位仍清除历史。
- 同 generation、同原点的增量几何发布可以保留 seed-only suffix 历史；下一帧仍按当前场景重新追踪、按当前支撑几何筛选。
- DI 另外要求上传的 light-data words 完全相等。发光体数据变化清除 DI，允许 suffix 历史继续参与。
- 仍每八帧全局截断 reservoir ancestry；没有增加按选中样本年龄进行的筛除。

suffix 保留依赖当前 identity seed replay 的共同完整支持域。将来引入局部支持的几何重连接时，必须重新审查这一条件。

## 5. 可选单 donor gather

新增独立 ReSTIR 配置 `restirSingleDonorGather=false`，F9 中可切换，中英文提示已补齐。既有 ReSTIR 开关及该新开关的默认值均未启用。

首个 prefix 继续 canonical + 全部 donor gather，用于生成新 reservoir。后续 borrowed-only prefix 可从合并后的 `u` 个 seed group 中均匀选一个，贡献乘 `u`，分母仍为原始 donor domain 数 `n`：

```text
group g: combined UCW U_g, selection q_g = 1/u
estimate = U_g * f(seed_g) / (n * q_g)
```

这保持条件期望，但通常增加方差。没有历史或不支持复用时仍执行 fresh canonical suffix。以三个互异 donor、两个 prefix 为例，suffix 求值次数从 `4 + 3 = 7` 降到 `4 + 1 = 5`；这是算法计数，不是实测耗时。如果 donor 已合并为一个 group，则没有额外节省。

## 诊断数据与 ABI

suffix reservoir 的首个 vec4 仍以 x 存 seed bits、y 存 UCW。首个 prefix 满足复用条件时，此前未使用的 z/w 记录其诊断信息，无新增 buffer、descriptor 或 CPU readback：

| 分量 | 内容 |
|---|---|
| z | resampling 累加质量的二进制指数 |
| w bit 0 | 数值失效标志 |
| w bits 4–7 | 接受的原始 donor domain 数 |
| w bits 8–11 | 合并后的 seed group 数 |
| w bits 12–15 | 去重后的有效坐标 donor 读取次数 |

w 是可精确表示的整数值，按 float 存放，检查时先转整数再解码；不是 `floatBitsToUint`。这些值供 GPU buffer 检查使用，未生成实时统计面板，也未取得运行中的计数。

## 编译记录与范围

本轮完成以下构建及文档检查：

- 152 个 main Java 源文件编译通过；最终 Gradle `jar` 构建成功。
- 使用最终 Gradle classes，通过 shaderc 3.4.1 编译实际生成的 8 种 raygen：ReSTIR mode 0/1/2/3 × legacy/physical atmosphere，目标 Vulkan 1.2、performance optimization。此处记录编译结果，未另行运行 SPIR-V validator。
- 中英文资源 JSON 可解析；docs 相对 Markdown 链接无缺失；`git diff --check` 通过。
- 输出 `build/libs/rtest-0.1.0.jar`，102,480,109 字节。
- JAR SHA-256：`4b4dc90e4d936bfa1c6d565932f057e25920bebe9ff80189fc8e37940bf1577f`。

未新增、修改或运行测试，未启动游戏，未部署到游戏目录，未进行固定相机画质对照或 GPU benchmark。工作区原有其他修改保留；该 JAR 包含当前工作区的生产代码，不能视为仅含本轮 ReSTIR 改动的独立产物。

构建沿用本机已有、版本匹配的 Minecraft 26.2 / NeoForge 26.2.0.84 产物及临时 offline init script，跳过 `createMinecraftArtifacts`。这不是全新环境在线依赖生成验证。

本轮未实施 world-space donor 索引、几何重连接、当前帧 reciprocal MIS、ReSTIR PG、重复度反馈或 radiance cache。它们需要额外数据结构或估计器设计；仍可从续读报告的后续方向继续推进。
