# Prime 26.3 大气系统研究与移植建议

> 研究对象：`/tmp/prime-26.3`，branch `26.3`，commit `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`。以下 Prime 结论均以该 commit/branch 的文件为准。本次只写入本文件，未修改生产代码。

## 1. 结论摘要

Prime 的大气不是一个可直接塞进 RT raygen 的函数，而是一个拥有静态介质预计算、动态 SkyView/方向透射、空气透视 3D LUT、太阳阴影服务和历史/延迟回收生命周期的 Vulkan compute 子系统。当前 RT 已有“相机段内联积分”：它能在主射线击中表面前后做有限步 Rayleigh/aerosol 消光与单次散射，并复用 RT TLAS 做体积太阳可见性；但它没有 Prime 的光谱介质、预计算多次散射、SkyView、方向透射或 aerial LUT。

建议先把 Prime 的**坐标/介质数据/方向透射与 SkyView 消费路径**作为独立阶段，再考虑 aerial 3D LUT；不要从 Prime 的九个 compute entry 和 33-binding pipeline 一次性复制。当前实现短期可保留，作为性能基线和降级路径。

## 2. Prime 26.3 的模块与依赖

### 2.1 入口与调度

`shaders/programs.json` 注册了 `atmosphere_transmittance`、`atmosphere_directions`、`atmosphere_incident`、`atmosphere_moments`、`atmosphere_multi_scattering`、`atmosphere_ground`、`atmosphere_sky`、`atmosphere_aerial`、`atmosphere_aerial_transmittance` 九个大气 artifact；其中静态求解链与每帧/条件变化的相机链是不同生命周期。[Prime `shaders/programs.json` @26.3, `3ab5f75e`]

`AtmospherePrecomputation` 将静态求解安排为：512×128 optical depth；先 ground seed；8 轮、每轮按 4 个高度分批执行 directions → incident → moments →（低层）multi-scattering → ground，并以两套 bank 交替读写。[Prime `src/client/java/dev/prime/render/vulkan/AtmospherePrecomputation.java` @26.3, `3ab5f75e`]

### 2.2 Vulkan 资源与管线所有权

`AtmospherePipeline` 创建并拥有：介质 buffer、静态 source/mean/ground/Rayleigh 场、256×256 `RGBA32F` log SkyView、8193×1 方向 camera transmittance、aerial radiance 3D image（128×256×128）和 aerial transmittance 3D image（128×64×128），另有 solver scratch、备用 field、太阳阴影层级和 33-binding descriptor layout。[Prime `src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java` @26.3, `3ab5f75e`；`shaders/abi.json` 的 `atmosphereContract` @26.3, `3ab5f75e`]

`AtmospherePipeline` 的注释和成员表明：transmittance/multiple-scattering 只在不可变介质或密度设置变化时生成；SkyView 随视点海拔和太阳高度变化；aerial 还随相机投影/完整太阳方向变化；dispatch 参数通过 push constants 传递，另以独立 48-byte uniform 发布阴影 bank/basis。[Prime `src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java` @26.3, `3ab5f75e`]

### 2.3 坐标、物理数据与 shader 依赖

`AtmosphereCoordinates` 将 Minecraft `Y=-64` 设为地面 datum，世界单位为 0.001 km，并将可配置海拔偏移加入物理高度；相机半径被限制在 6360 km 到 6480 km 壳层内。[Prime `src/client/java/dev/prime/render/AtmosphereCoordinates.java` @26.3, `3ab5f75e`]

shader 入口依赖 `model/atmosphere/{constants,parameters,geometry,optical_depth,integrate,source,spectrum,sky,transmittance,...}`，以及 `service/atmosphere/epipolar_shadow.slang`、`service/atmosphere/trace.slang` 等；这些依赖共同定义介质、光谱转换、壳层求交、积分和地形太阳可见性，并非入口文件自包含。[Prime `shaders/entry/atmosphere/*.compute.slang`、`shaders/model/atmosphere/*.slang`、`shaders/service/atmosphere/*.slang` @26.3, `3ab5f75e`]

静态资源包括压缩的 `medium.bin.gz.b64`、`extinction.bin.gz.b64` 及 `medium.json`；这说明介质参数来自资源数据而不是 Java 中的几组 RGB 常数。[Prime `src/client/resources/prime/atmosphere/medium.json`、`medium.bin.gz.b64`、`extinction.bin.gz.b64` @26.3, `3ab5f75e`]

