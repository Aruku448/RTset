# AMD / NVIDIA 崩溃差异：15:50 复测

## 新的命令阶段证据

测试实例最新报告为 `crash-2026-10-09_15.50.59-client.txt`。初始 AS 经过 15 个分批完成，最后一批日志 `tlasBuild=true`，每批沿用原有提交后等待。首个光追帧日志：`dispatch=15 gpuFrame=1 extent=854x480 dynamicBuilds=0 tlasRefresh=false driverValidation=false`，之后未见 fence 完成。

设备丢失时，共享帧编码器的 checkpoint：

```
TOP_OF_PIPE    RT after trace
BOTTOM_OF_PIPE RT TLAS ready / before trace
```

独立初始 AS 编码器的 TOP/BOTTOM 均为 `RT incremental AS builds recorded`。证据把重点收窄到首个 trace 及其输入资源：进入 trace 前的底部标记完成，trace 后的顶部标记到达，而底部未越过 trace。不能将顶部标记解释成 trace 已完成，也不能仅凭标记证明某一条 shader 指令或完全排除并行阶段影响。

这次失败不需要动态 BLAS 重建或 TLAS UPDATE；初始 TLAS 使用完整 BUILD。因此此前 AMD Windows 的 forceTlasBuild 短期改善，不能直接解释这个 NVIDIA 复现。失败前有一次场景发布，但相关性不足以证明发布代码就是根因。

Windows System 在 15:50:56.175 和 15:50:58.525 记录 `nvlddmkm` Event 153，数据均为 `Error occurred on GPUID: 100`。该信息确认驱动也记录故障，不能区分非法 GPU 访存、执行超时或驱动缺陷。此时间窗口没有检出 Display 4101；不据此判定是否发生 TDR。

## 当前机器的只读查询

| 属性 | NVIDIA RTX 5070 Ti / 616.92 | 本机 AMD Radeon(TM) Graphics / 26.6.1 |
|---|---:|---:|
| subgroupSize | 32 | 64 |
| shaderGroupHandleSize | 32 | 32 |
| shaderGroupHandleAlignment | 32 | 4 |
| shaderGroupBaseAlignment | 64 | 64 |
| AS scratchAlignment | 128 | 256 |
| maxRayRecursionDepth | 31 | 31 |
| maxRayDispatchInvocationCount | 1073741824 | 1073741824 |
| maxStorageBufferRange | 4294967295 | 4294967295 |
| maxInstanceCount | 16777215 | 16777216 |
| maxPrimitiveCount | 536870911 | 536870912 |
| NV driver RT validation extension（环境变量=1） | 支持，feature=true | 不支持 |

本机 AMD 是核显，并非历史记录中的 RX 7800 XT；没有在它上面运行同一场景，不能称配对性能/稳定性测试。

代码核对结果：核心 RT shader 未使用 subgroup 运算或固定 wave 大小；SBT、scratch 按设备查询值处理，实际 NVIDIA SBT 地址 `0xc504a00` 对齐 64；854×480=409920 次调用在查询上限内。两家都支持 robustBufferAccess，但支持不等于当前设备启用，更不证明所有访问都合法。映射写通过 VMA flush，未发现依赖 HOST_COHERENT 的直接假设。正常光追算法不按 AMD/NVIDIA vendor 分支；新增 NV 验证是诊断分支。

## 与历史 RX 7800 XT 的区别

仓库 `windows-device-lost-2026-10-08.md` 记录 AMD Windows primary_material 曾完成 trace（0.128–0.202 ms），之后场景发布仍崩溃；forceTlasBuild 曾短期改善。NVIDIA 4050/4070 Ti SUPER 在 BUILD、关闭体积光或 NRD 下仍有失败。当前 5070 Ti 首次 trace 就未完成，是不同的可观察失败阶段，不能把多份崩溃合并为已证实的单一问题。Linux RADV 的成功记录也不能代表 Windows AMD 驱动行为。

静态 SBT/payload 对应未发现错误，但仍须检查运行期损坏、shader 内越界、输入数据以及编译后的 continuation stack。Vulkan 在未指定动态栈大小时定义默认计算，不能把未调用 stack setter 认定为错误。已加入逐阶段 stack size 查询，只打印需求与规范默认公式结果，不修改栈或递归深度；待 AMD/NVIDIA 相同构建实测才能比较。

## 下一次复测准备

