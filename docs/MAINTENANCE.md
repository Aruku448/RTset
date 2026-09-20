# RTest 维护手册

本文档面向后续维护、升级 NeoForge/Minecraft、排查 Vulkan 崩溃和发布 Mod 的工作。

## 1. 维护原则

### 资源所有权

Vulkan 原生资源只能由 `RayTracingVulkanPass` 创建和释放。调用方只通过以下接口使用它：

- `create(...)`
- `matches(...)`
- `dispatch(...)`
- `close()`

不要在 `RayTracingProbe` 或 `RayTracingSmokeTest` 中直接销毁 Buffer、Pipeline、Descriptor 或 Acceleration Structure。`RayTracingVulkanPass` 在 GameRenderer 的 before-hand seam 追加显示拷贝时借用 Minecraft 的 device-owned shared frame encoder；该 encoder 不属于 RTest，不能销毁或抢先独立提交。

### 生命周期

资源生命周期为：

```text
无资源
  -> 场景捕获
  -> RayTracingVulkanPass.create
  -> 首次 Dispatch 构建 BLAS/TLAS
  -> 后续 Dispatch 只更新 UBO 和 Trace
  -> 场景/RenderTarget/Atlas 改变
  -> close
  -> 重新 create
```

`close()` 必须保持幂等。修改资源字段时，必须同步检查：

1. `create` 的正常路径；
2. `create` 的异常清理路径；
3. `close` 的正常路径；
4. `RayTracingSmokeTest` 的资源替换路径。

### 配置

所有用户可调节的客户端参数进入 `RayTracingClientConfig`，不要散落在 Shader 字符串或 Probe 静态字段中。配置修改应通过 F9 界面或 `config/rtest-client.toml` 完成。NRD/ReBLUR 现在默认启用（源码默认 `nrdStrength=1.0`），与 Sundial 互斥；如果实机出现异常，先在 F9 将 NRD strength 降到 0 或关闭 NRD，再收集日志。`fsrQuality` 支持 `native_aa`、`quality_75`（75% 线性内部渲染尺寸，4:3 upscale ratio）、`quality`、`balanced`、`performance` 和 `ultra_performance`；它改变输入尺寸和 FSR 资源，下一次 F8/场景重建时重新创建。

FSR3 使用 `src/main/resources/prime/shaders/fsr3/fp32/` 中的 FidelityFX 3.1.5 SPIR-V 和独立 Vulkan compute pass。它需要上一帧历史、reversed depth、motion vectors、reactive mask 和 jitter；不要把它描述为 FSR4，也不要在没有这些输入的情况下复用其历史图像。RT 输出会先经过 NRD 4.17.3 `REBLUR_DIFFUSE_SPECULAR`，再交给 FSR3；NRD 的 Linux bridge 位于 `native/nrd/`，运行时库为 `rtest/natives/linux-x86_64/libprime_nrd.so`。

## 2. 常见日志和故障

### 非 Vulkan 后端

日志通常包含：

```text
RTest ray tracing is disabled because Minecraft is not using the Vulkan backend
```

检查：

- 游戏是否使用 Vulkan；
- `VulkanBackendMixin` 是否成功应用；
- 设备是否创建成功；
- 是否启用了 RT 扩展和 Buffer Device Address。

### RT 限制查询失败

检查 `RayTracingSupport`：

- `VK_KHR_acceleration_structure`；
- `VK_KHR_ray_tracing_pipeline`；
- `VK_KHR_buffer_device_address`；
- `maxRayRecursionDepth` 是否至少为 `1`；
- SBT alignment 和 scratch alignment 是否满足设备限制。

### FSR3 创建或 dispatch 失败

检查：

- `prime/shaders/fsr3/fp32/` 和 `prime/shaders/fsr_display.comp.spv` 是否打包进 JAR；
- FSR 输入图像是否为 `rgba16f` scene color、`rg16f` motion、`r32f` reversed depth；
- RayGen 的 7--16 FSR/NRD bindings 以及 20--23 signal-split bindings 与 `RtestFsr3`/`NrdDenoiser` 的图像顺序一致；
- 相机移动/切换场景时是否触发 history reset；
- `VK_ERROR_DEVICE_LOST` 时先把 `fsrQuality` 改为 `native_aa`，再检查单帧 RT 和资源大小。

AMD FSR Upscaling 4.1.1 已支持 RX 7000（包括 RX 7800 XT），但官方 SDK 2.3 仅提供 Windows/DirectX 12 的签名 DLL，且明确标注 Vulkan 当前不受支持。因此本项目的 Linux/RADV/Vulkan 路径仍不能启用 FSR4，不能通过 FSR3 SPIR-V 冒充支持；详细调查见 [`docs/FSR4-RESEARCH.md`](FSR4-RESEARCH.md)。

### Shader 编译失败

