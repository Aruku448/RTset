# Persistent RT World 研究分支

分支：`codex/persistent-rt-world-research`

代码基线：`569424f8aea3407599a2f7bb320a62807af9df69`。

## 研究目标

用静态 Minecraft 场景验证：持续维护可复用照明和可见表面历史，使用最新显示相机重投影，并仅修补必要照明，能否在可接受画质下减少追踪成本和相机显示延迟。

本次建立研究分支，只提交设计与已有性能证据。原始工作区仍在 main，其未提交的辐射缓存实验没有搬入本分支。后续若采用该实验，应先引入并独立核对，而不能假定正式 raygen 已有缓存。

## 首版范围

- 静态不透明几何，固定太阳与环境照明。
- 先纯旋转，再小幅平移；固定分辨率，适度 guard band。
- 手持物与 HUD 保持当前显示更新。
- 镜面、透明、水、POM、体积与复杂动态实体暂不作为复用对象；不适用区域保持完整路径或从测试场景排除，并在结果中注明。
- 实体阴影不能通过历史照明间接进入 NRD；不能因为 receiver 是静态表面就判定照明是静态。

## 分阶段可验收成果

| 阶段 | 工作 | 验收 |
| --- | --- | --- |
| 0：成本拆分 | 增加主可见性、阴影、GI、体积和 GPU 排队的测量；保留固定参考相机轨迹 | 能解释剩余成本，统计覆盖 AS 与提交等待；不要把现有 trace 总时间当作主射线时间 |
| 1：旋转重投影 | 单套已完成表面历史，最新 pose warp，深度竞争与覆盖 mask | 与完整当前视角参考比较，边缘有明确无效标记，记录 pose 延迟与覆盖率 |
| 2：平移可见性 | 比较当前 raster G-buffer 或全屏廉价 primary rays；深度、法线、材质/代次验证 | 新遮挡不能被旧背景错误覆盖；区分主射线比例与昂贵照明修补比例 |
| 3：局部修补 | compact repair list；对无效照明补直接光/GI，缓存未命中回退 | primary hit 后也有完整照明来源；实际射线数量和各阶段成本可追踪 |
| 4：预算与快照 | 分块 RT、read/write/ready 快照、epoch 与 age、短提交；显示消费完成状态 | 无资源读写竞争；统计 deadline、backlog 和 stale age；不以拖欠工作换取表面高 FPS |
| 5：持久照明 | 引入真实路径目标、空间缓存、局部失效、NRD 更新合同 | 不重复计入 NEE/发光或反照率；检查局部漏光、材质变化和场景变化 |

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

三组对照：完整当前视角参考、只做旧图像 warp、加入可见性与按需修补。全部使用相同分辨率、FOV、照明和可重复相机轨迹。

## 现有约束与证据

详见 [可行性分析](persistent-rt-world-feasibility-2026-10-05.md) 和 [原始性能检查](rt-other-overheads-2026-10-05.md)。当前 36–42 ms trace 不能直接保持 30–60 次完整更新/秒；宽 FOV 保持中心采样密度时有显著像素成本。原始日志中的 NRD ON/OFF 窗口不是受控 A/B，不作为 NRD 单独成本结论。

本分支后续实验结果记录到 `docs/profiling`；未经真实测量不宣称游戏帧率收益。
