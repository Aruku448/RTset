package com.rtest.client;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import org.joml.Vector3f;
import org.slf4j.Logger;

/** Captures final vanilla ItemModel quads without reimplementing entity/item transforms or count animation. */
public final class ItemModelGeometryAdapter {
    private static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final java.util.Set<String> debugKeys = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Mesh> pending = new ConcurrentHashMap<>();
    private static final Map<Integer, Mesh> pendingPlayerItems = new ConcurrentHashMap<>();
    private static final Map<Integer, FirstPersonSnapshot> pendingFirstPersonItems = new ConcurrentHashMap<>();
    // Deferred vanilla item submission can occasionally arrive outside the dispatcher scope.
    // Keep the last complete mesh so a transient missed capture does not replace a real item
    // with the diagnostic placeholder BLAS.
    private static final Map<Integer, Mesh> lastValid = new ConcurrentHashMap<>();
    // Render states are frame-owned. Weak keys keep an exceptional missing endWorldDraw hook
    // from retaining every historical entity state until the next world unload.
    private static final Map<EntityRenderState, Integer> stateIds = new WeakHashMap<>();
    private static final ThreadLocal<Capture> current = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> firstPersonCapture = ThreadLocal.withInitial(() -> false);
    private static volatile RayTracingPbrSampler pbrSampler;
    private static Vec3 worldCamera;

    private static void debugOnce(String key, String message, Object... arguments) {
        if (debugKeys.add(key)) {
            LOGGER.info("[DEBUG-ITEM-CAPTURE] " + message, arguments);
        }
    }

    public record Mesh(float[] vertices, float[] materialData) {
        public Mesh {
            if (vertices.length % 9 != 0
                || materialData.length != vertices.length / 9 * MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("Item mesh/material stride mismatch");
            }
        }

        public int triangleCount() {
            return vertices.length / 9;
        }

        /** Stable key for UV, texture selector, tint and PBR changes at unchanged topology. */
        public long materialRevision() {
            long materialHash = Integer.toUnsignedLong(Arrays.hashCode(materialData));
            return (materialHash << 32) ^ Integer.toUnsignedLong(materialData.length);
        }
    }

    record FirstPersonSnapshot(double cameraX, double cameraY, double cameraZ, Mesh mesh) { }

    private ItemModelGeometryAdapter() {
    }

    static void setPbrSampler(RayTracingPbrSampler sampler) {
        pbrSampler = sampler;
    }

    public static void beginWorldDraw(Vec3 camera) {
        // LevelRenderer invokes this after submitFeatures(), while item entities have already
        // submitted their deferred item nodes. Do not clear pending captures here; drain() owns
        // the hand-off to DynamicEntityGeometry and clears the completed frame.
        worldCamera = camera;
    }

    public static void endWorldDraw() {
        current.remove();
        firstPersonCapture.set(false);
        stateIds.clear();
        worldCamera = null;
    }

    static void clear() {
        current.remove();
        firstPersonCapture.set(false);
        pending.clear();
        pendingPlayerItems.clear();
        pendingFirstPersonItems.clear();
        lastValid.clear();
        stateIds.clear();
        worldCamera = null;
    }

    public static void registerState(EntityRenderState state, int entityId) {
        if (state != null) {
            stateIds.put(state, entityId);
            debugOnce("register", "register entity={} state={}", entityId, System.identityHashCode(state));
        } else {
            debugOnce("register-null", "register skipped entity={} state=null", entityId);
        }
    }

    public static void beginItem(ItemEntityRenderState state) {
        beginEntity(state, worldCamera, false);
    }

    /** Entity submission happens before LevelRenderer prepares deferred feature playback. */
    public static void beginItem(ItemEntityRenderState state, Vec3 camera) {
        beginEntity(state, camera, false);
    }

    /** Captures item layers submitted by a third-person player renderer. */
    public static void beginPlayer(AvatarRenderState state) {
        beginPlayer(state, worldCamera);
    }

    /** Entity submission happens before LevelRenderer prepares the deferred feature frame. */
    public static void beginPlayer(AvatarRenderState state, Vec3 camera) {
        beginEntity(state, camera, true);
    }

    private static void beginEntity(EntityRenderState state, Vec3 camera, boolean player) {
        Integer entityId = state == null ? null : stateIds.get(state);
        if (state == null || entityId == null) {
            debugOnce(player ? "begin-player-rejected" : "begin-rejected",
                "begin rejected state={} mapped={}",
                state == null ? "null" : System.identityHashCode(state), entityId);
            current.set(null);
            return;
        }
        debugOnce(player ? "begin-player" : "begin",
            "begin entity={} state={} cameraProvided={}", entityId,
            System.identityHashCode(state), camera != null);
        beginItem(entityId, state.x, state.y, state.z, camera, player);
    }

