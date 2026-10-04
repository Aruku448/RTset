# Prime 大气移植状态

固定上游：26.3 / `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`。

## 2026-10-03 更新：阶段2源码接线

阶段2现已实现并部署，尚未进行真实GPU图像验证。安装JAR SHA-256为`add1713a4b8513a6cb1c29658c712f0d7ae1901a4ae7fe50cccf8ab086df4268`，阶段1 JAR保存在安装目录的`rtest-0.1.0.jar.bak-stage2-20261003`。原有Sun SkyView保留；另加Moon SkyView图和独立`SkyLutHistory`，由同一Prime 26.3 sky kernel按月亮仰角生成。静态四波长介质、source-independent optical depth和相机透射共享，Sky输出及key互相独立。Moon活跃且非新月时才更新其方向key；月相和满月照度作为shader端源倍率，不触发LUT重建。反射/折射及主ray miss调用同一Moon图查询。

Moon Sky使用反射太阳光谱近似，并将单位太阳天空响应乘以配置的月相照度/Prime太阳基准12.5。它保留Prime球壳与地面边界近似，不是月球实测光谱或Minecraft方块地表天空模型。当前天气仍是已有标量可见度，不是介质 profile 闭环。

Prime模式下天空盒混合使用手动opacity；昼夜透明曲线仅在旧PNG天空路径生效。PNG仍可作为艺术背景混合。`rayTracingAtmosphereShaderTest`通过，shaderc编译了RayGen与查询shader的物理/旧版两种变体；这不代表GPU LUT dispatch或图像正确性。

## 可见月亮

当前源码添加放大月盘及月光（游戏方向/八阶段月相、大气透射、反射与折射），地面月光NEE保留RGB阴影，有限段月光使用同介质源场；F9「月亮」默认开启，并有「月光强度」滑块。不是完整Prime月球或月光SkyView LUT移植，见 `docs/prime-atmosphere-visible-moon.md`。下面首阶段的「月亮未移植」为历史状态。

## 有限段物理空气透视

四波长有限段积分已接入，NRD/OFF共用FSR前太阳散射合成，表面透射与local-emitter历史仍保持RayGen分工。不是完整epipolar移植，详情及验证边界见 `docs/prime-atmosphere-finite-aerial.md`。Sundial已从源码删除。下面诊断部署与首阶段内容是历史记录，不能当作当前源码的未实现项。

## 最新诊断版部署

用户随后要求暂时关闭天空盒贴图以观察效果，已部署诊断版，见 `docs/skybox-texture-diagnostic.md`。新增 `skyboxTextureEnabled=false`，不采样旧PNG（包括反射/折射环境照明），物理LUT/太阳独立保留。`primeAtmosphereEnabled` 仍默认false，需F9手动开启。当前安装JAR SHA256为 `8485a2f48bc00811a50d19603191222c798586d28668d642a293e4eb14002f51`；部署时实例停止、已备份、当前配置逐字节保留。实际GPU结果仍待用户观察。下面是此前首阶段接线/验证的历史记录，不应把其中「未部署」解读为最新状态。

## 结论（首阶段历史记录）

**第一阶段运行时接线已实现，尚未部署或完成真实 GPU 验证。** F9「Prime 物理天空（实验）」/ `primeAtmosphereEnabled` 默认 `false`，不会自动替换现有天空。开启后尝试原生 Vulkan LUT；普通初始化失败或硬件限制不足回退原天空。设备丢失、超时或退休失败不能当作安全回退。

当前实例 JAR 仍为 `8fca869406606438ecc2cf161be01cef4574fb6bebdee0cf4449d21e0a1696e0`。没有写实例配置；本次连续只读检查观察到配置在外部继续变化（`34b55ffe…` → `bb418530…`），不是固定部署快照；后续部署必须备份并保留当时最新 bytes，不能恢复旧配置。

## 基础与构建

