package com.rtest.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;

/** Publishes numeric snapshots from native entity, held-item, and whitelisted chest-model draws. */
public final class DynamicEntityGeometry {
    private static final long PLAYER_TOPOLOGY = 0x504C415945524CL;
    private static final long ITEM_TOPOLOGY = 0x4954454D4CL;
    private static final long FIRST_PERSON_ITEM_ID = 0x4000000000000000L;
    private static final long PARTICLE_ID = 0x3000000000000000L;
    private static final long PARTICLE_TOPOLOGY = 0x5041525449434C45L;
    private static final long LIVING_TOPOLOGY = 0x4C4956494E474CL;
    // This is the Vulkan pass's fixed dynamic TLAS capacity. Overflow is counted as an RT
    // admission failure; it must never be silently truncated into an apparently represented frame.
    private static final int DYNAMIC_SLOT_CAPACITY = 64;
    // Keep CPU admission identical to RayTracingVulkanPass material ranges. An over-budget mesh
    // must remain an explicit raster fallback; registering it and masking it later would lose it.
    static final int DYNAMIC_MODEL_TRIANGLE_CAPACITY = 512;
    static final int DYNAMIC_ITEM_TRIANGLE_CAPACITY = 1024;
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private final DynamicInstanceRegistry registry = new DynamicInstanceRegistry(2, DYNAMIC_SLOT_CAPACITY);

    public record Instance(DynamicInstanceRegistry.Instance snapshot, Family family, float width,
                           float height, long topology) { }
    public enum Family { PLAYER_BODY, FIRST_PERSON_BODY, LIVING_BODY, BLOCK_ENTITY_MODEL, ITEM_PLACEHOLDER, FIRST_PERSON_ITEM, PARTICLE }
    public record Frame(DynamicInstanceRegistry.Frame dynamicFrame, List<Instance> instances,
                        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> playerMeshes,
                        java.util.Map<Long, Identifier> playerSkinTextures,
                        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> livingMeshes,
                        java.util.Map<Long, Identifier> livingTextures,
                        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> blockEntityMeshes,
                        java.util.Map<Long, Identifier> blockEntityTextures,
                        java.util.Map<Integer, Identifier> livingTextureSlots,
                        java.util.Map<Long, ItemModelGeometryAdapter.Mesh> itemMeshes,
                        int admissionFailures) {
        public Frame {
            // All arguments are freshly-built local collections in collect(); wrapping them
            // avoids copying every map and list a second time on every render frame.
            instances = java.util.Collections.unmodifiableList(instances);
            playerMeshes = java.util.Collections.unmodifiableMap(playerMeshes);
            playerSkinTextures = java.util.Collections.unmodifiableMap(playerSkinTextures);
            livingMeshes = java.util.Collections.unmodifiableMap(livingMeshes);
            livingTextures = java.util.Collections.unmodifiableMap(livingTextures);
            blockEntityMeshes = java.util.Collections.unmodifiableMap(blockEntityMeshes);
            blockEntityTextures = java.util.Collections.unmodifiableMap(blockEntityTextures);
            livingTextureSlots = java.util.Collections.unmodifiableMap(livingTextureSlots);
            itemMeshes = java.util.Collections.unmodifiableMap(itemMeshes);
        }

        /** Identities with a concrete dynamic entity instance in the current RT frame. */
        public Set<Long> representedEntityIds() {
            Set<Long> represented = new java.util.LinkedHashSet<>();
            for (Instance instance : instances) {
                if (instance.family() != Family.FIRST_PERSON_ITEM
                    && instance.family() != Family.BLOCK_ENTITY_MODEL
                    && instance.family() != Family.PARTICLE) {
                    represented.add(instance.snapshot().identity());
                }
            }
            return Set.copyOf(represented);
        }

        /** Counts represented entities without allocating the diagnostic identity set. */
        public int representedEntityCount() {
            int count = 0;
            for (Instance instance : instances) {
                if (instance.family() != Family.FIRST_PERSON_ITEM
                    && instance.family() != Family.BLOCK_ENTITY_MODEL
                    && instance.family() != Family.PARTICLE) {
                    count++;
                }
            }
            return count;
        }

        /** Identities with a concrete block-entity model captured for this same frame. */
        public Set<Long> representedBlockEntityIds() {
            Set<Long> represented = new java.util.LinkedHashSet<>();
            for (Instance instance : instances) {
                if (instance.family() == Family.BLOCK_ENTITY_MODEL) {
                    represented.add(instance.snapshot().identity());
                }
            }
            return Set.copyOf(represented);
        }
    }

