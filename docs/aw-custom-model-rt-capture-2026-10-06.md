# AW custom avatars in the Vulkan RT scene

## Evidence

The live RTest log recorded `player-mesh-over-capacity[entity.minecraft.player]` at 23:39:42 and 23:41:56. CPU admission and GPU material ranges both allowed only 512 model triangles.

The real deferred custom renderer seam initially failed `customEntityGeometryTest` with `TRIANGLES blend=false expected=1 captured=0`. Capture always grouped four vertices. After topology support, the alpha fixture still bypassed capture because all non-emissive blended living layers were filtered out.

## Repairs

- Custom entity, block entity and item captures use the submitted primitive topology. Independent triangles and quads are supported; lines are forwarded but do not become surface triangles.
- Recognize AW pipeline identifiers (`armourers_workshop:pipeline/*`) and admit their blended world skin layers. Outline duplicates remain excluded.
- Preserve vertex alpha and direct texture selection/PBR lookup. AW blended material uses the existing RT transmission contract. Dynamic texture descriptor slots are not interpreted as RGB absorption coefficients. Zero-alpha dynamic transmissive surfaces are ignored by primary any-hit.
- Merge custom layers before testing whether the player mesh is empty, allowing a fully replaced/hidden vanilla body.
- Raise the shared CPU/GPU model budget to 8192 triangles. At 64 slots this adds approximately 52.5 MiB to the reserved GPU material range, excluding allocator copies and actual BLAS resources. Larger meshes still explicitly fall back; there is no unlimited-model claim.
- Keep unowned GUI previews outside world capture.

## Checks

Passed:

```sh
./gradlew customEntityGeometryTest playerBlasCommandTest dynamicTlasInstanceContractTest rayTracingShaderContractTest jar --console=plain
```

Shader contract compiles the active Vulkan ray stages with shaderc. The custom regression exercises a deferred callback after its entity owner scope ends, triangle/quad counts, alpha, absent/hidden vanilla bodies and GUI exclusion. These checks do not establish actual GPU pixel correctness.

`playerAnimationContractTest` has a separate existing hand-map failure (`Hand map custom submit did not enter camera-local RT capture`). The same failure was reproduced after reverting this repair's item custom-capture changes; it is not reported as passed.

## Installed build

SHA256: 6184b7ea9ab2162a29d34fbf756256d419d779e2e445565fe750916fa419e289

The currently running process must restart to load the replacement jar. In-game verification still needs the same custom avatar in third person, reflections/shadows, and the resource preview. GPU transparency uses the RT transmission model; exact raster alpha-blending pixel equivalence is not claimed.

## Follow-up: complete avatar partitioning

The next live session still recorded `entity-mesh-over-capacity[entity.armourers_workshop.mannequin]` and `player-mesh-over-capacity[entity.minecraft.player]` at 23:51. Raising a single slot to 8192 did not admit these avatars.

Player and generic entity models now partition complete captured geometry into 8192-triangle chunks. Each chunk has a stable owner/ordinal identity, its own BLAS cache key and material slot, and the same world/first-person transform and ray mask. UVs, tint, alpha, texture selector and all material rows copy without reordering. Entity representation remains one owner. Whole-model slot preflight prevents partially admitted avatars; the existing 64 total dynamic slots are still a finite limit. GPU material reservation is unchanged from the previous build.

A 24593-triangle fixture exercised the actual admission helper and initially failed because the Vulkan model cache key truncated chunk identities to 32 bits. Cache keys now retain chunk ordinal bits. Regression covers all triangles/materials, independent cache identities, entity deduplication, common transform and atomic overflow.

Passed: `dynamicModelChunksTest customEntityGeometryTest dynamicContractTest dynamicTlasInstanceContractTest playerBlasCommandTest rayTracingShaderContractTest jar`. Active Vulkan shader stages compiled successfully.

Runtime reports `RTest partitioned complete entity model: owner=..., type=..., triangles=..., parts=...` on first multi-part admission or when its part count changes.

Installed SHA256: c91c7c7d659d2815eef50a16c756823bdb4e543f2c064282280909374da837d5

Actual in-game appearance remains pending restart of the user's active session. No native pixel equivalence or visual-success claim is made from CPU regression checks.
