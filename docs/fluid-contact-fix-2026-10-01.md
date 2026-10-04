# 水/方块接触处跳面修复

依据截图 `/tmp/Spectacle.nSeBTZ/屏幕截图_20261001_231028.png` 排查。没有用增大降噪、降低采样或删掉实体方块面来掩盖缺口。

## 复现与边界

反馈命令：`./gradlew fluidContactTest --offline`。

回归先提取原本缺失的邻面遮挡步骤（初始返回false，实际网格行为不变），调用生产shape入口，并读取生产流体顶点坐标和生成GLSL的偏移/tMin，进行normal-incidence平面射线数值回放。首次失败：

```text
water/opaque contact loses block face: north water=0.001 wall=0.0
spawn=-0.0019999999 nextHitT=-0.0019999999 traceMin=0.001
```

水边界距离相邻实体面仅1mm，旧continuation偏移3mm已经穿过实体面，tMin还要求至少1mm；射线自然不能再命中刚刚越过的方块面。这是可证实的跳面漏洞，不是仅凭截图推断面已从BLAS删除。

仅修shape遮挡后，全立方体接触用例通过，但半砖接触仍失败：部分水侧面合法可见，不能整面删掉；同样的大偏移仍会跳过其低处的半砖壁。修改介质偏移后通过。

继续覆盖顶/底接触：原bottom位于0，与下方玻璃等保留邻面重合，较小偏移仍会越过邻界面，红测`water bottom skips touching block interface: spawn=-1.0E-4`。补齐上下cap规则与原版inset后通过。

**测试限制**：这是真实Minecraft `VoxelShape`、生产捕获路由与GLSL数值的CPU回放/源码集成契约，不是实际GPU trace，也不是整张截图的自动复现。尝试完整Blocks/bootstrap时，普通JavaExec没有FML Loader，故移除此不可运行方案，使用不需要loader的shape入口。没有伪造一个loader声称Minecraft端到端测试通过。

## 核实依据

当前Minecraft26.2 `FluidRenderer.shouldRenderFace`只处理邻块fluid/overlay隐藏策略及自身遮挡；真正的邻块shape遮挡是后续独立的private `isFaceOccludedByNeighbor`。NeoForge默认`shouldHideAdjacentFluidFace`只检查同种流体，不会替代实体面遮挡。

侧面应以两角最大高度查询neighbor对面shape；顶面以四角最小高度查询，只有真的接触上方遮挡面才隐藏；底面查询下方的UP face。`isSolidRender()`不能表达半砖/楼梯的具体边界，也会把上方仍存在空气间隙的水面提前隐藏。

## 实际改动

- `RayTracingScene.java`：补齐六方向邻面shape遮挡，empty/block fast path与原版一致；侧面在构造顶点/查询UV前判定。保留半砖上方真实可见水面。按原版top−1mm/bottom+1mm的inset设置cap，侧面底缘同步bottomOffset；侧面原有1mm inset不扩大。
- `RayTracingShaderRaygen.java`：仅介质表面后的continuation使用0.1mm最小偏移及坐标尺度浮点余量，tMin=0.1mm；初始相机射线、普通不透明反弹与shadow rays维持旧偏移/tMin。采样分布、PDF、MIS、RR、透射能量分账、buffers及同步未改。
- `FluidContactTest.java`/`build.gradle`：测试full/empty/partial face、UP实际高度、上下半砖的DOWN occlusion、四水平朝向、局部坐标−1024…1024、水顶/底和邻界面间隙。回放是法向透射的数值检查，不覆盖所有法线贴图、掠射角、GPU浮点误差或嵌套介质。

Shader的浮点尺度项是保守余量，不是完整的robust-ray误差界；极远局部坐标或精确共边仍需实机观察。不会宣称所有水/玻璃/微小几何问题已经解决。

## 验证与实机验收

`./gradlew fluidContactTest test jar --offline`通过，包括所有shader编译和原有NRD/增量捕获契约；`git diff --check`通过。未新增临时运行时debug日志，也未调整实例降噪/画质配置。

实机尚待重启后对比：保持窗口尺寸与原设置，回到截图位置，查看水下石头/泥土壁与底面，再看半砖、楼梯、玻璃与水接触处；切换视角验证三角洞是否消失且没有新增self-hit黑点。噪声改善不是本轮验收承诺；不需要开启降噪来验证缺口。

## 部署

确认实例停止后，已原子安装 `/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`。

SHA256：`e0d2cc6f240380b26ad04a056afd4ce9e20219a3c3d13f8584422a29415442bc`。

旧JAR/配置备份：`/home/aruku/.minecraft/versions/RTest/backups/fluid-contact-20261002-002402/`。实例配置逐字节不变，重启后加载新几何与shader。