### 2.4 aerial 的调度/算法形态

`atmosphere_aerial.compute.slang` 使用一个 epipolar slice 对应一个 workgroup，线程覆盖径向样本；每个深度段做固定次数子步，累计 radiance/transmittance，并将地形太阳可见性乘到总 aerial source。[Prime `shaders/entry/atmosphere/atmosphere_aerial.compute.slang` @26.3, `3ab5f75e`]

`atmosphere_aerial_transmittance.compute.slang` 则让一个 workgroup 的深度线程共享同一屏幕射线，通过 groupshared prefix product 生成 3D aerial transmittance；其 push constant 明确包含 inverse VP、eye radius、最大距离、epipole、太阳方向/强度及 shadow 参数。[Prime `shaders/entry/atmosphere/atmosphere_aerial_transmittance.compute.slang` @26.3, `3ab5f75e`]

## 3. 与当前 RT 实现的差异

### 3.1 当前已有能力

`RayTracingShaders.RAYGEN_SHADER` 在 `integrateAtmosphereSegment` 中执行最多 16 个段样本，使用 0.001 km/block、8 km Rayleigh 高度尺度、1.2 km aerosol 高度尺度、固定 RGB extinction/scattering、aerosol `g=0.76`，并根据配置选择 4/8/16 steps。[当前 `src/main/java/com/rtest/client/RayTracingShaders.java:703-824`]

该函数在主射线命中距离已知时被调用，返回 segment transmittance 与 inscatter，随后作用于表面/天空路径。[当前 `RayTracingShaders.java:1934-1961`；具体行号以当前工作树为准]

它在每个体积样本处调用同一 TLAS 的 shadow trace，并使用 `SECONDARY_RAY_MASK` 和透明 shadow payload；这与 Prime 的“地形可见性影响 aerial source”方向一致，但实现仍是逐主射线、逐样本追踪，而不是 Prime 的预计算 shadow profile/epipolar compute。[当前 `RayTracingShaders.java:728-824`；Prime `shaders/entry/atmosphere/atmosphere_aerial.compute.slang` @26.3, `3ab5f75e`]

Java 侧已经把 volumetric enabled/strength/fog density/quality 写入 Camera UBO 的 `settings.z/settings.w/environment.x`，设置变化会触发 FSR history reset；camera buffer 总大小是 304 bytes。[当前 `RayTracingVulkanPass.java:1885-1916, 1992-2001, 2040-2050`]

### 3.2 关键缺口

| 维度 | Prime 26.3 | 当前 RT |
|---|---|---|
| 介质 | 资源化、多波长/Rec.2020、光学厚度和多次散射场 | 固定 RGB 常数 + 单次 Rayleigh/aerosol 近似 |
| 静态求解 | 512×128 optical depth、8 轮双 bank transport | 无大气预计算资源或 compute pipeline |
| 天空 | 256×256 log SkyView，96 步积分；独立方向透射表 | 主要依赖 skybox/RT raygen 内联段积分 |
| 空气透视 | aerial radiance/transmittance 3D LUT，epipolar 映射 | 每条主射线最多 16 次循环，距离/分辨率耦合 |
| 太阳可见性 | epipolar shadow profile/hierarchy 服务 | 每个体积样本直接发 shadow ray |
| 历史 | `AtmosphereLutHistory` 以 aerial key 管理复用/重置 | 仅配置变化触发 FSR reset，无 LUT history |
| 生命周期 | pipeline 拥有资源，提交完成后延迟回收 scratch/旧场 | RT pass 自己管理 camera/output/descriptor 与单帧 fence |
| ABI | push constant `AtmospherePushConstants` + 大量 binding（包括 23、25、26、29-32） | 现有 set=0 binding 0-26，Camera 固定 304 bytes，ray payload ABI 已稳定 |

Prime SkyView entry 同时写 SkyView 和 camera transmittance，并使用 256 threads/row、96 步 `atmIntegrateAt`；当前没有可对应的独立 resource/dispatch。[Prime `shaders/entry/atmosphere/atmosphere_sky.compute.slang` @26.3, `3ab5f75e`]

## 4. 分阶段可落地计划

### 阶段 0：冻结契约和基线

1. 不改现有 Camera UBO 字段含义；先建立 `AtmosphereContract` Java 常量，逐项记录单位、颜色空间、世界 datum、最大距离和 reset key。
2. 为当前内联积分加 GPU timestamp、固定场景截图和解析恒定介质测试；现有实现作为 fallback。
3. 明确当前 `geometry.origin` 相对坐标与 Prime 的绝对物理壳层坐标之间的转换，禁止直接把 `camera.origin` 当作 6360 km 半径。

