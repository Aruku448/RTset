# Optional ReSTIR and conditional suffix replay

Implemented against the working-tree integrator on 2026-10-07. Both switches default off. This is an independently written Vulkan/GLSL implementation of conditional RIS principles, not a port of the authors' Falcor/DXR code.

Reference: Kettunen et al., *Conditional Resampled Importance Sampling and ReSTIR*, SIGGRAPH Asia 2023, Sections 4–7, Equations 9, 13–15 and 22. Local paper: `/home/aruku/文档/kettunen2023conditional.pdf`. [Paper DOI](https://doi.org/10.1145/3610548.3618245), [author prototype](https://github.com/NVLabs/conditional-restir-prototype). Direct-light reservoirs also build on Bitterli et al. 2020.

## Controls and scope

F9 → path tracing:

| Config | Default | Scope |
|---|---:|---|
| `restirDirectEnabled` | false | Primary static, opaque, non-delta surface emissive-light NEE |
| `restirSuffixEnabled` | false | Experimental conditional suffix replay and final gather |
| `restirCandidates` | 4 | 1–16 fresh primary area-light candidates |
| `restirSpatialNeighbors` | 2 | 0–4 previous-frame spatial proposals, plus one temporal proposal |
| `restirGatherPrefixes` | 1 | 1–4 fresh first-scatter integration prefixes |

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

1. Trace a canonical suffix with UCW 1 in the normalized uniform tape domain.
2. Search the previous frame's reprojected window for deduplicated reservoirs whose supporting secondary endpoint is within two blocks and whose normal agrees. This geometry is established before suffix sampling and does not depend on which suffix was selected.
3. Replay each borrowed tape from the current prefix. All scattering PDFs, current material evaluations, shadow rays and Russian roulette are handled by the original path integrator.
4. Evaluate the sum of MIS contributions (Equation 14), using `1/K` domain weights, and average the prefix estimates (Equation 22).
5. For the first integration prefix, resample the evaluated canonical/borrowed suffixes into a new conditional reservoir, using positive suffix contribution targets and `W = sum(target_i * W_i/K)/target_selected`.

The supporting prefix is updated by fresh Monte Carlo sampling each frame; it is never selected using the suffix contribution. The prefix's sampling dimensions and suffix resampling draws are separate, preserving the joint-UCW independence requirement in Section 4.1. Stored supporting endpoint geometry is used for search; uniform full-support MIS and identity tape shifts do not require evaluating an old prefix's reconnection BSDF.

This version traces a canonical suffix for every gather prefix. It does not apply Equation 23's canonical-suffix roulette. Low-roughness, transmissive, underwater or dynamic supporting prefixes use fresh canonical paths, without reservoir borrowing. Later suffix segments can still encounter specular, transmissive or dynamic geometry, since replay reevaluates them. Debug views disable suffix gathering.

Final gather combines indirect diffuse, specular/transmission, signed dynamic visibility residuals and generator distances before NRD. Primary lighting is included once, outside the suffix MIS sum. Camera-segment atmosphere uses independent sampling and is applied after gather. Dynamic/sky reactive signals are combined across contributions.

The optimized replay path saves the per-prefix integrator state after the first scatter, caches the secondary hit, and resumes borrowed tapes there. The camera hit is also shared across gather prefixes within one invocation. Primary lighting is evaluated once per gather prefix instead of once per borrowed tape. It still re-traces subsequent suffix segments, so this is an experimental replay path rather than demonstrated geometric-reconnection acceleration. A subsequent geometry-reconnection implementation could amortize suffix traversal, but would need explicit stored path vertices, reciprocal shift support, BSDF reevaluation and Jacobians.

## Resource and history behavior

- Binding 41: read-only previous reservoirs; 42: write-only current reservoirs; 43: 32-byte frame parameters.
- Each enabled mode uses two vec4s per pixel: sample/UCW plus supporting geometry. Both modes use four vec4s. Every pixel writes invalid entries on misses or unsupported prefixes.
- Storage is allocated lazily and released when disabled. At 1920×1080, one mode costs 126.6 MiB across both banks; both modes cost 253.1 MiB. Sizes use the RT render extent, not the display extent.
- Buffer sizes are checked against `maxStorageBufferRange`; oversized requests log a warning and use the ordinary path.
- Descriptor rebinding and buffer replacement happen after the previous submission's fence has completed. Swapping banks happens only after a submitted frame. An explicit RT-write → RT-read/write dependency makes the prior bank available to the next dispatch.
- All spatial reads use the previous bank. No invocation reads a neighboring entry being written by the same dispatch.
- Camera cuts, FSR resets, parameter/mode changes, allocations and every geometry publication invalidate history. Incremental scene publication may retain `SceneGeometry.revision()`, so publication also clears the ready flag explicitly.
- Every eighth frame resets reservoir ancestry globally. This limits correlations without clamping UCWs or rejecting selected samples according to their sample-dependent age.
- Mode-off shader specialization removes the optional descriptor accesses and loops; only tiny placeholder buffers remain.

## Verification and limits

`RestirConditionalMathTest` exhaustively checks finite-domain reservoir outcomes under changing conditional targets, joint prefix/suffix UCWs, signed contributions, fresh visibility, loss of support with zero targets and recovery with positive targets. It also checks area/solid-angle conversion, buffer sizes and history gates. This validates the estimator algebra, not a proof of unbiasedness of the complete floating-point, pseudorandom Minecraft renderer.

The actual integrated shader compiles in all four modes for both legacy and physical atmospheres. Optimized off shaders are checked for removal of optional bindings. The eight generated modules passed `spirv-val --target-env vulkan1.2`. Existing active-stage compilation and NRD output contracts pass.

Final targeted command: `./gradlew restirConditionalMathTest rayTracingShaderContractTest rayTracingAtmosphereShaderTest jar --offline --console=plain` passed. The eight final mode/atmosphere modules passed SPIR-V validation. `git diff --check` passed.

`./gradlew test jar --continue --offline` completed the remaining checks and built the JAR, but the overall suite failed at four existing contracts:

| Task | Failure |
|---|---|
| `entityRasterFallbackContractTest` | Missing source fragment `new SubmitNodeStorage()` |
| `sceneGeometryMergeContractTest` | Missing source fragment `MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY = 10_000_000;` |
| `playerAnimationContractTest` | Hand map custom submit did not enter camera-local RT capture |
| `vulkanResourceLifecycleTest` | Missing source fragment `DYNAMIC_BLAS_UPDATE_INTERVAL_FRAMES = 4` |

All four were reproduced with the pre-ReSTIR main Java files compiled into an isolated classpath and identical existing entity/scene inputs; the shared checkout was not reverted. These failures were not changed by this work.

Artifact: `build/libs/rtest-0.1.0.jar`.

No game frame timing, visual equivalence or GPU speedup is established by compiler/finite-domain tests. Installation into an instance does not update code already loaded by running game processes.

## Activation troubleshooting

`./gradlew restirActivationTest --offline` exercises the actual F9 ReSTIR button factories and callbacks against an isolated config. It verifies disabled, direct-only, both and suffix-only modes, and that enabled values survive the NeoForge config cache reload. The render-resource compatibility check compares the requested mode with the compiled mode, causing pipeline recreation when a mode changes.

ReSTIR settings are now registered separately in `config/rtest-restir-client.toml`; all five settings retain their existing names and defaults. F9 saves both config specs. Older builds only manage `rtest-client.toml`, so their correction cannot remove these independent settings. The activation test also verifies that correcting the main config leaves the enabled mode unchanged. The actual pre-ReSTIR config schema was tested in an isolated classpath: it deletes unknown ReSTIR fields placed in the main config, confirming the original conflict. Existing entries in the main config are no longer used; configure the independent file. Replacing the JAR on disk does not replace loaded classes: restart the game to load this fix.

Runtime logs distinguish configuration from GPU activation: `ReSTIR mode=0` is disabled, `1` is direct-only, `2` is suffix-only, and `3` enables both. A storage-limit warning explicitly reports a GPU fallback. A button displaying On alone does not prove GPU dispatch used the enabled mode.

Suggested game validation: start with direct-only, no spatial proposals, then enable temporal/spatial reuse. Compare at identical camera/resolution/NRD settings. Test moving/removed lights, chunk publication, camera cuts and both atmosphere paths. Test suffix-only with one gather prefix before increasing counts. Compare raw RT and NRD output separately to distinguish reservoir correlation from denoiser history.

## Prefix continuation optimization

Following the author prototype's separation of prefix and suffix work, the raygen now stores per-invocation prefix state. This scratch state is private to one pixel invocation; no additional persistent reservoir buffer is allocated. It includes ray origin/direction, throughput, medium/spectral state, primary guides and direct lighting, environment-MIS state, lobe classification, dynamic residuals and hit distances. Borrowed proposals restore this state and start at bounce 1. The new seed is applied only to suffix sampling.

The primary hit is cached across gather prefixes; the secondary hit is cached across candidates of the same prefix. These are deterministic queries within one frame/invocation, not previous-frame visibility reuse. Later suffix rays and light visibility remain fresh. This avoids repeated camera/secondary traversal and repeated primary NEE per borrowed suffix. Independent gather prefixes still reevaluate primary lighting and sample their own first scatter.

Direct RIS now retains the selected candidate's already evaluated target and updates it on every replacement. Its target is independent of the stochastic UCW, so using that value removes two identical BSDF/emission/geometry evaluations without changing the proposal, weight or random draws.

Validation: the actual shader's live prefix-local state is checked for complete save/restore; ray-depth and mode-gating contracts, activation tests, finite-domain UCW tests and all eight atmosphere/mode variants pass. Final modules pass SPIR-V Vulkan 1.2 validation. No claim of GPU image equivalence or measured speedup is made. Keeping more prefix state live may affect register pressure; fixed-camera GPU comparisons remain necessary. Full hybrid geometric reconnection and Equation 23 canonical roulette remain future work.
