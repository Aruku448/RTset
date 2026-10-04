# 水下橙红修正

截图报告：水下整体橙红，伴随密集噪点。

## 已复现的原因

水材质和相机初始介质都使用 RGB 吸收 `(0.015, 0.045, 0.09)`。
按 `T = exp(-sigma * distance)`，20 格后 RGB 透射为
`(0.740818, 0.406570, 0.165299)`：蓝光衰减最快，长路径必然偏红。

此外，相机在水中时仍执行空气有限段 LUT 和空气局部体积光积分。
该路径没有划分水段与空气段，不能正确表示水下介质。

## 修正

- 同步 CPU 水材质及 GPU 相机水介质吸收为 `(0.09, 0.045, 0.015)`。
  这是现有风格化系数的通道纠正，不是测量所得的完整水光谱模型。
- 水下相机跳过空气体积合成，保留路径原有 Beer 吸收、折射及表面照明。
- 保留用户当前配置，包括 NRD 开启状态。

## 验证与限制

`python3 scripts/check_water_transport.py` 修改前失败，修改后通过：
CPU/GPU 系数一致，1、5、20、100 格均为 `Tred < Tgreen < Tblue`，且空气积分有水下门控。

`./gradlew rayTracingShaderContractTest rayTracingAtmosphereShaderTest rayTracingPbrMaterialsTest jar --offline`
通过；物理及旧版大气 raygen 均实际编译通过。

没有进行 GPU 截图验证，不能据此宣称所有噪点已经消除。
独立水内散射及出水后空气分段积分尚未实现，当前门控是保守修正。
