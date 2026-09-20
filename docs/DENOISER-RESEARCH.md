# rtest 光线追踪降噪器调查

更新时间：2026-09-15

## 结论

在当前约束 **Linux + RADV + Vulkan + RX 7800 XT + Minecraft 原生 Vulkan 后端** 下，最适合 `rtest` 的现成方案是 **NVIDIA Real-time Denoisers（NRD）中的 `REBLUR_DIFFUSE_SPECULAR`，使用 SPIR-V 计算着色器**。

当前状态（2026-09-15）：

- NRD/ReBLUR 的 diffuse/specular、motion preparation、HDR composite 和 FSR3 链路已经接入；当前实现使用项目内的 Linux x86-64 bridge 与预生成 SPIR-V 资源。
- 透明材质已经进入 RT path loop，transmission radiance 路由到 specular signal；专用透明历史的画质仍未完成专项验收。
- 用户已确认 RT/FSR3 Vulkan 渲染能够正常开启并显示。
- 本文中的候选比较和“推荐实施计划”是调研背景/历史记录，不是当前开发计划；当前代码事实以 `docs/DEVELOPMENT.md`、`docs/MAINTENANCE.md` 和源码为准。

原始选型顺序（历史记录）：

1. **短期接入 NRD/ReBLUR**：质量和输入契约最适合当前的 diffuse/specular path tracing；NRD 不要求 NVIDIA GPU，关键是由应用自己创建 Vulkan 资源、pipeline、descriptor 和同步。
2. **先只支持不透明主表面**：完成运动、法线/粗糙度、view-Z、hit distance 和 diffuse/specular AOV 后，再增加透明反射/透射历史。
3. **如果不希望引入 NVIDIA SDK 许可**：实现项目自有的 SVGF/temporal-spatial denoiser。它更容易完全控制，但需要自行处理历史有效性、方差估计、disocclusion、firefly 和材质边界。

**AMD FSR Ray Regeneration 不适用于本项目**：官方要求 RX 9000 Series 或更新 GPU、DirectX 12 + Shader Model 6.6、Windows 11；本机是 RX 7800 XT，项目是 Linux/RADV/Vulkan。不能因为 SDK 中存在 `amd_fidelityfx_denoiser_dx12.dll` 就把它用于当前 Vulkan 进程。

## 候选对比

| 方案 | 当前硬件/API | 与 `rtest` 的匹配度 | 判断 |
|---|---|---:|---|
| NVIDIA NRD `REBLUR_DIFFUSE_SPECULAR` | 可使用其 SPIR-V；不依赖 NVIDIA 光栅/RT 硬件 | 高 | 首选；需要原生桥接或把 NRD 调度描述接到 Java Vulkan |
| NVIDIA NRD `RELAX` | 同上 | 中 | 更偏反射/镜面信号；第一阶段不如 ReBLUR 直接 |
| AMD FSR Ray Regeneration 1.2 | RX 9000+、DX12/SM6.6、Windows 11 | 不可用 | 排除 |
| 自研 SVGF | Vulkan compute，RX 7800 XT 可用 | 中到高 | 无第三方 SDK 许可；开发和调参成本较高 |
| Intel Open Image Denoise / OptiX Denoiser | 不是当前 Vulkan RT pass 的直接可插入方案；还会引入不同的设备/运行时路径 | 低 | 不作为实时 Minecraft 主方案 |

这里的“支持”指能否在当前项目中形成稳定、实时、可维护的 Vulkan pass，不等于算法在理论上不能运行在 AMD GPU 上。

## 1. NRD：最可行的方案

### 官方能力

