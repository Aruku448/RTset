# Prime 时域重构与 RTest 对比调查

- 调查对象：`bWFuanVzYWth/prime` 的 `26.2` 分支，源码快照 commit `ea83b34`（本地只读 clone `/tmp/prime-research`）。
- 对比对象：当前仓库 `/home/aruku/桌面/RT` 的 RTest 实现。
- 范围：temporal reconstruction、upscaling、accumulation、denoising、history、motion vector、depth、ghosting/history validity。
- 结论基于仓库 README、Prime 自己的设计文档、Slang shader、Java/Vulkan 调度代码及 RTest shader/Java 源码；本次没有修改任何 Java/GLSL 生产代码。

## 1. 结论摘要

Prime 的关键设计不是“把上一帧 final color 重投影后混合”，而是把 **照明历史的几何归属** 与 **屏幕像素的几何归属** 分开：

1. 首个透明接口会生成透射和反射两套 guide/信号；NRD-FSR 为它们建立两个独立的 REBLUR 历史。
2. NRD motion/depth 采用 virtual/PSR 目标表面；FSR motion/depth 采用屏幕上真正可见的接口。这样玻璃后的照明不会被玻璃前表面错误重投影。
3. 动态表面保留上一帧真实位置/三角形重心等 f32 信息，静态表面用上一帧矩阵投影；无效投影、相机后方、无历史或分支无效时明确写 invalid/零 motion，而不是猜测。
4. NRD 负责 RT 信号时空降噪，NRD composite 做 SH resolve、ReJitter、重调制和透明分支合成；FSR 3 之后负责低分辨率到显示分辨率的时域重建/超分。Prime 明确不把 FSR 当作 RT 降噪器。
5. RTest 已经有相同的总体分层：RT AOV → NRD/ReBLUR 或 Sundial → FSR3 temporal upscaling → display。但当前 RTest 的透明/虚拟 guide 分离远没有 Prime 完整，透明信号当前主要并入 specular，且 RTest 的 FSR history 在 shader SPIR-V/FSR 资源内部，不由 RTest 自己实现一套显式 color-history rejection。
6. 最值得借鉴的不是直接复制 Prime 的透明 PSR，而是其 **双坐标语义、显式 validity bits、f32 previous-position、prepare/submit history 生命周期、独立透明分支和可诊断输入契约**。最大风险是把 NRD 的 virtual motion 错接给 FSR，或在缺少稳定动态几何身份时“伪造” motion，反而制造拖影。

## 2. Prime 的实时数据流

