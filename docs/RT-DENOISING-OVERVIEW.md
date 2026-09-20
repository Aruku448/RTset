# RTest 当前光追降噪实现概览

> 用途：给外部渲染/降噪专家快速了解当前实现，便于请教。本文描述当前代码，不代表最终设计。

## 1. 一句话流程

```text
Minecraft 原版可见几何/材质
        ↓
CPU 捕获并建立 BLAS/TLAS
        ↓
Ray Generation Shader：每像素 1 条主射线 + 随机 GI continuation
        ↓
线性 HDR noisy diffuse/specular + 深度/法线/运动/材质 guides
        ↓
nrd_motion.comp：整理 guides、解调信号
        ↓
NRD 4.17.3 REBLUR_DIFFUSE_SPECULAR compute passes
        ↓
direct/emission 保持未过滤，和 NRD 结果合成
        ↓
FSR3 prepare/accumulate/RCAS/display
```

当前默认配置：

```toml
nrdEnabled = true
nrdStrength = 1.0
sundialDenoiserEnabled = false
```

NRD 和 Sundial 互斥，实际运行时只选择一个降噪器。

## 2. 初始数据从哪里来

### 2.1 场景几何

入口主要是：

- `RayTracingScene.java`
- `SectionCompilerCaptureMixin.java`
- `RayTracingProbe.java`

Minecraft 负责可见性、视锥裁剪、遮挡裁剪和视距。RTest 在原版 Section 编译完成时复制 `MeshData`，或者在必要时走 CPU fallback，读取：

- 三角形位置、UV、法线；
- Block Atlas 纹理；
- tint/albedo；
- alpha cutout / 透明材质信息；
- LabPBR normal/specular/emission；
- 水、岩浆、玻璃等材质的 IOR 和吸收参数。

这些数据上传到 GPU material buffer，并按 Section 建立 BLAS，再组合成 TLAS。

### 2.2 动态元素

玩家、生物、掉落物和部分方块实体通过原版最终提交点捕获：

- `PlayerModelCaptureMixin`
- `LivingEntityTextureCaptureMixin`
- `ItemEntityCaptureMixin`
- `BlockEntityRenderDispatcherMixin`
- `BlockEntityModelGeometryAdapter.java`
- `DynamicInstanceRegistry.java`

捕获的是原版已经完成动画、pose、可见部件和纹理绑定后的最终模型，不重复调用 `setupAnim()`。每个动态对象有：

- 当前 object-to-world transform；
- 上一帧 transform；
- 稳定 identity/slot；
- 当前和历史失效标志；
- 每三角形纹理 descriptor、UV、材质和 PBR 参数。

这些信息用于动态 BLAS/TLAS 和运动矢量计算。

## 3. 噪声是怎么产生的

当前不是固定的蓝噪声，而是每像素、每帧、每 bounce 的 PCG hash 伪随机序列。

相关代码在：

- `RayTracingShaders.java` 的 ray generation shader；
- `pcgHash()`；
- `randomFloat()`。

每个像素初始 seed 大致由以下内容组成：

```text
pixelIndex
XOR camera.random.x 的 bit pattern
XOR camera.random.y 的 bit pattern
```

其中 `camera.random.x` 是帧计数相关值，`camera.random.y` 是固定 seed。之后每次取随机数都会再次经过 `pcgHash()`。

### 3.1 主射线

每个 RT 输出像素发射一条主射线。射线位置加入 FSR 的当前 frame jitter：

```glsl
jitteredPixel = pixel + 0.5 + camera.jitter
```

因此相机 jitter 同时参与：

- 当前帧射线采样；
- FSR3 temporal accumulation；
- NRD 历史重投影。

### 3.2 光照采样

主射线命中后，当前 shader 可能执行最多 4 个 continuation segment（由 `giBounces` 控制，Primary 不计入）。每个 continuation 只选择一种路径：

- cosine-weighted diffuse hemisphere；
- GGX specular；
- transmission/refraction；
- 对太阳光单独执行一次直接光 shadow ray。

路径选择使用 Fresnel、metallic、roughness、transmission 等概率，并用 throughput 做无偏概率补偿。每个 bounce 还可能进行 Russian Roulette。

太阳采样使用独立的随机域，不消费 BSDF 的 seed，避免改变太阳采样后导致 BSDF 序列整体偏移。

### 3.3 当前噪声的主要来源

