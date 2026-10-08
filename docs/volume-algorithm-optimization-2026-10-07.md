# 体积与太阳采样算法优化（2026-10-07）

## 本轮实现

### 连续高度的区间复用

大气积分的每个采样点都需要在排序的高度 LUT 中确定插值区间。原来每点从完整表二分查找，再读取两个端点。现在将前一个点的两个索引作为 hint：

- 先读取原区间两端；高度仍在区间内时直接插值。
- 移入相邻区间时再读一个端点。
- 仍不能覆盖时，回退原完整二分查找。

高度恰好等于上端点时进入下一档，最低/最高表边界保持原 clamp 规则。初始 hint 为索引 0/1，仅是查找起点；算法不假设积分高度单调，支持下降、上升、地面夹紧和大幅跳变。hint 仅存在于当前像素的积分循环内，无跨像素/跨帧缓存或新 buffer。

稳定区间通常只需 2 次高度表读取，邻区间 3 次；原来是 O(log N) 次探测加 2 个端点。大跳变时增加快速探测开销再回退，不能保证所有路径更快。插值表达式不重排，保留原积分步数、位置及 LUT 数据。

### 先确认局部体积光有效，再计算介质

局部体积光原来在选灯前计算采样高度、介质和 4 点 view transmittance，即使 light PDF 为零或灯的实际发光为零。现在将这些纯函数计算移入 PDF>0 且发光有正分量的分支。

有效样本仍执行相同计算、可见性、谱传输与动态差值。选灯距离与发光体随机域保持原样；没有取消有效样本或缩减积分点。收益取决于无效或零发光候选比例，若所有样本有效，可能没有收益。

### 太阳无贡献样本提前结束

当 `directCosine == 0` 且 `foliageResponse == 0`，普通 BRDF 和植被校正的直接贡献均为零，原来的 needsSunShadow 条件也不会追踪。这时提前 continue，省去大气太阳透射查询、BSDF/PDF/MIS 及后续零贡献运算。

这不是余弦阈值近似，不截断微小的正贡献；背面植被若 foliageResponse 非零仍继续。太阳采样数、MIS 的样本数、随机序列和 BSDF 续追保持。有限且有效的原输入下贡献一致；不以这项改动宣称减少正常路径的射线数量。

## 验证

`AtmosphereHeightCoherenceTest` 使用排序数组的独立 interval oracle 对比连续 hint 算法，覆盖 2/3/8/64/256 项非均匀表、502997 个输入、表节点及相邻 float、方向逆转、小移动、跨表跳变和表外高度；区间索引和 float 插值结果一致。测试同时检查真实 shader 的 hint 更新、回退和局部体积光延迟计算接线。

验证命令：

```bash
./gradlew atmosphereHeightCoherenceTest rayTracingCostAuditTest rayTracingShaderContractTest restirConditionalMathTest jar --offline --console=plain
```

shader 检查覆盖普通路径、ReSTIR、两个大气模式和所有审计路径，共 98 个 SPIR-V 模块；另外逐模块使用 `spirv-val --target-env vulkan1.2`。

本轮没有减少画质设置或有效光照样本，也未更改 BLAS 批处理逻辑。CPU oracle 和编译检查不能替代 GPU 画面/计时验证。大气高度复用可能增加寄存器或分支压力；收益需在相同场景、频率和分辨率下实测，不从表读取次数推导毫秒。原源码保存在 `tmp/volume-optimization-before/`。

全部上述测试通过，98 个最终 SPIR-V 模块通过独立验证。已安装实例 JAR，SHA256 `c74f9340c5b2e57fd281d96b70899559443932229ffc1caeed7c6e8cf15a0990`；旧版本备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261007-223355-volume-algorithm/rtest-0.1.0.jar`。游戏于 22:29:48 退出，参考采样未取得数据；已停止采样并恢复 baseline。需重启加载后进行运行验证。