    static void beginItem(int entityId, double x, double y, double z) {
        beginItem(entityId, x, y, z, worldCamera, false);
    }

    private static void beginItem(int entityId, double x, double y, double z, Vec3 camera) {
        beginItem(entityId, x, y, z, camera, false);
    }

    private static void beginItem(int entityId, double x, double y, double z, Vec3 camera, boolean player) {
        if (camera == null) {
            debugOnce("scope-rejected", "scope rejected entity={} camera=null", entityId);
            current.set(null);
            return;
        }
        current.set(new Capture(
            entityId,
            player,
            (float)(camera.x - x),
            (float)(camera.y - y),
            (float)(camera.z - z),
            pbrSampler));
    }

    public static void endItem() {
        endCapture(false);
    }

    public static void endPlayer() {
        endCapture(true);
    }

    /** Opens a camera-relative scope for the first-person held item submission. */
    public static void beginFirstPerson(int entityId) {
        beginFirstPerson(entityId, null);
    }

    /** First-person vanilla poses are already camera-relative after the inverse model-view setup. */
    public static void beginFirstPerson(int entityId, Vec3 camera) {
        firstPersonCapture.set(true);
        current.set(new Capture(entityId, false, true, 0.0F, 0.0F, 0.0F, pbrSampler,
            camera == null ? 0.0 : camera.x,
            camera == null ? 0.0 : camera.y,
            camera == null ? 0.0 : camera.z));
    }

    public static boolean isFirstPersonCaptureActive() {
        return firstPersonCapture.get();
    }

    public static void endFirstPerson() {
        Capture capture = current.get();
        current.remove();
        firstPersonCapture.set(false);
        if (capture != null && capture.firstPerson) {
            Mesh mesh = capture.finish();
            if (mesh.triangleCount() > 0) {
                pendingFirstPersonItems.put(capture.entityId, new FirstPersonSnapshot(
                    capture.cameraX, capture.cameraY, capture.cameraZ, mesh));
            }
        }
    }

    private static void endCapture(boolean player) {
        Capture capture = current.get();
        current.remove();
        if (capture != null) {
            if (capture.player != player) {
                debugOnce("scope-mismatch", "scope kind mismatch entity={} expectedPlayer={} actualPlayer={}",
                    capture.entityId, player, capture.player);
                return;
            }
            Mesh mesh = capture.finish();
            if (!player || mesh.triangleCount() > 0) {
                if (capture.firstPerson) {
                    pendingFirstPersonItems.put(capture.entityId, new FirstPersonSnapshot(
                        capture.cameraX, capture.cameraY, capture.cameraZ, mesh));
                } else if (player) {
                    pendingPlayerItems.put(capture.entityId, mesh);
                } else {
                    pending.put(capture.entityId, mesh);
                }
                if (!player && mesh.triangleCount() > 0) {
                    lastValid.put(capture.entityId, mesh);
                }
            }
            debugOnce(player ? "end-player" : "end", "end entity={} triangles={}",
                capture.entityId, mesh.triangleCount());
        } else {
            debugOnce("end-empty", "end without active scope");
        }
    }

    public static void captureQuad(PoseStack.Pose pose, BakedQuad quad, QuadInstance quadInstance) {
        Capture capture = current.get();
        if (capture != null) {
            debugOnce("quad-deferred", "quad entity={} path=deferred", capture.entityId);
            capture.quad(pose, quad, quadInstance);
        } else {
            debugOnce("quad-deferred-dropped", "quad dropped path=deferred current=null");
        }
    }

    /** Captures the quad list at submitItem, before vanilla defers feature playback. */
    public static void captureSubmit(PoseStack pose, int ignoredVanillaLight, int[] tintLayers, List<BakedQuad> quads) {
        Capture capture = current.get();
        if (capture == null) {
            debugOnce("submit-dropped", "submit dropped quads={} current=null", quads.size());
            return;
        }
        // The vanilla light argument is intentionally ignored: this snapshot is RT material
        // input, not a raster-lighting overlay.
        debugOnce("submit", "submit entity={} quads={}", capture.entityId, quads.size());
        QuadInstance quadInstance = new QuadInstance();
        for (BakedQuad quad : quads) {
            int color = -1;
            if (quad.materialInfo().isTinted()) {
                int tintIndex = quad.materialInfo().tintIndex();
                if (tintIndex >= 0 && tintIndex < tintLayers.length) {
                    color = tintLayers[tintIndex];
                }
            }
            quadInstance.setColor(color);
            capture.quad(pose.last(), quad, quadInstance);
        }
    }