- 每像素只有一条主路径；
- diffuse/GI 半球随机采样；
- GGX 高光随机采样；
- 折射和色散路径；
- 太阳圆盘采样；
- Russian Roulette；
- FSR 低分辨率内部渲染和 jitter。

Primary miss 的天空盒采样会直接累加环境光。当前实现把 Primary miss 视为环境背景，通常不送入 NRD 的表面历史。

## 4. RayGen 输出的降噪输入

RayGen 每个像素向多个 storage image 写数据。核心输入如下：

| 图像 | 内容 | 用途 |
|---|---|---|
| `nrdNoisyDiffuse` | diffuse/indirect radiance + hit distance | NRD diffuse 输入 |
| `nrdNoisySpecular` | specular/transmission radiance + hit distance | NRD specular 输入 |
| `nrdNormalRoughness` | octahedral normal + signed roughness | 空间/历史边缘拒绝 |
| `nrdViewZ` | 线性 view-Z | 深度重投影和 disocclusion |
| `nrdMotion` | 当前像素到上一帧位置的 2.5D motion | temporal history 重投影 |
| `nrdMaterial` | primary albedo + roughness | 材质相关判断/预留 |
| `nrdPrimaryPosition` | primary hit 相对摄像机的位置 | motion preparation |
| `nrdSpecularMaterial` | primary normal + material flags | specular/transmission 判断 |
| `nrdDirectDiffuse` | 确定性的直接太阳 diffuse | 保持锐利，不进入普通滤波 |
| `nrdIndirectDiffuse` | 间接 diffuse | 进入 NRD diffuse |
| `nrdEmission` | emission + validity | 保持发光稳定 |

格式主要是 `RGBA16_FLOAT`，normal/roughness 为 `A2B10G10R10`，view-Z 为 `R32_FLOAT`。

### 4.1 Diffuse/specular 拆分

RayGen 将路径按 Primary BSDF 类型拆分：

- `directDiffuseRadiance`：主表面直接太阳光；
- `indirectDiffuseRadiance`：间接 diffuse 和 secondary-surface 光照；
- `specularRadiance`：反射；
- `transmissionRadiance`：折射/透射；
- `emissionRadiance`：发光。

当前 NRD 输入是：

```text
NRD diffuse  = indirectDiffuseRadiance
NRD specular = specularRadiance + transmissionRadiance
unfiltered   = emissionRadiance + directDiffuseRadiance
```

这样直接太阳光和可见 emission 不会被普通 temporal/spatial 滤波弄糊；反射和透射则共享 specular history。

## 5. NRD 分支：当前主降噪器

实现文件：

- `src/main/java/com/rtest/client/fsr/NrdNative.java`
- `src/main/java/com/rtest/client/fsr/NrdDenoiser.java`
- `src/main/resources/prime/shaders/nrd_motion.comp`
- `src/main/resources/prime/shaders/nrd_composite_simple.comp`
- bundled native library：`libprime_nrd.so`

使用版本：`NRD 4.17.3`，算法类型：`REBLUR_DIFFUSE_SPECULAR`。

### 5.1 Java/GPU 边界

`NrdNative` 是很窄的 native bridge，只负责：

1. 创建 NRD instance；
2. 读取 NRD description、SPIR-V pipeline、资源池和 dispatch 列表；
3. 写入 frame settings；
4. 写入 tuning settings；
5. 返回当前帧应执行的 compute dispatch。

NRD native library 不持有 Vulkan handle。Vulkan image、descriptor、pipeline、command recording 和生命周期均由 `NrdDenoiser` 管理。

### 5.2 每帧执行顺序

`NrdDenoiser.record()` 大致执行：

1. 判断是否需要 reset history；
2. 设置当前/上一帧相机矩阵、jitter、尺寸、frame index、delta time；
3. 绑定 RayGen 写出的 noisy images 和 guides；
4. 运行 `nrd_motion.comp`；
5. 按 NRD native scheduler 返回的 dispatch 列表执行 REBLUR compute pipelines；
6. 对 opaque 分支运行 `nrd_composite_simple.comp`；
7. 提交后才把当前相机和资源状态保存为下一帧 history。

### 5.3 历史失效条件

以下情况会让 NRD 重启/清空历史：

- 首帧；
- 显式 `forceRestart`；
- 场景 reset revision 变化；
- atlas view/sampler 变化；
- 太阳方向变化超过约 1°；
- 纹理或场景捕获发生需要重置的变化；
- 相机历史不存在。

