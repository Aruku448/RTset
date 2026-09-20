package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Contract for one-pass CPU merging of captured Section geometry. */
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
        assertEmissionCalibration();
        require(source, "containsEmitter = containsEmissiveBlock(this.level, origin);");
        require(source, "section.maybeHas(state -> state.getLightEmission() > 0)");
        require(source, "containsFluid || hasGlass || containsEmitter\n                        ? null : compiled");
        require(source, "boolean hasGlass = !pbrNeedsSpriteCapture && containsGlass(this.level, origin);");
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
        require(merge, "int vertexLength = 0;");
        require(merge, "int materialLength = 0;");
        require(merge, "vertices = new float[vertexLength];");
        require(merge, "materials = new float[materialLength];");
        require(merge, "System.arraycopy(section.vertices, 0, vertices, vertexOffset, section.vertices.length);");
        require(merge, "System.arraycopy(section.materialData, 0, materials, materialOffset, section.materialData.length);");
        require(merge, "addFallbackGeometry(mergedSections, fallbackVertices, fallbackMaterials, cameraPosition);");
        assertFallbackMaterialArity(source);
        assertNoFloatBoxing(source);
        assertVanillaFaceCulling(source);
        assertReusedCaptureScratch(source);
        require(merge, "false,\n                this.revision");
        require(probeSource, "private static final int SECTIONS_PER_TRANSACTION = 8;");
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
        require(probeSource, "completedMerge.generation() == sceneGeneration");
        require(probeSource, "completedMerge.level() == capturedLevel");
        require(probeSource, "&& !fullCaptureRequested");
        require(probeSource, "pendingDirtySections.addAll(completedMerge.dirtySections())");
        require(probeSource, "smokeGeometry = completedMerge.geometry();");
        reject(probeSource, "smokeGeometry = smokeGeometry.replaceSections");
        require(probeSource, "boolean stalePartialCapture = completedPartial && cameraMovedDuringCapture;");
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

    /** Section capture must reuse one mutable position and one RandomSource per Section. */
    private static void assertReusedCaptureScratch(String source) {
        require(source, "BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();");
        require(source, "random.setSeed(state.getSeed(position));");
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
