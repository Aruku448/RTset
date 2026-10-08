# Windows RT 启动、性能与 DEVICE_LOST 调查（2026-10-08）

## 当前结论

**尚未修复所有 Windows GPU 崩溃，也未确认驱动是根因。** 本轮修正了明确的跨平台兼容问题，并加入可复现的诊断开关。AMD 上的短期改善不等于 NVIDIA 或所有 Windows 实例稳定。

Linux RADV 与 Windows AMD Vulkan 是不同实现；跨驱动差异可能暴露代码中的规范问题，也可能来自驱动本身。没有运行时 validation / 故障命令证据，不将 DEVICE_LOST 直接等同于驱动缺陷、TDR、显存不足或某个着色器错误。

## 已保留的代码修正

1. **Vulkan uint32 上限**：LWJGL 返回 Java int，UINT32_MAX 表现为 -1。大气能力检查改用无符号比较；真实能力不足时记录限制并回退到非 LUT RT 路径，而不是直接阻止整个 RT 启动。
2. **AS 输入地址对齐**：Windows 实测 TLAS 输入地址仅 8 字节对齐，触发 `AS input address is misaligned ... alignment=16`。AS build input 分配显式请求 16 字节对齐。
3. **SBT 起始地址对齐**：原代码只保证记录步长，没有显式保证缓冲起始地址。新增 aligned allocation，按 shaderGroupBaseAlignment 分配并检查实际地址。修复后仍有 DEVICE_LOST，不能称其为已确认的崩溃根因。
4. **TLAS 对照开关**：`config/rtest-audit-client.toml` 新增 `forceTlasBuild`，默认 false。true 时通过共同 recorder 将 TLAS UPDATE 改为完整 BUILD，保留输入、分配、场景更新频率及 BLAS 工作。默认行为在 Windows/Linux 均不自动改变。
5. **启动计时**：记录核心 RT shader 编译、驱动 RT pipeline 创建、SBT 地址/对齐及诊断配置，区分 CPU 捕获、shader 编译和驱动管线等待。
6. **Windows 哈希校验**：core.autocrlf=true 将固定来源文本改成 CRLF，导致完整性校验失败。恢复 reviewed LF 字节并添加 scoped .gitattributes；未改锁定哈希、未放松校验。七个 SPIR-V 二进制原本就与锁定记录匹配。

以上对齐/无符号处理适用于所有 Vulkan 驱动，不使用仅对 Windows 生效的错误规范假设；Linux 原生 NRD 库保留不变。此前已提交的 Windows NRD bridge 本轮没有重新替换。

## 实测与反馈

| 设备 / 测试 | 观察 | 能得出的结论 |
| --- | --- | --- |
| RX 7800 XT / Windows AMD Vulkan 9.2.10.395 | 修正 uint32、输入/SBT 对齐后仍 DEVICE_LOST；无 RTSS/OBS 钩子的运行也失败 | 单靠对齐修正或去掉钩子不能消除故障 |
| RX 7800 XT / primary_material | trace 0.128–0.202 ms，仍在区块发布后 DEVICE_LOST | 高光照负载并非复现必需条件；发布相关性不是因果证据 |
| RX 7800 XT / forceTlasBuild=true | 用户反馈不再崩溃；恢复 baseline 后运行数分钟，日志中未见 DEVICE_LOST | 支持隔离 UPDATE 路径，但不是永久稳定或驱动缺陷证明 |
| RX 7800 XT / baseline + 完整 BUILD | 854×480，GI=4、太阳采样=4、native_aa；trace 133–199 ms，TLAS 步骤约 0.19–0.24 ms，NRD约 0.4–1.1 ms | 直接构建耗时不是主瓶颈；不能排除构建质量对后续 traversal 的影响 |
| RX 7800 XT / no_volume + 完整 BUILD | 用户反馈最近一次帧率正常 | 体积光路径值得优先调查；缺少锁定相机的同配置数值对照 |
| RTX 4050 Laptop / NVIDIA 572.83 | baseline / UPDATE 失败；no_volume + BUILD 仍失败 | AMD 上有效的诊断组合不是通用修复 |
| RTX 4070 Ti SUPER / NVIDIA 572.70 | no_volume + BUILD，未见 OBS/RTSS DLL，仍 DEVICE_LOST | 不能把全部 NVIDIA 故障解释为捕获钩子 |
| RTX 4050 Laptop / NRD=false | 日志确认降噪调度关闭，no_volume + BUILD 仍 DEVICE_LOST | NRD 降噪执行不是复现必要条件；初始化仍存在，不能彻底排除所有 NRD 资源影响 |
| RTX 4050 Laptop / primary_material + BUILD + NRD=false | 配置确认生效，进入 RT 约 5 秒后 DEVICE_LOST；没有 GPU 分阶段完成样本 | 复杂光照、体积光和 NRD 调度均非复现必要条件；仍保留材质/几何与后处理路径 |

