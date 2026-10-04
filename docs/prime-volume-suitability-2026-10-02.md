# Prime 体积光雾对 RTest26.2 的适配性评估（2026-10-02）

> 历史阶段1评估：下面RTest“无finite aerial hook”等描述已过时；当前已有四波长有限段、太阳/月光及FSR前L合成。最新primary复核见 `prime-atmosphere-unified-migration-research.md`，逐步统一方案见 `prime-atmosphere-unified-migration-plan.md`。特别明确：固定上游 `aerialStep` 对 `(direct+multiple)` 整体乘scalar terrain visibility；当前RTest只对direct乘RGB阴影是adapter选择，不是完整Prime aerial阴影行为。

评估对象：上游 `bWFuanVzYWth/prime` commit `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`（"移植新的大气：现在计算各向异性的多重散射"）的 epipolar 体积光雾（aerial perspective）与太阳阴影 clipmap。RTest 现状：已移植 7 个固定介质核与 Sky/相机透射（`RayTracingAtmosphere.java:23-28`），aerial 与 clipmap 未移植；旧 volume 为每像素 4/8/16 step、逐 step TLAS 阴影（`RayTracingShaderRaygen.java:410-490`）。**本文档只评估，不改任何代码/配置。**

## 结论分档

- **可复用**：物理多重散射模型、epipolar aerial LUT 结构、太阳阴影 clipmap 双 bank 调度、Vulkan 管线形态、许可框架。
- **需适配**：aerial 更新 key（含完整相机矩阵，动即重算）；NRD/OFF 两个合成入口；薄遮挡膨胀参数；雾强度/天气参数迁移。
- **不宜照搬**：透明 RGB 透射与 localEmitter/天气雾必须保留 RTest 自己的路径，Prime 体系不提供。

## 1. 物理太阳多重散射与空气透视范围（可复用）

aerial 每步积分 `direct + multiple`：direct 使用4个波长、5个散射物种（Rayleigh + 4 气溶胶物种）表驱动各向异性相位乘太阳透射；multiple 来自 Sky Tracer 逐次散射 LUT（`atmosphere_aerial.compute.slang:39-50`、`source.slang:63`）。本 commit 正是把多重散射升级为各向异性。静态解算器 40 高度×160 太阳×20 相位×12 锥、8 轮迭代共约 290 个 dispatch（`AtmospherePrecomputation.java:9-16,38-59`），仅密度设置变更时运行一次，引导完成后 scratch 与备用 bank 即销毁。**范围限制**：aerial 最远 2.048 km（`abi.json:960`），超程像素 clamp 到末层雾片（`trace.slang:55-56`）；太阳阴影同样只覆盖 2048 m（`epipolar_shadow.slang:35`）。尺度 1 block=1 m（`abi.json:956`），眼高以 Y=-64 为基准加 300 m 默认偏移（`AtmosphereCoordinates`、`AtmosphereSettings`）。远景（>2 km）雾饱和取末片值，RTest 若要远视距雾渐变得调 `aerialMaxDistanceKm` 并重排 LUT 深度分布；近场有 nearWeight 平方深度淡入，不会给近景强加雾段（`trace.slang:53-58`）。skyView 方位相对太阳水平投影定义，方位旋转只改查找朝向不改表数据，故 sky 的更新 key 只含眼高与太阳仰角位（`AtmospherePipeline.java:421-423` 及其注释）。

## 2. 太阳阴影 clipmap：透明 RGB 不进体积雾（不宜照搬）

结构：10 张 512² r32f 深度图（2 bank×5 级联）+ 5 张 512² rg32f 叶子层级（`SunShadowClipmap.java:32-34`、`AtmospherePipeline.java:188-197`）。级联 texel 0.5/1/2/4/8 m，半径 256×texel 即 128 m 到 2048 m（`SunShadowClipmap.texelSize/cascadeRadius`、`epipolar_shadow.slang:34-37`）。**关键事实**：raygen 只把 payload 的标量命中距离写入 clipmap（`sun_shadow.raygeneration.slang:17,88-95`）；nonopaque anyhit 虽沿射线累积 RGB 光学深度（水体吸收、玻璃消光），但该数据在 clipmap 写入时被整体丢弃——RGB 透射只服务 Prime 主光照路径（`transport/direct/shadow.slang:56-57` 的 `exp(-opticalDepth.xyz)`）。aerial 消费侧的 epipolar 剖面是单通道 lit-length：每 workgroup 用 5 条 lane 沿太阳平面网格做 DDA、容量 512+4×256 段的 groupshared 剖面，二分查找后按段累积可见长度（`epipolar_shadow.slang:103-108` 及以下）。**因此体积雾中的水下/彩色玻璃阴影是单色的**；RTest 必须保留自己的 vec3 `shadowTransmittance`（`RayTracingShaderStages.java:342-355`：`materialTransmissionColor` + `exp(-optical*0.5)` 累乘 + `ignoreIntersectionEXT`）承担表面光照，体积雾侧不要指望 Prime clipmap 传 RGB。薄遮挡风险：叶子层级对 3×3 邻域取最大深度做保守膨胀（`sun_shadow_hierarchy.compute.slang:88-94`），剖面未覆盖或溢出一律判影（`epipolar_shadow.slang:101`）——设计以"防洞穴漏光"优先，代价是薄墙/小孔光束可能受固定texel和邻域膨胀影响；暗带宽度随cascade及投影变化，不能固定声称1.5 m；这些阈值（膨胀半径、0.02 m bias）需在 RTest 场景实测调参。

