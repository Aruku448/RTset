# 根据 Ray Tracing Gems II 第 39 章优化天空采样

后续已接入真正的硬件 CDF 反演，见 `sky-cdf-hardware-paper-39-2026-10-04.md`。
下文记录初始 alias 方案；当前 alias 与硬件路径共享量化 CDF 的概率数据。

来源：用户提供的 `978-1-4842-7185-8_39.pdf`，Nate Morrical 与 Stefan Zellmann，
*Inverse Transform Sampling Using Ray Tracing Hardware*，2021，印刷页 625–641。
DOI: https://doi.org/10.1007/978-1-4842-7185-8_39 。

## 论文与当前实现的对应关系

论文第 39.2 节按纹理亮度建立概率分布，以匹配的 PDF 修正蒙特卡洛估计。
第 39.4–39.5 节把边缘与条件 CDF 编码成概率带状几何，用一次硬件遍历完成反演，
通过简化几何减少随机访存。第 39.6 节说明额外 RT 遍历有开销，某些贴图未获得渲染加速。
论文的采样速度与渲染速度提升不能直接当成当前 mod 的性能结果。

当前 mod 没有纹理 CDF 二分搜索：天空 NEE 原来采用余弦半球采样。
因此本次实现论文的亮度重要性采样原理，使用常数时间 alias 查表；
没有移植其 CDF ribbon BLAS、简化算法或 OptiX 示例代码。
论文的硬件 CDF 搜索仍是未来需要独立 GPU A/B 验证的候选方案。

## 实现

- 在天空盒加载时，把全部像素的线性 sRGB 亮度乘立体角 Jacobian 累计到每面 32×32 格。
  所有像素参与，避免只采格子中心而漏掉小高亮区域。
- 构建六个主方向的 `亮度 × max(Naxis·方向, 0)` 分布。
  初始不考虑表面朝向的分布在当前平缓天空盒上反而增加方差，故没有采用。
- 每个表面选最接近法线的主方向，用余弦采样与该分布混合。
  旧版纹理天空的混合比例为 50%；物理天空路径为 `50% × 天空盒透明度`。
  纯物理天空或禁用天空纹理时保持余弦采样，并跳过查表。
- alias 分支的随机数余量重新映射为格内水平 jitter，另一维用于垂直 jitter。
  不增加 Sobol 维度，不增加天空可见性射线。
- 返回实际分布的立体角 PDF：
  `pOmega = cellPMF × SIDE²/4 × (1+u²+v²)^(3/2)`。
  分布的近似只影响方差；能量估计使用真实混合 PDF。
- 天空 NEE 使用 `cosine/pdf`，BSDF 后续命中天空使用相同混合 PDF 的 MIS。
  RGB 透明阴影与 NRD AOV 分类保持原有路径。
- 六张表共 589824 字节（576 KiB），新增只读 SSBO binding 38。
  天空盒拥有缓冲，资源销毁时释放；物理与旧版 Vulkan 描述符布局均绑定该缓冲。
- 新配置 `skyImportanceSamplingEnabled=true`；设为 false 可比较原余弦路径。
  切换会参与已有的历史失效判定。没有改动实例现有光照、NRD 或大气参数。

## 验证

命令：

```sh
./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest vulkanResourceLifecycleTest jar --offline
python3 scripts/check_water_transport.py
python3 scripts/benchmark_sky_importance.py
```

- 实际序列化表的归一化、alias 生成概率与查询 PDF 匹配：通过。
- 零亮度、单高亮格、强动态范围及六方向白色天空测度：通过。
- 常量天空球积分：12.566735，参考 4π 为 12.566371。
- 强高亮离散夹具的方差检查通过；该夹具不代表当前游戏噪声收益。
- 物理与旧版实际 raygen 编译、现有 shader/资源生命周期检查：通过。
- 水吸收与空气门控回归：通过。

当前打包天空盒的近似参考积分保存在
`docs/profiling/2026-10-04-sky-importance-reference.json`。
六个主方向上，新方差/旧方差约为 0.192–0.313。
条件为 128×128 每面评估、32×32 提议、50% 混合、无遮挡漫反射；
诊断先缩小 PNG，再解码亮度，数值不是逐像素精确积分。
它不包含动态遮挡、法线贴图、镜面 BRDF、物理天空混合、NRD 或 GPU 时间。

## 边界与后续测量

本次主要改善静态 PNG 天空的样本分配；动态太阳/月光 LUT 尚未建立自己的分布。
不保证任意方向、遮挡场景、镜面材质都降低方差。
需要重启游戏后，在固定相机、时间、分辨率与 NRD 配置下对比开关，
记录 RT GPU 时间及稳定后同帧数噪声，才能确认端到端性能收益。
