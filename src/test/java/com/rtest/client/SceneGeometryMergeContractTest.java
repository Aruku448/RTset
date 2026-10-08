package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Contract for section-backed CPU geometry snapshots and incremental merging. */
public final class SceneGeometryMergeContractTest {
    private SceneGeometryMergeContractTest() {
    }

    public static void main(String[] args) throws IOException {
        // Normalize CRLF so multi-line source contracts run identically on Windows and Linux.
        String source = Files.readString(Path.of(
                "src/main/java/com/rtest/client/RayTracingScene.java")).replace("\r\n", "\n");
        String compiledSource = Files.readString(Path.of(
                "src/main/java/com/rtest/client/CompiledSectionMeshCache.java")).replace("\r\n", "\n");
        String probeSource = Files.readString(Path.of(
                "src/main/java/com/rtest/client/RayTracingProbe.java")).replace("\r\n", "\n");
        String materialBufferSource = Files.readString(Path.of(
                "src/main/java/com/rtest/client/RayTracingMaterialBuffer.java")).replace("\r\n", "\n");
        assertEmissionCalibration();
        require(source, "&& containsEmissiveBlock(capturedSection);");
        require(source, "section.maybeHas(state -> state.getLightEmission() > 0)");
        require(source, "containsFluid || hasGlass || containsEmitter || containsVegetation\n                        ? null : compiled");
        require(source, "&& containsGlass(capturedSection);");
        require(source, "BlockState state = capturedSection.getBlockState(localX, localY, localZ);");
        require(source, "this.lightTree = RayTracingLightTree.build");
        require(Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingLightTree.java")),
            "power-weighted binary light tree");
        require(Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java")),
            ".dstBinding(26)");
        require(source, "key.getPath().contains(\"glass\")");
        require(compiledSource, "float averageR = 1.0f;");
        require(compiledSource, "Vanilla's compiled Color contains face/cardinal shade and AO");
        int start = source.indexOf("SceneGeometry replaceSections(");
        int end = source.indexOf("private static long sectionOriginKey", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("SceneGeometry.replaceSections contract is missing");
        }
        String merge = source.substring(start, end);
        require(merge, "addFallbackGeometry(mergedSections, cameraPosition);");
        require(merge, "sectionTriangleCount(mergedSections)");
        reject(merge, "vertices = new float[vertexLength];");
        reject(merge, "materials = new float[materialLength];");
        require(source, "this.materialData = new float[0];");
        require(materialBufferSource, "for (SceneGeometry.SectionGeometry section : geometry.sections)");
        assertFallbackMaterialArity(source);
        assertNoFloatBoxing(source);
        assertVanillaFaceCulling(source);
        assertCaptureTransfersOwnedArrays(source);
        assertReusedCaptureScratch(source);
        require(merge, "false,\n                this.revision");
        require(probeSource, "private static final int SECTIONS_PER_TRANSACTION = 512;");
        require(probeSource, "private static final int INITIAL_CAPTURE_SECTIONS_PER_FRAME = 4;");
        require(probeSource, "TERRAIN_DIRTY_IDLE_FLUSH_NANOS = 250_000_000L;");
        require(probeSource, "TERRAIN_DIRTY_MAX_BATCH_AGE_NANOS = 2_000_000_000L;");
        require(probeSource, "pendingDirtySections.size() >= SECTIONS_PER_TRANSACTION");
        require(probeSource, "notePendingDirtyEvents();");
        require(probeSource, "MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY = 50_000_000;");
        require(probeSource, "if (renderGeometry.triangleCount() > MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY)");
        require(probeSource, "if (smokeGeometry.triangleCount() <= MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY)");
        require(probeSource,
            "int sectionBudget = smokeGeometry == null\n                ? INITIAL_CAPTURE_SECTIONS_PER_FRAME\n                : SECTIONS_PER_FRAME;");
        require(probeSource, "if (activeDirtySections.size() >= SECTIONS_PER_TRANSACTION)");
        require(probeSource, "private static final ExecutorService GEOMETRY_MERGE_EXECUTOR");
        require(probeSource, "CompletableFuture.supplyAsync");
        require(probeSource, "pendingGeometryMerge = submitDirtyGeometryMerge(");
        require(probeSource, "pollDirtyGeometryMerge(renderDistanceChunks);");
        require(probeSource, "private static CompletableFuture<FullGeometryBuild> submitFullGeometryBuild(");
        require(probeSource, "pendingFullGeometryBuild = submitFullGeometryBuild(");
        require(probeSource, "pollFullGeometryBuild(renderDistanceChunks);");
        require(probeSource, "RayTracingScene.SceneGeometry delta = session.buildPartial();");
        require(probeSource, "RayTracingScene.SceneGeometry geometry = session.build();");
        require(probeSource, "finally {\n                session.close();\n            }");
        require(probeSource, "previous.replaceSections(");
        reject(probeSource, "cameraChunkX(camera) == completedMerge.windowChunkX()");
        reject(probeSource, "cameraChunkZ(camera) == completedMerge.windowChunkZ()");
        require(probeSource, "private static boolean queuedWindowValid;");
        require(probeSource, "if (!windowMoved) {");
        require(probeSource, "completedMerge.level() == capturedLevel");
        require(probeSource, "&& !fullCaptureRequested");
        require(probeSource, "pendingDirtySections.addAll(completedMerge.dirtySections())");
        require(probeSource, "smokeGeometry = completedMerge.geometry();");
        require(probeSource, "updateTerrainLodInputsIncrementally(terrainLodSourceGeometry, smokeGeometry,");
        require(probeSource, "smokeGeometry.revision() != terrainLodSourceGeometry.revision()");
        require(probeSource, "terrainLodWindowGeneration = terrainLodSourceGeneration;");
        require(probeSource, "terrainLodScheduler.cancel(schedulerKey)");
        require(probeSource, "terrainLodWorkerTokens.put(id, request.token())");
        int windowChangedStart = probeSource.indexOf("if (windowChanged) {");
        int windowChangedEnd = probeSource.indexOf("for (RayTracingTerrainLodScheduler.Result", windowChangedStart);
        if (windowChangedStart < 0 || windowChangedEnd < 0
                || probeSource.substring(windowChangedStart, windowChangedEnd).contains("cancelAll()")
                || probeSource.substring(windowChangedStart, windowChangedEnd).contains("terrainLodPending.clear()")) {
            throw new AssertionError("camera movement must preserve independent terrain LOD work");
        }
        reject(probeSource, "smokeGeometry = smokeGeometry.replaceSections");
        reject(probeSource, "stalePartialCapture");
        if (merge.contains("FloatAccumulator vertices =") || merge.contains("FloatAccumulator materials =")) {
            throw new AssertionError("CPU Section merge must not use growing FloatAccumulator arrays");
        }
        System.out.println("Scene geometry merge contract passed");
    }

