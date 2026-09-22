package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level contract for the dependency-free incremental section update pipeline. */
public final class IncrementalGeometryContractTest {
    private IncrementalGeometryContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String dirtyMixin = source("src/main/java/com/rtest/mixin/ClientLevelDirtyMixin.java");
        String compilerMixin = source("src/main/java/com/rtest/mixin/SectionCompilerCaptureMixin.java");
        String probe = source("src/main/java/com/rtest/client/RayTracingProbe.java");
        String scene = source("src/main/java/com/rtest/client/RayTracingScene.java");
        String vulkan = source("src/main/java/com/rtest/client/RayTracingVulkanPass.java");

        assertClientLevelCallbacks(dirtyMixin);
        assertCompilerPublication(compilerMixin);
        assertDirtyNeighborPropagation(probe);
        assertSectionMergeKeepsUnchanged(scene);
        assertGeometryPublicationResetsTemporalHistory(vulkan);

        System.out.println("Incremental geometry contract tests passed");
    }

    private static void assertClientLevelCallbacks(String source) {
        require(source, "@Inject(method = \"setBlocksDirty\", at = @At(\"TAIL\"))");
        require(source, "RayTracingProbe.markSectionDirty(position);");
        require(source, "@Inject(method = \"onChunkLoaded\", at = @At(\"TAIL\"))");
        require(source, "RayTracingProbe.markChunkDirty(level, chunkPosition, true);");
        require(source, "@Inject(method = \"unload\", at = @At(\"TAIL\"))");
        require(source, "RayTracingProbe.markChunkDirty(level, chunk.getPos(), false);");
    }

    private static void assertCompilerPublication(String source) {
        require(source, "@Inject(method = \"compile(Lnet/minecraft/core/SectionPos;");
        require(source, "at = @At(\"RETURN\"))");
        require(source, "CompiledSectionMeshCache.publish(sectionPos, callbackInfo.getReturnValue());");
    }

    private static void assertDirtyNeighborPropagation(String source) {
        require(source, "public static void markSectionDirty(BlockPos position)");
        require(source, "invalidateDirtySection(section.offset(-1, 0, 0))");
        require(source, "invalidateDirtySection(section.offset(1, 0, 0))");
        require(source, "invalidateDirtySection(section.offset(0, -1, 0))");
        require(source, "invalidateDirtySection(section.offset(0, 1, 0))");
        require(source, "invalidateDirtySection(section.offset(0, 0, -1))");
        require(source, "invalidateDirtySection(section.offset(0, 0, 1))");

        require(source, "private static Set<ChunkPos> affectedChunks(ChunkPos center)");
        require(source, "new ChunkPos(center.x() - 1, center.z())");
        require(source, "new ChunkPos(center.x() + 1, center.z())");
        require(source, "new ChunkPos(center.x(), center.z() - 1)");
        require(source, "new ChunkPos(center.x(), center.z() + 1)");
        require(source, "CompiledSectionMeshCache.invalidateChunk(affected, level.getMinSectionY(), level.getMaxSectionY());");
        require(source, "if (affectedChunks.contains(sectionChunk))");
    }

    private static void assertSectionMergeKeepsUnchanged(String source) {
        int start = source.indexOf("SceneGeometry replaceSections(");
        int end = source.indexOf("private static long sectionOriginKey", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("replaceSections contract is missing");
        }
        String merge = source.substring(start, end);
        require(merge, "Map<Long, SectionGeometry> merged = new LinkedHashMap<>();");
        require(merge, "for (SectionGeometry section : this.sections)");
        require(merge, "merged.put(sectionOriginKey(section), section);");
        require(merge, "merged.remove(origin);");
        require(merge, "merged.put(sectionOriginKey(section), section);");
        require(merge, "System.arraycopy(section.vertices, 0, vertices, vertexOffset, section.vertices.length);");
        require(merge, "System.arraycopy(section.materialData, 0, materials, materialOffset, section.materialData.length);");
        require(merge, "this.revision,");
        require(merge, "true,\n                false");
    }

    private static void assertGeometryPublicationResetsTemporalHistory(String source) {
        int start = source.indexOf("void updateGeometry(SceneGeometry nextGeometry");
        int end = source.indexOf("// Player body overlays", start);
        if (start < 0 || end < 0) {
            throw new AssertionError("geometry publication contract is missing");
        }
        String publish = source.substring(start, end);
        require(publish, "this.geometry = nextGeometry;");
        require(publish, "this.dynamicHistoryResetPending = true;");
        require(publish, "this.fsr.requestReset();");
        require(publish, "RTest geometry publish:");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("incremental geometry contract is missing: " + fragment);
        }
    }
}
