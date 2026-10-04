# Prime 26.3 大气系统源码参考

## 结论与研究边界

**复用上游的物理输入、光谱求解与 LUT 契约，适配已有 RT 的资源和合成接线；不要再独立实现一套天空/雾模型，也不要把 26.3 的 Vulkan/游戏宿主代码原样覆盖到 26.2。** RT 已使用 Prime shaders、NRD/FSR 是本任务的已知前提；本文不声称 RT 尚无大气，也没有审计 RT 当前功能覆盖率。

研究对象为 `bWFuanVzYWth/prime` 的 **26.3 分支，commit `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`**。通过 `git ls-remote ... refs/heads/26.3` 查得 SHA，独立浅克隆至 `/tmp/prime-atmosphere-26.3-3ab5f75` 并核对 HEAD。原 `/home/aruku/prime-work/prime` 为 26.2、HEAD `97110537c281b5c2180770822736a9a3f6ca00d1` 且有本地修改；只读查看，未切分支、reset 或修改其工作树。下面链接全部固定到本次 26.3 SHA，行号对应实际读取的文件。

本次只写本文；未运行上游构建、GPU 测试、游戏或物理资产导出。上游文档的测试/性能数字不作为本机验证结果。

## 1. 许可证先决条件

- **Prime-authored code 是 GPL-3.0-only，不是 MIT**，另有 GPL §7 的限定附加许可。指定组件包含 Minecraft 与 NVIDIA DLSS/NGX/NRD；这不豁免 Prime 自身修改、桥接、shaders、构建脚本的 GPL 对应源码要求，不扩展到任意模组或其他专有组件。旧 MIT 发行的权限仍保留，但不能将后续 GPL 修改视为 MIT。[许可及例外][license]
- 四波长大气移植自作者 linlin 的 Sky Tracer `b66b16342afe38e788a5ece5371d3b3a67c5909a`、`crates/sky-realtime`，声明 **GPL-3.0-only**；源码文件有相同来源头。[大气来源声明][sky-notice] [光谱源码][spectrum]
- epipolar 太阳阴影 profile 有 Intel Outdoor Light Scattering Sample 的 **Apache-2.0** 来源，必须保留相应 NOTICE/许可证；不是可删掉声明的纯 Prime 新代码。[Intel notice][intel]
- 星图另保留 NASA/Goddard、Gaia/DPAC 等署名；其 EXR 未指定原色/白点，Prime 的 linear-sRGB 解释并非 NASA 的色度保证。[NASA notice][nasa]

**复用建议有许可证前提**：先核对 RT 发布许可、保留来源和修改声明及对应源码交付，再搬代码/资产。本文核查的是该快照随附声明与 GPL 文本，不替代法律意见，也未独立验证原 Sky Tracer 仓库的授权链。[GPL 修改/二进制分发条款][gpl]

## 2. 实际模型：不是旧解析雾，也不是仅一个天空贴图

这是 Sky Tracer balanced 的**分层物理表、四波长定向逐次散射**实现。上游文档明确波长为 450/510/580/650 nm；ABI 球壳为地面半径 6360 km、顶层 6480 km。介质包含一种 Rayleigh 和四种表格气溶胶散射，相函数表按方向余弦查表，消光独立存储；不能把它简化成一个固定 Henyey–Greenstein 参数。`atmMedium(h)` 对 50 个高度样本插值，返回 `float4 extinction` 和五组 `float4 scattering`。[模型说明][model-doc] [ABI][abi-atm] [介质/色彩实现][spectrum]

光谱输运为 f32 四通道。`atmIntegrateAt` 沿球壳路径累计 `S`、`T`，直接源为五类散射系数乘相函数、太阳透射和 `ATM_SOLAR`；需要多散射时再加 `atmIndirect`。撞虚拟球形地面时加地面边界辐亮度。积分器有大气外入口逻辑，但 Java 摄像机映射仍钳在壳内，不能据此宣称游戏支持无限太空相机。[积分源码][integrate] [高度映射][coords]

低空多散射场保存**归一化源项 f16 + 对数均值 f32**，不是最终 RGB。35 km 及以上用 Rayleigh 二次型矩表达；`atmIndirect` 插值亮度和方向形状再乘散射系数。[多散射写入][multi] [多散射读取][source]

