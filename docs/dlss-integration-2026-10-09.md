# DLSS / DLSS Ray Reconstruction 接入记录（2026-10-09）

新增 Windows x86_64 NVIDIA RTX 路径：`upscaler = "fsr" | "dlss" | "dlss_rr"`。
默认仍为 FSR3；F9 → 渲染 → 时域重建可切换。DLSS 和 RR 均标记为实验功能。
启用模组后必须完全重启游戏，使设备创建时包含 SDK 所需功能。

## 渲染与资源契约

- 初始化使用 NVIDIA Streamline **2.14.1**，SR/RR 运行库均为 **310.9.1**；加载的
  功能仅 SR 与 RR。官方 SDK 归档 SHA-256 为
  `92c4d954631a1710da86ca3fa8d5034f2b9503838c95fc4ae977ae149319781b`。
- 原生桥接采用 manual hooking，借用游戏实际 Vulkan instance/device/graphics queue；
  不替换 Vulkan loader、swapchain，不接入帧生成。真实 Present 调用 common 插件的
  before/after hooks，保留原生 VkResult。
- 在 VkInstance/VkDevice 创建之前查询 `slGetFeatureRequirements`。启用实际支持的
  Vulkan 扩展/功能，并检查最终设备创建链。剔除与 Vulkan 1.2 BDA 冲突的 legacy
  `VK_EXT_buffer_device_address`，使用 standalone privateData + `VK_EXT_private_data`，
  避免 Vulkan13 aggregate 与 Minecraft 的 standalone synchronization2 节点冲突。
- SR 与 RR 都从 SDK 查询精确输入尺寸。Native AA 对应 DLAA；Quality/Balanced/
  Performance/UltraPerformance 分别对应 SDK 质量档。FSR 的 Quality 75% 在 DLSS 下
  映射为 Quality，F9 提示中明确说明，不能宣称 DLSS 使用 75% 输入尺寸。
- SR 读取可选 NRD 后的颜色、硬件反向深度和像素单位运动向量。RR 跳过 NRD，读取
  原始 noisy RT、linear viewZ、dense motion、世界空间 XYZ 法线/线性粗糙度、diffuse
  albedo、integrated specular reflectance、未归一化的世界单位 specular hit distance。
  RR 不消费 NRD 编码的法线、YCoCg 辐射或 normalized hit distance。
- shader 准备过程将线性 Rec.2020 转为非负线性 Rec.709，输出转回 Rec.2020，再进入
  现有离线累积、后处理和显示链。超出 Rec.709 的负分量会截断，广色域保真仍需画面评估。
- JOML 列主序存储对应 SDK 行向量矩阵的转置存储；矩阵无 jitter，另传像素 jitter。
  相机使用 camera-relative world；前后帧 clip 变换包含相机平移。运动向量包含相机和
  动态对象运动；转换为输入像素，SDK scale 为 `1/renderExtent`。
- 每次 evaluate 获取独立 SDK frame token；它不与采样索引绑定，历史重置的索引 0
  不复用旧 token。场景/图集变化、切换重建路径、resize、camera cut 都重建或重置历史。
- 资源由 Java 持有到实际 GPU completion；RT pass 退休后才 `slFreeResources`，SDK
  shutdown 先于 VkDevice destruction。SDK 报错/异常不会在同一录制中继续 FSR 回退或
  提交部分 SDK 命令。不支持的设备在调度前选择 FSR，日志与 GPU 完成提示显示实际路径。

## 已执行验证

显卡 NVIDIA GeForce RTX 5070 Ti；驱动 616.92；验证层 Vulkan SDK 1.4.363.0。
独立 Vulkan 1.2 device，包含与游戏一致的 standalone privateData/synchronization2。

| 验证 | 结果 |
| --- | --- |
| SR / RR，各五档质量，1920×1080 输出 | 10 种配置均 evaluate / submit / wait 成功 |
| 每档首帧、历史帧、历史重置，共 30 次输出 | 每次 RGB 6,220,800 个分量非零、无 NaN/Inf |
| 核心 Vulkan validation | **0 errors** |
| 严格 synchronization validation | **失败，10 errors**；未过滤或忽略 |
| Java 打包库实际加载 / ABI / 未附加设备前拒绝创建 | 通过 |
| 生产 Java device negotiation、最终 feature chain、FFM extent/create/destroy | RTX 实测通过，**0 errors** |
| 两个实际 guide/color shader 的 shaderc Vulkan 1.2 编译 | 通过 |
| 设置布局、RT shader、denoiser history、activation、cleanup | 通过 |
| 最终 RT pipeline descriptor reflection（10 pipelines / 90 stages） | 通过 |

