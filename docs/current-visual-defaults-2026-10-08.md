# 当前画面参数设为默认（2026-10-08）

来源：RTest 实例 rtest-client.toml 与 rtest-restir-client.toml 的实际字段值，不使用旧的 #Default 注释。116 项非诊断参数完整快照见 [default-settings-2026-10-08.json](default-settings-2026-10-08.json)，记录包含采集时间。debugView 保持 0，审计默认保持 baseline。

RayTracingClientConfig 与 PostProcessingSettings 的配置定义默认值已与快照逐项一致。保留精确浮点值，不对当前参数进行量化。现有实例配置文件未改写；F9 的恢复默认按钮和新生成配置将使用新的默认值。GI 枚举中的默认标记移到 4 层，AO 提示去除过期的默认关闭说明，基础发光提示去除旧默认 25。

注意：之前固定亮度的熔岩/物品按 emissionScale / getDefault() 调整。更新默认值会改变分母并使当前画面变暗，因此改用固定历史标定 25，保持默认值更新前后的亮度一致。该常量仅用于旧亮度标定，配置默认值是快照中的 148.62。

验证：全部 116 个代码默认值与快照相等，所有数值默认值位于允许范围内，实例配置仍与快照一致。settingsLayoutTest、rayTracingPbrMaterialsTest、lightingLogicRepairTest、fluidContractTest 与 jar 通过。打包的 GPU shader / SPIR-V / Vulkan 类与上一版本逐项一致。未进行新一轮游戏内视觉验证。

实例 JAR SHA256：`270ef8d56190b3a5b3a6b9cb4908ab60fd15f6b5d4b44170c39c9a91ef46c87f`。

上一版 JAR 与参数快照备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/20261008-190600-current-visual-defaults`。
