# 渲染数学修正与加速实现

日期：2026-10-04。对应 [数学审计](rt-math-acceleration-audit-2026-10-04.md) 中优先实现的表面输运、POM 与体积遮挡方案。

## 已实现

1. **平滑介电界面全反射**：refract 失败时镜面权重为 1，避免继续乘 Schlick 导致能量丢失。粗糙透射、嵌套介质与完整 eta² 输运仍是已有模型限制。
2. **太阳 MIS 一致性**：太阳 NEE 使用实际延续路径的 diffuse/specular 混合概率，保留 N 个太阳采样的权重约定、delta 分布规则和 RGB 阴影。
3. **零贡献路径终止**：scatter 后全通道 throughput 严格为零即退出表面路径；先保留 primaryTransmissionPath，已有 radiance、primary guides、独立相机大气积分继续有效。没有使用亮度阈值截断。
4. **POM 化简**：四邻居共享左上角包裹坐标，双线性高度采样取模从 8 次降到 2 次；动画 frame 的整数运算每 hit 准备一次，保留原浮点 UV 运算顺序。
5. **平坦高度静态判定**：加载时扫描全部 normal companion 像素，包括动画的所有帧。只有每个 alpha 都为 0/255 才可跳过 POM。标记放在 normal width 元数据的高位，全部 shader width 消费点屏蔽该位；元数据仍为 10 words，初始与动态上传使用同一标记。
6. **太阳／天空代数化简**：太阳 Li/pdf 的共同 solid angle 相消；天空正 cosine 支持内 cos/pdf=π。没有删除 MIS PDF。小太阳方向采样用稳定 delta，圆盘命中用单位方向的弦长平方，避免小角度 cosine 消减。
7. **GGX 可见法线采样**：用视线条件下的 Heitz VNDF 替代 NDF；全局 BSDF evaluator 同步使用 `D(h)G1(wi)/(4 n·wi)` 的反射方向 PDF。太阳、月光、天空和延续路径共用评价器。BRDF 和方向能量表不变，随机方向和噪声分布改变；不保证固定样本数每帧更快。
8. **体积遮挡控制变量**：保留原 4/8/16 步介质、T、源透射和多重散射积分。将步索引分成 K 个整数区间，每区间均匀抽一个索引，遮挡残差乘区间长度。每个非零贡献都有支持，RGB 有限步估计在合成截断前保持期望。太阳与月光共用索引，各自仍追踪 RGB 透明阴影。新 Sobol effect=11，固定 camera-volume vertex/path=0，不受表面退出 bounce 影响。

## 体积对照设置

`rtest-client.toml` 新增 `volumetricShadowSamples`，默认 **2**，范围 0–16：

| 设置 | 行为 |
|---|---|
| 0 | 对所有积分步查询阴影，恢复完整遮挡对照 |
| 1–16 | 最多 K 个位置分别查询太阳／月光；K≥步数时查询全部 |

质量 2 的积分仍为 8 步；默认 K=2 时每个有效天体最多 2 次查询，而非 8 次。这是遮挡射线数量减少 75%，不是整帧耗时减少 75%。零源、地面遮挡或关闭阴影时实际查询更少。

无遮挡的残差恒为零；有遮挡时会增加噪声，强光柱可能出现单帧负的有限 L 估计。Aerial AOV 与 composite 保留其符号，避免在加入场景颜色前截零；最终场景仍遵循已有 RGBA16F 范围截断。该非线性截断与后续重建不保证保持蒙特卡洛期望。相机体积继续通过专用 post-NRD AOV 合成，不借用不匹配的表面 NRD 历史。若画面闪烁，应提高 K 或设为 0 做对照。

光学深度 LUT 端点差、解析球面分段积分、窗口条带与 POM 连续 DDA 不属于此次实施：它们在审计中属于改变近似或另需算法验证的后续候选。

## 验证

已通过：

```
./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest rayTracingPbrMaterialsTest jar --offline
glslangValidator -V --target-env vulkan1.2 \
  src/main/resources/prime/shaders/aerial_perspective_composite.comp \
  -o src/main/resources/prime/shaders/aerial_perspective_composite.comp.spv
```

- shaderc 编译实际活动 RT stages，以及 physical/legacy 两种实际 raygen。
- `RayTracingRenderMathTest` 穷举非幂次尺寸/负坐标邻居包裹；检查最后动画帧单像素 relief 不被误判为 flat。
- RGB 遮挡残差对 4/8/16 步和全部 K 枚举数学期望，覆盖不等长区间与独立 RGB 透射。
- VNDF 200,000 样本估计与独立半球数值积分比较，覆盖 roughness=0.4/0.8、view cosine=0.1/0.5/1；同时检查 sampler/PDF 的源代码连接。
- 保留 TIR、MIS、POM、月光、天空残差、NRD 信号等已有契约。源字符串契约随公式改写更新，没有取消其行为要求。

这些是编译、CPU 数值参考与连接检查，不是 GPU 像素一致性或性能测量。当前客户端仍运行旧 mod，尚无新版本同场景 FPS、HDR 误差或时间稳定性数据。

## 部署

- 已原子替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`。
- 旧版本备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-math-opt-20261004-184739`。
- 新 JAR SHA-256：`688bb2bb6cb3aac43486ddc4614e776c361e06036fe1e7ef76d2fe1a3f004a20`。
- 已核对部署 JAR 与构建产物哈希一致，并包含新的配置字段和重编译 aerial composite SPIR-V。
- 没有修改正在运行客户端的配置文件。重启后新配置字段按默认值补入；需要重启客户端才会加载新实现。