原始 GPU 日志在工作区 `tmp/dlss-temporal-core-final.log` 和
`tmp/dlss-temporal-strict.log`。独立测试不是 Minecraft 场景画面对比，也不是性能测试。
本次没有操作游戏界面；实际 F9/F8 切换、材质/天空、透明体、运动拖影和相机切换仍待游戏验证。

18:23 已部署到 `C:\game\mc\.minecraft\versions\26.2-NeoForge\mods\rtest-0.1.0.jar`。
JAR SHA-256 为 `e8f53b807f3a3c9a0ac1a31ec0bc38ad3e273ae04df6526dc1d7f5c66ccdd310`，
部署文件与构建文件一致，九个内嵌运行库哈希、shader/mixin 和许可条目已核对。
旧 JAR 和当前 `rtest*.toml` 备份于实例的
`rtest-backups/dlss-integration-20261009-182353`；未更改实例配置开关。

严格同步错误为 `vkCmdClearColorImage WRITE_AFTER_WRITE`：SR 插件/NGX 首帧内部
清屏、RR 的 `nv.ngx.dlssd.resource` 在 `nv.ngx.dlssd.Evaluate` 内 layout transition 后
清屏，其内部依赖没有覆盖 transfer write。输入清屏到 SDK 调用前已设置全命令阶段
依赖；错误发生在 SDK 内部，不通过关闭日志或修改外部 VkImage layout 宣称修复。
默认严格测试仍以非零状态退出；显式 `-CoreValidationOnly` 才禁用 sync validation。
两种 DLSS 功能因此保持实验标记，不能据核心验证通过宣称不存在同步问题。

## 构建、打包与来源

`scripts/build-dlss-windows.ps1` 用 LLVM-MinGW 静态链接桥接所需 C++ runtime；
`-GpuTest` 启用默认严格测试。源码包含固定 C ABI 大小断言与异常边界。
`scripts/verify_dlss_resources.py` 在 Gradle `processResources` 前校验所有九个运行时文件
SHA-256，并拒绝误打包测试 EXE 或其它额外文件。JAR 同时包含 NVIDIA/Streamline、LLVM
及第三方许可通知与锁文件。运行库保留 NVIDIA 独立许可；不作为 GPL 源码标识。

- [官方 Streamline 2.14.1 SDK](https://github.com/NVIDIA-RTX/Streamline/releases/tag/v2.14.1)
- [NVIDIA DLSS SDK](https://github.com/NVIDIA/DLSS)
- [Manual Hooking Guide](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/docs/ProgrammingGuideManualHooking.md)
- [DLSS RR Guide](https://github.com/NVIDIA-RTX/Streamline/blob/v2.14.1/docs/ProgrammingGuideDLSS_RR.md)
- Prime 参考固定为 dev commit `e1917423d0c35f57742a2ca08e0f3258d0ff23c0`。
  原生桥接独立实现；GPU fixture 的 Vulkan image/readback harness 从 Prime 适配，
  GPL-3.0-only 来源及许可已注明。

## 提交前回归

提交前执行 Gradle `test`，DLSS ABI/guide shader、RT 管线反射、全部 16 个
audit profile 的 114 个 SPIR-V 及其余契约测试通过。
全量任务因 `playerAnimationContractTest` 的手持地图捕捉断言失败而返回非零，
因此不能标记为全量测试通过。

该断言调用 `captureCustomSubmit`，材质读取依赖 RenderType/RenderSetup 的 Mixin
accessor；独立 JavaExec 未应用这些 Mixin，材质读取返回 null，捕捉提前结束。
涉及的测试及 ItemModelGeometryAdapter、LivingEntityGeometryAdapter、
PlayerModelGeometryAdapter 与此次提交前的远端版本相同，本次未修改或跳过该断言。
此前核心 GPU 验证与严格同步验证的结果及限制保持上述记录。

Git 属性明确保留 Streamline 运行库、许可和官方 SDK 文件的原始字节；
九个暂存运行库条目均已核对与哈希验证所用的工作区文件一致。