## 3. 天气 / 月亮 / localEmitter（不宜照搬）

`AtmosphereSettings` 明确"手动、与天气和时间无关"（`AtmosphereSettings.java:5-15`）；aerial 只接受单太阳 `sunDirectionIntensity`，双 bank 是同一太阳方向的双缓冲换装（方向差 >0.02° 时在备用 bank 上重建、建完切换），不是日月双光源（`SunShadowClipmap.java:221-233`）。RTest 旧 volume 有 rain/thunder 天气可见度耦合、夜间环境散射项与逐 step TLAS RGB 可见性（`RayTracingShaderRaygen.java:437-490`），Prime此aerial没有局部发光源散射项；RTest已有local-emitter随机估计不能因此删除，也不能据此断言局部体积光只能用某一种算法。结论：天气雾、月亮与 localEmitter 体积效果继续由 RTest 自有路径承担；Prime 只接管太阳侧大气，两套需明确开关与雾强度换算，避免视觉断层。

## 4. 资源与更新节奏（需适配）

持久有效payload约 **67.97 MiB**（包含bank0静态散射场/光学深度约6.72 MiB；不含driver/VMA对齐和pipeline/descriptor开销，非实测显存）：aerialRadiance 128×256×128 rgba16f=32 MiB、aerialTransmittance 128×64×128 rgba16f=8 MiB（`AtmospherePipeline.java:177-186`、`abi.json:961-965`）、深度图 10 MiB、层级 10 MiB、skyView 256² rgba32f 1 MiB、相机透射 8193×1 rgba16f 64 KiB（L171-176）、介质 SSBO 199,584 B（`AtmosphereMedium.java:15`）。引导期另有约 30 MiB scratch 与 6 MiB 备用 bank，静态解算提交后销毁。更新节奏：sky 仅眼高/太阳仰角位变化时重发（1×256 组，每射线 96 步积分）；**aerial key 为 21 个 int——完整 16 元逆视投影矩阵位 + 眼高 + 太阳 xyz + clipmap 内容版本**（`AtmospherePipeline.java:67,711-731`）——相机任何平移/旋转都全量重发 aerialTransmittance（128×64 组×128 深度 lane 前缀扫描）与 aerial（256 组×128 径向 lane，每 lane 128 深度×2 子步，4波长/5散射物种，组内还建 epipolar 剖面）。该开销与分辨率无关、是移动时的常数成本，但 Sky 旧实测 0.568/1.134 ms **不可外推** aerial——二者负载完全不同，7800XT/RADV 上必须实测。太阳阴影：相机跨 texel 时按 toroidal 滚动条带重追（`SunShadowClipmap.java:440-502`）；16 tile 全量重建在active bank首次起步时4 tile/帧，其余building bank通常1 tile/帧摊销（L236-248），每 tile 128²×5 级联≈8.2 万条 shadow ray，另有每级联每帧 1 个脏 tile 修复。MC 昼夜 20 分钟 → 太阳角速度 0.3°/s，远超 0.02° 重建阈值，正常时钟推进会反复触发bank重建，是潜在持续RT开销；实际占空比受FPS、太阳轨迹、dirty repair和调度影响，不能从阈值推断几乎每帧执行。帧协议为 prepare/submitted/abandon token + LUT history 候选-提交两阶段，RTest 已有同型 `SkyLutHistory`，扩展成 aerial key 即可。

## 5. NRD / OFF 合成入口与避免双雾（需适配）