光谱到 **D65 linear Rec.2020** 使用拟合矩阵，按外层太阳的各 RGB 分量归一化，校准至 Prime 白色太阳强度 12.5。透射转换也按太阳谱归一化并钳至 `[0,1]`。因此四通道光学厚度/源场不是 RGBA 颜色，SkyView/空气透视 RGB 也不是 linear-sRGB；禁止重复 EOTF、曝光或色彩矩阵。[spectrum][spectrum] [光源工作色彩契约][light]

## 3. LUT、资源格式与生成依赖

以下尺寸来自 `AtmospherePipeline` 资源创建和 ABI，不是概念示意。[资源创建][images] [调度常量及计划][plan] [ABI][abi-atm]

| 资源 | 实际形状/格式 | 生产与意义 |
| --- | --- | --- |
| 光学厚度 | 512×128，RGBA16F | `atmosphere_transmittance` 写四波长 **optical depth**，不是直接 RGBA 透射颜色 |
| 低空 source | 3200×240，RGBA16F | 160 太阳状态×20 相位、20 低高度×12 cone，归一化方向源 |
| mean | 160×40，RGBA32F | 四波长对数均值 |
| ground | 160×1，RGBA32F | 虚拟 Lambert 球形地面边界，不是 Minecraft 材质的 albedo |
| high/Rayleigh | 800×21，RGBA32F | 高空二次型矩/归一化数据 |
| SkyView | 256×256，RGBA32F | RGB 为 log Rec.2020；A 保存行方向余弦 |
| cameraTransmittance | 8193×1，RGBA16F | RGB 方向透射；同一 SkyView dispatch 的前 8193 invocation 生成 |
| aerialRadiance | 128×256×128，RGBA16F | epipolar radial×slice×distance；累计 RGB 入散射 |
| aerialTransmittance | 128×64×128，RGBA16F | 屏幕方向×distance；累计 RGB 消光透射 |

空气透视/方向透射的 helper 明确分配 `VK_FORMAT_R16G16B16A16_SFLOAT`、storage usage，不能想当然要求所有表都是 sampler3D。静态 optical/source 有 sampled+storage usage，配线性、clamp-to-edge sampler；其他多个消费者通过 storage-image load 和手工插值读取。[图像 helper][image-helper] [描述符及 sampler][descriptors]

### 静态求解 DAG

```text
固定物理/求积 payload + 手动气溶胶倍率
  → optical-depth LUT
  → direct ground seed（field 0）
  → 8 轮 ping-pong：
      每批 4 个高度：directions → incident → moments
                     → low-height multi_scattering（如有低空）
                     → ground（每轮仅首批）
  → 完整 final field → SkyView / aerial 生产者
```

每轮读取完整旧场、写另一场；不能将部分已更新高度混入同轮旧场。ground 不是整个求解只执行一次，而是初始直射 seed 后**每轮首批更新一次**。temporary scratch 包含 directions/incoming/moments，轮间和批间均有 barrier。[plan][plan] [静态准备][static] [求解屏障][solver-sync] [ground 实现][ground]

`medium.bin.gz.b64` 是 Base64 包装的 gzip，解压 199584 bytes、小端 float4；`extinction.bin.gz.b64` 为 1600 bytes、各高度 gas float4 + aerosol float4。手动倍率重算 gas+scale×aerosol 消光、缩放四气溶胶散射并重算源总散射；不重新拟合物理表。[payload 读取/缩放][medium] [清单][manifest]

`tools/export_atmosphere.py` 要求固定 Sky Tracer commit 且 `crates/sky-realtime` 无修改，在 Prime `build/atmosphere-export` 复制源后注入导出器，调用 Cargo/Rust；正常使用已有 payload 不需运行导出器。`--gpu-reference` 才追加原 WGSL GPU 对照生成。当前研究没取得该原始本地仓库，不能宣称已复现资产。[导出器][export]

静态源表的求解描述符与上面的最终消费者共享声明，但轮次输出绑定另一 bank；最终 bank 由 `ITERATIONS & 1` 选择。本快照8轮结束使用field 0。[plan][plan] [descriptors][descriptors]

### 动态更新 DAG / key

- **静态场**只在首次/介质替换时求解，不随当前太阳或相机旋转重跑。
- **SkyView + cameraTransmittance**：key 是 f32 eyeRadius bits、sun Y bits；太阳水平转向只改变查询相对方位，不改变表内容。Sky 每行共享一次逆 CDF、96 步输运，写 log RGB。[history][history] [帧准备][prepare] [Sky dispatch][sky]
- **aerial 两体积缓存**：key 为相对 inverseViewProjection 的 16 个 float bits、eyeRadius、完整 sun xyz、sun-shadow content version。阴影 cache 可能滞后可见太阳，epipolar 投影必须使用拥有该缓存的 active shadow direction。[key/push ABI][push] [缓存方向解释][epipole]
- 每帧顺序为 `atmosphere.prepare`（包含太阳阴影缓存准备）→ `pipeline.trace` → reconstruction processor；只有提交成功才 `submitted(token)`，失败 `abandon(token)`，不是“录制了就更新历史”。[实际执行器][executor]

