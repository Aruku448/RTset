package com.rtest.client;

import com.rtest.client.RayTracingScene.SceneGeometry.SectionGeometry;
import java.lang.reflect.Constructor;
import java.util.List;

/** Exercises real immutable material layouts across multiple scene lifetimes; no Vulkan device. */
public final class MaterialLifetimeMathTest {
    private static Constructor<SectionGeometry> sectionConstructor;

    public static void main(String[] args) throws Exception {
        sectionConstructor = SectionGeometry.class.getDeclaredConstructor(
            int.class, int.class, int.class, float[].class, float[].class);
        sectionConstructor.setAccessible(true);
        if (args.length != 0 && args[0].equals("bench")) {
            benchmark();
            return;
        }
        String pass = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        if (!pass.contains("RayTracingMaterialBuffer.allocationBytes(nextMaterialFloatCount * Float.BYTES, this.materialBuffer.size)")
                || !pass.contains(".buffer(materialBuffer.buffer).offset(0).range(materialFloatCount * Float.BYTES)"))
            throw new AssertionError("GPU capacity growth/live descriptor range not wired");
        verifyAllocationGrowth();
        preservesFreedHolesAcrossLifetimes();
        joinsAdjacentFreeSpans();
        bitmapDifferential();
        System.out.println("Material lifetime interval math passed (not GPU release validation)");
    }

    private static void verifyAllocationGrowth() {
        long capacity = 0;
        int allocations = 0;
        for (int mib = 1; mib <= 256; mib++) {
            long required = mib * 1024L * 1024;
            long next = RayTracingMaterialBuffer.allocationBytes(required, capacity);
            if (next < required || next < capacity || next - required > 64L * 1024 * 1024)
                throw new AssertionError("Invalid bounded allocation capacity");
            if (next != capacity) allocations++;
            capacity = next;
        }
        if (allocations >= 20) throw new AssertionError("Growth still causes frequent allocations: " + allocations);
        if (RayTracingMaterialBuffer.allocationBytes(100, 1000) != 1000)
            throw new AssertionError("Existing allocation shrank");
        long edge = Integer.MAX_VALUE - 15L;
        if (RayTracingMaterialBuffer.allocationBytes(edge - 100, 0) != edge)
            throw new AssertionError("Growth must respect host mapping address limit");
        System.out.println("1–256 MiB growth: " + allocations + " allocations versus 256 exact allocations (synthetic)");
    }

    private static void preservesFreedHolesAcrossLifetimes() throws Exception {
        SectionGeometry a = section(0, 4), b = section(1, 1), c = section(2, 2);
        var first = RayTracingMaterialBuffer.Layout.compact(List.of(a, b));
        var removed = first.update(List.of(b));
        var addedLater = removed.update(List.of(b, c));
        if (addedLater.baseTriangle(c) != 0 || addedLater.highWaterTriangle() != 5
            || addedLater.baseTriangle(b) != first.baseTriangle(b)) {
            throw new AssertionError("freed material interval forgotten across snapshots: newBase="
                + addedLater.baseTriangle(c) + " highWater=" + addedLater.highWaterTriangle());
        }
        // Reusing a split hole must preserve its remaining portion for another later snapshot.
        SectionGeometry d = section(3, 2);
        var split = addedLater.update(List.of(b, c, d));
        if (split.baseTriangle(d) != 2 || split.highWaterTriangle() != 5) {
            throw new AssertionError("split material interval remainder lost");
        }
        // Older snapshots retain their addresses; update cannot mutate a published layout.
        if (first.baseTriangle(a) != 0 || first.highWaterTriangle() != 5) {
            throw new AssertionError("layout update mutated the previous snapshot");
        }
    }

    private static void joinsAdjacentFreeSpans() throws Exception {
        SectionGeometry a = section(0, 3), b = section(1, 4), c = section(2, 1), d = section(3, 6);
        var first = RayTracingMaterialBuffer.Layout.compact(List.of(a, b, c));
        var next = first.update(List.of(c, d));
        if (next.baseTriangle(d) != 0 || next.baseTriangle(c) != 7 || next.highWaterTriangle() != 8) {
            throw new AssertionError("contiguous freed spans must fit a larger material write without growing dynamic base");
        }
    }