- 用户已确认 GPL 复用和资产倍率、LUT 提交状态、天空/太阳查询测试边界。组合 mod 标识 `GPL-3.0-only`；Prime 限定例外、Sky Tracer notice、来源记录分别保留。源码归档随 JAR 配套交付，不能免除其他 SDK 条款。
- 四波长介质/消光资产原样导入。默认 `AtmosphereMedium.load(100)` 为199584 bytes；资产测试校验固定 SHA256、0/2倍气溶胶倍率、保留 Rayleigh 项。
- 七个 compute entrypoint 的22文件最小闭包及 `SOURCE-MANIFEST.json` 原样保留。八轮 ping-pong 的公共计划实际是290 dispatch，最终 bank0，不是早期规划摘要的282。
- 精确 Slang 从官方 commit `84792eb15f9d9284ca451e435fdb3ca1d66393c2` 构建，实际版本 `2026.13.1-1-g84792eb15`。匹配 glslang/SPIRV-Tools 子模块和 wrapper，保留 `-O2 -emit-spirv-directly`。没有改版本字符串、混用候选库或删除优化参数。获取/构建及 hash 记录在 `third_party/prime-atmosphere-26.3/toolchain/`。
- 七份精确编译且通过 Vulkan1.2 `spirv-val` 的 SPIR-V 现已放入 `src/main/resources/prime/atmosphere/shaders/`。`processResources` 必须通过 `verifyPrimeAtmosphereResources`：对照已审查的精确产物记录和全部固定源文件 hashes，拒绝修改/混入 compatibility probe。普通 Java/JAR 构建不要求重装 Slang。
- `compilePrimeAtmosphere` 仍为显式源码重编译入口；改变来源后必须独立审查并刷新锁定记录与打包产物。编译历史记录中的 `runtime_integrated=false` 是生成记录时的基础层状态，不是运行时开关，也不是 GPU 执行证据。

## 原生 GPU 资源与提交

`RayTracingAtmosphere` 使用 Minecraft 已有 device/VMA，不创建第二设备或引入26.3 renderer：

- 独立33槽 compute descriptor layout，检查图像维度、descriptor/push-constant 限制和格式能力；实际七核没有引用的槽位不写入 descriptor。三个 descriptor sets 为两 solver banks 与最终 Sky set。
- 光学深度512×128 RGBA16F；两组 source3200×240 RGBA16F、mean160×40 RGBA32F、ground160×1 RGBA32F、high800×21 RGBA32F；Sky256×256 RGBA32F；方向透射8193×1 RGBA16F。物理采样数和资产系数没有降低。
- 静态初始化290次 dispatch，按32次分为10个私有提交，每批实际 fence 等待完成；逐步保留 compute RAW/WAR 屏障。所有图像从 UNDEFINED 转 GENERAL，介质 host 写入明确同步到 compute。
- 最后一个 fence 完成且私有 encoder 销毁后，释放两 solver sets、scratch、bank1 和六个静态 pipelines。最终 Sky set 没有这些引用，保留 bank0 和 Sky kernel。理论有效载荷峰值约44.25 MiB，常驻约7.97 MiB，**不含驱动/VMA对齐、pipeline/descriptor 分配；不是实测显存占用**。
- Sky key 只包含 eye radius/sun elevation float bits；方位角只旋转查询，不重建表。录制不等于提交完成：相关 Minecraft frame fence 真正完成后才 `completed(token)`，未执行的录制失败 `abandon(token)`，同键允许重试。非有限/越界输入拒绝进入 GPU。
- Sky 重写前同步 RT/compute read→compute write，写后同步到 RT/compute read。RT pass 先退休旧 frame 再上传16-byte独立相机参数，原304-byte相机 ABI与0–26 bindings 保留，新路径增加27–29。
- 超时不能释放仍被 GPU 使用的资源；退休失败会保留分配并抛出显式 `UnretiredWorkException`，父pass禁止将它当安全回退。独立审查发现多层finally可能覆盖原timeout、导致错误fallback，已改为保留primary、退休/cleanup错误加入suppressed，仅确认退休后close fence/encoder。后续复核确认核心修复成立，并补齐waitIdle返回设备丢失时的显式 `DeviceLostException`：允许安全清理但绝不继续fallback，原错误仍保留。该分支尚无真实驱动故障注入证据。

