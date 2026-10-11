# Optional ReSTIR and conditional suffix replay

> **文档状态：维护入口** · 整理核对：2026-10-11。当前事实以源码为准；旧验证不自动延续。[文档索引](README.md)

Initial implementation: 2026-10-07; source/document review: 2026-10-11. Both switches default off. This is an independently written Vulkan/GLSL implementation of conditional RIS principles, not a port of the authors' Falcor/DXR code.

Reference: Kettunen et al., *Conditional Resampled Importance Sampling and ReSTIR*, SIGGRAPH Asia 2023, Sections 4–7, Equations 9, 13–15 and 22. Local paper: `/home/aruku/文档/kettunen2023conditional.pdf`. [Paper DOI](https://doi.org/10.1145/3610548.3618245), [author prototype](https://github.com/NVLabs/conditional-restir-prototype). Direct-light reservoirs also build on Bitterli et al. 2020.

## Controls and scope

F9 → path tracing:

| Config | Default | Scope |
|---|---:|---|
| `restirDirectEnabled` | false | Primary static, opaque, non-delta surface emissive-light NEE |
| `restirSuffixEnabled` | false | Experimental conditional suffix replay and final gather |
| `restirCandidates` | 11 | 1–16 fresh primary area-light candidates |
| `restirSpatialNeighbors` | 2 | 0–4 previous-frame spatial proposals, plus one temporal proposal |
| `restirGatherPrefixes` | 2 | 1–4 fresh first-scatter integration prefixes |
| `restirSingleDonorGather` | false | Later prefixes select one historical group with probability compensation |

The switches are independent and can be enabled together. Sun, moon, sky NEE and the configured GI depth remain in the existing integrator. Changing the enabled mode recreates the RT pipeline so shader optimization removes inactive paths (including the suffix integrator in direct-only mode). Changing its parameters invalidates reservoirs and requests an FSR/NRD reset.

## Primary direct-light reservoirs

Generate candidates using the existing light tree and uniform triangle-area sampling. A candidate's initial area-measure UCW is `W_A = area / selectionPdf`. Reservoir selection uses a nonnegative unshadowed contribution target plus a strictly positive support floor. Rejected hemispheres, backfaces and black emission texels still retain proposal support: deleting them would prevent correct transfer to a different receiver.

The fresh reservoir averages its M candidate weights. Reproject the current static primary hit into the previous camera, then inspect the temporal reservoir and deduplicated spatial proposals. Accept them using receiver geometry alone: depth-scaled world-distance and normal agreement. Combine the fresh reservoir and accepted historical UCWs with uniform MIS domain weights `1/K`:

```
selectionWeight_i = target_current(sample_i) * W_i / K
W_selected = sum(selectionWeight_i) / target_current(selected)
```

Every accepted input covers the complete light-area integration domain because its source target has positive support. Consequently uniform MIS is valid without evaluating the reservoir's unknown marginal PDF. Historical candidate counts are not multiplied again: each stored W is already a normalized UCW. The implementation does not claim those correlated inputs are independent rays.

Convert the selected area UCW into the existing solid-angle NEE interface using `proxySelectionPdf = area / W_A`. The resulting `p_omega = r^2 / (W_A * abs(cos_light))` makes the existing visibility/BSDF evaluation contribute `f_A * W_A`. This proxy must not be interpreted as the actual PDF that generated the reservoir sample.

Only the selected valid light requires a fresh shadow ray (with the existing additional static-visibility ray when separating dynamic shadows). No old visibility is reused. The current primary area-light energy and secondary BSDF-emitter suppression policy is retained.

Legacy RGB volume fog formerly consumed the primary sample's original selection PDF. With direct ReSTIR enabled it instead draws its own tree/uniform-mixture volume candidate and evaluates current visibility. Physical-atmosphere volume sampling is already independent. This prevents feeding a stochastic reservoir weight into an unrelated conditional volume proposal.

## Conditional suffix replay and final gather

This is a random-space shift variant of conditional suffix ReSTIR. It is not the authors' geometric reconnection implementation and does not implement their supporting-prefix GRIS or hybrid low-roughness shift.

A fresh integration prefix consists of the camera path through the primary vertex and its first BSDF scatter, ending at the secondary surface. Its random dimensions remain separate from the suffix tape. Suffix random numbers, starting with direct-light queries at the secondary surface and continuing through scattering, emission and Russian roulette, are generated from a saved 32-bit tape seed. All candidates at one integration prefix use exactly the same prefix and reevaluate the suffix against the current scene.

The shift maps a tape to the identical tape at another prefix. In this seed integration domain its Jacobian is one and the seed support is shared across receivers, including paths that terminate early. The original conditional contribution weight can therefore integrate a new receiver's suffix function. This trades extra ray tracing for simpler support/Jacobian handling and avoids stale path geometry or visibility.

For each fresh prefix:

1. Establish the secondary endpoint and search the previous frame's reprojected window for reservoirs within two blocks whose supporting normal agrees. This happens before suffix random draws and does not depend on the chosen suffix.
2. The first prefix always traces a canonical suffix with UCW 1 and merges borrowed suffixes. Later prefixes use borrowed suffixes alone when available; unsupported prefixes and history misses trace a fresh canonical suffix. This relies on the **full common seed support of identity replay**, not a general property of geometric path reconnection.
3. Coalesce historical reservoirs with identical 32-bit tape seeds by adding their UCWs, then replay each resulting tape once in full gather. Optional single-donor mode selects one coalesced group uniformly on later borrowed-only prefixes, multiplying its contribution by the number of groups while retaining the original domain denominator. All scattering PDFs, current material evaluations, shadow rays and Russian roulette are handled by the original path integrator.
4. Evaluate the sum of MIS contributions (Equation 14), using `1/K` domain weights, and average the prefix estimates (Equation 22). `K` is the number of original accepted donor domains plus one if canonical sampling is used, not the number of unique replayed tapes.
5. For the first integration prefix, resample the evaluated canonical/borrowed suffixes into a new conditional reservoir, using positive suffix contribution targets and `W = sum(target_i * W_i/K)/target_selected`.

The supporting prefix is updated by fresh Monte Carlo sampling each frame; it is never selected using the suffix contribution. The prefix's sampling dimensions and suffix resampling draws are separate, preserving the joint-UCW independence requirement in Section 4.1. Stored supporting endpoint geometry is used for search; uniform full-support MIS and identity tape shifts do not require evaluating an old prefix's reconnection BSDF.

This version does not implement Equation 23's canonical-suffix roulette. Its later-prefix reuse-only estimator instead renormalizes uniform MIS over full-support seed UCWs. This optimization must be revisited before adding partial-support geometric shifts. Low-roughness, transmissive, underwater or dynamic supporting prefixes use fresh canonical paths, without reservoir borrowing. Later suffix segments can still encounter specular, transmissive or dynamic geometry, since replay reevaluates them. Debug views disable suffix gathering.

Final gather combines indirect diffuse, specular/transmission and signed dynamic visibility residuals before NRD. Generator hit distances are collected once per independent prefix without UCWs: diffuse averages valid diffuse-proposal observations, while specular uses the minimum positive specular-proposal distance. Radiance still partitions by evaluated BSDF lobes, independently of guide classification. Missing lobe guides remain zero rather than borrowing a total-light DI emitter distance. Primary lighting is included once, outside the suffix MIS sum. Camera-segment atmosphere uses independent sampling and is applied after gather. Dynamic/sky reactive signals are combined across contributions.

The optimized replay path saves the per-prefix integrator state after the first scatter, caches the secondary hit, and resumes borrowed tapes there. The camera hit and deterministic primary area-light result (including current-frame RGB visibility and dynamic residuals) are shared across gather prefixes within one invocation. Eligible static, opaque, rough primary prefixes also share sun/moon/sky NEE and signed dynamic residuals across prefixes; unsupported primary surfaces retain per-prefix evaluation. Their NEE sample count and enable flags remain consistent with the original continuation MIS. It still re-traces subsequent suffix segments, so this is an experimental replay path rather than demonstrated geometric-reconnection acceleration. A subsequent geometry-reconnection implementation could amortize suffix traversal, but would need explicit stored path vertices, reciprocal shift support, BSDF reevaluation and Jacobians.

## Resource and history behavior

- Binding 41: read-only previous reservoirs; 42: write-only current reservoirs; 43: 32-byte frame parameters. History x holds independent DI/GI validity bits; history w holds optional gather flags.
- Each enabled mode uses two vec4s per pixel: sample/UCW plus supporting geometry. Both modes use four vec4s. Every pixel writes invalid entries on misses or unsupported prefixes.
- Storage is allocated lazily and released when disabled. At 1920×1080, one mode costs 126.6 MiB across both banks; both modes cost 253.1 MiB. Sizes use the RT render extent, not the display extent.
- Buffer sizes are checked against `maxStorageBufferRange`; oversized requests log a warning and use the ordinary path.
- Descriptor rebinding and buffer replacement happen after the previous submission's fence has completed. Swapping banks happens only after a submitted frame. An explicit RT-write → RT-read/write dependency makes the prior bank available to the next dispatch.
- All spatial reads use the previous bank. No invocation reads a neighboring entry being written by the same dispatch.
- Camera cuts, FSR resets, parameter/mode changes, allocations, scene-generation changes and origin rebases invalidate history. Incremental publication in the same generation/coordinates may retain seed-only suffix history. DI additionally requires exact light-data equality; changed emitters clear only DI. Geometry compatibility and fresh current-scene replay remain required.
- Every eighth frame resets reservoir ancestry globally. This limits correlations without clamping UCWs or rejecting selected samples according to their sample-dependent age.
- Mode-off shader specialization removes the optional descriptor accesses and loops; only tiny placeholder buffers remain.

## Verification and known limitations

Exponent scaling protects intermediate products/sums; an output UCW outside the persistent float range invalidates that reservoir. This finite-precision failure path is not an unbiased clamp, and arbitrary HDR radiance overflow is not solved.

Finite-domain estimator tests are not a proof of unbiasedness of the complete floating-point, pseudorandom Minecraft renderer. No fixed-camera game comparison or GPU speedup is established for the latest gather optimization. A JAR path or old hash does not establish what a running game loaded.

## Activation troubleshooting

ReSTIR settings live in `config/rtest-restir-client.toml`; F9 saves the independent ReSTIR spec and the main client spec. `restirSingleDonorGather` defaults off. Main-config ReSTIR entries are not used. A mode change recreates the RT pipeline; replacing a JAR requires restarting the game to load its classes.

Runtime logs distinguish configuration from GPU activation: `ReSTIR mode=0` is disabled, `1` is direct-only, `2` is suffix-only, and `3` enables both. A storage-limit warning explicitly reports a GPU fallback. A button displaying On alone does not prove GPU dispatch used the enabled mode.

Suggested game validation: start with direct-only, no spatial proposals, then enable temporal/spatial reuse. Compare at identical camera/resolution/NRD settings. Test moving/removed lights, chunk publication, camera cuts and both atmosphere paths. Test suffix-only with one gather prefix before increasing counts. Compare raw RT and NRD output separately to distinguish reservoir correlation from denoiser history.

## Prefix continuation optimization

Following the author prototype's separation of prefix and suffix work, the raygen now stores per-invocation prefix state. This scratch state is private to one pixel invocation; no additional persistent reservoir buffer is allocated. It includes ray origin/direction, throughput, medium/spectral state, primary guides and direct lighting, environment-MIS state, lobe classification, dynamic residuals and hit distances. Borrowed proposals restore this state and start at bounce 1. The new seed is applied only to suffix sampling.

The primary hit is cached across gather prefixes; the secondary hit is cached across candidates of the same prefix. These are deterministic queries within one frame/invocation, not previous-frame visibility reuse. Later suffix rays and light visibility remain fresh. This avoids repeated camera/secondary traversal and repeated primary NEE per borrowed suffix. Independent gather prefixes sample their own first scatter. Since primary area-light random dimensions do not depend on the gather-prefix index, its complete result is shared within the pixel invocation; sun, moon and sky primary terms now share a separate invocation-local cache on eligible prefixes. Current-frame visibility is never borrowed across frames.

Direct RIS now retains the selected candidate's already evaluated target and updates it on every replacement. Its target is independent of the stochastic UCW, so using that value removes two identical BSDF/emission/geometry evaluations without changing the proposal, weight or random draws.

Keeping more state live can affect register pressure. Full hybrid reconnection and Equation 23 canonical roulette remain unimplemented. Consult the [stage implementation record](restir-follow-up-implementation-2026-10-11.md) only when checking build evidence; older audits are discoverable through the [historical catalog](CATALOG.md).
