# 删除 Sundial 降噪

用户明确决定不再保留 Sundial，当前源码只支持 NRD 与 OFF，不再为未来物理空气透视考虑 Sundial 兼容。历史研究及第三方材质算法来源记录不回写、不删除。

## 实际修改

- `fsr/RtestFsr3.java` 删除 Sundial 创建、资源、token、录制、取消、提交、销毁和 bit-exact太阳历史；保留NRD取消/提交、太阳角度阈值及FSR模式变化reset。
- `fsr/RtestDenoiserMode.java` 仅OFF=-1、NRD=1，选择接口只接受NRD开关/强度。
- `RayTracingClientConfig`、`RayTracingSettingsScreen`和两语言移除三个Sundial降噪设置及相关描述。
- `RayTracingShaderRaygen` 删除legacy YCoCg/归一化hitDistance分支；只输出原有NRD linear radiance/raw hitDistance，OFF保留五个FSR输出，NRD保留十一个额外guide。未更改BSDF、MIS、太阳/大气查询或geometry。
- 删除专用Java、compute源码/二进制、专属test及Gradle任务。保留NRD仍使用的split AOV及descriptor ABI，不以全局grep删除共享资源。
- 更新现有模式/历史/生命周期/source-contract测试；保留NRD信号及guide有效性约束。未新增测试seam。

## 验证边界

`./gradlew test jar correspondingSource --offline`通过60tasks；实际JAR无Sundial类/专用shader残留。shader tests仍确认OFF五次image写与NRD十六次写。两variant RayGen/大气查询SPIR-V继续校验。CPU/编译检查不是实机画面或FPS证明。

未部署、未修改实例配置。当前已安装诊断版仍含旧Sundial，需后续授权/停止实例/备份最新配置/原子部署后才在游戏中移除。旧配置中的三字段不主动编辑；新版NeoForge加载可能按新schema自动修正，不保证游戏重新保存后保持废弃字段。