## 天空、太阳与降噪

- `AtmosphereCoordinates` 保留上游 Y=-64 datum、0.001 km/block、300 m 默认偏移和6360–6480 km shell；使用冻结/实时相机的绝对高度，不能使用 scene-relative Y。
- `RayTracingAtmosphereShader` 适配上游 chart、producer行mu搜索、log插值后 exp、sqrt方向透射坐标和球地面裁切，不自行拟合天空。
- 所有路径的 sky miss（含反射/折射）、太阳盘和太阳 NEE 共用物理查询。LUT 已是 linear Rec.2020，不经过 PNG EOTF/原色转换；Sky按 `sunIntensity/12.5` 匹配 baked solar source。盘与 NEE 保留原 solid-angle/PDF/MIS，透射查询使用实际方向，不能只查太阳中心。物理路径不重复乘旧 day/night 或 blackbody 校准，天气艺术倍率仍一致保留。
- 物理背景不再叠加旧相机有限段雾。实体表面仍保留现有 finite aerial/局部 emitter 体积光与 NRD/Sundial/OFF AOV 分工；没有模糊掩盖、降低几何/样本数或删除原同步。
- 没有 sky light 的维度仍使用原策略。开关变化重建 pass/FSR history，资源创建失败不会逐帧重复尝试；关闭重开可重试。

## 验证与剩余边界

- 坐标缺类先 red，再完成实际 datum/单位/上下限测试；history 的 NaN/Inf 输入先 red，再拒绝。资产读出/倍率/七份 SPIR-V binding 反射、prepare/abandon/retry/complete、query compute harness 与实际 RayGen 两个 variant 均通过；四份产物也通过 Vulkan1.2 `spirv-val`。
- 新 shader test 首次运行暴露 `Out of stack space`，已改为非 NUL native UTF-8源码 buffer；不是扩大 stack 隐藏问题。原 source contract 更新了新增分支的声明形式，保留物理能量/采样/PDF/AOV约束。
- `./gradlew test jar correspondingSource --offline` 通过61 tasks；精确七核重新编译及 SPIR-V validation 通过；Python4/4、diff whitespace 检查通过。
- **这些是 CPU/状态/编译/打包检查，不证明 GPU LUT 数值、像素、原生故障恢复或 FPS。** 本次未进行真实 Vulkan validation；没有启动 Minecraft 或替换实例 JAR。
- 这是阶段1混合模型：物理 aerial/epipolar、Sun-shadow clipmap、月亮/星空、完整天气/维度与曝光策略未移植。不能声称已完整等同 Prime 天空系统。旧 resize device-loss 也未修复，验证仍须固定窗口尺寸。
- 新 GPU query6–7 单独记录 `atmosphere_lut_ms`；原RT/post/total范围不变，仍不包含此前AS、天空更新或完整Minecraft帧。bootstrap日志的 `wall_ms` 包含编译/分配/提交/等待，不能称GPU时间。

## 复现

```bash
./gradlew test jar correspondingSource --offline
./gradlew compilePrimeAtmosphere -PprimeSlangCompiler=/absolute/path/to/exact/slangc --offline
python3 scripts/verify_prime_atmosphere_resources.py
```

兼容性探测仍只能输出隔离目录，不能用于打包：

```bash
python3 scripts/compile_prime_atmosphere.py --slangc /path/to/candidate/slangc --compatibility-probe
```

下一阶段：独立审查通过后，在固定窗口和现有画质/config下执行真实 Vulkan validation、首次 bootstrap、正午/黄昏/地平线/反射/折射、NRD/Sundial/OFF 切换及GPU时间检查。通过后再按停止实例、备份最新配置、原子替换和安装 hash 核验流程部署。
