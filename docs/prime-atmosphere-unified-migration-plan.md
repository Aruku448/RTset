# RTest 逐步统一到物理大气：替换边界与顺序

用户目标：逐步以统一大气介质替代旧天空/雾/体积光近似，不继续叠加独立艺术雾来冒充物理模拟。本文保留原迁移设计；当前实现状态见下方记录。Prime固定源码研究见 `prime-atmosphere-unified-migration-research.md`。

## 实施进度（2026-10-03）

阶段3之后用户报告树冠丁达尔光束缺失，已补上primary sky miss的近段RGB遮挡残差与局部体积散射；当前安装hash为`16838f7f38e203ccde586068c7b5e7a7d92a21a4d6ed6189eb013313ba0874df`。下面阶段3的primary-hit限定描述记录该阶段首次部署状态。最新修复、介质量级和未完成GPU图像验收见`tyndall-sky-shadow-fix-2026-10-03.md`。

02:20 normal-view截图显示1.84级浓度下仍无可读光束（配置已到2上限）。现已把气溶胶配置/UI范围放宽至0–16（AtmosphereSettings原支持该范围），保存值仍是2，未代改；浓度应用仍触发完整medium/LUT generation重建。新版mod SHA-256=`c414bc35d7521cfcf759df398ddf63d8e71654d0782f2386f9d45851436bebd7`。高密度会显著削弱直射光，待游戏逐档验收。旧mod及配置分别为`rtest-0.1.0.jar.bak-aerosol-range-20261003`与`rtest-client.toml.bak-aerosol-range-20261003`。

- **阶段1已接线并部署**：原雾密度滑块在 Prime 模式下控制0–16倍气溶胶。改变后完整重建不可变介质与静态场，上一帧fence完成后更新整组绑定并重置FSR/NRD历史；0倍仍保留气体/Rayleigh。海拔偏移只更新眼高与Sky动态历史。初始化不再静默回退旧天空。部署JAR及备份由对应任务记录。
- **阶段2已接线并部署，尚无GPU验证**：共享当前四波长介质和source-independent相机透射，使用同一Sky compute kernel、独立方向key/缓存图生成Moon SkyView。天空miss把Sun/Moon散射分别按太阳强度与月相/满月照度缩放。Prime模式改用手动PNG混合值；昼夜透明曲线只用于旧PNG天空路径。首次启动发现月光SkyView描述符集数为4但句柄数组仍为3，初始化在创建RT渲染通道前越界；已修正为4并重新部署。当前安装JAR SHA-256：`9716488edf00572c3c9f444935c2801b16ea1d92898198627ed1746db8586ed1`；故障版(stage2)备份为`rtest-0.1.0.jar.bak-render-fix-20261003`，阶段1 JAR备份仍为`rtest-0.1.0.jar.bak-stage2-20261003`。
- Moon LUT把月光光谱近似为反射太阳光谱，并按当前配置月球照度缩放；这不是月球反照率/光谱模型。PNG混合仍保留为艺术背景，不代表物理介质响应。天气仍由原可见度倍率控制，不构成天气介质闭环。
- **阶段3已接线并部署**：物理模式下局部体积光沿主相机命中段做1/2/4个分层随机积分样本，独立Sobol域选距离和光源。在样本点使用50% light-tree与50%全发光面均匀proposal的完整混合PDF，并重新追踪RGB透明阴影。viewT、sourceT及Rayleigh/四类气溶胶散射相函数来自当前介质；viewT仅计一次，随机信号继续进入NRD diffuse历史。安装JAR SHA-256：`7891787dc40a01c839373865504992827fd0d515b87ed3d9e0a24ecbfa380f72`；上一版JAR与配置备份分别为`mods/rtest-0.1.0.jar.bak-stage3-20261003`与`config/rtest-client.toml.bak-stage3-20261003`，配置字节保持一致。
- 验证：`./gradlew build --offline`通过，包含legacy/physical实际shaderc编译、混合PDF完整支持及均匀介质积分CPU检查、NRD接线检查。已有游戏日志确认阶段2修复版完成290次大气dispatch并持续输出RT+FSR渲染帧；阶段3尚无GPU图像或性能验证。

## 当前源码中已经物理化与仍混合的部分

