# RTest

当前状态：RT/FSR3 Vulkan 渲染已由用户实机确认可以正常开启并显示；视觉质量、性能和部分动态对象仍以明确的实机记录为准，不把契约测试当作画面验收。

## Code organization

- `RayTracingSmokeTest`: small public RT-pass facade and failure handling.
- `RayTracingScene`: native visible-Section capture and scene/material validation.
- `RayTracingVulkanPass`: Vulkan RT resources, descriptors, pipeline, SBT, synchronization, and cleanup.
- `fsr/RtestFsr3`: local FidelityFX FSR 3.1.5 temporal reconstruction/upscaling and NRD/ReBLUR compute resources.
- `RayTracingShaders`: embedded RT shader sources and NRD signal outputs.
- `RayTracingClientConfig` / `RayTracingSettingsScreen`: persistent client parameters and the F9 settings entry.

NeoForge 26.2 shader development project.

接手工作先读 [`docs/HANDOFF.md`](docs/HANDOFF.md)；详细开发流程见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)，实例验证记录见 [`docs/instance-validation.md`](docs/instance-validation.md)，维护与故障排查见 [`docs/MAINTENANCE.md`](docs/MAINTENANCE.md)。`docs/PRIME-COMPARISON.md`、FSR/NRD 调查和渲染审计是参考资料，不是当前执行计划。

## Environment

- Minecraft: `26.2`
- NeoForge: `26.2.0.84`
- Java: `25`
- Mod ID: `rtest`
- Shader resources: `src/main/resources/assets/rtest/shaders/` and `src/main/resources/prime/shaders/`

## Commands

Use Java 25 to run Gradle:

```bash
./gradlew build
```

The development client uses `/home/aruku/.minecraft/versions/RTest/` by default. To override it:

```bash
./gradlew runClient \
  -Pgame_directory=/home/aruku/.minecraft/versions/RTest \
  -Pgraphics_backend=vulkan
```

When Vulkan is selected, the Gradle run task automatically disables NeoForge's early OpenGL loading window. This is required because Minecraft's Vulkan window must be created with `GLFW_NO_API`.

After building, install the mod into that instance with:

```bash
./gradlew installToInstance \
  -Pinstance_directory=/home/aruku/.minecraft/versions/RTest/mods
```

On Windows PowerShell, use the bundled wrapper. This checkout automatically uses
`C:\Program Files (x86)\mc\DS2\.minecraft` when that directory exists; explicit properties
always take precedence:

```powershell
.\gradlew.bat installToInstance `
  -Pgame_directory="C:\Program Files (x86)\mc\DS2\.minecraft" `
  -Pinstance_directory="C:\Program Files (x86)\mc\DS2\.minecraft\mods"

.\gradlew.bat runClient `
  -Pgame_directory="C:\Program Files (x86)\mc\DS2\.minecraft" `
  -Pgraphics_backend=vulkan
```

`installToInstance` and `runClient` update `config/fml.toml` with
`earlyWindowControl = false`. Vulkan must create the GLFW window with `GLFW_NO_API`; leaving
NeoForge's early OpenGL window enabled causes `GLFW error 65540` on Windows.

The NRD denoiser also needs the Windows x64 bridge at
`src/main/resources/rtest/natives/windows-x86_64/prime_nrd.dll`. Build it with the instructions
in `native/nrd/README.md`; the Linux `.so` cannot be renamed or used on Windows.

For a new Windows instance, the helper script writes `earlyWindowControl = false` and installs
RTest automatically:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\setup-windows-instance.ps1 `
  -GameDirectory "C:\path\to\new\instance"
```

The script does not edit launcher-owned arguments; keep `--graphicsBackend vulkan` in the PCL
launch arguments. Use `-SkipBuild` when only the instance configuration needs to be repaired.

The Gradle fallback defaults are derived from `user.home` and are portable across Linux and
Windows; explicit `game_directory`/`instance_directory` values always take precedence.

Current progress:

