# Prime 统一大气：实现闭环与不可直接替代的边界

## 基线与结论

唯一 primary source 是 `/tmp/prime-atmosphere-26.3-3ab5f75`，已核对 HEAD 为 **`3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`**。下列路径、行号均属于该固定 commit；历史 reports 只用于定位，结论重新追至实际 owning functions。未访问或修改另一份 26.2 工作树。

**可复用的是“同一介质→光谱静态场→Sky/camera T/有限段 L、T→一次合成”的契约，不是完整通用体积渲染器。** Prime 此版只有太阳大气源，没有月亮、局部 emitter 体积散射或天气介质驱动闭环。本文不审计 RTest 当前实现，不建议删掉其 MoonSurfaceNEE、降采样/几何或取消同步来换取表面上的统一。[1–9]

## 1. 参数确实进入介质，不是末端 RGB fog

`AtmosphereSettings` 的 aerosol 倍率为 0–16、步长 .01、默认1；海拔偏移默认300m。`AtmosphereMedium.load` 读取固定 payload：50高度节点的 extinction 改为 **gas + scale×aerosol**，Rayleigh scattering 不缩放，四种 aerosol scattering 同倍率缩放；40个求解高度的 scattering 系数也缩放并重算总和。相函数及高度分布不重新拟合。`atmMedium` 随高度插值这些四谱系数；不存在独立“艺术雾 RGB”入口。[1,2]

因此倍率变化不能只刷新天空颜色：宿主创建完整 replacement，重算 optical depth、source、mean、ground、high，再发布并延迟退役旧套。偏移只改变 `(Y+64)/1000 + offset/1000` 的物理高度，不重建静态介质；它通过 eye-radius key 更新动态场。太阳 EV 是线性输出倍率，不触发静态求解，也不改变 T。[1,3,6]

## 2. 求解、资源与提交闭环

Pipeline 分配 optical-depth 512×128 RGBA16F；静态 source 3200×240 RGBA16F、mean160×40/ground160×1/high800×21 RGBA32F，双 bank 加 directions/incoming/moments scratch。动态资源是 log-RGB SkyView256² RGBA32F、camera T8193×1，以及 aerial L128×256×128、T128×64×128 RGBA16F。[3]

Precomputation 先生成 optical-depth、seed直射ground到bank0；之后8轮，每批4高度做 directions→incident→moments→低空multi_scattering，每轮首批更新ground。**分批是在一次 bootstrap 命令录制内，不是分帧收敛**；每轮读取完整旧bank、写另一bank，最终bank0。incident首轮无旧体积源，后续包含间接源；moments存log mean及高空Rayleigh矩，低空存按mean和总散射归一化的方向源。ground为反照率.18的虚拟球面边界，不是实际方块BSDF。[4]

每dispatch有compute read/write屏障（含RAW/WAR）；动态重写前RT/compute read→compute write，发布后同时供RT与compute读取。Sky key为eye-radius和sunY的float bits；aerial key为inverseVP16项、eye-radius、sunXYZ、shadow content version。太阳方位旋转只改Sky查询基，不改该表。prepare只产生候选；执行器提交后commit，未提交则abandon。`submittedStatic`把scratch/spare/solver descriptors交给`context.defer`，实际落到宿主`queueForDestroy`回调，不是立即free；回调注册失败仍保留所有权供idle后回收。[3,5]

## 3. 输运与相机合成

geometry负责球壳高度、地平线、地面/顶层交点。transmittance表实际存光学厚度，查询`exp(-tau)`；`atmSunT`另判球面遮挡。integrate逐段计算五类散射×相函数×太阳T×`ATM_SOLAR`，加`atmIndirect`，积累 **L += T×source×segmentIntegral，T *= exp(-extinction×ds)**；完整撞地路径加ground边界。[2,6]

source按高度、源仰角和观察方向查询同一静态bank：低空插值归一化形状与log mean，高空≥35km用Rayleigh二次型矩。spectrum四通道不是RGBA颜色：输出经拟合矩阵转D65 linear Rec.2020并按太阳谱校准；RGB T也按`ATM_SOLAR`归一化。因此它不是对任意入射光谱严格通用的RGB消光算子。[2,6]