## 4. 太阳、月亮、天空、地表与体积光接线

### 太阳与天空

太阳是有限圆盘，角半径 `0.00471 rad`，均匀立体角 PDF。圆盘 radiance 为太阳强度/立体角，再乘**实际采样方向**的 cameraTransmittance；逐方向判断是否撞球形地面，不能只判断太阳中心然后裁掉整盘。天空为 `primeAtmosphereSky(direction,sunDirection)` 查 SkyView，再按运行时太阳 EV 倍率缩放；EV 不需重跑线性静态求解。[light][light] [方向透射服务][trace]

cameraTransmittance 用 `u=0.5+0.5*sign(mu)*sqrt(abs(mu))` 地平线加密坐标。SkyView 查询区分撞地、上半球、地平线下未撞地 chart，对 log RGB 插值后 exp，避免跨地平线分支滤波。[sky 图生成][sky] [SkyView 读取][sky-read] [方向映射][direction]

### 地表照明与虚拟地面不是一回事

真实 Minecraft 表面太阳项走 sun sample → 几何/透明介质 shadow trace → 方向大气透射×shadow.transmittance → 实际 BSDF/direct split。普通环境路径逃逸读取天空/星图/太阳，并对太阳命中与前一顶点 NEE 做 MIS。[真实表面太阳][direct] [miss 环境/MIS][environment]

ground LUT 只是大气模型反照率 0.18 的球形地面边界：直射加 incoming 半球辐照，再乘 `0.18/pi`；**不要用其替换 RT 地形 BSDF、方块光或实际地面材质**。[ground][ground] [积分边界][integrate]

### 月亮、天气、维度：明确缺口

已读的光源服务只提供太阳和 SkyView；miss 环境是天空+星图+太阳。天文帧由原版 `skyRenderState.sunAngle` 加纬度/太阳黄经构造，世界东 +X、上 +Y、南 +Z。本次对 `src/client/java` 的 moon/Moon、rainLevel/RainLevel、hasSkyLight/hasSkylight、DimensionType 等精确搜索，没有发现月光、天气介质或维度大气切换接线；**这是所读/所搜范围的结果，不是对全仓所有功能的绝对否定**。[光源服务][light] [环境求值][environment] [游戏输入][mixin] [天文坐标][astronomy]

气溶胶明确是手动设置，独立天气/时间；不是雨雾模型。设置键为 `atmosphere.aerosol_density_scale`（0–16，步进 .01，默认1）和 `atmosphere.altitude_offset_meters`（0–10000m，默认300）。海拔为 `(Y+64)*0.001 + offset*0.001 km`，钳至球壳底/顶下1m。截图期间冻结介质；倍率变化创建整套 replacement，bootstrap 提交后发布，延迟回收旧套。[settings][settings] [配置键][config] [coords][coords] [冻结][freeze] [替换][replace]

**移植时另行定义** Nether/End、无天空维度、生物群系/雨雪、水下以及月亮视觉/月光策略。本文没有证据支持 26.3 已替你完成这些策略，也不建议把原版雨量直接映射成每帧静态场重建。

### 空气透视 / 体积光

实际模型为 `Lout=T(view,distance)*Lsurface+S(view,distance)`，距离上限 2.048 km，128 个平方分布深度切片、每段两次子步。radiance 卷在 epipolar slice 内构建共享太阳阴影 profile，**地形可见度乘整个 direct+multiple 源项**，这是上游保留的近似，不是任意几何遮挡下完整多散射体积解。transmittance 卷另按屏幕方向计算四波长前缀乘积再转 RGB。[aerial 源/阴影/积分][aerial] [aerial 消光][aerial-t] [ABI][abi-atm]

近平面合成将首个中心切片向 `T=1,S=0` 混合，以免近景套上有限雾段；无效/负距离跳过，超范围距离采最大深度而非继续解析积分。[离线采样服务][trace] [实时合成][composite]

## 5. raygen / 背景 / 透明 / NRD / FSR

