# 区块构建与地形遍历数学复查

## 范围

检查 Scene 的 section 原点/窗口、CompiledSectionMeshCache 的三角展开、TerrainLod 的父节点/界盒/SAT、TerrainTraversalAbi 的投影与 Hi-Z，以及实际 GPU terrain traversal/Hi-Z shader。LOD 构建算法没有改为另一种近似。

## 已复现与修复

1. CPU selectNodes 调用 projectBounds 时丢失 DepthConvention。反向 Z 因使用正向近深度可误剔除；现显式传递约定。
2. CPU/GPU 使用固定 depthFar>=0 && depthNear<=1 判断视锥，反向 Z 下跨近/远裁剪面的 AABB 会被误判。现用无方向区间重叠 max(depthNear,depthFar)>=0 && min(...)<=1，并按各自约定保存最近深度供 Hi-Z。
3. 投影极值以视口/深度边界初始化，导致完全位于视锥外的节点仍可能被当作相交；改为正负有限最大值，输出坐标和深度双向 clamp，与 GPU 一致。clip.w<=0 仍采用保守全视口回退。
4. Vulkan 的奇数 mip 尺寸向下取整，但 GPU 固定 2x2 归约遗漏末行/列。现每个目标 texel 覆盖源区间 [floor(i*S/D),ceil((i+1)*S/D))，保守包含所有重叠源像素。CPU Hi-Z 同步为 floor mip 与相同覆盖区间。偶数尺寸仍为 2x2；奇数尺寸局部最多 3x3，允许重叠以避免漏取可见深度。

当前实例 terrainLodEnabled/terrainLodGpuTraversalEnabled 均开启；当前渲染日志为 1505x825，Hi-Z 从该尺寸创建，因此奇数尺寸问题关联实际运行路径。

## 未发现新的可复现错误

- 原点与父节点使用 floor/floorDiv，对负坐标不采用向零截断。
- Node bounds 从 double 乘法与 long 的 key+1 构造；局部三角边在添加世界原点前计算，避免大坐标吞掉边长。
- 体素 SAT 包括盒轴、三角面法向和九个边叉轴；零叉轴不会错误剔除。
- TLAS 与相机上传使用相对场景原点坐标；当前世界范围内整数 section 位移未发现溢出。
- 材质缓存字节统计使用 long；已有 mesh 转换/LOD/ABI 测试继续通过。

以上不是对所有输入或整个构建链的形式化证明。

## 验证

terrainTraversalAbiTest 原实现先失败：Reversed-Z node crossing near plane must stay visible with clamped depth；随后新增奇数尺寸 fixture 先失败：Odd Vulkan mip must include the last source texel。两项修复后通过，覆盖跨近/远裁剪面、全区间外、视口外、反向遮挡选择以及 5x1 末像素可见性的归约。GPU shader 同步改动由实际 shaderc 编译与契约检查验证。

完整 `./gradlew check jar --offline` 通过，59 tasks。未做 GPU 像素回放或 FPS 基准，不宣称实际漏显全部消失或固定性能收益。实体阴影/NRD 与近期曲线设置保持现有实现。
