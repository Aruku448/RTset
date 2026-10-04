# FSR4 / AMD FSR Upscaling 研究记录

> **历史快照（2026-09-12）**：本文结论只对应当时检查的 SDK/平台。运行时能力和官方支持需查当前 AMD SDK 文档；不要把 Proton/DX12 DLL 视为原生 Vulkan 后端。

当时的结论是：RTest 的 Linux/Vulkan 渲染路径没有可直接调用的官方 FSR4 Vulkan backend；项目实际使用 FidelityFX FSR 3.1.5 Vulkan shader 路径。硬件支持某一 FSR 版本，不代表操作系统、图形 API 或 SDK 分发形式也受支持。

没有在本项目中接入 FSR4，也没有把 FSR3 标记为 FSR4。当前链路入口见 `RtestFsr3Upscaler` 与 `RtestFsr3`；是否发生变化请直接核对代码和当前 AMD 官方 SDK。
