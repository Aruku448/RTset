# 草斜面的世界轴明暗接缝

用户截图：`/tmp/Spectacle.IvlRiJ/屏幕截图_20261004_173836.png`。用户进一步说明接缝随世界 X/Z 轴变化。

## 可复现错误

实例启用了 `pbrTerrainCpuCaptureEnabled`。CPU 地形捕获的 `RayTracingScene.addMaterial` 原来将 `BakedQuad.direction()` 的六向面标记写入几何法线。交叉草和旋转模型的三角形实际可以是斜面，面标记只有 X/Y/Z 六个方向。

对位于 x=z 的草平面，真实法线是 (-1,0,1)/sqrt(2)，而旧材料可能存 (-1,0,0)。raygen 会以 dot(rayDirection,normal) 判断可见侧并翻转法线。两条同样位于真实平面一侧的视线，只要 x 分量由负变正，旧法线就改变可见侧判断，随后直接光、天空 NEE 与切线法线映射的朝向发生突变。这符合世界轴线接缝的特征。

这证明了源码中的这一类错误；没有 GPU 同场景 A/B，不能宣称它是截图唯一原因。代码中未发现显式分屏对比；动态实体阴影的整像素 NRD 绕过仍是需要在剩余接缝出现时检查的独立路径。

## 修复

新增 `RayTracingTangent.geometricNormal`，从最终三角形顶点绕序的边叉乘取得单位法线，并将它同时用于地形材料与 UV 切线框架。退化/无效三角形仍安全退回面标记。材质 ABI 与太阳/NRD 参数没有变化。

## 验证

同一实际捕获辅助方法的斜草用例先运行旧六向法线行为：

`./gradlew rayTracingPbrMaterialsTest --offline`

失败于 `Slanted grass plane must store its actual perpendicular normal, not a cardinal face tag`。

修复后运行：

`./gradlew rayTracingPbrMaterialsTest rayTracingShaderContractTest rayTracingAtmosphereShaderTest jar --offline`

全部通过。用例检查法线垂直于斜面、单位长度、X/Z 附近视线变化不错误翻面，以及反向绕序反转法线；shaderc 编译活动阶段和物理/传统大气 raygen。未做 GPU 画面对比。

## 部署

已原子替换 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`。需重启客户端以加载新类并重新捕获地形。

SHA-256：`5d0b5d710f6a4535569f413d7e4874a4ad3b1440daab96f8c286ff3922883fc0`。

旧 JAR：`rtest-0.1.0.jar.bak-slanted-normals-20261004`。本次没有修改用户配置。
