# FSR Radiance Caching 官方支持边界核实

日期：2026-10-05。只检查官方平台、公开代码、硬件、API 与许可；未修改运行代码或部署。

## 结论

**当前 RTset 的 Linux / Vulkan 路径不能直接接入官方 RC 0.9.0 二进制。** 官方提供的是 DX12 DLL 和 DX12 接入示例，没有公开 Vulkan / Linux RC 后端。RX 7800 XT 也不在当前官方 RC 支持列表中。可以研究缓存算法并另行实现 Vulkan 缓存，不能把现有公开 RC 头文件当作可移植的完整训练／推理实现。

## 核实基线

官方仓库 `GPUOpen-LibrariesAndSDKs/FidelityFX-SDK`；本轮远端 `HEAD` 与 `v2.3.0` 都指向 **`60f4ea81909200d8542eca14dccb2628b763a9a3`**。后面的源码链接均固定到这一提交。版本号由 [ffx_radiancecache.h:31–36](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/radiancecache/include/ffx_radiancecache.h#L31-L36) 定义为 0.9.0。

## 平台与公开内容

| 核实项目 | 证据与判断 |
|---|---|
| Vulkan | 仓库 README 虽然概述提到 Vulkan，但其 Known issues 明确表示当前 SDK 不支持 Vulkan。[readme.md:48](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/readme.md#L48) |
| Linux | 示例要求 Windows 与 DX12；loader 在非 Windows / Xbox 平台直接报 Unsupported platform，而不是提供 `dlopen` 路径。[sample requirements](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/docs/samples/radiance-cache.md#L11-L14)、[ffx_api_loader.h:35–48](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/api/include/ffx_api_loader.h#L35-L48) |
| 实际 backend | 官方示例用 `CreateBackendDX12Desc` 传入 `ID3D12Device` 创建 RC context。[RenderManager.cpp:304–317](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Samples/RadianceCaches/FidelityFX_NRC/dx12/RenderManager.cpp#L304-L317) |
| RC 可见源码 | `Kits/FidelityFX/radiancecache` 在固定提交只含 `.h/.hpp` 两个 API 头文件，没有内部训练／推理实现、网络权重处理或 GPU kernel 源码。[目录](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/tree/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/radiancecache) |
| RC 二进制 | 固定提交提供 `Kits/FidelityFX/signedbin/amd_fidelityfx_radiancecache_dx12.dll` 以及 loader DX12 DLL / lib，没有对应 RC `.so` 或 Vulkan DLL。[signedbin](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/tree/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/signedbin) |
| Shader 入口 | 官方要求 HLSL / CS_6_6；示例 `ShaderGraph.cpp` 同样按 `cs_6_6` 编译。只有示例 shader 可移植不能解决缺失的 RC 内部 kernel / backend。[technique requirements](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/docs/techniques/radiance-cache.md#L33-L36)、[ShaderGraph.cpp:174](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Samples/RadianceCaches/FidelityFX_NRC/dx12/ShaderGraph.cpp#L174) |

## RX 7800 XT、WMMA 与 reference backend

官方 SDK 产品页将 RC 0.9.0 正式支持硬件列为 **RX 9000 系列及以上**，图形 API 为 DX12，系统为 Windows 10 / 11，要求 SM 6.6。该页对 RX 7000 的新增支持明确属于 Upscaling 4.1.1，不能外推给 RC。[官方 Requirements](https://gpuopen.com/amd-fsr-sdk/#requirements)

源码里存在更宽的实验路径：创建时先设置 `FFX_RADIANCE_CACHE_CONTEXT_TRY_FORCE_WMMA`；失败后取消此 flag 重试 reference backend，并检查设备能支持 wave32。[RenderManager.cpp:313–333](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Samples/RadianceCaches/FidelityFX_NRC/dx12/RenderManager.cpp#L313-L333) API 头文件仅保证“强制 WMMA 不可用则创建失败”，没有给出 RDNA 3 RC 支持承诺。[ffx_radiancecache.h:43](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/Kits/FidelityFX/radiancecache/include/ffx_radiancecache.h#L43)

因此应区分三件事：硬件具备矩阵指令、官方 DLL 接受该设备、当前 renderer 能调用该 DLL。源码无法确认 RX 7800 XT 上 RC WMMA backend 能成功创建，也无法量化 reference backend 的训练开销。本轮未在 Windows / DX12 / RX 7800 XT 上执行官方示例，不应承诺可用或性能收益。

## 许可的准确范围

不能只读许可第一页。`docs/license.md` 先给出默认二进制分发与禁止逆向条款；随后列出例外文件，并在列表结尾给出 MIT 文本。**RC 两个头文件、RC DX12 signed DLL、loader DLL / lib、以及 NRC 示例文件都明确在例外列表里。** 因此“RC DLL 一律受默认禁止逆向条款”不符合该固定提交的许可文件；分发这些列出文件应保留对应版权和许可声明。完整 SDK 中其他未列出的文件仍需按其各自范围处理。[默认条款](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/docs/license.md#L1-L9)、[RC 与 signed DLL 例外](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/docs/license.md#L578-L585)、[MIT 文本](https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/60f4ea81909200d8542eca14dccb2628b763a9a3/docs/license.md#L885-L894)

许可开放程度与源码可获得程度是独立问题：列出的 DLL 适用 MIT，不等于其内部训练／推理源码已经公开。

## 未确认项

- RX 7800 XT 上官方 DLL 的 WMMA / reference 创建结果与耗时。
- 非官方 Wine / VKD3D 方式是否能运行示例；这不能直接让 Vulkan 原生 renderer 获得 DX12 device / command list。
- 后续 RC 的 Linux / Vulkan 计划、内部 kernel 开放计划，官方当前资料没有承诺。
- 本轮依据最新可见 SDK 2.3.0，不将 technical preview 当作稳定 ABI。官方说明该集成文档仍在开发。[用户给定 technique 页面](https://gpuopen.com/manuals/fsr_sdk/techniques/radiance-cache/)
