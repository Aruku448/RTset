# 2026-10-01：细节优先的采样 → NRD 质量修正

## 目标和现场依据

优先保留材质纹理、接触阴影、细小几何和窄高光，不通过扩大模糊或额外锐化掩盖信号错误。

参考截图：`/tmp/Spectacle.jxZPJG/屏幕截图_20261001_193218.png`。最新实例日志在 19:32:05 确认 `NRD=true, guides=true`；19:33:45 游戏停止。配置为 GI=4、FSR `native_aa`、NRD strength=1、hit-distance reconstruction=`off`。不能把此前 16:42 的无降噪性能测量当作此次截图的状态。

截图的全部涂抹来源仍未经 GPU A/B 验证。下面区分确定的代码/契约问题与待验证的参数取舍。

## 确定修正

### 1. 连续时间 Sobol 序列

`RayTracingShaderRaygen.java`：将 `frame * 4 + bounce` 改为连续 frame 索引；保留 vertex、effect、dimension 的独立扰乱域。bounce 已由 vertex 隔离，不需要额外稀疏时间跨步。没有减少光照、GI、体积或阴影样本。

### 2. 依照实际 BSDF lobe 拆分 AOV

之前，两个连续采样分支均使用完整混合 BSDF `fd + fs`，却按抽中的 proposal 将整条后续路径标为 diffuse 或 specular。这不是实际漫反射/镜面的无偏通道划分；材质相关漫反射可能进入镜面解调通道。

现在保留原总 throughput、BRDF、PDF、MIS 和 RR，在第一处连续散射记录 RGB `fd / (fd + fs)`，将同一后续路径贡献分别乘 share 与 `1-share`。正能量分量精确除法，不通过额外 epsilon 改变小能量比例；零能量分量返回零。两个通道逐路径之和仍为原总贡献，公共 RR、介质衰减和光谱权重自然作用于两者。

Primary delta mirror 的 diffuse share 为零；primary transmission 保留专用传输累加器。主表面 NEE 原本已经分 lobe，不重复拆分；可见自发光不进入过滤通道。

### 3. 次级距离不再用相机距离兜底

第一处次级命中/天空距离按实际有能量的 lobe 写 guide。未采到的 lobe 保留零；天空次级 miss 保留现有 64-block sentinel。删除 camera-to-primary 距离兜底，避免将 viewZ 错当路径 hit distance。保留既有 Sundial/NRD 编码协议和 NRD 的 viewZ 归一化。

### 4. 重用面积光时保留实际选择概率

`AreaLightSample` 增加局部 shader 字段 `selectionPdf`，保存表面采样时的树选择概率。体积重用该灯样本时只更换面积 → 立体角 Jacobian，不再用体积位置的另一棵分布重新计价。背面/退化面积不贡献。

这只修正 PDF 错配；表面可见性和采样支持域重用仍是既有近似，不声称体积估计已经完整无偏。

### 5. NRD 历史不被太阳微动每步清空

`fsr/RtestFsr3.java`：bit-exact 太阳变化只用于 Sundial；NRD 继续用自己的 1°突变阈值。镜头切换、FSR reset、场景 revision、atlas identity、调参和 NRD 启停仍会失效历史。切换 OFF/NRD/Sundial 时，在 FSR `beginFrame` 前请求一次 reset，避免新模式混用旧输出。

没有更改 GPU fence、资源归还、image barrier 或 descriptor 生命周期。

### 6. NRD 解调后再保护 FP16 范围

`nrd_motion.comp`：`sanitize(raw / factor)` 替换 `sanitize(raw) / factor`。例：有限灰色 2000 除以 0.02 得到 100000，超过 FP16 的 65504。现在保护除法后的结果；正常可表示范围不额外压暗。并非解决了所有 producer 端 HDR 溢出问题。

### 7. 合成不消费范围外的旧输出

`nrd_composite_simple.comp`：新增 `denoisingRange` push float；用与 NRD 4.17.3 相同的严格、NaN-safe `z < range` 判定，在读取任何 denoised history 前将范围外像素原样返回。NRD 会跳过这些像素，不能假定输出已更新。

`NrdDenoiser.java` 使用同一帧的 range 配置；现有 16-byte push range 不变，实际推送两个 float（offset 0/4）。两个生产 `.comp.spv` 已同步重新编译，而非只改 GLSL 文本。

### 8. 无有效次级 guide 的通道保留当前帧信息