    private static void assertEmissionCalibration() {
        if (RayTracingEmission.fromMinecraftLevel(0) != 0.0F
            || Math.abs(RayTracingEmission.fromMinecraftLevel(3) - 0.06F) > 0.000001F
            || Math.abs(RayTracingEmission.fromMinecraftLevel(15) - 1.5F) > 0.000001F) {
            throw new AssertionError("Prime emission calibration changed");
        }
    }

    /**
     * The fallback material must emit exactly the same 28 floats as the normal path. It previously
     * wrote 27 because the alpha-test slot was missing, which makes the SectionGeometry
     * constructor throw the moment addFallbackGeometry runs.
     */
    private static void assertFallbackMaterialArity(String source) {
        int start = source.indexOf("private static void addMaterial(FloatAccumulator materialData, int color, float normalX");
        int end = source.indexOf("private static void addUv(", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("Fallback addMaterial contract is missing");
        }
        String fallback = source.substring(start, end);
        int floats = 0;
        for (String line : fallback.split("\n")) {
            if (line.contains("materialData.add")) {
                floats++;
            }
        }
        if (floats != 28) {
            throw new AssertionError(
                "Fallback addMaterial must emit 28 floats per triangle but emits " + floats);
        }
    }

    /** Section capture must accumulate floats directly, never through List&lt;Float&gt; boxing. */
    private static void assertNoFloatBoxing(String source) {
        if (source.contains("List<Float>")) {
            throw new AssertionError("Section capture must not box floats through List<Float>");
        }
        // toFloatArray must accept the primitive accumulator, not a boxed list.
        require(source, "private static float[] toFloatArray(FloatAccumulator values)");
    }

    /** Internal faces between identical transparent blocks must use vanilla culling rules. */
    private static void assertVanillaFaceCulling(String source) {
        require(source, "Block.shouldRenderFace(level, position, state, neighbor, direction)");
        reject(source, "level.getBlockState(position.relative(direction)).isSolidRender()");
    }

    /** Fresh CPU capture arrays transfer into the immutable section without another full copy. */
    private static void assertCaptureTransfersOwnedArrays(String source) {
        require(source, "return SectionGeometry.takeOwnership(");
        require(source, "copyArrays ? vertices.clone() : vertices");
    }

    /** Section capture must reuse block/neighbor positions and one RandomSource per Section. */
    private static void assertReusedCaptureScratch(String source) {
        require(source, "BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();");
        require(source, "BlockPos.MutableBlockPos neighborPosition = new BlockPos.MutableBlockPos();");
        require(source, "neighborPosition.set(x + direction.getStepX()");
        require(source, "random.setSeed(state.getSeed(position));");
        reject(source, "position.relative(direction)");
        reject(source, "RandomSource.create(state.getSeed(position))");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Scene geometry merge contract is missing: " + fragment);
        }
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) {
            throw new AssertionError("Scene streaming contract contains obsolete behavior: " + fragment);
        }
    }
}
