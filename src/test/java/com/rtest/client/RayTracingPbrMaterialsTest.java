package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies stable PBR slots and append-only GPU update policy. */
public final class RayTracingPbrMaterialsTest {
    public static void main(String[] args) throws IOException {
        assertEmissionDecode();
        assertEmissionOverrideContract();
        assertSampleEmissionContract();
        assertUvTangentEncoding();
        assertPbrFormatContract();
        assertStaticMaterialLayout();
        assertPbrEmissionStrengthLimit();
        assertStableGpuLayout();
        assertVulkanPassUsesInPlacePbrUpdates();
        assertReloadGenerationContract();
        System.out.println("PBR append-only upload policy contract passed");
    }

    private static void assertStableGpuLayout() {
        RayTracingPbrMaterials materials = new RayTracingPbrMaterials(null);
        int[] first = materials.packedData();
        int[] second = materials.packedData();
        if (first != second) {
            throw new AssertionError("Unchanged PBR data must reuse its initial snapshot");
        }
        if (first[0] != 0 || !materials.gpuUpdatesAfter(0).isEmpty()) {
            throw new AssertionError("An empty PBR registry must not publish dynamic updates");
        }
        if (RayTracingPbrMaterials.metadataOffsetForSlot(1) != 1
            || RayTracingPbrMaterials.metadataOffsetForSlot(2) != 11) {
            throw new AssertionError("PBR metadata slots no longer match the shader ABI");
        }
        if (RayTracingPbrMaterials.pixelDataStartWord() <= RayTracingPbrMaterials.metadataOffsetForSlot(8192)) {
            throw new AssertionError("PBR pixel storage must follow the reserved metadata region");
        }
        if (materials.gpuBufferSizeBytes() < (long)first.length * Integer.BYTES) {
            throw new AssertionError("Fixed PBR buffer is smaller than the initial ABI payload");
        }
        materials.close();
        materials.close();
    }

    private static void assertVulkanPassUsesInPlacePbrUpdates() throws IOException {
        String pass = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        if (pass.contains("packedDataIfChanged") || pass.contains("NativeBuffer nextPbr = uploadIntBuffer(this.device, packedData")) {
            throw new AssertionError("Dynamic PBR must not repack and replace its SSBO");
        }
        require(pass, "List<RayTracingPbrMaterials.GpuUpdate> updates");
        require(pass, "this.pbrBuffer.map()");
        require(pass, "destination.put(0, mapCount)");
        require(pass, "pbrMaterials == null");
        require(pass, "bindings.get(5).binding(5).descriptorType");
        int pbrBinding = pass.indexOf("bindings.get(5).binding(5).descriptorType");
        int nextBinding = pass.indexOf("bindings.get(6).binding(6)", pbrBinding);
        if (pbrBinding < 0 || nextBinding < pbrBinding
            || !pass.substring(pbrBinding, nextBinding).contains("VK_SHADER_STAGE_RAYGEN_BIT_KHR")
            || !pass.substring(pbrBinding, nextBinding).contains("VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR")) {
            throw new AssertionError("PBR descriptor binding must be visible to raygen and closest-hit");
        }
    }

    private static void assertReloadGenerationContract() throws IOException {
        String probe = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingProbe.java"));
        String mod = Files.readString(Path.of("src/main/java/com/rtest/RTest.java"));
        require(probe, "capturedResourceGeneration != resourceGeneration");
        require(probe, "markResourcesReloaded()");
        require(probe, "smokeTestRequested = true");
        require(mod, "AddClientReloadListenersEvent");
        require(mod, "rt_resource_generation");
        require(mod, "thenRunAsync(RayTracingProbe::markResourcesReloaded, applyExecutor)");
    }

    private static void assertEmissionDecode() {
        assertClose(0.0F, RayTracingPbrMaterials.decodeLabPbrEmission(255), "255 sentinel");
        assertClose(0.0F, RayTracingPbrMaterials.decodeLabPbrEmission(0), "alpha 0");
        assertClose(127.0F / 254.0F, RayTracingPbrMaterials.decodeLabPbrEmission(127), "alpha 127");
        assertClose(1.0F, RayTracingPbrMaterials.decodeLabPbrEmission(254), "alpha 254");
    }

    private static void assertEmissionOverrideContract() {
        assertClose(0.0F,
            RayTracingPbrMaterials.resolveEmission(1.0F, 0.0F, true),
            "PBR zero masks material fallback");
        assertClose(0.5F,
            RayTracingPbrMaterials.resolveEmission(1.0F, 0.5F, true),
            "PBR value replaces material fallback");
        assertClose(1.0F,
            RayTracingPbrMaterials.resolveEmission(1.0F, 0.0F, false),
            "missing PBR emission keeps material fallback");
        assertClose(32.0F,
            RayTracingPbrMaterials.resolveLightTreeEmission(32.0F, 0.4F, true),
            "PBR mask keeps calibrated block-light radiance in the light tree");
        assertClose(0.4F,
            RayTracingPbrMaterials.resolveLightTreeEmission(0.0F, 0.4F, true),
            "authored-only emitter keeps positive light-tree support");
        assertClose(0.0F,
            RayTracingPbrMaterials.resolveLightTreeEmission(0.0F, 0.4F, false),
            "disabled authored emission remains dark");
    }

