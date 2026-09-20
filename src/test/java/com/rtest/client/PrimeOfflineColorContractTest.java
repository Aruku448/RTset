package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Contracts for the Prime-derived display transform and frozen GPU offline accumulation path. */
public final class PrimeOfflineColorContractTest {
    private PrimeOfflineColorContractTest() {
    }

    public static void main(String[] args) throws Exception {
        PrimeRgbReinhardOutput.Parameters sdr = PrimeRgbReinhardOutput.parameters(1.0F);
        assertNear(sdr.outputPeak(), 1.0F, 1.0e-6F, "SDR output peak");
        assertNear(sdr.curvePeak(), 1.0149157F, 1.0e-6F, "Prime RGB Reinhard curve peak");

        String display = read("src/main/resources/prime/shaders/fsr_display.comp");
        require(display, "const float PRIME_COMPRESSION_START = 0.18;");
        require(display, "rec2020ToBt709");
        require(display, "toVirtualRgb");
        require(display, "protectHue");
        require(display, "settings.colorManagementMode != 0u");

        String accumulate = read("src/main/resources/prime/shaders/offline_accumulate.comp");
        require(accumulate, "layout(set = 0, binding = 1, rgba32f) uniform image2D runningMean;");
        require(accumulate, "previousMean + (sampleValue - previousMean) / float(settings.sampleIndex + 1u)");

        String upscaler = read("src/main/java/com/rtest/client/fsr/RtestFsr3Upscaler.java");
        int accumulateAt = upscaler.indexOf("this.offlinePass.record(");
        int displayAt = upscaler.indexOf("this.displayPass.record(");
        if (accumulateAt < 0 || displayAt <= accumulateAt) {
            throw new AssertionError("offline running mean must execute before the display transform");
        }

        String controller = read("src/main/java/com/rtest/client/OfflineRenderController.java");
        require(controller, "frozenCamera");
        require(controller, "frozenSceneRevision");
        require(controller, "frozenEnvironment");
        require(controller, "key.getValue() != InputConstants.KEY_F2");
        require(controller, "InputConstants.KEY_RALT");

        String settings = read("src/main/java/com/rtest/client/RayTracingSettingsScreen.java");
        require(settings, "screen.rtest.settings.category.output");
        require(settings, "primeColorManagementEnabled");
        require(settings, "OfflineRenderController.toggle(this.minecraft)");
        require(settings, "OfflineRenderController.sampleCount()");

        String pass = read("src/main/java/com/rtest/client/RayTracingVulkanPass.java");
        require(pass, "OfflineRenderController.environment(");
        require(pass, "OfflineRenderController.camera(");

        String mixins = read("src/main/resources/rtest.mixins.json");
        require(mixins, "MinecraftOfflineRenderMixin");
        require(mixins, "TextureAtlasOfflineMixin");
        System.out.println("Prime offline/color-management contracts passed");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Prime offline/color contract is missing: " + fragment);
        }
    }

    private static void assertNear(float actual, float expected, float tolerance, String label) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }
}
