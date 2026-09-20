package com.rtest.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final int CATEGORY_COUNT = 5;
    private static final String[] CATEGORY_KEYS = {
        "screen.rtest.settings.category.lighting",
        "screen.rtest.settings.category.pathTracing",
        "screen.rtest.settings.category.reconstruction",
        "screen.rtest.settings.category.denoiser",
        "screen.rtest.settings.category.output"
    };
    private static final String[] CATEGORY_DESCRIPTION_KEYS = {
        "screen.rtest.settings.category.lighting.description",
        "screen.rtest.settings.category.pathTracing.description",
        "screen.rtest.settings.category.reconstruction.description",
        "screen.rtest.settings.category.denoiser.description",
        "screen.rtest.settings.category.output.description"
    };

    private final Screen parent;
    private final List<Button> categoryButtons = new ArrayList<>();
    private SettingsList settingsList;
    private StringWidget categoryDescription;
    private Button offlineButton;
    private int selectedCategory;

    public RayTracingSettingsScreen(Screen parent) {
        super(Component.translatable("screen.rtest.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int panelWidth = Math.min(420, this.width - 32);
        int left = center - panelWidth / 2;

        this.addRenderableWidget(new StringWidget(
            center - 180,
            8,
            360,
            20,
            this.title,
            this.font
        ));

        this.categoryButtons.clear();
        int categoryWidth = panelWidth / CATEGORY_COUNT;
        for (int index = 0; index < CATEGORY_COUNT; index++) {
            int category = index;
            Button button = this.addRenderableWidget(Button.builder(
                    Component.translatable(CATEGORY_KEYS[index]),
                    ignored -> this.selectCategory(category))
                .bounds(left + index * categoryWidth, 32, categoryWidth - 3, 20)
                .build());
            this.categoryButtons.add(button);
        }

        this.categoryDescription = this.addRenderableWidget(new StringWidget(
            left,
            56,
            panelWidth,
            16,
            Component.empty(),
            this.font
        ));
        this.categoryDescription.setMaxWidth(panelWidth);

        this.settingsList = this.addRenderableWidget(new SettingsList(
            this.minecraft,
            panelWidth,
            this.height,
            76,
            Math.max(110, this.height - 42)
        ));

        this.addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(left, this.height - 32, panelWidth, 20)
                .build()
        );
        this.selectCategory(Math.min(this.selectedCategory, CATEGORY_COUNT - 1));
    }

    private void selectCategory(int category) {
        this.selectedCategory = category;
        if (this.categoryDescription != null) {
            this.categoryDescription.setMessage(Component.translatable(CATEGORY_DESCRIPTION_KEYS[category]));
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
            default -> List.of();
        };
    }

    private List<SettingsEntry> lightingEntries() {
        return List.of(
            slider("screen.rtest.settings.sunIntensity", "screen.rtest.settings.sunIntensity.tip",
                "1.0", 0.0D, 2.0D, RayTracingClientConfig.INSTANCE.sunIntensity.get(),
                value -> RayTracingClientConfig.INSTANCE.sunIntensity.set(value)),
            slider("screen.rtest.settings.sunColorTemperature", "screen.rtest.settings.sunColorTemperature.tip",
                "6500", 1000.0D, 20000.0D, RayTracingClientConfig.INSTANCE.sunColorTemperature.get(),
                value -> RayTracingClientConfig.INSTANCE.sunColorTemperature.set(value)),
            slider("screen.rtest.settings.ambientColorTemperature", "screen.rtest.settings.ambientColorTemperature.tip",
                "6500", 1000.0D, 20000.0D, RayTracingClientConfig.INSTANCE.ambientColorTemperature.get(),
                value -> RayTracingClientConfig.INSTANCE.ambientColorTemperature.set(value)),
            slider("screen.rtest.settings.shadowStrength", "screen.rtest.settings.shadowStrength.tip",
                "1.0", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.shadowStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.shadowStrength.set(value)),
            cycle("screen.rtest.settings.volumetricLightingEnabled", "screen.rtest.settings.volumetricLightingEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.volumetricLightingEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.volumetricLightingEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.volumetricLightingEnabled.set(value))),
            slider("screen.rtest.settings.volumetricLightingStrength", "screen.rtest.settings.volumetricLightingStrength.tip",
                "1.0", 0.0D, 2.0D, RayTracingClientConfig.INSTANCE.volumetricLightingStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.volumetricLightingStrength.set(value)),
            slider("screen.rtest.settings.volumetricFogDensity", "screen.rtest.settings.volumetricFogDensity.tip",
                "1.0", 0.0D, 2.0D, RayTracingClientConfig.INSTANCE.volumetricFogDensity.get(),
                value -> RayTracingClientConfig.INSTANCE.volumetricFogDensity.set(value)),
            cycle("screen.rtest.settings.volumetricLightingQuality", "screen.rtest.settings.volumetricLightingQuality.tip", "2",
                CycleButton.builder(value -> Component.translatable("screen.rtest.settings.volumetricLightingQuality.value." + value),
                        RayTracingClientConfig.INSTANCE.volumetricLightingQuality.get())
                    .withValues(1, 2, 3)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.volumetricLightingQuality"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.volumetricLightingQuality.set(value))),
            slider("screen.rtest.settings.sunAngleOffset", "screen.rtest.settings.sunAngleOffset.tip",
                "0.0", -180.0D, 180.0D, RayTracingClientConfig.INSTANCE.sunAngleOffset.get(),
                value -> RayTracingClientConfig.INSTANCE.sunAngleOffset.set(value)),
            slider("screen.rtest.settings.sunAzimuthOffset", "screen.rtest.settings.sunAzimuthOffset.tip",
                "0.0", -180.0D, 180.0D, RayTracingClientConfig.INSTANCE.sunAzimuthOffset.get(),
                value -> RayTracingClientConfig.INSTANCE.sunAzimuthOffset.set(value))
        );
    }

    private List<SettingsEntry> pathTracingEntries() {
        return List.of(
            cycle("screen.rtest.settings.giBounces", "screen.rtest.settings.giBounces.tip", "3",
                CycleButton.builder(value -> Component.translatable("screen.rtest.settings.giBounces.value." + value),
                        RayTracingClientConfig.INSTANCE.giBounces.get())
                    .withValues(1, 2, 3, 4)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.giBounces"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.giBounces.set(value))),
            cycle("screen.rtest.settings.pbrFormat", "screen.rtest.settings.pbrFormat.tip", "labpbr",
                CycleButton.builder(Component::literal, RayTracingClientConfig.INSTANCE.pbrFormat.get())
                    .withValues("labpbr", "classic", "bedrock")
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrFormat"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrFormat.set(value))),
            slider("screen.rtest.settings.pbrNormalStrength", "screen.rtest.settings.pbrNormalStrength.tip",
                "1.0", 0.0D, 3.0D, RayTracingClientConfig.INSTANCE.pbrNormalStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.pbrNormalStrength.set(value)),
            cycle("screen.rtest.settings.pbrTextureAoEnabled", "screen.rtest.settings.pbrTextureAoEnabled.tip", "off",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrTextureAoEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrTextureAoEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrTextureAoEnabled.set(value))),
            cycle("screen.rtest.settings.pbrPorosityEnabled", "screen.rtest.settings.pbrPorosityEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrPorosityEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrPorosityEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrPorosityEnabled.set(value))),
            slider("screen.rtest.settings.pbrWetnessStrength", "screen.rtest.settings.pbrWetnessStrength.tip",
                "1.0", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.pbrWetnessStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.pbrWetnessStrength.set(value)),
            slider("screen.rtest.settings.pbrEmissionStrength", "screen.rtest.settings.pbrEmissionStrength.tip",
                "1.0", 0.0D, 20.0D, RayTracingClientConfig.INSTANCE.pbrEmissionStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.pbrEmissionStrength.set(value)),
            cycle("screen.rtest.settings.pbrPredefinedMetalsEnabled", "screen.rtest.settings.pbrPredefinedMetalsEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrPredefinedMetalsEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrPredefinedMetalsEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrPredefinedMetalsEnabled.set(value))),
            cycle("screen.rtest.settings.pbrTerrainCpuCaptureEnabled", "screen.rtest.settings.pbrTerrainCpuCaptureEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrTerrainCpuCaptureEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrTerrainCpuCaptureEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrTerrainCpuCaptureEnabled.set(value))),
            cycle("screen.rtest.settings.pbrParallaxEnabled", "screen.rtest.settings.pbrParallaxEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrParallaxEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrParallaxEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrParallaxEnabled.set(value))),
            cycle("screen.rtest.settings.pbrEntityParallaxEnabled", "screen.rtest.settings.pbrEntityParallaxEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.pbrEntityParallaxEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.pbrEntityParallaxEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.pbrEntityParallaxEnabled.set(value))),
            slider("screen.rtest.settings.pbrParallaxDepth", "screen.rtest.settings.pbrParallaxDepth.tip",
                "1.0", 0.0D, 4.0D, RayTracingClientConfig.INSTANCE.pbrParallaxDepth.get(),
                value -> RayTracingClientConfig.INSTANCE.pbrParallaxDepth.set(value))
        );
    }

    private List<SettingsEntry> reconstructionEntries() {
        return List.of(
            cycle("screen.rtest.settings.fluidRtEnabled", "screen.rtest.settings.fluidRtEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.fluidRtEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.fluidRtEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.fluidRtEnabled.set(value))),
            cycle("screen.rtest.settings.fsrQuality", "screen.rtest.settings.fsrQuality.tip", "quality",
                CycleButton.builder(Component::literal, RayTracingClientConfig.INSTANCE.fsrQuality.get())
                    .withValues("native_aa", "quality_75", "quality", "balanced", "performance", "ultra_performance")
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.fsrQuality"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.fsrQuality.set(value)))
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
            cycle("screen.rtest.settings.primeColorManagementEnabled",
                "screen.rtest.settings.primeColorManagementEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.primeColorManagementEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20,
                        Component.translatable("screen.rtest.settings.primeColorManagementEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.primeColorManagementEnabled.set(value))),
            cycle("screen.rtest.settings.hdrEnabled", "screen.rtest.settings.hdrEnabled.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.hdrEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.hdrEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.hdrEnabled.set(value))),
            cycle("screen.rtest.settings.hdrWideGamutEnabled",
                "screen.rtest.settings.hdrWideGamutEnabled.tip", "off",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.hdrWideGamutEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20,
                        Component.translatable("screen.rtest.settings.hdrWideGamutEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.hdrWideGamutEnabled.set(value))),
            cycle("screen.rtest.settings.nativeColorDecodeEnabled",
                "screen.rtest.settings.nativeColorDecodeEnabled.tip", "off",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.nativeColorDecodeEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20,
                        Component.translatable("screen.rtest.settings.nativeColorDecodeEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nativeColorDecodeEnabled.set(value))),
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
        return List.of(
            cycle("screen.rtest.settings.nrdEnabled", "screen.rtest.settings.nrdEnabled.tip", "off",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.nrdEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdEnabled.set(value))),
            slider("screen.rtest.settings.nrdStrength", "screen.rtest.settings.nrdStrength.tip",
                "1.0", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.nrdStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdStrength.set(value)),
            cycle("screen.rtest.settings.nrdHitDistanceReconstruction", "screen.rtest.settings.nrdHitDistanceReconstruction.tip", "area_3x3",
                CycleButton.builder(Component::literal, RayTracingClientConfig.INSTANCE.nrdHitDistanceReconstruction.get())
                    .withValues("off", "area_3x3", "area_5x5")
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdHitDistanceReconstruction"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdHitDistanceReconstruction.set(value))),
            slider("screen.rtest.settings.nrdDiffusePrepassBlurRadius", "screen.rtest.settings.nrdDiffusePrepassBlurRadius.tip",
                "0.0", 0.0D, 96.0D, RayTracingClientConfig.INSTANCE.nrdDiffusePrepassBlurRadius.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdDiffusePrepassBlurRadius.set(value)),
            slider("screen.rtest.settings.nrdSpecularPrepassBlurRadius", "screen.rtest.settings.nrdSpecularPrepassBlurRadius.tip",
                "0.0", 0.0D, 96.0D, RayTracingClientConfig.INSTANCE.nrdSpecularPrepassBlurRadius.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdSpecularPrepassBlurRadius.set(value)),
            slider("screen.rtest.settings.nrdMinHitDistanceWeight", "screen.rtest.settings.nrdMinHitDistanceWeight.tip",
                "0.10", 0.0001D, 0.2D, RayTracingClientConfig.INSTANCE.nrdMinHitDistanceWeight.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdMinHitDistanceWeight.set(value)),
            slider("screen.rtest.settings.nrdMinBlurRadius", "screen.rtest.settings.nrdMinBlurRadius.tip",
                "1.0", 0.0D, 16.0D, RayTracingClientConfig.INSTANCE.nrdMinBlurRadius.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdMinBlurRadius.set(value)),
            slider("screen.rtest.settings.nrdMaxBlurRadius", "screen.rtest.settings.nrdMaxBlurRadius.tip",
                "12.0", 1.0D, 96.0D, RayTracingClientConfig.INSTANCE.nrdMaxBlurRadius.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdMaxBlurRadius.set(value)),
            slider("screen.rtest.settings.nrdLobeAngleFraction", "screen.rtest.settings.nrdLobeAngleFraction.tip",
                "0.15", 0.001D, 1.0D, RayTracingClientConfig.INSTANCE.nrdLobeAngleFraction.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdLobeAngleFraction.set(value)),
            slider("screen.rtest.settings.nrdRoughnessFraction", "screen.rtest.settings.nrdRoughnessFraction.tip",
                "0.15", 0.001D, 1.0D, RayTracingClientConfig.INSTANCE.nrdRoughnessFraction.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdRoughnessFraction.set(value)),
            slider("screen.rtest.settings.nrdPlaneDistanceSensitivity", "screen.rtest.settings.nrdPlaneDistanceSensitivity.tip",
                "0.02", 0.0001D, 0.2D, RayTracingClientConfig.INSTANCE.nrdPlaneDistanceSensitivity.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdPlaneDistanceSensitivity.set(value)),
            slider("screen.rtest.settings.nrdFastHistoryClampingSigmaScale", "screen.rtest.settings.nrdFastHistoryClampingSigmaScale.tip",
                "2.0", 1.0D, 3.0D, RayTracingClientConfig.INSTANCE.nrdFastHistoryClampingSigmaScale.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdFastHistoryClampingSigmaScale.set(value)),
            slider("screen.rtest.settings.nrdFireflySuppressorMinRelativeScale", "screen.rtest.settings.nrdFireflySuppressorMinRelativeScale.tip",
                "1.0", 1.0D, 3.0D, RayTracingClientConfig.INSTANCE.nrdFireflySuppressorMinRelativeScale.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdFireflySuppressorMinRelativeScale.set(value)),
            slider("screen.rtest.settings.nrdDisocclusionThreshold", "screen.rtest.settings.nrdDisocclusionThreshold.tip",
                "0.01", 0.0001D, 0.2D, RayTracingClientConfig.INSTANCE.nrdDisocclusionThreshold.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdDisocclusionThreshold.set(value)),
            cycle("screen.rtest.settings.nrdMaxAccumulatedFrameNum", "screen.rtest.settings.nrdMaxAccumulatedFrameNum.tip", "48",
                CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                        RayTracingClientConfig.INSTANCE.nrdMaxAccumulatedFrameNum.get())
                    .withValues(1, 2, 4, 6, 8, 12, 16, 24, 30, 48, 63)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdMaxAccumulatedFrameNum"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdMaxAccumulatedFrameNum.set(value))),
            cycle("screen.rtest.settings.nrdMaxFastAccumulatedFrameNum", "screen.rtest.settings.nrdMaxFastAccumulatedFrameNum.tip", "12",
                CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                        RayTracingClientConfig.INSTANCE.nrdMaxFastAccumulatedFrameNum.get())
                    .withValues(1, 2, 3, 4, 6, 8, 12, 16, 24, 30, 48, 63)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdMaxFastAccumulatedFrameNum"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdMaxFastAccumulatedFrameNum.set(value))),
            cycle("screen.rtest.settings.nrdHistoryFixFrameNum", "screen.rtest.settings.nrdHistoryFixFrameNum.tip", "3",
                CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                        RayTracingClientConfig.INSTANCE.nrdHistoryFixFrameNum.get())
                    .withValues(0, 1, 2, 3, 4, 6, 8, 12, 16)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdHistoryFixFrameNum"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdHistoryFixFrameNum.set(value))),
            cycle("screen.rtest.settings.nrdAntiFirefly", "screen.rtest.settings.nrdAntiFirefly.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.nrdAntiFirefly.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdAntiFirefly"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdAntiFirefly.set(value))),
            cycle("screen.rtest.settings.nrdSpecularPrepassMotionOnly", "screen.rtest.settings.nrdSpecularPrepassMotionOnly.tip", "on",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.nrdSpecularPrepassMotionOnly.get())
                    .withValues(false, true)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdSpecularPrepassMotionOnly"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdSpecularPrepassMotionOnly.set(value))),
            cycle("screen.rtest.settings.nrdHistoryFixPixelStride", "screen.rtest.settings.nrdHistoryFixPixelStride.tip", "14",
                CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                        RayTracingClientConfig.INSTANCE.nrdHistoryFixPixelStride.get())
                    .withValues(1, 2, 4, 7, 10, 14, 20, 28, 40, 64)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.nrdHistoryFixPixelStride"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.nrdHistoryFixPixelStride.set(value))),
            slider("screen.rtest.settings.nrdConvergenceScale", "screen.rtest.settings.nrdConvergenceScale.tip",
                "1.0", 0.1D, 4.0D, RayTracingClientConfig.INSTANCE.nrdConvergenceScale.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdConvergenceScale.set(value)),
            slider("screen.rtest.settings.nrdConvergenceBase", "screen.rtest.settings.nrdConvergenceBase.tip",
                "0.2", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.nrdConvergenceBase.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdConvergenceBase.set(value)),
            slider("screen.rtest.settings.nrdConvergencePercent", "screen.rtest.settings.nrdConvergencePercent.tip",
                "0.8", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.nrdConvergencePercent.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdConvergencePercent.set(value)),
            slider("screen.rtest.settings.nrdDenoisingRange", "screen.rtest.settings.nrdDenoisingRange.tip",
                "60000", 256.0D, 60000.0D, RayTracingClientConfig.INSTANCE.nrdDenoisingRange.get(),
                value -> RayTracingClientConfig.INSTANCE.nrdDenoisingRange.set(value)),
            cycle("screen.rtest.settings.sundialDenoiserEnabled", "screen.rtest.settings.sundialDenoiserEnabled.tip", "off",
                CycleButton.builder(value -> Component.translatable(value ? "options.on" : "options.off"),
                        RayTracingClientConfig.INSTANCE.sundialDenoiserEnabled.get())
                    .withValues(true, false)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.sundialDenoiserEnabled"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.sundialDenoiserEnabled.set(value))),
            slider("screen.rtest.settings.sundialDenoiserStrength", "screen.rtest.settings.sundialDenoiserStrength.tip",
                "0.75", 0.0D, 1.0D, RayTracingClientConfig.INSTANCE.sundialDenoiserStrength.get(),
                value -> RayTracingClientConfig.INSTANCE.sundialDenoiserStrength.set(value)),
            cycle("screen.rtest.settings.sundialDenoiserHistory", "screen.rtest.settings.sundialDenoiserHistory.tip", "48",
                CycleButton.builder(value -> Component.literal(Integer.toString(value)),
                        RayTracingClientConfig.INSTANCE.sundialDenoiserHistory.get())
                    .withValues(8, 16, 24, 32, 48, 64, 96, 128)
                    .create(0, 0, 320, 20, Component.translatable("screen.rtest.settings.sundialDenoiserHistory"),
                        (button, value) -> RayTracingClientConfig.INSTANCE.sundialDenoiserHistory.set(value)))
        );
    }

    private SettingsEntry slider(
            String labelKey,
            String tipKey,
            String defaultValue,
            double minimum,
            double maximum,
            double initial,
            DoubleConsumer consumer) {
        SettingSlider slider = new SettingSlider(
            0, 0, 320, labelKey, minimum, maximum, initial, consumer);
        addTooltip(slider, tipKey, defaultValue);
        return new SettingsEntry(slider);
    }

    private SettingsEntry cycle(String labelKey, String tipKey, String defaultValue, AbstractWidget widget) {
        addTooltip(widget, tipKey, defaultValue);
        return new SettingsEntry(widget);
    }

    private static void addTooltip(AbstractWidget widget, String tipKey, String defaultValue) {
        Component tooltip = Component.translatable(tipKey)
            .append(Component.literal("\n"))
            .append(Component.translatable("screen.rtest.settings.default", defaultValue));
        widget.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void onClose() {
        RayTracingClientConfig.INSTANCE.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    private static final class SettingsList extends ContainerObjectSelectionList<SettingsEntry> {
        private SettingsList(Minecraft minecraft, int width, int height, int top, int bottom) {
            super(minecraft, width, height, top, bottom);
        }

        private void setEntries(List<SettingsEntry> entries) {
            this.replaceEntries(entries);
            this.setScrollAmount(0.0D);
        }
    }

    private static final class SettingsEntry extends ContainerObjectSelectionList.Entry<SettingsEntry> {
        private final List<AbstractWidget> widgets;

        private SettingsEntry(AbstractWidget widget) {
            this.widgets = List.of(widget);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            for (AbstractWidget widget : this.widgets) {
                widget.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public int getHeight() {
            return 36;
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            this.layoutWidgets();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            this.layoutWidgets();
        }

        @Override
        public void setWidth(int width) {
            super.setWidth(width);
            this.layoutWidgets();
        }

        private void layoutWidgets() {
            if (this.widgets == null || this.widgets.isEmpty()) {
                return;
            }
            AbstractWidget widget = this.widgets.get(0);
            widget.setX(this.getContentX() + 4);
            widget.setY(this.getContentY() + 6);
            widget.setWidth(Math.max(100, this.getContentWidth() - 8));
        }
    }

    private static final class SettingSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum;
        private final double maximum;
        private final DoubleConsumer consumer;

        private SettingSlider(
            int x,
            int y,
            int width,
            String label,
            double minimum,
            double maximum,
            double initial,
            DoubleConsumer consumer
        ) {
            super(x, y, width, 20, Component.empty(), (initial - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            this.consumer = consumer;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double current = this.minimum + this.value * (this.maximum - this.minimum);
            this.setMessage(Component.translatable(this.label, String.format(Locale.ROOT, "%.2f", current)));
        }

        @Override
        protected void applyValue() {
            this.consumer.accept(this.minimum + this.value * (this.maximum - this.minimum));
        }
    }
}