    private static void assertSampleEmissionContract() {
        RayTracingPbrMaterials.Sample defaults = RayTracingPbrMaterials.defaultSample();
        if (defaults.emission() != 0.0F || defaults.hasEmission()) {
            throw new AssertionError("Default PBR sample must have no authored emission");
        }
        RayTracingPbrMaterials.Sample authored = new RayTracingPbrMaterials.Sample(
            0.04F, 0.0F, 0.88F, 0.0F, 0.0F, 1.0F, false, true, 127.0F / 254.0F, true, 3);
        if (!authored.hasEmission() || authored.emission() != 127.0F / 254.0F) {
            throw new AssertionError("PBR sample emission fields are not preserved");
        }
        RayTracingPbrMaterials.Sample authoredDark = new RayTracingPbrMaterials.Sample(
            0.04F, 0.0F, 0.88F, 0.0F, 0.0F, 1.0F, false, true, 0.0F, true, 3);
        if (!authoredDark.hasEmission() || authoredDark.emission() != 0.0F) {
            throw new AssertionError("A zero PBR texel must remain an authored dark emission mask");
        }
    }

    private static void assertUvTangentEncoding() {
        RayTracingTangent.Frame aligned = RayTracingTangent.fromTriangle(
            0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
            0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F);
        if (!aligned.valid() || Math.abs(Math.abs(aligned.angle()) - (float)Math.PI) > 1.0E-5F) {
            throw new AssertionError("UV tangent must encode the triangle's +U direction");
        }
        RayTracingTangent.Frame mirrored = RayTracingTangent.fromTriangle(
            0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
            0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F);
        if (!mirrored.valid() || mirrored.handedness() >= 0.0F) {
            throw new AssertionError("Mirrored UVs must preserve a negative tangent handedness");
        }
    }

    private static void assertPbrFormatContract() throws IOException {
        String materials = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingPbrMaterials.java"));
        String scene = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingScene.java"));
        String pass = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        String item = Files.readString(Path.of("src/main/java/com/rtest/client/ItemModelGeometryAdapter.java"));
        String player = Files.readString(Path.of("src/main/java/com/rtest/client/PlayerModelGeometryAdapter.java"));
        require(materials, "format == 2");
        require(materials, "porosity = blueByte / 255.0F");
        require(materials, "decodeLabPbrEmission(alpha)");
        require(materials, "normalZ = isLabPbr()");
        require(scene, "pbrTerrainCpuCaptureEnabled.get()");
        require(pass, "buffer.putFloat(272, pbrPackedMode)");
        require(pass, "buffer.putFloat(284, pbrWetnessStrength)");
        require(pass, "buffer.putFloat(288, pbrParallaxDepth)");
        require(pass, ".putFloat(292, emissionScale)");
        require(pass, ".range(304)");
        require(materials, "float textureAo");
        require(scene, "RayTracingTangent.fromBakedQuad");
        require(item, "RayTracingTangent.fromBakedQuad");
        require(player, "RayTracingTangent.fromTriangle");
    }

    private static void assertStaticMaterialLayout() throws IOException {
        String scene = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingScene.java"));
        int alphaTest = scene.indexOf("materialData.add(alphaTest ? 1.0F : 0.0F);");
        int textureKind = scene.indexOf("materialData.add(1.0F);", alphaTest);
        int tangentAngle = scene.indexOf("materialData.add(tangent.angle());", alphaTest);
        int dispersion = scene.indexOf("materialData.add(properties.optical().dispersionScale());", alphaTest);
        int handedness = scene.indexOf("materialData.add(tangent.handedness());", alphaTest);
        int mapIndex = scene.indexOf("materialData.add((float)pbr.mapIndex());", alphaTest);
        if (alphaTest < 0 || textureKind < 0 || tangentAngle < 0 || dispersion < 0
            || handedness < 0 || mapIndex < 0
            || !(alphaTest < textureKind && textureKind < tangentAngle
                && tangentAngle < dispersion && dispersion < handedness && handedness < mapIndex)) {
            throw new AssertionError("Static terrain materials must keep uv2.w before tangent metadata");
        }
    }

    private static void assertPbrEmissionStrengthLimit() throws IOException {
        String config = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingClientConfig.java"));
        String screen = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingSettingsScreen.java"));
        require(config, "defineInRange(\"pbrEmissionStrength\", 1.0D, 0.0D, 20.0D)");
        require(screen, "\"1.0\", 0.0D, 20.0D, RayTracingClientConfig.INSTANCE.pbrEmissionStrength.get()");
    }

    private static void assertClose(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > 1.0E-6F) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("PBR upload contract is missing: " + fragment);
        }
    }
}