- **介质密度运行时接线已完成**：`AtmosphereSettings.java`及当前配置/UI支持0–16倍。`AtmosphereMedium.load(steps)`重建gas+aerosol消光、缩放四气溶胶散射并重算总散射，保留Rayleigh。密度变化完整重建介质和静态LUT generation；高度偏移只更新相机坐标和动态Sky key。
- **静态场/动态天空生命周期已经接入**：`RayTracingAtmosphere`求解290dispatch、10批实际fence，保留finalbank0并释放scratch、bank1、六个静态pipeline和两个solver descriptor sets。`SkyLutHistory`只在实际framefence完成后commit眼高/太阳仰角key。不能为热更新简单改活跃SSBO，或跳过等待销毁。
- **有限段太阳/月亮T与L已经接入，但只是主相机段**：`RayTracingShaderRaygen.integratePhysicalAtmosphereSegment`用四波长介质及直接/多重源、4/8/16steps与RGB TLAS阴影。表面T在RayGen/AOV预乘；L在NRD/OFF后、FSR前统一加。不能再整体post-multiply T。
- **SkyView现含独立太阳/月亮方向结果**：两图使用同一介质与源方向无关的相机T，月相/强度不重建静态介质。Moon source仍使用反射太阳谱近似；背景、reflection/refraction miss共享各自Sky结果，但查询使用相机高度，不等于每个secondary origin的完整介质传输。
- **PNG仍是艺术背景插值**：当前两条昼夜曲线控制`mix(physicalSky,PNG,opacity)`，不是把PNG当远端辐射再通过大气的物理传输。用户若最终要求纯物理天空，这个曲线不应继续作为大气强度参数。
- **localEmitter物理分支已统一介质**：`samplePhysicalVolumeEmitter`使用实际体积点的proposal、RGBvisibility、两段透射及物理散射相函数，退出physical中的midpoint×fogWeight近似。RGB局部光按太阳谱加权的四谱响应近似转换；两段透射各用4点介质积分。当前仅覆盖主相机命中段，sky miss与secondary段局部散射未覆盖，细小光源的低样本方差和性能仍待GPU验收。legacy模式保留`sampleLegacyVolumeEmitter`旧效果，待阶段4退役。
- **legacy仍存在**：`integrateAtmosphereSegment`的独立RGB介质、夜间常数ambient、4倍密度/8倍可见散射scale；physical下还存在L-only strength与rain/thunder可见度缩放。physical分支正常运行时不同时再加legacy段，但系统并未只剩物理路径。

## 最终原则

同一不可变介质generation决定所有消光/散射/源场，光源能量与介质密度分离。概念上所有段都满足：`L_camera = T * L_boundary + integral(T * scattering_source ds)`，RGB局部阴影不替代光谱消光。密度改变后介质SSBO、optical、多重源及derived Sky/aerial必须一致；气溶胶0意味着保留气体/Rayleigh，不等于关闭整个大气。

四谱→RGB的消光转换仍有模型限制：Prime的RGB T是按太阳谱归一化，不是任意Moon/localEmitter谱的严格通用算子。统一介质不是立刻把整套RGB表面renderer称为精确光谱路径追踪；需要记录谱近似并验证彩色光源。

NRD的信号归属可保持分开，**物理模型统一不要求把所有信号都放到同一个滤波器**。表面/局部随机估计与camera-only L有不同history对应。不能让后续统一导致双T、双L，不能以把负shadow residual塞NRD隐藏不一致。

## 阶段1：让物理密度真正控制介质（推荐先做）

1. 增加明确的气溶胶密度/高度参数；将现有`AtmosphereSettings`输入接到owner构建及坐标，而不是用旧fogDensity去乘最终雾颜色。高度偏移只影响坐标/derived history，不必同密度一样盲目重算静态介质。
2. 密度确认应用时构建pending不可变介质generation，重新求解完整固定场；初版可明确显示重建，不应承诺每帧即时滑块解算。当前solver资源bootstrap后释放，热更新必须重新拥有所需资源，不能假设原pipelines还活着。
3. 保持旧active全套有效；pending完成真实fence、校验request仍最新后，在安全frame边界更新完整descriptor/介质generation并reset NRD/FSR。新请求淘汰旧请求；放弃未提交与已提交GPU资源的退休严格区分。
4. 通常失败保留最后完整有效generation；unknown retirement/device lost仍fatal，不允许伪装成功或继续legacy fallback。纯大气模式初始化不支持时应显式报告，不静默退回PNG/旧雾。
5. 从物理路径逐步解绑旧fogDensity和L-only strength；原配置暂保留兼容说明，不擅自覆写最新配置。以物理aerosol参数控制雾；太阳/月光强度仍是源能量参数。

验收：0/1/2倍气溶胶的gas/Rayleigh不变，medium与所有LUT使用同generation；临时构建失败/取消不破坏active；相同场景密度变化实际影响T与multiple而非仅L亮度。保留4/8/16sample及全部capture预算/barrier。