26.3 不是单一巨型 raygen：camera raygen 只调用相机追踪、保存 surface 和入队；首次 visible miss 在后续 transport 中把环境贡献放进 stable radiance。环境含天空+星图+太阳，太阳有 BSDF/NEE 竞争权重。[raygen 入口][raygen] [相机追踪][camera] [visible miss][visible] [environment][environment]

NRD 后 `nrd_composite`：

1. 没有有效 material 的直接背景返回 stable signal，**不再应用有限段 aerial**；SkyView 本身已含至大气边界的输运。
2. 实体表面 remodulate diffuse/specular + sun；透明时另合 reflection branch，再加 stable。
3. 用真实 `displayPosition` 的长度应用一次 aerial，而不是用虚拟反射 guide 的距离；输出仍为 linear Rec.2020 HDR，之后才供 FSR，显示变换必须在 upscaling 之后。
4. 从 visible material flags 生成 FSR reactive/transparency masks。

这些是必须与已有 NRD/FSR 合成保持一致的信号归属，不能再在 raygen 与 composite 双重加雾。[composite 定义及 aerial][composite-head] [合成分支及输出][composite]

透明反射/折射 miss 仍消费同一个 `primeEvaluateEnvironmentContribution`，乘路径 throughput；PSR 可能生成 directional guide，但不能将 guide 当成物理雾距离。离线在 running-mean **之前**按每次真实 camera sample 应用 aerial，避免只按像素中心采样造成确定性偏差。[透明 miss][transparent] [offline 输出][offline] [trace][trace]

星图是 BC6H 的 linear Rec.2020 资产，乘相同方向透射、独立星光倍率。DLSS RR 有直接相机星图移到后置原生分辨率 pass 的特例，普通反射/折射 miss 仍取星图；RT 的 NRD/FSR 接线不应误套 RR-only 特例。[星图服务][stars] [环境 nativeStars 判断][environment] [上游 RR 特例说明][rr-doc]

## 6. Vulkan 资源、同步、ABI 耦合

- atmosphere compute 自有 set，33 bindings、128-byte push；medium 在7、cameraTransmittance 在23、high 在24、备用场25–28、scratch29–31、optical-depth output32。**与共享 RT set 的 binding 不是同一套**：共享 skyView4、cameraTransmittance5、aerialRadiance7、aerialTransmittance8。[layout/描述符][descriptors] [共享 ABI][abi-bind]
- push 偏移：inverseVP0、eye64、maxDistance68、epipole72/76、sun80–92、shadow direction/bank96–108、scene-relative shadow camera112–124。Java 创建与 Slang 结构必须一起搬；不可只搬 SPIR-V。[push][push] [aerial-t 结构][aerial-t]
- 首次 image `UNDEFINED→GENERAL`，之后 GENERAL 内显式同步；重写前 RT/compute shader read→compute write，发布后 compute write→RT **及 compute** read。静态阶段每 dispatch 有 read/write→read/write memory barrier，覆盖 RAW/WAR 和 ping-pong。sun-shadow query uniform 48-byte 更新另有 RT read→transfer write→RT read barrier。[image/query barrier][sync] [solver-sync][solver-sync] [帧发布目标 stage][prepare]
- `submittedStatic` 后 scratch/spare/solver descriptor 经 `context.defer` 延迟销毁，不能在命令录制后立刻 free；LUT keys 只在提交后 commit。介质替换同样延迟回收。[static][static] [history][history] [replace][replace]
- **版本**：本快照目标 Minecraft26.3/Fabric `.160.6+26.3`，Slang `2026.13.1-1-g84792eb15`，使用 `com.mojang.renderpearl` API。26.2 的包名、Mixin target descriptor、command encoder、资源生命周期需逐项对齐，不能凭“同为 Vulkan”保证二进制兼容。[构建版本][versions] [mixin target][mixin] [宿主提交][replace]
- **坐标**：top-left UV 转 clip Y 翻转；aerial inverseVP 用近 z=1、远 z=0；世界单位 1 block=1m，场景相对原点与绝对 camera Y 职责不同。错误的投影方向、jitter、render-origin、纬度框架会让天空和体积阴影错位。[UV 契约][uv] [aerial-t][aerial-t] [coords][coords] [astronomy][astronomy] [push][push]
- **色彩/信号**：四谱源场、log RGB SkyView、RGB aerial、太阳 EV、NRD remodulation、FSR scene-referred Rec.2020 不是可互换纹理。适配必须同步生成 ABI、binding、格式、push offset、extent、抖动和曝光位置。[spectrum][spectrum] [composite-head][composite-head]

