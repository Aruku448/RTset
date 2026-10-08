# Vulkan 辐射缓存算法实验

## 状态

已实现并在 RX 7800 XT / RADV 上实际运行 GPU 更新与查询。**这是独立算法实验，没有接入 Minecraft 正式渲染、没有部署 mod，也没有新增游戏内开关。** 源码和 shader 放在 experimental 目录，正式 raygen 没有调用或绑定它，因此当前游戏没有缓存加速效果，也没有新增每帧成本。

参考 AMD RC 的“完整路径提供目标，缓存估计剩余路径”思路，但实现采用空间哈希均值，不使用 AMD DLL，也不是 AMD 神经网络或其性能的复现。[官方技术说明](https://gpuopen.com/manuals/fsr_sdk/techniques/radiance-cache/)

## 实验实现

- CPU 路径尾部参考：`B_i = L_i + W_i * B_(i+1)`，从最后一个顶点逆向求解。目标必须是匹配的漫反射贡献，不能输入整条已乘相机吞吐量的像素或完整 PBR 总照明。
- 存储漫反射入射辐照度 `E`，查询重建 `beta * albedo * E / pi`。因子化只适用于该实验的 Lambertian 合同；不把一般 GGX、菲涅耳、草木透射直接除反照率。
- 空间键包含稳定锚点下的位置格、归一化法线量化值、材质身份。负坐标使用 floor；锚点、世界、材质和光照有效性由调用者管理 generation。
- 四个 compute 阶段：清空、槽位所有者选举、累计、查询。选举采用 atomicMin 选出最小合法输入索引；后续阶段读取完整的不可变输入键，避免同一 dispatch 发布半写入键的竞态。阶段之间有显式 shader write/read 依赖。
- 哈希碰撞逐字段比对完整键，异键丢弃并回退，不误用其他表面的照明。没有线性探测或历史保留，本轮每次更新从零开始。
- 仅接受 flags=7，即调用者确认的纯漫反射、静态、非透明目标。generation 不匹配、样本不足、异键、特殊材质和异常值返回 valid=0，由未来渲染调用者完整追踪。
- 用一阶及二阶矩判断样本异质性，包含定点量化误差上界。该条件不是统计置信区间，也不能证明几何无漏光。黑色估计允许 valid=1，不以亮度判断信号是否存在。
- 仅使用 32 位整数原子操作，不要求 WMMA 或浮点原子扩展。固定点一阶 Q1024、二阶 Q16；目标每通道限制到 [0,64]，**超出则拒绝，不能截断后参加均值**。每批最多 32768 条，单槽最坏和为 2^31，仍在 uint32 内；C/Java 输入侧都验证上限。

## 实测

GPU 内存实际 flags=0x7（DEVICE_LOCAL/HOST_VISIBLE/HOST_COHERENT）；5 次预热、31 次 GPU timestamp 采样。结果不是 Minecraft 实测，也未包含真实路径目标生成、查询记录生成和 NRD 合成成本。

| 数据集 | 回读验证 | GPU 总时间中位数 |
| --- | --- | --- |
| 13 个合同查询，177 条记录；黑值、碰撞、代次、动态/特殊材质、异常数及方差回退 | 4132 words，0 mismatches；3 hit / 10 fallback | 0.009520 ms |
| 32768 条最大辐照度记录集中单槽，验证累加最坏上限 | 524340 words，0 mismatches | 0.107880 ms |
| 解析环境，1024 表面格 × 16 个独立训练样本 | 806928 words，0 mismatches；1021 hit | 0.019880 ms |
| 65536 槽、16384 条更新、1824438 次合成查询 | 37275208 words，0 mismatches；1362984 hit（74.71%） | 0.660480 ms |

大负载分阶段中位数：清空 0.005760 ms，选举 0.004800 ms，累计 0.008800 ms，查询 0.641760 ms。各阶段中位数不要求相加等于全程中位数。缓存本身 2 MiB；实验 ABI 中查询输入 64 bytes、结果 16 bytes，每批完整单 SSBO 约 142.2 MiB，包含合成输入和回读输出，并非未来生产实现所需的最小显存。

解析测试采用 `L(w)=a+b*cos(theta)`，解析 `E=pi*(a+2b/3)`，独立 cosine-hemisphere 采样生成训练与留出估计。R 通道输出 RMSE：单样本 0.011664517；缓存 16 样本 0.002938363，比例 0.251906。**这是共享样本的噪声降低测试，并非用相同采样成本获得 4 倍收益，也不验证遮挡边界的空间偏差。** 合成命中率由输入分布决定，不能外推到真实世界。

CPU 验证包括 1000 条随机路径：逆向尾部结果与显式逐段吞吐量积分比较；Lambertian 因子化重建；负坐标格、归一化法线、输入边界和拒绝条件。GPU 各数据集所有整数词逐字相同，RGB 容许浮点舍入，观察最大绝对差不超过 9.54e-7。

`./gradlew check jar --offline` 通过，61 个任务；正式 raygen/NRD/Vulkan 生命周期等原有回归也通过。

## 复现

从仓库执行：

```bash
bash tools/run_radiance_cache_experiment.sh
```

需要已有项目 Gradle 缓存、C 编译器、Vulkan 开发库、带 compute/timestamp 能力的 Vulkan 设备。数据、SPIR-V、二进制和日志写入忽略的 `build/radiance-cache-experiment`。该脚本不启动游戏或替换 mod。

## 接入正式渲染之前必须解决的事项

1. 真实 raygen 输出独立漫反射尾部目标，处理 NEE/MIS/发光的所有权，保留足够完整训练路径，避免把已经计入的直接光再加一次。该实验目前没有采集游戏路径。
2. 使用两套资源或明确 dispatch 依赖，禁止同帧对查询中的缓存原位更新。generation 要反映真实区块、材质、环境变化；移动 geometry origin 不能静默改变空间键。
3. 静态表面也会被实体遮挡，所以不能仅检查 receiver 是静态就设 flags=7。必须保证训练目标不含实体阴影；对实体可能影响的路径完整追踪，或证明独立动态修正完整后才缓存。
4. 保留首跳 final gather、直接光与 RGB 透明阴影；仅在明确可用的漫反射间接光范围截断尾部。镜面、玻璃、水、体积和草木特殊散射暂时回退。
5. 缓存贡献进入对应 NRD diffuse AOV，保证一次反照率处理和正确距离指南。缓存不替代 NRD，也不对实体阴影进行历史平滑。
6. 固定真实场景测量“省下的射线”是否超过全部新增成本，并检查室内漏光、接触细节、实体移动、传送和环境变化。近邻共享和方差拒绝可能引入偏差，不能仅靠当前回读测试判定画质可接受。

## 文件

- `src/main/java/com/rtest/client/experimental/RadianceCacheExperiment.java`：输入合同、尾部数学与 CPU oracle。
- `src/main/resources/rtest/shaders/experimental/radiance_cache.comp`：实际 Vulkan compute 算法。
- `src/test/java/com/rtest/client/RadianceCacheExperimentTest.java`：解析测试和数据生成。
- `tools/radiance_cache_smoke.c`：GPU 调度、计时和回读。
- `tools/run_radiance_cache_experiment.sh`：完整复现入口。
- `docs/profiling/2026-10-05-radiance-cache-experiment.json`：本轮数值记录。
