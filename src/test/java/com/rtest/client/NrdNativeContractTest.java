package com.rtest.client;

import com.rtest.client.fsr.NrdNative;
import java.util.Locale;

/** Verifies that the bundled NRD bridge can create a scheduler and emit dispatches. */
public final class NrdNativeContractTest {
    private NrdNativeContractTest() {
    }

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64")
                || architecture.equals("x86-64");
        if ((!os.startsWith("linux") && !os.startsWith("windows")) || !x64) {
            System.out.println("NRD native contract skipped on unsupported platform");
            return;
        }
        try (NrdNative.Instance instance = NrdNative.create(64, 64)) {
            NrdNative.Description description = instance.description();
            int expectedVersion = 4 << 24 | 17 << 16 | 3;
            if (description.nrdVersion() != expectedVersion
                    || description.pipelines().isEmpty()
                    || description.samplers().size() != 2) {
                throw new AssertionError("Bundled NRD description is incomplete");
            }
            float[] identity = new float[16];
            identity[0] = identity[5] = identity[10] = identity[15] = 1.0F;
            instance.setFrameSettings(new NrdNative.FrameSettings(
                    identity, identity, identity, identity,
                    new float[] {0.0F, 0.0F}, new float[] {0.0F, 0.0F},
                    64, 64, 64, 64, 0, true, 16.0F, 60000.0F, false));
            var dispatches = instance.getDispatches();
            if (dispatches.isEmpty() || dispatches.stream().anyMatch(dispatch ->
                    dispatch.gridWidth() <= 0 || dispatch.gridHeight() <= 0
                        || dispatch.resources().isEmpty())) {
                throw new AssertionError("NRD did not emit valid compute dispatches");
            }
            System.out.println("NRD native contract passed: " + dispatches.size() + " dispatches");
        }
    }
}