NVIDIA 的 RawOutput (1)–(4) 日志中，GPU 故障首先以等待超时或 `VK_ERROR_DEVICE_LOST: Failed to wait for semaphore` 检出；之后尝试原版准备/清理又出现 `PreparedFrame already in use`。后者是故障处理路径的次生异常，不应作为 GPU 故障的首要原因。检测点不能确定哪条 GPU 命令先失败。

RTX 4050 报告仍有 OBS graphics-hook64.dll；RTX 4070 Ti SUPER 报告未见。没有实际 Vulkan validation、同步验证或 GPU fault address，不能声称符合全部运行时规范。

## 启动耗时与 Linux 对比边界

Windows baseline 驱动管线创建有 8 ms 与 33.5 s 的不同样本；shader 编译约 2.1–2.2 s。缓存、variant、场景、相机没有被完全控制，不能从这些样本直接计算跨平台提速比例。

用户报告 Linux 在同配置、分辨率下低于 40 ms，是继续调查平台差异的重要线索；本轮未独立取得 Linux 同一场景的配对采样。早期 Windows 为 GI=1 / balanced FSR，后来为 GI=4 / native_aa / 太阳采样4，不能混作同配置基线。

## 手动部署与诊断配置（无需安装脚本）

Minecraft 26.2、对应 NeoForge、Java 25，最新 Mod JAR 放实际实例的 mods，仅保留一份。实际实例 `config/fml.toml` 设置 `earlyWindowControl = false`；启动器**游戏参数**加 `--graphicsBackend vulkan`。完全重启，进入世界按 F8。

`config/rtest-audit-client.toml`：

```toml
# 正常渲染基线；不是所有 Windows 设备的稳定保证。
rayCostAuditProfile = "baseline"
forceTlasBuild = false
```

对照：forceTlasBuild=true 隔离 UPDATE；no_volume 保留表面光照/GI但去掉体积光；primary_material 简化到主射线材质。后两者改变图像，不是正式画质预设或最终修复。`config/rtest-client.toml` 的 nrdEnabled=false 仅用于隔离降噪调度。每次只改变一个因素、先备份配置，新实例不会自动继承旧实例的设置。

## 已完成的验证

- vulkanUnsignedLimitTest：包括 UINT32_MAX、最高位为1、阈值相等、低于阈值。
- accelerationStructureSynchronizationTest：源码契约覆盖访问范围、AS/SBT allocation 与共同 TLAS 诊断 recorder；不是 GPU runtime validation。
- playerBlasCommandTest：原生 BUILD/UPDATE 命令结构契约。
- nrdNativeContractTest：Windows ABI 通过，32 dispatches；不是 GPU 执行测试。
- rayTracingCostAuditTest：15 profiles、baseline identity、reservoir consistency、105 SPIR-V modules通过；不是 GPU计时或稳定测试。
- verifyPrimeAtmosphereResources 与 jar 在不跳过校验的情况下通过。

未声称完整测试套件、所有设备稳定性或运行时 validation 通过。本轮提交不包含原始日志、用户信息、release JAR 或新安装/启动脚本。

## 下一步

停止仅靠降低画质排查。优先获得失败的 NVIDIA 实例的 Vulkan validation（含同步验证）或命令级诊断证据，审查描述符/缓冲边界、AS 输入和生命周期、共享 graphics queue 提交，以及 RT 到后处理/显示复制的同步。必须区分 GPU 先故障与 PreparedFrame 清理期异常。

在实际 GPU 上建立同场景配对复现，再缩小单项 recorder/shader/resource 改动。保留 Linux 默认行为及数值契约，不把延长 TDR 超时、重写哈希记录或永久关闭效果作为解决方案。