独立复核指出，官方要求零 hitT 的概率跳过 lobe 使用重建和邻域有效样本；不能把“非零主表面 NEE + 零次级距离”无条件视为有充分 guide 的降噪输入。合成逐通道检查准备后的 hitT：只有正值才采用该通道的降噪输出，否则保留该通道当前帧的解调信号并按原因子重调制。另一有效通道仍可过滤。

Opaque composite 原本未使用的 binding 8 改为 noisy diffuse，新增 binding 9 noisy specular；Java descriptor 数量/图像数组和 SPIR-V 同步更新。没有新增图像或改变 barrier/fence；使用现有准备阶段输出和 compute barrier。当前帧回退可能更有噪声，并有已有 FP16/YCoCg 量化，不承诺逐像素位等价或完整解决内部历史的所有零-guide 边界。

## 待实景验证的细节优先参数

| 参数 | 原实例 | 新实例 |
| --- | ---: | ---: |
| diffuse prepass radius | 22.6508 | 0 |
| specular prepass radius | 0 | 0（保持） |
| min blur radius | 2.7076 | 0.5 |
| max blur radius | 12.2597 | 8 |
| history-fix pixel stride | 14 | 4 |

新安装的默认 min/max/stride 也采用 0.5/8/4。保持 strength=1、现有历史帧数、GI=4、FSR native AA、roughness、材质因子、几何精度及 reconstruction 模式。

较小的空间滤波范围可能让刚显露区域暂时更有噪声，这是保细节与快速平滑之间的取舍，不是已经证明的质量胜利。

## 自动验证

- 新测试先红后绿：AOV/Sobol/distance，体积选择 PDF，解调后 FP16 保护，合成 range guard，FSR/NRD 历史调用边界。
- `RayTracingSignalQualityTest`：真实生成 GLSL 的结构契约 + CPU 数学反例，包含 lobe 和、公共 RR、零分量、delta、RGB/光谱 mask。
- `DenoiserHistoryQualityTest`：调用实际 NRD 太阳突变谓词，检查 0.01°、0.99°、1.01°、2°及集成调用顺序。
- NRD motion/composite 与所有 RT shader 均执行 shaderc 编译；OFF/Sundial/NRD 常量模式的 image write 计数仍通过。
- `./gradlew test jar --offline`：通过。
- 生产 shader 二进制：

```sh
for shader in nrd_motion nrd_composite_simple; do
  glslangValidator -V -Os --target-env vulkan1.2 \
    "src/main/resources/prime/shaders/$shader.comp" \
    -o "src/main/resources/prime/shaders/$shader.comp.spv"
  spirv-val --target-env vulkan1.2 \
    "src/main/resources/prime/shaders/$shader.comp.spv"
done
```

这些是编译、数学及契约检查，不是实际 GPU 图像比较或性能测量。

## 实景验收

重新启动 RTest，保持窗口尺寸不变（既有 resize device-loss 未修复）。同一视点、材质包、曝光和时刻，分别检查关闭 NRD 的原始图、启用后刚出现的图、静止约 2 秒后的图：

1. 棋盘/细纹地面：纹理对比不能在静止后持续消失。
2. 台阶、细杆与贴地物体：接触暗线、轮廓不应扩大或漏掉。
3. 粗糙/光滑金属与镜面：窄高光应保留，不混入漫反射纹理。
4. 慢转视角、遮挡显露、动态实体影子：检查拖影和短时噪声。
5. 慢变太阳及 OFF→NRD→Sundial 切换：历史应收敛，切换不混旧模式输出。
6. 高亮灯、玻璃及雾：检查溢出、偏亮和透射能量。

GGX VNDF 等改变采样/PDF 的进一步低方差方案暂未实施，避免在本轮叠加未经均值/方差验证的估计器改动。

## 部署记录

- 已安装：`/home/aruku/.minecraft/versions/RTest/mods/rtest-0.1.0.jar`
- SHA256：`d9c86e168c6f7d77cc1c89635b8c1279cb1a76ec192dcf565b4073bf4328fef0`
- 原 JAR 与实例配置备份：`/home/aruku/.minecraft/versions/RTest/backups/denoiser-quality-20261001-195900/`
- 确认实例停止后原子替换；实例配置仅改上述四项，TOML 解析比较确认其他设置不变。
- JAR 内两份 GLSL 和两份 SPIR-V 与工作区一致；最终全套测试通过。需要重新启动加载。
