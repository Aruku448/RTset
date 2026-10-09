# RT 进入失败：Prime dev 对照与 Vulkan 管线检查

## 结论与最新证据

测试实例为 `C:\game\mc\.minecraft\versions\26.2-NeoForge`，最近运行日志结束于 2026-10-09 16:59:42。
16:58 已成功建立 Vulkan 设备；16:59 捕获 310 sections / 193960 triangles。此前 feature 链、NRD capability 与天空盒布局错误在本次日志没有复现。

当前明确阻断是 `VUID-VkRayTracingPipelineCreateInfoKHR-layout-07988`：主管线 stage 6（shadow ANY_HIT）访问 set 0 / binding 2 相机 UBO，布局只声明 RAYGEN / CLOSEST_HIT 可见。创建返回 `VK_ERROR_VALIDATION_FAILED_EXT`，因此尚未执行场景 trace 或控制 raygen。它不是已成功进入 RT 后的设备丢失。

修正 binding 2 的 ANY_HIT 可见性。将生产使用的 descriptor layout、9 个 stages、7 个 groups 提取到 `RayTracingPipelineContract`，实机检查直接使用相同工厂，避免另写一份简化布局造成错误通过。没有对全部 bindings 粗略启用所有阶段。

## 参考版本与实现差异

参考仓库 dev 固定到 [`e1917423d0c35f57742a2ca08e0f3258d0ff23c0`](https://github.com/bWFuanVzYWth/prime/tree/e1917423d0c35f57742a2ca08e0f3258d0ff23c0)，2026-10-06。
RTset origin/main 重新 fetch 仍是 `9a4a1f65e595096987b8715ced26d4b36538e27c`。远端另有 persistent-rt-world-research 研究分支，其最新提交为 2026-10-05；本次不合并另一套渲染架构。

| 检查范围 | Prime dev 实际实现 | RTset 检查与处理 |
|---|---|---|
| 设备协商 | 26.2 VulkanBootstrap 在实际 createDevice 前添加 AS、rayQuery、BDA、scalarBlockLayout，创建成功后记录具体设备 | RTset 需要 AS、rayTracingPipeline、BDA、非均匀 skin 数组及实际 shader 要求的图像 feature；已修正 sType 枚举与启用链。使用真实最终创建参数验证，不能只依赖硬件支持查询 |
| 光追架构 | 当前生产 PT 管线为 compute + ray query；pipeline-stats 示例另有 RT pipeline | RTset 使用 raygen / miss / hit groups 和 SBT。不能直接套用 Prime compute 的阶段标志或将 rayQuery 当成 rayTracingPipeline 的替代 feature |
| descriptor 可见性 | pipeline-stats 的示例布局包含 ANY_HIT；生产 compute layout 明确使用 COMPUTE | RTset shadow ANY_HIT 漏了相机 binding 2。反射全部优化 SPIR-V 的 set、binding、type、array count 与 stage flags；覆盖 64 个 skin descriptor |
| 宿主命令排序 | 借用 MC encoder；execute 只追加，提交由宿主拥有，不抢先单独 queue submit | RT 普通帧沿用共享 encoder。天空盒初始 UNDEFINED→GENERAL 与上传现使用同一共享 encoder；最近游戏日志没有再次报告六层布局错误 |
| 完成证明与资源复用 | 真实 submit serial / timeline；createFence 在 submit 前捕获，等待实际完成 | RTset 在下一次 mapped 参数复用前等待上一帧 fence；BLAS 退休跟随完成。初始构建及控制对照使用独立 owner。没有改为按经过帧数假定完成 |
| 输出图像 | compute 直接写宿主 GENERAL 图像，再覆盖后续 attachment/sampling 访问 | RTset 从输出 buffer 复制到宿主图像；已核对 transfer 写到后续 color attachment read/write、fragment sampled read、最终 transfer read。架构和带宽成本不同 |
| AS / SBT / payload | 生产 ray query 路径不依赖 RT SBT；AS scratch 依设备对齐 | RTset 查询 scratch 和 SBT alignment，七组按 {0,1,2,5,3,4,6} 打包；三个 miss / hit 区域对应主射线、阴影、sky CDF。hit 中没有递归 trace，pipeline depth=1。native 管线创建并读取七组 handle 成功，不等同于验证所有运行期 AS 输入 |
| NRD / 大气 | 设备协商含扩展存储格式；shader 与 native ABI 分开验证 | 14 个 bundled NRD modules 在实际 feature 定义创建的 NVIDIA 设备上通过；七个大气内核与仓库固定构建哈希匹配。新 native 检查包含完整 RT pipeline，而不止 vkCreateShaderModule |
| 退出 | VulkanDeviceLifecycleMixin 在宿主设备关闭前退休自身 owner | 核对实例 26.2.0.88 的实际字节码，发现原版 VulkanRenderPipeline.destroy() 漏销毁 withDepthStencilPipeline；新增局部 mixin 补齐此句柄。其与 RT layout 初始化失败是独立问题 |

Prime 参考代码：

- [宿主管线与完成契约](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/docs/pipeline.md)
- [26.2 实际设备协商](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/adapters/mc-26.2/src/main/java/dev/primept/VulkanBootstrap.java)
- [宿主提交与资源退休](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/adapters/mc-26.2/src/main/java/dev/primept/HostVulkanRenderer.java)
- [借用队列录制与图像依赖](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/crates/prime-vulkan/src/frame.rs)
- [RT 管线统计示例](https://github.com/bWFuanVzYWth/prime/blob/e1917423d0c35f57742a2ca08e0f3258d0ff23c0/crates/prime-vulkan/examples/pipeline-stats.rs)

## 退出泄漏的证据边界

16:59 的 vkDestroyDevice 报 105 leaked objects，首十项均为 VkPipeline。实例 `minecraft-client-patched-26.2.0.88.jar` 的 `javap -c` 确认 destroy() 仅调用 vkDestroyPipeline(withoutDepthPipeline / withDepthPipeline)，没有 withDepthStencilPipeline。该缺口足以造成每个原版图形管线遗留一个对象。
本次补齐遗漏释放，沿用原版 pipeline cache 已有的 queue idle 完成证明，不改宿主缓存关闭顺序。尚未运行修复后的游戏退出流程，因此不能声称 105 个对象已全部消失，也不能把总数归为 NRD 泄漏。

## 实机管线检查

在 RTX 5070 Ti / NVIDIA 616.92 上，以实际 RTset feature descriptors 建立独立 Vulkan 设备，开启 Khronos validation 1.4.363.0 与 NVIDIA RT validation：

- 14 个 NRD shader modules、control / opaque / baseline raygen modules 创建通过。
- baseline 的 ReSTIR mode 0 / 1 / 2 / 3 × atmosphere false / true，共 8 条完整 RT pipeline 创建通过。
- opaque_traversal × atmosphere false / true，共 2 条完整 RT pipeline 创建通过。
- 10 条 pipeline 共 90 个 shader stages，使用生产的实际布局与 groups；七组 handle 获取通过。所有测试对象销毁后，ERROR callback 计数为零。
- CPU 反射回归故意撤销 binding 2 的 ANY_HIT 标志，必须重新检出 shadow stage 的原错误；同时检查 diagnostics 开关两种布局。

可选实机任务为 `gradlew vulkanDeviceFeatureChainTest -Pvulkan_gpu_validation`。需 Java 25、实际 Khronos layer 目录和 `NV_ALLOW_RAYTRACING_VALIDATION=1`；普通 test 默认不运行 GPU 工作。独立设备检查不是游戏场景绘制，不更改驱动或系统 Vulkan 安装。

## 回归与部署

16 个 audit profiles 共 114 个 SPIR-V 模块编译及生产 layout 反射通过；官方 spirv-val 以 vulkan1.2 环境检查这 114 个模块全部通过。另有 10 组完整阶段的 CPU descriptor 检查通过，包含撤销 ANY_HIT 标志的反向回归。

AS 同步、Vulkan 生命周期、玩家 BLAS 命令、NRD native scheduler（32 dispatches）、NRD 平台选择、无符号 limit、CPU runtime cleanup、F8 activation、捕获调度、核心 shader 契约、terrain traversal shader、大气 shader、mapped 上传范围、动态 TLAS、材质分配范围及 PBR 更新检查通过。布局提取后将 PBR / sky metadata 检查改为读取真实 native layout；修正旧源码检查的 CRLF 敏感性，并将实际启用的两个控制 shader 纳入 active shader 清单。没有用跳过测试来处理这些断言。

新 JAR 已构建并替换测试实例的 `mods/rtest-0.1.0.jar`，本地与实例 SHA-256 相同：
`D3D87422F8CEA9116C97DD521BD1BEE8854F7E9DA55B36A5909864133F453841`。
包内确认存在生产 contract 类与新增生命周期 mixin，语言和 mixin JSON 解析通过。
替换前日志、JAR、配置备份位于实例 `rtest-backups/prime-pipeline-audit-20261009-171625`。诊断 markers 继续保留。

## AMD / NVIDIA 结论

本次 layout 违规是代码契约问题，对两家均应修正。当前实机完整管线检查在 NVIDIA 上执行；本机 AMD 核显只有此前的能力查询，没有进行相同场景的配对验证。SBT handle alignment（NV 32 / AMD 4）与 scratch alignment（NV 128 / AMD 256）已经按实际设备查询使用，shader 未依赖固定 subgroup 大小。没有证据将本次初始化拒绝归为 NVIDIA 专有驱动缺陷。

## 尚需游戏验收

这些结果证明当前 shader/layout/feature/pipeline 创建路径通过实机检查，不证明世界输入数据、所有 AS 引用、场景 trace、后处理 dispatch 或运行期资源生命周期全部正确。
重启实例并在同一世界按 F8，依次检查 `RTest GPU control: COMPLETE, all three results verified`、场景首帧 fence 完成、`RTest RT rendering confirmed: first GPU frame completed`。只有最后一条才是当前 HUD 所称的“已进入 RT”。测试实例仍使用 opaque_traversal 隔离模式，正常画质需要首帧通过后恢复 baseline。

规范依据：[layout-07988 / descriptor kind / array count](https://docs.vulkan.org/refpages/latest/refpages/source/VkRayTracingPipelineCreateInfoKHR.html)、[SBT 地址区域](https://docs.vulkan.org/refpages/latest/refpages/source/VkStridedDeviceAddressRegionKHR.html)。

## 17:23 实例复测：首帧成功，恢复正常材质模式

17:23:11 日志确认三个 GPU 控制值均正确、场景首帧 fence 完成，且出现 `RTest RT rendering confirmed: first GPU frame completed`。随后数千帧仍使用 `audit_profile=opaque_traversal` 完成，未检出 RT failed、天空盒加载失败或 Khronos ERROR 日志。

用户截图中的固定灰表面与黑天空符合该隔离 shader 的明确行为：closest-hit 直接设置 baseColor=vec3(0.18) 并返回，raygen 对命中输出该灰值、未命中输出零，跳过真实材质、alpha 与天空光照。不能把此画面当作已证实的资源解析失败。

17:26 已备份实例 audit 配置与日志到 `rtest-backups/restore-baseline-20261009-172648`，并将 `config/rtest-audit-client.toml` 的 rayCostAuditProfile 恢复 baseline；其他设置保留。生产管线匹配检查会识别 profile 改变并重建。正常材质与天空的实际显示尚需加载该配置后的游戏画面确认。
