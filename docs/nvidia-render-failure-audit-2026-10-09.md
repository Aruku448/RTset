# NVIDIA 渲染失败：代码与规范核对（2026-10-09）

## 能确认的结论

15:36 复测的设备丢失仍未定位到单条 GPU 指令。`vkWaitSemaphores` 是检出位置，`entity_translucent` 是原有 checkpoint 最后记录的标签，二者均不能独立证明故障命令。现有日志没有 NVIDIA 驱动验证消息或 Khronos 同步验证结果，不能据此认定驱动缺陷、着色器栈溢出或超时恢复。

## 实机只读查询

通过 `tools/NvidiaVulkanProbe.java` 查询实际 Vulkan 设备，未提交绘制或修改驱动设置：

| 项目 | RTX 5070 Ti 查询结果 | 当前使用 |
|---|---|---|
| maxStorageBufferRange | 4294967295 字节 | 最近场景材质范围约 90 MB，未超过此上限 |
| 每阶段 storage buffer/image 数 | 各 1048576 | RT descriptor layout 未超过此上限 |
| shaderGroupHandleSize / Alignment | 32 / 32 | handle 32，stride 64 |
| shaderGroupBaseAlignment | 64 | 本次 SBT 地址 `0xc4fc800`，各 region 地址均 64 字节对齐 |
| maxShaderGroupStride | 4096 | stride 64 |
| maxRayRecursionDepth | 31 | pipeline 为 1；场景、阴影、天空 CDF 的 trace 调用来自 raygen，未发现 hit 阶段递归 trace |
| NVIDIA rayTracingValidation | 支持；设置进程环境后扩展可见 | 原复测未启用 |
| Khronos validation layer | 未安装 | 不能声称同步验证通过 |

## 源码核对

- **提交/命令池**：核对 Minecraft 26.2 实际反编译源码。`createCommandEncoder()` 返回共享编码器；`execute()` 结束已有原生命令缓冲并追加 RT 缓冲；`createFence()` 记录提交序号。RT 正常路径不提前 submit，下帧才等待。编码器 reset 命令池前等待对应提交。未发现此前怀疑的“等待尚未提交 fence”或跨帧过早 reset 证据。
- **SBT**：七个 group 按 `{0,1,2,5,3,4,6}` 打包；raygen 一个、miss 三个、hit 三个。场景/阴影/天空 CDF 的 offset 分别为 0/1/2，天空使用 geometry stride 0。未发现索引超出这些 region。上述仅为静态核对，不能排除运行期 SBT 损坏。
- **AS**：输入地址、scratch 对齐、batch scratch 不重叠和 build→trace access scope 已有代码检查。动画 BLAS 后刷新 TLAS 的修正已保留；场景发布改用完整 BUILD 是保守处理，并不是 Vulkan 禁止所有 BLAS 引用替换 UPDATE 的证明。
- **payload/递归**：已核对场景 `PathPayload`、阴影 `ShadowPayload` 和天空 `vec2` 的声明与 SBT 目标；未发现接口类型不一致。复杂 raygen 的实际栈需求与执行期非法访存仍需驱动验证，不能通过编译成功排除。
- **图像与后处理：发现并修正缺口**：RT 输出到 compute 有 shader-write→shader-read/write 依赖，显示复制有 compute→transfer 依赖，但复制到 Minecraft 主画面后的 barrier 原本只覆盖 TRANSFER_READ。原版后续 GUI/屏幕效果在最终 blit 前会使用 COLOR_ATTACHMENT_OUTPUT 的 LOAD/混合/写入。原版 `createRenderPass` 不在开始前添加全局 barrier；全局 barrier 位于 `submitRenderPass` 结束处，不能保护该 pass 自己此前的 attachment 访问。正常 RT 和 replay 已补全 transfer→color attachment read/write、fragment sampled read，同时保留最终 transfer read。此为明确的依赖覆盖缺口，但尚未证明它是本次 DEVICE_LOST 的唯一原因。

## 本轮诊断实现

设置启动进程环境 `NV_ALLOW_RAYTRACING_VALIDATION=1` 后：

