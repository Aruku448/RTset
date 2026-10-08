# Sundial 2026-07-31 后处理源码核对

## 范围与来源

只读用户提供的 `/home/aruku/.minecraft/versions/RiaFst/shaderpacks/Sundial Alpha Build 2026-07-31.zip`，477 个 ZIP 条目，SHA256 `b3baf694dc8ab5e9b25caa0e96a85598a36d5a2046dccff9e44543479ad7db45`。以下引用均为 **ZIP 内路径和一基行号**，不是互联网版本。未执行包内程序，未修改运行代码。结论描述包内默认宏；用户在 Iris 中保存的外部配置及 SR 注入宏不在 ZIP 中，不能据此确认实际运行设置。

核心结论：该包的纯后处理为 **CoC / 运动向量 → DOF → TAA（或外部 SR）→ 运动模糊、曝光统计与 bloom 基础 → bloom 双向模糊 → bloom 拼合、曝光、调色和 tonemap → RCAS**。大气、体积光、反射追踪与光照求解另属渲染贡献，不应混称纯后处理。

## 通道执行顺序

编号按 composite、composite1 … composite14、final 的入口顺序列出。入口包含 programs 下实际实现；启用条件来自 `shaders/shaders.properties:67–83`。不同维度共用 programs，world-1/world1 不具备主世界 composite0/2 的同等入口。

| 通道 | 实际工作 | 主要输入 → 输出 | 依据 |
|---|---|---|---|
| composite0 | 云及远处天空/雾相关处理；主世界非 underground | 深度、天空和云 → 5 | `shaders/programs/composite/Composite0.frag:14–35`；properties:69 |
| composite1 | 折射、透明层、大气/水下/其他介质合成 | GBuffer、深度、材质、气象/云 → 0/4/5 | `Composite1.frag:10–31` |
| composite2 | 体积光/吸收；非 underground 且 VOLUMETRIC_LIGHT | 水/固体深度、阴影 → 4/5 | `Composite2.frag:19–35`；properties:70 |
| composite3 | 路径追踪/反射、材质相关求解 | GBuffer、体素、atlas、反射 → 1/2/4/5 | `Composite3.frag:12–33` |
| composite4/5 | 透明层反射过滤，尺度 6.0、2.5 | 4、深度 → 4 | `Composite4.frag:12–19`；`Composite5.frag:12–22` |
| composite6 | 反射按 mirrorWeight 合成，雨雪粒子光照，存 parallaxOffset | 2/4/5、天气材质 → 5 | `Composite6.frag:19–51` |
| composite7 | 自动/手动焦点、CoC 前景扩散、当前到上一帧速度、TAA 权重 | 5/7、深度、材质/法线、前后相机 → 1/4/5 | `Composite7.frag:151–253` |
| composite8 | 景深 gather、遮挡感知散景、可选光圈蚀变；仅 DEPTH_OF_FIELD | 4.w 扩散 CoC、5.rgb/CoC → 5 | `Composite8.frag:20–111`；properties:73 |
| composite9 | TAA 重投影、YCoCg 方差限制；外部带 jitter SR 时跳过内部 TAA | 1 速度/权重、4 历史、5 当前 → 5 | `Composite9.frag:110–150` |
| composite10 | mip bloom atlas 基础、运动模糊、亮度统计；保存颜色/深度/焦点历史 | 5 mip、1 速度、7 历史、深度 → 4/5/7 | `Composite10.frag:20–118` |
| composite11 | bloom 水平 9 taps，高速像素补充运动平滑 | 4 bloom atlas、5 颜色、1 速度 → 4/5 | `Composite11.frag:8–62` |
| composite12 | bloom 垂直 9 taps；转 1/2.2 编码 | 4 → 4 | `Composite12.frag:8–35` |
| composite13 | bloom atlas 小滤波；同时声明纹理格式及持久历史 clear 行为 | 4 → 4 | `Composite13.frag:9–40,54–76` |
| composite14 | 畸变、RGB 色散、bloom/雨雾、暗角、自动/手动曝光、饱和度/色温、tonemap、抖动 | 5 HDR、4 bloom、7.w 亮度、深度、0.w 天气、noise → 0 | `Composite14.frag:227–285` |
| final | 显示空间 RCAS | 0 → 默认帧缓冲 | `Final.frag:44–100` |

