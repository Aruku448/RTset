# PBR 照明黑色采样绕过 NRD 修复

## 证据

截图时段日志记录 NRD=true、strength=1、guides=true。面积灯/PBR 自发光的直接照明及二次命中贡献已写入 diffuse/specular AOV；直接可见自发光独立保留。

实际 RayGen 以当前 radiance 的 RGB 和大于 1e-5 判断 NRD 信号有效性：零贡献样本写入 -1 距离，准备阶段将其归一化为 0；合成遇到 alpha=0 时选择当前噪声 RGB，放弃降噪输出。于是被遮挡或未采到有效发光 texel 的黑色样本可以持续绕过 NRD。

`./gradlew nrdCompositeContractTest --offline` 在修复前报告 `Zero-radiance Diffuse surface samples incorrectly bypass NRD history`。这验证的是实际 shader 源码中的错误接线，非 GPU 像素回放。

## 修复

有效性由主表面是否命中决定，不由当前帧光照能量决定。保留已采到的路径距离；无有效路径距离时使用面积灯距离或既有 64-block 环境哨兵，非相机深度。两通道均保留零能量观察，避免合成退回原始黑点。背景与范围外回退不变。

实体光源遮挡差值继续按用户要求绕过 NRD；其随机采样残差仍可能可见。有限段太阳/月光大气及直接可见的发光表面保持独立路径，因此本修复不等于所有画面噪声已消除。

## 验证边界

通过 shader 合约和编译检查后部署。回归覆盖源码接线；画面改善幅度、剩余实体阴影残差以及历史稳定性须实机确认。
