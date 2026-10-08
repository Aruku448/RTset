package com.rtest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4fc;

/** Reuses native glyph shaping, atlas pages and posed quads for world-space RT text. */
public final class WorldTextGeometry {
    private static final ThreadLocal<Boolean> glowingSign = ThreadLocal.withInitial(() -> false);
    private WorldTextGeometry() { }
    public static void setGlowingSign(boolean glowing) { glowingSign.set(glowing); }
    public static void endSign() { glowingSign.remove(); }

    public interface Sink {
        void accept(PlayerModelGeometryAdapter.Mesh mesh, Identifier texture);
    }

    public static boolean isText(RenderType type) {
        return type != null && type.toString().toLowerCase(java.util.Locale.ROOT).startsWith("rendertype[text");
    }

    static PlayerModelGeometryAdapter.Mesh material(PlayerModelGeometryAdapter.Mesh mesh, RenderType type) {
        var pipeline = type.pipeline();
        if (pipeline != RenderPipelines.TEXT_GRAYSCALE
            && pipeline != RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET
            && pipeline != RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH) return mesh;
        float[] data = mesh.materialData().clone();
        for (int i = 0; i < data.length; i += 28) data[i + 14] = 3;
        return new PlayerModelGeometryAdapter.Mesh(mesh.vertices(), data);
    }

    public static void capture(Font font, PoseStack pose, float x, float y, FormattedCharSequence string,
        boolean shadow, Font.DisplayMode mode, int light, int color, int background, int outline,
        float offsetX, float offsetY, float offsetZ, Sink sink) {
        if (outline != 0) {
            visit(font.prepare8xTextOutline(string, x, y, outline), pose.last().pose(),
                Font.DisplayMode.NORMAL, light, offsetX, offsetY, offsetZ, 0.0001F, sink);
        }
        visit(font.prepareText(string, x, y, color, outline == 0 && shadow, false,
            outline == 0 ? background : 0), pose.last().pose(), mode, light,
            offsetX, offsetY, offsetZ, outline == 0 ? 0.0001F : 0.0003F, sink);
    }

    private static void visit(Font.PreparedText text, Matrix4fc pose, Font.DisplayMode mode, int light,
        float x, float y, float z, float bias, Sink sink) {
        text.visit(new Font.GlyphVisitor() {
            @Override public void acceptRenderable(TextRenderable renderable) {
            RenderType type = renderable.renderType(mode);
            Identifier texture = LivingEntityGeometryAdapter.atlasForRenderType(type);
            float emission = glowingSign.get() ? RayTracingEmission.fromMinecraftLevel(15) : 0;
            var capture = new PlayerModelGeometryAdapter.Capture(null, x, y, z, null, null, emission);
            renderable.render(pose, capture, light, false);
            var mesh = material(LivingEntityGeometryAdapter.retag(capture.finish(), texture), type);
            // Native polygon offset has no meaning to a BLAS. Separate the front glyphs from
            // outline/background planes in block units, independent of the font's pixel scale.
            float[] vertices = mesh.vertices().clone();
            for (int t = 0; t < mesh.triangleCount(); t++) {
                for (int v = 0; v < 3; v++) {
                    for (int c = 0; c < 3; c++)
                        vertices[t * 9 + v * 3 + c] += mesh.materialData()[t * 28 + 4 + c] * bias;
                }
            }
            sink.accept(new PlayerModelGeometryAdapter.Mesh(vertices, mesh.materialData()), texture);
            }
        });
    }
}