**SR 顺序限制**：composite10.vsh:3 设置 AFTER_SR，Common.vert:29–30 在它之前按 renderScale 缩小；composite9:142 在 SR_ENABLE && SR_ALGO_SUPPORTS_JITTER 时禁用内部 TAA。这证明源码准备了 SR 前后接缝；ZIP 本身没有给出外部超分框架实际 dispatch，不能把外部调度当作本包自有算法。

## 默认与可选设置

默认宏来自 `shaders/settings/GlobalSettings.glsl:89–99`、composite7/8/10/14/final 的文件头；界面分组在 `shaders/shaders.properties:153–173`。

| 功能 | 包内默认 | 配置项及默认数值 |
|---|---|---|
| TAA | 开 | TAA；SR 支持 jitter 时内部 resolve 自动让位 |
| 运动模糊 | 开 | MOTION_BLUR；STRENGTH=1.0；QUALITY=5（2–10） |
| 景深 | 开 | DEPTH_OF_FIELD；FOCUS_MODE=0 自动；MANUAL_FOCUS_DEPTH=100；FOCAL_LENGTH=.01；APERTURE_DIAMETER_SCALE=.26；COC_SPREAD_SAMPLES=10；DOF_SAMPLES=10 |
| 手部景深 | 开 | HAND_DOF、CORRECT_DOF_HAND_DEPTH；FOCUS_IGNORE_HAND 关闭 |
| 景深最大半径 | 1.0 | MAX_BLUR_RADIUS=1.0；实际采样最大半径 15 像素 ×该项×SR 渲染缩放 |
| 焦点平滑 | 默认项1.0 | centerDepthHalflife=1.0，但源码含 ×10，实际半衰期为该值/10 秒 |
| 光圈蚀变/猫眼 | 关 | APERTURE_CORROSION；LENS_DIAMETER_SCALE=1.0；APERTURE_CORROSION_OFFSET=1.5 |
| Bloom | 默认有效 | BLOOM_INTENSITY=1.2（0–10）；没有阈值提取开关；水下/天气额外加量不能仅靠此项归零 |
| 雨 bloom 雾 | 主世界天气条件 | RAIN_BLOOM_FOG_DENSITY=1.0（0–100） |
| 自动曝光 | 始终计算 | AVERAGE_EXPOSURE_STRENGTH=.60（0–1）；CENTER_WEIGHT=4.0（1–8）；TENDENCY=1.0（.01–50） |
| 手动曝光补偿 | 0 EV | EXPOSURE_VALUE=0（−10–10） |
| 暗角 | 有效 | VIGNETTE_STRENGTH=1.0（0–20）；禁用原版暗角 properties:5 |
| 畸变 | 数值0，效果关闭 | DISTORTION_STRENGTH=0（−1–1） |
| 色散 | RGB 均0，效果关闭 | CHROMATIC_DISPERSION_R/G/B=0（0–.3） |
| Tonemap | Uchimura | TONEMAPPING=uchimura，可选 ACES、AgX |
| 调色 | 默认中性参数 | GAMMA=1.0；SATURATION=1.0；COLOR_TEMPERATURE=6500K；注意色温函数并非归一化白点 |
| Uchimura | 默认选中 | CONTRAST=1.0；MINIMUM_BRIGHTNESS=0；BLACK_TIGHTNESS=1.0 |
| AgX | 备选 | AGX_LOOK=0，1 Golden、2 Punchy；AGX_EV_MIN=−7.5；MAX=6.0 |
| RCAS | 开 | FINAL_SHARPENING；SHARPENING_SRENGTH=.5（源码拼写如此）；LIMIT=.18；SHARPENING_DENOISE 关闭 |

没有查到独立镜头光斑、镜头污渍、胶片颗粒、LUT 调色或额外 FXAA 纯后处理功能。蓝噪声输出抖动不等价胶片颗粒。

## 数学、单位和依赖

### 景深、速度与 TAA

