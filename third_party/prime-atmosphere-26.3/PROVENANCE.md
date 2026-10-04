# Prime 26.3 大气来源与修改声明

- 上游：https://github.com/bWFuanVzYWth/prime
- 固定分支/提交：26.3 / `3ab5f75e33de3f6947e96f4a4dd1406588e9e98b`
- 物理模型原来源：Sky Tracer `b66b16342afe38e788a5ece5371d3b3a67c5909a`，见 `SKY-TRACER-NOTICE.md`。
- Prime 代码 GPL-3.0-only，限定附加许可见 `LICENSE-EXCEPTIONS`；Sky Tracer 来源部分按其 GPL-3.0-only 声明，不能擅自增加其他作者未授予的例外。

## 导入记录

`src/main/resources/prime/atmosphere/{medium.bin.gz.b64,extinction.bin.gz.b64,medium.json}` 从固定上游逐字节复制，未重拟合物理系数。解压结果与上游 manifest 匹配：

- medium：199584 bytes，SHA256 `be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5`。
- extinction：1600 bytes，SHA256 `13fd1a001ce195be387bd2c57c989fce8b94a33bb476a8504e87da7976311a29`。

`src/main/java/com/rtest/client/atmosphere/{AtmosphereSettings,AtmosphereMedium,AtmospherePrecomputation}.java` 来源分别为上游 `src/client/java/dev/prime/render/AtmosphereSettings.java` 及 `render/vulkan` 下的两文件。修改仅 package/import 与 RTest 修改声明，倍率和调度算法保持原样。适配代码仍保留原 GPL 来源头。

`shaders/` 原样保留七个 compute entrypoint、十二个 atmosphere model、solve contract、方向映射及固定 `abi.json`，共22文件；`SOURCE-MANIFEST.json` 记录逐文件 SHA256。没有搬入 aerial/epipolar 或 Intel 阴影 profile 代码。本地脚本从固定 ABI 自动生成 standalone 大气标量声明，不修改物理模型。

`AtmosphereCoordinates.java` 适配上游 `render/AtmosphereCoordinates.java`，内联固定 scalar ABI 常量并拒绝非有限坐标。`RayTracingAtmosphere.java` 参考上游 `render/vulkan/AtmospherePipeline.java` 的资源、descriptor与dispatch，改为26.2原生 Vulkan ownership、分批fence bootstrap和完成后key发布；只调度原七核。`SkyLutHistory.java` 是此窄提交接口的本地状态实现。

`RayTracingAtmosphereShader.java` 的GLSL查询适配上游 `model/atmosphere/{sky_view,sky_rows,geometry}.slang`、`math/atmosphere_direction.slang` 与 `service/atmosphere/trace.slang`：保留chart/log读表，新增finite guards；后者没有整文件导入其aerial/epipolar依赖。Sky Tracer衍生的chart/geometry仍按GPL-3.0-only处理，不擅自扩大Prime例外。RayGen/VulkanPass增加可选LUT/太阳读表与生命周期接线，不改上游四波长compute transport。

`src/main/resources/prime/atmosphere/shaders/` 是精确Slang产物，源/工具/每个二进制hash见 `toolchain/LOCKED-ARTIFACTS.json`；打包前由 `scripts/verify_prime_atmosphere_resources.py` 强制核验，不能混入兼容性探测输出。

`RayTracingAtmosphereSegmentShader.java` 和 RayGen 的 `integratePhysicalAtmosphereSegment` 翻译固定上游 `model/atmosphere/{spectrum,source,geometry,optical_mapping,transmittance,integrate}.slang`（Sky Tracer GPL-3.0-only）。保留四波长/五物种、非均匀积分边界、small-tau公式、低高度与高高度多重散射分支；本地adapter逐样本在Rec.2020直接光项上乘现有RGB TLAS阴影，不移植scalar epipolar/clipmap，不添加地球ground尾项到Minecraft表面。新增合成代码是本地实现，不导入Intel epipolar源码；本次不额外声称已导入其闭包。

物理资产验证不等于 GPU LUT/大气显示已经运行。完整接入状态见 `docs/prime-atmosphere-integration.md`。源码及构建脚本需与二进制一起交付，使用 `correspondingSource` 任务生成归档并人工核查分发内容。
