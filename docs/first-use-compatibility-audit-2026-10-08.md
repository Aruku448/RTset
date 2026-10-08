# 首次使用与 Windows 11 兼容性审查

审查基线：2026-10-08，提交 `1b96561`。本次是源码、打包资源及契约检查，没有 Windows 实机启动结果。用户反馈的启动失败发生在旧版，不能据此判定当前版本仍有同样故障。

## 结论与首次安装条件

当前默认 `hdrEnabled=false`、`hdrWideGamutEnabled=false`、`nativeColorDecodeEnabled=false`。SDR 光追不要求 HDR 显示器，也不要求开启 Windows HDR；内部浮点光照缓冲与显示器 HDR 是不同的条件。

首次安装仍需要正确的 Minecraft 26.2 / NeoForge 26.2 实例、Java 25、支持所需 Vulkan RT 特性的显卡与驱动，启动游戏时添加 **游戏参数** `--graphicsBackend vulkan`，并将该实例的 `config/fml.toml` 设置为 `earlyWindowControl = false`。仅复制 mod JAR 不会自动完成启动器参数和提前窗口配置。

Windows x64 的 NRD bridge 已打包，当前 DLL 为 PE32+ x86-64，导入依赖只有 `KERNEL32.dll`；CMake 使用静态 MSVC runtime。使用已构建 JAR 不需要自行安装 NRD SDK 或编译该 DLL。Windows ARM64 没有对应 native bridge，不能按 Windows x64 的支持范围说明兼容。平台名称“Windows 11”已有映射测试，但不等于 Windows 11 实机验证。

GLFW 在 Windows 通过 `vulkan-1.dll` 加载 Vulkan runtime，应由显卡厂商驱动提供；用户不应把 Vulkan SDK 当作运行游戏的必需配置。参见 [GLFW Vulkan 指南](https://www.glfw.org/docs/latest/vulkan_guide.html)。

只有主动选择 HDR 时，才需要支持 HDR 的显示器及正确的系统显示配置。Windows 设置入口为系统 → 显示 → HDR，参见 [Microsoft HDR 设置](https://support.microsoft.com/en-gb/windows/hardware/display-graphics/hdr-settings-in-windows)。模组还需要驱动为当前窗口 surface 提供兼容的 float-format/color-space 配对；当前实现选择线性 scRGB，或可选线性 Rec.2020，没有实现 HDR10 PQ 输出路径。扩展可用不等于当前屏幕已开启 HDR，参见 [Vulkan 色彩空间扩展](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_swapchain_colorspace.html)。

## 发现的代码与配置问题

| 优先级 | 证据与问题 | 首装/兼容影响 | 建议 |
| --- | --- | --- | --- |
| P1 | `VulkanGpuSurfaceMixin` 成功选择 HDR 后保存 `rtest$colorSpace`；两条 SDR 回退路径没有清除此字段；`configure` 始终覆盖原生色彩空间参数。 | 同一 surface 对象从 HDR 回到 SDR 时可能用旧 HDR 色彩空间搭配原生 SDR 格式。该状态路径成立，尚无 Windows 实机复现。 | 每轮协商清除 HDR 覆盖状态；只有本轮选中 HDR 时覆盖参数，SDR 保留原生参数。 |
| P1 | `RtestFsr3.create` 无条件调用 `NrdDenoiser.create`，后者立即调用 native 创建；失败清理后向外抛出。 | 即使用户关闭 NRD，缺少适用 DLL、native 加载失败等仍可阻止 RT 初始化。NRD 在初始化层面不是完全可选。 | 检查平台和加载结果；对确定的 native 不可用提供原始 RT 降级，保留真实 GPU/资源错误。运行链需支持无 NRD 实例，而不是仅捕获异常。 |
| P1 | `VulkanBackendMixin` 无条件加入 VMA BDA allocator 标志；`RayTracingSupport` 只在整套 RT 能力通过时加入 BDA device feature。检查的 Minecraft 26.2.0.87 原生 feature 列表没有 BDA。 | 不满足整套 RT 的设备存在 allocator 标志与实际启用 feature 不一致的路径，影响低端 GPU 或双显卡配置的安全回退。不是已证明的旧版 Win11 崩溃原因。 | 根据实际启用的 device feature 设置 allocator 标志；无 RT 能力时保持原生渲染可启动。VMA 明确要求实际启用 BDA feature，见 [VMA 初始化规范](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/group__group__init.html)。 |
| P2 | `HdrSupport.requested()` 直接读配置；`wideGamutPreferred()` 在配置未加载时返回 true，与 false 默认值相反。 | 启动早期配置加载顺序是额外依赖；异常分支会偏向实验性的广色域输出。尚未证明生产启动顺序触发该异常。 | 未加载时采用安全的 SDR/非广色域默认，配置读取与输出协商分开。 |
| P2 | Windows helper 只提示添加 Vulkan 参数；自动定位全局 `.minecraft`，不自动定位启动器版本隔离实例。 | 可以修改了错误实例的配置，或成功运行脚本后仍用 OpenGL 启动。 | 明确传入真实游戏目录；给首次启动显示后端、GPU、实际输出模式和失败原因。 |
| P2 | Gradle 的 early-window 检测会 trim，但替换正则不接受行首空白。 | 存在缩进的现有 `earlyWindowControl = true` 可以被检测到，却未改为 false。PowerShell helper 的正则没有此问题。 | 使用相同的允许空白的 TOML key 匹配，并核验修改结果。 |
| P2 | `neo_version_range=[26,)` 比 Minecraft 26.2 专用 mixin 的支持范围宽。 | 元数据可能接受未经验证的新 NeoForge 版本，随后在 mixin 阶段失败。 | 限定经过验证的版本范围，或建立版本矩阵后扩展。 |

当前没有查询 Windows 显示器 HDR 状态的代码。F9 展示的是请求开关，不能直接代表驱动实际协商出的输出模式。可增加“请求 HDR / 实际 SDR 或 scRGB / 回退原因”状态，减少把未生效 HDR 当作已启用的误解。

## 已执行验证与边界

`./gradlew hdrPolicyContractTest nativeColorManagementTest nrdNativePlatformTest rtActivationContractTest jar`：通过，`BUILD SUCCESSFUL`。Windows DLL 文件格式及导入表检查通过。原生 Vulkan backend feature 列表通过本地缓存的 Minecraft 26.2.0.87 字节码核对；该检查不是所有 NeoForge 版本的保证。

未执行 Windows 11 实机启动、显示器 HDR 开关切换、多显示器切换、双 GPU 选择、Windows DLL 动态加载或权限限制测试。因此以上问题是明确的代码状态路径或兼容风险，不能替代当前 Windows 日志的故障归因。优先修复输出降级状态、设备 feature/allocator 一致性和 NRD 可选初始化，再进行全新配置的 Windows x64 SDR 首启、NRD 关闭、HDR 请求但不支持等对照测试。