## 7. 优先复用清单与待适配风险

| 组件 | 建议 | 约束 |
| --- | --- | --- |
| `model/atmosphere` 数学/输运、`entry/atmosphere` 求解 | 优先成套复用，不重写物理/相函数 | GPL；参数布局、四谱与 Rec.2020 契约必须一致 |
| medium/extinction payload + `parameters.slang` + export tool | 优先使用固定资产及来源清单 | 三者绑定；修改介质必须重建相关静态场 |
| `AtmospherePrecomputation` / LUT history 逻辑 | 复用调度/commit-abandon 思路 | Vulkan dispatch、barrier、退役接到 RT 已有宿主 |
| SkyView / cameraTransmittance 生产与查询 | 复用坐标图和方向映射 | eye/sun frame、颜色、binding/格式成套核对 |
| aerial + epipolar + sun-shadow profiles | 先对照 RT 现有功能再选增量 | 与 scene-relative RT shadow clipmap、active bank/方向、缓存版本强耦合；保留 Apache NOTICE |
| light/environment / NRD composite | 参考现有接线，避免第二套路径 | RT 已有 shaders；透明分支、stable/background、真实雾距离与 FSR masks 不可重复叠加 |
| Java `AtmospherePipeline`、执行器、Mixin | 不能原样当26.2可用插件 | renderpearl/26.3 API、共享descriptor、defer队列和错误回滚需适配 |
| 天气、维度、月亮 | 保持为明确未解决策略 | 本次所读来源没有给出可直接复用的实现 |

以上为基于已读源码的工程建议，而非已经完成的移植。后续验收至少包括：0距离恒等、地平线圆盘裁切、海拔/太阳/旋转 key、倍率替换和失败提交回滚、shader reload、透明到天空、NRD背景不双重雾、FSR输入色彩、render-origin迁移与阴影bank滞后；26.2游戏实测与Vulkan validation仍缺。

## 8. 来源缺失与验证边界

26.3 源码访问成功、SHA 已锁定，没有退回 README 推断。缺失的是：原 Sky Tracer 的可访问公开仓库地址/独立 checkout（随附 notice 仅称 local repository）、资产从原始数据再导出的复现、26.2 兼容性实验、本地 GPU/游戏运行结果以及 RT 现有大气逐项差异审计。NASA/Intel 的法律/数据上游网页也未在本次重新独立核验；这里明确引用 Prime 随附 notices，不将其冒充已核验的上游原件。

## 9. 与 RTest 当前代码的对照及接入顺序

以下为主代理补充的本地源码对照，不是上游已完成的兼容性保证：

- `RayTracingSkybox.java` 目前加载六面 PNG，`RayTracingShaderRaygen.java:90–108` 对其做 sRGB→linear Rec.2020；`1051–1096` 将其用于所有路径 miss，再单独加太阳圆盘。Prime SkyView 已是光谱求解后的 Rec.2020，不能继续走 PNG 的 EOTF/原色矩阵。
- 当前 `RayTracingShaderRaygen.java:368–494` 是相机段 Rayleigh/单参数 aerosol 单散射，含短距离艺术密度/散射倍率，4/8/16 点积分并逐点 TLAS 太阳可见性；它并不等于上游表格四谱、定向多散射模型，也没有上游 LUT 缓存资源。
- 当前 `1564–1628` 在 RayGen 对表面与背景都应用有限段空气透视，并分摊到 AOV、把太阳/环境 in-scatter 放到不滤波 emission。接上 Prime SkyView 后，直接背景必须避免再走这段有限距离雾；表面 aerial 也只能在 RayGen/AOV 或 composite 中选一个位置执行。OFF、Sundial、NRD 三种模式都必须保持相同能量分工。
- 当前局部 emitter 体积散射已有 `sampleVolumeEmitter` 路径。不能以新 aerial 太阳系统为由无声删除它，也不能重复叠加旧太阳散射。
- 当前 Vulkan skybox 为 set0/binding6、Camera UBO 304 bytes；已有 descriptor binding0–26 多数占用。上游33-binding compute set、共享shader ABI和128-byte push不能覆盖这些槽，需要独立 compute layout 和明确的消费接口。
- 本地 `gradle.properties:15` 仍声明 `mod_license=MIT`；`build.gradle:94–95` 虽已有 Prime license 包装，不足以把26.3 GPL代码重新许可为MIT。移植/发布前需明确GPL覆盖和对应源码交付策略，不能擅自把用户整个项目改许可。