### 阶段 1：只移植坐标、介质资源和方向透射

1. 导入 Prime `medium/extinction` 数据及许可证/notice，写一个独立资源解压与校验层。
2. 实现 `AtmosphereCoordinates` 等价物，但先只生成 8193×1 direction transmittance；可复用一个现有 compute/sky dispatch，避免新增整套 solver。
3. 在 raygen 中以真实射线方向采样该表，仅替换太阳盘/天空方向的透射；保留当前体积积分和 TLAS shadow。
4. 只在海拔/太阳高度/介质 key 变化时更新资源，并在提交成功后延迟销毁旧 image；验证地平线两侧、夜间和水平方向。

### 阶段 2：SkyView 与 camera aerial transmittance

1. 增加 256×256 `RGBA32F` log SkyView 和 sky dispatch；Java 侧单独拥有 image、sampler、descriptor、barrier。
2. 先消费 SkyView 替代 skybox miss 的大气部分，再将 camera transmittance 用于天空/太阳盘；表面 RT 仍走当前内联路径。
3. 将 Prime 的 96 步只作为参考精度，不直接照搬数值；根据 Minecraft 相机距离、天气和曝光重新确定测试阈值。

### 阶段 3：aerial 3D LUT（可选、独立开关）

1. 先实现 transmittance 3D LUT，再实现 radiance 3D LUT；二者使用独立 image layout/barrier 与尺寸。
2. 加入 epipolar mapping 和完整的 inverse VP/epipole push constants；不要把这些字段塞入当前 304-byte Camera UBO，除非先完成 ABI 版本化。
3. 消费方式优先是 raygen 对相机段查表，几何命中距离作为 LUT 深度；没有命中/超出最大 aerial distance 时回退当前积分。
4. 评估 shadow：第一版可用当前 TLAS visibility 作为 LUT source 的近似，但必须明确它与 Prime 的 profile/hierarchy 不是同一算法。

### 阶段 4：静态多次散射求解

1. 最后再移植 `AtmospherePrecomputation.plan()` 的双 bank、分批 dispatch 和 solver scratch。
2. 仅在启动/气溶胶密度变化时运行；所有 scratch、备用 bank 和旧场进入已有 fence/deferred-retirement 机制。
3. 通过 Prime 的 solver/camera GPU tests 思路建立 RT 本地 golden data，但不要把 Prime 的测试资源误当作当前颜色空间或 Minecraft 地形的正确性证明。

## 5. ABI、资源与调度风险

### ABI

- Prime shader 使用 `[[vk::push_constant]]` 的 `AtmospherePushConstants`；aerial transmittance 明确为 128 bytes，当前 RT 没有 push-constant 大气契约。[Prime `shaders/entry/atmosphere/atmosphere_aerial_transmittance.compute.slang` @26.3, `3ab5f75e`]
- Prime 大气 pipeline 使用 binding 23/25/26/29/30/31/32 等资源；当前 RT descriptor layout 已占用 set=0 binding 0-26，且 shader modules/SBT/payload location 已稳定，直接复制会造成 binding 冲突或 stageFlags 不匹配。[Prime `src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java`、`shaders/entry/atmosphere/*.compute.slang` @26.3, `3ab5f75e`；当前 `RayTracingVulkanPass.java` create descriptor layout]
- Prime 的 `float4`/结构化 buffer 与当前 Java `ByteBuffer.putFloat(offset)` 必须按 Vulkan alignment 逐字段核对；不能凭字段名称追加 UBO。
- 当前 raygen 以 `PathPayload`、shadow transmittance、dynamic occluder 三个 payload location 工作；compute aerial 不共享这些 payload，不能从 compute shader 直接调用 `traceRayEXT` 而不重新设计 TLAS/descriptor/同步契约。

### 资源

- Prime 的静态介质、source/mean/ground/Rayleigh、SkyView、aerial 两个 3D image 和 shadow hierarchy 的峰值显存远高于当前几组 Camera/RT buffer；两套 bank 与 solver scratch 还会在重建期间叠加。[Prime `AtmospherePipeline.java`、`AtmospherePrecomputation.java` @26.3, `3ab5f75e`]
- `RGBA32F` SkyView、`RGBA16F` aerial image、3D LUT 的 layout、mip/sampler/filter 与当前 `GpuTexture`/Vulkan image 包装需要逐项验证；不可因格式名称相似就复用 RT output image。
- 旧资源只能在 GPU 完成读写后回收。Prime 的 pipeline 设计显式保留 spare/scratch 并配合延迟退休；当前 pass 的单帧 fence 也必须覆盖 compute dispatch 和后续 raygen 消费。