1. Vulkan RT extensions, features, VMA buffer-device-address support, and RT limits are enabled through client mixins.
2. A ray-tracing pipeline, SBT, BLAS/TLAS path, and `vkCmdTraceRaysKHR` dispatch are implemented.
3. The manual `F8` test captures nearby Minecraft block-model quads and runs a Ray Generation path loop with a configurable 1--4 GI continuation budget and at most one continuation per layer. Closest Hit only returns hit position, normal, UV-resolved material, optical data, and material emission; it does not recursively trace reflection, refraction, or GI rays. Ray Generation evaluates a consistent Lambert/GGX BSDF, samples one finite solar disk for direct lighting and soft shadow edges, selects exactly one diffuse, specular, or transmission continuation, and may issue one cheap direct-sun shadow ray using `TerminateOnFirstHit`; Shadow Any Hit filters Alpha Cutout/transmissive intersections and Shadow Closest Hit marks opaque blockers. Alpha cutout, biome tint, the 20-TPS game-clock-synchronized sun direction, rain/thunder and night state, optional resource-pack `_n`/`_s` LabPBR maps, and the bundled 4096² six-face skybox remain supported. Minecraft block/sky light and baked ambient channels are not used as radiance. The authored skybox is decoded as an independent environment signal, while material emission remains a separate emission AOV. Geometry capture consumes Minecraft's native `IRenderableSection` list, and static BLAS/TLAS resources are built once per captured scene. The RT pass uses a Caustica-style `GameRenderer.renderLevel` before-hand seam after vanilla `LevelRenderer` (including shader transparency, clouds and weather) completes; `AfterOpaqueFeatures` remains the native capture hook, and Minecraft's native hand/screen effects/HUD/UI are rendered afterward. Press `F9` to open the separate RTest settings screen.

The smoke test renders RT at the configured FSR input resolution, writes NRD diffuse/specular and guide signals, runs the local NRD 4.17.3 `REBLUR_DIFFUSE_SPECULAR` compute denoiser (enabled by default at `nrdStrength=1.0`), then runs the FidelityFX FSR 3.1.5 temporal reconstruction chain and copies the display-resolution result into Minecraft's main 3D render target. The quality preset is configurable from F9 or `config/rtest-client.toml` (`fsrQuality`). HDR is controlled through `hdrEnabled` (default `true`): on a Wayland session with `VK_EXT_swapchain_colorspace` and a linear scRGB surface format, RTest selects an `RGBA16F` main target and swapchain and leaves the final output scene-referred; unsupported/X11 surfaces fall back to SDR automatically. Restart after changing the option. Frame generation and FSR4 are not included: AMD FSR Upscaling 4.1.1 now supports RX 7000 hardware, but its official SDK 2.3 integration is signed Windows/DirectX 12 DLLs and does not provide a usable Linux/RADV/Vulkan backend for this project. See [`docs/FSR4-RESEARCH.md`](docs/FSR4-RESEARCH.md). Fluid/Lava integration now uses the vanilla FluidStateModelSet and fluid-aware height/face rules. Sections containing fluids intentionally use the CPU capture path so compiled MeshData cannot replace their fluid material/height data; this remains incremental per Section and can be disabled with `fluidRtEnabled` for the compiled-terrain fallback. The renderer remains a prototype and does not yet replace all native Minecraft geometry, full translucent rendering, or every special entity/block-entity renderer.

动态实体造成的太阳光和面光源阴影保留当前帧原始结果，不进入 NRD 时间历史；静态地形和方块阴影继续使用 NRD。这样移动实体的阴影不会因缺少稳定的接收面运动矢量而拖影。

SDR 显示变换和离线累积采用 Prime 26.3 的 RGB Reinhard 色彩管理与 RGBA32F GPU running-mean 公式。按 `右 Alt + F2` 可切换离线累积；会冻结相机、场景版本、方块图集动画、太阳时钟和天气，场景版本变化时自动停止。Prime 的 GPL-3.0 许可证及附加许可保存在 `third_party/PRIME-LICENSE*.txt`。
