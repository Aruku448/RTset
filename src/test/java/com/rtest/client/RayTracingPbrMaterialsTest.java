package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies stable PBR slots and append-only GPU update policy. */
public final class RayTracingPbrMaterialsTest {
    public static void main(String[] args) throws IOException {
        assertVegetationClassification();
        assertSlantedSurfaceNormals();
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

    private static void assertVegetationClassification() {
        for (String path : new String[] {"short_grass", "tall_grass", "fern", "large_fern", "short_dry_grass"}) {
            if (RayTracingVegetation.kind(path, false) != 1) throw new AssertionError("Missing thin grass: " + path);
        }
        if (RayTracingVegetation.kind("custom_foliage", true) != 2
                || RayTracingVegetation.kind("oak_leaves", false) != 2) {
            throw new AssertionError("Leaves tag/path must select leaf response");
        }
        for (String path : new String[] {"grass_block", "moss_block", "oak_log", "glass", "water", "stone"}) {
            if (RayTracingVegetation.kind(path, false) != 0) throw new AssertionError("Wrong foliage: " + path);
        }
    }

    private static void assertSlantedSurfaceNormals() {
        var p0 = new org.joml.Vector3f(0, 0, 0);
        var p1 = new org.joml.Vector3f(1, 0, 1);
        var p2 = new org.joml.Vector3f(1, 1, 1);
        var p3 = new org.joml.Vector3f(0, 1, 0);
        var quad = new net.minecraft.client.resources.model.geometry.BakedQuad(
            p0, p1, p2, p3, 0L, 0L, 0L, 0L, net.minecraft.core.Direction.WEST, null,
            net.neoforged.neoforge.client.model.quad.BakedNormals.UNSPECIFIED,
            net.neoforged.neoforge.client.model.quad.BakedColors.DEFAULT);
        var normal = RayTracingTangent.geometricNormal(quad, 0, 1, 2);
        var edge = new org.joml.Vector3f(p1).sub(p0);
        if (Math.abs(normal.dot(edge)) > 1e-6F || Math.abs(normal.length() - 1) > 1e-6F) {
            throw new AssertionError("Slanted grass plane must store its actual perpendicular normal, not a cardinal face tag");
        }
        // Both rays see the same side of the diagonal plane. A cardinal X normal instead
        // flips at ray.x=0 and introduces the reported world-axis brightness boundary.
        float left = normal.dot(new org.joml.Vector3f(-0.2F, 0, -1));
        float right = normal.dot(new org.joml.Vector3f(0.2F, 0, -1));
        if (!(left < 0 && right < 0)) {
            throw new AssertionError("Crossing world X must not flip the side of the same grass plane");
        }
        if (!(normal.dot(new org.joml.Vector3f(1, 0, -0.2F)) < 0
                && normal.dot(new org.joml.Vector3f(1, 0, 0.2F)) < 0)) {
            throw new AssertionError("Crossing world Z must not flip the side of the same grass plane");
        }
        var reverse = RayTracingTangent.geometricNormal(quad, 0, 2, 1);
        if (normal.dot(reverse) > -0.99999F) {
            throw new AssertionError("Geometric normal must follow the triangle's final winding");
        }
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
        assertReversedWindingTangent();
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

    private static void assertReversedWindingTangent() {
        // The same physical plane and UV derivatives, with opposite vertex winding.
        // An authored/model normal can retain +Z independently of the triangle order.
        var frame = RayTracingTangent.fromTriangle(
            0, 0, 0, 0, 1, 0, 1, 0, 0,
            0, 0, 0, 1, 1, 0, 0, 0, 1);
        if (!frame.valid()) throw new AssertionError("Valid reversed-winding UV frame rejected");
        // For N=+Z, the shader reference tangent is -X and reference bitangent is -Y.
        double tx = -Math.cos(frame.angle()), ty = -Math.sin(frame.angle());
        double bx = -ty * frame.handedness(), by = tx * frame.handedness();
        if (Math.abs(tx - 1) > 1e-5 || Math.abs(ty) > 1e-5
            || Math.abs(bx) > 1e-5 || Math.abs(by - 1) > 1e-5)
            throw new AssertionError("PBR reconstructed +V points opposite the actual UV derivative on reversed winding");
        // All cube faces, both authored normal sides, both windings and mirrored UVs.
        for (int axis = 0; axis < 3; axis++) {
            for (int normalSide : new int[] {-1, 1}) {
                for (int winding : new int[] {-1, 1}) {
                    for (int uvSide : new int[] {-1, 1}) {
                        var n = new org.joml.Vector3f().setComponent(axis, normalSide);
                        var u = new org.joml.Vector3f().setComponent((axis + 1) % 3, 1);
                        var v = new org.joml.Vector3f().setComponent((axis + 2) % 3, 1);
                        var p1 = winding > 0 ? u : v;
                        var p2 = winding > 0 ? v : u;
                        var f = RayTracingTangent.fromTriangle(0, 0, 0,
                            p1.x, p1.y, p1.z, p2.x, p2.y, p2.z,
                            0, 0, winding > 0 ? uvSide : 0, winding > 0 ? 0 : 1,
                            winding > 0 ? 0 : uvSide, winding > 0 ? 1 : 0, n.x, n.y, n.z);
                        var reference = n.cross(Math.abs(n.y) < .999f
                            ? new org.joml.Vector3f(0, 1, 0) : new org.joml.Vector3f(1, 0, 0),
                            new org.joml.Vector3f()).normalize();
                        var referenceB = n.cross(reference, new org.joml.Vector3f());
                        var t = reference.mul((float)Math.cos(f.angle()), new org.joml.Vector3f())
                            .add(referenceB.mul((float)Math.sin(f.angle()))).normalize();
                        var b = n.cross(t, new org.joml.Vector3f()).mul(f.handedness());
                        if (!f.valid() || t.dot(u) * uvSide < .99999f || b.dot(v) < .99999f)
                            throw new AssertionError("PBR cube-face frame mismatch: axis=" + axis
                                + " normal=" + normalSide + " winding=" + winding + " mirror=" + uvSide);
                    }
                }
            }
        }
    }

    private static void assertPbrFormatContract() throws IOException {
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "pathNormal = vec4(pbrOrientSurfaceNormal(geometricNormal, normal, gl_WorldRayDirectionEXT)");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 normal = normalize(pathNormal.xyz);");
        if (RayTracingShaders.RAYGEN_SHADER.contains("vec3 geometricNormal = normalize(pathNormal.xyz);"))
            throw new AssertionError("Mapped normal must not be reclassified as the geometric face");
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
        require(screen, "number(\"screen.rtest.settings.pbrEmissionStrength\", c.pbrEmissionStrength, 0.0D, 20.0D)");
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