运动物体使用动态对象的 current/previous transform 和 history flags。对象新出现、消失或变换失效时，相关像素不能继续使用旧历史。

### 5.4 NRD 调参

主要配置项在 `RayTracingClientConfig.java`：

- hit-distance reconstruction：`off` / `area_3x3` / `area_5x5`；
- diffuse/specular pre-pass blur radius；
- min hit-distance weight；
- min/max blur radius；
- lobe angle / roughness rejection；
- plane distance sensitivity；
- fast-history sigma clamp；
- firefly suppression；
- disocclusion threshold；
- main/fast history frame count；
- history reconstruction frame number/pixel stride；
- convergence scale/base/percent。

## 6. Sundial 分支：备用降噪器

实现文件：

- `src/main/java/com/rtest/client/fsr/SundialDenoiser.java`
- `src/main/resources/prime/shaders/sundial_denoiser.comp`

这是一个独立的实验性 temporal-spatial compute filter，不是 NRD，也没有复用 Sundial shader-pack 源码。

### 6.1 数据结构

它有双缓冲 history：

- `historyColor[2]`：历史颜色和 history confidence；
- `historyMeta[2]`：历史 normal 和 depth；
- 每帧通过 parity 交换读写 buffer。

输入使用：

- noisy diffuse；
- noisy specular；
- normal/roughness；
- view-Z；
- motion。

### 6.2 算法步骤

每个 8×8 workgroup invocation 对一个像素执行：

1. 解码 diffuse/specular 的 YCoCg；
2. 计算当前 normal、roughness、depth 和 hit distance；
3. 以 motion 将当前 UV 重投影到上一帧；
4. 对上一帧 history 做 4-tap bilinear sampling；
5. 每个 tap 单独检查 normal 和 depth compatibility；
6. 根据局部 3×3 luminance moments 对历史亮度做 clipping；
7. 用历史帧数计算 temporal history weight，最多约 0.92；
8. 对当前像素周围 3×3 做 normal/depth/luminance/color/roughness/hit-distance 加权空间滤波；
9. 将结果按 `strength` 与当前 noisy color 混合；
10. 保存 filtered color、confidence、normal 和 depth 到下一帧 history。

其空间权重包含：

```text
normalWeight
× depthWeight
× luminanceWeight
× colorWeight
× roughnessWeight
× hitDistanceFactor
```

`historyFrames` 控制最大历史长度，`strength` 控制最终滤波结果与当前 noisy 输入的混合比例。

## 7. 与 FSR3 的关系

降噪和超分是两个不同阶段：

```text
RayGen noisy signal
    ↓
NRD 或 Sundial
    ↓
FSR3 prepare inputs
    ↓
FSR3 accumulate / RCAS
    ↓
display
```

FSR3 自身还会使用：

- motion；
- depth；
- reactive mask；
- transparency composition mask；
- luma instability/shading change。

因此 motion/depth 错误会同时影响 NRD temporal history 和 FSR3 accumulation。

## 8. 目前最值得外部专家检查的点

1. **采样不足**：当前每像素只有一条主路径，GI 噪声主要靠 temporal accumulation 消除；是否应引入 blue-noise/sobol/scrambled sequence？
2. **BSDF 与 AOV 拆分**：direct diffuse 保持未过滤，indirect/specular/transmission 进入 NRD，这种拆分是否满足能量守恒和 NRD 输入契约？
3. **hit distance**：当前 hit distance 由首次 continuation 命中距离生成，miss 使用 denoising range；是否应分别记录 diffuse/specular 的严格第一有效 hit distance？
4. **motion vector**：动态物体使用 previous transform，静态物体使用 primary hit position 和 previous camera；请检查坐标系、jitter 和 reversed-Z 约定。
5. **历史失效**：场景增量更新、动态对象 slot 重用、材质变化和相机快速变化是否充分 reset history？
6. **颜色空间**：RayGen/NRD 使用线性 HDR radiance；原版 raster 当前不再启用 native fragment decode（`nativeColorDecodeEnabled=false`），请勿把 SDR 颜色直接与线性 radiance 混合。
7. **透明路径**：反射和透射使用独立 NRD branch，但当前透明/半透明原版 overlay 仍有部分走 raster fallback。
8. **验证方式**：当前主要是契约测试和实机观察，没有 GPU 逐像素 reference、噪声收敛曲线或 temporal ghosting 自动回归测试。