已核对 HMCL v3.15.2 官方源码：`hmclversion.cfg` 的 `environmentVariables` 经 tokenize 后按 `=` 分割并传入游戏 ProcessBuilder。本实例 usesGlobal=false，原环境为空，现仅增加 `NV_ALLOW_RAYTRACING_VALIDATION=1`；备份包含原 cfg、JAR、15:50 日志及报告。HMCL 缓存实例设置，需要完全退出并重新打开一次。之后普通启动即可，日志仍须出现 `NVIDIA driver RT validation ENABLED` 才确认成功。

本次保持 baseline 和所有画质配置，只增加 stack 查询并继续启用阶段诊断。构建、AS 同步/生命周期/BLAS 检查及七核资源校验通过，部署 JAR 哈希一致。尚未实际执行启用 NVIDIA instrumentation 的游戏复现，未宣称根因已修复。

## 15:55 崩溃点与下一轮隔离

`crash-2026-10-09_15.55.53-client.txt` 再次显示首个 trace 未退休，checkpoint 与 15:50 一致。CPU 检出位置是 `waitForPreviousFrame` 中的 `GpuFence.awaitCompletion`；GPU 命令边界是 `recordAccelerationStructuresAndDispatch` 中 `vkCmdTraceRaysKHR`。这不等于已定位 raygen/any-hit/closest-hit 中的具体指令。

本次 stack 查询全部为 0。这是驱动返回的查询结果，不据此推断栈溢出或将显式 stack setter 当作修复。`driverValidation=false`、设备扩展缺少 NV validation，说明虽然磁盘上的 HMCL 实例配置已写入环境，实际进程仍未启用；启动器缓存设置须重载。

新 profile `opaque_traversal` 用于二分定位：一条主射线、强制 opaque 跳过 alpha/material any-hit、最小 closest-hit payload、无后续 GI/阴影/天空 NEE/体积光。保留同一场景 AS/SBT、描述符和后处理链。原 `traversal_only` 没有跳过 any-hit，不能用其结果单独排除材质访问。

该模式只用于测试，透明与镂空物体会变实心，画面不代表正常渲染。若 trace 仍不完成，优先检查剩余 AS/SBT、射线输入、最小 payload 和驱动执行；若 trace 完成，逐项恢复 any-hit、closest-hit 材质和 raygen 光照以缩小范围。一次成功也不是根因证明。测试实例暂设此 profile，仓库默认仍 baseline，原配置已备份。

新增模式已通过完整 audit 编译检查：16 profiles / 112 SPIR-V 模块，baseline identity 和 reservoir mode 检查通过；AS 同步、生命周期、七核资源校验及 JAR 构建通过。实机进程查询显示 HMCL javaw PID 3052 创建于 15:28:10，仍是设置诊断环境之前的启动器。

## 16:00 隔离复测与诊断启动修正

`crash-2026-10-09_16.00.16-client.txt` 的日志确认 `opaque_traversal` 生效，但首个 trace 仍未退休，TOP/BOTTOM 标记与前两次相同。完整材质 closest-hit、alpha any-hit、复杂光照不是此复现的必要条件，剩余检查重点为场景 AS/SBT、射线与 payload 输入、仍保留的 raygen 输出处理，以及驱动执行。该结果没有单独证明哪一项存在缺陷。

HMCL 仍为 PID 3052、15:28:10 创建，所有游戏运行实际 `driverValidation=false`。为解除这一证据缺口，新增仅测试实例使用的 `rtest-nvidia-validation.flag`：通过 Java 25 FFM 调用 Windows `SetEnvironmentVariableW`，只修改当前游戏进程的 native 环境；不修改系统/用户环境或驱动注册表。VulkanBackend 的可用性检查与设备创建入口在 GLFW/Vulkan 查询前调用初始化；实例 marker 不存在时默认不调用 native API。设置成功后仍检查扩展和 feature 才启用验证。

独立实机探针清空父进程环境后验证：有 marker 时 `markerRequested=true`、NV extension=true、rayTracingValidation=true；无 marker 时 requested=false、NV extension=false。也验证了未加额外 JVM native-access 参数时 Java 25 当前默认发出警告但调用成功。`System.getenv` 的 Java 缓存仍返回 null，不代表 Windows native 环境设置失败，实际扩展枚举是本次验证依据。

构建、AS 同步/生命周期、七核资源校验通过，已备份并部署。测试仍保持 opaque_traversal；尚待游戏实际加载该版本后确认 `NVIDIA driver RT validation ENABLED` 及具体错误消息，不将探针成功等同于崩溃已修复。恢复普通渲染需将 audit profile 改为 baseline；关闭 marker 诊断还需移除标记并清除实例配置中的验证环境变量。

## 16:05：驱动验证生效，未报告具体错误

