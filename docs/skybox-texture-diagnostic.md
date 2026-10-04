# 暂停旧天空盒贴图的诊断版

本文记录此前暂时关闭天空盒的诊断部署。当前源码按用户新要求保留天空盒并支持与物理大气半透明混合；新配置默认开启、默认25%贴图权重。见 `docs/prime-atmosphere-skybox-blend.md`，尚未部署；下面false默认及旧shader行为仅描述历史诊断版。

用户当时要求暂时关闭天空盒贴图，观察Prime物理天空是否生效。

- `skyboxTextureEnabled` 默认false，F9「旧天空盒贴图」可恢复。RayGen在原304-byte UBO闲置lane296传递该标志；关闭时不执行PNG采样，天空miss和反射/折射的贴图环境照明都归零，物理LUT和太阳不受此标志影响。切换会重建pass/FSR history，避免旧图残留。
- `primeAtmosphereEnabled` 仍默认false，测试时在F9开启「Prime物理天空（实验）」。关闭/初始化失败时不再有PNG回退，但太阳及原有限段雾仍可能可见；不能把它们误认作Sky LUT生效。
- 开启物理天空后原色温/PNG分支不再用于物理天空；新模型实机验证仍未完成。固定窗口尺寸，不做resize压力测试。
- `test jar correspondingSource` 61tasks通过；四份query/RayGen SPIR-V通过Vulkan1.2 validation。这不是GPU画面/FPS证据。

## 部署

部署前检查Minecraft进程停止，核对旧JAR为`8fca8694…`，备份JAR与当时最新配置，再原子替换。

- 安装JAR：SHA256 `8485a2f48bc00811a50d19603191222c798586d28668d642a293e4eb14002f51`。
- 备份：`/home/aruku/.minecraft/versions/RTest/backups/skybox-texture-off-20261002-162531/`，含`deployment.json`。
- 配置逐字节保留：SHA256 `bb418530894d1fcde74e6e220cb33ab392c446302d7cd29510d009515fe88677`；没有擅自改用户其它设置。
- 对应源码：`/home/aruku/.minecraft/versions/RTest/sources/rtest-0.1.0-8485a2f48bc0-corresponding-source.zip`。它是部署构建时源码快照，其中旧「未部署」状态文档记录的是部署前时点；以本记录为准。

启动后F8开启RT，再F9开启Prime物理天空、保持旧天空盒关闭。若无天空，检查`latest.log`是否出现`Prime atmosphere bootstrap completed`或初始化fallback错误；单凭黑色背景不能判断GPU求解成功。