Prime README 将兼容 GPU 的实时路径描述为 `NRD + FSR`，RTX GPU 的主路径为 DLSS Ray Reconstruction；同时明确 offline 模式是冻结场景后对 raw undenoised samples 做 native-resolution running accumulation，而不是 realtime reconstruction。[Prime README](https://github.com/bWFuanVzYWth/prime/blob/26.2/README.en.md)

### 2.1 积分器只产生当前帧样本

`shaders/transport/surface/accumulate.slang` 的 `primeAccumulate` 只是把当前 contribution 加入 diffuse/specular accumulator；它没有把上一帧 final color 回灌到 transport。`primeAccumulateAfterPrimary` 和 `primeAccumulateTransparentBranch` 分别累积普通/透明分支的 diffuse 或 specular radiance。

因此 Prime 的实时“历史”属于后处理重建器，不是路径积分器的普通 running mean。离线 running mean 则在 `shaders/service/reconstruct/offline.slang` 中读取 `primeOfflineRunningMean`，使用 `previous + (sample - previous) / sampleCount`；这与 realtime NRD/FSR 历史是两条不同管线。

### 2.2 透明像素的两套身份

Prime 文档将透明像素区分为：

- visible interface：相机首先看到的透明边界，负责屏幕轮廓、FSR depth/motion/mask；
- transmission replacement surface：透射 guide 链中的 solid-angle 表面，负责主 NRD 历史；
- reflection replacement surface：反射 guide 链中的目标或方向，负责反射 NRD 历史。[透明渲染与实时重建.md §总体模型、§2](https://github.com/bWFuanVzYWth/prime/blob/26.2/docs/%E9%80%8F%E6%98%8E%E6%B8%B2%E6%9F%93%E4%B8%8E%E5%AE%9E%E6%97%B6%E9%87%8D%E5%BB%BA.md)

两个候选都有效时，Prime 用 STBN 等概率选一条后续照明路径并乘逆选择概率补偿；未选分支仍只建立 guide。guide 的离散链累计完整 f32 path length 和 reflection transform，最终形成：

```text
virtualPosition = firstDirection * totalPathLength
virtualNormal   = accumulatedReflectionTransform(targetNormal)
```

动态目标保留 triangle id、重心坐标和上一帧顶点信息，从而恢复上一帧 virtual position；大位移不依赖易溢出的 FP16 delta。无效目标、PSR 溢出或平面退化时回退到可见接口/已有表面，而不是发布不稳定的目标。[同一文档 §2 PSR guide 的构造]

## 3. Prime 的 motion vector 与 depth

### 3.1 NRD motion/depth：服务“照明历史属于谁”

`shaders/entry/post/nrd_motion.compute.slang` 的 `primePrepareBranch` 对主分支和 reflection 分支分别执行：

1. 读取分支自己的 position、normal、roughness、material；
2. 计算当前 view-Z；
3. 用 `previousWorldToClip` 投影上一帧位置；
4. 生成约定 `old = new + MV`：

```text
MV.xy = (previousUv - currentSampleUv) * renderExtent
MV.z  = previousViewZ - currentViewZ
```

5. noisy diffuse/specular 除以对应 albedo（demodulate）；
6. 记录 YCoCg/归一化 hit distance、SH direction、normal/roughness、material。

`currentSampleUv` 包含当前 jitter；静止几何用非抖动历史矩阵投影回当前 sample UV，motion 应为零。previous clip-W 非正、NaN/Inf 或 UV 非有限时，Prime 写零 motion，不把投影奇点伪装为有效历史。方向型 reflection guide 用 `w=0`，其 MV.z 为零。[Prime 文档 §4.2；`shaders/entry/post/nrd_motion.compute.slang`]

Prime 的 `output.slang` 还写入：

- `primeNrdPrimaryPosition`：NRD/RR 所需目标位置，动态时可用上一帧 position；
- `primeNrdDisplayPosition`：真实可见接口的位置/flags，专门给 FSR/显示 motion；
- `R8_UINT primeReconstructionControl`：material class、surface-valid、history-valid、motion-valid、object-motion 独立 bit。

这避免把“virtual target 的上一帧位置”和“屏幕真实接口的当前深度”塞入同一个 position。

### 3.2 FSR motion/depth：服务“屏幕上覆盖了什么”

Prime 的 `primePrepareFsrGuide` 只读取 `primeNrdDisplayPosition`，不读取透射或反射 PSR：

- surface depth = visible interface view-Z；
- surface motion = visible interface previous projection；
- sky depth = 0，motion 只描述相机旋转。

FSR 输入契约为：linear HDR color、reversed infinite depth `near/viewZ`（天空 0）、`previousUv - currentSampleUv` normalized UV、reactive mask、transparency/composition mask。Prime 用 T&C mask（透明=1、动画纹理=0.75、其他=0），而不是把透明全部标为高 reactive；高 reactive 会直接丢弃 FSR history，T&C 则让颜色运动与几何运动不一致的反射/透射使用较软历史。`currentJitter` 到 FSR API 边界时取反，motion scale 为 render width/height。[Prime 文档 §4.3、§4.8；`shaders/service/reconstruct/fsr_input.slang`]

这是一条非常重要的差异化契约：

```text
NRD 重投影：虚拟照明目标表面
FSR 重建：屏幕真实可见表面
```

### 3.3 反射与透明历史

NRD-FSR 路径写两组完整 diffuse/specular AOV、albedo、hit distance、direction、position/normal/roughness：主 REBLUR 处理普通表面和透射 PSR，反射 REBLUR 只处理透明条件反射。反射 guide 无效时 `reflectionMaterial.a < 0`，motion pass 先检查 marker 并清空整条 branch，避免读取未定义 companion images。

Prime 文档记录的配置为：主 REBLUR 最多 63 帧主/stabilized history、10 帧 fast history；反射 REBLUR 主历史最多 63 帧、stabilized history 10 帧；低粗糙度反射启用 responsive accumulation、最低 3 帧；两者使用 4 帧 history fix、5×5 hit-distance reconstruction、1.5 sporadic-outlier relative scale。`nrd_composite.compute.slang` 随后对每条分支做 SH/SG resolve、ReJitter、按当前 albedo 重调制，再合成 stable radiance、primary 和 reflection。[Prime 文档 §4.1、§4.4–§4.7]

## 4. Prime 的历史有效性、抗鬼影和 reset

Prime 的抗鬼影不是单一阈值，而是多层门禁：

- history-valid、surface-valid、motion-valid、object-motion 由独立 control bits 传递；
- 当前/上一帧 position、clip-W、UV、normal、view-Z、material class 和 hit distance 共同约束 NRD history；
- 动态几何没有稳定上一帧身份时不伪造 object motion；
- 透明主分支的 material class 保留 visible interface 身份，避免历史从玻璃后表面穿过边界交换到直接可见表面；
- 天空和确定性 emission 是 stable radiance，不建立第二份表面 history；否则旧表面 silhouette 会泄漏到天空；
- 反射 branch 的负 distance/material marker 使整条无效分支明确清空；
- FSR 通过 depth/motion、reactive/T&C、transparency mask 和其内部 temporal rejection 处理显示级 history。

Prime 的时间状态采用 prepare/accept 生命周期。设计文档 §6 规定：先由 camera、scene/texture revision、time、reset 生成 frame plan；frame token 固定 frame index/jitter/history camera；GPU 命令录制后，只有 `encoder.execute()` 被 host submission 接受才推进 NRD/FSR/RR history；execute 前失败的 token abandon，execute 后宿主失败则退休 renderer，不继续复用已经推进的 history。[Prime 文档 §6]

`RealtimeSampleState.java` 和 `NrdFramePlan.java` 体现同一原则：普通相机平移不自动清空 temporal sequence，camera cut、force reset、scene revision 变化才 reset；reset 时从新的 history camera/jitter 开始。配置、后端切换、renderer revision、资源重建和动态/材质语义变化也会导致 reset。

## 5. RTest 当前实现事实

### 5.1 管线顺序

当前 RTest 的主链是：

```text
RayGen RT
  -> nrd_motion.comp（准备 NRD/FSR depth、motion、AOV）
  -> NRD ReBLUR dispatches 或 Sundial（互斥）
  -> nrd_composite_simple.comp / NRD composite
  -> FSR3.1.5 prepare/luma/shading/accumulate/RCAS/display
  -> Minecraft main RenderTarget
```

`src/main/java/com/rtest/client/fsr/RtestFsr3.java` 创建 scene color、RG16F motion、R32F depth、reactive mask、transparency mask 和 display image；同时创建 `NrdDenoiser` 和 `SundialDenoiser`。`recordAfterRayTracing` 先执行 NRD 或 Sundial，再记录 FSR；`submitted` 后才提交各自 frame token，避免录制失败推进历史。

`src/main/java/com/rtest/client/fsr/RtestFsr3Upscaler.java` 是直接 Vulkan compute 实现的 FidelityFX FSR 3.1 upscaler：八个平台无关 pass（含 RCAS），没有 native FSR backend、optical flow 或 frame interpolation。它用 parity ping-pong 的 FSR internal resources 保存历史，并在 `clearPerFrameResources(commandBuffer, reset)` 时按 reset 清理。

### 5.2 RTest 的 motion/depth

`src/main/resources/prime/shaders/nrd_motion.comp`：

- 从 `primaryPosition` 和 `material/specularMaterial` 读取 RT primary hit；
- 静态 hit 用 `currentClipToWorld` 反算 ray，再用 `previousWorldToClip` 投影当前世界点；
- dynamic hit 使用 raygen 写入的 `raygenMotion`；
- sky 使用 raygen motion 的 XY，去掉平移视差；
- 输出 `nrdMotion = (previousUv-currentUv, previousViewZ-currentViewZ)`；
- 输出 NRD `viewZ` 为正线性 view-Z，FSR depth 为 `near/viewZ`，天空为 0；
- 输出 normal/roughness、YCoCg demodulated noisy diffuse/specular、normalized hit distances。

这已经与 Prime 的核心 motion 约定高度相似，尤其是 static-vs-dynamic 分支、reversed depth、sky translation 处理和 NRD/FSR barrier。但 RTest 当前 shader 使用 `rgb10_a2` normal/roughness 和较紧凑的 `rgba16f` scratch；Prime 当前设计文档选择 `RGBA32F` 世界法线/roughness，并用独立 `R8_UINT ReconstructionControl` 保存 validity/class，原因是先避免法线编码误差污染重建门禁。

### 5.3 RTest 的 NRD

`src/main/java/com/rtest/client/fsr/NrdDenoiser.java` 通过窄 C ABI 取得 NRD 4.17.3 dispatch description，但 NRD 不拥有 Vulkan handle；RTest Java 创建 image、pipeline、descriptor、constant buffer 并记录 dispatch。RTest 的 opaque path 使用 `REBLUR_DIFFUSE_SPECULAR`，输入包括 noisy diffuse/specular、normal/roughness、view-Z、motion、material/hit distance。

`nrd_composite_simple.comp` 明确：

- 过滤 diffuse/specular 后乘回 material；
- direct diffuse 保持空/不进入 NRD filtered channel；有限太阳 visibility 仍需滤波；
- emission 和 sky/camera segment atmosphere bypass NRD；
- dynamic entity shadow 没有稳定 receiver-side MV，因此当前像素保持 exact current-frame result，避免表面历史 smear/lag；
- `strength=0` 仍能得到 diffuse+specular fallback。

RTest 另有 `SundialDenoiser` 自研风格 temporal/spatial 路径：`sundial_denoiser.comp` 读取 motion、historyColor/historyMeta，使用四 tap bilinear history、每 tap 独立 normal/depth 兼容检查、luminance clipping、history confidence、3×3 cross-compatible spatial pass、normal/depth/luma/color/roughness/hit-distance 权重。它对 sky 不建立 surface history，并把当前八面体 normal 编码写回 historyMeta。

### 5.4 RTest 的 FSR history/reset

`RtestFsr3Upscaler.beginFrame` 在以下情况 reset：首次初始化、显式 `requestReset`、camera cut、scene reset revision 变化、atlas view/sampler 变化。普通相机运动不 reset，使用上一帧 camera/jitter。`submitted` 后才更新 previous camera/jitter/frame index；这是与 Prime prepare/accept 相同的可靠方向。

`RtestFsr3` 还在 NRD/Sundial 路径中对 denoiser enable 切换、sun direction 变化、camera cut、FSR token reset 做 restart。`RayTracingVulkanPass` 在 TLAS/geometry publication、材质解释变化等场景调用 `fsr.requestReset()`；dynamic entity registry 为新建、停用、变换 discontinuity、geometry change 写 `FLAG_HISTORY_RESET`。

但有一个重要语义差异：RTest 的 FSR history 主要由 FidelityFX FSR shader 的 internal resources 管理；RTest 自己不拥有 Prime 那种按 virtual target/visible interface 分开的 FSR guide 与显式 validity-control image。当前 FSR 的 motion/depth 是共享低分辨率屏幕输入，而 NRD motion 另存在 NRD image；RTest 的 `nrd_motion.comp` 同时生产二者。

## 6. 横向差异表

| 主题 | Prime | RTest 当前状态 | 判断 |
|---|---|---|---|
| 历史对象 | NRD 主/反射两套历史；FSR 自己的 temporal history；RR 独立后端 | NRD opaque 一套主历史；Sundial 可选一套自研历史；FSR internal history | RTest 总体链正确，但透明历史粒度较粗 |
| 历史建立 | 当前 ray sample/AOV + stable guide；不把 final color 回灌 transport | 当前 RT AOV 写入 scene/NRD images，后处理消费 | 相同原则 |
| NRD motion | virtual PSR target 的 previous position/矩阵投影 | primaryPosition + static previous matrix/dynamic raygenMotion | 对普通不透明相似；Prime 的透明/virtual target 更完整 |
| FSR motion/depth | visible interface displayPosition，独立于 NRD virtual guide | nrd_motion 由 primaryPosition 生成 FSR depth/motion | 对不透明足够；对透明容易把“照明目标”和“屏幕表面”混在一起 |
| dynamic motion | triangle id/重心/上一帧顶点恢复 f32 previous virtual position | dynamic TLAS metadata + raygen motion；实体 history reset 有基础 | RTest 有基础，但需验证所有动态 hit 的稳定 identity 和 invalid fallback |
| validity | 独立 surface/history/motion/object-motion bits、负 marker、finite/W 检查 | token reset、dynamic flags、shader finite/sanitization；缺少统一 per-pixel validity image | Prime 的控制面更清晰、更可诊断 |
| ghosting | NRD depth/normal/material/hit-distance/history fix + FSR mask + branch separation | NRD ReBLUR 自身 rejection；Sundial explicit checks；FSR reactive/transparency mask 已有 | RTest 已有大量机制，但透明身份边界不足 |
| denoise | NRD 主、反射、SIGMA shadow；SH resolve/ReJitter 后再 FSR | NRD opaque composite 或 Sundial，随后 FSR3 | RTest 对 opaque 已接近目标；透明 branch 尚非 Prime 级 |
| jitter | ray sample jitter、NRD current/previous jitter、FSR API 取反 | RTest raygen/NRD 直接 sample sign，FSR boundary `forFsrDispatch()` 取反 | 方向正确，需继续用静止场景测试零 MV |
| reset 生命周期 | prepare/accept；提交失败 abandon/退休 | frame token 录制后 submitted 才推进，已有 fence/retire 语义 | 这是 RTest 可保留的强项 |
| 输出 | linear HDR → FSR → exposure/display transform | linear HDR → FSR → Prime display transform | 顺序一致 |

## 7. 可借鉴改进（不等于立即实施）

### P0：先加强验证，不改变算法

1. **分离并可视化两类 motion/depth**：同时 debug `NRD motion/viewZ` 和 `FSR motion/depth`，验证静止相机、Halton jitter、相机平移、动态实体、天空、玻璃边缘。Prime 的规则要求静止表面 motion 为零，FSR depth 必须对应真实可见表面。
2. **引入显式 per-pixel validity contract**：至少把 surface-valid、history-valid、motion-valid、object-motion、material/reconstruction class 从 normal/material alpha 中分离出来，避免负距离、NaN、sky 和无效反射依赖隐式约定。
3. **加强 reset coverage**：scene/TLAS publication、atlas/material/lighting semantic changes、尺寸/质量 preset、denoiser backend switch、sun/environment discontinuity 都应有明确的 reset 原因和诊断计数。
4. **做 ghosting regression scenes**：玻璃后移动方块、静态玻璃边框、动态实体投射阴影、天空/几何 disocclusion、emissive firefly、快速镜面反射。记录每像素 history-valid 与 motion-valid，而不只看最终截图。

### P1：改进透明契约

1. 为 transmission 和 reflection 建立独立 NRD branch images/instances，而不是把 transmission radiance 简单并入 specular。RTest 的 `NrdDenoiser.createTransparentBranch` 已经提供了可复用方向，但需要端到端接入 raygen guide、motion、composite 和资源生命周期。
2. 独立维护 `visible interface` guide 给 FSR，维护 `virtual target` guide 给 NRD。不要用一个折射后的 position 同时作为 FSR depth 和 NRD history position。
3. 将透明材质的 FSR mask 细分为 T&C/transparency composition，而不是默认高 reactive；高 reactive 会牺牲历史并造成 jitter noise，过低则会拖影。
4. 对 dynamic entities 保存稳定 triangle/instance identity + f32 previous position；如果上一帧身份缺失，显式 invalid motion，而不是使用当前位置伪装 static。

### P2：调参和质量

1. 在 RTest NRD 已有的 max history、fast history、history fix、firefly、disocclusion 参数上，增加“主表面/反射/透射”独立 preset；低 roughness specular 使用更 responsive 的 accumulation。
2. 将 RTest 当前 `rgb10_a2` normal/roughness 的误差与 Prime 的 RGBA32F 方案做 A/B；只有当 debug/质量数据证明格式足够稳定时才保持低精度。
3. 继续保留 emission、sky、direct deterministic terms 与 noisy indirect/specular 分离；不要用一个 final-color history 把确定性发光和随机路径样本混为一谈。

## 8. 风险和不应直接复制的部分

- **Prime 的透明 PSR 不是低成本 patch**：它需要 guide 链、介质/反射变换、动态三角形 previous data、双分支 AOV、NRD composite 和大量 shader ABI 协同；只复制一段 motion 公式会产生更严重的错位。
- **virtual motion 不能直接给 FSR**：FSR 的 depth/motion 描述屏幕覆盖表面；错接会使玻璃内后景相对边框滑动，或把后景 history 泄漏到边框。
- **历史有效不等于 reset**：过度 reset 会让 FSR/NRD 失去收敛并增加噪声；过少 reset 会产生鬼影。应优先 per-pixel rejection，整帧 reset 只用于真正的 discontinuity。
- **动态实体阴影没有 receiver MV**：RTest 当前选择 bypass current-frame 结果是保守且合理的；不要仅因为 Prime 使用 virtual guides 就无条件把动态 shadow 纳入旧历史。
- **NRD 版本/ABI/许可证**：Prime 源码文档以 NRD 4.17.4 为基线；RTest 当前资源/bridge 是 4.17.3。输入格式、motion sign、normal encoding 或 dispatch ABI 不可混用。NRD 许可也不同于 FidelityFX，升级或复制资源必须单独核对许可证。
- **FSR 版本/输入契约**：RTest 使用项目内 FSR 3.1.5 SPIR-V；Prime 文档记录的 Prime FSR 版本/集成可能不同。不能仅凭同名输入认为 pass 常量、jitter sign、depth reconstruction 完全兼容。
- **RTest 当前生产链以 Vulkan barrier 和 submission token 为边界**：改动历史资源时必须保持 RT→compute、compute→FSR、host submission/fence 的同步；否则会把偶发 GPU race 误判为 temporal 算法问题。

## 9. 一手资料索引

### Prime

- [README.en.md](https://github.com/bWFuanVzYWth/prime/blob/26.2/README.en.md)：后端、实时/离线模式、限制。
- [透明渲染与实时重建.md](https://github.com/bWFuanVzYWth/prime/blob/26.2/docs/%E9%80%8F%E6%98%8E%E6%B8%B2%E6%9F%93%E4%B8%8E%E5%AE%9E%E6%97%B6%E9%87%8D%E5%BB%BA.md)：透明 guide、NRD/FSR/RR、history 生命周期和输入契约。
- [`shaders/service/reconstruct/output.slang`](https://github.com/bWFuanVzYWth/prime/blob/26.2/shaders/service/reconstruct/output.slang)：resolved sample 到重建输入的适配、visible/virtual guide、validity。
- [`shaders/entry/post/nrd_motion.compute.slang`](https://github.com/bWFuanVzYWth/prime/blob/26.2/shaders/entry/post/nrd_motion.compute.slang)：NRD 双分支 motion/view-Z/AOV 准备。
- [`shaders/service/reconstruct/fsr_input.slang`](https://github.com/bWFuanVzYWth/prime/blob/26.2/shaders/service/reconstruct/fsr_input.slang)：FSR reversed depth、normalized motion、mask sanitizer。
- [`shaders/entry/post/nrd_composite.compute.slang`](https://github.com/bWFuanVzYWth/prime/blob/26.2/shaders/entry/post/nrd_composite.compute.slang)：NRD resolve/rejitter/composite。
- [`src/client/java/dev/prime/render/vulkan/reconstruction/`](https://github.com/bWFuanVzYWth/prime/tree/26.2/src/client/java/dev/prime/render/vulkan/reconstruction)：reconstruction resource/backend lifecycle。

### RTest

- `src/main/resources/prime/shaders/nrd_motion.comp`：RTest NRD/FSR motion/depth/AOV producer。
- `src/main/resources/prime/shaders/nrd_composite_simple.comp`：NRD 输出、direct/emission/sky 合成。
- `src/main/resources/prime/shaders/sundial_denoiser.comp`：RTest 自研显式 temporal/spatial history、compatibility 和 clipping。
- `src/main/java/com/rtest/client/fsr/RtestFsr3.java`：资源、denoiser/FSR 顺序、reset/submission。
- `src/main/java/com/rtest/client/fsr/RtestFsr3Upscaler.java`：FSR 3.1.5 compute passes、jitter、内部 history、reset。
- `src/main/java/com/rtest/client/fsr/NrdDenoiser.java`：NRD Vulkan realization、dispatch/history bindings、transparent branch 工厂。
- `src/main/java/com/rtest/client/RayTracingVulkanPass.java`：RT descriptor、dynamic motion metadata、scene publication/reset、提交边界。
- `docs/DENOISER-RESEARCH.md`、`docs/MAINTENANCE.md`、`docs/native-effects-composition.md`：RTest 既有设计记录和运维契约。

## 最终判断

RTest 已经具备 Prime 时域重构架构的骨架：正确的 RT AOV 分层、NRD/Sundial 降噪、FSR3 temporal upscale、reversed depth、motion sign/jitter 边界、显式 Vulkan barrier 和 submission-scoped history。当前最显著的缺口不是“缺一个 accumulation 算法”，而是 **透明场景的 history identity 与 visible/virtual guide 分离不足，以及统一 per-pixel validity/诊断契约不完整**。下一步应先做 motion/depth/validity 可视化和 ghosting 回归，再评估双透明 NRD branch；不要直接重写 RTest 的 Java/GLSL 生产代码来追逐 Prime 的完整透明 PSR。

---

# 补查：区块/方块更新与时域历史（Prime 26.2 vs 当前 RTest）

本节只补充区块编译、方块状态/材质变化、加载卸载、局部方块更新及 BLAS/TLAS/geometry revision 的时域语义；未修改任何生产 Java/GLSL。

## 10. Prime：区块更新不是整帧 temporal reset

### 10.1 更新入口和扩散范围

Prime 在 `src/client/java/dev/prime/mixin/LevelExtractorMixin.java` 的 `prime$markBlockDirty` 和 `prime$markBlocksDirty` 分别拦截 `blockChanged(BlockPos, int)`、`setBlocksDirty(IIIIII)`，调用 `PrimeRuntime.invalidateBlocks(...)`；`allChanged()` 则调用 `invalidateAll()`。注释明确指出 `setSectionDirty` 也可能由纯光照更新触发，但 Prime 不消费 vanilla light data。也就是说，单点方块状态变化和矩形范围变化都先进入统一 terrain invalidation，而不是直接清掉 NRD/FSR 全部历史。

`TerrainStreamer.invalidateBlocks()` 将范围扩展一格后交给 `BoundedDirtyClusters.addExpandedBlockRange()`，并映射到对齐的 `4×4×4 Section cluster`。`BoundedDirtyClusters` 是有界、合并、跨线程安全的队列：同一 cluster 多次通知只占一个 key；范围或队列超限才升级为 `fullInvalidation`。`TerrainStreamer.drainInvalidations()` 对每个仍在 desired window 的 cluster 推进 `ClusterGenerationTracker` generation 并重新排队；过期任务由 `ClusterPipelineState` 取消/拒绝，避免旧编译结果回写新场景。[Prime `BoundedDirtyClusters.java`、`ClusterGenerationTracker.java`、`ClusterPipelineState.java`、`TerrainStreamer.java`]

这是 Prime 的关键边界：**局部方块更新影响局部编译/发布单位；只有全量失效、世界切换、资源语义变化等才影响全场景 continuity。**

### 10.2 Section 编译、邻域和材质变化

`VanillaClusterCompiler.capture()` 要求 cluster 周围的完整 horizontal halo 已加载；它从 `LevelChunk`/`LevelChunkSection` 读取非空 Section，创建不可变 `VanillaSectionSnapshot`，随后 `compile()` 通过 `VanillaSceneInterpreter.compileSection()` 和 `ClusterSceneTranslator.translate()` 生成不可变 `CpuClusterMesh`。因此方块状态、邻接面剔除、biome tint、fluid、透明关系和 sprite/LabPBR 解释均属于 cluster 的编译输入；边界方块变化会通过扩展范围重新编译邻居，而不是只重编发生变化的那个 Section。[Prime `VanillaClusterCompiler.java`；`docs/区块簇场景翻译架构.md`]

材质/纹理语义变化走更强路径：`TerrainStreamer` 检测 translated LabPBR material 是否使 resident texture lookup 失效，随后 `invalidateAll()`；资源 reload 由 `ResourceEpochCoordinator` 暂停旧 generation、完成后安装新 generation，worker 持有 lease，避免旧 atlas/material 输入继续发布。Prime 的 renderer/settings revision 另由 `PrimeConfig`/`RendererSettings` 管理，配置改变推进 revision；`RealtimeSampleState.plan()` 将其作为 `resetRevision` 输入。

### 10.3 BLAS/TLAS、geometry revision 与 history

`TerrainScene.update()` 对静态 cluster 内容变化建立 replacement TLAS；相同 voxel/prototype 的 BLAS 可复用，未变化 cluster 保留 resident，旧 cluster/TLAS 通过 `TerrainUpdateTransaction` 在提交前/提交后分别立即清理或 deferred retire。动态 cluster 替换独立处理。提交成功后，`TerrainScene` 发布：

- `revision`：每次 scene publication 递增，代表 GPU resident scene snapshot 变化；
- `resetRevision`：世界被视为与旧世界无关时递增（`beginUnrelatedWorld()`），用于跨世界/不可连续场景；
- `occluderRevision`：仅当 `TerrainOccluderChange` 非空时递增，表示遮挡/可见性语义变化；
- `ResidentSceneView` 同时携带上述 revision 和 occluder change 列表。

这三个概念没有被混成一个“geometry changed = reset”。`RealtimeSampleState` 的文档和 `plan()` 明确：普通相机移动保留 temporal sequence；camera cut、force reset、初始帧或 `resetRevision` 变化才重置 sample/epoch/history camera；**material、lighting、local scene changes 保留序列，让 temporal reconstruction 在变更像素处拒绝历史，而非整帧闪白/闪噪。**[Prime `TerrainScene.java`；`TerrainUpdateTransaction.java`；`RealtimeSampleState.java`；`docs/场景资源生命周期.md`]

因此 Prime 的 TLAS/BLAS replacement 本身不必等价于全局 history reset。它依靠当前/上一帧位置、normal、view-Z、material/hit-distance、occluder change 和 disocclusion rejection，在变化区域局部淘汰 history；真正没有可比性的世界或渲染语义才推进 reset revision。

## 11. RTest：局部场景发布与 temporal reset 的实际耦合

### 11.1 编译快路径和方块材质语义

`SectionCompilerCaptureMixin.rtest$captureCompiledMesh()` 在 vanilla `SectionCompiler.compile(...)` 返回时调用 `CompiledSectionMeshCache.publish(sectionPos, results)`，缓存的是各 `ChunkSectionLayer` 的 CPU copy。`CompiledSectionMeshCache` 只有 Section key 和 LRU/字节上限，没有独立 generation、block-state fingerprint 或材质 revision；缓存失效依靠外部回调。

`RayTracingScene.CaptureSession.step()` 在每个 Section 上优先使用该 compiled mesh；含 fluid、glass、emitter 或启用 sprite-aware PBR CPU capture 时改走 `captureSection()` 的 CPU fallback。后者才能重建 block/biome tint、fluid 邻接和 optical/PBR 语义。故同一个方块更新可能不仅改变顶点，还改变 layer、面剔除、fluid surface、透明 IOR、emission 或 LabPBR companion lookup；不能只对比 vertex count 判断“局部更新足够”。

### 11.2 方块、chunk load/unload 和 camera window

`ClientLevelDirtyMixin` 在 `ClientLevel.setBlocksDirty(BlockPos, oldState, newState)` 尾部调用 `RayTracingProbe.markSectionDirty()`；它使当前 Section 失效，并在坐标落在 0/15 边界时额外失效相邻 Section，以覆盖 vanilla 面剔除的邻接依赖。`onChunkLoaded`/`unload` 调用 `markChunkDirty()`：当前 chunk 和四个水平邻居的 compiled cache 都失效；加载时加入窗口内非空 Section，卸载时从已发布场景移除相应 Section。`RayTracingProbe` 通过 `pendingDirtySections`、`pendingCaptureSections`、`sceneGeneration` 和 `captureGeneration` 合并、限制并丢弃过期捕获；camera 跨 chunk 时用 `SceneWindowDelta` 增删窗口成员，不因普通 camera movement 整场重捕获。[RTest `ClientLevelDirtyMixin.java`、`RayTracingProbe.java`、`SceneWindowDelta.java`]

这条路径在“捕获/CPU merge”层已是局部的，但发布层仍有全量特征：`RayTracingScene.SceneGeometry.replaceSections()` 保留未变 Section，却重新 flatten 所有 resident vertices/materials 并构建新的 immutable SceneGeometry；`RayTracingVulkanPass.updateGeometry()` 重写 instance/material/light buffers，并按 Section fingerprint 复用未变 BLAS。TLAS 在 Section 数量相同且旧 TLAS 已 build 时可 UPDATE，否则新建；无论是否复用 TLAS，发布都会令 `topLevelBuilt=false`。

### 11.3 当前 RTest 对 history 的触发规则

RTest 的 `SceneGeometry.revision` 在 `RayTracingScene` 中单调分配；但局部 `replaceSections()` 明确传入 `this.revision`，保留 geometry revision，以表达“局部像素可由 depth/normal/motion rejection 处理”。完整新 scene 才获得 `NEXT_REVISION.incrementAndGet()`。这是一个接近 Prime 的语义设计。

然而 Vulkan 发布端的实际策略更保守：`RayTracingVulkanPass.updateGeometry()` 在 geometry publication 后设置 `dynamicHistoryResetPending=true` 并调用 `fsr.requestReset()`；注释说明静态 primary surface 也可能反射已变化的 dynamic/scene TLAS，所以不能保留 specular history。`RtestFsr3Upscaler.beginFrame()` 会因 `sceneResetRevision`、atlas view/sampler、camera cut、首次初始化或显式 reset 清理 FSR internal history；`RtestFsr3.requestReset()` 同步耦合 NRD/Sundial 的 restart。故当前行为是：**局部方块更新的场景 revision 语义倾向局部拒绝，但 geometry publish 的实现实际触发整套 FSR/耦合 history reset。**

动态对象另有局部门控：`DynamicInstanceRegistry.upsert()` 对新建、slot 重用、discontinuity、family/`GeometryKey` 改变写 `FLAG_HISTORY_RESET`，并令 previousTransform=currentTransform；`RayTracingVulkanPass.updateDynamicInstances()` 对新增、拓扑变化和 BLAS vertex update 加入 `historyResetIdentities`。动态 BLAS 顶点数不变时走 UPDATE，拓扑变化换 BLAS 并使 TLAS 变化；但每次动态 transform 改变不必清空全局 FSR history，只通过 instance motion metadata 走 per-instance rejection。TLAS replacement 或 geometry publication 则把所有 active dynamic identities 放入 reset 集合。

## 12. 差异、潜在鬼影和建议

| 事件 | Prime 26.2 | 当前 RTest | 时域风险 |
|---|---|---|---|
| 单方块状态/材质改变 | block range 扩展到 cluster/halo，推进 cluster generation；local scene continuity 保留 | Section + 边界邻居重新捕获，merge 后整 Geometry publication；调用 `fsr.requestReset()` | 过度 reset：局部编辑导致全屏噪声/闪烁；不 reset 则旧反射/阴影拖影 |
| chunk load/unload | desired cluster 增删、generation/cancellation；resident/TLAS replacement，通常不改 resetRevision | window delta/dirty Section；发布新 TLAS 或 update | 新 chunk/disocclusion 处可能历史混入；发布期间 scene window 与 FSR history 不一致 |
| fluid/玻璃/发光材质 | cluster compiler 重译邻域；纹理 generation 变化可 full invalidation | 触发 CPU fallback，但 compiled cache 没有材质 generation；PBR SSBO/atlas 变化另行 reset | 同几何不同 BRDF/IOR/emission 时旧 specular/indirect 泄漏 |
| BLAS UPDATE（动态顶点） | 动态 cluster 记录 motion mesh/previous positions；连续对象可保留历史 | stable identity + previous/current transform；geometry/topology change reset identity | identity 缺失、slot reuse 或 transient capture miss 会把新物体与旧物体串历史 |
| TLAS UPDATE/replace | scene revision 与 occluderRevision 分离；局部拒绝优先 | geometry publish 统一 `fsr.requestReset()`，dynamic TLAS metadata 另有 reset bit | 过度 reset降低收敛；若某些 TLAS/材质更新绕过 publish，则会出现全局静态 surface 反射旧动态物体的鬼影 |

具体建议（仅调查结论，不在本轮实施）：

1. **先拆分 revision 语义。** 在 RTest 侧保持已有 `SceneGeometry.revision`，再显式区分 `terrainContentRevision`、`material/atlasRevision`、`tlasTopologyRevision`、`occluderRevision` 与 `temporalResetRevision`。局部 Section 内容变化不应自动等同 atlas/renderer reset；TLAS topology 变化是否清 FSR，应由“影响范围/反射可见性”策略决定并可诊断。
2. **给 Section cache 和 capture 绑定 generation。** `CompiledSectionMeshCache` 至少应能表达 section content generation 与 atlas/PBR generation；否则同一 Section 的 compiled mesh 可能在 block state 已变而 callback 尚未覆盖、或材质解释变更后继续复用。Prime 的 cluster generation/cancellation 是可借鉴的最小模型。
3. **短期保守路径要明确标注。** 在尚未有 per-pixel scene-change mask、反射 receiver motion 或 occluder rejection 前，geometry publication 的全局 reset 是质量安全阀，不应贸然删掉；但应记录 reset reason、affected Section count 和 TLAS reuse，避免把全屏闪烁误诊为 FSR 错误。
4. **优先做局部更新 ghosting 回归。** 固定相机分别测试：替换方块、清空方块、边界方块、fluid level、玻璃/发光材质、chunk load/unload、动态 BLAS UPDATE、动态 BLAS topology replacement、slot reuse。画面和 debug 同时记录 FSR reset、NRD/Sundial history confidence、motion、scene/geometry revision。
5. **不要只 reset FSR。** 若将来把局部 geometry publication 改为不全局 reset，必须同时为 NRD/Sundial 提供局部 scene-change/occluder rejection；否则 FSR 虽继续收敛，NRD 的间接光仍会在静态 primary 表面保留旧 TLAS 结果，形成慢速反射/阴影拖影。
6. **保留 Prime 的提交边界思想。** RTest 的 `RayTracingVulkanPass` 已等待上一帧 fence 后更新资源，且 `RtestFsr3Upscaler` 在提交后推进 token；未来异步 TLAS/BLAS 发布应把 geometry revision 的 prepare/accept/retire 与 temporal frame token 同一提交绑定，不能让新 scene revision 与旧 FSR history 在不同 submission 中交叉可见。

## 13. 本次补查资料索引

### Prime 26.2 源码

- `src/client/java/dev/prime/mixin/LevelExtractorMixin.java`：`blockChanged`、`setBlocksDirty`、`allChanged` 失效入口。
- `src/client/java/dev/prime/render/runtime/terrain/BoundedDirtyClusters.java`：范围扩展、有界 dirty queue、full invalidation。
- `src/client/java/dev/prime/render/runtime/terrain/ClusterGenerationTracker.java`、`ClusterPipelineState.java`：局部 generation、取消和 stale result 门禁。
- `src/client/java/dev/prime/render/runtime/terrain/TerrainStreamer.java`：desired window、cluster load/unload、材质 generation 变化和 invalidation drain。
- `src/client/java/dev/prime/render/scene/vanilla/VanillaClusterCompiler.java`：halo、LevelChunk/Section capture、section compile、cluster translation。
- `src/client/java/dev/prime/render/vulkan/terrain/TerrainScene.java`：resident cluster、BLAS/TLAS replacement、`revision`/`resetRevision`/`occluderRevision`。
- `src/client/java/dev/prime/render/vulkan/terrain/TerrainUpdateTransaction.java`：提交前、提交后和 published 资源生命周期。
- `src/client/java/dev/prime/render/RealtimeSampleState.java`：local scene change 不整帧 reset，`resetRevision`/camera cut 的 temporal reset。

### 当前 RTest 源码

- `src/main/java/com/rtest/mixin/ClientLevelDirtyMixin.java`：block/chunk load/unload callback。
- `src/main/java/com/rtest/mixin/SectionCompilerCaptureMixin.java`、`src/main/java/com/rtest/client/CompiledSectionMeshCache.java`：vanilla compiled mesh capture/cache。
- `src/main/java/com/rtest/client/RayTracingProbe.java`：`markSectionDirty`、`markChunkDirty`、camera window delta、`sceneGeneration`、bounded capture/merge。
- `src/main/java/com/rtest/client/RayTracingScene.java`：`SceneGeometry.revision`、`replaceSections`、compiled/CPU fallback、全量 flatten。
- `src/main/java/com/rtest/client/RayTracingVulkanPass.java`：`updateGeometry`、BLAS cache、TLAS reuse/replace、`dynamicHistoryResetPending`、`historyResetIdentities`。
- `src/main/java/com/rtest/client/DynamicInstanceRegistry.java`：stable identity、slot generation、previous/current transform、`FLAG_HISTORY_RESET`。
- `src/main/java/com/rtest/client/fsr/RtestFsr3.java`、`RtestFsr3Upscaler.java`：scene revision、atlas identity、FSR reset 和提交 token。

## 补查结论

RTest 已经正确实现了“区块/Section 捕获增量化”和“动态对象局部 motion/reset bit”，但在静态 geometry publication 上仍采取全局 FSR/耦合 history reset；Prime 则把 local cluster scene revision、occluder revision 和 temporal reset revision 分开，优先依赖 per-pixel rejection。当前最现实的风险不是漏掉一个 dirty callback，而是 **材质/邻接变化已经局部发生，历史却以过粗或过细的粒度处理**：过粗会导致方块更新全屏闪烁和收敛变慢，过细且缺少 occluder/反射拒绝会留下玻璃、阴影和镜面反射鬼影。建议先补 revision/reset reason 可观测性和局部更新回归，再决定是否把 RTest 的 geometry publish 从全局 FSR reset 收窄。
