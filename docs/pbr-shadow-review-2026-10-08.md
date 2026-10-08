# PBR 与箱子阴影差异检查

用户提供当前 RTest 的箱子及其镜面反射截图，并确认关闭降噪后仍存在差异。因此不能将问题仅归因于 NRD，也不能从截图推断所有直接/反射路径的真实几何命中一致。

## 已复现并修复：切线手性

`RayTracingTangent.fromTriangle()` 原先只用 UV 行列式的符号作为切线手性。这要求传入的模型法线与三角形绕序一致；模型提供的法线、反向绕序或镜像变换并不总能满足此前提。

数值回归使用位置和 UV 的实际 +U/+V 导数作为参考，再按现有 shader 的切线重建规则比较。反向绕序且模型法线仍为 +Z 的输入，在旧实现中得到相反的 +V。`./gradlew rayTracingPbrMaterialsTest --console=plain` 已实际返回失败，日志 `/tmp/rt-pbr-tangent-red.log`。这是 PBR 坐标系错误的复现，不是对截图原场景的 GPU 重现。

修复从位置/UV 计算真实副切线，并用 `dot(cross(N,T), B)` 确定手性，退化或非有限结果标为无效帧。材质 ABI、shader、射线数、提交数不变。法线贴图和 POM 共用这个编码，均能受益。增加 24 组轴向、法线侧、绕序、镜像 UV 组合回归，正常与镜像原测试仍通过。

通过 `rayTracingPbrMaterialsTest`、`rayTracingShaderContractTest`、`lightingLogicRepairTest`、`itemModelGeometryAdapterTest`、`modelCaptureBatchTest`、`customEntityGeometryTest` 及 JAR 构建。shader、GPU recorder、AS 和缓冲相关 17 个 class 与实测版本 A 一致。实例 JAR 已替换，SHA-256 为 `2520eb26cdb39f74fce8db8db6b37267942b49b2d303f482bcb31e0747b5ba88`，需要重启生效。

尚未取得当前场景模型三角形的法线/绕序快照或修复前后 GPU 图像，因此不能认定截图中的箱子阴影已修好。

## 其他发现，未混入本次修复

1. `RayTracingShaderStages` 将法线贴图后的着色法线写入 `pathNormal.xyz`。RayGen 随后把它命名为 `geometricNormal`，并按观察射线与该着色法线的夹角整体翻转，还用它偏移阴影射线。它并非真实几何法线：较陡的法线贴图可能在同一几何表面的不同观察方向下触发不同翻转。这是主视图与反射差异的待验证方向，需要原场景法线/命中数据或关闭法线贴图的对照，当前没有修改此 shader 路径。
2. LabPBR 绿色通道在 CPU 和 GPU 被当作连续金属度，并通过 `0.04 + 0.96 * metallic` 得到普通反射率。官方标准的 0–229 表示线性 F0，230–255 表示金属。当前做法会改变漫反射/镜面分配，不符合该通道语义。蓝色通道也未将 0–64 孔隙度与 65–255 次表面散射分段解码。这些属于独立的材质格式问题，不能单凭它们宣称阴影差异已定位。标准：[shaderLABS LabPBR Material Standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard)。
3. POM 只调整纹理 UV；closest-hit 的 `pathPosition` 仍是真实三角形命中点，shadow any-hit 也没有高度场遮挡求交。纹理高度造成的凹凸与真实几何锁扣的投影必须区分。当前没有增加高度场阴影射线或改变真实几何。
4. `roughness = 1 - smoothness` 单独看不像标准中的平方，但当前 BSDF 在消费该参数时使用 `roughness * roughness`。应按完整参数链评估，不能再盲目平方造成四次方。

后续最有区分力的观察是在同一视角将 PBR 法线强度设为 0，再单独关闭实体 POM，记录箱子本体与镜面的阴影变化。关闭降噪的反馈已经记录，无需重复。

## 后续实际对照与修复（04:00 后）

用户反馈切线修复后差异仍在，提供 `2026-10-08_03.47.21.png` 与 `2026-10-08_03.49.33.png`。法线强度 0 的观察仍异常；实体 POM 关闭时配置及截图仍显示差异。临时 `no_sky_nee` 和 `no_volume` 对照均收到“画面没有变化”的反馈，实际 GPU 日志确认对应审计 shader 已使用。临时 `no_pbr` 对照收到“差异明显减小”反馈，并提供 `/tmp/Spectacle.ADIbHm/屏幕截图_20261008_035953.png`。这缩小到逐像素材质处理，但尚非单项根因证明。三个审计配置均已自动恢复，当前 `rayCostAuditProfile = "baseline"`，用户自己调整的其他设置没有被覆盖。

新增实际 GPU 回归 `python3 tools/profiling/check_pbr_normal.py`，从生产 `PBR_FUNCTIONS` 和 RayGen 的朝向表达式构造 compute 探针，复用独立 Vulkan dispatch/readback 入口。它在 RX 7800 XT 上执行真实 `samplePbr`，对 256 组 RG 编码和 0、0.5、1、2 四个强度共 1024 组与 CPU 的缩放约定比较；再检查同一几何面的两个观察方向。旧版本 512 组法线不一致，朝向测试另有 1 次失败，总失败计数 513（`0x201`），日志 `/tmp/rt-pbr-normal-gpu-red.log`。修复后失败计数为 0，日志 `/tmp/rt-pbr-normal-gpu-green.log`。这复现确定的解码/朝向错误，不是完整 Minecraft 场景回放。

修复内容：

- GPU LabPBR 法线现在先缩放 XY，再重建 Z，与 CPU 一致。零强度明确生成 `(0,0,1)`；退化/非有限向量回退为平面法线。CPU classic/Bedrock 零强度也明确生成平面法线，避免原 Z 为 0 或负数的输入留下错误朝向。
- Closest-hit 根据原始几何面与入射射线决定双面朝向，再把着色法线交给 RayGen。RayGen 不再误把映射后的法线当成几何法线并按视角整体翻转。原先的“法线强度 0”对照受上述 GPU 解码缺陷影响，不能作为完全排除法线问题的证据。
- Payload ABI、AS 标志、diagnostic buffer、同步、资源管理和提交路径未修改；`RayTracingVulkanPass.java` 和 `VulkanAccelerationResources.java` 源码仍与版本 A 相同。Pass 编译类因内联 closest-hit shader 字符串改变而变化，不能宣称其 class 文件完全一致。

新增 `sun_visibility` 诊断候选只显示既有太阳可见性结果，在非 delta 接收面终止；镜面的几何 continuation 保留，不新增阴影 trace。它关闭 aerial、ReSTIR 并用无效材质标记避免 NRD 改写原始结果。baseline 仍原样，不在游戏中启用此诊断。

105 个审计 SPIR-V 变体通过编译及独立 `spirv-val --target-env vulkan1.2` 检查；ReSTIR integrated variants、shader contract、PBR contract、lighting logic、AS synchronization 和 JAR 构建通过。旧 shader contract 的 payload normal 写入字符串已更新为实际几何朝向调用，仍核验 dispersion 写入。

修复 JAR 已替换到 RTest 实例，SHA-256 为 `9357621089c277a463bcc3854031af11501e4073c4b35498c821b200ffeea2da`。需要重启加载；尚未取得本次法线解码/朝向修复后的原场景图像，也没有把最初 GPU 重置归因于这些法线问题。LabPBR F0/porosity 的独立格式偏差仍未混入这次阴影修复。
