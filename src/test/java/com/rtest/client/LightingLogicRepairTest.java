package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

/** Exercises the real particle preflight and owned vertex snapshots without a Vulkan device. */
public final class LightingLogicRepairTest {
    public static void main(String[] args) throws Exception {
        checkVisibilityCulling();
        long id = 0x3000000000000000L;
        var owner = new RayTracingDynamicInstances(64);
        var preflight = RayTracingDynamicInstances.class.getDeclaredMethod(
            "hasChangedDynamicMaterials", DynamicEntityGeometry.Frame.class);
        preflight.setAccessible(true);
        var cacheField = RayTracingDynamicInstances.class.getDeclaredField("livingModelBlas");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var cache = (Map<Long, RayTracingDynamicInstances.DynamicCachedBlas>)cacheField.get(owner);
        var mesh = new PlayerModelGeometryAdapter.Mesh(new float[9], new float[28]);
        require((boolean)preflight.invoke(owner, frame(id, mesh, true, true)), "new particle needs mapping");
        var cached = new RayTracingDynamicInstances.DynamicCachedBlas(1, 0, 1, mesh.vertices(), null, null);
        cached.uploadedMaterials = mesh.materialData().clone();
        cache.put(id, cached);
        require(!(boolean)preflight.invoke(owner, frame(id, mesh, true, true)), "unchanged particle skips mapping");
        require(!(boolean)preflight.invoke(owner, frame(id, mesh, false, true)),
            "initialized stable cache with no geometry change skips mapping");
        cached.uploadedMaterials = null;
        require((boolean)preflight.invoke(owner, frame(id, mesh, false, true)),
            "an existing cache without an uploaded snapshot still needs initialization");
        cached.uploadedMaterials = mesh.materialData().clone();
        float[] material = mesh.materialData().clone();
        material[3] = 1;
        var changed = new PlayerModelGeometryAdapter.Mesh(mesh.vertices(), material);
        require((boolean)preflight.invoke(owner, frame(id, changed, true, true)), "particle material-only change needs mapping");
        require(!(boolean)preflight.invoke(owner, frame(id, changed, true, false)), "inactive particle skips mapping");
        require(!(boolean)preflight.invoke(owner, frame(id, changed, false, true)), "no changed geometry skips mapping");
        cache.clear();
        require((boolean)preflight.invoke(owner, frame(id, changed, true, true)), "reappearing particle needs mapping");
        checkReactivation(owner, preflight, id, mesh);

        require(cached.uploadedVertices == cached.topologyVertices, "initial vertex snapshot must be shared owned storage");
        require(cached.uploadedVertices != mesh.vertices(), "capture must not own cache storage");
        mesh.vertices()[0] = 7;
        require(cached.uploadedVertices[0] == 0, "capture mutation cannot alter uploaded snapshot");
        var replacement = new RayTracingDynamicInstances.DynamicCachedBlas(1, 0, 1, mesh.vertices(), null, null);
        require(replacement.uploadedVertices[0] == 7 && cached.uploadedVertices[0] == 0,
            "replacement retains independent storage");
        String instances = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingDynamicInstances.java"));
        require(!instances.contains("cached.uploadedVertices = mesh.vertices();")
            && !instances.contains("cached.uploadedVertices = java.util.Arrays.copyOf(mesh.vertices()"),
            "publication must retain constructor-owned snapshot");
        require(instances.contains("uploadedVertices = java.util.Arrays.copyOf(vertices, vertices.length);"),
            "UPDATE must replace the snapshot rather than mutate topology storage");

        String shader = RayTracingShaders.RAYGEN_SHADER;
        int volume = shader.indexOf("vec3 samplePhysicalVolumeEmitter(");
        int query = shader.indexOf("vec3 staticVisibility = staticEmitterVisibility(", volume);
        int gate = shader.indexOf("if (!emitterTransportRequired(visibility, staticVisibility)) continue;", volume);
        int medium = shader.indexOf("PhysicalAtmMedium medium =", volume);
        require(query > volume && gate > query && medium > gate, "volume must finish visibility and cull before medium");
        int area = shader.indexOf("AreaDirectSplit estimateAreaDirect(");
        query = shader.indexOf("vec3 staticVisibility = staticEmitterVisibility(", area);
        gate = shader.indexOf("if (!emitterTransportRequired(result.visibility, staticVisibility)) return result;", area);
        int bsdf = shader.indexOf("BsdfValue bsdf = evaluateBsdf(", area);
        require(query > area && gate > query && bsdf > gate, "area BSDF must not live across static trace");
        require(shader.contains("return any(notEqual(actual, vec3(0.0))) || any(notEqual(staticVisibility, vec3(0.0)));"),
            "both actual and static channels must participate in exact-zero culling");
        System.out.println("Particle preflight, snapshot ownership and visibility scheduling passed (not GPU validation)");
    }