Sky每方向96步积分至边界，存log L；同dispatch写camera T，查询时另裁掉撞球面地面的方向。有限段aerial累计至2.048km，平方分布128深度、每段2子步；L为epipolar方向，T为屏幕方向，二者分别生成。NRD先还原opaque或transmission+reflection信号，再加stable，以真实displayPosition距离统一做 **C = T×Csurface + L**；直接背景只返回stable，不再加有限雾。透明分支miss仍查同一Sky环境，但这并不证明每段反射/折射路径都做了有限段大气积分。近平面混回L=0/T=1，超范围仅clamp到最大表深度。[7,8]

## 4. 独立源、体积阴影与天气缺口

太阳圆盘用实际采样方向camera T，天空与aerial L施加同一运行时太阳倍率。CPU端也已核对：GameRendererMixin只提交sunAngle；VulkanRenderer.captureCamera调用`AstronomyState.atSolarHourAngle`，该状态只含SunDirection、观测纬度和太阳黄经，没有夜间替换为Moon的主源。[9,12]**没有Moon方向、月相、亮度或光谱输入进入此版求解，也没有额外Moon LUT**。静态bank覆盖源仰角、理论上可按另一方向查询，但源码没有实现第二源；更不能把太阳谱求解结果简单改名为物理月光。不同谱源、独立阴影和合成的可行性仍需验证。[6,9]

volume主路径是**标量depth clipmap→resolved leaf→每epipolar slice共享profile→区间可见长度比例**。sun-shadow raygen虽使用shared shadow payload，最终只写hit depth，不输出RGB吸收；表面direct shadow另由hit路径累计RGB optical depth并返回`exp(-tau)`，不能将后者冒充aerial主路径。aerial把标量visibility乘在direct+multiple总源上，是局部地形遮挡近似，非任意几何下完整多散射；缺coverage/overflow保持shadowed，cache方向可滞后可见太阳。[7,10]

完整aerial/integrate源函数只有太阳和静态间接源，没有local-emitter注入；局部表面光不能因此被称为体积光支持。weather也不是“已有介质变化”或“已有L/T modulation”：Settings明确手动且独立天气，camera捕获只取sunAngle；对Java/shaders检索moon、rainLevel/rainStrength、thunderLevel、weather未找到相关大气接线。Java的WEATHER分类属于场景边界，不是介质参数。[1,7,9]

## 5. 复用约束与 limitations / unverified GPU

统一应先固定介质/谱/源场和L、T职责，再验证多源及局部源，不能把epipolar太阳近似视为全部旧体积功能的替代品。资源不是随意换绑定即可：Pipeline拥有独立compute描述符集、128字节push，生产者与消费者的格式、相机投影、单位和色彩必须成套核对。当前Settings也没有逐物种气溶胶、独立气体密度或相函数调节入口；不能将一个总倍率解释为任意天气物理模型。[1,3]须保留同步、提交history和资源退役；验证零距离、海拔/密度失效、阴影bank滞后、透明真实距离和背景不双重雾。

