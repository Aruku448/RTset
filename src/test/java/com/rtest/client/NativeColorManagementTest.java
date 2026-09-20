package com.rtest.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Contracts for the HDR native color decode: every display-color fragment shader in the game must be
 * decoded exactly once, data shaders must stay untouched, and SDR sessions must not be modified.
 */
public final class NativeColorManagementTest {
    private static final String FRAGMENT = """
        #version 330
        out vec4 fragColor;
        void main() {
            fragColor = vec4(0.5, 0.5, 0.5, 1.0);
        }
        """;

    public static void main(String[] args) throws Exception {
        Map<String, RenderPipeline> pipelines = pipelines();
        HdrSupport.OutputMode previous = HdrSupport.outputMode();
        try {
            HdrSupport.setMode(HdrSupport.OutputMode.SDR);
            assertSdrIsUntouched(pipelines);

            HdrSupport.setMode(HdrSupport.OutputMode.HDR_SCRGB);
            assertDisplayShadersAreDecoded(pipelines);
            assertShaderPathsAreComparedInFull(pipelines);
            assertDataShadersAreUntouched(pipelines);
            assertNonFragmentStagesAreUntouched(pipelines);
            assertPrimaries(HdrSupport.OutputMode.HDR_SCRGB);

            HdrSupport.setMode(HdrSupport.OutputMode.HDR_BT2020_LINEAR);
            assertDisplayShadersAreDecoded(pipelines);
            assertDataShadersAreUntouched(pipelines);
            assertPrimaries(HdrSupport.OutputMode.HDR_BT2020_LINEAR);
        } finally {
            HdrSupport.setMode(previous);
        }
        System.out.println("HDR native color management contracts passed");
    }

    private static void assertSdrIsUntouched(Map<String, RenderPipeline> pipelines) {
        for (String name : new String[] {"ENTITY_CUTOUT", "GUI", "OPAQUE_PARTICLE", "CLOUDS"}) {
            String output = NativeColorManagement.transform(
                pipelines.get(name), ShaderType.FRAGMENT, FRAGMENT);
            if (!output.equals(FRAGMENT)) {
                throw new AssertionError("SDR session modified " + name + " shader output");
            }
        }
    }

    /**
     * The whole point of the change: world-space native passes reach the linear HDR target too, so
     * entities, items, particles, clouds, weather and block entities all need the decode.
     */
    private static void assertDisplayShadersAreDecoded(Map<String, RenderPipeline> pipelines) {
        for (String name : new String[] {
            "ENTITY_CUTOUT", "ENTITY_TRANSLUCENT", "ENTITY_SOLID", "ARMOR_CUTOUT_NO_CULL",
            "ITEM_CUTOUT", "ITEM_TRANSLUCENT", "GLINT", "BANNER_PATTERN",
            "OPAQUE_PARTICLE", "TRANSLUCENT_PARTICLE", "CLOUDS", "FLAT_CLOUDS",
            "WEATHER_DEPTH_WRITE", "WEATHER_NO_DEPTH_WRITE", "WORLD_BORDER",
            "TEXT", "TEXT_SEE_THROUGH", "GUI", "GUI_TEXTURED", "BLOCK_SCREEN_EFFECT",
            "FIRE_SCREEN_EFFECT", "VIGNETTE", "CROSSHAIR"
        }) {
            RenderPipeline pipeline = pipelines.get(name);
            String output = NativeColorManagement.transform(pipeline, ShaderType.FRAGMENT, FRAGMENT);
            if (!output.contains("rtestToLinear(vec4(0.5, 0.5, 0.5, 1.0))")) {
                throw new AssertionError(name + " fragment output is not decoded into the HDR space");
            }
            if (output.indexOf("rtestSrgbToLinear") > output.indexOf("void main()")) {
                throw new AssertionError(name + " decode helper was injected inside main");
            }
        }
    }

    /**
     * The primaries step must match the negotiated surface. Decoding sRGB to BT.709 is enough for
     * scRGB; a Rec.2020 surface needs the extra rotation or an sRGB UI pixel would display with the
     * wrong hue, which is the whole point of not mapping the UI into the wide gamut blindly.
     */
    private static void assertPrimaries(HdrSupport.OutputMode mode) {
        boolean rec2020 = mode == HdrSupport.OutputMode.HDR_BT2020_LINEAR;
        if (HdrSupport.usesRec2020Primaries() != rec2020) {
            throw new AssertionError("primaries mode does not follow the negotiated output mode");
        }
        String chain = NativeColorManagement.conversionChainExpression();
        if (rec2020 != chain.contains("rtestBt709ToRec2020")) {
            throw new AssertionError("conversion chain does not match the surface primaries");
        }
        if (!chain.contains("rtestSrgbToLinear")) {
            throw new AssertionError("the sRGB transfer decode is missing from the conversion chain");
        }
    }

    private static void assertDataShadersAreUntouched(Map<String, RenderPipeline> pipelines) {
        for (String name : new String[] {
            "LIGHTMAP", "WATER_MASK", "ANIMATE_SPRITE_BLIT", "ANIMATE_SPRITE_INTERPOLATE",
            "OUTLINE_CULL", "OUTLINE_NO_CULL"
        }) {
            String output = NativeColorManagement.transform(
                pipelines.get(name), ShaderType.FRAGMENT, FRAGMENT);
            if (!output.equals(FRAGMENT)) {
                throw new AssertionError(name + " writes data, not display color, and must stay raw");
            }
        }
    }

    /**
     * Regression guard for the bug this change fixes: {@code Identifier.getPath()} returns
     * {@code core/entity}, not {@code entity}. A bare-name comparison silently disables the decode for
     * every shader, which is exactly how the previous UI-only allowlist became dead code.
     */
    private static void assertShaderPathsAreComparedInFull(Map<String, RenderPipeline> pipelines) {
        RenderPipeline gui = pipelines.get("GUI");
        if (!gui.getFragmentShader().getPath().equals("core/gui")) {
            throw new AssertionError("Fragment shader paths no longer carry their directory");
        }
        if (!NativeColorManagement.transform(gui, ShaderType.FRAGMENT, FRAGMENT)
            .contains("rtestToLinear(")) {
            throw new AssertionError("GUI output is not decoded; check fragment shader path comparisons");
        }
    }

    private static void assertNonFragmentStagesAreUntouched(Map<String, RenderPipeline> pipelines) {
        for (ShaderType type : ShaderType.values()) {
            String output = NativeColorManagement.transform(pipelines.get("ENTITY_CUTOUT"), type, FRAGMENT);
            if (type != ShaderType.FRAGMENT && !output.equals(FRAGMENT)) {
                throw new AssertionError(type + " stage must never be rewritten");
            }
        }
        String withoutOutput = "#version 330\nvoid main() { }\n";
        if (!NativeColorManagement.transform(
            pipelines.get("ENTITY_CUTOUT"), ShaderType.FRAGMENT, withoutOutput).equals(withoutOutput)) {
            throw new AssertionError("Shaders without a fragColor output must be left alone");
        }
    }

    private static Map<String, RenderPipeline> pipelines() throws Exception {
        Map<String, RenderPipeline> result = new LinkedHashMap<>();
        for (Field field : RenderPipelines.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != RenderPipeline.class) {
                continue;
            }
            field.setAccessible(true);
            RenderPipeline pipeline = (RenderPipeline) field.get(null);
            if (pipeline != null) {
                result.put(field.getName(), pipeline);
            }
        }
        return result;
    }
}
