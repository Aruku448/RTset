package com.rtest.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Display effects operate on reconstructed scene-linear radiance, never on
 * path transport.
 */
public final class PostProcessingSettings {
    public final ModConfigSpec.BooleanValue enabled, depthOfField, autofocus, rainBloomFog;
    public final ModConfigSpec.IntValue dofSamples, motionSamples, agxLook;
    public final ModConfigSpec.ConfigValue<String> toneMapping;
    public final ModConfigSpec.DoubleValue exposureEV;
    public final ModConfigSpec.DoubleValue autoExposureStrength;
    public final ModConfigSpec.DoubleValue centerWeight;
    public final ModConfigSpec.DoubleValue exposureTendency;
    public final ModConfigSpec.DoubleValue bloomIntensity;
    public final ModConfigSpec.DoubleValue vignetteStrength;
    public final ModConfigSpec.DoubleValue saturation;
    public final ModConfigSpec.DoubleValue contrast;
    public final ModConfigSpec.DoubleValue blackTightness;
    public final ModConfigSpec.DoubleValue minimumBrightness;
    public final ModConfigSpec.DoubleValue gamma;
    public final ModConfigSpec.DoubleValue colorTemperature;
    public final ModConfigSpec.DoubleValue focalLength;
    public final ModConfigSpec.DoubleValue apertureScale;
    public final ModConfigSpec.DoubleValue manualFocusDepth;
    public final ModConfigSpec.DoubleValue maxBlurRadius;
    public final ModConfigSpec.DoubleValue motionStrength;
    public final ModConfigSpec.DoubleValue sharpenStrength;
    public final ModConfigSpec.DoubleValue chromaticR;
    public final ModConfigSpec.DoubleValue chromaticG;
    public final ModConfigSpec.DoubleValue chromaticB;
    public final ModConfigSpec.DoubleValue distortion;
    public final ModConfigSpec.DoubleValue rainBloomFogDensity;
    public final ModConfigSpec.DoubleValue agxMinEV;
    public final ModConfigSpec.DoubleValue agxMaxEV;
    PostProcessingSettings(ModConfigSpec.Builder b) {
        b.push("postProcessing");
        enabled = b.define("enabled", true);
        depthOfField = b.define("depthOfField", true);
        autofocus = b.define("autofocus", true);
        rainBloomFog = b.define("rainBloomFog", true);
        dofSamples = b.defineInRange("dofSamples", 10, 2, 64);
        motionSamples = b.defineInRange("motionSamples", 8, 2, 32);
        agxLook = b.defineInRange("agxLook", 0, 0, 2);
        toneMapping = b.define("toneMapping", "uchimura",
            v -> v instanceof String s && java.util.Set.of("uchimura", "aces", "agx", "prime").contains(s));
        exposureEV = b.defineInRange("exposureEV", 0.0D, -10.0D, 10.0D);
        autoExposureStrength = b.defineInRange("autoExposureStrength", 0.6D, 0.0D, 1.0D);
        centerWeight = b.defineInRange("centerWeight", 4.0D, 1.0D, 8.0D);
        exposureTendency = b.defineInRange("exposureTendency", 1.0D, 0.1D, 8.0D);
        bloomIntensity = b.defineInRange("bloomIntensity", 1.2D, 0.0D, 10.0D);
        vignetteStrength = b.defineInRange("vignetteStrength", 1.0D, 0.0D, 5.0D);
        saturation = b.defineInRange("saturation", 1.0D, 0.0D, 2.0D);
        contrast = b.defineInRange("contrast", 1.0D, 0.1D, 2.0D);
        blackTightness = b.defineInRange("blackTightness", 1.0D, 0.1D, 2.0D);
        minimumBrightness = b.defineInRange("minimumBrightness", 0.0D, 0.0D, 0.1D);
        gamma = b.defineInRange("gamma", 1.0D, 0.1D, 2.0D);
        colorTemperature = b.defineInRange("colorTemperature", 6500.0D, 1000.0D, 40000.0D);
        focalLength = b.defineInRange("focalLength", 0.01D, 0.001D, 1.0D);
        apertureScale = b.defineInRange("apertureScale", 0.26D, 0.01D, 10.0D);
        manualFocusDepth = b.defineInRange("manualFocusDepth", 100.0D, 0.1D, 500.0D);
        maxBlurRadius = b.defineInRange("maxBlurRadius", 16.0D, 1.0D, 64.0D);
        motionStrength = b.defineInRange("motionStrength", 1.0D, 0.0D, 4.0D);
        sharpenStrength = b.defineInRange("sharpenStrength", 0.5D, 0.0D, 1.0D);
        chromaticR = b.defineInRange("chromaticR", 0.0D, 0.0D, 0.3D);
        chromaticG = b.defineInRange("chromaticG", 0.0D, 0.0D, 0.3D);
        chromaticB = b.defineInRange("chromaticB", 0.0D, 0.0D, 0.3D);
        distortion = b.defineInRange("distortion", 0.0D, -1.0D, 1.0D);
        rainBloomFogDensity = b.defineInRange("rainBloomFogDensity", 1.0D, 0.0D, 10.0D);
        agxMinEV = b.defineInRange("agxMinEV", -7.5D, -15.0D, 5.0D);
        agxMaxEV = b.defineInRange("agxMaxEV", 6.0D, 5.1D, 15.0D);
        b.pop();
    }
}