    /**
     * A visible entity with no submitted geometry is a capture miss, not an empty world frame.
     * Publishing that frame would make the dynamic TLAS mask every previously represented slot.
     */
    private static long mixRevision(long topology, int triangleCount) {
        long value = topology ^ (Integer.toUnsignedLong(triangleCount) * 0x9e3779b97f4a7c15L);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return value;
    }

    private static long hashFloats(float[] values) {
        long hash = 0xcbf29ce484222325L;
        for (float value : values) {
            hash ^= Integer.toUnsignedLong(Float.floatToRawIntBits(value));
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    static boolean isTransientCaptureMiss(Frame current, Frame previous) {
        return current != null
            && previous != null
            && current.instances().isEmpty()
            && current.admissionFailures() > 0
            && previous.dynamicFrame().activeCount() > 0;
    }

    /**
     * Consumes the current vanilla draw's living-entity/player/chest vertices and item feature quads;
     * never re-runs setupAnim or invents renderer transforms. The first-person body is explicitly
     * submitted by the capture mixin so RT can keep it for secondary/reflection rays; visibility
     * masks, rather than omission, keep it out of the RT primary ray.
     */
    public Frame collect(ClientLevel level, Camera camera, int renderDistanceChunks, float partialTick) {
        var nativePlayers = PlayerModelGeometryAdapter.drain();
        var nativePlayerItems = ItemModelGeometryAdapter.drainPlayerItems();
        var nativeItems = ItemModelGeometryAdapter.drain();
        var nativeFirstPersonItems = ItemModelGeometryAdapter.drainFirstPersonItems();
        var nativeLiving = LivingEntityGeometryAdapter.drain();
        var nativeBlockEntities = BlockEntityModelGeometryAdapter.drain();
        var nativeParticles = ParticleGeometryAdapter.drain();
        if (!RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
            // With dynamic capture disabled every world entity is a vanilla fallback. Do not
            // cancel LevelRenderer and then erase these entities with the RT display copy.
            int fallback = 0;
            for (Entity entity : level.entitiesForRendering()) {
                if (!entity.isRemoved() && !entity.isPassenger() && !entity.isInvisible()) {
                    fallback++;
                }
            }
            return new Frame(new DynamicInstanceRegistry.Frame(registry.frame(), List.of(), java.util.Set.of(), 0),
                List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), fallback);
        }
        registry.beginFrame();
        List<Pending> pending = new ArrayList<>();
        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> playerMeshes = new java.util.HashMap<>();
        java.util.Map<Long, Identifier> playerSkinTextures = new java.util.HashMap<>();
        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> livingMeshes = new java.util.HashMap<>();
        java.util.Map<Long, Identifier> livingTextures = new java.util.HashMap<>();
        java.util.Map<Long, PlayerModelGeometryAdapter.Mesh> blockEntityMeshes = new java.util.HashMap<>();
        java.util.Map<Long, Identifier> blockEntityTextures = new java.util.HashMap<>();
        java.util.Map<Integer, Identifier> livingTextureSlots = LivingEntityGeometryAdapter.drainTextureSlots();
        java.util.Map<Long, ItemModelGeometryAdapter.Mesh> itemMeshes = new java.util.HashMap<>();
        int fallback = 0;
        int slotOverflowFallbacks = 0;
        double radius = Math.max(32.0, (renderDistanceChunks + 2) * 16.0);
        double radiusSquared = radius * radius;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isPassenger() || entity.isInvisible()) continue;
            double dx = entity.getX() - camera.position().x;
            double dy = entity.getY() - camera.position().y;
            double dz = entity.getZ() - camera.position().z;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
            if (entity instanceof AbstractClientPlayer) {
                var captured = nativePlayers.get(entity.getId());
                if (captured == null || captured.mesh().triangleCount() == 0) {
                    fallback++;
                    continue;
                }
                var mesh = captured.mesh();
                var livingLayers = nativeLiving.get(entity.getId());
                if (livingLayers != null) {
                    mesh = PlayerModelGeometryAdapter.append(mesh, livingLayers.mesh());
                }
                mesh = PlayerModelGeometryAdapter.append(mesh, nativePlayerItems.get(entity.getId()));
                if (mesh.triangleCount() > DYNAMIC_MODEL_TRIANGLE_CAPACITY) {
                    fallback++;
                    continue;
                }
                boolean localFirstPerson = entity == Minecraft.getInstance().player
                    && Minecraft.getInstance().options.getCameraType().isFirstPerson();
                boolean registered = registry.upsert(entity.getId(), localFirstPerson
                        ? DynamicInstanceRegistry.Family.FIRST_PERSON_BODY
                        : DynamicInstanceRegistry.Family.ENTITY,
                    new DynamicInstanceRegistry.GeometryKey(PLAYER_TOPOLOGY, PLAYER_TOPOLOGY),
                    DynamicInstanceRegistry.Transform.translation((float)captured.x(), (float)captured.y(), (float)captured.z()),
                    DynamicInstanceRegistry.FLAG_OPAQUE
                        | (localFirstPerson ? DynamicInstanceRegistry.FLAG_FIRST_PERSON_BODY : 0), false);
                if (!registered) {
                    fallback++;
                    slotOverflowFallbacks++;
                    continue;
                }
                pending.add(new Pending(entity.getId(), localFirstPerson ? Family.FIRST_PERSON_BODY : Family.PLAYER_BODY,
                    entity.getBbWidth(),
                    entity.getBbHeight(), PLAYER_TOPOLOGY));
                playerMeshes.put((long)entity.getId(), mesh);
                if (captured.skinTexture() != null) {
                    playerSkinTextures.put((long)entity.getId(), captured.skinTexture());
                }
            } else if (entity instanceof ItemEntity) {
                var captured = nativeItems.get(entity.getId());
                if (captured == null || captured.triangleCount() == 0) {
                    // A submitItem callback can miss the short-lived dispatcher scope. Reuse
                    // the last complete mesh for this entity instead of publishing the red test
                    // placeholder; a later successful capture replaces it when the stack changes.
                    captured = ItemModelGeometryAdapter.lastValid(entity.getId());
                }
                if (captured != null && captured.triangleCount() > DYNAMIC_ITEM_TRIANGLE_CAPACITY) {
                    fallback++;
                    continue;
                }
                if (captured != null && captured.triangleCount() > 0) {
                    Pending item = upsert(entity, DynamicEntityGeometry.Family.ITEM_PLACEHOLDER, ITEM_TOPOLOGY,
                        captured.materialRevision(), DynamicInstanceRegistry.FLAG_CUTOUT, partialTick);
                    if (item != null) {
                        pending.add(item);
                        itemMeshes.put((long)entity.getId(), captured);
                    } else {
                        fallback++;
                        slotOverflowFallbacks++;
                    }
                } else {
                    fallback++;
                }
                // Never publish an item instance without captured geometry. The placeholder BLAS
                // is a diagnostics resource, not a valid far-distance or culling fallback.
            } else {
                // Every entity renderer that submits a model is collected by the generic
                // ModelFeatureRenderer capture, including projectiles, vehicles and display
                // entities that do not extend LivingEntity.
                var captured = nativeLiving.get(entity.getId());
                if (captured != null && captured.mesh().triangleCount() > DYNAMIC_MODEL_TRIANGLE_CAPACITY) {
                    fallback++;
                    continue;
                }
                if (captured != null && captured.mesh().triangleCount() > 0) {
                    Pending living = upsert(entity, DynamicEntityGeometry.Family.LIVING_BODY, LIVING_TOPOLOGY,
                        LIVING_TOPOLOGY,
                        DynamicInstanceRegistry.FLAG_CUTOUT, partialTick);
                    if (living != null) {
                        pending.add(living);
                        livingMeshes.put((long)entity.getId(), captured.mesh());
                        livingTextures.put((long)entity.getId(), captured.texture());
                    } else {
                        fallback++;
                        slotOverflowFallbacks++;
                    }
                } else {
                    fallback++;
                }
            }
        }
        // First-person item vertices are already camera-relative because vanilla starts this
        // pass from the inverse view matrix. Give them a separate synthetic identity so their
        // animation is represented in TLAS instead of being appended to the world player body.
        for (var captured : nativeFirstPersonItems.entrySet()) {
            long id = FIRST_PERSON_ITEM_ID | (captured.getKey() & 0xffffffffL);
            ItemModelGeometryAdapter.FirstPersonSnapshot firstPerson = captured.getValue();
            ItemModelGeometryAdapter.Mesh mesh = firstPerson.mesh();
            if (mesh == null || mesh.triangleCount() == 0) {
                fallback++;
                continue;
            }
            if (mesh.triangleCount() > DYNAMIC_ITEM_TRIANGLE_CAPACITY) {
                fallback++;
                continue;
            }
            boolean registered = registry.upsert(id, DynamicInstanceRegistry.Family.FIRST_PERSON_ITEM,
                new DynamicInstanceRegistry.GeometryKey(ITEM_TOPOLOGY, mesh.materialRevision()),
                // The mesh belongs to the previous hand pass. Use the camera position captured
                // with that exact pose rather than the current frame's camera; mixing them caused
                // the PBR item to slide and jump during movement/turning.
                DynamicInstanceRegistry.Transform.translation(
                    (float)firstPerson.cameraX(), (float)firstPerson.cameraY(),
                    (float)firstPerson.cameraZ()),
                DynamicInstanceRegistry.FLAG_CUTOUT, false);
            if (registered) {
                pending.add(new Pending(id, Family.FIRST_PERSON_ITEM, 1.0F, 1.0F, ITEM_TOPOLOGY));
                itemMeshes.put(id, mesh);
            } else {
                fallback++;
                slotOverflowFallbacks++;
            }
        }
        // Block-entity snapshots come only from captured vanilla model draws. Their high-bit
        // identity namespace and BLOCK_ENTITY family safely reuse this registry's slots without
        // ever aliasing a signed 32-bit Entity id.
        for (var captured : nativeBlockEntities.values()) {
            long id = captured.identity();
            if (captured.mesh().triangleCount() == 0
                || captured.topology() == BlockEntityModelGeometryAdapter.UNSUPPORTED_TOPOLOGY) {
                fallback++;
                continue;
            }
            if (captured.mesh().triangleCount() > DYNAMIC_MODEL_TRIANGLE_CAPACITY) {
                fallback++;
                continue;
            }
            // Animated baked/custom models may keep the same owner type while their triangle
            // count, normals, tint, alpha or emission changes. Track topology and material
            // independently so Vulkan never pairs an updated BLAS with stale/zero material rows.
            long topologyRevision = mixRevision(captured.topology(), captured.mesh().triangleCount());
            long materialRevision = hashFloats(captured.mesh().materialData());
            boolean registered = registry.upsert(id, DynamicInstanceRegistry.Family.BLOCK_ENTITY,
                new DynamicInstanceRegistry.GeometryKey(topologyRevision, materialRevision),
                DynamicInstanceRegistry.Transform.translation(
                    (float)captured.x(), (float)captured.y(), (float)captured.z()),
                DynamicInstanceRegistry.FLAG_CUTOUT, false);
            if (registered) {
                pending.add(new Pending(id, Family.BLOCK_ENTITY_MODEL, 1.0F, 1.0F, captured.topology()));
                blockEntityMeshes.put(id, captured.mesh());
                blockEntityTextures.put(id, captured.texture());
            } else {
                fallback++;
                slotOverflowFallbacks++;
            }
        }
        if (nativeParticles.particleCount() > 0
                && nativeParticles.mesh().triangleCount() <= DYNAMIC_MODEL_TRIANGLE_CAPACITY) {
            boolean registered = registry.upsert(PARTICLE_ID, DynamicInstanceRegistry.Family.ENTITY,
                new DynamicInstanceRegistry.GeometryKey(PARTICLE_TOPOLOGY, nativeParticles.revision()),
                // Particle render-state vertices are camera-relative. One translated TLAS instance
                // reconstructs their world positions while keeping all quads in one dynamic BLAS.
                DynamicInstanceRegistry.Transform.translation(
                    (float)camera.position().x, (float)camera.position().y, (float)camera.position().z),
                DynamicInstanceRegistry.FLAG_CUTOUT | DynamicInstanceRegistry.FLAG_EMISSIVE,
                false);
            if (registered) {
                pending.add(new Pending(PARTICLE_ID, Family.PARTICLE, 1.0F, 1.0F, PARTICLE_TOPOLOGY));
                livingMeshes.put(PARTICLE_ID, nativeParticles.mesh());
            } else {
                fallback++;
                slotOverflowFallbacks++;
            }
        }
        DynamicInstanceRegistry.Frame dynamicFrame = registry.finish();
        if (slotOverflowFallbacks > 0 && dynamicFrame.frame() % 120L == 0L) {
            LOGGER.warn("RTest dynamic TLAS capacity {} exceeded by {} instance(s); keeping them vanilla raster",
                DYNAMIC_SLOT_CAPACITY, slotOverflowFallbacks);
        }
        Map<Long, DynamicInstanceRegistry.Instance> snapshotsById = new HashMap<>();
        for (DynamicInstanceRegistry.Instance snapshot : dynamicFrame.instances()) {
            snapshotsById.put(snapshot.identity(), snapshot);
        }
        List<Instance> instances = new ArrayList<>(pending.size());
        for (Pending value : pending) {
            DynamicInstanceRegistry.Instance snapshot = snapshotsById.get(value.identity);
            if (snapshot != null) {
                instances.add(new Instance(snapshot, value.family, value.width, value.height,
                    value.topology));
            }
        }
        return new Frame(dynamicFrame, instances, playerMeshes, playerSkinTextures,
            livingMeshes, livingTextures, blockEntityMeshes, blockEntityTextures,
            livingTextureSlots, itemMeshes, fallback);
    }

    void clear() {
        registry.clear();
    }

    private Pending upsert(Entity entity, Family family, long topology, long materialRevision,
                           int flags, float partialTick) {
        float x = (float)(entity.xo + (entity.getX() - entity.xo) * partialTick);
        float y = (float)(entity.yo + (entity.getY() - entity.yo) * partialTick);
        float z = (float)(entity.zo + (entity.getZ() - entity.zo) * partialTick);
        boolean registered = registry.upsert(entity.getId(), DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(topology, materialRevision),
            DynamicInstanceRegistry.Transform.translation(x, y, z), flags, false);
        return registered
            ? new Pending(entity.getId(), family, entity.getBbWidth(), entity.getBbHeight(), topology)
            : null;
    }

    private record Pending(long identity, Family family, float width, float height, long topology) { }
}
