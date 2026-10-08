# 非 PBR 发光强度（2026-10-08）

在 F9 → 光照增加“非 PBR 发光强度”，复用已有 emissionScale 配置键，范围 0–256，默认 25，并支持统一的单项恢复默认值按钮。现有配置值不变。

原版方块光源与发光粒子原本已使用该倍率。补齐熔岩与物品模型四边形的基础发光：按当前倍率 / 配置默认倍率缩放，默认倍率保留原有亮度，0 关闭基础发光。专用发光实体渲染层取消最小 1.5 的亮度下限，直接使用倍率，默认 25 同样不变。

关闭 F9 时比较打开界面时的倍率；只有值确实变化时才通过已有 markSceneDirty 路径请求一次完整地形材质重新采集。拖动滑条不反复请求完整采集，未修改或恢复原值时不会请求。原版发光方块与流体采用 CPU 材质采集，后续发布沿用现有几何事务和同步逻辑。

PBR 贴图发光强度保留独立配置。原版灯源即使附有 PBR 发光遮罩，其既有照明计算仍使用基础辐射标定；提示文字明确此点。GPU shader、uniform ABI、提交与资源生命周期均未修改。

验证：settingsLayoutTest、lightingLogicRepairTest、fluidContractTest、rayTracingPbrMaterialsTest、jar 均通过。PBR 测试中的旧界面字符串断言更新为统一 number 控件调用，保持 0–20 范围检查。测试覆盖界面布局、数值显示、流体材质和 PBR 上传契约，不等于游戏内视觉确认。

实例 JAR SHA256：`fb61e0f24bfa386ca6ebbc5dd26d1e662e5326c4732d1c96e7b814c412a8e04c`。

上一版备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261008-184240-nonpbr-emission/rtest-0.1.0.jar`。
