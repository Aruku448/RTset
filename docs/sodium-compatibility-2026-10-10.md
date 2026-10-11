# Sodium terrain compatibility (2026-10-10)

> **文档状态：阶段变更记录** · 整理日期：2026-10-11。
> 描述当时修改与验证；旧默认值、命令结果、安装 hash 和“当前/未部署”均限于该阶段，不是当前实现规范。 [分类索引](README.md)

Supported target: NeoForge Sodium `0.9.2+mc26.2`. The optional Mixin plugin enables the
whole bridge only for this exact version. Sodium is compile-only and is not bundled.
Other versions and instances without Sodium retain RTest terrain capture.

## Integration

* A task-local collector copies block quads immediately before Sodium compresses vertices,
  after model transforms, offsets, tint and invalid translucent-quad rejection.
* Snapshots retain block states, sprite identities, uncompressed UVs and pre-lighting colors.
  Raster AO and baked lighting do not enter RT materials. Existing PBR companion maps,
  glass optics, vegetation properties and block emission use the normal RT material writer.
* Only meshing outputs accepted by Sodium publish snapshots. Cancelled tasks release their
  collectors and output-side snapshots. Epoch/generation checks reject invalidated builds.
* Block/chunk invalidation, Sodium rebuild scheduling, section membership, renderer disposal
  and resource reload clear affected snapshots. Late results request incremental RT recapture.
* The snapshot LRU allows at most 8,192 sections and an estimated 128 MiB. Each build allows
  at most 32,768 quads. This adds CPU copying and memory use to Sodium meshing, including
  before RT activation, so future scene capture can reuse already-built geometry.

## Conservative fallback

Fluid sections now retain the ordinary block component and append RTest fluid geometry
without collecting block models a second time, including for waterlogged blocks. A scoped
fluid-render hook and the final mesh-buffer push hook account for fluid vertices separately;
captured block vertices plus known fluid vertices must equal submitted vertices, and the
accepted output must have the same count. Unknown geometry cannot be mistaken for fluid.

Whole sections still use existing capture for mesh appenders, unsupported atlases, invalid
values, topology changes, or model emission levels 1–14 that Sodium does not retain separately
from baked lighting. Accepted unsupported rebuilds remove previous snapshots. Fluid boundaries
and absorption continue through RTest fluid capture. Per-vertex block colors are averaged per
triangle to fit the existing RT material ABI.

Generation-table rollover rejects older in-flight builds without deleting unrelated published
meshes. Unbuilt layers no longer create invalidation records; every task registers its token
before it starts, so stale pending results remain rejectable. Chunk neighborhood invalidation
is retained for correct face boundaries, with the duplicate center-chunk call removed. The
entry limit is now 8,192; the estimated memory budget remains 128 MiB.

Full and incremental captures both log `RTest terrain reuse`, including processed/sodium/
hybridFluid/compiled/cpu section counts and cumulative cache publication, stale-result,
eviction, invalidation, lookup and categorized failure counters.

This bridge reuses terrain meshing work; Vulkan acceleration-structure construction and ray
traversal are unchanged. No FPS benefit is claimed without measurement.

## Build status

Java compilation and JAR/source packaging completed. An isolated NeoForge/Sodium client
reproduced the inherited `state` shadow crash. The BlockRenderer mixin now extends
AbstractBlockRenderContext and accesses its protected state normally. A temporary client
subscriber loaded all eight bridge target classes through the real Mixin transformer; the
same run failed before the fix and passed after it. The subscriber was removed before
packaging. Full-world visual correctness and performance remain unverified.
Restart the RTest instance after replacing its JAR. Scene-capture logs report the number
of sprite-aware Sodium sections actually reused; the optional plugin logs its version gate.

The hybrid update passed an isolated cache probe for generation rollover, stale-result
rejection, block/fluid separation, incomplete topology rejection and scope cleanup. All eight
Mixin targets loaded successfully in an isolated NeoForge/Sodium client. The temporary
subscriber was removed before packaging. Full-world fluid visuals and measured reuse gains
require a restart and observation in the actual RTest instance.

## Incremental light-tree and temporal-history fix

Live logs showed repeated `temporalReset=true` on incremental geometry publication, including
updates with the same emitter count. Publication requested an FSR reset, which also forces
an NRD restart through the shared frame token. This repeatedly exposed raw first-frame noise.

Section replacement now preserves existing section order and retains identical section
objects after fingerprint prechecks plus full array comparison. Identical deltas return the
old scene, avoiding unnecessary GPU publication and emitter-order changes. Actual emitter
changes still rebuild the hierarchy; explicitly different emitter order remains incompatible.

ReSTIR reservoir history is still invalidated whenever geometry is published because emitter
indices may have changed. NRD/FSR image history is retained for incremental updates with the
same scene reset revision. Full scene revisions, camera cuts, atlas changes and existing
configuration discontinuity handling still reset image history. Entity-local history flags
remain when dynamic material ranges are reinitialized. Depth/normal reprojection and NRD
anti-lag handle newly visible surfaces and lighting changes; reflection lag and visual noise
still require observation in the actual world.

`lightTreeReuseMathTest` and `incrementalGeometryContractTest` passed. These checks cover
immutable merge, light-tree packed-word comparisons and publication policy; they do not
validate GPU rendering or the reported visual flicker. Incremental publication logs should
now show `temporalReset=false` and `PRESERVE_INCREMENTAL_HISTORY`.
