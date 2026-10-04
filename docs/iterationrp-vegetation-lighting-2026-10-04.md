# ITRP 草木受光模拟（2026-10-04）

## 参考

用户提供的 iterationRP Alpha 0.8.29.zip，SHA256 `7d79e2eddf0a57e2ff570f0994fc1a50e014c11e81201711fa947788b605c0ad`。
- `shaders/Lib/Programs/Composite/Soild_FS.glsl:592`：草的太阳受光法线向世界上方偏置 0.49。
- 同文件 603、616：树叶权重 0.25、草权重 0.20，将 Burley 太阳漫反射混向 0.6。
- `shaders/Lib/Utilities.glsl:478`：Burley 响应包括 NdotL 和 1/pi；0.6 是直接混合目标，不再除以 pi。

## 实现

- 草、长草、干草、蕨类、bush 分类为草；LEAVES 标签或 _leaves 路径分类为树叶。grass_block、树干和普通地形不受影响。
- 保持每材质七个 vec4；normal.w 存分类，closest-hit 将其传到不透明材质 payload.material.w 的负值区域。正值仍表示透明介质的进出面，光学色散字段保持原样。
- 在太阳 NEE 中加入上述 Burley 法线偏置和薄片受光混合。原有 BRDF/MIS 保留，额外项为“模拟响应减去原太阳漫反射响应”，只由 NEE 积分。
- 模拟受光同样经过太阳光盘采样、大气透射、天气衰减、RGB 阴影。背光时阴影射线从朝向光源的一侧发出，避免单薄草面立即遮住自己的背光采样。
- 法线偏置只用于这项太阳漫反射，不改几何、法线贴图、NRD 法线、镜面反射或路径续传方向。
- 即使关闭 PBR CPU capture，含草木的 section 仍走能携带分类的 CPU capture，因为 compiled MeshData 没有方块身份。该模式下会增加相关区块捕获开销。

这是 ITRP 风格的美术近似，不是完整次表面散射。未复制可选 SUNLIGHT_RIMS，也未实现树叶内部厚度传输。

## 验证和部署

`./gradlew rayTracingPbrMaterialsTest rayTracingShaderContractTest rayTracingAtmosphereShaderTest sceneGeometryMergeContractTest jar --offline` 全部通过。测试覆盖草木分类与草方块排除、材质布局、实际活动 shader 编译、物理和旧大气 raygen 编译。尚未做 GPU 截图验证。

已替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`；未修改用户配置。重启客户端后重新捕获场景才能加载。

SHA256：`e00962e0f000c30158d3c91a3d170a3d895ce68b6cd237192ef7aac415d8aed6`。
备份：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar.bak-vegetation-lighting-20261004`。