1. 实例启用 `VK_EXT_debug_utils`，注册返回 `VK_FALSE` 的日志 callback，生命周期到实例销毁前。
2. 查询设备的 `VK_NV_ray_tracing_validation` 和 `rayTracingValidation`，支持时才加入设备扩展和 feature chain。
3. 在 RT 开始、BLAS、TLAS、trace、后处理阶段及显示复制后，使用 Minecraft 自己的 checkpoint storage 记录标签，沿用其提交退休和存储轮转。

诊断默认关闭。不设置环境变量时不启用驱动 instrumentation，不新增逐帧等待，不改画质。启动日志必须出现 `RTest NVIDIA driver RT validation ENABLED (diagnostic run)` 才算启用成功。诊断版编译和 CPU 检查不等于已完成带 instrumentation 的游戏复现。

本轮 `compileJava`、同步/资源生命周期/BLAS 命令契约测试、资源完整性校验及 JAR 构建通过。15 个 audit profile 的 105 个 SPIR-V 变体编译通过。新增同步回归覆盖正常显示与 replay 的 attachment 及 transfer 消费范围。测试实例已部署本轮版本并保留替换前 JAR 和崩溃日志备份。

先退出已有启动器，再运行：

```powershell
.\scripts\start-nvidia-validation.ps1 -LauncherPath '启动器的完整路径.exe'
```

启动测试实例，在同一场景按 F8。之后检查 `RTest Vulkan diagnostic` 和 `RT ...` checkpoint。驱动可能提供 SBT 类型/越界、payload、递归/栈、AS 数据/同步等信息；无消息也不能证明全部合法。若仍无法定位，再补 Khronos 同步验证或 GPU crash dump。

## 15:46 复测补充

最新 `crash-2026-10-09_15.46.06-client.txt` 仍在 `waitForPreviousFrame` 检出 DEVICE_LOST，清理异常保持为 suppressed。测试实例加载了含主画面同步修正的 JAR，因此该修正不足以消除本场景的设备丢失；尚不能证明缺口与本次崩溃之间的因果关系。

启动设备扩展列表没有 `VK_NV_ray_tracing_validation`，也没有 ENABLED 日志或自定义 RT checkpoint。检查发现 HMCL 的 javaw 启动器仍在运行；新启动脚本无法让既有启动器继承环境，是验证未启用的可能原因。启动脚本现在检测已有 HMCL 并给出明确错误，不再静默将请求转给旧进程。

新增独立 audit 配置 `gpuCrashDiagnostics`，仓库默认 false、测试实例 true。无需 NVIDIA 环境变量即可记录初始分批 AS 构建、动态 BLAS、TLAS、trace 和后处理的 GPU 标记；前八个 RT 帧记录 CPU 命令追加与 fence 完成，初始 AS 分批前八次及 TLAS 构建也记录。命令追加日志不代表 GPU 已执行；fence 完成才表明对应提交退休。沿用原有等待，不新增同步等待。此轮只增加定位证据，没有宣称修复新的根因。

远端重新 fetch 后仍为 `9a4a1f6`。编译、三个 AS/生命周期/BLAS 检查、资源校验及 JAR 构建通过，尚待实际游戏复测。

## 官方依据（链接）

- [NVIDIA：驱动级 Ray Tracing Validation](https://developer.nvidia.com/blog/ray-tracing-validation-at-the-driver-level/)：Vulkan 的环境变量、扩展、feature、debug callback 及设备丢失时自动刷新诊断消息。
- [Vulkan：VK_NV_ray_tracing_validation](https://docs.vulkan.org/refpages/latest/refpages/source/VK_NV_ray_tracing_validation.html)。
- [Vulkan：vkCmdTraceRaysKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdTraceRaysKHR.html)：SBT、descriptor validity、layout 与调用限制。
- [Vulkan：Acceleration Structures](https://docs.vulkan.org/spec/latest/chapters/accelstructures.html)：BUILD/UPDATE、有效引用、scratch 及同步规则。
- [Vulkan：Synchronization and Cache Control](https://docs.vulkan.org/spec/latest/chapters/synchronization.html)：copy 写入、attachment LOAD/混合与访问阶段的定义；[同步示例](https://docs.vulkan.org/guide/latest/synchronization_examples.html)。
- [NVIDIA：RTX 最佳实践](https://developer.nvidia.com/blog/best-practices-using-nvidia-rtx-ray-tracing/)：性能建议与规范有效性要求必须分开，不将建议使用完整 TLAS BUILD 当作 UPDATE 在 NVIDIA 上不合法。
