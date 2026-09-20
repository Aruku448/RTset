package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.LightCoordsUtil;

/** Verifies that particles enter dynamic RT rather than a native raster overlay. */
public final class ParticleRtGeometryContractTest {
    public static void main(String[] args) throws Exception {
        if (!ParticleGeometryAdapter.isEmissiveLight(LightCoordsUtil.pack(15, 0))) {
            throw new AssertionError("full-bright flame particles must contribute emission");
        }
        if (ParticleGeometryAdapter.isEmissiveLight(LightCoordsUtil.pack(14, 15))) {
            throw new AssertionError("ordinary sky-lit particles must not become RT emitters");
        }

        String probe = source("src/main/java/com/rtest/client/RayTracingProbe.java");
        String levelMixin = source("src/main/java/com/rtest/mixin/LevelRendererMixin.java");
        String dynamic = source("src/main/java/com/rtest/client/DynamicEntityGeometry.java");
        String settings = source("src/main/java/com/rtest/client/RayTracingSettingsScreen.java");
        String mixins = source("src/main/resources/rtest.mixins.json");
        require(levelMixin, "RayTracingProbe.captureParticles(this.levelRenderState.particlesRenderState)");
        require(dynamic, "Family.PARTICLE");
        require(dynamic, "DynamicInstanceRegistry.FLAG_EMISSIVE");
        require(mixins, "QuadParticleRenderStateAccessor");
        require(mixins, "QuadParticleStorageAccessor");
        if (probe.contains("renderRtAtNativeOverlaySeam")
                || settings.contains("nativeEffectsOverlayEnabled.set(value)")) {
            throw new AssertionError("particle RT must not restore a broad native raster overlay");
        }
    }

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Missing particle RT contract: " + fragment);
        }
    }
}
