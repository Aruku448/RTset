package com.rtest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.NarratorStatus;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.narration.NarratableEntry;

public final class RayTracingSettingsScreen extends Screen {
    private static final int CATEGORY_COUNT = 6;
    private static final int ROW_HEIGHT = 30;
    private static final String[] CATEGORY_KEYS = {
        "screen.rtest.settings.category.lighting",
        "screen.rtest.settings.category.pathTracing",
        "screen.rtest.settings.category.reconstruction",
        "screen.rtest.settings.category.denoiser",
        "screen.rtest.settings.category.output",
        "screen.rtest.settings.category.post"
    };
    private static final String[] CATEGORY_DESCRIPTION_KEYS = {
        "screen.rtest.settings.category.lighting.description",
        "screen.rtest.settings.category.pathTracing.description",
        "screen.rtest.settings.category.reconstruction.description",
        "screen.rtest.settings.category.denoiser.description",
        "screen.rtest.settings.category.output.description",
        "screen.rtest.settings.category.post.description"
    };

    private final Screen parent;
    private final List<Button> categoryButtons = new ArrayList<>();
    private SettingsList settingsList;
    private StringWidget categoryDescription;
    private Button offlineButton;
    private int selectedCategory;
    private final double initialEmissionScale;

    public RayTracingSettingsScreen(Screen parent) {
        super(Component.translatable("screen.rtest.settings.title"));
        this.parent = parent;
        this.initialEmissionScale = RayTracingClientConfig.INSTANCE.emissionScale.get();
    }

