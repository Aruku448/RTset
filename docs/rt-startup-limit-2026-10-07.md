# RT startup scene limit: 50 million triangles

User requested a 50,000,000-triangle admission ceiling on 2026-10-07. Changed MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY from 10,000,000 to 50,000,000. MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL derives from this constant.

Live failure evidence: RT presentation repeatedly waited with triangles=18,812,630 and lodEnabled=false. Enabling LOD through live config reload produced a composed scene with 18,030,474 triangles, still exceeding the former ceiling. Restored the prior terrainLodEnabled=false setting after the explicit ceiling-change request. No adaptive-radius implementation is included.

Passed rtActivationContractTest, dynamicModelChunksTest and customEntityGeometryTest; jar built and installed with backup. A running JVM needs restart for the changed constant.

This raises admission only. Actual successful dispatch at this scale remains unverified. Existing material host mapping uses Java ByteBuffer integer addressing, and Vulkan instanceCustomIndex is 24 bits; raising this ceiling alone does not establish 50-million-triangle GPU/material support. Those limits require separate addressing work for scenes exceeding their representable spans.
