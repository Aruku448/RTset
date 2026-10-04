package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Regression contract for moving the RT Section capture window with the camera. */
public final class SceneWindowDeltaTest {
    private SceneWindowDeltaTest() {
    }

    public static void main(String[] args) throws IOException {
        long oldSection = 1L;
        long enteringSection = 2L;
        SceneWindowDelta.Delta delta = SceneWindowDelta.between(
                Set.of(oldSection), Set.of(enteringSection));
        if (!delta.removed().equals(Set.of(oldSection))
                || !delta.added().equals(Set.of(enteringSection))) {
            throw new AssertionError("camera-window movement did not produce the expected Section delta");
        }

        String probe = Files.readString(Path.of(
                "src/main/java/com/rtest/client/RayTracingProbe.java"));
        require(probe, "SceneWindowDelta.between");
        require(probe, "capturedWindowChunkX");
        require(probe, "pendingCaptureSections");
        require(probe, "capturedWindowOrigins");
        require(probe, "requestedSectionOrigins");
        require(probe, "sectionOriginKeys(finished.windowOrigins())");
        require(probe, "smokeGeometry.renderDistanceChunks != renderDistanceChunks");
        require(probe, "captureGeneration != sceneGeneration");
        require(probe, "Publish this batch, then reconcile the newer window from the");
        require(probe, "if (completedPartial && smokeGeometry != null && activeDirtySections != null)");
        if (probe.contains("stalePartialCapture")
                || probe.contains("cameraChunkX(camera) == completedMerge.windowChunkX()")) {
            throw new AssertionError("camera movement must not discard a coherent partial capture");
        }
        int renderDistanceStart = probe.indexOf("if (renderDistanceChanged)");
        int windowUpdateStart = probe.indexOf("if (smokeGeometry != null && captureSession == null", renderDistanceStart);
        if (renderDistanceStart < 0 || windowUpdateStart < 0 || renderDistanceStart >= windowUpdateStart
                || !probe.substring(renderDistanceStart, windowUpdateStart)
                    .contains("if (captureSession != null)")) {
            throw new AssertionError("render-distance change must guard a missing capture session");
        }
        System.out.println("Scene window delta contract passed");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("camera-window capture contract is missing: " + fragment);
        }
    }
}
