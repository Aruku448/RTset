# 维护与排障

实现变化频繁，故障处理先查日志和源码。此页只保留排查入口，避免过期的绑定号、阈值和历史配置误导诊断。

## 资源与同步

- `RayTracingVulkanPass` / `VulkanAccelerationResources` 是 Vulkan RT 资源生命周期的主要入口；不要从 Probe 或 facade 越权释放其内部 handle。
- 对象的所有权、共享 frame encoder 规则、BLAS/TLAS 更新与 fence 语义以当前代码为准。录制、提交、等待和释放必须作为一个完整生命周期核对。
- 资源新增或更改时检查成功创建、部分失败清理、替换、关闭幂等性，以及 GPU 尚在使用资源时的退休策略。
- 不要把 `waitIdle()` 添加到常规逐帧路径；具体允许位置以实现与验证为准。

## 常见问题定位

### RT 无法启动

检查是否使用 Vulkan 后端、Mixin 是否应用、设备 RT 扩展/功能是否可用，以及最近的 RT 初始化异常。查看 `RayTracingSupport`、`RayTracingProbe`、`RayTracingSmokeTest` 日志。

### 黑屏、旧帧或缺块

依次检查捕获数量与失败原因、场景/窗口/LOD 发布状态、RT dispatch 是否完成、保留上一帧图像的分支、输出图像和同步。`centerPixel` 单个值不能区分有效纯黑与未完成输出，结合状态日志判断。

### 动态对象消失或重复

查看动态 admission/fallback 诊断与 raster fallback 条件；检查 capture 是否运行在最终顶点/模型提交 seam、RT 显示时序是否与原生绘制重叠。不要仅凭早期动态 placeholder 验证记录推断当前行为。

### FSR/NRD/Sundial 异常

检查资源是否打包、设备格式支持、shader 编译错误、输入尺寸与 history reset 日志。NRD native bridge 及版本/许可说明见 `native/nrd/README.md`。FSR3、NRD 与 Sundial 的具体选择和互斥策略以当前配置/dispatch 代码为准。

### Shader 编译或 Vulkan validation 错误

从首个 shader compiler/validation 错误开始，核对 CPU 写入、descriptor layout、shader 声明、payload/SBT、图像 layout 与 barrier。避免先通过增大资源预算或插入全队列等待掩盖错误。

### 窗口、HDR 或显示颜色异常

记录后端、平台、surface formats/color spaces、配置和复现步骤；核对 `HdrSupport`、`NativeColorManagement` 及目标合成器行为。跨平台历史观察不能泛化为普遍结论。

## 构建与实机

```bash
./gradlew test --no-configuration-cache
./gradlew build
```

只在相关变更需要时运行。Java contract tests 不执行完整 GPU/画质验收；需分别说明构建结果和实机结果。实例与环境记录见 [`instance-validation.md`](instance-validation.md)。