    /** Captures the final posed baked quads used by BlockEntityRenderer.submitBlockModel. */
    static Mesh captureBlockModel(PoseStack.Pose pose, List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
                                  int[] tintLayers, float offsetX, float offsetY, float offsetZ,
                                  RayTracingPbrSampler sampler) {
        Capture capture = new Capture(0, false, offsetX, offsetY, offsetZ, sampler);
        QuadInstance quadInstance = new QuadInstance();
        for (var part : parts) {
            for (Direction direction : Direction.values()) {
                captureBlockQuads(capture, pose, part.getQuads(direction), tintLayers, quadInstance);
            }
            captureBlockQuads(capture, pose, part.getQuads(null), tintLayers, quadInstance);
        }
        return capture.finish();
    }

    private static void captureBlockQuads(Capture capture, PoseStack.Pose pose, List<BakedQuad> quads,
                                          int[] tintLayers, QuadInstance quadInstance) {
        for (BakedQuad quad : quads) {
            int color = -1;
            if (quad.materialInfo().isTinted()) {
                int tintIndex = quad.materialInfo().tintIndex();
                if (tintIndex >= 0 && tintIndex < tintLayers.length) {
                    color = tintLayers[tintIndex];
                }
            }
            quadInstance.setColor(color);
            capture.quad(pose, quad, quadInstance);
        }
    }

    static Map<Integer, Mesh> drain() {
        Map<Integer, Mesh> result = Map.copyOf(pending);
        pending.clear();
        if (!result.isEmpty()) {
            debugOnce("drain", "drain meshes={} entities={}", result.size(), result.keySet());
        }
        return result;
    }

    static Mesh lastValid(int entityId) {
        return lastValid.get(entityId);
    }

    static Map<Integer, FirstPersonSnapshot> drainFirstPersonItems() {
        Map<Integer, FirstPersonSnapshot> result = Map.copyOf(pendingFirstPersonItems);
        pendingFirstPersonItems.clear();
        return result;
    }

    static Map<Integer, Mesh> drainPlayerItems() {
        Map<Integer, Mesh> result = Map.copyOf(pendingPlayerItems);
        pendingPlayerItems.clear();
        if (!result.isEmpty()) {
            debugOnce("drain-player", "drain player-held meshes={} entities={}", result.size(), result.keySet());
        }
        return result;
    }

    private static final class Capture {
        private final int entityId;
        private final boolean player;
        private final boolean firstPerson;
        private final float offsetX;
        private final float offsetY;
        private final float offsetZ;
        private final RayTracingPbrSampler pbrSampler;
        private final double cameraX;
        private final double cameraY;
        private final double cameraZ;
        private final FloatArrayBuilder vertices = new FloatArrayBuilder();
        private final FloatArrayBuilder materials = new FloatArrayBuilder();
        private final Vector3f faceNormal = new Vector3f();
        private final Vector3f transformedPosition = new Vector3f();

        private Capture(int entityId, boolean player, float offsetX, float offsetY, float offsetZ,
                        RayTracingPbrSampler pbrSampler) {
            this(entityId, player, false, offsetX, offsetY, offsetZ, pbrSampler, 0.0, 0.0, 0.0);
        }

        private Capture(int entityId, boolean player, boolean firstPerson,
                        float offsetX, float offsetY, float offsetZ,
                        RayTracingPbrSampler pbrSampler,
                        double cameraX, double cameraY, double cameraZ) {
            this.entityId = entityId;
            this.player = player;
            this.firstPerson = firstPerson;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.pbrSampler = pbrSampler;
            this.cameraX = cameraX;
            this.cameraY = cameraY;
            this.cameraZ = cameraZ;
        }

