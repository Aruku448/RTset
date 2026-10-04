# Prime 机制性能优化第一批（2026-10-04）

## 实施

参考 [3292bbf](https://github.com/bWFuanVzYWth/prime/commit/3292bbf81acd169e8390fc5a82a75b2121cd5a04) 的零源查询早退，以及 [8026f75](https://github.com/bWFuanVzYWth/prime/commit/8026f7554dfd034f5d3a156bb9e450f07cfe6af6) 的接收表面准备/窄状态原则。

修改 RayTracingShaderRaygen.java：

1. `prepareBsdfDirectionalEnergy` 在每个路径命中点准备两分量GGX视向能量，太阳（最多16样本）、月光、主表面天空、面积灯和BSDF续传均复用。采样概率也消费同一值，避免同一normal/view/roughness在每个光源重复查表与计算反射能量拟合。只保留vec2，不保存完整材质closure。调试探针的独立接收面保留自准备的重载入口。
2. 太阳先查询光源大气透射，精确零源跳过当前阴影采样；sunIntensity精确0时直接退出太阳循环。GI/太阳采样数量、Sobol域及PDF/MIS不变。
3. 局部emitter在visibility ray之前求sourceRadiance；精确RGB全零时跳过查询。后续复用该radiance，不再重新取纹理。
4. 物理大气积分的太阳/月光直射步贡献精确为零时不查对应RGB阴影；太阳强度0同样跳过太阳shadow。多重散射、介质消光、T、天空残差和月光保持原来源计算。

光源早退用精确全通道零判定，不引入亮度阈值、不删微弱光，不把带负通道的working RGB或signed sky correction丢弃。保留原alpha cutout、RGB玻璃透射、草木受光、采样预算、NRD/FSR资源和同步协议。早退会停止记录零贡献来源的动态遮挡标记；对应来源本身没有图像能量。

## 预期与限制

GGX能量拟合从每个光源/续传重复求值改为每命中点一次，减少确定的源码级重复工作。只对有效贡献为零的光源减少shadow数量。正常白天、非零源的8条太阳软阴影和体积积分步数仍保持，不能承诺这批改动消除体积路径的主要成本。

缓存两个值增加跨查询存活范围，驱动寄存器配置、CSE、spill和实际收益仍须GPU测量。现有测试不证明逐像素GPU等价或实际加速百分比。当前客户端已退出，未进行同场景性能A/B。

本轮没有移植大气epipolar/共享遮挡缓存、K1/K2多pass、alias光网格或BLAScompaction；这些需单独资源和图像验证，迁移条件见prime-dev-optimization-migration-2026-10-04.md。

## 验证

`./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest rayTracingPbrMaterialsTest jar --offline` 全部通过。

- 编译实际活动raygen、miss、closest-hit、any-hit与shadow shader。
- 编译physical LUT与legacy两个实际raygen变体。
- 检查能量准备、各光源消费与零源判定先于shadow的顺序。
- 保留月光normalized continuation PDF、草木材质布局、PBR与alpha/NRD契约。

没有新增运行时诊断日志或改变实例配置。

## 部署

已原子替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`。

SHA256：`500954b6aae028ab6266260c60599a8ed5376df4a95e2b0a06db4fdf457b83b2`。
备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-prime-opt-20261004`。

客户端下一次启动加载；之后固定场景、分辨率、时钟、天气和配置采集gpu_timing，与备份版对照。不得将离线累计与实时模式混为一组，也不得把CPU fence等待再加到GPU时间。
