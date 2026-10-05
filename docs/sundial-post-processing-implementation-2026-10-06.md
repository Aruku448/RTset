# Sundial 参考后处理：RTset main 实现

## 范围

参考 `Sundial Alpha Build 2026-07-31.zip` 的可见功能，独立实现 Vulkan compute 后处理；研究依据见 [源码研究](sundial-post-processing-research-2026-10-06.md)。不是逐像素移植，不包含 Sundial 的光照、大气、云或反射求解。

完整功能通过 F9 → **后处理** 调节，并存储于客户端配置的 `postProcessing` 节。默认开启，关闭总开关即恢复原有 Prime 显示路径。参数变化下一帧生效；重新开启后清除曝光/焦点历史。

## 管线

```text
RT → NRD/原始贡献合成 → 大气 → FSR 重建 → 离线线性累积（如启用）
 → GPU 曝光/焦点历史 → CoC 前景扩散 → 景深聚集 → 运动模糊
 → 无阈值泛光 mip 链 → 镜头畸变/色散 → 雨雾/泛光/暗角
 → 曝光/饱和度/色温 → SDR 色调映射/编码或原生 HDR
 → 最终锐化/SDR 抖动 → 游戏显示与 HUD
```

曝光及焦点不读取回 CPU，不修改路径辐射量、NRD 历史或 FSR 的固定场景预曝光。后处理开启时 FSR 内部 RCAS 强度为零，最终显示仅锐化一次。诊断视图绕过此链。HUD 不参与测光、泛光或景深。

## 对应功能与区别

| 功能 | 实现 | 与参考的区别 |
|---|---|---|
| 自动曝光 | 16×16 中心加权广义亮度均值、亮/暗独立时间常数、EV 补偿 | 对线性 BT.709 亮度统计，使用指数时间平滑；参考是 mip RGB 均值和线性插值 |
| 泛光 | 最多七级 RGBA16F mip，全部辐射量参与，权重 `0.92^(level+1)` | 归一化 tent 逐级滤波代替 atlas 横纵 9 taps；不是相同点扩散核 |
| 景深 | 有符号 CoC、近景扩散、黄金角聚集、深度遮挡判断 | 在 FSR 后运行，使用稳定采样，不复制参考 TAA 前的随机聚集 |
| 对焦 | 中心自动对焦或手动距离 | 自动对焦平滑倒数距离，避免看过天空后迟迟无法回焦 |
| 运动模糊 | 已有 `previousUV-currentUV`、轨迹聚集、前景拒绝、切镜禁用 | 不重复参考的向量压缩或额外历史图像采样 |
| 色调映射 | Uchimura、ACES RRT/ODT 拟合、AgX 及已有 Prime | 使用 RTset 线性输入与精确 sRGB 编码，不复制中间 ×0.005/×100/×6 的内部编码 |
| 调色 | EV、饱和度、色温、gamma；Uchimura 对比度/抬黑/黑部收紧；AgX looks/EV 范围 | HDR 保留扩展线性值，SDR tone 曲线不作用于 HDR |
| 镜头效果 | 暗角、径向畸变、RGB 独立径向色散 | 默认色散/畸变为零 |
| 天气/介质屏幕效果 | 雨量、天空等级、场景深度影响 bloom haze；水/熔岩额外 bloom | 没有迁入 Sundial 特定天气编码，独立于物理大气 |
| 锐化/抖动 | 有界对比度锐化，SDR 时间抖动 | 延用现有 FSR 重建，不再添加 TAA |

## 数学约定

- 输入为场景线性 Rec.2020；测光/调色转换到 BT.709。原生 HDR 按输出 primaries 选择保持 BT.709 或转换回 Rec.2020。
- 深度来自既有 reversed infinite depth：`viewZ=0.05/depth`；零深度表示天空。
- CoC 显示像素半径：`15 * aperture * (viewZ-focus)/(viewZ*(focus-focalLength))`，裁剪至用户设置的半径上限。**不乘屏幕高度**。
- 自动曝光强度为零时仅应用 `2^EV`；非零时参考曝光标度为 `0.2*(meanBrightness+1e-5)^(-strength)*2^EV`，亮度统计使用 `0.01*luminance`。
- 泛光合成 `(C+B*amount)/(1+0.5*amount)`，`amount=0.2*bloomIntensity+waterOrLavaBoost`。因此水/熔岩状态下 `bloomIntensity=0` 不代表总泛光为零。
- HDR 不裁至 1；半精度中间图像使用 60000 的有限值保护。极端 gamma 在幂运算前限幅，避免溢出导致整像素变黑。

## 资源与性能

拥有四张全尺寸 RGBA16F 图像（CoC、DOF、运动光学、调色），半尺寸开始的 mip 链，以及 1×1 RGBA32F 状态与 176 字节设备端 uniform buffer。1440p 图像有效负载约 **122 MiB**，不计驱动对齐与外部 FSR/NRD 资源。资源随上采样器创建/销毁，调整窗口或重建上采样器时重置历史。

全部阶段显式同步。uniform 更新前保护前帧读操作，更新后 transfer→compute；图像各阶段 compute→compute。景深/运动采样数量可以通过 F9 降低。关闭总开关不执行此链，但已创建图像暂时仍保留到上采样器销毁。未测量游戏内 GPU 时间，不能据小尺寸回归测试声称帧率改善。

## 回归验证

`tools/run_post_processing_smoke.sh` 编译源文件并核对被打包的两份 SPIR-V，然后直接运行生产 compute shader 的所有阶段。

在 RX 7800 XT / RADV 上验证 SDR 和原生 HDR：

- 奇数尺寸 mip、全像素输出有限值；
- 恒定图像与无阈值泛光的解析能量增益；
- 手动 +1 EV 翻倍、曝光历史变化与重置；
- 四种 SDR 色调映射亮度单调性；HDR 高光大于 1；
- 散焦高光扩散、近景散焦轮廓扩散；
- 运动高光扩散、相机切换关闭轨迹。

这些验证不替代游戏内 HUD 合成、薄几何、透明表面深度、窗口变化与实际画面风格的检查。景深与运动拒绝使用当前可见表面深度，透明多层焦平面无法由一张深度图精确恢复。

## 来源与声明

没有复制整个 Sundial 包。Prime 输出算子复用项目已许可的实现及 `third_party/PRIME-LICENSE*.txt`。色温近似在 Sundial 中标注来源 https://www.shadertoy.com/view/lsSXW1 （CC BY 3.0）；这里改为动态 Kelvin 参数、低温边界保护与线性色温输出。最终锐化采用标准有界 RCAS 数学，项目已保留 FidelityFX SDK 的许可；本实现未复制 Sundial final pass。更多定位见研究文档。