运行时 Shader 由 `Shaderc` 编译。检查：

1. Shader binding 与 Descriptor Layout 是否一致；
2. UBO 字段顺序、大小、对齐是否一致；
3. Payload location 是否匹配；
4. Shadow Ray 使用的 Miss/Hit group 是否存在；
5. Pipeline recursion depth 是否为 `1`；Shadow Ray 从 Ray Generation 发射，不在 Closest Hit 内嵌套；
6. `RayTracingShaders.java` 是否出现字符串转义或文本块格式错误。

修改 Shader 后，优先查看最新日志中的 Shaderc 错误，而不是先修改 Java Vulkan 代码。

### 树叶、草、花、藤蔓显示为完整矩形

检查 Alpha Cutout 路径：

1. `RayTracingScene` 是否将 `ChunkSectionLayer.CUTOUT` 写入材质的 Alpha Cutout 标志；
2. `RayTracingShaders.ANY_HIT_SHADER` 是否使用 `textureLod(..., 0.0).a`；
3. Any Hit 是否调用 `ignoreIntersectionEXT`；
4. Primary hit group 和 Shadow hit group 是否都绑定 Any Hit Shader；
5. Alpha 阈值是否与 Minecraft 的 `CUTOUT_TERRAIN`（当前为 `0.5`）一致；
6. Shadow Ray 的 `SkipClosestHitShader` 是否跳过可传输材质，避免玻璃/水产生完全不透明的硬阴影。

Any Hit 不能依赖隐式导数，因此不要在这里使用普通 `texture()` 进行 Alpha 测试。Primary Any Hit 和 Shadow Any Hit 必须共享同一套 Alpha Cutout 逻辑；Shadow Any Hit 还要跳过 transmissive 材质，并交给 Shadow Closest Hit 标记不透明遮挡。

### 黑屏或全黑

按顺序检查：

1. 是否按 F8 在 3D 世界中启动；
2. 日志中的 `centerPixel=0x0` 是否表示没有完成的 readback；`0xff000000` 可能是有效的纯黑像素，不能把所有黑色画面误判为未渲染；
3. `RayTracingScene` 是否捕获到三角形；
4. Sky/Block Light 是否为零；
5. Camera UBO 的 `sun` 和 `environment` 偏移是否正确；
6. Shadow Ray 是否错误地将所有命中都视为遮挡；
7. Output Buffer 到 RenderTarget 的 Image Barrier 是否完整。

### 画面紫色或背景不正确

紫色通常表示旧版 Miss Shader 的诊断颜色，不代表纹理本身缺失。检查：

- 当前 `MISS_SHADER` 是否使用程序化天空；
- RT 最大距离是否与原生视距同步；
- 场景 Section 是否来自本帧的 `IRenderableSection`；
- 远处区块是否已经加载并进入原生可见 Section 列表。

### 摄像头移动后区块缺失或残留

这是 RT Section capture window 未跟随摄像头移动的表现。检查：

1. `latest.log` 是否出现 `RTest queued camera-window update`；
2. 日志中的 `remove ... sections, capture ... sections` 是否随 chunk 边界移动变化；
3. 当前有效视距是否与 `minecraft.options.getEffectiveRenderDistance()` 一致；
4. 新区块未立即加载时，等待对应 `onChunkLoaded` 或下一次窗口差分；
5. 不应通过整场景重捕获解决普通摄像头移动，正常路径必须使用 Section 增量更新。

### 反射、折射错误或出现 Vulkan Validation 错误

当前 path tracer 不再使用 Reflection/Refraction/GI Closest Hit 分支。检查：

1. Ray Generation 的 path loop 每个 bounce 是否只发射一次主 `traceRayEXT`；
2. continuation 是否只选择 diffuse、specular 或 transmission 之一；
3. Shadow Ray 是否使用 `TerminateOnFirstHit` 与 `SkipClosestHitShader`；
4. Active Closest Hit 是否只写回 path material payload，不包含任何 `traceRayEXT`；
5. Pipeline 的 `maxPipelineRayRecursionDepth` 是否为 `1`。

如果自定义镜面仍像普通方块，检查场景捕获日志中的 `loaded ... PBR companion texture sets`：

1. Quad 的 sprite 名称是否能映射到 `textures/<sprite>_n.png` 和 `_s.png`；
2. 资源包是否使用标准 PBR 伴随文件名，而不是仅放在 OptiFine CTM 私有目录；
3. `_s` 是否按 LabPBR 解码：红色 smoothness、绿色 metalness、蓝色 porosity（当前忽略）；
4. 当前 PBR 纹理已经通过 binding `5` 在 GPU 命中点逐像素采样；三角形材质只保留无 PBR 时的 fallback。

### Path bounce 没有颜色反弹或画面噪声明显

检查 Ray Generation path loop：