## 9. 后续演进路线（设计目标，不是当前实现）

下面是建议的目标架构。它不能通过简单修改几个 NRD 参数直接得到，必须分阶段验证。

### 阶段 0：保持当前稳定基线

```text
REBLUR_DIFFUSE_SPECULAR
Full RT resolution
1 path/pixel
```

不改变 NRD 类型和 native bridge，先保证输出信号语义正确。

### 阶段 1：信号正确性

按 A/B 顺序单独验证：

1. 确认 NRD 使用 LabPBR 最终 shading normal，而不是 geometry normal；
2. diffuse/specular 分别记录语义正确的 hit distance；
3. GGX 改为 VNDF sampling；
4. 接入 Material ID，并用于历史/空间拒绝；
5. 检查 motion、AOV 拆分和 history reset。

这一阶段继续使用当前 `REBLUR_DIFFUSE_SPECULAR`，避免同时引入新的 NRD 类型。

### 阶段 2：改进采样

保留 PCG 作为 hash/scramble 工具，增加 Sobol/Owen 或 blue-noise spatial scrambling。仍保持当前分辨率和 REBLUR 类型，以便独立测量输入噪声改善。

### 阶段 3：升级 native bridge

当前 `NrdNative` 的 `DenoiserKind` 是窄接口。后续不应继续堆叠 `DIFFUSE_SH`、`SPECULAR_SH`、`RELAX` 等枚举，而应改成描述驱动的接口：

```text
Denoiser identifier
required resources
pipelines
transient/permanent pools
dispatch descriptions
```

Java `NrdDenoiser` 只消费 resource/pipeline/dispatch descriptor，Vulkan 资源所有权仍留在 Java。这样以后切换 REBLUR、RELAX、SH 或透明分支，不需要为每种算法重新设计 Java ABI。

### 阶段 4：SH/SG 路径

在 native bridge v2 支持后，引入 `REBLUR_DIFFUSE_SH` / `REBLUR_SPECULAR_SH`，但第一版仍保持 full resolution 和原 ray budget。随后增加 full-resolution SG resolve，并保留普通 REBLUR 路径作为运行时 A/B 开关：

```text
低/当前分辨率 SH 输出
        +
full-res normal/roughness/depth/motion/material guides
        ↓
full-res SG resolve
```

必须先确认 SG resolve 能恢复 LabPBR normal 的高频响应，再进入分辨率优化。

### 阶段 5：异分辨率与异采样预算

最后才做性能优化：

- diffuse：half resolution，约 0.25--0.5 rpp；
- specular：full resolution 或 0.5 checkerboard；
- direct diffuse 和 emission：继续保持不降噪；
- NRD 后继续接 FSR3。

不要同时引入 SH、SG resolve、half-resolution 和 checkerboard，否则出现细节丢失时无法判断根因。

### 目标数据流

```text
RayGen
├─ direct diffuse ─────────────── 不降噪
├─ emission ──────────────────── 不降噪
├─ indirect diffuse ──────────── diffuse signal → REBLUR_DIFFUSE_SH
└─ specular/transmission ─────── specular signal → REBLUR_SPECULAR_SH

shared full-res guides:
normal/roughness + viewZ + motion + materialID

REBLUR/SH output
        ↓
full-res diffuse/specular SG resolve
        ↓
HDR composite → FSR3 → display
```

建议仍由一个中心化的 `NrdDenoiser` 管理 NRD instance、shared guides、camera history、reset revision 和 frame index；内部按 signal 拆分 diffuse/specular pipeline，而不是立即创建两个完全独立、各自管理 history 的 Java denoiser。

## 10. 关键源码索引

```text
RayTracingShaders.java                 RayGen、随机数、路径追踪、AOV 写入
RayTracingScene.java                   静态 Section/材质采集
DynamicInstanceRegistry.java           动态对象 current/previous 状态
DynamicTlasInstanceWriter.java         动态运动元数据上传
NrdNative.java                         NRD native ABI bridge
NrdDenoiser.java                       NRD Vulkan 资源与 dispatch
SundialDenoiser.java                   备用 temporal-spatial filter
nrd_motion.comp                        NRD 输入准备、解调、guide 处理
nrd_composite_simple.comp              NRD 输出合成
sundial_denoiser.comp                  Sundial 算法实现
RtestFsr3.java                         每帧 NRD/Sundial 调度入口
RtestFsr3Upscaler.java                 FSR3 调度和历史管理
```
