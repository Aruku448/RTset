package com.rtest.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import java.util.Set;

/**
 * Decodes vanilla fragment shader output from sRGB-encoded values into the HDR composite's color
 * space, so native SDR content keeps its authored appearance while the RT scene stays wide gamut.
 *
 * <p>Vanilla shaders emit sRGB-encoded values intended for an 8-bit UNORM target. RTest's main
 * target is {@code RGBA16_FLOAT} whenever HDR is active, and 1.0 is reference white in every HDR
 * mode, so writing those values unchanged lifts mid-tones and desaturates highlights. Decoding them
 * makes an sRGB UI pixel land on exactly the same display luminance it has in SDR.
 *
 * <p>The primaries step depends on the negotiated mode:
 *
 * <ul>
 *   <li>{@link HdrSupport.OutputMode#HDR_BT2020_LINEAR} — the surface carries Rec.2020 primaries, so
 *       the decoded BT.709 value is rotated into Rec.2020. This is what lets the RT scene, which is
 *       already scene-referred linear Rec.2020, keep its wide gamut to the compositor.
 *   <li>{@link HdrSupport.OutputMode#HDR_SCRGB} — the surface carries BT.709 primaries, so the
 *       decoded value is already correct and no rotation happens.
 * </ul>
 *
 * <h2>Scope</h2>
 * RTest replaces the world with its own image, but not the hand,
 * the GUI, screen effects, clouds, weather or particles. Those keep drawing through vanilla pipelines
 * into the same target, so a UI-only allowlist is not enough.
 *
 * <h2>Cache safety</h2>
 * Minecraft caches compiled shaders by {@code (shader id, stage, defines)} and ignores the pipeline.
 * The decode must therefore be a function of the fragment shader alone; a per-pipeline decision would
 * be resolved by whichever pipeline compiled first. That is why there is exactly one conversion:
 * {@code core/position_tex_color} is shared by ten pipelines with different blend functions, so a
 * premultiplied variant chosen per pipeline was unsound, and the lobby shader it was paired with does
 * not exist as a separate fragment shader at all.
 */
// Color-space functions and matrices match Prime 26.3 math/color_space.slang.
public final class NativeColorManagement {
    /**
     * Fragment shaders whose output is data rather than display color, or whose result a later pass
     * decodes. Converting these would corrupt the data or double-decode.
     */
    private static final Set<String> DATA_FRAGMENT_SHADERS = Set.of(
        // Lightmap data texture; its values are multipliers, not radiance.
        "core/lightmap",
        // Coverage mask consumed as a data channel.
        "core/rendertype_water_mask",
        // Texture atlas contents; these write texels that later become source textures.
        "core/animate_sprite_blit",
        "core/animate_sprite_interpolate",
        // Encoded entity-outline buffer; core/blit_screen performs the decode when it reaches main.
        "core/rendertype_outline"
    );

    private NativeColorManagement() {
    }

    public static String transform(RenderPipeline pipeline, ShaderType shaderType, String source) {
        if (pipeline == null || shaderType != ShaderType.FRAGMENT || !HdrSupport.isActive()) {
            return source;
        }
        // Identifier paths include their directory ("core/entity"), never compare bare names: doing so
        // silently disables the decode, which is how the original UI-only allowlist became dead code.
        if (DATA_FRAGMENT_SHADERS.contains(pipeline.getFragmentShader().getPath())) {
            return source;
        }
        if (!source.contains("out vec4 fragColor;") || !source.contains("fragColor =")) {
            return source;
        }

        String transformed = source.replace("out vec4 fragColor;", "out vec4 fragColor;" + helpers());
        // Vanilla has several output forms (notably text's apply_fog() path). Match the final
        // assignment rather than a small list of known RHS expressions, so no shader silently keeps
        // encoded values on the HDR target.
        return transformed.replaceAll(
            "(?m)(fragColor\\s*=\\s*)([^;]+)(;)",
            "$1rtestToLinear($2)$3");
    }

    /** True when this fragment shader must keep producing raw values. */
    public static boolean isDataShader(RenderPipeline pipeline) {
        return pipeline != null
            && DATA_FRAGMENT_SHADERS.contains(pipeline.getFragmentShader().getPath());
    }

    /** The decode chain for the negotiated output mode; the mode is fixed for the whole session. */
    static String conversionChainExpression() {
        return HdrSupport.usesRec2020Primaries()
            ? "rtestBt709ToRec2020(rtestSrgbToLinear(color.rgb))"
            : "rtestSrgbToLinear(color.rgb)";
    }

    private static String helpers() {
        // The BT.709 to Rec.2020 matrix is the exact inverse pairing used by the RT material path
        // (RayTracingShaders.materialLinearSrgbToWorking) and the skybox decode.
        String primaries = HdrSupport.usesRec2020Primaries() ? """

vec3 rtestBt709ToRec2020(vec3 linearBt709) {
    return vec3(
        dot(vec3(0.6274039, 0.3292830, 0.0433131), linearBt709),
        dot(vec3(0.0690973, 0.9195404, 0.0113623), linearBt709),
        dot(vec3(0.0163914, 0.0880133, 0.8955953), linearBt709));
}
""" : "";
        return """

vec3 rtestSrgbToLinear(vec3 encoded) {
    encoded = max(encoded, vec3(0.0));
    vec3 low = encoded / 12.92;
    vec3 high = pow((encoded + vec3(0.055)) / 1.055, vec3(2.4));
    return mix(low, high, step(vec3(0.04045), encoded));
}
""" + primaries + """
vec4 rtestToLinear(vec4 color) {
    return vec4(""" + conversionChainExpression() + """
, color.a);
}
""";
    }
}
