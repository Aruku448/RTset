package com.rtest.client;

import org.apache.commons.lang3.tuple.Pair;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class RayTracingClientConfig {
    public static final ModConfigSpec SPEC;
    public static final RayTracingClientConfig INSTANCE;

    public final ModConfigSpec.BooleanValue persistentRtEnabled;
    public final ModConfigSpec.BooleanValue worldRasterDisplayEnabled;
    public final ModConfigSpec.IntValue worldProbeTrainingBudget;
    public final ModConfigSpec.IntValue worldSurfaceTrainingBudget;
    public final ModConfigSpec.BooleanValue persistentRtStatistics;
    public final ModConfigSpec.ConfigValue<String> rtEvaluationMode;
    public final ModConfigSpec.IntValue persistentRtUpdateIntervalMs;
    public final ModConfigSpec.IntValue persistentRtMaxAgeMs;
    public final ModConfigSpec.IntValue persistentRtMinimumSamples;
    public final ModConfigSpec.IntValue persistentRtTrainingBudget;
    public final ModConfigSpec.IntValue persistentWorldMaxAgeMs;
    public final ModConfigSpec.IntValue persistentWorldTrainingBudget;
    public final ModConfigSpec.BooleanValue gpuLightTreeEnabled;
    public final ModConfigSpec.DoubleValue sunIntensity;
    public final ModConfigSpec.BooleanValue sunDaylightIntensityEnabled;
    public final ModConfigSpec.DoubleValue sunDaylightPeakIntensity;
    public final ModConfigSpec.DoubleValue sunAngularRadiusDegrees;
    public final ModConfigSpec.IntValue sunShadowSamples;
    public final ModConfigSpec.DoubleValue sunColorTemperature;
    public final ModConfigSpec.DoubleValue ambientColorTemperature;
    public final ModConfigSpec.DoubleValue shadowStrength;
    public final ModConfigSpec.BooleanValue skyboxTextureEnabled;
    public final ModConfigSpec.BooleanValue skyImportanceSamplingEnabled;
    public final ModConfigSpec.BooleanValue skyCdfHardwareEnabled;
    public final ModConfigSpec.DoubleValue skyboxTextureOpacity;
    public final ModConfigSpec.BooleanValue skyboxDaylightOpacityEnabled;
    public final ModConfigSpec.BooleanValue moonEnabled;
    public final ModConfigSpec.DoubleValue moonIntensity;
    public final ModConfigSpec.BooleanValue primeAtmosphereEnabled;
    public final ModConfigSpec.BooleanValue volumetricLightingEnabled;
    public final ModConfigSpec.DoubleValue volumetricLightingStrength;
    public final ModConfigSpec.DoubleValue volumetricFogDensity;
    public final ModConfigSpec.IntValue atmosphereAltitudeOffsetMeters;
    public final ModConfigSpec.IntValue volumetricLightingQuality;
    public final ModConfigSpec.IntValue volumetricShadowSamples;
    public final ModConfigSpec.DoubleValue sunAngleOffset;
    public final ModConfigSpec.DoubleValue sunAzimuthOffset;
    public final ModConfigSpec.IntValue giBounces;
    public final ModConfigSpec.ConfigValue<String> pbrFormat;
    public final ModConfigSpec.DoubleValue pbrNormalStrength;
    public final ModConfigSpec.BooleanValue pbrTextureAoEnabled;
    public final ModConfigSpec.BooleanValue pbrPorosityEnabled;
    public final ModConfigSpec.DoubleValue pbrWetnessStrength;
    public final ModConfigSpec.DoubleValue pbrEmissionStrength;
    public final ModConfigSpec.BooleanValue pbrPredefinedMetalsEnabled;
    public final ModConfigSpec.BooleanValue pbrTerrainCpuCaptureEnabled;
    public final ModConfigSpec.BooleanValue terrainLodEnabled;
    public final ModConfigSpec.IntValue terrainLodNativeRadiusChunks;
    public final ModConfigSpec.IntValue terrainLodMaxLevel;
    public final ModConfigSpec.IntValue terrainLodBuildBudget;
    public final ModConfigSpec.IntValue terrainLodQueueLimit;
    public final ModConfigSpec.BooleanValue terrainLodFarCacheEnabled;
    public final ModConfigSpec.IntValue terrainLodFarRadiusChunks;
    public final ModConfigSpec.BooleanValue terrainLodGpuTraversalEnabled;
    public final ModConfigSpec.BooleanValue pbrParallaxEnabled;
    public final ModConfigSpec.BooleanValue pbrEntityParallaxEnabled;
    public final ModConfigSpec.DoubleValue pbrParallaxDepth;
    public final ModConfigSpec.ConfigValue<String> fsrQuality;
    public final ModConfigSpec.BooleanValue hdrEnabled;
    public final ModConfigSpec.BooleanValue hdrWideGamutEnabled;
    public final ModConfigSpec.BooleanValue nativeColorDecodeEnabled;
    public final ModConfigSpec.BooleanValue primeColorManagementEnabled;
    public final ModConfigSpec.BooleanValue fluidRtEnabled;
    public final ModConfigSpec.BooleanValue dynamicEntityMvpEnabled;
    public final ModConfigSpec.BooleanValue nrdEnabled;
    public final ModConfigSpec.DoubleValue nrdStrength;
    public final ModConfigSpec.ConfigValue<String> nrdHitDistanceReconstruction;
    public final ModConfigSpec.DoubleValue nrdDiffusePrepassBlurRadius;
    public final ModConfigSpec.DoubleValue nrdSpecularPrepassBlurRadius;
    public final ModConfigSpec.DoubleValue nrdMinHitDistanceWeight;
    public final ModConfigSpec.DoubleValue nrdMinBlurRadius;
    public final ModConfigSpec.DoubleValue nrdMaxBlurRadius;
    public final ModConfigSpec.DoubleValue nrdLobeAngleFraction;
    public final ModConfigSpec.DoubleValue nrdRoughnessFraction;
    public final ModConfigSpec.DoubleValue nrdPlaneDistanceSensitivity;
    public final ModConfigSpec.DoubleValue nrdFastHistoryClampingSigmaScale;
    public final ModConfigSpec.DoubleValue nrdFireflySuppressorMinRelativeScale;
    public final ModConfigSpec.DoubleValue nrdDisocclusionThreshold;
    public final ModConfigSpec.IntValue nrdMaxAccumulatedFrameNum;
    public final ModConfigSpec.IntValue nrdMaxFastAccumulatedFrameNum;
    public final ModConfigSpec.IntValue nrdHistoryFixFrameNum;
    public final ModConfigSpec.BooleanValue nrdAntiFirefly;
    public final ModConfigSpec.BooleanValue nrdSpecularPrepassMotionOnly;
    public final ModConfigSpec.IntValue nrdHistoryFixPixelStride;
    public final ModConfigSpec.DoubleValue nrdConvergenceScale;
    public final ModConfigSpec.DoubleValue nrdConvergenceBase;
    public final ModConfigSpec.DoubleValue nrdConvergencePercent;
    public final ModConfigSpec.DoubleValue nrdDenoisingRange;
    public final ModConfigSpec.IntValue debugView;
    public final ModConfigSpec.DoubleValue emissionScale;

    private RayTracingClientConfig(ModConfigSpec.Builder builder) {
        worldRasterDisplayEnabled = builder.comment("Experimental high-frequency raster display with low-frequency RT irradiance probes. Approximate lighting; full RT remains the default reference.")
            .define("worldRasterDisplayEnabled", false);
        worldSurfaceTrainingBudget = builder.comment("Static RT surface corner samples per world tick; moving entities additionally refresh their own corners. Zero freezes surface training.")
            .defineInRange("worldSurfaceTrainingBudget",65536,0,131072);
        worldProbeTrainingBudget = builder.comment("Maximum irradiance probes updated per world tick; zero freezes training. Display misses never trigger synchronous RT repair.")
            .defineInRange("worldProbeTrainingBudget", 1024, 0, 4096);
        persistentRtStatistics = builder.comment("Research counters sample every 256th display/world invocation; logs are sampled counts, not complete ray counts.")
            .define("persistentRtStatistics", false);
        persistentRtEnabled = builder.comment("Research branch: independent persistent world lighting updates for static rough surfaces. Current primary/first-secondary visibility and direct lighting remain live. Default off.")
            .define("persistentRtEnabled", false);
        rtEvaluationMode = builder.comment("Research cost probes: full, current_direct (no continuation), current_visibility (material/visibility only). Mode changes reset screen histories.")
            .define("rtEvaluationMode", "full", value -> value instanceof String text && java.util.Set.of("full", "current_direct", "current_visibility").contains(text));
        persistentRtUpdateIntervalMs = builder.comment("Minimum interval between cache training batches; not a GPU scheduling guarantee.")
            .defineInRange("persistentRtUpdateIntervalMs", 50, 16, 1000);
        persistentRtMaxAgeMs = builder.comment("Legacy inline prototype setting, retained for config compatibility; use persistentWorldMaxAgeMs.")
            .defineInRange("persistentRtMaxAgeMs", 500, 50, 5000);
        persistentWorldMaxAgeMs = builder.comment("Independent world-ray cache lifetime; separate from legacy inline tail prototype settings.")
            .defineInRange("persistentWorldMaxAgeMs", 2000, 100, 10000);
        persistentWorldTrainingBudget = builder.comment("Maximum world-ray seeds dispatched per update batch. Zero freezes world training.")
            .defineInRange("persistentWorldTrainingBudget", 8192, 0, 65536);
        persistentRtTrainingBudget = builder.comment("Legacy inline prototype setting, retained for config compatibility; use persistentWorldTrainingBudget.")
            .defineInRange("persistentRtTrainingBudget", 4096, 0, 65536);
        persistentRtMinimumSamples = builder.comment("Distinct update epochs needed before using a cache slot.")
            .defineInRange("persistentRtMinimumSamples", 4, 1, 16);
        gpuLightTreeEnabled = builder.comment("Build large emissive light trees using GPU compute; small trees stay on CPU.").define("gpuLightTreeEnabled", true);
        sunIntensity = builder
            .comment("Solar source intensity used consistently by direct sunlight, the physical sky and solar atmospheric scattering. Values above 2 allow bright daylight under fixed display exposure.")
            .defineInRange("sunIntensity", 1.0D, 0.0D, 16.0D);
        sunDaylightIntensityEnabled = builder
            .comment("Override manual solar intensity using dimension sky light level: night baseline 4 (normalized level 0) gives intensity 3, level 15 gives the configured peak. Applies to direct light, sky and atmosphere together.")
            .define("sunDaylightIntensityEnabled", true);
        sunDaylightPeakIntensity = builder
            .comment("Solar intensity at sky light level 15 when daylight intensity is enabled. Intermediate levels use a smooth curve from the night baseline 4.")
            .defineInRange("sunDaylightPeakIntensity", 16.0D, 3.0D, 16.0D);
        sunAngularRadiusDegrees = builder
            .comment("Solar angular radius in degrees. Larger disks produce wider geometric penumbrae; disk-integrated energy stays fixed. Real Sun is about 0.27 degrees.")
            .defineInRange("sunAngularRadiusDegrees", 0.27D, 0.05D, 5.0D);
        sunShadowSamples = builder
            .comment("Primary-surface solar disk samples per frame, from 1 to 16. Higher counts reduce penumbra noise even without NRD; secondary surfaces retain one sample.")
            .defineInRange("sunShadowSamples", 4, 1, 16);
        sunColorTemperature = builder
            .comment("Sunlight color temperature in Kelvin.")
            .defineInRange("sunColorTemperature", 6500.0D, 1000.0D, 20000.0D);
        ambientColorTemperature = builder
            .comment("Environment/sky light color temperature in Kelvin.")
            .defineInRange("ambientColorTemperature", 6500.0D, 1000.0D, 20000.0D);
        shadowStrength = builder
            .comment("How strongly Shadow Rays darken direct sunlight.")
            .defineInRange("shadowStrength", 1.0D, 0.0D, 1.0D);
        skyboxTextureEnabled = builder
            .comment("Retain the PNG skybox, blended over physical sky when Prime atmosphere is enabled.")
            .define("skyboxTextureEnabled", true);
        skyImportanceSamplingEnabled = builder
            .comment("Use directional luminance importance sampling for PNG sky lighting. Disable for cosine-only A/B profiling.")
            .define("skyImportanceSamplingEnabled", true);
        skyCdfHardwareEnabled = builder
            .comment("Invert the sky CDF with one RT hardware traversal. False uses the alias table with the same distribution.")
            .define("skyCdfHardwareEnabled", true);
        skyboxDaylightOpacityEnabled = builder
            .comment("Use separate artist-authored morning and afternoon skybox opacity curves, interpolated smoothly between control points. Night brightness 0 = 15%, peak brightness 15 = 90%; morning brightness 2 dips to 8%. Manual opacity is used only when disabled.")
            .define("skyboxDaylightOpacityEnabled", true);
        skyboxTextureOpacity = builder
            .comment("Manual PNG opacity when the daylight opacity curve is disabled: 0 = physical sky, 1 = PNG. The curve overrides this value when enabled. Without physical sky the original PNG fallback is unchanged. Sun disk and direct sunlight remain independent.")
            .defineInRange("skyboxTextureOpacity", 0.25D, 0.0D, 1.0D);
        moonEnabled = builder
            .comment("Show and illuminate with the Minecraft moon and eight phases, including colored transparent shadows and finite-segment lunar scattering.")
            .define("moonEnabled", true);
        moonIntensity = builder
            .comment("Full-moon disk-integrated irradiance: artistic night lighting, not lux. Scales visible disk, surface NEE and lunar volume together; phase/new-moon and shadowing remain active.")
            .defineInRange("moonIntensity", 0.06D, 0.0D, 1.0D);
        primeAtmosphereEnabled = builder
            .comment("Experimental Prime 26.3 physical sky and four-wave finite-segment aerial. Local-emitter volume uses the same medium and remains a separate NRD diffuse signal; GPU image validation is pending.")
            .define("primeAtmosphereEnabled", false);
        volumetricLightingEnabled = builder
            .comment("Enable finite-segment aerial perspective and sun shafts. With Prime atmosphere enabled, uses the pinned four-wave medium and multiple-scattering LUT.")
            .define("volumetricLightingEnabled", true);
        volumetricLightingStrength = builder
            .comment("Strength of the aerial perspective and single-scattering sun volume.")
            .defineInRange("volumetricLightingStrength", 1.0D, 0.0D, 2.0D);
        volumetricFogDensity = builder
            .comment("Legacy RGB fog density. With Prime atmosphere enabled, this sets physical aerosol density from 0 to 16 times the pinned baseline and rebuilds the medium and complete static LUT; zero retains gas and Rayleigh scattering. Dense settings also attenuate direct light strongly.")
            .defineInRange("volumetricFogDensity", 1.0D, 0.0D, 16.0D);
        atmosphereAltitudeOffsetMeters = builder
            .comment("Physical atmosphere altitude offset in metres. Changes the eye-radius and dynamic sky LUT without rebuilding the static medium.")
            .defineInRange("atmosphereAltitudeOffsetMeters", 300, 0, 10_000);
        volumetricLightingQuality = builder
            .comment("Atmosphere integration quality: 1=performance, 2=balanced, 3=quality.")
            .defineInRange("volumetricLightingQuality", 2, 1, 3);
        volumetricShadowSamples = builder
            .comment("Physical atmosphere RGB shadow budget per sun/moon: 0=all integration steps, 1-16=stratified nonnegative direct scattering (surface segments only; sky uses all steps). Fewer samples reduce ray cost but increase volume noise; medium/transmittance/multiple scattering keep full quality.")
            .defineInRange("volumetricShadowSamples", 0, 0, 16);
        sunAngleOffset = builder
            .comment("Additional sun rotation in degrees applied to the Minecraft sun angle.")
            .defineInRange("sunAngleOffset", 0.0D, -180.0D, 180.0D);
        sunAzimuthOffset = builder
            .comment("Horizontal rotation of the sun path: 0 degrees is east-to-west, 90 degrees is north-to-south.")
            .defineInRange("sunAzimuthOffset", 0.0D, -180.0D, 180.0D);
        giBounces = builder
            .comment("Number of GI continuation rays per path; the Primary ray is not counted. 1=Debug, 2=Performance, 3=Balanced/Default, 4=Quality.")
            .defineInRange("giBounces", 1, 1, 4);
        pbrFormat = builder
            .comment("PBR companion texture format: labpbr, classic, or bedrock.")
            .define("pbrFormat", "labpbr",
                value -> value instanceof String && pbrFormatCode((String)value) >= 0);
        pbrNormalStrength = builder
            .comment("Tangent-space normal map strength for PBR companion textures.")
            .defineInRange("pbrNormalStrength", 1.0D, 0.0D, 3.0D);
        pbrTextureAoEnabled = builder
            .comment("Use the LabPBR normal-map blue channel as texture AO, matching Sundial.")
            .define("pbrTextureAoEnabled", false);
        pbrPorosityEnabled = builder
            .comment("Enable LabPBR porosity decoding and rain-driven wet-surface response.")
            .define("pbrPorosityEnabled", true);
        pbrWetnessStrength = builder
            .comment("Strength of rain wetness applied from LabPBR porosity.")
            .defineInRange("pbrWetnessStrength", 1.0D, 0.0D, 1.0D);
        pbrEmissionStrength = builder
            .comment("Multiplier for authored PBR emissiveness; values up to 20 are supported for high-intensity glow.")
            .defineInRange("pbrEmissionStrength", 1.0D, 0.0D, 20.0D);
        pbrPredefinedMetalsEnabled = builder
            .comment("Use LabPBR predefined metal F0 values for metal IDs 230 through 237.")
            .define("pbrPredefinedMetalsEnabled", true);
        pbrTerrainCpuCaptureEnabled = builder
            .comment("Capture terrain through block sprites so PBR companion maps retain their material IDs.")
            .define("pbrTerrainCpuCaptureEnabled", true);
        terrainLodEnabled = builder
            .comment("Enable MVP terrain LOD; only opaque static terrain is supported. Transparent, fluid, emissive, and dynamic entity geometry is never degraded.")
            .define("terrainLodEnabled", true);
        terrainLodNativeRadiusChunks = builder
            .comment("Native-detail radius around the camera for terrain LOD, in chunks.")
            .defineInRange("terrainLodNativeRadiusChunks", 8, 2, 64);
        terrainLodMaxLevel = builder
            .comment("Maximum terrain LOD level: 1=32^3 blocks, 2=64^3 blocks.")
            .defineInRange("terrainLodMaxLevel", 2, 1, 2);
        terrainLodBuildBudget = builder
            .comment("Maximum number of terrain LOD builds started per frame.")
            .defineInRange("terrainLodBuildBudget", 1, 1, 8);
        terrainLodQueueLimit = builder
            .comment("Maximum queued terrain LOD builds.")
            .defineInRange("terrainLodQueueLimit", 32, 4, 256);
        terrainLodFarCacheEnabled = builder
            .comment("Retain immutable opaque terrain proxies after chunks leave the loaded client window. Unknown chunks are never fabricated.")
            .define("terrainLodFarCacheEnabled", true);
        terrainLodFarRadiusChunks = builder
            .comment("Maximum camera distance for cached far-terrain proxies, in chunks.")
            .defineInRange("terrainLodFarRadiusChunks", 64, 8, 256);
        terrainLodGpuTraversalEnabled = builder
            .comment("Use the experimental GPU terrain node/Hi-Z traversal to rewrite static TLAS instance masks before tracing.")
            .define("terrainLodGpuTraversalEnabled", false);
        pbrParallaxEnabled = builder
            .comment("Enable RT parallax occlusion mapping from the normal companion alpha channel.")
            .define("pbrParallaxEnabled", true);
        pbrEntityParallaxEnabled = builder
            .comment("Apply parallax occlusion mapping to entity, item, and block-entity materials.")
            .define("pbrEntityParallaxEnabled", true);
        pbrParallaxDepth = builder
            .comment("PBR parallax depth in texture-height units; 1.0 matches Sundial's default.")
            .defineInRange("pbrParallaxDepth", 1.0D, 0.0D, 4.0D);
        fsrQuality = builder
            .comment("FSR quality preset: native_aa, quality_75, quality, balanced, performance, ultra_performance.")
            .define("fsrQuality", "balanced");
        hdrEnabled = builder
            .comment("Use an HDR float swapchain when supported. Disabled by default for a standard sRGB/SDR output.")
            .define("hdrEnabled", false);
        hdrWideGamutEnabled = builder
            .comment("EXPERIMENTAL: prefer the linear Rec.2020 surface so the RT wide gamut reaches the compositor. "
                + "Only enable when the compositor really performs a linear-to-display conversion for "
                + "VK_COLOR_SPACE_BT2020_LINEAR_EXT; KWin treats it as sRGB pass-through, which makes the decoded "
                + "native content far too dark. Leave false to use linear scRGB, where the extended range works "
                + "but the 3D gamut is BT.709.")
            .define("hdrWideGamutEnabled", false);
        nativeColorDecodeEnabled = builder
            .comment("EXPERIMENTAL: decode vanilla fragment output into the HDR linear target. Disable when the compositor "
                + "presents the float surface without applying the matching linear-to-display transfer.")
            .define("nativeColorDecodeEnabled", false);
        primeColorManagementEnabled = builder
            .comment("Apply Prime 26.3 RGB Reinhard gamut compression, highlight rolloff, and hue repair to SDR output.")
            .define("primeColorManagementEnabled", true);
        fluidRtEnabled = builder
            .comment("Capture vanilla fluid surfaces in the RT scene. Disable to use the compiled terrain fallback.")
            .define("fluidRtEnabled", true);
        dynamicEntityMvpEnabled = builder
            .comment("Legacy compatibility value. The complete-world RT path requires captured entity and block-entity geometry and re-enables this value when F8 starts RT.")
            .define("dynamicEntityMvpEnabled", true);
        nrdEnabled = builder
            .comment("Enable the NRD 4.17.3 ReBLUR diffuse/specular denoiser for opaque primary surfaces.")
            .define("nrdEnabled", true);
        nrdStrength = builder
            .comment("Blend stochastic indirect and reflected lighting toward NRD output; direct light and visible emission stay unfiltered.")
            .defineInRange("nrdStrength", 1.0D, 0.0D, 1.0D);
        nrdHitDistanceReconstruction = builder
            .comment("NRD hit-distance reconstruction kernel: off, area_3x3, or area_5x5.")
            .define("nrdHitDistanceReconstruction", "area_3x3");
        nrdDiffusePrepassBlurRadius = builder
            .comment("REBLUR diffuse pre-pass radius in pixels; disabled by default to preserve detail and skip its dispatch.")
            .defineInRange("nrdDiffusePrepassBlurRadius", 0.0D, 0.0D, 96.0D);
        nrdSpecularPrepassBlurRadius = builder
            .comment("REBLUR specular pre-pass radius in pixels; disabled by default to preserve detail and skip its dispatch.")
            .defineInRange("nrdSpecularPrepassBlurRadius", 0.0D, 0.0D, 96.0D);
        nrdMinHitDistanceWeight = builder
            .comment("REBLUR minimum hit-distance weight; larger values suppress shadow sensitivity.")
            .defineInRange("nrdMinHitDistanceWeight", 0.10D, 0.0001D, 0.2D);
        nrdMinBlurRadius = builder
            .comment("REBLUR minimum spatial blur radius after convergence; small by default to retain contact detail.")
            .defineInRange("nrdMinBlurRadius", 0.5D, 0.0D, 16.0D);
        nrdMaxBlurRadius = builder
            .comment("REBLUR base spatial blur radius before convergence reduces it; detail-first default.")
            .defineInRange("nrdMaxBlurRadius", 8.0D, 1.0D, 96.0D);
        nrdLobeAngleFraction = builder
            .comment("REBLUR normal rejection sensitivity as a fraction of the lobe angle.")
            .defineInRange("nrdLobeAngleFraction", 0.15D, 0.001D, 1.0D);
        nrdRoughnessFraction = builder
            .comment("REBLUR roughness rejection sensitivity.")
            .defineInRange("nrdRoughnessFraction", 0.15D, 0.001D, 1.0D);
        nrdPlaneDistanceSensitivity = builder
            .comment("REBLUR allowed deviation from the local tangent plane.")
            .defineInRange("nrdPlaneDistanceSensitivity", 0.02D, 0.0001D, 0.2D);
        nrdFastHistoryClampingSigmaScale = builder
            .comment("REBLUR standard-deviation scale used when clamping main history to fast history.")
            .defineInRange("nrdFastHistoryClampingSigmaScale", 2.0D, 1.0D, 3.0D);
        nrdFireflySuppressorMinRelativeScale = builder
            .comment("REBLUR firefly suppression scale; the aggressive default targets sparse emissive-light samples.")
            .defineInRange("nrdFireflySuppressorMinRelativeScale", 1.0D, 1.0D, 3.0D);
        nrdDisocclusionThreshold = builder
            .comment("REBLUR relative depth threshold used to reject history during disocclusion.")
            .defineInRange("nrdDisocclusionThreshold", 0.01D, 0.0001D, 0.2D);
        nrdMaxAccumulatedFrameNum = builder
            .comment("Maximum REBLUR main history length, from 1 to NRD's limit of 63 frames.")
            .defineInRange("nrdMaxAccumulatedFrameNum", 48, 1, 63);
        nrdMaxFastAccumulatedFrameNum = builder
            .comment("Maximum REBLUR fast history length; it is clamped to the main history length.")
            .defineInRange("nrdMaxFastAccumulatedFrameNum", 12, 1, 63);
        nrdHistoryFixFrameNum = builder
            .comment("Number of reconstructed frames after a history reset.")
            .defineInRange("nrdHistoryFixFrameNum", 3, 0, 16);
        nrdAntiFirefly = builder
            .comment("Enable NRD's firefly suppression pass for sparse emissive-light samples.")
            .define("nrdAntiFirefly", true);
        nrdSpecularPrepassMotionOnly = builder
            .comment("Use the specular pre-pass only for motion estimation, not visible filtering.")
            .define("nrdSpecularPrepassMotionOnly", true);
        nrdHistoryFixPixelStride = builder
            .comment("Base stride of NRD's 5x5 history reconstruction kernel; keep repair local to reduce smearing.")
            .defineInRange("nrdHistoryFixPixelStride", 4, 1, 64);
        nrdConvergenceScale = builder
            .comment("REBLUR convergence scale; larger values reduce blur faster after accumulation.")
            .defineInRange("nrdConvergenceScale", 1.0D, 0.1D, 4.0D);
        nrdConvergenceBase = builder
            .comment("REBLUR short-history convergence base.")
            .defineInRange("nrdConvergenceBase", 0.2D, 0.0D, 1.0D);
        nrdConvergencePercent = builder
            .comment("Fraction of maximum history affected by the short-history convergence base.")
            .defineInRange("nrdConvergencePercent", 0.8D, 0.0D, 1.0D);
        nrdDenoisingRange = builder
            .comment("Maximum view-space distance considered valid by NRD; sky uses a larger sentinel.")
            .defineInRange("nrdDenoisingRange", 60000.0D, 256.0D, 60000.0D);
        debugView = builder
            .comment("RT diagnostic view: 0=off, 1=direct diffuse AOV, 2=area-light NEE only, "
                + "3=emission AOV, 4=indirect diffuse AOV, 5=light-tree emitter count, "
                + "6=staged light-tree probe, 7=sampled-emitter geometry (R=far, G=front-facing), "
                + "8=area-light NEE without the MIS weight, 9=emitter blocker, "
                + "10=air scattering L x100, 11=air shadow deficit x100.")
            .defineInRange("debugView", 0, 0, 11);
        emissionScale = builder
            .comment("Radiance scale applied to block-light emission, for both the directly visible"
                + " glow and the light-tree area-light NEE. Minecraft light level 15 maps to 1.0 at"
                + " scale 1.0, which is far below the sun's effective irradiance scale, so an indoor"
                + " scene renders nearly black next to clipped lamps. Raise this until lantern-lit"
                + " rooms read correctly.")
            .defineInRange("emissionScale", 25.0D, 0.0D, 256.0D);
    }

    public void save() {
        SPEC.save();
    }

    int pbrFormatCode() {
        return pbrFormatCode(pbrFormat.get());
    }

    int pbrFeatureMask() {
        int mask = 0;
        if (pbrTextureAoEnabled.get()) mask |= 1;
        if (pbrPorosityEnabled.get()) mask |= 2;
        if (pbrPredefinedMetalsEnabled.get()) mask |= 4;
        if (pbrEmissionStrength.get() > 0.0001D) mask |= 8;
        return mask;
    }

    int pbrPackedMode() {
        return pbrFormatCode() | (pbrFeatureMask() << 8);
    }

    int pbrParallaxFlags() {
        return (pbrParallaxEnabled.get() ? 1 : 0) | (pbrEntityParallaxEnabled.get() ? 2 : 0);
    }

    private static int pbrFormatCode(String format) {
        return switch (format == null ? "" : format.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "labpbr", "lab" -> 0;
            case "classic" -> 1;
            case "bedrock", "bedrockrtx" -> 2;
            default -> -1;
        };
    }

    static {
        Pair<RayTracingClientConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(RayTracingClientConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }
}
