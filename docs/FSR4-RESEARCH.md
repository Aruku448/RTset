# FSR4 / AMD FSR Upscaling 4.1.1 调查结论

更新时间：2026-09-12

## 结论

**当前 `rtest` 不能在现有 Linux/RADV/Vulkan 渲染路径中直接启用真正的 FSR4。**

这不是 RX 7800 XT 的硬件限制：AMD 在 FSR SDK 2.3 / FSR Upscaling 4.1.1 中已经增加了 Radeon RX 7000（RDNA 3）支持，RX 7800 XT 属于支持范围。真正的阻塞点是当前项目使用 Linux Vulkan，而 AMD FSR Upscaling 4.1.1 的官方发行形态是 Windows + DirectX 12 的预编译签名 DLL；AMD 的 SDK 2.3 README 还明确列出 Vulkan 当前不受支持。

因此不能把 `amdxcffx64.dll` 放进 Minecraft、NeoForge 或 Mod JAR 后就让本项目的 Vulkan `VkImage`/`VkCommandBuffer` 使用 FSR4，也不能把现有 FSR3 SPIR-V 改名为 FSR4。

## 已确认的本机资源

### Proton FSR4 驱动 DLL

本机已经有 Proton/CachyOS Proton 的 FSR4 组件：

- 压缩缓存：`/home/aruku/.cache/protonfixes/upscalers/amdxcffx64_v4.1.1_398EA93C15D554EFB7ECE1F4CD057554.xz`
- 已安装副本：`/home/aruku/.local/share/Steam/steamapps/compatdata/0/pfx/drive_c/windows/system32/amdxcffx64.dll`
- PE32+ Windows DLL，大小 `65657608` bytes，MD5 `398ea93c15d554efb7ece1f4cd057554`
- 导出函数只有 `UpdateFfxApiProvider` 与 `UpdateFfxApiProviderEx`
- PE 导入包含 `dxgi.dll`；资源信息为 `DX12 AMD Driver Based FidelityFX Library`

CachyOS Proton 的 `protonfixes/upscalers.py` 将该文件安装到 Wine prefix 的 `drive_c/windows/system32/amdxcffx64.dll`。同一文件只属于 `fsr4` 驱动替换项；真正的 FSR API effect DLL 是另外的 `amd_fidelityfx_loader_dx12.dll` 和 `amd_fidelityfx_upscaler_dx12.dll`。

这证明本机 Proton 可以为 **Windows/DX12 游戏**准备 FSR4 文件，但不表示本机 Linux Java Vulkan 进程可以加载该 DLL。

### 当前项目的渲染接口

`rtest` 当前的 FSR3 实现是：

- Linux ELF / Java + LWJGL Vulkan；
- `VkDevice`、`VkCommandBuffer`、`VkImage`；
- `src/main/resources/prime/shaders/fsr3/fp32/*.spv`；
- 自己记录 Vulkan compute pass，并将结果复制到 Minecraft 的 Vulkan RenderTarget。

这与 FSR4 SDK 2.3 的签名 DX12 DLL 接口不是同一后端，二者之间没有可直接替换的 ABI 或资源句柄兼容层。

## 官方资料核对

1. [AMD FSR Upscaling 官方页面](https://gpuopen.com/amd-fsr-upscaling/)
   - FSR Upscaling 4.1.1 支持 Radeon RX 9000 和 RX 7000 Series discrete GPUs。
   - 需要 Shader Model 6.6。
   - 支持的图形 API 是 DirectX 12，支持的系统是 Windows 10/11。
   - 需要使用预编译、签名的 AMD FSR API DLL；AMD 文档列出的 upscaler DLL 是 `amd_fidelityfx_upscaler.dll`。
   - 页面版本历史显示 4.1.1 于 2026 年 6 月增加 RX 7000 / RDNA 3 支持。
   - 同一页面将 FSR Ray Regeneration 1.2 的支持 GPU 列为 RX 9000 Series；因此不能把 FSR4 的 ML RT 降噪器作为 RX 7800 XT 的可用方案。

2. [AMD FSR SDK 2.3 官方 GitHub README](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/readme.md)
   - SDK 2.3 的 ML Upscaler 版本为 4.1.1。
   - Known issues 明确写着：`Vulkan is currently not supported in AMD FSR SDK 2.3`。

3. [AMD FSR API 官方文档](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/docs/getting-started/ffx-api.md)
   - 应用必须使用 AMD 提供的签名 DLL。
   - 2.0+ 的 upscaler provider 是 `amd_fidelityfx_upscaler_dx12.dll`，通过 `amd_fidelityfx_loader_dx12.dll` 加载。
   - 该 backend-specific functionality 当前仅通过 DirectX 12 对应 DLL 提供。

## 可行路径

### 保持当前约束：Linux + RADV + Vulkan

继续使用当前已经验证的 FSR3.1.5 Vulkan 实现。当前 RT/FSR3 已使用 submission-scoped `GpuFence`，正常帧不再依赖 `waitIdle()`；motion/depth/reactive mask、NRD 和 history reset 也已有基础链路。以下只是可选后续方向，不是当前开发计划：

1. 继续以实机数据调优 RT 输出的降噪和时间域稳定性；
2. 在有 profiler 证据后评估多帧 in-flight 与 deferred retire；
3. 继续完善 motion/depth/reactive mask 的专项验证；
4. 等待 AMD 发布 Linux/Vulkan FSR4 实现，或等待可合法使用的跨平台 FSR4 backend。

### 真正接入 FSR4

需要把相关渲染路径改为 Windows/DX12，并接入 AMD FSR SDK 2.3 的签名 DLL，至少包括：

1. DX12 device/command list/resource 生命周期；
2. FSR API loader/upscaler DLL 加载；
3. render-resolution color、inverted depth、screen-space motion vectors 和 camera jitter；
4. DX12 resource state transitions；
5. 将 Minecraft 的最终 Vulkan RenderTarget 改为 DX12 资源，或重写 Minecraft 后端/做稳定的 Vulkan-DX12 互操作。

仅通过 Proton 的 `PROTON_ADD_CONFIG=fsr4` 或安装 `amdxcffx64.dll`，只能作用于 Proton 启动的 Windows/DX12 游戏，不能改变本项目的原生 Minecraft Vulkan 后端；不建议为此把当前 Minecraft 改成 Proton + 双图形 API。

## 最终决策

本次没有向项目复制或加载 Windows FSR4 DLL，也没有新增一个名不副实的 FSR4 配置项。当前构建继续明确标为 **FSR3.1.5 Vulkan**；待有 Linux/RADV/Vulkan 可用的 FSR4 backend，或项目决定迁移到 DX12 后再实现 FSR4。