`crash-2026-10-09_16.05.09-client.txt` 对应运行首次确认 `NVIDIA driver RT validation ENABLED`，设备扩展包含 `VK_NV_ray_tracing_validation`，首帧日志 driverValidation=true。此前实例 marker 的 native 环境初始化实际生效。日志没有 `RTest Vulkan diagnostic` 消息，仍在首个 trace 上设备丢失，TOP/BOTTOM 与此前一致。不能把无诊断消息解释成全部资源合法；驱动验证覆盖有限，且没有故障指令级证据。

新版本在初始场景构建前、仅 gpuCrashDiagnostics=true 时，运行独立 raygen control pipeline：一个 raygen group、独立 SBT、一次 1×1 dispatch，只通过现有 binding 1 写入 0x52545052。不声明 AS、不执行 traceRay、不访问图片或命中着色器。沿用 pass 私有编码器，提交后等待、invalidate 并回读固定值；完成后释放临时 pipeline、shader、SBT，再执行原场景构建。日志为 `RTest GPU control: BEGIN` / `COMPLETE`，并有专用 GPU checkpoint。该对照新增一次仅诊断运行的 GPU 提交/等待，仓库默认诊断关闭时不执行。

若控制对照失败，则无需场景 AS 即可复现，应优先审查基础 pipeline、SBT、设备/分配/提交。若对照通过而场景 trace 失败，只能说明这一最小管线可执行，不能为另一张场景 SBT 或其 AS 背书；继续检查场景 AS/SBT、射线/payload、raygen 输出图片及驱动编译执行。

另外检查优化后的 opaque shader 时发现仍含 debugView 6/9 的备用 trace 指令；实际实例 debugView=0，它们未被配置启用。opaque profile 现在强制 debugView=0，让编译结果静态仅保留一条场景 trace，避免诊断视图破坏隔离。新回归直接遍历 SPIR-V：控制 shader 必须零条 OpTraceRayKHR，六个 opaque raygen 变体必须各一条。定向编译 8 个模块及 baseline identity/reservoir mode 检查通过，AS 同步、生命周期、资源完整性和 JAR 构建通过；本轮未重跑其他 15 个 profile，也未实际执行新控制管线的 GPU 复测。

## 16:12：未崩溃，初始化控制检查拒绝进入 RT

最新运行日志结束于 16:13:15，没有新增崩溃报告。多次尝试均在 `runRaygenControlProbe` 报 `Diagnostic raygen output mismatch: 0`；等待已返回完成，但预期的 `0x52545052` 未被读回。初始化保护主动拒绝进入 RT，因此这次“未崩溃”不代表场景 trace 已修复，也不能仅凭零值证明 raygen 没有执行。此运行的 NVIDIA driver validation 已启用。

新诊断在同一次提交中加入三个独立结果：transfer fill 初始化三个 uint；compute 写第二个；无场景遍历的 raygen 写第一个，第三个保留 transfer 标记。终端 barrier 覆盖 transfer/compute/raygen 到 host，等待后 invalidate 并回读全部三个值，同时打印 SBT handle。若 transfer 与 compute 正确而 raygen 保留初值，重点进一步收敛到 RT pipeline/SBT/执行；若全部为零，则先查公共提交或读取路径。不同结果只缩小调查范围，不单独证明具体根因。

定向 opaque audit 编译 9 个 SPIR-V 模块（含控制 compute/raygen），baseline identity/reservoir mode、AS 同步、生命周期、七核资源完整性及 JAR 构建通过。已备份 16:12 日志与原 JAR，部署新 JAR 并核对 SHA-256 一致；尚未在游戏中运行这个三组对照。

## 16:20：compute 与 transfer 正确，raygen 没有改写结果

16:20:59 到 16:21:44 的八次尝试结果相同：raygen=0x54494e49、compute=0x434f4d50、transfer=0x54494e49。后两项为预期值，raygen 位置保留 fill 的初值。RT 初始化保护仍拒绝启动；没有新增设备丢失报告。公共提交与回读路径至少完成了 transfer/compute，调查收敛到 RT 管线/SBT/执行及其实际设备配置，未定位具体 shader 指令。

新增独立 `tools/RaygenExecutionProbe.java`，绕开 Minecraft 编码器和 VMA，在相同 GPU 上执行一次 storage-buffer raygen 写入。探针最初也失败，但这项初始证据已撤回：Khronos 验证层发现探针 `VkDeviceCreateInfo.pNext(bda)` 的结构体重载截断预先构造的链，导致 rayTracingPipeline 未启用。修为地址重载 `pNext(bda.address())` 后，LWJGL 3.4.1、正常隐式层、Khronos 验证开启的测试读回 0x52545052 并通过；加入 NV 驱动验证也通过。该错误在独立探针，不是已证实的游戏代码错误。

