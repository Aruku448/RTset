# RT CPU / 材质数学审计（2026-10-04）

只读检查当前源码；没有改运行代码、配置或部署。结论区分实数等价、浮点位等价、改变采样/画面的算法。当前配置 POM 与实体 POM 开启，terrainLodEnabled=false，因此 LOD 不是当前加速收益来源。

## 优先级 1：POM 双线性采样的整数包裹约简

位置：`src/main/java/com/rtest/client/RayTracingShaderCommon.java:32`–55、112–145。

当前每个高度采样读四个像素，每个 `pbrHeightTexel` 都对 X / Y 取模：每次双线性样本共 8 次整数 remainder。32 次步进 + 5 次细化 + 起始样本上限 38 次双线性采样，理论上限 304 次取模。

正整数尺寸 n 下，令 a=wrap(x,n)，则 wrap(x+1,n)=(a+1==n ? 0 : a+1)。因此先计算左上角 X / Y 两次 wrap，再用条件加一得到右 / 下邻居，共 2 次 remainder，省去其余 6 次。读到的像素、decode 次序、mix 次序完全保留时，可保持现有浮点计算位序。Python 穷举 n=1..256、x=-2n..2n-1 共 263168 个坐标，恒等式全部通过。并非 GPU 性能测量；GPU 编译器可能已有部分 common-subexpression 消除，仍需看 SPIR-V / 实测。

动画 frameCount / tick remainder / frame UV 变换在每次 `pbrHeight` 重做（43–46）；固定时间与 mapIndex 时可提升到每次 hit / POM 调用。严格保持原 UV 转换算术顺序即可保留结果。进一步把 POM 与 `pbrWorldNormal` 的 tangent / bitangent 生成共享，可省同一 hit 的重复 normalize、sin/cos，但可能增加跨函数活跃寄存器；检查 shader 编译与寄存器再决定。

不要无条件用位与取代 modulo：只有尺寸为 2 的幂，且使用符合整数表示的包裹时成立。现有 API 允许任意正尺寸。

## 优先级 2：整张平坦高度贴图的静态判定

位置：`RayTracingShaderCommon.java:24`、91–92；`RayTracingPbrMaterials.java` 负责资源加载。

alpha 0 与 255 都 decode 为高度 1。若贴图所有帧的所有像素都属于这两个 sentinel，则双线性插值恒等于 1，起始采样必然返回 atlasUv。可在加载期标记“decoded height 恒为 1”，运行时在 tangent / 高度读取前直接返回。该判定对当前算法等价，不能仅凭一帧、均值、min 原始 alpha 或 CPU 采样点猜测。动画全帧与资源重载必须更新标记。可复用 map metadata 的标志位，避免加大 material ABI。

## 已有加速：不要重复列为待实现

- LightTree `buildOrReuse` 对不可变 sections、origin、材质地址及光源输入作严谨比较（`RayTracingLightTree.java:35`–81），避免不变光源的重建；不依赖碰撞哈希证明等价。
- 大节点已有四轮稳定 radix，小节点比较排序（同文件 320–390），树构建大体 O(E log E)，已经改善递归比较排序的 O(E log² E)。
- stable material spans 与 changed-span 上传已实现（`RayTracingMaterialBuffer.java:83`、246）。free holes >=64 使用 subtree 最大容量树，地址顺序 first-fit O(log H)（331–378），不再是所有洞线性扫描。
- 原地窗口没有重扫（`RayTracingProbe.java:990`–999）；Section merge 不再 flatten 整世界顶点（`RayTracingScene.java:604`–613）。

## CPU 可再推进，但不是主要 PT GPU 耗时修复

每次跨 chunk 仍重建整个 desiredOrigins，又重新遍历 loadedSectionOrigins（`RayTracingProbe.java:1004`、1030；`RayTracingScene.java:1109`–1139），O(R² S)。对小幅移动可只更新新旧方窗差集条带，复杂度 O(R S |delta|)；固定 R 的轴向移动一个 chunk 时只增删两个边条。必须另外处理区块 load/unload、渲染距离变化、世界 section 高度变化，否则仅“空间方窗”与实际 loaded membership 不等价。现有 queued window 避免每帧重做已很有价值，条带方案只改善移动尖峰。

LightTree bounds 可对子节点 bottom-up merge 减少重复遍历，但 power 浮点和的括号 / emitter 顺序会变化，不能保证当前 packed words 位一致。Quickselect 替代完整排序可进一步减少系数，但改变稳定 tie 顺序和树拓扑，从而改变样本与方差。不能称为保持画面逐样本一致的代数优化。LBVH / 分 section 二级树更适合增量，但须同步正向与反向 PDF。

发光 triangle 当前 power = area * PI * (emission * referenceArea / area) * importance（`RayTracingLightTree.java:142`–145）；实数可消掉 area。但 CPU 构建并非每像素热点，改浮点顺序会影响树权重、测试与 cache 同一性，收益很小，不建议优先。

## 光源采样数学检查

`RayTracingShaderRaygen.java:937` 已使用 cross-multiplied score：

p_left=(P_l / D_l)/(P_l / D_l+P_r / D_r)=P_l D_r/(P_l D_r+P_r D_l)。

已有代数消除两次除法；无需再次提出倒数优化。节点 softening 为正 0.25，防止 point 在 AABB 内时除零。极端权重下 cross products 有 overflow 风险，不过目前受限场景范围无实际复现，不能直接宣称 bug。

体积局部光的 tree/uniform 50/50 mixture 已对两分支使用完整 mixture PDF（同文件 1123–1137），不能只把采样到分支的 PDF 放进分母。

`sampleAreaLightAtIndex` 已算 direction 与 solid-angle PDF（1098–1108），legacy 体积路径又重算 direction / 距离 / 光源 cosine（1142–1156）。可复用 direction 且省再 normalize，不过目前体积 PDF 在 area*cosine 处有 max(...,1e-6)，surface PDF 没有此 clamp。直接拿 light.pdf 替换 volumePdf 不等价：小面积或掠射光将改变亮度。可将受 clamp 的体积 PDF 在首次构造时一起保存，或明确统一数值约定后同步正反 PDF；结构体增加字段也有寄存器成本。与主物理体积路径调用条件一并测量。

## 不能当等价优化的方向

双线性高度沿 UV 直线在一个 texel cell 内是 t 的二次函数，理论上可以 DDA 遍历 cell + 解二次根，而非固定 32 步。但现有算法只寻找离散采样首次跨越（可漏过子步结构），解析第一个连续交点会改变结果，且 DDA 在掠射方向跨很多 texel 时可能更慢；高度 min/max hierarchy 只在有保守上界证明时可跳过。此类属于另一个 POM 算法，需要单独对照渲染和最坏性能，而不是本轮低风险等价整理。
