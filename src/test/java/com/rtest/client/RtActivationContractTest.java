package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards against an enabled F8 state that can never reach the post-hand RT seam. */
public final class RtActivationContractTest {
    public static void main(String[] args) throws Exception {
        verifyActivationFreezePolicy();

        // Normalize Windows CRLF so multi-line source contracts are platform independent.
        String probe = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingProbe.java")).replace("\r\n", "\n");
        String settings = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingSettingsScreen.java")).replace("\r\n", "\n");
        String config = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingClientConfig.java")).replace("\r\n", "\n");

        require(probe, "RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.set(true)");

        int prepare = probe.indexOf("public static void prepareLevelRender()");
        int render = probe.indexOf("public static void renderRtAfterHandCapture()");
        if (prepare < 0 || render < 0 || !probe.substring(prepare, render)
                .contains("ensureDynamicGeometryPath();")) {
            throw new AssertionError("RT preparation must repair the required dynamic geometry path");
        }

        int tick = probe.indexOf("public static void onClientTick(");
        if (tick < 0 || !probe.substring(tick).contains(
                "ensureDynamicGeometryPath();\n        smokeTestRequested = true;")) {
            throw new AssertionError("F8 activation must repair the required dynamic geometry path");
        }

        if (settings.contains("RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.set(value)")) {
            throw new AssertionError("F9 must not expose an option that silently prevents RT presentation");
        }

        require(probe, "activationFreeze = true;");
        require(probe, "RtActivationFreeze.mayPublishFullSnapshot(");
        require(probe, "RtActivationFreeze.holdIncrementalUpdates(activationFreeze, smokeGeometry != null)");
        require(probe, "activationFreeze = false;");
        require(config, ".define(\"terrainLodEnabled\", false);");

        int renderFailure = probe.indexOf("public static void renderRtAfterHandCapture()");
        int renderEnd = probe.indexOf("/** Consumes model vertices", renderFailure);
        String renderSource = probe.substring(renderFailure, renderEnd);
        require(probe, "MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL =\n        MAX_TRIANGLES_BEFORE_TERRAIN_LOD_READY");
        require(renderSource, "terrainLodGpuTraversalActive");
        require(renderSource, "RT_RETRY_BASE_DELAY_NANOS << (rtFailureCount - 1)");
        if (renderSource.contains("stopSmokeTestResources();")) {
            throw new AssertionError("A transient Vulkan presentation failure must not disable RT activation");
        }

        int effectiveLodStart = probe.indexOf("private static void updateTerrainLod(");
        int effectiveLodEnd = probe.indexOf("private static void logTerrainLodState(", effectiveLodStart);
        String effectiveLodSource = probe.substring(effectiveLodStart, effectiveLodEnd);
        require(effectiveLodSource, "smokeGeometry.triangleCount() <= MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL");
        require(effectiveLodSource, "if (terrainLodScheduler != null || terrainLodSourceGeometry != null)");
        require(effectiveLodSource, "candidateTriangleCount > MAX_TRIANGLES_FOR_GPU_TERRAIN_TRAVERSAL");
        require(effectiveLodSource, "&& !terrainLodGpuCandidateLimitExceeded;");
        require(effectiveLodSource, "gpuTraversalEnabled &= terrainLodPending.isEmpty();");
        require(effectiveLodSource, "compositionNeeded |= terrainLodCompositionPending");
        require(effectiveLodSource, "TERRAIN_COMPOSITION_EXECUTOR");

        int prepareStart = probe.indexOf("public static void prepareLevelRender()");
        int renderStart = probe.indexOf("public static void renderRtAfterHandCapture()");
        String prepareSource = probe.substring(prepareStart, renderStart);
        int lodUpdate = prepareSource.indexOf("updateTerrainLod(camera);");
        int activationFreezeGuard = prepareSource.indexOf(
            "if (RtActivationFreeze.holdIncrementalUpdates(activationFreeze, smokeGeometry != null))");
        if (lodUpdate < 0 || activationFreezeGuard < 0 || lodUpdate > activationFreezeGuard) {
            throw new AssertionError(
                "terrain LOD must progress before the activation-freeze return so oversized scenes can reach RT");
        }
        int lodStart = probe.indexOf("private static void updateTerrainLod(");
        int lodEnd = probe.indexOf("private static void logTerrainLodState(", lodStart);
        String lodSource = probe.substring(lodStart, lodEnd);
        int selectionGate = lodSource.indexOf("if (selectionUpdateDue)");
        int hierarchyBuild = lodSource.indexOf("RayTracingTerrainLod.fromNodes(hierarchyNodes)");
        if (selectionGate < 0 || hierarchyBuild < selectionGate) {
            throw new AssertionError("terrain LOD hierarchy selection must be cached between state changes");
        }
        require(probe, "TERRAIN_LOD_SELECTION_INTERVAL_NANOS = 1_000_000_000L;");
        require(lodSource, "terrainLodScheduler.poll(budget, terrainLodSourceGeneration,");
        require(lodSource, "new RayTracingTerrainLodScheduler.NodeKey(id), terrainLodSourceGeneration,");
        if (lodSource.contains("terrainLodSceneGeneration != sceneGeneration")) {
            throw new AssertionError("pending block events must not cancel builds for an unchanged LOD source snapshot");
        }

        if (!probe.contains("GUI widgets are drawn after this seam. Keep RT active underneath them;")) {
            throw new AssertionError("GUI screens must keep RT active underneath the GUI");
        }
    }

    private static void verifyActivationFreezePolicy() {
        if (!RtActivationFreeze.mayPublishFullSnapshot(
                true, true, true, false, true)) {
            throw new AssertionError("entry freeze must publish a coherent initial snapshot despite deferred invalidations");
        }
        if (RtActivationFreeze.mayPublishFullSnapshot(
                true, false, true, true, false)) {
            throw new AssertionError("entry freeze must never publish a snapshot from another world");
        }
        if (RtActivationFreeze.mayPublishFullSnapshot(
                true, true, false, true, false)) {
            throw new AssertionError("entry freeze must respect render-distance compatibility");
        }
        if (RtActivationFreeze.mayPublishFullSnapshot(
                false, true, true, false, false)) {
            throw new AssertionError("normal scene publication must reject stale generations");
        }
        if (!RtActivationFreeze.holdIncrementalUpdates(true, true)
                || RtActivationFreeze.holdIncrementalUpdates(true, false)
                || RtActivationFreeze.holdIncrementalUpdates(false, true)) {
            throw new AssertionError("incremental updates must pause only after the frozen snapshot is published");
        }
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Missing RT activation contract: " + fragment);
        }
    }
}
