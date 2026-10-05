# Persistent RT World 研究分支

分支：`persistent-rt-world-research`

远端：[Aruku448/RTset 的研究分支](https://github.com/Aruku448/RTset/tree/persistent-rt-world-research)。

代码基线：`569424f8aea3407599a2f7bb320a62807af9df69`。

## 研究目标

以 **Persistent RT World + Real-Time View Rendering** 为主路线：RT 按预算更新空间 GI/照明缓存，高频显示层使用当前相机与当前实体状态计算可见性、直接光和最终着色，查询持久照明并修补缺失贡献。重投影用于照明历史复用和重建。

用户最新补充将摄像机自由移动、旋转和实体实时呈现列为目标。完整设计见 [多频渲染架构](persistent-rt-world-multirate-design.md)。此前只读旧深度的旋转原型保留为可选对照实验；主路线首先取得当前可见性。

当前研究分支只包含设计与已有性能证据，尚未实现多频运行管线。原始工作区仍在 main，其未提交的辐射缓存实验没有搬入本分支。后续若采用该实验，应先引入并独立核对，而不能假定正式 raygen 已有缓存。

## 首版范围

- 先以静态不透明世界、固定太阳与环境照明测量，再加入简单实体的当前姿态与动画；实体的全场景 GI 影响作为后续研究。
- 当前相机平移、旋转和 FOV 参与每个显示帧的可见性计算。主路线不要求通过超宽 RT FOV 才能自由移动。
- 简单实体、手持物与 HUD 按当前显示帧呈现；动态阴影初期维持实时正确路径。
- 镜面、透明、水、POM、体积与草木特殊散射暂不作为低频颜色复用对象；保持当前视角路径或在早期测量中明确排除。
- 实体阴影不能通过历史照明间接进入 NRD；不能因为 receiver 是静态表面就判定照明是静态。

## 分阶段可验收成果

| 阶段 | 工作 | 验收 |
| --- | --- | --- |
| 0：成本拆分 | 增加主可见性、阴影、GI、体积和 GPU 排队的测量；保留固定参考相机轨迹 | 能解释剩余成本，统计覆盖 AS 与提交等待；不要把现有 trace 总时间当作主射线时间 |
| 1：当前视图 | 对照当前 raster G-buffer 与全屏廉价 primary；拆分直接光和间接光输出；固定参考轨迹自由移动 | 可见性匹配完整当前视角；不是 warped 旧背景；记录两种主可见性成本 |
| 2：持久漫反射照明 | 引入真实路径尾部目标与可查询空间缓存；只发布完成快照 | 当前表面查询正确照明，未命中回退；反照率/NEE/发光各计一次；可以新视角查询 |
| 3：预算与局部修补 | compact 无效照明列表，短批次更新，read/write/ready、epoch/age | 无读写竞争或长任务阻塞；统计 deadline、backlog、stale age 与实际射线成本 |
| 4：简单实时实体 | 当前姿态/动画/可见性、当前直接光和动态阴影；低频更新实体间接影响 | 运动与边缘不依赖慢照明时钟；实体阴影维持独立处理；测量 GI 滞后伪影 |
| 5：扩展与事件失效 | 方块改动、局部 BVH、区域缓存失效、环境变化；逐项加入特殊材质 | 新几何立即触发可见性与失效；检查远处影响与室内外漏光；不无条件复用低频结果 |

每阶段都保留实验开关和完整参考路径。先将 120 Hz 作为测量目标，只有 deadline、延迟与画质数据支持后再评价 240 Hz。

## Buffer 与时间语义

世界状态与屏幕历史分开：BVH/材质/空间照明具有 scene/light generation；surface history 保存采样 pose、实际 RT sample index、表面身份与时间戳；display state 保存相邻显示 pose 与 display index。

计划中的 read/write/ready 资源使用明确 completion 依赖。不能移除当前 fence 之后立即重用 mapped buffer/TLAS，也不能在 shader 正在查询时原位更新同一个缓存。普通跨帧 display motion 与两次真实照明采样之间的 motion 不应混为一谈。

重建保留表面照明和新视线大气的组合合同：`surface * T_new + L_new`。不重复降噪同一批样本来提高采样 confidence。初期优先避免双重 temporal filter，将 FSR/NRD 接入作为明确的后续合同验证。

## 必测指标

- 实际 primary/shadow/GI/volume traced rays；昂贵着色 repair ratio。
- visibility、warp、compact、repair、reconstruction、缓存更新/查询各阶段 GPU time。
- scene/AS/上传成本，queue/fence 等待和完整 GPU critical path。
- 输入到显示延迟，display deadline miss，快照 age 的 p50/p95/max。
- hole 与错误覆盖分别计数；repair backlog；静止时失效误判率。
- 对照完整当前视角图像的误差、边缘残影、遮挡变化、漏光与细节稳定性。

主路线三组对照：完整当前视角参考、当前可见性加冻结照明、当前可见性加预算更新/按需修补。旧图像 warp 可另作辅助对照。全部使用相同分辨率、FOV、照明和可重复相机轨迹。

## 现有约束与证据

详见 [可行性分析](persistent-rt-world-feasibility-2026-10-05.md) 和 [原始性能检查](rt-other-overheads-2026-10-05.md)。当前 36–42 ms trace 不能直接保持 30–60 次完整更新/秒；宽 FOV 保持中心采样密度时有显著像素成本。原始日志中的 NRD ON/OFF 窗口不是受控 A/B，不作为 NRD 单独成本结论。

本分支后续实验结果记录到 `docs/profiling`；未经真实测量不宣称游戏帧率收益。
