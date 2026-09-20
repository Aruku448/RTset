package com.rtest.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Copies vertices from the actual vanilla draw; never constructs poses or animates models. */
public final class PlayerModelGeometryAdapter {
    private static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    private static final Map<Integer, Snapshot> pending = new ConcurrentHashMap<>();
    private static Vec3 worldCamera;

    public static void beginWorldDraw(Vec3 camera) {
        pending.clear();
        worldCamera = camera;
    }

    public static void endWorldDraw() { worldCamera = null; }

    static void clear() {
        pending.clear();
        worldCamera = null;
    }

    public static Vec3 worldCamera() { return worldCamera; }

    /** Updates the camera for an entity submit without clearing already captured model meshes. */
    public static void updateWorldCamera(Vec3 camera) {
        worldCamera = camera;
    }

    public record Mesh(float[] vertices, float[] materialData) {
        public Mesh {
            if (vertices.length % 9 != 0 || materialData.length != vertices.length / 9 * MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("Player mesh/material stride mismatch");
            }
        }
        public int triangleCount() { return vertices.length / 9; }
    }

    /**
     * Appends geometry captured from a player's item-in-hand feature to the already captured
     * player body. Both captures use the same entity-local coordinate space, so one dynamic BLAS
     * and one TLAS transform preserve the vanilla hand placement without replaying transforms.
     */
    static Mesh append(Mesh body, ItemModelGeometryAdapter.Mesh heldItem) {
        if (heldItem == null || heldItem.triangleCount() == 0) {
            return body;
        }
        return append(body, heldItem.vertices(), heldItem.materialData());
    }

    static Mesh append(Mesh body, Mesh additional) {
        if (additional == null || additional.triangleCount() == 0) {
            return body;
        }
        return append(body, additional.vertices(), additional.materialData());
    }

    private static Mesh append(Mesh body, float[] additionalVertices, float[] additionalMaterials) {
        float[] vertices = java.util.Arrays.copyOf(body.vertices(),
            body.vertices().length + additionalVertices.length);
        float[] materials = java.util.Arrays.copyOf(body.materialData(),
            body.materialData().length + additionalMaterials.length);
        System.arraycopy(additionalVertices, 0, vertices, body.vertices().length, additionalVertices.length);
        System.arraycopy(additionalMaterials, 0, materials, body.materialData().length, additionalMaterials.length);
        return new Mesh(vertices, materials);
    }

    public record Snapshot(double x, double y, double z, Identifier skinTexture, Mesh mesh) { }

    private PlayerModelGeometryAdapter() { }

    public static void publish(int id, double x, double y, double z, Mesh mesh) {
        publish(id, x, y, z, null, mesh);
    }

    public static void publish(int id, double x, double y, double z, Identifier skinTexture, Mesh mesh) {
        pending.put(id, new Snapshot(x, y, z, skinTexture, mesh));
    }

    /**
     * Claims the first opaque PlayerModel submit as the vanilla player body. Equipment extensions
     * are allowed to return PlayerModel as their armor model, so class or texture equality cannot
     * distinguish those later submits reliably.
     */
    public static boolean publishBodyIfAbsent(int id, double x, double y, double z,
                                              Identifier skinTexture, Mesh mesh) {
        return pending.putIfAbsent(id, new Snapshot(x, y, z, skinTexture, mesh)) == null;
    }

    static Map<Integer, Snapshot> drain() {
        Map<Integer, Snapshot> result = Map.copyOf(pending);
        pending.clear();
        return result;
    }

    public static Mesh captureDraw(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                   int light, int overlay, int color, float offsetX, float offsetY, float offsetZ) {
        return captureDraw(model, pose, buffer, light, overlay, color,
            offsetX, offsetY, offsetZ, null);
    }

    /** Captures model-local UVs while preserving vanilla's sprite wrapper for the raster delegate. */
    public static Mesh captureDraw(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                   int light, int overlay, int color, float offsetX, float offsetY, float offsetZ,
                                   TextureAtlasSprite sprite) {
        return captureDraw(model, pose, buffer, light, overlay, color,
            offsetX, offsetY, offsetZ, sprite, null);
    }

