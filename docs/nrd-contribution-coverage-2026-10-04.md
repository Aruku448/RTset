# NRD 贡献覆盖检查

日期：2026-10-04。只读检查运行源码、实例配置和日志；没有更改配置、代码或部署。

## 当前实际调度

实例 `rtest-client.toml:30` 为 `nrdEnabled=false`。最新日志中 18:51:53 的 `denoiser scheduling` 为 `NRD=false, strength=1.0, guides=false`。NRD 的 native library / pipelines 加载成功不代表执行降噪。目前所有贡献都没有 NRD 处理。

## 开启 NRD 后的输入归属

| 贡献 | 输入 | 证据与限制 |
|---|---|---|
| 主表面太阳、月光漫反射与软阴影 | diffuse | Raygen `directDiffuseRadiance` → `filteredDiffuseRadiance` → `nrdNoisyDiffuse` |
| 主表面太阳、月光镜面 | specular | `directSpecularRadiance` → `nrdNoisySpecular` |
| 天空/天空盒/物理天空对表面的 NEE | diffuse + specular | `skyDirectDiffuseContribution`、`skyDirectSpecularContribution` 分别加入对应通道 |
| 主表面面积灯/光树 | diffuse + specular | `areaDirectDiffuseRadiance`、`areaDirectSpecularRadiance` 并入最终输入，没有额外未滤波灯层 |
| 草木受光艺术修正 | diffuse | 修正加入 `sunDiffuseContribution`，随太阳 diffuse 输入处理 |
| 多跳 GI、反射看到的天空/日月/发光/灯光 | diffuse + specular | 按第一表面的 `primaryDiffuseShare` 划分，包含完整 throughput、RR 与介质衰减 |
| 主表面透射后的路径贡献与色散 | specular | `transmissionRadiance` 并入 specular；透明材质保留有效 receiver，preparation 不做 opaque material-factor 除法 |
| 直接可见的发光材质 | emission 未滤波 | 只将 bounce=0 的发光加入 `emissionRadiance`，composite 原样加回 |
| 直接可见的天空、天空盒、太阳/月球圆盘 | 未滤波背景 | primary miss 的 viewZ=65504，composite 返回原始像素 |
| 局部灯/天空的相机段体积贡献 | diffuse，但有条件 | `volumeEmitterInscatter` 加入 `indirectDiffuseRadiance`；primary miss 或 diffuse hit distance 无效时没有可靠历史覆盖 |
| 太阳/月光相机段散射 L、天空遮挡残差 | 专用 aerial AOV，未滤波 | `physicalAerialL` 在 NRD record/composite 后、FSR 前合成 |
| 旧 RGB 大气散射 | emission 未滤波 | legacy `atmosphereInscatter` 加入 emission |
| 大气透射率 T、玻璃吸收、纹理色/法线/粗糙度 | 调制/guide，不是独立去噪信号 | T 先乘表面输入；吸收在 throughput；纹理/材质 guide 用于重建。NRD 不单独处理这些本身的混叠 |
| 调试视图 | 未滤波诊断路径 | 清空实际 filtered contributions 并携带诊断结果 |

## 接入后的回退条件

`src/main/resources/prime/shaders/nrd_composite_simple.comp` 的实际合成条件：

1. primary miss、超出 `denoisingRange`、无效材质：返回完整 rawValue。
2. `directDiffuse.a > 0.5`：动态阴影标记导致整个像素返回 rawValue，不是只跳过动态阴影这一项。标记来源包括太阳、月光、天空、面积灯和局部体积的动态遮挡。
3. diffuse/specular 的 normalized hit distance ≤0：该 lobe 使用 prepared noisy 信号，即当前帧，不消费 denoised 输出。
4. strength<1：rawSurface 与 filtered surface 混合，因此保留一部分原始噪声。

Raygen 的两种 hit distance 初始为 0，主要在 bounce=1 命中/环境 miss 时按有效 primary lobe 更新。新增的严格零 throughput 早退保留已有直接 NEE，但不制造 hit distance；因此可能出现“直接 NEE 有贡献，延续无效，此 lobe 未去噪”。这与当前 composite 的保守 fallback 一致，不能把有值写入 SSBO/image 当成完全覆盖。

局部体积贡献绑定表面 diffuse guides，并不是专门的体积降噪。纯金属/透射 receiver 或无有效 diffuse continuation 时，diffuse hit distance 可能仍为 0，局部体积即使非零也可能不使用降噪输出。对相机移动和深度边缘，它的体积位置也不等于 receiver 位置，接入不证明重投影准确。

## 源码入口

- `RayTracingShaderRaygen.java:1593`：环境 miss 按 primary transmission / lobe 划分。
- `RayTracingShaderRaygen.java:1816`：面积灯 primary AOV。
- `RayTracingShaderRaygen.java:1912`：可见发光与表面光照划分。
- `RayTracingShaderRaygen.java:2243`：physical aerial AOV。
- `RayTracingShaderRaygen.java:2301`：局部体积加入 diffuse。
- `RayTracingShaderRaygen.java:2414`：最终 diffuse/specular 输入总和。
- `nrd_motion.comp:75`：无效 hit distance 转 0；`:164`：透明材质 demodulation 分支。
- `nrd_composite_simple.comp:41`：背景、范围、动态阴影与 lobe fallback。
- `RtestFsr3.java:341`：NRD → aerial → FSR 顺序。

## 结论

没有发现主要表面灯光漏加至 NRD 输入，或被另加一份未滤波直接光覆盖的问题。主要覆盖缺口是当前 NRD 关闭、动态阴影全像素回退、无效 hit distance 的 lobe 回退、直接背景/发光，以及太阳/月光大气 AOV。只开启 NRD 不能消除这些所有噪声；大气抽样需要独立的体积时间重建，不能简单塞进 surface diffuse 历史。