    private static void checkVisibilityCulling() {
        Random random = new Random(202610072);
        int skipped = 0;
        for (int trial = 0; trial < 100000; trial++) {
            float[] actual = new float[3], baseline = new float[3];
            boolean required = false;
            for (int channel = 0; channel < 3; channel++) {
                // Include opaque static/dynamic blockers, shadow disabled and tinted transmission.
                actual[channel] = trial % 5 <= 1 ? 0 : random.nextFloat();
                baseline[channel] = trial % 5 == 0 ? 0 : trial % 5 == 1 ? 1 : actual[channel];
                if (trial % 5 == 3 && channel != 1) actual[channel] = baseline[channel] = 0;
                if (trial % 5 == 4) actual[channel] = baseline[channel] = 1;
                required |= actual[channel] != 0 || baseline[channel] != 0;
            }
            if (!required) skipped++;
            for (int channel = 0; channel < 3; channel++) {
                float transport = random.nextFloat() * 100;
                float source = random.nextFloat() * 1000;
                float pdf = 1e-6f + random.nextFloat();
                float oldActual = transport * source * actual[channel] / pdf;
                float oldDelta = transport * source * (actual[channel] - baseline[channel]) / pdf;
                float newActual = required ? oldActual : 0;
                float newDelta = required ? oldDelta : 0;
                require(oldActual == newActual && oldDelta == newDelta,
                    "exact-zero culling must preserve finite actual and dynamic contributions");
                if (trial % 5 == 1) require(required, "dynamic-only shadow must not be culled");
            }
        }
        require(skipped == 20000, "only jointly occluded samples should be skipped");
    }

    private static DynamicEntityGeometry.Frame frame(long id, PlayerModelGeometryAdapter.Mesh mesh,
                                                     boolean changed, boolean active) {
        var transform = DynamicInstanceRegistry.Transform.identity();
        var snapshot = new DynamicInstanceRegistry.Instance(id, DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(1, 2), 0, 0, transform, transform,
            DynamicInstanceRegistry.FLAG_CUTOUT, active, true);
        var dynamic = new DynamicInstanceRegistry.Frame(2, List.of(snapshot), changed ? Set.of(id) : Set.of(), active ? 1 : 0);
        return frame(dynamic, mesh);
    }

    private static void checkReactivation(RayTracingDynamicInstances owner,
                                         java.lang.reflect.Method preflight, long id,
                                         PlayerModelGeometryAdapter.Mesh mesh) throws Exception {
        var registry = new DynamicInstanceRegistry(64);
        var key = new DynamicInstanceRegistry.GeometryKey(1, 2);
        var transform = DynamicInstanceRegistry.Transform.identity();
        registry.beginFrame();
        registry.upsert(id, DynamicInstanceRegistry.Family.ENTITY, key, transform, 0, false);
        registry.finish();
        registry.beginFrame();
        require(!registry.finish().instances().getFirst().active(), "missing capture masks the instance");
        // The GPU model cache releases inactive entries, while the registry retains its slot.
        registry.beginFrame();
        registry.upsert(id, DynamicInstanceRegistry.Family.ENTITY, key, transform, 0, false);
        var reactivated = registry.finish();
        require(reactivated.changedGeometry().isEmpty() && reactivated.instances().getFirst().historyReset(),
            "same-geometry reactivation resets history without a geometry change");
        for (var family : DynamicEntityGeometry.Family.values()) {
            require((boolean)preflight.invoke(owner, frame(reactivated, mesh, family)),
                "reactivation after GPU cache release must initialize materials: " + family);
        }
    }

    private static DynamicEntityGeometry.Frame frame(DynamicInstanceRegistry.Frame dynamic,
                                                     PlayerModelGeometryAdapter.Mesh mesh) {
        return frame(dynamic, mesh, DynamicEntityGeometry.Family.PARTICLE);
    }

    private static DynamicEntityGeometry.Frame frame(DynamicInstanceRegistry.Frame dynamic,
                                                     PlayerModelGeometryAdapter.Mesh mesh,
                                                     DynamicEntityGeometry.Family family) {
        var snapshot = dynamic.instances().getFirst();
        long id = snapshot.identity();
        var instance = new DynamicEntityGeometry.Instance(snapshot, family, 1, 1, 1);
        var models = Map.of(id, mesh);
        var item = new ItemModelGeometryAdapter.Mesh(mesh.vertices(), mesh.materialData());
        return new DynamicEntityGeometry.Frame(dynamic, List.of(instance), models, Map.of(),
            models, Map.of(), models, Map.of(), Map.of(), Map.of(id, item), 0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
