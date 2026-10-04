# 降噪方案研究记录

> **历史快照（2026-09-15）**：这里只保留当时的选型背景。RTest 当前降噪实现请查 `RayTracingClientConfig`、`RtestFsr3`、`NrdDenoiser`、`SundialDenoiser` 和 shader 源码；本文不描述当前运行状态。

## 当时的判断

- RTest 的目标路径是 Vulkan compute。NRD/ReBLUR 的 SPIR-V 调度可由应用创建 Vulkan 资源后调用，不要求 NVIDIA GPU；RTest 所带 bridge/运行库版本以 `native/nrd/README.md` 和资源目录为准。
- FSR3 是时域重建/超分路径，不等同于 RT 降噪。Sundial 是项目内另一条降噪实现。
- FSR Ray Regeneration 与 Vulkan 能否使用取决于官方 SDK、操作系统和后端支持；本文对 2026-09-15 SDK 的判断不是后续版本承诺。
- NRD 使用 NVIDIA RTX SDKs License；发行与重建时应以仓库中的 `third_party/NRD-LICENSE.txt` 和随包资源为准。

## 透明 NRD 的实现边界

`NrdDenoiser` 定义了透明反射/透射 branch 的创建和 dispatch API，但截至本记录整理时，`RtestFsr3` 只创建并调度常规 NRD 实例，没有调用 `createTransparentBranch` / `recordBranch`。因此不能据 API 存在推断透明分支已经进入运行时画面。

## 本地核对入口

- NRD native bridge：[`native/nrd/README.md`](../native/nrd/README.md)
- 实机证据：[`instance-validation.md`](instance-validation.md)
- 当前模块入口：[`DEVELOPMENT.md`](DEVELOPMENT.md)
