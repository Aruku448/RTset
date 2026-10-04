# 几何发布与光源复用优化

## 基于最近监测落实的改动

1. 材质GPU缓冲由精确容量改为 bounded growth：所需容量外预留约50%，额外最多64MiB，微小buffer至少预留64KiB；已有容量足够不缩小。ByteBuffer映射整数地址上限附近减少预留。材质descriptor范围仍为当前live字节，不暴露未初始化预留行。
2. 光源缓冲同样预留容量。灯数据变化但容量足够时，完成上帧fence后复用allocation；以1024个int/4KiB页比较，只写变化页。descriptor读取的有效树范围由已初始化header/count/offset控制。失败前注册回滚，恢复旧数组的变化页；复用对象不被当作候选新资源关闭。
3. 灯内容、源顺序、相对原点和发光材质地址不变，仅不发光材质span改变时，不再完整建树。复制现有节点/灯/forward/reverse表，只调整material-to-emitter长度和leaf-table偏移。新增map行填-1，缩小时拒绝截断仍有效的灯映射。没有改树排序、概率、功率、MIS或灯数量。
4. 发布日志新增blas_acquire_ms、scene_buffers_ms、material_write_ms、light_pbr_upload_ms、descriptors_ms、retire_ms，及material live/capacity、光源allocation复用标志和容量。均为CPU墙钟阶段，不能误读为GPU执行时长。先前总duration仍保留、原解析器兼容。

## 验证

- 新的不发光区块增长复用fixture在旧实现先失败：Dark section growth must reuse emitter hierarchy and resize only lookup。修复后与完整build的打包words完全一致；已有随机150次编辑、部分snapshot拒绝、浮点raw-bit/顺序/材质变化防护仍通过。
- 8万灯合成fixture：完整build中位27.527ms，lookup resize中位2.277ms，约12.09倍。4次预热、9次测量；每次输出数组与完整build逐项相等。仅测此特定不发光增长内核，不是最新世界回放或整帧加速。
- 1–256MiB、每次增加1MiB的容量仿真：12次分配，旧精确增长256次。验证不缩小、覆盖required、64MiB预留上限及映射边界。
- GPU上传生产writer使用Java-owned mapped buffer验证：变化页、追加后缀、无变化不flush、缩小后的旧数据恢复。资源生命周期测试继续通过，但没有GPU故障注入。
- 完整check和jar构建通过，59 tasks；包含NRD/大气/天空CDF/地形shader契约。

## 边界与下一步

最多材质64MiB＋光源64MiB额外预留，换取少分配；不宣称零内存代价。已有fence保护保留。

真正灯几何/功率变化、灯地址变化或场景原点变化仍完整重建树；没有实现分层section树或GPU refit。该轮先削减CPU重复建树和GPU buffer重建/上传，未新增compute建树kernel。下一次运行用分项日志确定发布180ms主因，再决定分层树与GPU计算迁移，不能把合成12倍收益套用所有移动更新。

已保留现有实体阴影绕过NRD、PBR零贡献采样降噪、昼夜曲线和地形深度修复。本轮部署后需重启并采集同路径对比。
