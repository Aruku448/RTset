# GPU 优化版本重置故障与撤回（2026-10-08）

## 已确认事实

用户在部署GPU传输优化版后报告显卡重置。最新日志是baseline audit profile；00:53:19仍输出GPU计时，随后报告 `VK_ERROR_DEVICE_LOST: Failed to present image`，清理阶段进一步出现semaphore等待失败。清理堆栈不是最初根因。

内核00:53:20报告 `ring gfx_0.0.0 timeout`，关联java PID1272259、Render thread1272264；随后开始ring reset，出现 `Illegal opcode in command stream`，ring reset失败并升级为GPU MODE1 reset；00:53:24报告GPU reset成功和VRAM丢失。非法opcode是在reset期间出现的信号，仅凭该文字不能证明新shader生成非法机器指令或应用提交了非法命令。

证据：`tmp/gpu-reset-20261008/latest.log`（保存后对已知token键做脱敏），`kernel.log`（00:52:50–00:53:30）。崩溃版本JAR和三份GPU源码保存在同目录。没有重复部署该版本来主动触发更多GPU重置。

## 已执行撤回

实例恢复为CPU模型累积优化版，SHA-256：`2ab6e907b232b94b7e3899ae3cfe8de06c2d6981545be8b9cdc9bd3d552899fe`。

恢复文件：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`；来自 `/home/aruku/.minecraft/versions/RTest/mod-backups/20261008-004941-gpu-transport/rtest-0.1.0.jar`。逐字节验证实例JAR与备份相同。

源码 `RayTracingShaderRaygen.java`、`RayTracingVulkanPass.java`、`VulkanAccelerationResources.java` 恢复到本轮GPU优化前。撤回太阳/月亮/天空调度改动、零强度gate、4字节诊断buffer、读回新日志及唯一any-hit几何flag，保留之前CPU捕获线性合并。GPU优化测试归档至证据目录并移除其构建任务依赖，避免测试继续要求已撤回行为。

恢复源码重建后，以上三个GPU类及ModelMeshAccumulator的class字节与旧JAR匹配。`modelCaptureBatchTest`、`accelerationStructureSynchronizationTest`、`rayTracingShaderContractTest`、`rayTracingFrameTimingContractTest`和jar通过，日志 `/tmp/rt-gpu-reset-rollback-checks.log`；`git diff --check`通过。这证明回退构建一致，不是恢复版的游戏运行稳定性测试。

## 诊断边界与复测要求

新版本的shaderc编译、98 SPIR-V校验和CPU数值检查未覆盖真实驱动代码生成、BVH构建/遍历、资源生命周期及运行稳定性，本轮验证不足。当前无法依据CPU/编译测试锁定GPU重置根因；不能把flags、4字节buffer或BSDF调度中的任意一项当作已证明原因。

下一步先重启加载已恢复实例，在同一场景确认恢复版不再重置，并记录相机/分辨率/NRD/ReSTIR和耗时。恢复版若仍重置，应继续调查共同路径及最近其他修改。恢复版稳定后才具备收窄回归的对照基础；GPU优化改动需分项验证，不能再次整体上线。诊断回放当前日志只证明症状存在，不是运行故障的可执行复现。此时没有agent可独立运行的GPU故障反馈循环，根因仍未定位。
