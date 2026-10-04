# 当前大气性能与体积接线检查

## 实测范围

只读保存当前实例日志到 `/tmp/rtest-atmosphere-performance-163415.log`，SHA256 `b4e51f299379b2a130d9fe4f09776753ed9729080658241f9f2fc5c2004de6b9`。安装JAR仍为诊断版 `8485a2f48bc00811a50d19603191222c798586d28668d642a293e4eb14002f51`。没有修改实例配置或部署新JAR。

- 首次/重建共5次 bootstrap，wall_ms分别827、752、764、752、751。每次290 dispatch/10 fenced batches完成。**约0.75–0.83秒是CPU墙钟，不是单独GPU初始化时间**，包含分配、driver pipeline编译、提交和等待；每个pass重新初始化，不能称整个会话只执行一次。
- 日志62个稀疏GPU样本，每120帧打印；两个样本实际Sky LUT区间非零，分别1.134与0.568ms。这个区间包含动态Sky/方向T计算及同步，不含同帧RayGen的LUT读表，更不包含旧体积积分。其余样本0不能说明LUT免费，也不能据此算更新频率或全帧平均开销。
- 最后一次bootstrap后 `[16:33:50,16:34:11]` 有6个GPU样本，2560×1404 → 2560×1404：RT中位19.6355ms（17.422–39.669），post中位4.4595ms，RT开始到最终copy区间中位23.8955ms。窗口内仍有8次geometry merge/publish，视角未冻结，不是稳定同场景A/B。
- `total_ms` 是q3−q0，不是Minecraft完整帧，不包括此前天空更新、AS/Hi-Z等；不能简单换算FPS。`terrain_traversal_ms=0` 也不是没有AS构建的证据。

原始统计：`docs/profiling/2026-10-02-atmosphere-live-window.json`、`2026-10-02-atmosphere-live-lut.json`。

## 当前配置（读取时快照）

`primeAtmosphereEnabled=true`、`skyboxTextureEnabled=true`、NRD=true、Sundial=false、FSR native_aa；volume=true、strength≈1.46699、fogDensity≈0.182329、quality=3。

两天空开关同时true不是叠加两套天空：物理分支有效时不进入PNG采样分支；PNG只用于物理路径未启用/不可用的legacy分支。用户已自行恢复贴图开关，本次不更改。

## 体积光/体积雾确实尚未并入物理模型

这是首阶段已记录的未完成接线，不是当前雾密度设置失败：

- 新物理路径只生成静态散射场、SkyView与相机方向太阳透射；背景/反射/折射miss、太阳盘和NEE读取新LUT。
- 旧 `integrateAtmosphereSegment` 仍在RayGen中处理实体的有限相机段，使用原有系数、艺术倍率和采样/阴影查询。`volumetricLightingStrength` / `volumetricFogDensity` 控制它，不会更改Prime固定介质或静态多重散射场。
- 物理天空背景跳过旧相机段雾，避免重复散射；不是将背景雾合并到了新空气透视。
- 局部emitter体积估计和NRD/Sundial AOV分工仍保留。还没有物理aerial-radiance/aerial-transmittance LUT与相应合成阶段。

因此“天空是物理LUT，实体雾仍是旧模型”的混合状态是当前事实，不能宣称已完整移植Prime大气系统。

## 下一步的性能归因与实现边界

RT内尚无独立GPU区间可将旧体积积分、物理天空读表与其它光照分开；仅凭现有日志不能给出它们各自的ms。已核对 `RayTracingShaderRaygen.java:440–489`：白天太阳有效时，旧积分每一步执行一次TLAS透明shadow查询，quality3最多16次/有效实体像素；甚至shadow混合权重为零仍查询。2560×1404满屏实体时理论上限约5750万次体积shadow/帧，**不是实测射线数**，实际还受primary-hit、开关、太阳/昼夜等条件控制。local emitter单样本复用表面可见性，不增加独立体积shadow。旧介质高度还使用相对相机Y，新模型用绝对world height，两者不是同一物理状态。

后续需要固定窗口、相机、时钟、天气、材质、NRD/FSR，进行受控对照：物理天空on/off、旧体积on/off，滤掉bootstrap和资源重建期间；结束恢复原设置。仅作为归因，不能把关闭体积/降低采样作为优化方案。LUT更新还需事件计数/聚合时序，不能用每120帧打印的0样本估算。

只读核对上游 `AtmospherePipeline` / aerial entry / `trace.slang` / NRD composite后，确认所需aerial L为128×256×128、T为128×64×128；太阳遮挡依赖双银行五级RT shadow clipmap及叶层/profile，执行链为shadow→hierarchy→aerial，T独立计算。更新key要加入投影、眼高、完整太阳方向及shadow版本，不能沿用Sky的eye/sunY键。

完整物理空气透视应忠实接入这些资源，在表面降噪之后、FSR之前按深度合成 `Lsurface*T+Laerial`。必须移除旧太阳/环境体积散射及对应AOV预衰减，避免重复散射/二次透射；继续保留局部发光随机体积估计及其采样PDF/历史归属，不能把它当作全局太阳源。关键上游证据：`AtmospherePipeline.java:187–219,422–534`、`atmosphere_aerial.compute.slang:38–154`、`atmosphere_aerial_transmittance.compute.slang:43–105`、`nrd_composite.compute.slang:71–107`。这份检查没有实现新体积模型，也没有进行降低采样、移除同步或模糊之类改动。
