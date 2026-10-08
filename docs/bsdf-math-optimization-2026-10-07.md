# BSDF 数学计算优化（2026-10-07）

## 变更

在 `RayTracingShaderRaygen.java` 中对 Schlick/Burley/介质 Fresnel 的固定五次幂使用 `x² * x² * x`，平方使用直接乘法。输入仍沿用原来的 clamp/余弦范围，未更改随机序列、采样概率、MIS、能量补偿表或追踪预算。

GGX BRDF 与可见法线 PDF 显式共享 `D(n·h)` 和 `G1(n·wi)`。固定参考 alpha=0.64 的二维能量拟合，按 x 次数收集为 `A + B*x + C*x²`，A/B/C 在 shader 编译时常量折叠，避免参考分支运行完整二维式。不增加 LUT、buffer、跨帧缓存或数据搬运。

数学上是同一表达式，浮点重排有微小误差，并非逐位恒等。`tools/profiling/check_bsdf_math.py` 读取 shader 中实际拟合系数，与原二维形式做 100001 点 float 模拟比较：拟合最大绝对误差 2.9802322387695312e-8；五次幂展开相对 double 参考的最大绝对误差 1.058047495172687e-7。实际 GPU 的 FMA 和 Pow 降低策略可能不同，需游戏运行验证。

## 编译证据与限制

普通 mode=0、atmosphere=true 的性能优化 SPIR-V：

| 指标 | 之前 | 之后 |
|---|---:|---:|
| Pow 调用点 | 68 | 57 |
| Sqrt 调用点 | 100 | 100 |
| TraceRay 调用点 | 18 | 18 |
| 静态指令数 | 18999 | 19007 |
| 字节数 | 340476 | 340552 |

幂函数调用点减少 11 个，乘法展开使总静态指令略增；不能从指令数推导毫秒或帧率。GGX 公共子表达式已有编译器优化，没有观测到额外 Sqrt 减少。这次未减少实际射线数，之前审计中主要的可见性、体积与 GI 成本仍存在。

原 shader 源码与普通路径 SPIR-V 保存在 `tmp/math-optimization-before/`。数值证据与编译证据分别位于 `docs/profiling/2026-10-07-bsdf-math-numerical.json`、`docs/profiling/2026-10-07-bsdf-math-spv.json`。

## 验证

验证命令：

```bash
python3 tools/profiling/check_bsdf_math.py
./gradlew rayTracingCostAuditTest rayTracingShaderContractTest restirConditionalMathTest dynamicUploadBatchTest jar --offline --console=plain
```

覆盖普通/ReSTIR shader 合约、条件重采样数学、上轮 BLAS 分区与差分写入回归，以及 14 消融路径 × 3 ReSTIR 请求模式 × 2 大气模式的 raygen 和 14 closest-hit，共 98 个 shader。生成模块另外使用 `spirv-val --target-env vulkan1.2` 检查。

本次没有固定场景的旧版/新版 GPU 对照，不宣称已获得特定性能提升。实例需要重启加载新 JAR，之后再验证画面和计时。

全部上述检查已通过。旧 render math 测试的源码 wiring 断言已更新为检查共享 D/G1 变量和同一 PDF 表达式；其独立数值积分参考保持原样。

已安装实例 JAR，SHA256 `b69c94f271f4ad16e7f436ce527b266eb78da0b7ba49df7170117b990b2f2c70`。上一个可正常运行的批处理版备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261007-222738-bsdf-math/rtest-0.1.0.jar`。游戏目前已退出，需要启动后加载。
