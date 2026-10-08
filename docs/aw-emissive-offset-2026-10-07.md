# AW emissive identification and surface offset

AW port render pipelines carry the EMISSIVE shader define and use custom pipeline identifiers. The RT adapter previously recognized vanilla EYES/entity emissive pipelines and names only. It now recognizes the EMISSIVE flag for armourers_workshop:pipeline/*.

As requested, native AW emissive custom geometry uses zero displacement and preserves original posed vertex positions in the RT mesh. This applies to entity, block entity and item custom capture. Non-emissive vertices remain unchanged, and vanilla emissive separation remains 0.001. Raster delegates receive original positions; this experiment adjusts RT geometry, not authored UVs or source PNG files. Companion-map-only emissive patches on otherwise non-emissive surfaces are not displaced by this pipeline-layer rule.

Passed customEntityGeometryTest (including EMISSIVE recognition, zero displacement and retained radiance marker), dynamicModelChunksTest and rayTracingShaderContractTest. Built and installed rtest-0.1.0.jar with backup. Restart and inspect the same avatar to evaluate visual separation; actual Vulkan pixels remain unverified.

User corrected the requested displacement from 0.1 to 0.00625 world units (0.1 model pixels at 16 pixels per block).

User subsequently requested no movement. AW emissive offset is now 0; emissive identification and radiance remain enabled.
