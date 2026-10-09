# 排错更改复核

基线：RTset origin/main `9a4a1f65e595096987b8715ced26d4b36538e27c`。检查对象是当前全部未提交代码、诊断新增文件、测试实例配置和已有备份。本轮只审查并记录，没有覆盖现有画质设置或撤回已生效的 Vulkan 修复。

## 应保留的修复

| 更改 | 依据与影响 |
|---|---|
| AS / RT / NV feature 的 sType 改为 Vulkan 枚举 | 原 `.STYPE` 是成员偏移量 0，导致设备 feature 链非法及布尔位别名；已由 Khronos 捕获，必须保留 |
| 无格式存储图像 read/write 和 extended formats | bundled NRD / raygen 的实际 SPIR-V capability 要求，硬件支持与最终逻辑设备启用均已检查 |
| 相机 UBO binding 2 加 ANY_HIT | 阴影 shader 确实读取此 binding；原 layout-07988 拒绝主管线创建。生产与测试共用 RayTracingPipelineContract |
| 天空盒借用共享 encoder 上传 | VulkanGpuTexture 的初始布局转换在同一宿主 encoder 上，避免私有上传越过 UNDEFINED→GENERAL；借用的 encoder 不销毁 |
| 主图像显示与 replay 的同步覆盖 | 保留 transfer read，并覆盖后续 attachment read/write 与 fragment sampling；不改变像素算法 |
| 动态 BLAS 重建后更新 TLAS | 变换和地址不变时也可能改变包围范围；修正刷新条件，非永久 forceTlasBuild |
| 设备丢失错误处理 | 停止对丢失设备重放/继续提交，保留最初异常，后续清理错误作为 suppressed |
| 原版 depth/stencil graphics pipeline 释放 | 测试实例 26.2.0.88 实际字节码漏掉第三个 pipeline；最新 17:24 退出日志没有再报告 leaked objects，但不能据此泛化为所有异常退出都无泄漏 |
| F8 后端反馈与 GPU 首帧确认 | 区块捕获完成不等于 GPU 渲染完成；以对应 fence 完成记录真正进入 RT |

## 仍需区别对待的保守措施

场景几何发布后重用 TLAS 分配，但将 topLevelUpdatePending 置 false，走完整 BUILD。它是此前排除 BLAS 引用替换 / UPDATE 问题的保守措施；并未证明它是历史 DEVICE_LOST 根因，可能增加频繁区块发布的构建成本。动态逐帧更新仍可使用 UPDATE，配置 forceTlasBuild=false。

本轮没有撤回这一处调度改变，因为当前最新游戏只证实 opaque_traversal 场景运行；恢复正常积分器后的同场景稳定性与 BUILD/UPDATE 配对性能尚无新证据。不能把它与明确的 sType/layout 根因修复混为一谈。

## 诊断残留与实际成本

仓库默认 gpuCrashDiagnostics=false、profile=baseline，诊断 shader 不替代正常 shader。当前测试实例仍有：

- `gpuCrashDiagnostics=true`：每次 pass 创建时执行 transfer / compute / raygen 三项控制检查，增加一次诊断提交、fence 等待和 12 字节回读；还查询 shader stack、记录 checkpoint 与前几帧日志。
- `rtest-nvidia-validation.flag`，以及 HMCL 实例环境 `NV_ALLOW_RAYTRACING_VALIDATION=1`：启用 NVIDIA 驱动 RT instrumentation。
- `rtest-vulkan-validation.flag`：通过当前游戏进程 VK_LAYER_PATH / VK_INSTANCE_LAYERS 加载实例中的 Khronos 层。

因此只关闭 gpuCrashDiagnostics 并不能关闭 NVIDIA/Khronos 验证。`gpuCrashDiagnostics` 现有注释中的 “no extra waits” 不准确：逐帧 checkpoint 不新增等待，但初始化控制检查确实等待提交。当前诊断运行不宜作为普通模式性能基准。

WindowsDiagnosticEnvironment 只设置游戏进程环境；SDK 仅被提取到实例目录，没有安装 SDK、修改系统驱动或注册表。marker、环境和诊断文件仍保留，便于正常模式复测定位；本轮没有删除。

## 材质、天空与画质配置复核

当前 profile 已为 baseline。此前 opaque_traversal 使 closest-hit 固定灰值并跳过材质、alpha，raygen 未命中输出黑色；它造成的灰材质和黑天空已经通过恢复 baseline 撤销，正常 raygen / hit shader 源文件未被排错替换。

当前 `skyboxTextureEnabled=true`、`primeAtmosphereEnabled=true`、`debugView=0`、`dynamicEntityMvpEnabled=true`、`giBounces=4`。

与 16:19:32 配置备份相比，实际差异为：

| 配置 | 16:19 备份 | 当前 |
|---|---|---|
| nrdEnabled | true | false |
| nrdEntityEnabled | true | false |
| postProcessing.enabled | true | false |
| rayCostAuditProfile | opaque_traversal | baseline |

17:16:25 备份中两个 NRD 开关已经为 false，但 postProcessing.enabled 仍为 true。后处理是在该备份之后关闭；仅凭文件差异不能确定更改者或把它归为此前代码排错操作。ReSTIR 配置与这两个备份相同。

关闭 NRD 会改变降噪与噪声表现，关闭 postProcessing 会改变后处理表现；它们不是材质纹理或天空盒解析开关。本轮没有盲目恢复这些设置，避免覆盖用户可能在游戏设置中作出的选择。

## 验证边界与产物

- 实例最新日志为 17:22–17:24 运行：控制值正确、17:23:11 场景首帧完成，之后多个 pass / 数千帧 opaque_traversal 成功；无 RTest failed / Khronos ERROR / 退出 leaked objects 记录。
- 当前磁盘配置为 baseline，但该日志仍使用 opaque_traversal；不能把旧日志解释成正常模式的材质、天空、光照已经通过验收。
- 原有 10 组 NVIDIA 完整管线创建、114 个 SPIR-V 编译/反射/spirv-val 和相关 CPU 回归结果仍有效。本轮未改变运行代码，因此没有重复 GPU 测试。
- 本地构建 JAR 与实例 JAR 相同，SHA-256：`D3D87422F8CEA9116C97DD521BD1BEE8854F7E9DA55B36A5909864133F453841`。
- 更改全部仍在本地工作区，未提交、未推送；独立硬件探针与诊断脚本不会在普通游戏路径中自动运行。

## 后续：关闭测试实例验证开销

19:11 按用户要求关闭测试实例的诊断选项：清除 HMCL 的
`NV_ALLOW_RAYTRACING_VALIDATION=1` 环境变量，将 `gpuCrashDiagnostics` 设为 false，
并将两个 validation marker 和专用 NVIDIA 验证启动脚本移入实例备份目录
`rtest-backups/disable-validation-20261009-191105`。其他启动与渲染配置保持原值。
进程、用户和系统级 NVIDIA/Vulkan 验证环境变量均未设置。

这些更改仅涉及本地测试实例；仓库中的验证工具保留供显式诊断使用，默认不启用。
需要完全退出游戏与 HMCL 后正常启动，才能创建不含验证 instrumentation 的设备。
已复核配置及 marker 状态，尚未进行重启后的性能测量。