    /**
     * Captures model-local UVs and, when a LabPBR sampler is supplied, bakes the per-quad PBR
     * companion lookup into the same seven-vec4 material ABI used by the static scene.
     */
    public static Mesh captureDraw(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                   int light, int overlay, int color, float offsetX, float offsetY, float offsetZ,
                                   TextureAtlasSprite sprite, RayTracingPbrSampler pbrSampler) {
        return captureDraw(model, pose, buffer, light, overlay, color, offsetX, offsetY, offsetZ,
            sprite, pbrSampler, 0.0F);
    }

    /** Captures a draw whose vanilla pipeline can explicitly declare a radiating surface. */
    public static Mesh captureDraw(Model<?> model, PoseStack pose, VertexConsumer buffer,
                                   int light, int overlay, int color, float offsetX, float offsetY, float offsetZ,
                                   TextureAtlasSprite sprite, RayTracingPbrSampler pbrSampler,
                                   float pipelineEmission) {
        Capture capture = new Capture(buffer, offsetX, offsetY, offsetZ, sprite, pbrSampler,
            pipelineEmission);
        model.renderToBuffer(pose, capture, light, overlay, color);
        return capture.finish();
    }

    /** Tee consumer: forwards the original draw unchanged and copies its final quad vertices. */
    public static final class Capture implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float offsetX, offsetY, offsetZ;
        private final TextureAtlasSprite sprite;
        private final RayTracingPbrSampler pbrSampler;
        private final float pipelineEmission;
        private final FloatArrayBuilder vertices = new FloatArrayBuilder();
        private final FloatArrayBuilder materials = new FloatArrayBuilder();
        // position, uv, normal and rgba for each original vertex. Vanilla lightmap values
        // are forwarded to the raster delegate but never copied into RT material data.
        private final float[][] quad = new float[4][12];
        private int count;

        public Capture(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ) {
            this(delegate, offsetX, offsetY, offsetZ, null, null);
        }

        public Capture(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ,
                       TextureAtlasSprite sprite) {
            this(delegate, offsetX, offsetY, offsetZ, sprite, null);
        }

        public Capture(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ,
                       TextureAtlasSprite sprite, RayTracingPbrSampler pbrSampler) {
            this(delegate, offsetX, offsetY, offsetZ, sprite, pbrSampler, 0.0F);
        }

