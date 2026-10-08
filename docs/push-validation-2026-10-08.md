# 推送前验证（2026-10-08）

本次提交包含此前完成的 ReSTIR、光追分段审计、GPU/CPU 算法与模型捕获优化、PBR 法线修复、后处理、F9 设置与当前画面默认值。临时诊断输出、备份 JAR、实例配置与测试快照目录 tmp/ 不纳入版本控制。

运行 `./gradlew test jar --continue`，发现七项失败。其中六项属于过期源代码断言或独立测试环境未加载配置：第一人称提交存储类型、当前 LOD 默认值与阈值、相干大气高度查询接口、ReSTIR 控件工厂、物品捕获配置初始化。更新测试初始化与断言后，下面的定向检查全部通过：

```
./gradlew restirActivationTest itemModelGeometryAdapterTest entityRasterFallbackContractTest rtActivationContractTest sceneGeometryMergeContractTest rayTracingAtmosphereShaderTest
```

ReSTIR 测试执行真实 F9 CycleButton 回调，并验证模式切换、独立配置重载与审计覆盖。控件工厂保持无实例状态的静态方法，独立测试无需构造依赖 Minecraft.font 的 Screen。

尚未通过：`playerAnimationContractTest` 中手持地图的捕获断言。该独立 Java 测试没有应用 RenderType/RenderSetup 的 Mixin accessor；纹理查询捕获 ClassCastException 后返回 null，自定义手持地图提交因而被跳过。此测试的前置模型、地图四边形和材质检查已执行，但手持地图端到端路径需要带 Mixin 的游戏运行环境进一步验证。本次保留失败断言，没有修改生产纹理查询逻辑，也没有将整个测试套件声明为通过。

105 个光追审计着色器模块编译、ReSTIR 数学和着色器检查、PBR 上传、AS 同步、资源生命周期及其他已运行检查通过。`git diff --check` 通过。待提交 src/docs/tools 中凭据特征扫描无匹配。