- CoC 为 `(z−focus)/(z*(focus−focalLength))*apertureScale/maxRadius`，前景负、背景正。自动 focus 下限为 max(focalLength+.01,.3)。composite7:158–196。
- 两阶段景深：先按圆盘/黄金角扩散前景 CoC（避免近物轮廓被背景覆盖），再 gather，以 CoC 的包含关系和深度符号决定 foreground/self 权重；并可用光圈圆相交做边角蚀变。composite7:198–223；composite8:26–108。
- 速度不是可直接当 RTset motion 用的原始值：写入 `v*abs(v)^(-.2)`，resolve/运动模糊读取再乘 abs(v)^.25，恢复原速度（零速度需实现防 NaN）。方向为 previousUV−currentUV。composite7:147,253；composite9:136；composite10:98。
- TAA 默认静止历史权重 .95，运动后最多减 .7，叠加屏幕边界与前帧深度验证。3×3 YCoCg 均值/标准差，2倍方差半径，历史做椭球截断，再混合；历史 Catmull-Rom 用5次双线性采样近似。composite7:238–247；composite9:34–69,83–124。
- 特别注意所谓 getCurrColorNeighborhood 虽计算邻域，最终返回 currentColor，没有实际空间平均。composite9:71–80。
- DOF 输出和 TAA 保存有人工曝光尺度/γ编码：composite8:111 `(.005*C)^(1/2.2)*10`，关闭 DOF 的 composite7:225 相同；composite9:150 `clamp(C*.1)^2.2*100`。因此后续通道的 HDR 数值不是输入 RT 辐射值原封不动。
- RTset 已有 NRD 与 FSR 时，不应再叠 Sundial 的内部 TAA；NRD 处理光照信号，FSR 处理时间重建，DOF/运动模糊另有视觉目的。接入点需根据当前引擎实际 HDR 输出决定，不能照抄 colortex 名称。

### Bloom

- **无 bright-pass 阈值**：整个 HDR 场景生成 mip，再四角+中心 tent 降采样存于对角 atlas；档位由 float exponent 检测。composite10:20–40,90。亮点自然更强，但暗景也有散光贡献。
- 水平/垂直核为中心 .2734375，±1 .21875、±2 .109375、±3 .03125、±4 .00390625，总和1。限制每个 atlas 层自身边界，避免越层污染。composite11:8–29；composite12:8–29。
- composite12 先γ压缩；13 再额外 atlas 小模糊（邻居左/下重复权重确实存在，不应自行假设对称核）；14 从七级读取，权重 .92^1…^7，除以5.084764，然后2.2幂恢复。composite13:54–73；composite14:40–82。**这等于在γ空间拼多尺度，而非严格线性多尺度卷积**。
- 合成 amount=.2*intensity +雪/天气编码分支1.0 +水下.6 +更深介质1.0，结果 `(C+B*amount)/(1+.5*amount)`。所以 bloomIntensity=0 并非任何状态完全关闭 bloom。composite14:263–265。
- 雨雾用深度、天气平方与天空亮度生成 exp2 衰减，将场景颜色向 bloom 混合，是屏幕空间艺术效果，不是实际介质多散射。composite14:249–262。

### 曝光、调色和 tone mapping