许可沿用现有[provenance说明](prime-atmosphere-reference.md#1-许可证先决条件)：Prime及Sky Tracer为GPL-3.0-only，需保留来源、修改声明和对应源码义务；Intel epipolar部分保留Apache-2.0/NOTICE。**不得重标为MIT、删除Intel notice，或擅自把Prime附加例外扩展给Sky Tracer/其他权利人代码**。[11]

本次仅源码研究及本文写入；无Java/shader/config修改、部署、构建、游戏或GPU运行。原Sky Tracer资产再导出、宿主queueForDestroy内部timeline、数值/视觉/性能与RTest兼容性未实测；尤其大气球面ground、有限距离截断与实际Minecraft地形的差异，不能由“物理”命名自动消除。月光谱复用、local-volume、天气映射均未获验证。Prime自身notice也明确算法是近似，不是精确光谱参考解。[5,11]

## 固定源码索引

所有链接固定到上述 commit；`J/`表示`src/client/java/dev/prime/`，`S/`表示`shaders/`。行段包含本次读取的完整关键函数，不以历史报告代替源码证据。

1. [J/render/AtmosphereSettings.java:5–41](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AtmosphereSettings.java#L5-L41)；[J/render/vulkan/AtmosphereMedium.java:15–87](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmosphereMedium.java#L15-L87)；[J/render/AtmosphereCoordinates.java:15–28](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AtmosphereCoordinates.java#L15-L28)。
2. [S/model/atmosphere/spectrum.slang:19–68](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/spectrum.slang#L19-L68)；[source.slang:66–115](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/source.slang#L66-L115)。
3. [J/render/vulkan/AtmospherePipeline.java:133–217、346–560、653–727、970–981](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePipeline.java#L133-L981)；[J/render/runtime/VulkanRenderer.java:941–1015](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/runtime/VulkanRenderer.java#L941-L1015)。
4. [J/render/vulkan/AtmospherePrecomputation.java:7–59](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmospherePrecomputation.java#L7-L59)；[S/entry/atmosphere/atmosphere_incident.compute.slang:15–31](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_incident.compute.slang#L15-L31)、[atmosphere_moments.compute.slang:16–50](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_moments.compute.slang#L16-L50)、[atmosphere_multi_scattering.compute.slang:17–73](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_multi_scattering.compute.slang#L17-L73)、[atmosphere_ground.compute.slang:16–29](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_ground.compute.slang#L16-L29)。
5. [J/render/vulkan/AtmosphereLutHistory.java:38–106](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/AtmosphereLutHistory.java#L38-L106)；[RealtimeFrameExecutor.java:109–120、180–190](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/RealtimeFrameExecutor.java#L109-L190)；[VulkanContext.java:499–559](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/vulkan/VulkanContext.java#L499-L559)。
6. [S/model/atmosphere/integrate.slang:18–133](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/integrate.slang#L18-L133)；[geometry.slang:8–33](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/geometry.slang#L8-L33)；[transmittance.slang:11–19](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/transmittance.slang#L11-L19)；[parameters.slang:6–13](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/model/atmosphere/parameters.slang#L6-L13)。
7. [S/entry/atmosphere/atmosphere_sky.compute.slang:28–50](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_sky.compute.slang#L28-L50)；[atmosphere_aerial.compute.slang:39–155](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_aerial.compute.slang#L39-L155)；[atmosphere_aerial_transmittance.compute.slang:43–106](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/atmosphere/atmosphere_aerial_transmittance.compute.slang#L43-L106)。
8. [S/entry/post/nrd_composite.compute.slang:73–120、327–382](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/post/nrd_composite.compute.slang#L73-L382)；[S/transport/scatter/transparent.slang:27–88](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/scatter/transparent.slang#L27-L88)；[S/service/atmosphere/trace.slang:25–101](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/atmosphere/trace.slang#L25-L101)。
9. [S/service/light/sample.slang:32–102](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/light/sample.slang#L32-L102)；[S/transport/surface/environment.slang:20–49](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/surface/environment.slang#L20-L49)；[J/mixin/GameRendererMixin.java:56–67](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/mixin/GameRendererMixin.java#L56-L67)。负面结论另基于正文列出的目录/标识符搜索，不宣称其他版本也缺失。
10. [S/entry/lighting/sun_shadow.raygeneration.slang:67–96](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/lighting/sun_shadow.raygeneration.slang#L67-L96)；[S/service/atmosphere/epipolar_shadow.slang:95–108、180–274、293–395](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/service/atmosphere/epipolar_shadow.slang#L95-L395)；[S/transport/direct/shadow.slang:26–65](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/transport/direct/shadow.slang#L26-L65)；[S/entry/hit/shadow_nonopaque.anyhit.slang:34–39、100–158](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/shaders/entry/hit/shadow_nonopaque.anyhit.slang#L34-L158)。
11. [LICENSE-EXCEPTIONS:6–98](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/LICENSE-EXCEPTIONS#L6-L98)；[THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md:1–21](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/THIRD_PARTY_LICENSES/SKY-TRACER-NOTICE.md#L1-L21)；[OUTDOOR-LIGHT-SCATTERING-NOTICE.txt:1–14](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/THIRD_PARTY_LICENSES/OUTDOOR-LIGHT-SCATTERING-NOTICE.txt#L1-L14)。
12. [J/render/runtime/VulkanRenderer.java:272–299](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/runtime/VulkanRenderer.java#L272-L299)；[J/render/AstronomyState.java:8–67](https://github.com/bWFuanVzYWth/prime/blob/3ab5f75e33de3f6947e96f4a4dd1406588e9e98b/src/client/java/dev/prime/render/AstronomyState.java#L8-L67)。该证据用于排除“只看sun变量名漏掉夜间Moon主源切换”的误判。