成功的独立测试仍报告 raygenStack=0，因此零栈查询本身并不说明 RT 执行失败。失败探针上的 SPIR-V 1.4、显式栈、JNI 绕过和 LWJGL 3.3.6 试验受未启用 feature 的错误影响，不用于给这些因素下排除结论。

下载 LunarG SDK 1.4.363.0，SHA-256 为 94a82d378f7a5e3e54c9db7d2fb7016af136e14ac0a18dbf0f2f67a36352d141，与官方文件列表一致。仅提取并在测试实例 `rtest-diagnostics` 中放置验证层 DLL/manifest，没有运行安装器或修改系统注册表。新增 `rtest-vulkan-validation.flag` 内容为该目录的绝对路径，在 Vulkan 初始化前通过当前进程 native 环境设置 VK_LAYER_PATH/VK_INSTANCE_LAYERS。移除该 marker 即停止这项额外配置。

游戏版在 vkCreateDevice 的最终入口打印实际 feature 链中的 rayTracingPipeline、accelerationStructure、bufferDeviceAddress 和非均匀纹理索引，而不把“硬件支持”或“请求集合包含”当作实际启用的证明。Khronos 调用约束错误会通过现有 debug-utils 回调写入 `RTest Vulkan diagnostic`。已备份日志/JAR，定向 9 模块 audit、AS 同步、生命周期、资源验证通过，构建并部署；游戏内验证结果仍待下一次启动。

## 16:40：确认非法 feature 结构类型，修复设备创建

16:40:48 的 Khronos 错误为 `VUID-VkDeviceCreateInfo-pNext-pNext`：设备创建链包含 `VK_STRUCTURE_TYPE_APPLICATION_INFO`，随后 loader 报 `Failed to create device chain`。游戏回退 OpenGL，16:40:50 明确记录 `RTest ray tracing is disabled because Minecraft is not using the Vulkan backend`。因此本次 F8 不启动捕获，不是无法观察到的 RT 成功，而是 RT 后端未建立。

明确代码缺陷是 `VulkanPNextStruct` 的 sType 参数误用了 LWJGL 结构类的 `.STYPE`（成员字节偏移量，值为 0），而不是 `VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_*_FEATURES_*` 枚举。原 accelerationStructure 与 rayTracingPipeline 定义受影响，新增 NV validation 定义也复用了这个错误。三者被 Minecraft 的链查找/创建辅助方法当作同一类型，并在相同布尔字段偏移上互相别名；之前“实际 feature=true”日志仍可能是假阳性，不能证明驱动收到合法启用链。扩展列出与支持查询也不替代合法的设备创建。

已将三处 tag 更正为对应 Vulkan 枚举。新增 `vulkanDeviceFeatureChainTest` 验证精确类型、四个不同节点（两个 Vulkan 1.2 feature 共用节点）、最终 VkDeviceCreateInfo 链保留，以及独立开关 rayTracingPipeline 不影响 AS/NV flag。`--gpu` 模式使用游戏同一 feature 定义在当前 RTX 5070 Ti 实际创建并销毁设备，开启 Khronos 层环境后通过。AS 同步、资源生命周期、七核校验及构建通过。当前尚未验证游戏场景首帧；不将设备创建修复直接等同于全部历史崩溃修复。

F8 现在在后端检查前消费按键，后端不为 Vulkan 时显示明确错误；捕获完成的提示改为“正在准备 RT 渲染”，仅当 `hasPresentedFrame()` 确认此前提交的 GPU fence 完成时提示“RT 已进入渲染”并记录 `RTest RT rendering confirmed: first GPU frame completed`。避免旧提示把 CPU 捕获/初始化阶段表示为已渲染。已备份 16:40 运行日志与旧 JAR，部署并核对哈希。

## 16:48：后端与捕获已通过，NRD 能力及天空盒上传阻断

最新运行已使用 Vulkan，并捕获 310 sections / 190748 triangles；没有再出现非法 ApplicationInfo feature 链。失败发生在 `NrdDenoiser.createShaderModule` 的 shader 0，结果 -1000011001，VUID-VkShaderModuleCreateInfo-pCode-08740 指向缺少 `shaderStorageImageWriteWithoutFormat`。该失败早于 pass 主 RT 管线及控制对照创建，不是新的“raygen 写零”复现。

