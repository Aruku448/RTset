package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.rtest.client.RayTracingScene.SceneGeometry;
import com.rtest.client.RayTracingScene.SceneGeometry.SectionGeometry;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Actual sparse material writer and Mapped range accounting, with Java memory and no native allocation. */
public final class MappedUploadMathTest {
    public static void main(String[] args) throws Exception {
        Constructor<SectionGeometry> section = SectionGeometry.class.getDeclaredConstructor(
            int.class, int.class, int.class, float[].class, float[].class);
        section.setAccessible(true);
        float[] triangle = {0, 1, 0, 1, 1, 0, 1, 1, 1};
        float[] material = new float[28];
        material[3] = material[5] = material[27] = 1;
        SectionGeometry a = section.newInstance(0, 0, 0, triangle, material);
        SectionGeometry b = section.newInstance(16, 0, 0, triangle, material);
        SceneGeometry old = scene(List.of(a)), next = scene(List.of(a, b));
        int placeholders = RayTracingDynamicInstances.DYNAMIC_PLACEHOLDER_TRIANGLES
            + RayTracingDynamicInstances.DYNAMIC_ITEM_MATERIAL_TRIANGLES;
        int bytes = (2 + placeholders) * 28 * Float.BYTES;
        NativeBuffer.Mapped mapped = view(bytes);
        RayTracingMaterialBuffer.writeChangedSectionMaterials(mapped, old, next, false);
        long placeholderStart = 2L * 28 * Float.BYTES;
        long placeholderBytes = (long)(RayTracingDynamicInstances.DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL.length
            + RayTracingDynamicInstances.DYNAMIC_ITEM_PLACEHOLDER_MATERIAL.length) * Float.BYTES;
        for (long offset = placeholderStart; offset < placeholderStart + placeholderBytes; offset++) {
            if (!covered(mapped, offset)) throw new AssertionError("placeholder write missing from visibility flush at byte " + offset);
        }
        NativeBuffer.Mapped rollback = view(bytes);
        RayTracingMaterialBuffer.writeChangedSectionMaterials(rollback, old, next, true);
        for (long offset = 28L * Float.BYTES; offset < 28L * Float.BYTES + placeholderBytes; offset++) {
            if (!covered(rollback, offset)) throw new AssertionError("rollback placeholder flush missing at " + offset);
        }
        NativeBuffer.Mapped unchanged = view(bytes);
        RayTracingMaterialBuffer.writeChangedSectionMaterials(unchanged, old, old, false);
        if (whole(unchanged)) throw new AssertionError("no-op sparse upload must not flush the entire allocation");
        for (long[] invalid : new long[][] {{-1, 1}, {0, 0}, {bytes, 1}, {Long.MAX_VALUE, 2}}) {
            try { mapped.flushOnlyRange(invalid[0], invalid[1]); throw new AssertionError("invalid range accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        java.util.Random random = new java.util.Random(321);
        for (int iteration = 0; iteration < 300; iteration++) {
            java.util.ArrayList<long[]> ranges = new java.util.ArrayList<>();
            boolean[] expected = new boolean[4096];
            for (int i = 0; i < 100; i++) {
                int start = random.nextInt(4000), length = 1 + random.nextInt(96);
                ranges.add(new long[] {start, length});
                java.util.Arrays.fill(expected, start, start + length, true);
            }
            NativeBuffer.coalesceFlushRanges(ranges);
            boolean[] actual = new boolean[4096];
            long previousEnd = -1;
            for (long[] range : ranges) {
                if (range[0] <= previousEnd) throw new AssertionError("unmerged or unordered flush interval");
                previousEnd = range[0] + range[1];
                java.util.Arrays.fill(actual, (int)range[0], (int)previousEnd, true);
            }
            if (!java.util.Arrays.equals(expected, actual)) throw new AssertionError("flush union changes coverage");
        }
        java.util.ArrayList<long[]> adjacent = new java.util.ArrayList<>();
        for (int i = 0; i < 256; i++) adjacent.add(new long[] {i * 112L, 112});
        NativeBuffer.coalesceFlushRanges(adjacent);
        if (adjacent.size() != 1 || adjacent.getFirst()[1] != 256L * 112) throw new AssertionError("adjacent upload batching");
        System.out.println("Mapped material write/flush coverage passed (no Vulkan allocation or GPU flush performed)");
    }

    private static SceneGeometry scene(List<SectionGeometry> sections) throws Exception {
        var constructor = SceneGeometry.class.getDeclaredConstructor(List.class, float[].class, float[].class,
            int[].class, int.class, int.class, double.class, double.class, double.class);
        constructor.setAccessible(true);
        int triangles = sections.stream().mapToInt(SectionGeometry::triangleCount).sum();
        return constructor.newInstance(sections, new float[0], new float[0], new int[0], triangles, 2, 0, 0, 0);
    }

    private static NativeBuffer.Mapped view(int bytes) throws Exception {
        Constructor<NativeBuffer> owner = NativeBuffer.class.getDeclaredConstructor(
            VulkanDevice.class, long.class, long.class, long.class);
        owner.setAccessible(true);
        NativeBuffer buffer = owner.newInstance(null, 0, 0, (long)bytes);
        var constructor = NativeBuffer.Mapped.class.getDeclaredConstructor(NativeBuffer.class, ByteBuffer.class);
        constructor.setAccessible(true);
        // Deliberately not closed: this is Java-owned test memory, not an actual VMA mapping.
        return constructor.newInstance(buffer, ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()));
    }

    private static boolean whole(NativeBuffer.Mapped mapped) throws Exception {
        var field = NativeBuffer.Mapped.class.getDeclaredField("flushWhole");
        field.setAccessible(true);
        return field.getBoolean(mapped);
    }

    @SuppressWarnings("unchecked")
    private static boolean covered(NativeBuffer.Mapped mapped, long offset) throws Exception {
        if (whole(mapped)) return true;
        var field = NativeBuffer.Mapped.class.getDeclaredField("flushRanges");
        field.setAccessible(true);
        for (long[] range : (List<long[]>)field.get(mapped)) {
            if (offset >= range[0] && offset - range[0] < range[1]) return true;
        }
        return false;
    }
}
