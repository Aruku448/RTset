# RT 热路径与死代码清理（2026-10-01）

## 范围与原则

本轮检查 RT shader、动态实例更新、AS 命令录制和 NRD 后处理。保留既有未提交修改和上一轮降噪质量修复；不减少 GI/光源/体积/阴影样本，不调整实例画质配置，不删 GPU barrier/fence，不改变 BLAS 替换与退役策略。

不是整个仓库的穷尽死代码扫描。可切换的诊断视图、public/JNI/反射接口不能因简单 grep 无引用而删除。

## 已实施

### Shader 结构与计算复用

- `RayTracingShaderStages.java`：删除未绑定到现有 SBT/pipeline 的 legacy reflection/GI/refraction 三个阶段，以及 active closest-hit 中未调用的 PCG/随机/余弦采样函数。
- `RayTracingShaders.java`：删除三个对应的内部别名；现有七个 active stage 保持不变。
- `RayTracingShaderRaygen.java`：删除未使用 PCG 随机链、旧随机采样 overload、无调用的 `evaluateHitEmitter`、旧采样域常量和转换常量。
- 清除仅赋值而不读取的 `primarySpecularPath` / `selectedSpecularPath`、空太阳分支、被立即覆盖的 direct-light 初值、重复局部变量/赋值及失真的临时诊断注释。
- 删除恒为 false 的 `DIRECT_SUN_ONLY` 开关，保留原本实际执行的 `1 + giBounces` 和环境光路径。
- 删除 `sampleGgx` 未使用的 view 参数，以及 specular sampling probability helper 未使用的 baseColor/reflectivity 参数；包括诊断调用点一并更新。没有更换 GGX 分布/PDF 或 Sobol 序列。
- 太阳/面积光直接复用 `evaluateBsdf` 已计算的 `diffuse`，不再额外重算 Fresnel 和 GGX diffuse energy。有效半球内复用同一公式；无效半球统一遵循 evaluateBsdf 的零结果，不承诺所有边界浮点行为逐位相同。
- 保留原 specular clipping、MIS、主表面 NEE、delta/transmission、RR 和 AOV 分配；去掉由外层条件和有限 [0,1] roughness 保证的内层 specular-energy 冗余条件。

仅清除 unused shader 源码通常不会带来 GPU 提速，编译器原本就可能消除它。真正的计算精简与源码/常量瘦身分别验证，不混为一谈。

### CPU：按 pass/device 生命周期共享硬件 limits

`RayTracingVulkanPass.java`：使用 create 时已有且校验成功的 `RayTracingSupport.Limits`，保存为 final 字段，用于 TLAS capacity、scratch size/address 及 BLAS/TLAS build validation。

`RayTracingDynamicInstances.java`：每次 update 借用所属 pass 的同一 limits，保留 `max(1, alignment)` 保护，不再每帧重新查询物理设备属性。

该 pass 创建时只查询一次；新 pass/device 会重新查询。不是全局静态缓存，没有跨设备复用，也没有绕过 build-argument validation。

### CPU：每实例只比较一次顶点数组

`RayTracingDynamicInstances.java`：角色/方块实体和物品两处，先确认缓存与拓扑一致，再计算一次 `verticesChanged`，供 replacement 和 deferred 分支共用。

此前普通不变网格和改变但尚未到期的网格会进入两次相同的 `Arrays.equals`；现在两次→一次。null cache / topology change 不额外扫描。保留 replacement interval、history-reset identities、deferred stats、材料更新及资源退役顺序。

### CPU：NRD 固定图像列表只建立一次

`fsr/NrdDenoiser.java` 的私有 `Images`：构造时保存 guide 数组和按 identity 去重的 ownership 数组；每帧 barrier 和销毁继续消费相同顺序、相同 image 身份。

不再每帧重新分配数组、ArrayList、IdentityHashMap/Set 或执行所有图片去重。所有 image/pool 成员在 Images 生命周期内固定，alias 去重仍保留；没有新增 GPU 资源或删减同步。

## 验证与可量化结果

- `./gradlew test jar --offline`：通过。
- 新 `RayTracingCleanupTest`：确认只有七个 active aliases、死逻辑不再存在、NEE 复用 BSDF split。
- 新 `RayTracingRuntimeCleanupTest`：设备属性查询生命周期与顶点比较次数集成契约。
- 新 `fsr/NrdImageListTest`：直接调用真实 Images 构造/访问方法，无 Vulkan allocation，覆盖普通图像和 alias/pool 去重；重复访问返回同一数组，顺序与 ownership 正确。
- 原 NRD/Sundial/motion/composite、动态几何、AS 命令和资源生命周期测试继续通过。移除旧 Sundial 测试中绑定失真注释的断言，改为检查实际 guide list。
- 精确比对 `RayTracingShaderStages` 与本轮前快照：只删指定旧阶段和三个未用 helper，所有 active hit main/其他 active stage 原样保留。
- 实际生成 GLSL 以相同 `glslangValidator -V -Os --target-env vulkan1.2` 编译，`spirv-val` 通过：

| glslang 优化产物 | 本轮前 | 本轮后 |
| --- | ---: | ---: |
| RayGen bytes | 144128 | 140012 |
| RayGen SPIR-V instructions | 7814 | 7593 |
| RayGen imageWrites | 16 | 16 |
| Closest-hit bytes | 37132 | 37132 |
| Closest-hit instructions | 2144 | 2144 |

Closest-hit 清理前后产物 SHA256 完全相同，证明这些 helper 在该编译器中本来就是死代码，不能报成 GPU 加速。

对应 JSON：`docs/profiling/2026-10-01-cleanup-shader-structure.json`。这不是驱动最终机器码、GPU 帧耗或像素等价验证，不能将静态指令减少比例直接换算成 FPS。

源码主要瘦身：RayGen 1890→1785 行、ShaderStages 700→359 行、aliases 17→14 行。与上一轮已部署 JAR 比较：ShaderStages class 91940→33070 bytes，aliases class 106917→48047 bytes；这是类内常量/代码体积，不是 GPU 常驻显存测量。

## 暂不实施

- 按 `appliedTuning` 跳过 NRD native setter：submitted 快照不是 native 参数当前状态，录制取消后的状态未证明可回滚；必须有独立 native-settings cache 并测试 A提交→B录制取消→A 场景，不能直接跳过。
- 合并 NRD frame/motion 矩阵计算：需证明 push bytes、相对相机坐标、Y 翻转和可变矩阵副作用一致。
- 玩家 body/layer/held-item 三路一次性拼接：后续可减少复制，但本轮不扩大几何适配层变更。
- 跨阶段长期保存 BSDF/GGX 计算结果：可能增加 shader register lifetime/pressure，需真正的 GPU profiling。
- 更改现有大型 Vulkan resource constructor/所有权结构：本轮仅共享 immutable limits；不在性能清理中冒险重写异常清理和 GPU 生命周期。

## 实景验证

需要重新启动客户端；保持窗口大小不变，避免触发既有未修复的 resize device-loss。用同场景、同分辨率、GI 和 NRD 设置对比：纹理/接触阴影/金属/玻璃以及动态实体几何不能退化；记录 RT/post GPU timestamps 与 CPU dynamic-update scopes 后才能判断实际性能收益。

## 部署

- 已安装：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`
- SHA256：`29ddd25404eb63ce790c9be72aa052cda03a2cd68899ded400a892df3db1bb6b`
- 旧 JAR/配置备份：`/home/aruku/.minecraft/versions/RTest/backups/runtime-cleanup-20261001-205815/`
- 确认实例未运行后原子替换 JAR；实例配置逐字节保持不变。