        private void quad(PoseStack.Pose pose, BakedQuad quad, QuadInstance quadInstance) {
            Direction direction = quad.direction();
            pose.transformNormal(direction.getUnitVec3f(), this.faceNormal).normalize();
            BakedQuad.MaterialInfo info = quad.materialInfo();
            boolean translucent = info.layer() == ChunkSectionLayer.TRANSLUCENT || info.itemRenderType().hasBlending();
            TextureAtlasSprite sprite = info.sprite();
            int textureSelector = textureSelector(sprite == null ? null : sprite.atlasLocation());
            debugOnce("quad-info", "quad atlas={} selector={} sprite={} uv0=({}, {}) uv1=({}, {}) uv2=({}, {}) uv3=({}, {})",
                sprite == null ? null : sprite.atlasLocation(), textureSelector, sprite,
                UVPair.unpackU(quad.packedUV(0)), UVPair.unpackV(quad.packedUV(0)),
                UVPair.unpackU(quad.packedUV(1)), UVPair.unpackV(quad.packedUV(1)),
                UVPair.unpackU(quad.packedUV(2)), UVPair.unpackV(quad.packedUV(2)),
                UVPair.unpackU(quad.packedUV(3)), UVPair.unpackV(quad.packedUV(3)));
            float emission = RayTracingEmission.fromMinecraftLevel(info.lightEmission());
            for (int triangle = 0; triangle < 2; triangle++) {
                int a = 0;
                int b = triangle == 0 ? 1 : 2;
                int c = triangle == 0 ? 2 : 3;
                addVertex(pose, quad, quadInstance, a);
                addVertex(pose, quad, quadInstance, b);
                addVertex(pose, quad, quadInstance, c);
                RayTracingTangent.Frame tangent = RayTracingTangent.fromBakedQuad(
                    pose, quad, a, b, c, this.faceNormal.x, this.faceNormal.y, this.faceNormal.z);
                addMaterial(quad, quadInstance, a, faceNormal, textureSelector, translucent, emission,
                    a, b, c, tangent);
            }
        }

        private void addVertex(PoseStack.Pose pose, BakedQuad quad, QuadInstance quadInstance, int index) {
            pose.pose().transformPosition(quad.position(index), this.transformedPosition);
            vertices.add(this.transformedPosition.x + offsetX);
            vertices.add(this.transformedPosition.y + offsetY);
            vertices.add(this.transformedPosition.z + offsetZ);
        }

        private void addMaterial(BakedQuad quad, QuadInstance quadInstance, int tintIndex, Vector3f faceNormal,
                                 int textureSelector, boolean translucent, float emission,
                                 int uv0Index, int uv1Index, int uv2Index, RayTracingTangent.Frame tangent) {
            int color = ARGB.multiply(quadInstance.getColor(tintIndex), quad.bakedColors().color(tintIndex));
            materials.add(ARGB.red(color) / 255.0F);
            materials.add(ARGB.green(color) / 255.0F);
            materials.add(ARGB.blue(color) / 255.0F);
            materials.add(ARGB.alpha(color) / 255.0F);
            materials.add(faceNormal.x);
            materials.add(faceNormal.y);
            materials.add(faceNormal.z);
            materials.add(0.0F);
            addUv(quad.packedUV(uv0Index));
            addUv(quad.packedUV(uv1Index));
            addUv(quad.packedUV(uv2Index));
            materials.add(translucent ? 0.0F : 1.0F);
            materials.add((float)textureSelector);
            // Keep the seven-vec4 ABI. lighting.x/z carry the UV tangent rotation/handedness;
            // lighting.w is the PBR map index.
            materials.add(tangent.angle());
            materials.add(0.0F);
            materials.add(tangent.handedness());
            RayTracingPbrMaterials.Sample pbr = this.pbrSampler == null
                ? RayTracingPbrMaterials.defaultSample()
                : this.pbrSampler.sample(quad.materialInfo().sprite(),
                    quad.packedUV(uv0Index), quad.packedUV(uv1Index), quad.packedUV(uv2Index));
            materials.add((float)pbr.mapIndex());
            materials.add(pbr.hasSpecular() ? pbr.roughness() : (translucent ? 0.18F : 0.88F));
            materials.add(pbr.hasSpecular() ? pbr.metallic() : 0.0F);
            // Authored LabPBR emission takes precedence over vanilla block light for the
            // visible surface only; the seven-vec4 ABI and tangent metadata stay intact.
            materials.add(RayTracingPbrMaterials.resolveEmission(
                emission, pbr.emission(), pbr.hasEmission()));
            materials.add((translucent ? 1.0F : 0.0F) +
                (pbr.hasSpecular() ? pbr.reflectivity() : 0.04F));
            materials.add(textureSelector == 3 ? -1.0F : (translucent ? 0.02F : 0.0F));
            materials.add(translucent ? 0.02F : 0.0F);
            materials.add(translucent ? 0.02F : 0.0F);
            materials.add(translucent ? 1.5F : 1.0F);
        }

        private void addUv(long packedUv) {
            materials.add(UVPair.unpackU(packedUv));
            materials.add(UVPair.unpackV(packedUv));
        }

        private Mesh finish() {
            return new Mesh(vertices.toArray(), materials.toArray());
        }
    }

    static int textureSelector(Identifier atlasLocation) {
        // The shared material ABI uses 0 for an untextured placeholder, 1 for the block atlas,
        // and 3 for the item atlas. RayTracingShaders uses selector > 0.5 to enable sampling.
        if (atlasLocation == null) {
            return 0;
        }
        return TextureAtlas.LOCATION_ITEMS.equals(atlasLocation) ? 3 : 1;
    }
}