### 调度

- 静态求解是很多 dispatch 的有序依赖链，不是一次 dispatch；每一段需要 image/buffer barrier 和 bank 选择。[Prime `AtmospherePrecomputation.java` @26.3, `3ab5f75e`]
- aerial transmittance 使用 workgroup shared prefix product；改变 local size 或 3D 尺寸会同时破坏 shader 假设和 dispatch dimensions。[Prime `shaders/entry/atmosphere/atmosphere_aerial_transmittance.compute.slang` @26.3, `3ab5f75e`]
- 当前 RT 是一个 ray-tracing pipeline 后接 NRD/FSR 路径；新增 compute 需要定义 compute→raygen、raygen→compute、compute→FSR 的 layout transition 和 semaphore/fence 顺序，不能仅在 Java 中“先 dispatch 再 trace”。
- 配置变化既要 reset FSR/NRD temporal history，也要使 SkyView/aerial/LUT history 失效；Prime 的 `AtmosphereLutHistory` 存在正是为此。[Prime `src/client/java/dev/prime/render/vulkan/AtmosphereLutHistory.java`、`AtmospherePipeline.java` @26.3, `3ab5f75e`]

## 6. 明确不应直接复制的部分

1. **不要直接复制 Prime 的整套 `AtmospherePipeline`**：它依赖 Prime 的 `VulkanContext`、`ShaderAbi`、descriptor helper、deferred retirement 和 SunShadowClipmap，不能映射为当前 RT pass 的字段后就认为 ABI 相同。[Prime `AtmospherePipeline.java` @26.3, `3ab5f75e`]
2. **不要直接复制物理常量/资源而不做坐标审计**：Prime 的 6360/6480 km 壳层、`Y=-64` datum、0.001 km/world-unit 和当前 RT 的 render-origin 相对坐标必须先统一。[Prime `AtmosphereCoordinates.java`、`shaders/abi.json` @26.3, `3ab5f75e`]
3. **不要把 Prime 的 aerial shader 当作 raygen 函数粘贴**：它依赖 epipolar 3D image、groupshared 工作组、shadow profile、`inverseViewProjection` 和 128-byte push constants。[Prime `atmosphere_aerial.compute.slang`、`atmosphere_aerial_transmittance.compute.slang` @26.3, `3ab5f75e`]
4. **不要直接复制 Prime 的 8 轮 solver 到每帧**：这是静态介质预计算，运行成本和资源生命周期与 RT frame dispatch 完全不同。[Prime `AtmospherePrecomputation.java` @26.3, `3ab5f75e`]
5. **不要直接替换当前内联积分并删除 fallback**：Prime aerial 最大距离/高度壳层对 Minecraft 室内、地下、短距离和非 sky-light dimension 并不自动成立；必须保留当前路径作为超界、资源未就绪和能力不足时的降级。
6. **不要复制 Prime 的测试数值作为视觉验收标准**：Prime 测试验证的是其介质、光谱和资源输入；当前 RT 还有 Minecraft tint、skybox、NRD/FSR、TLAS 遮挡和曝光差异。应只借用测试结构和不变量，重新生成当前仓库 golden data。
7. **不要忽略许可证/来源声明**：Prime atmosphere entry 明确标注改编自 Sky Tracer `b66b163` 并指向 `THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md`；若引入其模型或资源，必须同步审查当前项目的许可证兼容性和 notice。[Prime `shaders/entry/atmosphere/atmosphere_sky.compute.slang`、`atmosphere_transmittance.compute.slang` @26.3, `3ab5f75e`]

## 7. 最小可执行下一步

在不改变生产代码的前提下，下一次实现应只提交“阶段 0 + 阶段 1 的资源/ABI 草案”：列出 Camera/compute push constants 的二进制布局、加入 direction-transmittance 离线/运行时 fixture、验证一个晴天/日落/夜间/地平线场景，并以当前内联积分作为 fallback。只有这些测试通过后，才进入 SkyView；aerial 3D LUT 和静态多次散射应保持独立 feature flag。
