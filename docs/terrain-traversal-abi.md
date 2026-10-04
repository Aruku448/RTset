# RTest terrain traversal ABI

`RayTracingTerrainTraversalAbi` and `rtest/shaders/terrain_traversal.comp` implement the optional
GPU terrain node/Hi-Z traversal path used by `RayTracingVulkanPass`. The path is experimental and
disabled by default (`terrainLodGpuTraversalEnabled=false`); CPU terrain LOD remains a separate
selection path. Check `RayTracingClientConfig` and `RayTracingProbe` for activation and fallback
behavior. ABI details below describe the GPU traversal interface, not the default renderer path.

## Node SSBO

Each node is exactly 64 bytes (`std430`), four `vec4`/`uvec4`-sized groups:

| byte offset | value |
|---:|---|
| 0 | `boundsMin.xyz`, `lodErrorPixels` |
| 16 | `boundsMax.xyz`, `lod` bit pattern |
| 32 | `parentIndex`, `firstChildIndex`, 8-bit `childMask`, reserved |
| 48 | TLAS `instanceIndex`, `materialBase`, triangle count, flags |

`NODE_READY`, `NODE_RENDERABLE`, and `NODE_HAS_BLAS` gate publication. A node descends only
when all listed children are ready/renderable, its projected size exceeds `lodErrorPixels`, and
its parent chain also descends. Otherwise the current renderable node is the conservative parent
fallback.

## Conservative screen/Hi-Z test

The closed world AABB is projected from all eight corners. A corner behind the near plane makes
the rectangle full-screen rather than falsely culling it. Hi-Z is a farthest-surface pyramid:
forward-Z uses max reduction and reversed-Z uses min reduction. The shader queries every texel
touched at a mip level whose texel footprint covers the rectangle. Occlusion is rejected only if
all queried texels are nearer than the node's nearest depth; missing/non-finite samples remain
visible.

## TLAS output

The output is a fixed-capacity array of **Vulkan `VkAccelerationStructureInstanceKHR`, exactly
64 bytes per slot**:

| byte offset | value |
|---:|---|
| 0..47 | identity 3x4 transform (world-space terrain BLAS) |
| 48..51 | `instanceCustomIndex[23:0]` = triangle `materialBase`; `[31:24]` = `TERRAIN_INSTANCE_MASK` |
| 52..55 | SBT offset in low 24 bits, facing-cull flag in high 8 bits |
| 56..63 | 64-bit BLAS device address |

Inactive slots keep a valid dummy BLAS address and mask zero. This is the important non-conflict
rule: the existing material SSBO remains a separate buffer indexed by the low 24-bit custom
index; it is never appended to, overlaid on, or read from the 64-byte Vulkan instance record.
The Java writer validates the 24-bit limit and the shader masks the value explicitly.
