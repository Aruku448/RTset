# 第一批光追等价优化

## 实际改动

### 降噪关闭时不生成专用 AOV

- `RayTracingShaderRaygen.java`：用帧统一模式包围 NRD/Sundial 专用计算及 11 个 imageStore；FSR scene color、motion、depth、reactive、transparency 和结果缓冲仍正常输出。
- 新增 `fsr/RtestDenoiserMode.java`：统一 OFF=-1、Sundial=0、NRD=1 的 shader 信号，保留 Sundial 优先和原有 strength 阈值语义。
- `fsr/RtestFsr3.java`：beginFrame 一次快照模式和强度；相机上传、guide producer、后处理 consumer 使用同一模式。首次 OFF 仍初始化静态绑定 guide descriptors 的 GENERAL layout；后续 OFF 不做 guide 屏障，重新启用时恢复准备与原 history reset。
- `RayTracingVulkanPass.java`：camera dynamicParameters.w 使用已快照模式，不再自行读取另一份开关组合。
- `fsr/NrdDenoiser.java`：同步更新准备方法的注释。

这是运行时 uniform 分支，不是新建两套 raygen pipeline 或增加模式切换时资源重建。保持原有 descriptor、图像和 pipeline 分配，避免将这次性能改动扩展为资源生命周期重构。不开降噪时可跳过的逻辑写入量约 88 B/像素，原生 2560×1404 对应 301.64 MiB/帧；不是实测带宽或帧率收益，也不降低这些图像的常驻显存容量。

模式日志新增 `guides=false/true`，方便重启后确认当前调度。

### BSDF 的两类能量共用一次计算

`RayTracingShaderRaygen.java` 的 evaluateBsdf() 内，漫反射与镜面补偿共用一次 primeDefaultGgxDirectionalEnergy(nDotI, alpha) 及同一个 resolvedEnergy。保留镜面、金属以及原有条件分支，不把缓存生命周期扩展到其他命中点，也不改变 PDF/MIS/随机采样。驱动若已做相同 CSE，实测收益可能很小。

### 零位移 POM 早退

`RayTracingShaderCommon.java`：在原有高度和视角早退之后，parallaxDelta 精确等于 vec2(0) 时返回 vec2(u0,v0)+coord*span，跳过后续最多32步高度搜索和5次细化。保留 pbrWrapCoord、sprite 边界与无高度/grazing 的原返回语义；不引入“近似为零”的 epsilon。

## 验证

沿用已有着色器编译契约入口 `rayTracingShaderContractTest`：

- 新 `RayTracingDenoiserOptimizationTest` 对真实 RayGen 的帧统一模式作常量编译探针并检查 SPIR-V OpImageWrite 数量：OFF 只保留5项 FSR 图像写入，Sundial/NRD 保留16项。旧 shader 的 OFF 探针失败为16!=5，修改后通过。生产 shader 仍使用 uniform 模式；探针不是 GPU 执行测试。
- 同测试覆盖关/开/零强度/阈值/双开时的模式选择。
- 新 `RayTracingParallaxOptimizationTest` 检查实际 GLSL 早退位置，含零深度、正视、tile 正负边界的字面预期数学参考。旧源码失败、修改后通过；不是 GPU 像素等价实测。
- 既有 shader 契约增加 BSDF 单次能量求值检查，并继续编译所有活跃 shader。
- `./gradlew test jar --offline` 用于最终完整验证与构建。

本轮不改 GI 深度、体积积分点数、随机序列、体积采样 PDF、分辨率、FSR 算法，也不删除 GPU wait/fence。CPU composition/索引优化和统计估计器改动尚未实施。

## 必须实机复测

同一世界/相机/配置，预热后分别记录 gpu_timing；不能拿不同分辨率、模式或视角的日志直接算提升比例。需覆盖 NRD、Sundial、OFF 切换、零强度、透明材质、动态实体、窗口缩放。当前证据不保证画面逐位一致，也不声称修复 resize device-lost。