1. 每个 bounce 是否先清空并填充 path payload；
2. Miss Shader 是否将 `pathPosition.w` 写为 `0`；
3. Closest Hit 是否只返回位置、法线、材质、光学和光照数据；
4. continuation 是否按 mixture PDF 采样并执行 `f * cos / pdf` 的估计；透射仍是明确标注限制的单界面近似；
5. `giBounces` 是否限制 path loop 的最大 bounce 数；
6. `environmentState` 是否同步写入时间、雨强、雷暴强度和夜晚因子；
7. Pipeline recursion depth 应保持为 `1`，不要将递归逻辑重新放回 Closest Hit。

### HUD/UI 消失

RT Pass 必须运行在 GameRenderer 的 Caustica 风格 before-hand seam：

```text
LevelRenderer 完成 → RT/FSR → hand / screen effects / crosshair
```

不要把 RT 移回 `RenderFrameEvent.Post` 或 `AfterOpaqueFeatures`。`AfterOpaqueFeatures` 只负责实体/场景捕获。

## 3. Minecraft/NeoForge 升级步骤

升级 Minecraft 或 NeoForge 时，按以下顺序检查：

1. 更新 `gradle.properties` 和 NeoForge 版本；
2. 重新生成/应用 Minecraft 源码；
3. 检查 `Camera` API、`ClientLevel` API、`BlockStateModel` API；
4. 检查 `GameRenderer.renderLevel` 的 before-hand seam 和 `RenderLevelStageEvent.AfterOpaqueFeatures` 是否仍然存在；
5. 检查 `IRenderableSection` 的 Section 原点和可见性语义；
6. 检查 `TextureAtlas`、`VulkanGpuTextureView`、`VulkanGpuSampler` API；
7. 检查 Vulkan 常量和 LWJGL 结构体布局；
8. 运行 `clean build`；
9. 手动验证 F8/F9、UI、视距、昼夜和阴影。

不要只依靠 Java 编译通过。Minecraft 图形 API 的改动经常能够编译，但会在 Vulkan Pipeline 或 Command Buffer 执行时失败。

## 4. 版本控制与文件管理

源码文件：

```text
src/main/java/com/rtest/
src/main/resources/
docs/
README.md
```

不要提交：

```text
.gradle/
build/
run/
logs/
*.class
*.jar
hs_err_pid*
```

开发游戏实例和源码项目必须分离。发布 Jar 只能通过 Gradle 生成，不要手动复制 `build/` 中间文件作为源码资源。

客户端配置由游戏实例管理：

```text
<game-directory>/config/rtest-client.toml
```

不要把个人配置、游戏日志和截图复制到源码仓库。

## 5. 发布前检查

```bash
export JAVA_HOME=/home/aruku/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build --no-configuration-cache
```

确认：

- `build/libs/` 生成目标 Jar；
- `neoforge.mods.toml` 版本正确；
- `assets/rtest/lang/` 文件存在；
- Client Config 可以加载；
- 无意外修改游戏实例中的配置；
- 安装到测试实例后 F8/F9 正常；
- 日志没有 Vulkan Pipeline、Descriptor、SBT 或资源释放错误。

## 6. 已知限制

- 当前没有自动化 GPU 画面回归测试；Java 契约测试和用户实机确认不能替代长期画质/稳定性测试；
- 正常帧使用 submission-scoped `GpuFence` 等待本次提交完成；`graphicsQueue().waitIdle()` 只保留在最终资源销毁或异常清理兜底，不是正常帧同步；
- 透明介质目前在统一 path loop 中使用一次 transmission continuation，并将 transmission radiance 路由到 specular signal；这不是完整的多层体积光；
- 水体几何使用 CPU `FluidState` 高度近似原生 `FluidRenderer`，尚未直接复用原生 GPU 流体 Mesh；
- 每个 path bounce 只选择一个 continuation，GI/path 层数可配置为 `1--4`；NRD/ReBLUR 主链处理 diffuse/specular signal，透明路径的视觉质量仍需单独实机验收；
- 直接光 Shadow Ray 从 Ray Generation 发射，并使用 `TerminateOnFirstHit`，由独立 Shadow Any Hit/Closest Hit 处理 Alpha/transmissive/opaque 遮挡；
- 普通生物主体、玩家 body、第三人称手持物、第一人称 body/item、成功捕获的掉落物模型和白名单方块实体模型在 `dynamicEntityMvpEnabled` 开启时可进入动态 BLAS/TLAS；复杂实体 layer、透明 layer、名称牌、特殊物品 baked model，以及 block entity 的特殊 renderer 仍保留原生 raster 或 fallback；
- Minecraft 区块模型仍由 CPU 捕获并重建，不是直接复用原生 GPU Section Mesh；
- Vulkan validation layers 可能未安装；
- RT Pass 当前是渲染目标原型，不是完整的 Minecraft Renderer 替换。