设备配置现在查询并启用 bundled shaders 实际要求的三个基础 feature：无格式图像写、无格式图像读及扩展存储图像格式。初次只补 write 后，实机遍历全部 14 个 NRD modules 又捕获两个 read capability 错误；补齐 read。主 raygen 的 SPIR-V 声明 StorageImageExtendedFormats（motion 使用 rg16f），也纳入设备要求，避免只修第一条阻断。最终硬件测试使用实际游戏 feature 定义，在 Khronos 层环境及 NV validation feature 下，14 个 NRD 模块和 control/opaque/baseline 三个 raygen 模块创建成功，error callback 计数为零。此检查不是完整计算 pipeline dispatch 或游戏场景验证。

另一个明确 VUID-vkCmdDraw-None-09600 是天空盒六层在提交时仍为 UNDEFINED，命令要求 GENERAL。Minecraft VulkanGpuTexture 构造函数把初始布局转换记在共享 encoder，原天空盒却用私有 encoder 先提交上传，形成跨提交次序错误。天空盒改用同一共享 encoder，先转换再上传并等待该次 fence；借用的 encoder 由 Minecraft 管理，不在天空盒 finally 中 destroy。超时等待队列空闲后再失败清理。纹理上传实机结果仍待游戏复测。

初始化失败路径已检查：NRD Images、samplers、pipeline layouts 和 FSR images 均有异常清理。退出时另有 105 leaked objects 报告，首十项都是 VkPipeline，不能仅凭这一总数归因为本次 NRD 图像泄漏；本轮不修改 Minecraft 全局 pipeline cache 的关闭顺序。NVIDIA 的 EXCESSIVE_DEGENERATE_PRIMITIVES 是性能警告，不作为本次拒绝着色器创建的原因。

新增/扩展设备 feature 链与 GPU module 检查、生命周期检查、NRD native scheduler 32 dispatches、七核资源校验及 JAR 构建通过；备份本次日志与旧 JAR后部署，文件哈希一致。场景 RT 首帧尚未执行，不能据此宣布全部渲染问题已解决。

## 16:59：主 RT 管线的阶段可见性拒绝

最新运行成功建立 Vulkan 并捕获 310 sections / 193960 triangles，随后主 RT pipeline 创建因 shadow ANY_HIT 的相机 binding 2 不可见被拒绝（layout-07988）。这一步早于控制 raygen 和场景 trace，不是首帧设备丢失的新复现。已补齐阶段标志，并以生产布局在 RTX 5070 Ti 实际创建 baseline 四种 ReSTIR 模式及大气开关、opaque 两种大气模式，共 10 条完整 RT 管线；Khronos ERROR 计数为零。

完整对照与检查边界见 [Prime dev Vulkan 审查](prime-dev-vulkan-audit-2026-10-09.md)。Prime 当前生产 compute + ray query 与 RTset 的 RT pipeline 不同，不能照搬阶段配置。实例 Minecraft 26.2.0.88 另有 depth/stencil 图形 pipeline 未释放的明确缺口，已补充局部销毁修正；修复后游戏首帧及退出泄漏总数仍需实测。

## 官方参考（链接）

- [Vulkan stack 默认规则](https://docs.vulkan.org/spec/latest/chapters/raytracing.html#ray-tracing-pipeline-stack)。
- [vkGetRayTracingShaderGroupStackSizeKHR](https://docs.vulkan.org/refpages/latest/refpages/source/vkGetRayTracingShaderGroupStackSizeKHR.html)。
- [NVIDIA driver validation](https://developer.nvidia.com/blog/ray-tracing-validation-at-the-driver-level/)。
- [HMCL v3.15.2 实例到启动选项的转换](https://github.com/HMCL-dev/HMCL/blob/v3.15.2/HMCL/src/main/java/org/jackhuang/hmcl/game/HMCLGameRepository.java)。
- [Khronos RayTracingKHR capability 要求](https://docs.vulkan.org/spec/latest/chapters/shaders.html#VUID-VkShaderModuleCreateInfo-pCode-08740)。
- [LunarG SDK 官方文件列表](https://vulkan.lunarg.com/sdk/files.json)。
- [LWJGL STYPE 字段为成员偏移](https://javadoc.lwjgl.org/org/lwjgl/vulkan/VkPhysicalDeviceRayTracingPipelineFeaturesKHR.html#STYPE)。
- [Vulkan rayTracingPipeline feature 的结构 tag 与创建设备要求](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceRayTracingPipelineFeaturesKHR.html)。
- [Vulkan 基础图像 shader features](https://docs.vulkan.org/refpages/latest/refpages/source/VkPhysicalDeviceFeatures.html)。
