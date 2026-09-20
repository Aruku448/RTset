package com.rtest.client;

import com.rtest.mixin.QuadParticleRenderStateAccessor;
import com.rtest.mixin.QuadParticleStorageAccessor;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Converts vanilla particle render-state quads into one bounded dynamic RT mesh per frame. */
public final class ParticleGeometryAdapter {
    private static final int MAX_PARTICLES = 256;
    private static Snapshot pending = Snapshot.EMPTY;
    private static long revision;

    record Snapshot(PlayerModelGeometryAdapter.Mesh mesh, long revision, int particleCount) {
        private static final Snapshot EMPTY = new Snapshot(
            new PlayerModelGeometryAdapter.Mesh(new float[0], new float[0]), 0L, 0);
    }

    private ParticleGeometryAdapter() {
    }

    public static void capture(ParticlesRenderState state) {
        FloatArrayBuilder vertices = new FloatArrayBuilder();
        FloatArrayBuilder materials = new FloatArrayBuilder();
        Builder builder = new Builder(vertices, materials);
        // Full-bright quads include flame and other authored luminous particles. Admit them first
        // so terrain dust cannot evict the light-producing particles from the bounded RT mesh.
        append(state, builder, true);
        append(state, builder, false);
        int count = builder.count;
        pending = count == 0 ? Snapshot.EMPTY : new Snapshot(
            new PlayerModelGeometryAdapter.Mesh(vertices.toArray(), materials.toArray()),
            ++revision,
            count);
    }

    static Snapshot drain() {
        Snapshot result = pending;
        pending = Snapshot.EMPTY;
        return result;
    }

    static void clear() {
        pending = Snapshot.EMPTY;
    }

    static boolean isEmissiveLight(int packedLight) {
        return LightCoordsUtil.block(packedLight) >= 15;
    }

    private static void append(ParticlesRenderState state, Builder builder, boolean emissivePass) {
        for (ParticleGroupRenderState group : state.particles) {
            if (!(group instanceof QuadParticleRenderState quads)) {
                continue;
            }
            Map<SingleQuadParticle.Layer, Object> layers =
                ((QuadParticleRenderStateAccessor)(Object)quads).rtest$getParticles();
            for (Map.Entry<SingleQuadParticle.Layer, Object> entry : layers.entrySet()) {
                QuadParticleStorageAccessor storage = (QuadParticleStorageAccessor)entry.getValue();
                float[] floats = storage.rtest$getFloatValues();
                int[] ints = storage.rtest$getIntValues();
                for (int index = 0; index < storage.rtest$getParticleCount()
                        && builder.count < MAX_PARTICLES; index++) {
                    int light = ints[index * 2 + 1];
                    boolean emissive = isEmissiveLight(light);
                    if (emissive == emissivePass) {
                        builder.add(entry.getKey(), floats, ints, index, emissive);
                    }
                }
                if (builder.count >= MAX_PARTICLES) {
                    return;
                }
            }
        }
    }

    private static final class Builder {
        private final FloatArrayBuilder vertices;
        private final FloatArrayBuilder materials;
        private final Map<Identifier, Integer> textureSlots = new HashMap<>();
        private int count;

        private Builder(FloatArrayBuilder vertices, FloatArrayBuilder materials) {
            this.vertices = vertices;
            this.materials = materials;
        }

        private void add(SingleQuadParticle.Layer layer, float[] values, int[] ints,
                         int particleIndex, boolean emissive) {
            int offset = particleIndex * 12;
            float x = values[offset];
            float y = values[offset + 1];
            float z = values[offset + 2];
            Quaternionf rotation = new Quaternionf(values[offset + 3], values[offset + 4],
                values[offset + 5], values[offset + 6]);
            float scale = values[offset + 7];
            float u0 = values[offset + 8];
            float u1 = values[offset + 9];
            float v0 = values[offset + 10];
            float v1 = values[offset + 11];
            Vector3f[] quad = {
                vertex(rotation, x, y, z, 1.0F, -1.0F, scale),
                vertex(rotation, x, y, z, 1.0F, 1.0F, scale),
                vertex(rotation, x, y, z, -1.0F, 1.0F, scale),
                vertex(rotation, x, y, z, -1.0F, -1.0F, scale)
            };
            float[][] uv = {{u1, v1}, {u1, v0}, {u0, v0}, {u0, v1}};
            int color = ints[particleIndex * 2];
            triangle(layer, quad, uv, 0, 1, 2, color, emissive);
            triangle(layer, quad, uv, 0, 2, 3, color, emissive);
            count++;
        }

        private void triangle(SingleQuadParticle.Layer layer, Vector3f[] quad, float[][] uv,
                              int a, int b, int c, int color, boolean emissive) {
            putVertex(quad[a]); putVertex(quad[b]); putVertex(quad[c]);
            Vector3f edgeOne = new Vector3f(quad[b]).sub(quad[a]);
            Vector3f edgeTwo = new Vector3f(quad[c]).sub(quad[a]);
            Vector3f normal = edgeOne.cross(edgeTwo, new Vector3f()).normalize();
            RayTracingTangent.Frame tangent = RayTracingTangent.fromTriangle(
                quad[a].x, quad[a].y, quad[a].z,
                quad[b].x, quad[b].y, quad[b].z,
                quad[c].x, quad[c].y, quad[c].z,
                uv[a][0], uv[a][1], uv[b][0], uv[b][1], uv[c][0], uv[c][1],
                normal.x, normal.y, normal.z);
            float alpha = ((color >>> 24) & 0xff) / 255.0F;
            float red = ((color >>> 16) & 0xff) / 255.0F;
            float green = ((color >>> 8) & 0xff) / 255.0F;
            float blue = (color & 0xff) / 255.0F;
            Identifier atlas = layer.textureAtlasLocation();
            boolean blockAtlas = TextureAtlas.LOCATION_BLOCKS.equals(atlas);
            boolean itemAtlas = TextureAtlas.LOCATION_ITEMS.equals(atlas);
            float selector = blockAtlas ? 1.0F : 2.0F;
            float textureSlot = itemAtlas ? -1.0F : blockAtlas ? 0.0F
                : textureSlots.computeIfAbsent(atlas, LivingEntityGeometryAdapter::textureSlotForRt);
            float emission = emissive
                ? RayTracingEmission.fromMinecraftLevel(15)
                    * RayTracingClientConfig.INSTANCE.emissionScale.get().floatValue()
                : 0.0F;

            materials.add(red); materials.add(green); materials.add(blue); materials.add(alpha);
            materials.add(normal.x); materials.add(normal.y); materials.add(normal.z); materials.add(0.0F);
            materials.add(uv[a][0]); materials.add(uv[a][1]);
            materials.add(uv[b][0]); materials.add(uv[b][1]);
            materials.add(uv[c][0]); materials.add(uv[c][1]);
            materials.add(1.0F); materials.add(selector);
            materials.add(tangent.angle()); materials.add(0.0F);
            materials.add(tangent.handedness()); materials.add(0.0F);
            materials.add(0.82F); materials.add(0.0F); materials.add(emission); materials.add(0.04F);
            materials.add(textureSlot); materials.add(0.0F); materials.add(0.0F); materials.add(1.0F);
        }

        private void putVertex(Vector3f vertex) {
            vertices.add(vertex.x); vertices.add(vertex.y); vertices.add(vertex.z);
        }

        private static Vector3f vertex(Quaternionf rotation, float x, float y, float z,
                                       float cornerX, float cornerY, float scale) {
            return new Vector3f(cornerX, cornerY, 0.0F).rotate(rotation).mul(scale).add(x, y, z);
        }
    }
}