        public Capture(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ,
                       TextureAtlasSprite sprite, RayTracingPbrSampler pbrSampler,
                       float pipelineEmission) {
            this.delegate = delegate;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.sprite = sprite;
            this.pbrSampler = pbrSampler;
            this.pipelineEmission = Math.max(pipelineEmission, 0.0F);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) flushQuad();
            float[] vertex = quad[count++];
            java.util.Arrays.fill(vertex, 0.0F);
            vertex[0] = x + offsetX; vertex[1] = y + offsetY; vertex[2] = z + offsetZ;
            vertex[8] = vertex[9] = vertex[10] = vertex[11] = 1.0F;
            delegate.addVertex(x, y, z);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            float[] vertex = quad[count - 1];
            vertex[8] = r / 255.0F; vertex[9] = g / 255.0F;
            vertex[10] = b / 255.0F; vertex[11] = a / 255.0F;
            delegate.setColor(r, g, b, a);
            return this;
        }
        @Override public VertexConsumer setColor(int color) {
            return setColor((color >>> 16) & 255, (color >>> 8) & 255, color & 255, color >>> 24);
        }
        @Override public VertexConsumer setUv(float u, float v) {
            // Capture receives model-local UVs because it wraps vanilla's SpriteCoordinateExpander.
            // Store atlas UVs for RT, but pass the original values to the delegate so vanilla still
            // performs exactly one sprite remap for raster rendering.
            quad[count - 1][3] = sprite == null ? u : sprite.getU(u);
            quad[count - 1][4] = sprite == null ? v : sprite.getV(v);
            delegate.setUv(u, v); return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { delegate.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v); return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            quad[count - 1][5] = x; quad[count - 1][6] = y; quad[count - 1][7] = z;
            delegate.setNormal(x, y, z); return this;
        }
        @Override public VertexConsumer setLineWidth(float width) { delegate.setLineWidth(width); return this; }

        public Mesh finish() {
            if (count != 0) flushQuad();
            return new Mesh(vertices.toArray(), materials.toArray());
        }

        private void flushQuad() {
            // Custom entity renderers are allowed to emit lines or triangles. Forwarded vanilla
            // rendering already received those vertices; only complete quads are RT-compatible.
            if (count == 4) {
                triangle(0, 1, 2);
                triangle(0, 2, 3);
            }
            count = 0;
        }

        private void triangle(int a, int b, int c) {
            addVertex(a);
            addVertex(b);
            addVertex(c);
            float[] vertex = quad[a];
            // PBR companion maps are baked per triangle exactly like the static scene and item
            // path: lighting.x/z keep the UV tangent frame, lighting.w keeps the map index, and
            // the following vec4 keeps the decoded surface parameters.
            float pbrMapIndex = 0.0F;
            float roughness = 0.82F;
            float metallic = 0.0F;
            float emission = this.pipelineEmission;
            float reflectivity = 0.04F;
            if (this.pbrSampler != null && this.sprite != null) {
                RayTracingPbrMaterials.Sample pbr = this.pbrSampler.sample(this.sprite,
                    UVPair.pack(quad[a][3], quad[a][4]),
                    UVPair.pack(quad[b][3], quad[b][4]),
                    UVPair.pack(quad[c][3], quad[c][4]));
                pbrMapIndex = pbr.mapIndex();
                if (pbr.hasSpecular()) {
                    roughness = pbr.roughness();
                    metallic = pbr.metallic();
                    reflectivity = pbr.reflectivity();
                }
                emission = RayTracingPbrMaterials.resolveEmission(
                    emission, pbr.emission(), pbr.hasEmission());
                // EYES and ENTITY_TRANSLUCENT_EMISSIVE are emissive by render contract. A
                // companion map may strengthen them, but a zero/missing authored texel must not
                // turn vanilla full-bright eyes back into an ordinary dark surface.
                emission = Math.max(emission, this.pipelineEmission);
            }
            RayTracingTangent.Frame tangent = RayTracingTangent.fromTriangle(
                quad[a][0], quad[a][1], quad[a][2],
                quad[b][0], quad[b][1], quad[b][2],
                quad[c][0], quad[c][1], quad[c][2],
                quad[a][3], quad[a][4], quad[b][3], quad[b][4], quad[c][3], quad[c][4],
                vertex[5], vertex[6], vertex[7]);
            // uv2.z marks the vanilla entity alpha test and uv2.w selects the dynamic texture
            // descriptor array instead of the block atlas. optical.x is retagged with the
            // texture slot when the model layer is captured.
            materials.add(vertex[8]); materials.add(vertex[9]); materials.add(vertex[10]); materials.add(1.0F);
            materials.add(vertex[5]); materials.add(vertex[6]); materials.add(vertex[7]); materials.add(0.0F);
            materials.add(quad[a][3]); materials.add(quad[a][4]);
            materials.add(quad[b][3]); materials.add(quad[b][4]);
            materials.add(quad[c][3]); materials.add(quad[c][4]);
            materials.add(1.0F); materials.add(2.0F);
            materials.add(tangent.angle()); materials.add(0.0F);
            materials.add(tangent.handedness()); materials.add(pbrMapIndex);
            materials.add(roughness); materials.add(metallic);
            materials.add(emission); materials.add(reflectivity);
            // optical.z is reserved as the vanilla pipeline-emissive marker. It prevents a
            // resource-pack PBR _s map with no authored emission from erasing EYES semantics.
            materials.add(0.0F); materials.add(0.0F);
            materials.add(this.pipelineEmission > 0.0F ? 1.0F : 0.0F); materials.add(1.0F);
        }

        private void addVertex(int index) {
            float[] vertex = quad[index];
            // EyesLayer resubmits the parent model at exactly the body depth. Raster order
            // resolves that overlay, but a BLAS has no draw order for coincident triangles.
            // Separate only RT emissive-layer vertices; the original delegate stays unchanged.
            float normalLength = (float)Math.sqrt(vertex[5] * vertex[5]
                + vertex[6] * vertex[6] + vertex[7] * vertex[7]);
            float offset = this.pipelineEmission > 0.0F && normalLength > 1.0e-8F
                ? 0.001F / normalLength : 0.0F;
            vertices.add(vertex[0] + vertex[5] * offset);
            vertices.add(vertex[1] + vertex[6] * offset);
            vertices.add(vertex[2] + vertex[7] * offset);
        }
    }
}
