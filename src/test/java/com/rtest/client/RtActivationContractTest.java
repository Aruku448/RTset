package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards against an enabled F8 state that can never reach the post-hand RT seam. */
public final class RtActivationContractTest {
    public static void main(String[] args) throws Exception {
        verifyActivationFreezePolicy();

        String probe = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingProbe.java"));
        String settings = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingSettingsScreen.java"));

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