    @Override
    protected void init() {
        SettingsLayout layout = SettingsLayout.forScreen(this.width, this.height, CATEGORY_COUNT);
        int panelWidth = layout.width();
        int left = layout.left();
        this.addRenderableWidget(new StringWidget(left, 6, panelWidth, 20, this.title, this.font));

        this.categoryButtons.clear();
        int categoryWidth = (panelWidth - (layout.tabColumns() - 1) * 4) / layout.tabColumns();
        for (int index = 0; index < CATEGORY_COUNT; index++) {
            int category = index;
            Button button = this.addRenderableWidget(Button.builder(
                    Component.translatable(CATEGORY_KEYS[index]), ignored -> this.selectCategory(category))
                .bounds(left + (index % layout.tabColumns()) * (categoryWidth + 4),
                    30 + (index / layout.tabColumns()) * 24, categoryWidth, 20)
                .build());
            this.categoryButtons.add(button);
        }
        this.categoryDescription = this.addRenderableWidget(new StringWidget(
            left, layout.listTop() - 20, panelWidth, 16, Component.empty(), this.font));
        this.categoryDescription.setMaxWidth(panelWidth);
        this.categoryDescription.setTooltip(Tooltip.create(
            Component.translatable(CATEGORY_DESCRIPTION_KEYS[this.selectedCategory])));
        this.settingsList = this.addRenderableWidget(new SettingsList(
            this.minecraft, left, panelWidth, layout.listTop(), layout.listBottom(), layout.columns()));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
            .bounds(left + (panelWidth - Math.min(240, panelWidth)) / 2, layout.footerTop(),
                Math.min(240, panelWidth), 20).build());
        this.selectCategory(Math.min(this.selectedCategory, CATEGORY_COUNT - 1));
    }

    private void selectCategory(int category) {
        this.selectedCategory = category;
        if (this.categoryDescription != null) {
            this.categoryDescription.setMessage(Component.translatable(CATEGORY_DESCRIPTION_KEYS[category]));
            this.categoryDescription.setTooltip(Tooltip.create(Component.translatable(CATEGORY_DESCRIPTION_KEYS[category])));
        }
        for (int index = 0; index < this.categoryButtons.size(); index++) {
            Component title = Component.translatable(CATEGORY_KEYS[index]);
            this.categoryButtons.get(index).setMessage(index == category
                ? Component.literal("▶ ").append(title)
                : title);
        }
        if (this.settingsList != null) {
            this.settingsList.setEntries(this.entriesFor(category));
        }
    }

    private List<SettingsEntry> entriesFor(int category) {
        return switch (category) {
            case 0 -> lightingEntries();
            case 1 -> pathTracingEntries();
            case 2 -> reconstructionEntries();
            case 3 -> denoiserEntries();
            case 4 -> outputEntries();
            case 5 -> postEntries();
            default -> List.of();
        };
    }

    private List<SettingsEntry> postEntries() {
        var p = RayTracingClientConfig.INSTANCE.post;
        return List.of(postToggle("enabled", p.enabled), postToggle("depthOfField", p.depthOfField),
            postToggle("autofocus", p.autofocus), postToggle("rainBloomFog", p.rainBloomFog),
            choice("screen.rtest.settings.post.toneMapping", p.toneMapping, List.of("uchimura", "aces", "agx", "prime")),
            number("screen.rtest.settings.post.dofSamples", p.dofSamples, 2D, 64D),
            number("screen.rtest.settings.post.motionSamples", p.motionSamples, 2D, 32D),
            choice("screen.rtest.settings.post.agxLook", p.agxLook, List.of(0, 1, 2)),
            postSlider("exposureEV", p.exposureEV, -10D, 10D),
            postSlider("autoExposureStrength", p.autoExposureStrength, 0D, 1D),
            postSlider("bloomIntensity", p.bloomIntensity, 0D, 10D),
            postSlider("vignetteStrength", p.vignetteStrength, 0D, 5D),
            postSlider("saturation", p.saturation, 0D, 2D),
            postSlider("contrast", p.contrast, 0.1D, 2D),
            postSlider("blackTightness", p.blackTightness, 0.1D, 2D),
            postSlider("minimumBrightness", p.minimumBrightness, 0D, 0.1D),
            postSlider("gamma", p.gamma, 0.1D, 2D),
            postSlider("colorTemperature", p.colorTemperature, 1000D, 40000D),
            postSlider("focalLength", p.focalLength, 0.001D, 1D),
            postSlider("apertureScale", p.apertureScale, 0.01D, 10D),
            postSlider("manualFocusDepth", p.manualFocusDepth, 0.1D, 500D),
            postSlider("maxBlurRadius", p.maxBlurRadius, 1D, 64D),
            postSlider("motionStrength", p.motionStrength, 0D, 4D),
            postSlider("sharpenStrength", p.sharpenStrength, 0D, 1D),
            postSlider("chromaticR", p.chromaticR, 0D, 0.3D),
            postSlider("chromaticG", p.chromaticG, 0D, 0.3D),
            postSlider("chromaticB", p.chromaticB, 0D, 0.3D),
            postSlider("distortion", p.distortion, -1D, 1D),
            postSlider("rainBloomFogDensity", p.rainBloomFogDensity, 0D, 10D),
            postSlider("centerWeight", p.centerWeight, 1D, 8D),
            postSlider("exposureTendency", p.exposureTendency, 0.1D, 8D),
            postSlider("agxMinEV", p.agxMinEV, -15D, 5D),
            postSlider("agxMaxEV", p.agxMaxEV, 5.1D, 15D));
    }
    private SettingsEntry postSlider(String name,
        net.neoforged.neoforge.common.ModConfigSpec.DoubleValue value, double min,
        double max) {
        return number("screen.rtest.settings.post." + name, value, min, max);
    }
    private SettingsEntry postToggle(
        String name, net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        return choice("screen.rtest.settings.post." + name, value, List.of(true, false));
    }

    private List<SettingsEntry> lightingEntries() {
        var c = RayTracingClientConfig.INSTANCE;
        return List.of(
            number("screen.rtest.settings.sunIntensity", c.sunIntensity, 0.0D, 16.0D),
            choice("screen.rtest.settings.sunDaylightIntensityEnabled", c.sunDaylightIntensityEnabled, List.of(true, false)),
            number("screen.rtest.settings.sunDaylightPeakIntensity", c.sunDaylightPeakIntensity, 3.0D, 16.0D),
            number("screen.rtest.settings.sunAngularRadiusDegrees", c.sunAngularRadiusDegrees, 0.05D, 5.0D),
            choice("screen.rtest.settings.sunShadowSamples", c.sunShadowSamples, List.of(1, 2, 4, 8, 16)),
            number("screen.rtest.settings.sunColorTemperature", c.sunColorTemperature, 1000.0D, 20000.0D),
            number("screen.rtest.settings.ambientColorTemperature", c.ambientColorTemperature, 1000.0D, 20000.0D),
            number("screen.rtest.settings.shadowStrength", c.shadowStrength, 0.0D, 1.0D),
            number("screen.rtest.settings.emissionScale", c.emissionScale, 0.0D, 256.0D),
            choice("screen.rtest.settings.skyboxTextureEnabled", c.skyboxTextureEnabled, List.of(true, false)),
            choice("screen.rtest.settings.skyboxDaylightOpacityEnabled", c.skyboxDaylightOpacityEnabled, List.of(true, false)),
            number("screen.rtest.settings.skyboxTextureOpacity", c.skyboxTextureOpacity, 0.0D, 1.0D),
            choice("screen.rtest.settings.moonEnabled", c.moonEnabled, List.of(true, false)),
            number("screen.rtest.settings.moonIntensity", c.moonIntensity, 0.0D, 1.0D),
            choice("screen.rtest.settings.primeAtmosphereEnabled", c.primeAtmosphereEnabled, List.of(true, false)),
            choice("screen.rtest.settings.volumetricLightingEnabled", c.volumetricLightingEnabled, List.of(true, false)),
            number("screen.rtest.settings.volumetricLightingStrength", c.volumetricLightingStrength, 0.0D, 2.0D),
            number("screen.rtest.settings.volumetricFogDensity", c.volumetricFogDensity, 0.0D, 16.0D),
            number("screen.rtest.settings.atmosphereAltitudeOffsetMeters", c.atmosphereAltitudeOffsetMeters, 0.0D, 10000.0D),
            choice("screen.rtest.settings.volumetricLightingQuality", c.volumetricLightingQuality, List.of(1, 2, 3)),
            choice("screen.rtest.settings.atmosphereDiagnostic", c.debugView, List.of(0, 10, 11)),
            number("screen.rtest.settings.sunAngleOffset", c.sunAngleOffset, -180.0D, 180.0D),
            number("screen.rtest.settings.sunAzimuthOffset", c.sunAzimuthOffset, -180.0D, 180.0D)
        );
    }

    private static SettingsEntry restirToggle(String name, net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value) {
        return choice("screen.rtest.settings." + name, value, List.of(true, false));
    }

    private List<SettingsEntry> pathTracingEntries() {
        var c = RayTracingClientConfig.INSTANCE;
        return List.of(
            restirToggle("restirDirectEnabled", c.restirDirectEnabled),
            restirToggle("restirSuffixEnabled", c.restirSuffixEnabled),
            number("screen.rtest.settings.restirCandidates", c.restirCandidates, 1D, 16D),
            number("screen.rtest.settings.restirSpatialNeighbors", c.restirSpatialNeighbors, 0D, 4D),
            number("screen.rtest.settings.restirGatherPrefixes", c.restirGatherPrefixes, 1D, 4D),
            choice("screen.rtest.settings.giBounces", c.giBounces, List.of(1, 2, 3, 4)),
            choice("screen.rtest.settings.pbrFormat", c.pbrFormat, List.of("labpbr", "classic", "bedrock")),
            number("screen.rtest.settings.pbrNormalStrength", c.pbrNormalStrength, 0.0D, 3.0D),
            choice("screen.rtest.settings.pbrTextureAoEnabled", c.pbrTextureAoEnabled, List.of(true, false)),
            choice("screen.rtest.settings.pbrPorosityEnabled", c.pbrPorosityEnabled, List.of(true, false)),
            number("screen.rtest.settings.pbrWetnessStrength", c.pbrWetnessStrength, 0.0D, 1.0D),
            number("screen.rtest.settings.pbrEmissionStrength", c.pbrEmissionStrength, 0.0D, 20.0D),
            choice("screen.rtest.settings.pbrPredefinedMetalsEnabled", c.pbrPredefinedMetalsEnabled, List.of(true, false)),
            choice("screen.rtest.settings.pbrTerrainCpuCaptureEnabled", c.pbrTerrainCpuCaptureEnabled, List.of(true, false)),
            choice("screen.rtest.settings.pbrParallaxEnabled", c.pbrParallaxEnabled, List.of(true, false)),
            choice("screen.rtest.settings.pbrEntityParallaxEnabled", c.pbrEntityParallaxEnabled, List.of(true, false)),
            number("screen.rtest.settings.pbrParallaxDepth", c.pbrParallaxDepth, 0.0D, 4.0D)
        );
    }

    private List<SettingsEntry> reconstructionEntries() {
        var c = RayTracingClientConfig.INSTANCE;
        return List.of(
            choice("screen.rtest.settings.terrainLodEnabled", c.terrainLodEnabled, List.of(true, false)),
            choice("screen.rtest.settings.terrainLodFarCacheEnabled", c.terrainLodFarCacheEnabled, List.of(true, false)),
            choice("screen.rtest.settings.terrainLodGpuTraversalEnabled", c.terrainLodGpuTraversalEnabled, List.of(true, false)),
            choice("screen.rtest.settings.fluidRtEnabled", c.fluidRtEnabled, List.of(true, false)),
            choice("screen.rtest.settings.upscaler", c.upscaler, List.of("fsr", "dlss", "dlss_rr")),
            choice("screen.rtest.settings.fsrQuality", c.fsrQuality, List.of("native_aa", "quality_75", "quality", "balanced", "performance", "ultra_performance"))
        );
    }

    private List<SettingsEntry> outputEntries() {
        this.offlineButton = Button.builder(this.offlineButtonMessage(), button -> {
                OfflineRenderController.toggle(this.minecraft);
                button.setMessage(this.offlineButtonMessage());
            })
            .bounds(0, 0, 320, 20)
            .build();
        this.offlineButton.setTooltip(Tooltip.create(
            Component.translatable("screen.rtest.settings.offlineRender.tip")));
        return List.of(
            choice("screen.rtest.settings.primeColorManagementEnabled", RayTracingClientConfig.INSTANCE.primeColorManagementEnabled, List.of(true, false)),
            choice("screen.rtest.settings.hdrEnabled", RayTracingClientConfig.INSTANCE.hdrEnabled, List.of(true, false)),
            choice("screen.rtest.settings.hdrWideGamutEnabled", RayTracingClientConfig.INSTANCE.hdrWideGamutEnabled, List.of(true, false)),
            choice("screen.rtest.settings.nativeColorDecodeEnabled", RayTracingClientConfig.INSTANCE.nativeColorDecodeEnabled, List.of(true, false)),
            new SettingsEntry(this.offlineButton)
        );
    }

    private Component offlineButtonMessage() {
        return OfflineRenderController.active()
            ? Component.translatable("screen.rtest.settings.offlineRender.active",
                OfflineRenderController.sampleCount())
            : Component.translatable("screen.rtest.settings.offlineRender.inactive");
    }

    @Override
    public void tick() {
        super.tick();
        if (this.offlineButton != null) {
            this.offlineButton.setMessage(this.offlineButtonMessage());
        }
    }

    private List<SettingsEntry> denoiserEntries() {
        var c = RayTracingClientConfig.INSTANCE;
        return List.of(
            choice("screen.rtest.settings.nrdEnabled", c.nrdEnabled, List.of(true, false)),
            choice("screen.rtest.settings.nrdEntityEnabled", c.nrdEntityEnabled, List.of(true, false)),
            number("screen.rtest.settings.nrdStrength", c.nrdStrength, 0.0D, 1.0D),
            choice("screen.rtest.settings.nrdHitDistanceReconstruction", c.nrdHitDistanceReconstruction, List.of("off", "area_3x3", "area_5x5")),
            number("screen.rtest.settings.nrdDiffusePrepassBlurRadius", c.nrdDiffusePrepassBlurRadius, 0.0D, 96.0D),
            number("screen.rtest.settings.nrdSpecularPrepassBlurRadius", c.nrdSpecularPrepassBlurRadius, 0.0D, 96.0D),
            number("screen.rtest.settings.nrdMinHitDistanceWeight", c.nrdMinHitDistanceWeight, 0.0001D, 0.2D),
            number("screen.rtest.settings.nrdMinBlurRadius", c.nrdMinBlurRadius, 0.0D, 16.0D),
            number("screen.rtest.settings.nrdMaxBlurRadius", c.nrdMaxBlurRadius, 1.0D, 96.0D),
            number("screen.rtest.settings.nrdLobeAngleFraction", c.nrdLobeAngleFraction, 0.001D, 1.0D),
            number("screen.rtest.settings.nrdRoughnessFraction", c.nrdRoughnessFraction, 0.001D, 1.0D),
            number("screen.rtest.settings.nrdPlaneDistanceSensitivity", c.nrdPlaneDistanceSensitivity, 0.0001D, 0.2D),
            number("screen.rtest.settings.nrdFastHistoryClampingSigmaScale", c.nrdFastHistoryClampingSigmaScale, 1.0D, 3.0D),
            number("screen.rtest.settings.nrdFireflySuppressorMinRelativeScale", c.nrdFireflySuppressorMinRelativeScale, 1.0D, 3.0D),
            number("screen.rtest.settings.nrdDisocclusionThreshold", c.nrdDisocclusionThreshold, 0.0001D, 0.2D),
            choice("screen.rtest.settings.nrdMaxAccumulatedFrameNum", c.nrdMaxAccumulatedFrameNum, List.of(1, 2, 4, 6, 8, 12, 16, 24, 30, 48, 63)),
            choice("screen.rtest.settings.nrdMaxFastAccumulatedFrameNum", c.nrdMaxFastAccumulatedFrameNum, List.of(1, 2, 3, 4, 6, 8, 12, 16, 24, 30, 48, 63)),
            choice("screen.rtest.settings.nrdHistoryFixFrameNum", c.nrdHistoryFixFrameNum, List.of(0, 1, 2, 3, 4, 6, 8, 12, 16)),
            choice("screen.rtest.settings.nrdAntiFirefly", c.nrdAntiFirefly, List.of(true, false)),
            choice("screen.rtest.settings.nrdSpecularPrepassMotionOnly", c.nrdSpecularPrepassMotionOnly, List.of(false, true)),
            choice("screen.rtest.settings.nrdHistoryFixPixelStride", c.nrdHistoryFixPixelStride, List.of(1, 2, 4, 7, 10, 14, 20, 28, 40, 64)),
            number("screen.rtest.settings.nrdConvergenceScale", c.nrdConvergenceScale, 0.1D, 4.0D),
            number("screen.rtest.settings.nrdConvergenceBase", c.nrdConvergenceBase, 0.0D, 1.0D),
            number("screen.rtest.settings.nrdConvergencePercent", c.nrdConvergencePercent, 0.0D, 1.0D),
            number("screen.rtest.settings.nrdDenoisingRange", c.nrdDenoisingRange, 256.0D, 60000.0D)
        );
    }

    private SettingsEntry number(String label,
            net.neoforged.neoforge.common.ModConfigSpec.IntValue config, double min, double max) {
        return number(label, min, max, config.get(), config.getDefault(), true,
            value -> config.set((int) Math.round(value)), () -> !config.get().equals(config.getDefault()));
    }

    private SettingsEntry number(String label,
            net.neoforged.neoforge.common.ModConfigSpec.DoubleValue config, double min, double max) {
        return number(label, min, max, config.get(), config.getDefault(), false,
            config::set, () -> !config.get().equals(config.getDefault()));
    }

    private SettingsEntry number(String label, double min, double max, double initial, double fallback,
            boolean integer, DoubleConsumer setter, java.util.function.BooleanSupplier changed) {
        SettingSlider slider = new SettingSlider(label, min, max, initial, fallback, integer, setter);
        Component defaultText = slider.valueText(fallback);
        slider.setTooltip(Tooltip.create(settingTooltip(label, defaultText).copy()
            .append(Component.literal("\n"))
            .append(Component.translatable("screen.rtest.settings.range", slider.valueText(min), slider.valueText(max)))));
        return setting(label, slider, () -> {
            setter.accept(fallback);
            slider.setConfiguredValue(fallback);
        }, changed, defaultText);
    }

    private static <T> SettingsEntry choice(String label,
            net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<T> config, List<T> presets) {
        List<T> values = new ArrayList<>(presets);
        if (!values.contains(config.getDefault())) values.add(config.getDefault());
        if (!values.contains(config.get())) values.add(config.get());
        CycleButton<T> button = CycleButton.builder(value -> choiceText(label, value), config.get())
            .withValues(values).create(0, 0, 320, 20, Component.translatable(label),
                (widget, value) -> config.set(value));
        Component defaultText = choiceText(label, config.getDefault());
        button.setTooltip(Tooltip.create(settingTooltip(label, defaultText)));
        return setting(label, button, () -> {
            config.set(config.getDefault());
            button.setValue(config.getDefault());
        }, () -> !config.get().equals(config.getDefault()), defaultText);
    }

    private static Component choiceText(String label, Object value) {
        if (value instanceof Boolean enabled) {
            return Component.translatable(enabled ? "options.on" : "options.off");
        }
        String key = label + ".value." + value;
        if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key);
        String unit = label + ".unit";
        return net.minecraft.locale.Language.getInstance().has(unit)
            ? Component.translatable(unit, value.toString()) : Component.literal(value.toString());
    }

    private static Component settingTooltip(String label, Component defaultText) {
        Component tip = Component.translatable(label + ".tip").append(Component.literal("\n"))
            .append(Component.translatable("screen.rtest.settings.default", defaultText));
        if (net.minecraft.locale.Language.getInstance().has(label + ".requires")) {
            tip = tip.copy().append(Component.literal("\n"))
                .append(Component.translatable(label + ".requires"));
        }
        return tip;
    }

    private static SettingsEntry setting(String label, AbstractWidget widget, Runnable restore,
            java.util.function.BooleanSupplier changed, Component defaultText) {
        Button reset = Button.builder(Component.literal("↺"), ignored -> restore.run())
            .bounds(0, 0, 20, 20).build();
        reset.setTooltip(Tooltip.create(Component.translatable("screen.rtest.settings.restore", defaultText)));
        return new SettingsEntry(new SettingControl(widget, reset, changed, () -> isAvailable(label)));
    }

    private static boolean isAvailable(String label) {
        var c = RayTracingClientConfig.INSTANCE;
        var p = c.post;
        if (label.startsWith("screen.rtest.settings.post.") && !label.endsWith(".enabled")) {
            if (!p.enabled.get()) return false;
            String name = label.substring("screen.rtest.settings.post.".length());
            return switch (name) {
                case "depthOfField", "rainBloomFog" -> true;
                case "autofocus", "focalLength", "apertureScale", "maxBlurRadius", "dofSamples" -> p.depthOfField.get();
                case "manualFocusDepth" -> p.depthOfField.get() && !p.autofocus.get();
                case "rainBloomFogDensity" -> p.rainBloomFog.get();
                case "agxLook", "agxMinEV", "agxMaxEV" -> p.toneMapping.get().equals("agx");
                default -> true;
            };
        }
        return switch (label.substring("screen.rtest.settings.".length())) {
            case "sunDaylightPeakIntensity" -> c.sunDaylightIntensityEnabled.get();
            case "skyboxDaylightOpacityEnabled" -> c.skyboxTextureEnabled.get();
            case "skyboxTextureOpacity" -> c.skyboxTextureEnabled.get() && !c.skyboxDaylightOpacityEnabled.get();
            case "moonIntensity" -> c.moonEnabled.get();
            case "volumetricLightingStrength", "volumetricLightingQuality" -> c.volumetricLightingEnabled.get();
            case "pbrWetnessStrength" -> c.pbrPorosityEnabled.get();
            case "pbrParallaxDepth", "pbrEntityParallaxEnabled" -> c.pbrParallaxEnabled.get();
            case "restirCandidates", "restirSpatialNeighbors" -> c.restirDirectEnabled.get() || c.restirSuffixEnabled.get();
            case "restirGatherPrefixes" -> c.restirSuffixEnabled.get();
            default -> true;
        };
    }

    @Override
    public void onClose() {
        RayTracingClientConfig.INSTANCE.save();
        if (Double.compare(this.initialEmissionScale, RayTracingClientConfig.INSTANCE.emissionScale.get()) != 0) {
            // Re-capture cached terrain materials once after editing, rather than on every slider step.
            RayTracingProbe.markSceneDirty();
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    private static final class SettingsList extends ContainerObjectSelectionList<SettingsEntry> {
        private final int columns;

        private SettingsList(Minecraft minecraft, int left, int width, int top, int bottom, int columns) {
            // 26.2 API: height and default row height, NOT screen height and bottom Y.
            super(minecraft, width, bottom - top, top, ROW_HEIGHT);
            this.columns = columns;
            this.setX(left);
            this.centerListVertically = false;
        }

        @Override
        public int getRowWidth() {
            return Math.max(1, this.getWidth() - 20);
        }

        private void setEntries(List<SettingsEntry> entries) {
            List<SettingsEntry> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i += this.columns) {
                List<SettingControl> controls = new ArrayList<>();
                for (int j = i; j < Math.min(i + this.columns, entries.size()); j++) {
                    controls.addAll(entries.get(j).controls);
                }
                rows.add(new SettingsEntry(controls, this.columns));
            }
            this.replaceEntries(rows);
            this.setScrollAmount(0.0D);
        }
    }

    private record SettingControl(AbstractWidget widget, Button reset,
            java.util.function.BooleanSupplier changed, java.util.function.BooleanSupplier available) {
        void refresh() {
            widget.active = available.getAsBoolean();
            if (reset != null) reset.active = widget.active && changed.getAsBoolean();
        }
    }

    private static final class SettingsEntry extends ContainerObjectSelectionList.Entry<SettingsEntry> {
        private final List<SettingControl> controls;
        private final List<AbstractWidget> widgets;
        private final int columns;

        private SettingsEntry(AbstractWidget widget) {
            this(new SettingControl(widget, null, () -> false, () -> true));
        }

        private SettingsEntry(SettingControl control) {
            this(List.of(control), 1);
        }

        private SettingsEntry(List<SettingControl> controls, int columns) {
            this.controls = List.copyOf(controls);
            this.widgets = new ArrayList<>();
            for (SettingControl control : controls) {
                this.widgets.add(control.widget());
                if (control.reset() != null) this.widgets.add(control.reset());
            }
            this.columns = columns;
        }

        @Override
        public List<? extends GuiEventListener> children() { return this.widgets; }

        @Override
        public List<? extends NarratableEntry> narratables() { return this.widgets; }

        @Override
        public void extractContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            for (SettingControl control : this.controls) control.refresh();
            for (AbstractWidget widget : this.widgets) {
                widget.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public int getHeight() { return ROW_HEIGHT; }

        @Override
        public void setX(int x) { super.setX(x); this.layoutWidgets(); }

        @Override
        public void setY(int y) { super.setY(y); this.layoutWidgets(); }

        @Override
        public void setWidth(int width) { super.setWidth(width); this.layoutWidgets(); }

        private void layoutWidgets() {
            if (this.controls == null) return;
            int width = SettingsLayout.controlWidth(this.getContentWidth(), this.columns);
            for (int i = 0; i < this.controls.size(); i++) {
                SettingControl control = this.controls.get(i);
                int x = this.getContentX() + 2 + i * (width + 10);
                int y = this.getContentY() + 3;
                control.widget().setX(x);
                control.widget().setY(y);
                control.widget().setWidth(control.reset() == null ? width : width - 24);
                if (control.reset() != null) {
                    control.reset().setX(x + width - 20);
                    control.reset().setY(y);
                }
            }
        }
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum, maximum;
        private final int decimals;
        private final DoubleConsumer consumer;

        private SettingSlider(String label, double minimum, double maximum, double initial,
                double fallback, boolean integer, DoubleConsumer consumer) {
            super(0, 0, 320, 20, Component.empty(), (initial - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.decimals = SettingValueFormat.decimals(minimum, maximum, fallback, integer);
            this.consumer = consumer;
            this.updateMessage();
        }

        private Component valueText(double value) {
            String text = SettingValueFormat.format(value, this.decimals);
            String unit = this.label + ".unit";
            return net.minecraft.locale.Language.getInstance().has(unit)
                ? Component.translatable(unit, text) : Component.literal(text);
        }

        private void setConfiguredValue(double configured) {
            this.value = (configured - this.minimum) / (this.maximum - this.minimum);
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double current = this.minimum + this.value * (this.maximum - this.minimum);
            this.setMessage(Component.translatable("options.generic_value",
                Component.translatable(this.label), this.valueText(current)));
        }

        @Override
        protected void applyValue() {
            double current = SettingValueFormat.snap(
                this.minimum + this.value * (this.maximum - this.minimum),
                this.minimum, this.maximum, this.decimals);
            this.consumer.accept(current);
            this.setConfiguredValue(current);
        }
    }
}
