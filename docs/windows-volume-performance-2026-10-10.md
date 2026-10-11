# Windows 体积光／体积雾性能调查

> **文档状态：历史测量/排障记录** · 整理日期：2026-10-11。
> 日志、参数、安装 hash 和结论只属于文中日期及环境，不能当作当前基准或跨平台根因。 [分类索引](README.md)

## 结论与边界

Windows RX 7800 XT 的历史记录强烈指向 **raygen 中的体积路径**，不是大气 LUT 构建或最终雾合成。最值得优先验证的是：体积积分与 GI/表面光照共用大型 raygen，循环中的 `traceRayEXT` 和存活数组使不同驱动的 continuation 栈、寄存器分配、spill 与 occupancy 产生巨大差异。

这是有代码与计时依据的首要假设，**尚未取得 Windows ISA、VGPR、scratch 或栈对照，不能宣布根因已证实，也不能泛化到所有 Windows/NVIDIA 显卡**。本轮读取现有日志、配置、部署 JAR 和已有 SPIR-V；没有启动新基准、切换游戏配置、修改生产渲染代码或替换实例。

## Windows 原始证据

实例：`/mnt/windows/Program Files (x86)/mc/DS2/.minecraft/versions/26.2/`。

日志报告 RX 7800 XT，Vulkan 1.4.349，AMD proprietary driver 26.6.2 (LLPC)。下表按日志中的实际 RT extent、audit profile、ReSTIR mode 分组，按初始化会话与 frame 去除重复打印。

| 记录 | RT extent | profile | 独立样本 | trace 中位数 / 范围 ms |
| --- | --- | --- | --- | --- |
| 2026-10-08-5.log.gz，20:37–20:38 | 854×480 | baseline | 5 | 169.459 / 133.298–198.531 |
| 2026-10-08-6.log.gz，20:41–20:42 | 854×480 | no_volume | 34 | 5.9615 / 4.440–8.080 |
| latest.log，另一批会话 | 854×480 | no_volume | 130 | 4.0255 / 1.083–8.454 |

前两批均为 mode=0、forceTlasBuild=true，体积配置日志均为 enabled=true、strength=1、fogDensity=1、quality=2。`no_volume` 在编译前替换源码，不会把配置日志的 enabled 改为 false。

baseline 的 LUT 区间中位数 1.198 ms、TLAS 0.222 ms、NRD 0.498 ms、aerial composite 0.018 ms；no_volume 相应 TLAS 0.206 ms、NRD 0.4915 ms、aerial composite 0.016 ms。trace 才是异常耗时的主要所在。

baseline/no_volume 中位数相差约 28.4 倍，但没有固定同一相机、时刻、几何、频率及历史配置快照，baseline 只有五个样本。这个比值描述日志现象，不能当作算法提速承诺或纯体积独占耗时。

本次提取的摘要保存在 `tmp/profiling/windows-volume-20261010/log-summary.json`；只保留驱动信息与计时统计，不复制启动参数、账户或鉴权信息。

## Linux 对照

已有固定场景消融见 `ray-tracing-cost-audit-2026-10-07.md`：Linux RX 7800 XT，854×1074，quality=2，baseline trace 19.746 ms，no_volume 15.119 ms，边际减少约 4.627 ms。因此 Linux 上也有成本，只是没有 Windows 记录中的异常放大。该 Linux 场景、分辨率、介质密度与 Windows 不完全相同，不计算跨平台倍率。

当前 Linux 日志报告 Mesa RADV 26.2.4-arch3.1，实际 RT 569×320，quality=3、fogDensity=4.501458，不能直接与 Windows 854×480 对比。FSR 输出分辨率和 RT 输入分辨率必须分开。

## 部署版本核对

Windows 硬盘 JAR SHA-256：`3b07cad89987007db9e9db9bbc168828953ed54bd27a0e6a407b81fac77dd7c9`。此文件不证明所有历史日志均由同一 JAR 生成。

部署 JAR 的 `RayTracingAtmosphereSegmentShader.class` 和 `RayTracingShaderStages.class` 与当前构建逐字节相同。Raygen 整个 class 有差异，但从 class 常量提取后比对，物理相机积分、局部体积透射／发光体积分、legacy 相机积分函数与当前构建一致；此前收窄介质临时状态作用域的改动也已经存在。因此不能把“再加一次作用域括号”或“开启 shaderc performance”作为新修复。

## 算法上的成本来源

### 1. 完整分辨率、多次 RT 调用与跨调用状态

`RayTracingShaderRaygen.java` 的 `integratePhysicalAtmosphereSegment` 按质量执行 4/8/16 步。太阳与月亮分别查询 RGB 可见性。quality=2 时最多 16 条天体阴影射线，quality=3 时最多 32 条；光源／地平线／零贡献判断会降低实际数量。