## 阶段2：日月共用同一介质，补完整天空源

用同介质响应独立处理太阳/月亮的方向、谱与能量；校准Moon disk、NEE、有限段和Sky都一致。若复用单位源Sky producer，月亮方向要有自己的chart/key/结果（不能拿太阳Sky纹理随意换moon参数就声称正确）。共享与源方向无关的optical/T；月相/强度只是源项，通常不重建介质。

最终纯物理天空将不以PNG昼夜透明度混合代替散射。过渡时保留用户原PNG功能，但明确艺术背景模式与纯大气模式；如果保留星空/艺术远端背景，应按边界辐射经T传输后加大气L，不能同时再叠完整旧天空。最终移除PNG天空模式前确认用户是否仍需要远端星空素材。

验收：full/new/quarter Moon的Sky、地面和finite段一致；太阳关闭不误清Moon；天空像素不再加重复camera雾；白天/夜间horizon与地面边界有固定窗口图像测试。

## 阶段3：将局部发光体积光并入同一介质

替换midpoint×fogWeight常数为沿段的真实介质scatter/extinction、phase及入射radiance积分。source到sample需几何RGBvisibility和介质透射，sample到camera的viewT只计一次。

不删掉localEmitter效果，也不更改原表面light-tree分布/PDF/MIS/RR。若继续复用primary emitter样本，必须保留原selectionPdf并证明volume所需源的proposal support完整；surface visibility不能无条件当sample visibility。若新增volume专用proposal，使用独立Sobol域、明确完整PDF与CPU/differential测试，别把旧surface selection概率换成新point概率却沿用旧权重。

随机local-volume信号继续正确进入NRD历史；不能粗暴将scene整体再乘T，或把随机估计全塞未滤波L图。验收彩色玻璃、水、细小发光面、primary未朝灯但volume可见、墙后遮挡和NRD/OFF。

## 阶段4：覆盖反射/折射等路径，退出旧物理近似

校对各段起点/实际高度而非一律cameraHeight；将secondary段介质T/L按path throughput进入对应lobe，不重复主camera T。局部气体是否存在于密闭空间/不同维度需要定义，全球地球大气source表不自动理解Minecraft屋顶遮挡；不能简单把multiple乘directSun shadow宣称解决所有洞穴漏光。

移除physical路径残留的常数nightAmbient、独立legacy RGB系数及纯颜色weather dimmer。天气若需要真实介质变化，走明确的physical settings/profile及generation更新，不能无界每tick重建290dispatch。Prime未提供的天气/局部介质模型另列任务，不冒充照搬即可得到。

最后才退役legacy分支及其UI。先证明physical-only完整支持、可视化/失败路径合格，再取消默认回退；不要凭grep移除JNI/public/reflection接口或误删材质许可证。

## epipolar/clipmap：独立性能选项，不是物理化前置

本轮primary复核还修正了旧理解：Prime `atmosphere_aerial.compute.slang:aerialStep` 明确对`(direct+multiple)`整体乘scalar terrain visibility；当前RTest adapter则仅direct乘RGB TLAS、multiple不乘direct阴影。这是本地近似选择，不能描述为完整照搬Prime aerial的阴影语义。后续先定义多重散射的局部遮挡模型及洞穴验收，不擅自把scalar可见性当RGB等价替换。

本地逐像素四波长积分也可以只用同一物理介质；**only atmosphere不等于必须先搬入Prime scalar clipmap**。Prime具体阴影能力以新研究的primary引用为准；既有评估表明scalar深度无法替代RGB水/玻璃体积透射。未来若改epipolar，必须扩展彩色透射/局部光支持并实测薄遮挡，不允许以降低样本、几何或删同步换速。

不把之前bootstrap walltime或Sky GPU时间当aerial/阴影/整帧性能证据。Prime epipolar Intel衍生许可/notice需单独导入；GPL组合发行不重标Intel文件或扩展Prime例外。

## 每阶段验证与发布

CPU介质/坐标/随机估计测试、actual legacy/physical shader compile+SPIR-V、资源生命周期/取消/失败测试，随后固定分辨率和固定相机/时间/天气的GPU像素与阶段计时。resize device-loss未修，不能通过变窗口测性能。source tests不是图像和GPU退休的证据。

分阶段部署必须停机检查、备份已安装JAR与最新配置、核验hash、原子替换并保持配置最新字节；随JAR保存对应源码。此设计文档没有执行任何新的部署。
