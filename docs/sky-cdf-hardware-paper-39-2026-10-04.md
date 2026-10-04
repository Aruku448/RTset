# 论文第 39 章：硬件 CDF 反演已接入

来源：Nate Morrical、Stefan Zellmann，*Inverse Transform Sampling Using Ray Tracing Hardware*，
Ray Tracing Gems II，第 39 章，2021，用户提供的 `978-1-4842-7185-8_39.pdf`。
本文实现其 39.4–39.5 节的概率带状几何与单次光线搜索，独立编写 Vulkan/GLSL 代码。
此前 alias 实现记录见 `sky-importance-paper-39-2026-10-04.md`；现在 alias 保留为比较路径。

## 当前运行路径

1. 加载天空盒，累计线性亮度乘立体角，生成六个表面主方向的采样分布。
2. 把每面 32×32 格视作分段常量提议，合并六面为 192 行、每行 32 列。
3. 对每个方向，计算行边缘 CDF 和行内条件 CDF，转换到 float。
4. 每格生成一个两三角形带状面：x 为列位置，y 为条件 CDF，z 为行边缘 CDF。
   概率几何的三个维度都归一化到 [0,1]。
5. 建立六个 BLAS 与一个独立 TLAS；每个方向使用独立 instance mask。
6. 从 `(-0.001, ξconditional, ξmarginal)` 沿 +x 发射一条射线。
   专用 closest-hit 返回格子 ID 和命中 x；x 恢复列内位置，z 恢复行内位置。
7. 条件 CDF 斜率乘行宽给出格子 PMF，再乘 cubemap UV 到立体角的 Jacobian 得到 PDF。
8. NEE 使用该 PDF 的混合密度，BSDF 天空命中也使用相同密度进行 MIS。

这是一次真正的 `traceRayEXT` 硬件遍历。没有要求新的 ray-query Vulkan 特性。
采样几何使用专用 miss / closest-hit shader，不访问场景材质或运行透明阴影逻辑。
SBT 顺序为 raygen、三个 miss、三个 hit，原场景 primary/shadow 仍是 hit 索引 0/1，
CDF 使用 hit 索引 2、miss 索引 2，独立 payload location 3。

采样中的浮点边界未命中用二分反演同一份量化 CDF 恢复。
一般路径只有一次硬件遍历；恢复路径有搜索。没有用别的提议分布掩盖未命中。

## 与 alias 的对比

量化后的 CDF 面积用于生成 alias 表，所以两条路径采用相同格子 PMF。
`skyImportanceSamplingEnabled=false` 为原余弦策略。
重要性采样启用时，`skyCdfHardwareEnabled=true` 为硬件 CDF，false 为 alias。
默认两开关均为 true；切换参与已有历史重置判定。
物理天空混合比例仍随 PNG 透明度变化；纯大气 LUT 保持余弦采样。
两条路径的随机数映射不同，因此像素噪声不必逐帧一致。

## 资源与边界

- 静态几何最多 73728 个三角形，包含零概率退化面；零面积面不会被正常射线命中。
- 六个顶点输入总计 2654208 字节，CDF bounds 589824 字节，alias 表 589824 字节。
- BLAS/TLAS 内存由驱动决定；加载日志输出 `static_resource_bytes` 与 `scratch_bytes`。
- 每次天空盒加载构建一次，不在每帧重建；scratch 和上传 encoder 在构建结束释放。
- owner 销毁顺序为 TLAS、instance、BLAS、顶点及 metadata。
  所有引用天空盒的渲染 pass 先销毁。构建异常也执行资源清理。
- 使用现有 scratch 地址对齐与 AS 输入范围校验，并序列化共享 scratch 的构建。
- 当前输入仍是既有 PNG cubemap，经线性解码后用于辐射亮度估计；本次未新增 HDR 文件加载器。
- 32×32 提议是有界近似，未移植论文按二阶导数挑选控制点的自适应 ribbon 简化及跨行合并。
  原纹理分辨率仍用于辐射亮度查询。提议分辨率影响方差，不替代纹理辐射值。

## 已完成验证

```sh
./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest vulkanResourceLifecycleTest jar --offline
python3 scripts/check_water_transport.py
git diff --check
```

- 专用 CDF miss/hit 与两套实际 raygen shader 编译通过。
- CPU 用实际上传的三角形执行射线相交，与 CDF 反演比较：通过。
  覆盖零亮度、单高亮格、强高亮夹具和六个方向。
- CDF 单调性、概率面积归一化、几何/alias PDF 一致性及命中 x 反演：通过。
- Vulkan 描述符、SBT 接线及生命周期契约：通过。
- 原水介质与空气门控回归：通过。

**未进行 GPU 渲染或帧时间实测。** CPU 几何测试不替代 GPU 驱动验证。
论文自身指出遍历开销可能让部分纹理更慢，所以不宣称这一实现必然快于 alias。
此前 systemd-oomd 内存终止的具体堆外占用尚未定位，本次没有把它标记为修复。

部署后需重启。在固定场景、相机、时间、分辨率与 NRD 设置下比较
`skyCdfHardwareEnabled` 的两种状态，确认采样图像与 GPU 时间，再决定实际使用哪条路径。