- 统计8×8格点，共64次高 mip 取样，统计值是 RGB 等权平均的广义幂平均，**不是 Rec709 luminance 或 log-average**。CENTER_WEIGHT=4使权重显著集中画面中心。根据源码代数，TENDENCY=1时 currBrightness=2×加权 RGB均值。composite10:44–68。
- 时间适应权重 clamp(dt*(step(curr,prev)*2+2)+首次初始化,0,1)：暗下去快4/s、亮上来2/s，非指数时间步无关平滑。统计放 colortex7 首像素 alpha，焦点放末像素 alpha。composite10:67,107–116；Common.vert:35–54。
- 曝光应用 `C*(brightness+1e-5)^(-.6)*.2*2^EV`，再饱和度、色温、tonemap。composite14:90–93,267–278。
- Uchimura toe/linear/shoulder，P=1,m=.22,l=.4,c=1.33*blackTightness，contrast改变线性斜率、minimumBrightness抬底；最后1/(2.2*gamma)幂。composite14:98–125。
- ACES 使用输入/输出3×3矩阵和RRT/ODT拟合，前置×1.7、输出γ编码；AgX 使用输入矩阵、前置×7、log2范围[−7.5,6]、六次多项式、可选 look、逆矩阵及1/gamma幂。三种默认输入增益不同，切换不会保持同一中灰曝光。composite14:149–224。
- 饱和度用约Rec709权重(.2125,.7154,.0721)；色温是黑体近似乘RGB，没有参考白色除法，6500K不严格恒等。RTset 如在线性Rec2020工作，需要先转换至约定色彩空间，避免直接使用这些权重/矩阵。Common.glsl:110–112；composite14:128–147,273–276。
- 畸变为径向 `offset*(1+k*r²)` 并全局缩放；色散每个通道独立往图像中心缩放，属于坐标采样效果。暗角 `exp(-2*r²*strength)`。composite14:85–87,228–243。
- tone mapping 后加入幅度2/255的零均值蓝噪声并 clamp[0,1]。64×64噪声贴图叠加 frameCounter&63 的无理数偏移。composite14:280–282；Common.glsl:53–58。

### RCAS 与性能

final 使用十字5采样 RCAS，负lobe限幅为limit×strength，抑制overshoot；可选噪声检测降锐化。输入为已经 tone mapped 的 colortex0。Final.frag:44–100。RTset FSR 若已开锐化，需要选择单一最终锐化位置，避免叠加。

额外全分辨率 pass：7–14 共8个 +final；部分 atlas pass仅对对角区域有效，仍有全屏fragment/discard开销。DOF约10扩散+10gather，TAA邻域9+历史5，运动模糊默认5和局部5，bloom七级采样及水平/垂直9核。曝光统计 getAvgBrightness 只在首像素，但其 mip 生成本身有带宽成本。RTset Vulkan可用独立 mip 纹理/compute downsample替代atlas，保持可见功能同时减少重复全屏和坐标破解；性能是否更好须实机 GPU timestamp验证。

## RTset 集成建议

1. 明确只改 main 后处理，保留完整实时 RT、RGB 透明阴影、材质和 NRD。先核对现有 HDR工作空间与呈现 γ，建立单一曝光/tonemap出口。
2. 第一批：自动曝光及 EV、七级无阈值 bloom、Uchimura/ACES/AgX、gamma/saturation/white balance、暗角、输出抖动、最终锐化选择。尽量在降噪与 HDR 超分后处理，保证 HUD不参与曝光/bloom/景深。
3. 第二批：由真实线性深度与motion构建运动模糊、CoC前景扩散及gather景深，自动/手动focus、手部处理、光圈蚀变。透明深度与手部在 RTset 是否可获取要单独核对，不能假造Sundial的材料ID。
4. 畸变/色散数值默认0，提供界面功能即可；雨 bloom雾单独开关和语义，避免覆盖既有真实大气介质。
5. 保留 FSR 作为时间重建，避免添加重复TAA。效果相近不要求复刻Iris翻转纹理、atlas布局、压缩速度和历史像素塞参数。
6. 验证重点：纯色能量/曝光步长、尺寸变化和相机切换历史重置、黑帧有限数、高亮颜色范围、bloom层边界、CoC前后景边缘、UI排除、FSR重复锐化，以及各pass GPU时间。照片默认风格可提供“Sundial参考”预设，默认数值须按RTset辐射尺度重新校准。

## 许可事实

ZIP 条目中未发现独立 LICENSE/README/COPYING 文件；GlobalSettings.glsl:1 的 AUTHOR=GeForceLegend。Final.frag:12–30 内有 AMD 2025 copyright 与 MIT式许可全文，要求保留声明；Composite14.frag:128 标注色温片段 CC BY 3.0 来源，:149 标注 AgX来自Shadertoy；Composite9.frag:12 标注TAA参考Playdead。其他文件还有各自第三方声明。**局部声明不能推出整个包有同一许可。** 本研究只记录流程、参数及数学说明，不复制整段shader；实现功能可独立编写，若复制第三方部分需核对其拥有的许可及保留要求。