Prime 的设计：aerial 体在 **post-NRD composite** 应用（`nrd_composite.compute.slang:377`；天空像素走 stable 信号跳过 aerial，L330-335），offline 路径在输出时应用（`offline.slang:19`）；管线 barrier 注释明言 atmosphere 与显示合成"刻意跨过降噪器边界"（`AtmospherePipeline.java:508-516`）。composite 同时写 FSR reactive/transparency 掩码，FSR 消费线性 Rec.2020 HDR、display transform 在超分后。RTest `RtestFsr3.recordAfterRayTracing`（L287-331）目前 NRD record 后直接 `upscaler.record`，**中间没有 aerial hook**。适配要求：(1) 在降噪后、超分 record 前插入 aerial composite，且 NRD 与 OFF（无降噪直合成）两个 mode 必须共用同一入口，只 patch NRD 一处不够；(2) 启用 Prime aerial 时必须关闭 raygen 内旧 `integrateAtmosphereSegment`，否则双重雾；(3) 天空像素跳过 aerial（skyView 已含全路径大气）。Sundial 已判定不适配当前降噪方案、另行移除，此处仅作历史记录，不再分析其兼容需求。

## 6. Vulkan 特性与许可（可复用）

太阳阴影 raygen 用 `traceRayEXT`（ray tracing pipeline，非 ray query），aerial/hierarchy/sky 全是 compute + storage image（r32f/rg32f/rgba16f/rgba32f，`VulkanContext.java:358-377`）+ 128 B push constant + 48 B 查询 UBO。RTest 已启用 rayTracingPipeline/AS，**无需 ray_query**；当前扩展列表不含 ray_query 不构成障碍，也不代表硬件/驱动不支持。许可：Prime 代码 GPL-3.0-only + NVIDIA/Minecraft linking 例外（`LICENSE-EXCEPTIONS`）；大气模型改编自 Sky Tracer b66b163（GPL-3.0-only），epipolar 剖面改编自 Intel Outdoor Light Scattering Sample（Apache-2.0），介质数据以 `bin.gz.b64` 内嵌资源。RTest现已保留Prime与SkyTracer声明；目前仅导入七核闭包，不应假设已涵盖未导入epipolar代码的Intel notice。接入时必须另行保留 `OUTDOOR-LIGHT-SCATTERING-NOTICE.txt` 及Apache-2.0义务；组合发行GPL-3.0-only不改变Intel衍生文件自身Apache许可，也不能把Prime限定例外泛化到所有第三方文件。

## 建议

先在隔离分支移植 clipmap+aerial 并实测两项：移动相机时 aerial 全量重算的毫秒数、白天太阳持续换 bank 重建的 RT 开销；达标再以 Prime aerial 替换旧 volume 的太阳侧，同时永久保留 RTest 的 RGB 透明透射与 localEmitter 体积路径。

## 引用（固定 commit `3ab5f75e`）

上游 URL 前缀：`https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/`

| 结论 | 源 |
|---|---|
| aerial LUT 尺寸/格式、更新 key、跨降噪器边界 | `src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java` L67,152-197,416-423,481-516,711-731 |
| clipmap 结构/调度 | `src/client/java/dev/prime/render/vulkan/SunShadowClipmap.java` L32-44,221-248,313-333,440-573 |
| 深度只存标量 | `shaders/entry/lighting/sun_shadow.raygeneration.slang` L17,88-95 |
| RGB 仅主路径 | `shaders/transport/direct/shadow.slang` L56-57；`shaders/entry/hit/shadow_nonopaque.anyhit.slang` |
| epipolar 保守剖面 | `shaders/service/atmosphere/epipolar_shadow.slang` L34-37,101-108 |
| aerial 多重散射/范围/近场淡入 | `shaders/entry/atmosphere/atmosphere_aerial.compute.slang` L39-50；`shaders/service/atmosphere/trace.slang` L53-98 |
| post-NRD 应用 aerial、天空跳过 | `shaders/entry/post/nrd_composite.compute.slang` L330-335,377 |
| offline 应用 aerial | `shaders/service/reconstruct/offline.slang` L19 |
| 层级 3×3 膨胀 | `shaders/entry/post/sun_shadow_hierarchy.compute.slang` L88-94 |
| ABI 常量 | `shaders/abi.json` L952-966 |
| 与天气无关 | `src/client/java/dev/prime/render/AtmosphereSettings.java` L5-15 |
| 介质 payload 199,584 B | `src/client/java/dev/prime/render/vulkan/AtmosphereMedium.java` L15 |
| 静态解算计划 | `src/client/java/dev/prime/render/vulkan/AtmospherePrecomputation.java` L9-16,38-59 |
| rgba16f 存储图 | `src/client/java/dev/prime/render/vulkan/VulkanContext.java` L358-377 |
| 许可 | `LICENSE-EXCEPTIONS`；`THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md`；`THIRD_PARTY_LICENSES/OUTDOOR-LIGHT-SCATTERING-NOTICE.txt` |
| RTest 本地 | `src/main/java/com/rtest/client/RayTracingShaderStages.java` L342-355；`src/main/java/com/rtest/client/fsr/RtestFsr3.java` L287-331；`src/main/java/com/rtest/client/RayTracingAtmosphere.java` L23-28；`src/main/java/com/rtest/client/RayTracingShaderRaygen.java` L410-490 |