积分循环跨射线调用仍需要太阳和月亮相函数数组各 `vec4[5]`、阴影权重 `uint[16]`、谱透射、累计散射、积分几何及其他输出状态。局部作用域已结束当前步的 medium/table 临时值，但不能自动消除下一轮还要使用的状态。私有数组和动态索引是否转成寄存器、栈或 scratch，取决于驱动最终代码。

### 2. 介质和多重散射查询

相机每步调用 `physicalAtmMedium`，其中介质高度表仍是 50 项二分查找。已经加入的 coherence hint 优化的是另一张 indirect 高度表，不是所有介质查询。

近地面 `physicalAtmIndirect` 每个光源每步读取四个 mean texel、四次 shape 纹理，并进行两组太阳高度映射；直射另查 optical-depth 纹理。太阳路径每步约九个图像／纹理调用点，月亮有效时还有一套；这是源码工作量，非实际总线读取次数，高空分支也不同。

局部发光体每像素 1/2/4 个样本。一个有效且需要 transport 的样本含：一次采样点 medium、viewT 四点 medium、sourceT 四点 medium，共九次介质评价。因此 quality=2 最多 8+2×9=26 次 medium 评价，quality=3 最多 16+4×9=52 次。无效 PDF、零发光或双遮挡样本已有提前跳过；仅使用 extinction 的调用能否删掉 scattering 字段读取，需看编译结果。

### 3. RGB 阴影不能当作廉价 opaque 查询

`SHADOW_ANY_HIT_SHADER` 对 cutout/透射读取材质、重建 UV、采样 atlas；玻璃和水累计 RGB 滤色后 `ignoreIntersectionEXT` 继续遍历。`TerminateOnFirstHit` 只在接受交点后结束，不会在所有透明界面上结束。因此树叶、玻璃、长路径的多次阴影查询可能放大 any-hit 和调用调度成本。

不允许直接 ForceOpaque 或 SkipClosestHit 当作等价优化：前者改变透明遮挡，后者需要重新设计当前 closest-hit 将透射置零的契约。局部灯的动态遮挡差值在条件成立时还会额外追踪 static-only 可见性。

### 4. 两个配置误区

- `volumetricShadowSamples=0` 表示全积分点查询，**不是零条阴影**。
- 稀疏预算只作用于表面相机段；sky miss 的 signed residual 强制完整预算。所以满屏天空时调低预算不一定减轻主要成本。
- 物理模式的 fogDensity=0 仍保留气体／Rayleigh，不等于关闭体积积分。

## 为什么怀疑驱动代码生成

`RayTracingCostAudit.NO_VOLUME` 在编译前把 `camera.settings.z` 替换成常量 `0.0`。这会允许编译器删除整个体积路径，同时改变大型 raygen 的 live state、资源分配及调用点。因此现在的开关对照不能区分体积工作量与整个 shader 的资源压力。

已有审计 SPIR-V 的 mode0/physical 变体：baseline 337108 字节、18812 条静态指令、18 个 trace 调用点；no_volume 244820 字节、13724 条、12 个调用点。文件来自此前审计，仅说明编译产物有明显差异，**不是当前 Windows ISA，也不能从指令数推导毫秒**。

Mesa [RADV/ACO 调用 ABI 文档](https://docs.mesa3d.org/drivers/amd/function-calls.html) 说明 RT 调用的寄存器保留、clobber 和 scratch/spill 机制；AMD [occupancy 说明](https://gpuopen.com/learn/occupancy-explained/) 与 [RGP 事件说明](https://gpuopen.com/manuals/rgp_manual/events_windows/) 说明 VGPR 等资源可能限制整个事件的并发度。它们支持这个机制的合理性，不证明 Windows LLPC 在本例中已经发生 spill。

## 下一步如何证伪，以及优化方向

1. 在 Windows 固定同一 JAR、场景、相机、天气、时刻、RT 输入 extent、频率策略，比较 baseline、**运行时**关闭体积、编译时 no_volume。若 runtime-off 仍很慢而 no_volume 很快，重点检查全 shader 资源分配；若两者都快，重点检查体积运行时工作。运行时分支仍可能被驱动优化，因此还需要资源统计佐证。
2. 在只针对体积的诊断变体中分别保留介质积分但令可见性为一、保留相同体积射线而简化介质计算。现有 `no_shadow_rays` 会同时改变表面阴影，不足以单独归因体积。
3. 用 RGP/可用的 pipeline executable statistics 获取 baseline/对照的 VGPR、scratch、occupancy 和调用耗时。现有 gpuCrashDiagnostics 能记录各 shader group stack，但没有找到此次 Windows 对照的栈记录，栈大小本身也不能替代 occupancy。
4. 首选结构优化候选：让体积使用独立的简单 RT raygen/pass，保留相同全分辨率、4/8/16 步、随机域、介质／RGB 透射、sky residual 和 NRD 信号契约，避免 GI/表面状态与体积调用共享一个巨型 shader；之后再测介质查找和重复表查询复用。
5. Froxel/低分辨率重建是后续算法方案，会涉及薄遮挡和时域闪烁，需要单独评价画质；目前不将永久关体积、删同步或减步数作为修复。