建议按可单独验收的顺序接入：

1. 先确定许可/来源清单，固定 commit、物理 payload 和配套 Slang ABI/编译版本；不要手工重写相函数或改成 RGB 近似。
2. 接静态介质求解、SkyView和方向透射，统一背景、镜面/折射 miss 与太阳 NEE；暂保留现有局部体积光路径，但明确不对新天空重复加有限段雾。
3. 再接 aerial/epipolar 与本地太阳阴影资源，统一三种降噪模式的物理距离和一次性合成；最后处理天气/维度及月亮策略。

上游最终 aerial 两体积按当前格式合计约 **40 MiB**（radiance32MiB + transmittance8MiB），尚不含静态双bank、scratch、太阳阴影cache。不得在已有大世界显存压力下不测量就承诺更快或无内存风险。最终性能必须分别测静态初始化、动态LUT更新和RT/composite；减少逐像素阴影射线不自动证明整帧更快。

本次仅新增研究文档，未搬入GPL代码/资产，未改当前shader、JAR、配置或许可。后续移植需真实GPU验证，本文不把源码对照当视觉等价/性能证明。

## 永久源码链接

[license]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/LICENSE-EXCEPTIONS#L1-L98
[gpl]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/LICENSE#L200-L294
[sky-notice]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md#L1-L21
[intel]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/THIRD_PARTY_LICENSES/OUTDOOR-LIGHT-SCATTERING-NOTICE.txt#L1-L14
[nasa]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/THIRD_PARTY_LICENSES/NASA-DEEP-STAR-MAPS-2020-NOTICE.md#L1-L23
[model-doc]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/docs/灯光与大气采样.md#L64-L100
[abi-atm]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/abi.json#L950-L968
[spectrum]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/spectrum.slang#L1-L68
[integrate]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/integrate.slang#L14-L133
[source]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/source.slang#L37-L115
[multi]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_multi_scattering.compute.slang#L17-L73
[coords]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AtmosphereCoordinates.java#L7-L28
[images]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L133-L217
[image-helper]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/VulkanContext.java#L358-L378
[plan]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePrecomputation.java#L7-L59
[static]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L346-L389
[prepare]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L391-L560
[push]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L680-L727
[epipole]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L325-L340
[descriptors]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L729-L950
[sync]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L607-L678
[solver-sync]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L953-L981
[medium]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmosphereMedium.java#L13-L87
[manifest]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/resources/prime/atmosphere/medium.json#L1-L146
[export]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/tools/export_atmosphere.py#L1-L127
[history]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmosphereLutHistory.java#L38-L106
[sky]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_sky.compute.slang#L15-L50
[sky-read]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/sky_view.slang#L15-L111
[direction]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/math/atmosphere_direction.slang#L1-L15
[light]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/light/sample.slang#L17-L102
[trace]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/atmosphere/trace.slang#L15-L101
[ground]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_ground.compute.slang#L16-L29
[direct]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/direct/primary.slang#L53-L96
[environment]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/surface/environment.slang#L20-L49
[mixin]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/mixin/GameRendererMixin.java#L48-L98
[astronomy]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AstronomyState.java#L38-L74
[settings]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AtmosphereSettings.java#L5-L41
[config]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/config/PrimeConfigCodec.java#L48-L49
[replace]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/runtime/VulkanRenderer.java#L941-L1016
[aerial]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_aerial.compute.slang#L21-L155
[aerial-t]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_aerial_transmittance.compute.slang#L16-L106
[raygen]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/realtime/camera_trace.raygeneration.slang#L6-L15
[camera]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/realtime/camera_trace.slang#L23-L42
[visible]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/scatter/visible.slang#L25-L49
[composite-head]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/post/nrd_composite.compute.slang#L15-L120
[composite]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/post/nrd_composite.compute.slang#L327-L415
[transparent]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/scatter/transparent.slang#L25-L86
[offline]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/reconstruct/offline.slang#L13-L40
[stars]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/atmosphere/starmap.slang#L14-L50
[rr-doc]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/docs/灯光与大气采样.md#L157-L178
[abi-bind]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/abi.json#L748-L771
[uv]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/contract/coordinate.slang#L6-L21
[versions]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/gradle.properties#L1-L17
[executor]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/RealtimeFrameExecutor.java#L109-L158
[freeze]: https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/runtime/VulkanRenderer.java#L165-L202
