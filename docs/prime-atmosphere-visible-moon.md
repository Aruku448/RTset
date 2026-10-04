# 月盘、地面月光与有限段月光散射

## 本次扩展

上一版仅可见月盘、角半径0.00471rad，没有月光NEE；用户截图 `/tmp/Spectacle.pFdhIF/屏幕截图_20261002_225031.png` 显示月盘很小、地面暗。本次不是将整幅夜景提亮：月盘角半径改为0.01884rad，直径约2.16°（原来的4倍），加入实际地面月光选光/阴影及有限段体积月光。

`moonEnabled`默认true；F9新增`moonIntensity`（默认0.06，范围0–1），统一控制月盘、地面NEE与体积源。该值是艺术满月盘积分辐照度，不是lux或精确天文月光。强度0关闭月光能量，新月无发光。扩大月盘时通过固体角归一化，不凭面积额外放大光照。

## 游戏属性及ABI

使用MC26.2 `EnvironmentAttributes.MOON_ANGLE`和`MOON_PHASE`。`MoonPhase.index()`顺序为满月0、亏凸1、下弦2、残月3、新月4、娥眉5、上弦6、盈凸7。方向由MOON_ANGLE属性计算，与太阳采用同样的世界旋转和RT角度/方位偏移；无天空光维度禁用。真实月相属性仍以游戏时钟为准，不额外适配离线锁时钟。

保持304-byte camera布局、descriptor数量不变：闲置origin.w（12）、environment.w（124）、jitter.z（232）传moonDirection.xyz；jitter.w（236）为`phaseIndex+1+moonIntensity/4`，0禁用。强度最大1只占小数0.25，不影响round后的月相index；满月强度用`fract(token)*4`解码。位置依旧.xyz，既有jitter依旧.xy，previousCamera及字段offset不动。月相/强度变化在FSR beginFrame快照reset之前请求历史重启，正常每日运动不每帧重置。

## 月盘与表面NEE

程序化球面Lambert月盘、八相明暗，无额外纹理资源。统一`visibleMoonRadiance()`用于背景、反射/折射miss和NEE采样位置，线性Rec.2020；近似小角度投影下满月盘平均normal.z为2/3，辐射采用`1.5*fullIrradiance/solidAngle`，避免扩大月盘翻16倍能量。月相盘总能量的体积近似采用Lambert phase function `(sin(alpha)+(pi-alpha)*cos(alpha))/pi`。

NEE在独立稳定Sobol effect6上均匀采样月盘，PDF为1/solidAngle；对采样方向查询实际大气透射与RGB透明TLAS阴影，玻璃/水保持染色。月光用既有完整BSDF split进入directDiffuse/spec AOV，NRD和OFF共用原合成，不能只修改raw scene导致NRD擦掉月光。BSDF月盘miss用互补MIS，delta/primary为1。复核发现并修正：粗糙可折射玻璃的Moon NEE必须使用续射的实际Fresnel反射混合PDF，不能复用太阳NEE中的transmission-specular=1输入。原interface/refraction/三项归一化概率计算提前到NEE前共享，续射及BSDF/RR的选择规则不改，Moon调用同一diffuseProbability/specularProbability；终止bounce没有续射技术时，Moon NEE MIS为1。原Solar NEE输入不在本次修改范围；不借用太阳PDF，不重复采太阳，也不把月光层独立在NRD之后加到表面上。

月盘仍在PNG透明混合后加入，独立于PNG opacity。PNG若已画月亮会并存；暗面未完整遮挡PNG星空，不模拟月食。月盘radiance与月光强度关联，不能通过独立降低月盘曝光冒充能量一致。

## 体积

物理有限段仍按4/8/16steps、同一非均匀边界和viewT积分。太阳能量`sunIntensity/12.5`移动到积分内部；月光能量为`lunarIrradiance/12.5`，外部只乘共同strength，避免用太阳强度二次缩放或抹除月光。每步直接月光经四波长积分/Rec.2020转换后乘RGB TLAS阴影；多重月光用已有固定介质source字段在**月亮mu/nu**查询、保持不乘直接可见性，未重复加入地球ground尾项。源场是线性介质响应，不需要为另一恒星方向重建静态介质。

限制：月光反射谱近似为缩放太阳谱，未独立建立精确月球反射谱；月盘在体积中近似为中心方向/盘积分光源，不逐step全月盘采样。SkyView LUT仍只有太阳源，不宣称完整月光天空大气/Prime月球系统。legacy分支同step加入Rayleigh/aerosol单散射月光和RGB阴影，原夜间ambient保留。原localEmitter估计/PDF/NRD diffuse历史、原表面T预乘与FSR前L合成分工不变。

新增成本是有效月光时的每反弹至多一个月盘shadow query、体积每step至多一个月光shadow query及source读取；没有减采样/几何、增加capture预算或删除barrier。不能用之前Sky更新耗时证明月光成本，须真实GPU测量。

## 验证及限制

`test jar correspondingSource --offline`60tasks通过；actual physical/legacy RayGen与原query四variant编译并Vulkan1.2 SPIR-V验证。contract保留太阳约束并扩展Moon PDF/MIS、与实际玻璃续射混合PDF一致、终止bounce、surface AOV、RGB阴影、体积月亮方向和独立能量；phase/intensity spare lane的边界与八phase解码已覆盖。源码/数学/编译不是实际像素或驱动验证。

部署以实例backups/deployment.json为准，保留最新配置，不强制关闭或启动游戏。实机需固定窗口，比较露天地面/树冠与墙后、彩色玻璃/水、满月/弦月/新月、NRD/OFF和volume开关；月光有遮挡，不能要求洞穴、密集树荫都均匀提亮。