NRD 官方仓库是 [NVIDIA-RTX/NRD](https://github.com/NVIDIA-RTX/NRD)。其 API 将降噪器描述为一组资源、pipeline 和每帧 dispatch；应用负责底层图形 API 的资源和命令记录。NRD 源码中包含 SPIR-V shader 输出路径，适合由 Vulkan 应用自行建立 compute pipeline。

重点候选是：

- `REBLUR_DIFFUSE_SPECULAR`：同时处理去调制后的 diffuse 和 specular radiance，并利用法线/粗糙度、view-Z、motion vector、hit distance 和历史。
- `RELAX`：更适合以反射/镜面为主的信号；如果后续把反射单独拆成稳定的 radiance AOV，可以再做对比。

官方仓库和头文件是算法/API 的一手来源：

- [NRD README](https://github.com/NVIDIA-RTX/NRD/blob/master/README.md)
- [NRD public header](https://github.com/NVIDIA-RTX/NRD/blob/master/Include/NRD.h)
- [NRD shader sources](https://github.com/NVIDIA-RTX/NRD/tree/master/Shaders)

### 本地已有的可复用实现

`/home/aruku/repo` 中已经有一个 NRD Vulkan realization，可作为 `rtest` 的设计参考，但不能直接复制运行：

- `native/nrd/README.md`：Prime 参考实现固定 NRD `4.17.4`、SPIR-V、无 NRI，并由 Java 侧拥有 Vulkan 对象；RTest 当前运行时版本为 NRD `4.17.3`。
- `native/nrd/CMakeLists.txt`：通过 `NRD_SOURCE_DIR` 构建窄 C ABI bridge，并导出 SPIR-V、纹理池描述和每帧 dispatch 描述。
- `src/client/java/dev/prime/render/vulkan/nrd/NrdNative.java`：读取 bridge 的固定宽度 ABI。
- `src/client/java/dev/prime/render/vulkan/nrd/NrdDenoiser.java`：创建 Vulkan 图像、compute pipeline、descriptor、常量 buffer，记录 NRD dispatch，并在提交完成后回收临时资源。

该文件是 `/home/aruku/repo` 中 Prime 参考实现随包的 Windows PE DLL，**不是 RTest 当前运行库**，不能直接加载到当前 Linux Java 进程。RTest 当前随包的是 Linux x86-64 `rtest/natives/linux-x86_64/libprime_nrd.so`，并使用资源目录中的预生成 SPIR-V；不要把 Prime DLL 改名或通过 Proton DLL 解决 Vulkan ABI 问题。

NRD 的许可不是项目当前已有的 FidelityFX MIT 许可。随仓库中的 `/home/aruku/repo/THIRD_PARTY_LICENSES/NRD-LICENSE.txt` 是 NVIDIA RTX SDKs License。正式集成前需要把许可、版本和来源说明放入 `rtest`，并确认发行方式符合该许可证。

### 与当前 `rtest` 的接口差异

当前 `RayTracingShaders.java`、`nrd_motion.comp`、`NrdDenoiser.java` 和 `RtestFsr3` 已维护独立的 NRD/FSR 输入：

- `diffRadianceHitDist` / `specRadianceHitDist`：diffuse/specular raw radiance 与 hit distance；transmission radiance 当前并入 specular；
- `normalRoughness`、线性 `viewZ`、NRD motion 和 FSR reversed depth；
- direct/indirect diffuse、emission、albedo/roughness 以及 reactive/transparency 相关 AOV；
- NRD dispatch、emission-safe HDR composite 和 FSR3 temporal reconstruction 之间使用显式 Vulkan barrier。

这些信号不应退回为单张 final-color history。NRD 的当前视觉质量、透明历史、长时间稳定性和性能仍需以实机专项记录为准。
然后使用以下顺序：

```text
Vulkan RT raygen
  -> diffuse/specular + auxiliary AOV
  -> NRD/ReBLUR compute dispatches
  -> HDR diffuse/specular/emissive/sky 合成
  -> 当前 FSR 3.1.5 temporal reconstruction/upscale
  -> Minecraft RenderTarget
```

FSR3 仍然负责时域重建/超分，不应被当作 RT 降噪器。NRD 历史需要在相机跳变、场景/纹理重建、尺寸改变、太阳方向大幅跳变和不可信 motion 时清空。

## 2. AMD FSR Ray Regeneration：官方方案，但当前不可用

本机 `/tmp/FidelityFX-SDK` 是 AMD FSR SDK 2.3 的本地副本。其 `Kits/FidelityFX/docs/techniques/denoising.md` 明确说明 Ray Regeneration 1.2 是机器学习 RT denoiser，并提供 direct/indirect diffuse、direct/indirect specular、dominant-light visibility、AO 和 specular occlusion 等 signal。

但同一份官方文档的 **Requirements** 明确列出：

- AMD Radeon RX 9000 Series 或更新；
- DirectX 12 + Shader Model 6.6；
- Windows 11。

官方 sample 文档还说明不支持 GPU 上 denoising 功能会被禁用。SDK 文档的接入步骤要求链接 `amd_fidelityfx_loader_dx12.lib`，并加载 `amd_fidelityfx_denoiser_dx12.dll`。此外，SDK 2.3 本地 `Kits/FidelityFX/readme.md` 的 Known issues 写明：`Vulkan is currently not supported in AMD FSR SDK 2.3`。

一手资料：

- [AMD FSR Ray Regeneration 官方技术文档](https://gpuopen.com/amd-fsr-rayregeneration/)
- [SDK denoising 文档](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/main/Kits/FidelityFX/docs/techniques/denoising.md)
- [SDK denoiser sample](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/main/docs/samples/denoiser.md)
- 本地 `/tmp/FidelityFX-SDK/Kits/FidelityFX/docs/techniques/denoising.md`，约第 1119 行；本地 `/tmp/FidelityFX-SDK/Kits/FidelityFX/readme.md`，约第 35--38 行。

因此，FSR Ray Regeneration 不能作为 RX 7800 XT/Linux/Vulkan 的候选。Proton 中的 `amdxcffx64.dll` 只服务 Windows/DX12 游戏的驱动替换路径，也不能为 Minecraft 原生 Vulkan 后端提供此 denoiser。

## 3. 自研 SVGF：保底和长期可控方案

如果 NRD 的 NVIDIA SDK 许可、Linux bridge 或 diffuse/specular AOV 改造成本不可接受，可以实现项目自有的 SVGF 风格 compute pass。原始算法的一手资料是 NVIDIA Research 的 [Spatiotemporal variance-guided filtering](https://research.nvidia.com/publication/2017-07_Spatiotemporal-variance-guided-filtering-real-time-ray-tracing)，不是某个黑盒 DLL。

建议的最小版本：

1. 第一 pass：保存当前 noisy radiance、线性 view-Z、世界/视图法线、motion、材质类别和 hit distance；
2. temporal pass：motion reprojection，深度/法线/材质拒绝，估计历史权重，并对亮度做 variance clipping；
3. 2--3 次 spatial bilateral atrous pass：按 view-Z、法线、材质和 hit distance 保边；
4. 最后再合成 emissive、天空、透明结果并送入 FSR3。

SVGF 方案可以复用当前已有的 motion/depth/history 资源，且不需要 NRD native bridge；但当前 shader 只有合成 radiance，仍需要补齐可靠的 primary normal、线性 view-Z、材质 ID 和 hit distance。植物 Alpha Cutout、玻璃/水和 emissive 应使用独立的 history rejection 或 reactive mask，不能使用普通不透明表面的历史权重。

## 实施状态记录（历史建议，不是当前开发计划）

### 阶段 A：先验证 NRD 输入契约（已完成最小闭环）

- 不透明主表面由 RayGen 写入线性 diffuse/specular raw signal、hit distance、primary position、材质和法线 scratch；Sundial 互斥时仍保留自己的 YCoCg 输入契约。
- `nrd_motion.comp` 在 NRD dispatch 前统一完成材质因子解调、YCoCg 打包、normal/roughness、linear view-Z、FSR reversed depth 和 2.5D motion。
- motion preparation 使用当前/上一帧相机的真实投影/旋转/相对位移；动态对象沿用 RayGen 已验证的 FSR motion，静态表面使用矩阵重投影。
- RT → motion preparation → NRD dispatches → composite → FSR 的阶段顺序由显式 Vulkan barrier 连接。
- sky 使用 65504 sentinel，composite 保留环境 radiance；emission 作为独立 current-frame AOV 恢复，不进入 diffuse/specular history。

### 阶段 B：FSR3 与 NRD 合成（基础闭环已完成）

- NRD 输出合成 linear HDR `sceneColor`。
- 保留 FSR3 的 reversed depth、motion、reactive/transparency mask；不把 NRD 的 `viewZ` 直接当作 FSR reversed depth。
- 相机、场景、TLAS、纹理图集或太阳方向发生不连续变化时，按当前代码路径 reset 对应 history。
- GI 层数可配置为 `1--4`；性能和画质仍需用户实机专项记录。

### 阶段 C：透明和材质（基础路径已实现，专项验收仍待完成）

- 玻璃、水和染色材质已进入 transmission path loop，支持 RGB transmission、IOR/色散和 Beer--Lambert 吸收；transmission radiance 当前路由到 specular NRD signal。
- Alpha Cutout、transmissive、emissive 继续使用不同的材质/反应性语义；专用透明 history branch API 已保留，但不把它误报为完整画质闭环。
- 透明边缘、彩色阴影、资源重载和长期 temporal 稳定性仍需用户实机专项验证。

## 最终决策

AMD FSR Ray Regeneration 仍不接入。`rtest` 当前采用 Linux SPIR-V NRD/ReBLUR 路线：

1. 使用官方 NRD 4.17.3 源码在本地构建 `native/nrd` Linux bridge；
2. `NrdDenoiser` 在 Vulkan 中自行创建 NRD 图像、compute pipeline、descriptor 和历史调度；
3. 第一阶段已接入独立 motion preparation、真实相机矩阵、diffuse/specular demodulation，并将 emission-safe 的 NRD 输出 composite 到 FSR3 输入；
4. 透明分支、太阳方向 history reset、validation debug view 和性能调优仍待后续阶段；
5. 继续保持现有 FSR3.1.5 Vulkan 作为超分/时域重建，不把它标成 RT denoiser。

当前随包资源为 Linux x86-64 `rtest/natives/linux-x86_64/libprime_nrd.so`，不是 Windows DLL。正式发行仍需复核 NVIDIA RTX SDK License，并保留 `third_party/NRD-LICENSE.txt`。

## 4. Sundial Alpha Build 2026-07-31：本地算法分析与独立实现

本地 shader pack：

`/home/aruku/.minecraft/versions/TeaCon 2026 建筑整合包 v6.2.1/shaderpacks/Sundial Alpha Build 2026-07-31.zip`

它不是 NRD，也不是 FSR Ray Regeneration，主要使用两层自研过滤：

- GI：当前帧 radiance + motion reprojection + 深度/法线/世界位置拒绝，使用历史帧上限（包内默认 48）累积；随后根据颜色方差、法线和深度做多次小半径 edge-aware spatial filter。
- Reflection：根据 roughness、法线相似度、反射深度和 hit validity 做空间邻域加权，不直接使用大半径 Gaussian blur。
- Irradiance cache：另有独立的空间/时间 cache 平滑，不能与屏幕空间 primary radiance history 混为一谈。

该 shader pack 的 `LICENCE.md` 允许个人使用和个人修改，但禁止把其代码片段用于其他公开项目。因此 `rtest` 没有复制其 shader 源码，而是实现了独立的 Vulkan compute 版本：

- 输入当前 RT radiance、primary normal、linear view-Z 和 motion；
- 用 2 张 ping-pong history color + 2 张 history metadata 保存颜色、历史权重、法线和深度；
- 每帧进行 reprojection、几何拒绝和 3x3 保边空间加权；
- 当前空间权重同时使用线性 view-Z、法线、粗糙度、颜色方差和 diffuse/specular hit distance，避免在方块轮廓和路径长度变化处串色；
- 历史颜色在混合前按局部亮度方差做上限裁剪，并将长期静止历史的权重限制为 0.92，优先抑制 firefly 和拖影；
- 通过 `sundialDenoiserStrength` 与 `sundialDenoiserHistory` 控制输出；
- 仍在 FSR3 temporal reconstruction/upscale 之前执行。

对应实现为 `SundialDenoiser.java` 与 `sundial_denoiser.comp`。该实现是实验性的独立算法，不声称是 Sundial 官方实现；如果将 `rtest` 对外发布，仍需遵守 Sundial pack 的许可限制，不能把 pack 源码或派生代码一起发布。

## NRD REBLUR 精细参数

F9 的“降噪”分类现在暴露 NRD 4.17.3 `ReblurSettings` 中适合实时调试的参数：命中距离重建模式、漫反射/镜面预滤波半径、命中距离权重、最小/最大模糊半径、波瓣角/粗糙度/平面拒绝、快速历史 Sigma、火花抑制、去遮挡阈值、主/快速历史长度、历史修复步长、Anti-Firefly、收敛曲线和有效距离。修改这些参数会自动重置 NRD history，避免旧参数生成的历史污染新结果。

Java 与 NRD C++ 之间使用固定大小的 ABI 8 tuning block；native bridge 会再次限制范围并调用 `SetDenoiserSettings`，不会跨边界暴露 NRD C++ 结构体。FSR3 仍只负责时域重建/超分，Sundial 开启时仍优先于 NRD。
