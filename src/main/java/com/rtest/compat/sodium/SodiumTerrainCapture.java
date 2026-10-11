package com.rtest.compat.sodium;

import com.rtest.client.SodiumTerrainMeshCache;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteFinderCache;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/** Optional adapter; only the gated Sodium mixins load this class. */
public final class SodiumTerrainCapture {
    private static final java.util.concurrent.atomic.AtomicBoolean REPORTED_FAILURE = new java.util.concurrent.atomic.AtomicBoolean();

    private SodiumTerrainCapture() { }

    public static void capture(BlockState state, MutableQuadViewImpl quad,
                               ChunkVertexEncoder.Vertex[] vertices) {
        var build = SodiumTerrainMeshCache.active();
        if (build == null || !build.accepting()) return;
        try {
            var sprite = quad.sprite(SpriteFinderCache.forBlockAtlas());
            var layer = quad.getRenderType();
            if (state == null || sprite == null || layer == null || vertices.length != 4
                || !sprite.atlasLocation().equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)) {
                build.fail(SodiumTerrainMeshCache.Failure.INVALID_QUAD);
                return;
            }
            // Sodium has applied model offsets, transforms and biome tint here. Its AO and
            // light values are separate from color and must not enter the RT material.
            BakedQuad geometry = new BakedQuad(
                position(vertices[0]), position(vertices[1]), position(vertices[2]), position(vertices[3]),
                uv(vertices[0]), uv(vertices[1]), uv(vertices[2]), uv(vertices[3]), quad.getLightFace(),
                new BakedQuad.MaterialInfo(sprite, layer, Sheets.cutoutItemSheet(), -1, false,
                    quad.emissive() ? 15 : 0),
                BakedNormals.UNSPECIFIED, BakedColors.DEFAULT);
            build.append(new SodiumTerrainMeshCache.Quad(state, geometry,
                rgb(vertices[0].color), rgb(vertices[1].color), rgb(vertices[2].color), rgb(vertices[3].color)));
        } catch (RuntimeException | LinkageError exception) {
            build.fail(SodiumTerrainMeshCache.Failure.CAPTURE_EXCEPTION);
            if (REPORTED_FAILURE.compareAndSet(false, true)) {
                com.mojang.logging.LogUtils.getLogger().warn(
                    "RTest could not capture a Sodium block quad; using complete CPU terrain capture", exception);
            }
        }
    }

    private static Vector3f position(ChunkVertexEncoder.Vertex v) {
        if (!Float.isFinite(v.x) || !Float.isFinite(v.y) || !Float.isFinite(v.z)) {
            throw new IllegalArgumentException("Non-finite Sodium vertex");
        }
        return new Vector3f(v.x, v.y, v.z);
    }

    private static long uv(ChunkVertexEncoder.Vertex v) {
        if (!Float.isFinite(v.u) || !Float.isFinite(v.v)) throw new IllegalArgumentException("Non-finite Sodium UV");
        return UVPair.pack(v.u, v.v);
    }

    private static int rgb(int abgr) {
        return (abgr & 255) << 16 | abgr & 0xFF00 | abgr >>> 16 & 255;
    }
}
