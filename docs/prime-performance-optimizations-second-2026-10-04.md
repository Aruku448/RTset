# 第二批RT性能优化（2026-10-04）

## 改动与理由

延续Prime的查询切面/短状态消费原则，本轮针对RayTracingShaderStages和物理大气积分：

1. Primary any-hit先读取alpha-test标志，非cutout直接接受，不进行UV重建、光学记录读取或纹理采样。Closest-hit仍完整执行PBR与透明传输，any-hit从来不负责这些着色。
2. Shadow any-hit读取coverage和optical分类，非cutout且非transmissive的命中直接接受；动态实例仍设置shadowDynamicOccluder。Shadow closest-hit照旧将RGB T清零。避开该类命中的tint/UV读取及纹理采样，不改变不透明遮挡。草叶cutout仍按mip0点采样做alpha-test，彩色玻璃/水仍执行RGB透射与ignoreIntersection。
3. 主相机大气段的phase-coordinate只依赖本段固定的nu，移到积分循环之外，太阳一次、月光有效时一次。保留原映射公式，不查近似表。
4. 同一积分点太阳和月光的multiple-scattering高度区间与插值权重共用一次查找；各自太阳高度映射、亮度、方向形状仍分别计算。
5. 四波长介质、积分权重、太阳/月光光谱源在局部scope内全部解析后才执行RGB visibility ray。该临时介质记录不再有查询后的消费者。T更新放在两源光谱贡献计算之后、visibility之前；各贡献仍消费原来的段前T，没有提前衰减或二次衰减。

采样点数、GI段数、软阴影数量、alpha阈值、NRD/FSR、RGB透明阴影和大气模型参数保持原设置。没有迁移标量shadow cache，也没有降低分辨率。

## 证据与限制

`./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest rayTracingPbrMaterialsTest jar --offline`通过，编译活动RT阶段、physical/legacy实际raygen，新增opaque快速路径/dynamic标记、共享大气准备与两来源消费顺序检查。

这是源码级消除无用读取和重复求值；驱动可能已消除部分旧工作，短scope也不直接证明allocated registers下降。没有完成新旧同场景GPU像素等价或性能A/B，不声明提速百分比。

当前客户端已运行。替换前最近1795×983样本RT约20ms、后处理约0.57ms；记录于`docs/profiling/2026-10-04-before-prime-opt2-timing.json`。它和此前2560×1404的样本分辨率不同，不能当作第一批改动的收益证明。该基线没有固定相机/天气保证，也不能直接与之后移动场景的日志作因果比较。

## 部署

已原子替换`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`，未修改配置或关闭客户端。当前运行实例不会加载替换后的Java代码，需重启后生效。

SHA256：`7deb187d50aa1cbc5d9c8f713d5d49470a9462672fce60023d2a1fa41cb5b46a`。
备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-prime-opt2-20261004-182311`。