    /** Independent occupancy-bitmap oracle: smallest contiguous free run, reserved capacities included. */
    private static void bitmapDifferential() throws Exception {
        java.util.Random random = new java.util.Random(123);
        java.util.Map<Integer, int[]> reserved = new java.util.HashMap<>();
        var layout = RayTracingMaterialBuffer.Layout.compact(List.of());
        int highWater = 0;
        for (int step = 0; step < 300; step++) {
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            java.util.Map<Integer, SectionGeometry> sections = new java.util.HashMap<>();
            for (int id = 0; id < 512; id++) {
                if (random.nextBoolean()) { ids.add(id); sections.put(id, section(id, 1 + random.nextInt(12))); }
            }
            java.util.Collections.shuffle(ids, random);
            java.util.ArrayList<SectionGeometry> next = new java.util.ArrayList<>();
            java.util.Map<Integer, int[]> expected = new java.util.HashMap<>();
            boolean[] used = new boolean[20000];
            for (int id : ids) {
                SectionGeometry section = sections.get(id);
                next.add(section);
                int[] prior = reserved.get(id);
                if (prior != null && prior[1] >= section.triangleCount()) {
                    expected.put(id, prior);
                    java.util.Arrays.fill(used, prior[0], prior[0] + prior[1], true);
                }
            }
            for (int id : ids) {
                if (expected.containsKey(id)) continue;
                int requested = sections.get(id).triangleCount();
                int base = -1, run = 0;
                for (int i = 0; i < highWater; i++) {
                    run = used[i] ? 0 : run + 1;
                    if (run == requested) { base = i + 1 - run; break; }
                }
                if (base < 0) { base = highWater; highWater += requested; }
                java.util.Arrays.fill(used, base, base + requested, true);
                expected.put(id, new int[] {base, requested});
            }
            while (highWater > 0 && !used[highWater - 1]) highWater--;
            layout = layout.update(next);
            if (layout.highWaterTriangle() != highWater) throw new AssertionError("bitmap highWater mismatch at " + step);
            for (int id : ids) {
                if (layout.baseTriangle(sections.get(id)) != expected.get(id)[0]) {
                    throw new AssertionError("bitmap first-fit mismatch at step " + step + " id=" + id);
                }
            }
            reserved = expected;
        }
    }

    private static volatile int sink;

    private static void benchmark() throws Exception {
        java.util.ArrayList<SectionGeometry> old = new java.util.ArrayList<>();
        java.util.ArrayList<SectionGeometry> next = new java.util.ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            SectionGeometry section = section(i, 16);
            old.add(section);
            if ((i & 1) != 0) next.add(section);
        }
        for (int i = 4000; i < 8000; i++) next.add(section(i, 8));
        var layout = RayTracingMaterialBuffer.Layout.compact(old);
        for (int i = 0; i < 5; i++) sink = layout.update(next).highWaterTriangle();
        long[] samples = new long[9];
        RayTracingMaterialBuffer.Layout result = null;
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            result = layout.update(next);
            samples[i] = System.nanoTime() - start;
            sink = result.highWaterTriangle();
        }
        java.util.Arrays.sort(samples);
        int hash = result.highWaterTriangle();
        for (SectionGeometry section : next) hash = hash * 31 + result.baseTriangle(section);
        System.out.printf(java.util.Locale.ROOT,
            "MATERIAL_BENCH old=4000 next=6000 holes=2000 median_ms=%.3f min_ms=%.3f max_ms=%.3f address_hash=%d highWater=%d%n",
            samples[4] / 1e6, samples[0] / 1e6, samples[8] / 1e6, hash, result.highWaterTriangle());
    }

    private static SectionGeometry section(int id, int triangles) throws Exception {
        float[] vertices = new float[triangles * 9], material = new float[triangles * 28];
        for (int i = 0; i < triangles; i++) {
            int v = i * 9, m = i * 28;
            vertices[v + 1] = vertices[v + 4] = vertices[v + 7] = 1;
            vertices[v + 3] = vertices[v + 6] = vertices[v + 8] = 1;
            material[m] = material[m + 1] = material[m + 2] = material[m + 3] = 1;
            material[m + 5] = material[m + 27] = 1;
        }
        return sectionConstructor.newInstance(id * 16, 0, 0, vertices, material);
    }
}
