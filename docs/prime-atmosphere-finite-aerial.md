# 四波长有限段空气透视实现

固定来源 Prime26.3 `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`。这是保留RGB透明阴影的本地adapter，不是完整搬入epipolar/clipmap；源码已接入，尚未部署或做真实GPU像素/性能验证。

## 物理段

`RayTracingAtmosphereSegmentShader.java` 翻译上游 `model/atmosphere/{spectrum,source,geometry,optical_mapping,transmittance}.slang`；`RayTracingShaderRaygen.integratePhysicalAtmosphereSegment` 翻译 `integrate.slang` 的有限段积分：固定介质、四波长五物种、表驱动phase、低高度source/logmean及>=35km高moment分支、球壳边界、非均匀step和small-tau稳定积分。使用绝对眼高和0.001km/block，与Sky一致。真实Minecraft表面提供地表光，不附加上游earth-ground尾项。

每步保留当前TLAS RGB阴影（玻璃/水吸收及alpha cutout），直接光谱经viewT加权、积分并转换到Rec.2020后乘RGB可见性；多重散射独立转换且不乘局部直接光阴影，沿用Prime源项语义。4/8/16sample档不变，没有降低几何/采样或移除同步。Sky/background不再加有限段雾。

太阳有限段散射与Sky同样乘`sunIntensity/12.5`，不重复乘旧daylight/blackbody。原雨/雷艺术visibility保持。介质密度固定默认100；旧fogDensity在物理路径只有zero禁用、positive启用，不能靠随意缩放已求解静态source冒充新密度解。volume strength正值仅缩放太阳L；0禁用整个有限段。UI/配置说明已更新；旧模型fallback仍用旧参数规则。

## 合成与局部发光

仅NRD/OFF两mode。表面T仍在RayGen对surface/AOV预乘；局部emitter仍复用原表面选光PDF及RGBvisibility，估计只进入原NRD diffuse history，不重复T。全量太阳散射L写独立RGBA16F图；`AerialPerspectiveComposite`在NRD结果或原始RT之后、FSR之前加L。没有将负的阴影差值送NRD，没有把太阳L同时塞进emission，也没有两次surface透射。

太阳L目前不做单独时域降噪（与固定segment采样相配），不能宣称确定性无阴影/完全无alias；移动薄遮挡、玻璃及夜间等效果仍须真实GPU检查。后续若改为全post-T，需要重新解决localEmitter历史归属，不能简单将现有scene整体再乘T。

## 资源与同步

RT物理variant新增bindings30–36：L输出、medium SSBO、optical/source sampled2D、mean/ground/high readonly images；原0–29 ABI不改。静态最终bank0和medium由`RayTracingAtmosphere`只读提供，最后bootstrap加入compute-write→RT/compute-read依赖。

L由`RtestFsr3`按renderextent拥有，新增有效payload为width×height×8bytes（2560×1404约27.42MiB，不含VMA对齐）。每个物理RayGen像素先写zero，包括sky、debug、volume关闭，避免残影；初始GENERAL、下帧compute-read→RT-write和RT-write→compute-read同步保留。NRD→aerial及aerial→FSR有显式compute RAW/WAR/WAW barrier；gate关闭不dispatch合成，不读未写L。相关framefence仍由现有pass退休。新composite创建后upscaler失败会补偿销毁，native pipeline部分创建句柄会清理。

## 验证

`test jar correspondingSource --offline`全部通过60tasks；实际RayGen物理/legacy和query四variant编译，新增composite通过Vulkan1.2 SPIR-V validation。原source contract因text-block缩进变化失败，改为只忽略空白比较，约束token未删除。复核修正了遗漏太阳能量倍率、零初始化和跨compute依赖。没有启动游戏、修改实例配置或部署。

这些检查不证明四波长数值对上游逐像素一致、不证明原生失败恢复或GPU耗时。实体体积shadow仍逐sample查询，不能以旧Sky0.568–1.134ms宣称整个空气透视廉价。实机需固定窗口/相机/太阳/天气/NRD配置，分别核对室外、洞穴、薄墙、彩色玻璃、水、局部发光、夜间、NRD/OFF切换和volumezero。
