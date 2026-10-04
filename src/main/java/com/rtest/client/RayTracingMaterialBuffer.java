package com.rtest.client;

import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_ITEM_MATERIAL_TRIANGLES;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_ITEM_PLACEHOLDER_MATERIAL;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_PLACEHOLDER_TRIANGLES;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_SLOT_MATERIAL_TRIANGLES;
import static com.rtest.client.RayTracingDynamicInstances.MATERIAL_FLOATS_PER_TRIANGLE;

import com.rtest.client.RayTracingScene.SceneGeometry;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Defines stable per-section material spans and writes static and dynamic material rows. */
final class RayTracingMaterialBuffer {
    private static final int COMPACT_ABSOLUTE_SLACK_TRIANGLES = 16_384;

    // At most 64 MiB additional capacity; descriptors still expose only initialized live bytes.
    static long allocationBytes(long required, long current) {
        if (required < 0 || current < 0) throw new IllegalArgumentException("negative buffer size");
        if (required <= current) return current;
        long slack = Math.min(64L * 1024 * 1024, Math.max(64L * 1024, required / 2));
        // Host mapping uses ByteBuffer int addressing. Do not reserve unreachable space.
        long limit = Integer.MAX_VALUE - 15L;
        if (required > limit) return required;
        return required + Math.min(slack, limit - required);
    }

    /** Initial light upload; device-built ranges can be omitted on the GPU path. */
    static void writeLightData(NativeBuffer.Mapped mapped, RayTracingLightTree.Data data) {
        int[] words = data.words();
        if (!data.gpuBuild()) {
            writeChangedLightWords(mapped, new int[0], words);
            return;
        }
        if ((long)words.length * Integer.BYTES > mapped.buffer().capacity())
            throw new IllegalArgumentException("light buffer too small");
        mapped.flushOnlyWrittenRanges();
        var destination = mapped.buffer().asIntBuffer();
        destination.put(words, 0, 8);
        mapped.flushOnlyRange(0, 8L * Integer.BYTES);
        // Header + emitters/material map are the only host inputs. Every node,
        // forward/reverse pointer and reverse leaf index is written by compute.
        int first = words[4], count = words[7] - first;
        destination.position(first);
        destination.put(words, first, count);
        mapped.flushOnlyRange((long)first * Integer.BYTES, (long)count * Integer.BYTES);
    }

    /** Upload only changed 4 KiB pages, after the owning frame fence has retired. */
    static void writeChangedLightWords(NativeBuffer.Mapped mapped, int[] previous, int[] next) {
        if ((long)next.length * Integer.BYTES > mapped.buffer().capacity())
            throw new IllegalArgumentException("light buffer too small");
        mapped.flushOnlyWrittenRanges();
        var destination = mapped.buffer().asIntBuffer();
        final int pageWords = 1024;
        for (int start = 0; start < next.length; start += pageWords) {
            int end = Math.min(next.length, start + pageWords);
            boolean changed = end > previous.length;
            for (int i = start; !changed && i < end; i++) changed = next[i] != previous[i];
            if (!changed) continue;
            destination.position(start);
            destination.put(next, start, end - start);
            mapped.flushOnlyRange((long)start * Integer.BYTES, (long)(end - start) * Integer.BYTES);
        }
    }

    private RayTracingMaterialBuffer() {
    }

    static long materialFloatCount(SceneGeometry geometry, int capacity) {
        return ((long)geometry.materialLayout.highWaterTriangle()
                + DYNAMIC_PLACEHOLDER_TRIANGLES + DYNAMIC_ITEM_MATERIAL_TRIANGLES
                + (long)capacity * DYNAMIC_SLOT_MATERIAL_TRIANGLES)
            * MATERIAL_FLOATS_PER_TRIANGLE;
    }

    /** Dynamic instance materials follow the reserved static span, not just live triangle count. */
    static long dynamicMaterialBase(SceneGeometry geometry) {
        return (long)geometry.materialLayout.highWaterTriangle()
            + DYNAMIC_PLACEHOLDER_TRIANGLES + DYNAMIC_ITEM_MATERIAL_TRIANGLES;
    }

    static boolean sameDynamicMaterialBase(SceneGeometry previous, SceneGeometry next) {
        return previous != null && next != null
            && dynamicMaterialBase(previous) == dynamicMaterialBase(next);
    }

    /** A reused allocation can accept an arbitrary section delta because section indices are stable. */
    static boolean canUseIncrementalMaterialWrite(
        SceneGeometry previous,
        SceneGeometry next,
        boolean reuseMaterial,
        NativeBuffer previousMaterial,
        NativeBuffer nextMaterial
    ) {
        return previous != null && next != null && reuseMaterial && previousMaterial == nextMaterial;
    }

    static boolean hasChangedMaterialSections(SceneGeometry previous, SceneGeometry next) {
        Map<SectionKey, SceneGeometry.SectionGeometry> oldSections = sectionsByKey(previous.sections);
        if (oldSections.size() != next.sections.size()) return true;
        for (SceneGeometry.SectionGeometry section : next.sections) {
            SceneGeometry.SectionGeometry old = oldSections.get(SectionKey.of(section));
            if (old != section
                || previous.materialLayout.baseTriangle(section) != next.materialLayout.baseTriangle(section)) {
                return true;
            }
        }
        return false;
    }

    /** Writes changed or relocated sections at their new stable spans; untouched spans are skipped. */
    static void writeChangedSectionMaterials(
        NativeBuffer buffer,
        SceneGeometry previous,
        SceneGeometry next,
        boolean restorePrevious
    ) {
        try (NativeBuffer.Mapped mapped = buffer.map()) {
            writeChangedSectionMaterials(mapped, previous, next, restorePrevious);
        }
    }

    /** The exact write path is also exercised with a Java-owned mapped view, without a GPU. */
    static void writeChangedSectionMaterials(
        NativeBuffer.Mapped mapped, SceneGeometry previous, SceneGeometry next, boolean restorePrevious
    ) {
        SceneGeometry source = restorePrevious ? previous : next;
        SceneGeometry comparison = restorePrevious ? next : previous;
        Map<SectionKey, SceneGeometry.SectionGeometry> comparisonSections = sectionsByKey(comparison.sections);
        mapped.flushOnlyWrittenRanges();
        FloatBuffer destination = mapped.buffer().asFloatBuffer();
        for (SceneGeometry.SectionGeometry section : source.sections) {
            SceneGeometry.SectionGeometry old = comparisonSections.get(SectionKey.of(section));
            int targetTriangle = source.materialLayout.baseTriangle(section);
            boolean sameContentsAtSameAddress = old == section
                && comparison.materialLayout.baseTriangle(old) == targetTriangle;
            if (sameContentsAtSameAddress) continue;
            long byteOffset = (long)targetTriangle * MATERIAL_FLOATS_PER_TRIANGLE * Float.BYTES;
            long byteLength = (long)section.materialData.length * Float.BYTES;
            mapped.flushOnlyRange(byteOffset, byteLength);
            destination.position(Math.multiplyExact(targetTriangle, MATERIAL_FLOATS_PER_TRIANGLE));
            destination.put(section.materialData);
        }
        // Include shared placeholder writes in the visibility flush, including rollback.
        if (source.materialLayout.highWaterTriangle() != comparison.materialLayout.highWaterTriangle()) {
            writePlaceholders(mapped, destination, source.materialLayout.highWaterTriangle());
        }
    }

    /** Writes every live static span plus fixed dynamic rows into a fresh SSBO. */
    static void writeMaterialBuffer(NativeBuffer buffer, SceneGeometry geometry, int capacity) {
        writeMaterialBuffer(buffer, geometry, capacity, true);
    }

    /** Preserves dynamic rows when their base has not moved. */
    static void writeMaterialBuffer(
        NativeBuffer buffer, SceneGeometry geometry, int capacity, boolean clearDynamicSlots
    ) {
        try (NativeBuffer.Mapped mapped = buffer.map()) {
            FloatBuffer destination = mapped.buffer().asFloatBuffer();
            mapped.flushOnlyWrittenRanges();
            writeAllStatic(mapped, destination, geometry);
            if (clearDynamicSlots) clearDynamicSlots(mapped, destination, dynamicMaterialBase(geometry), capacity);
        }
    }

    /** Restores the old static snapshot after a failed in-place publication. */
    static void restoreStaticMaterialData(NativeBuffer buffer, SceneGeometry geometry) {
        try (NativeBuffer.Mapped mapped = buffer.map()) {
            mapped.flushOnlyWrittenRanges();
            writeAllStatic(mapped, mapped.buffer().asFloatBuffer(), geometry);
        }
    }

    static void clearDynamicMaterialSlots(NativeBuffer buffer, SceneGeometry geometry, int capacity) {
        try (NativeBuffer.Mapped mapped = buffer.map()) {
            FloatBuffer destination = mapped.buffer().asFloatBuffer();
            mapped.flushOnlyWrittenRanges();
            clearDynamicSlots(mapped, destination, dynamicMaterialBase(geometry), capacity);
        }
    }

    private static void writeAllStatic(NativeBuffer.Mapped mapped, FloatBuffer destination, SceneGeometry geometry) {
        for (SceneGeometry.SectionGeometry section : geometry.sections) {
            int base = geometry.materialLayout.baseTriangle(section);
            mapped.flushOnlyRange((long)base * MATERIAL_FLOATS_PER_TRIANGLE * Float.BYTES,
                (long)section.materialData.length * Float.BYTES);
            destination.position(Math.multiplyExact(base, MATERIAL_FLOATS_PER_TRIANGLE));
            destination.put(section.materialData);
        }
        writePlaceholders(mapped, destination, geometry.materialLayout.highWaterTriangle());
    }

    private static void writePlaceholders(NativeBuffer.Mapped mapped, FloatBuffer destination, int staticHighWater) {
        mapped.flushOnlyRange((long)staticHighWater * MATERIAL_FLOATS_PER_TRIANGLE * Float.BYTES,
            (long)(DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL.length + DYNAMIC_ITEM_PLACEHOLDER_MATERIAL.length) * Float.BYTES);
        destination.position(Math.multiplyExact(staticHighWater, MATERIAL_FLOATS_PER_TRIANGLE));
        destination.put(DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL);
        destination.put(DYNAMIC_ITEM_PLACEHOLDER_MATERIAL);
    }

    private static void clearDynamicSlots(NativeBuffer.Mapped mapped, FloatBuffer destination, long dynamicBase, int capacity) {
        int dynamicStart = Math.toIntExact(Math.multiplyExact(dynamicBase, MATERIAL_FLOATS_PER_TRIANGLE));
        int floatCount = Math.multiplyExact(
            Math.multiplyExact(capacity, DYNAMIC_SLOT_MATERIAL_TRIANGLES), MATERIAL_FLOATS_PER_TRIANGLE);
        if (floatCount > 0) mapped.flushOnlyRange((long)dynamicStart * Float.BYTES, (long)floatCount * Float.BYTES);
        destination.position(dynamicStart);
        for (int index = 0; index < floatCount; index++) destination.put(0.0F);
    }

    private static Map<SectionKey, SceneGeometry.SectionGeometry> sectionsByKey(
        List<SceneGeometry.SectionGeometry> sections
    ) {
        Map<SectionKey, SceneGeometry.SectionGeometry> result = new HashMap<>(sections.size());
        for (SceneGeometry.SectionGeometry section : sections) {
            if (result.put(SectionKey.of(section), section) != null) {
                throw new IllegalArgumentException("Duplicate section key in material layout");
            }
        }
        return result;
    }

    private record SectionKey(int x, int y, int z, int lodLevel) {
        static SectionKey of(SceneGeometry.SectionGeometry section) {
            return new SectionKey(section.originX, section.originY, section.originZ,
                section.terrainNodeKey().level());
        }
    }

    /** Immutable addresses for every live section in one scene snapshot. */
    static final class Layout {
        private final Map<SectionKey, Span> spans;
        private final int highWaterTriangle;
        private final List<FreeSpan> freeSpans;

        private Layout(Map<SectionKey, Span> spans, int highWaterTriangle) {
            this(spans, highWaterTriangle, List.of());
        }

        private Layout(Map<SectionKey, Span> spans, int highWaterTriangle, List<FreeSpan> freeSpans) {
            this.spans = Map.copyOf(spans);
            this.highWaterTriangle = highWaterTriangle;
            this.freeSpans = List.copyOf(freeSpans);
        }

        static Layout compact(List<SceneGeometry.SectionGeometry> sections) {
            Map<SectionKey, Span> result = new LinkedHashMap<>();
            int cursor = 0;
            for (SceneGeometry.SectionGeometry section : sections) {
                SectionKey key = SectionKey.of(section);
                if (result.put(key, new Span(cursor, section.triangleCount())) != null) {
                    throw new IllegalArgumentException("Duplicate section key in material layout");
                }
                cursor = Math.addExact(cursor, section.triangleCount());
            }
            return new Layout(result, cursor);
        }

        /** Reuses surviving section addresses, fills freed spans first, and compacts when fragmentation grows. */
        Layout update(List<SceneGeometry.SectionGeometry> sections) {
            if (sections.isEmpty()) return new Layout(Map.of(), 0);
            Set<SectionKey> nextKeys = new HashSet<>(sections.size());
            Map<SectionKey, Span> nextSpans = new LinkedHashMap<>();
            Set<SectionKey> retained = new HashSet<>();
            for (SceneGeometry.SectionGeometry section : sections) {
                SectionKey key = SectionKey.of(section);
                if (!nextKeys.add(key)) throw new IllegalArgumentException("Duplicate section key in material layout");
                Span previous = this.spans.get(key);
                if (previous != null && section.triangleCount() <= previous.capacityTriangles) {
                    nextSpans.put(key, previous);
                    retained.add(key);
                }
            }

            // Unused intervals survive scene snapshots, including remainders of split holes.
            List<FreeSpan> free = new ArrayList<>(this.freeSpans);
            for (Map.Entry<SectionKey, Span> entry : this.spans.entrySet()) {
                if (!retained.contains(entry.getKey())) {
                    Span span = entry.getValue();
                    free.add(new FreeSpan(span.baseTriangle, span.capacityTriangles));
                }
            }
            coalesceFreeSpans(free);
            FreeSpanAllocator allocator = new FreeSpanAllocator(free);
            int highWater = this.highWaterTriangle;
            for (SceneGeometry.SectionGeometry section : sections) {
                SectionKey key = SectionKey.of(section);
                if (nextSpans.containsKey(key)) continue;
                int requested = section.triangleCount();
                int freeBase = allocator.take(requested);
                if (freeBase >= 0) {
                    nextSpans.put(key, new Span(freeBase, requested));
                } else {
                    nextSpans.put(key, new Span(highWater, requested));
                    highWater = Math.addExact(highWater, requested);
                }
            }
            free.removeIf(range -> range.capacityTriangles == 0);
            highWater = trimFreeTail(highWater, free);
            int liveTriangles = 0;
            for (SceneGeometry.SectionGeometry section : sections) {
                liveTriangles = Math.addExact(liveTriangles, section.triangleCount());
            }
            long fragmentationAllowance = Math.max(
                COMPACT_ABSOLUTE_SLACK_TRIANGLES, liveTriangles / 20L);
            if ((long)highWater > (long)liveTriangles + fragmentationAllowance) {
                return compact(sections);
            }
            return new Layout(nextSpans, highWater, free);
        }

        int baseTriangle(SceneGeometry.SectionGeometry section) {
            Span span = this.spans.get(SectionKey.of(section));
            if (span == null || span.capacityTriangles < section.triangleCount()) {
                throw new IllegalArgumentException("Section is missing from material layout");
            }
            return span.baseTriangle;
        }

        int highWaterTriangle() {
            return this.highWaterTriangle;
        }

        private static void coalesceFreeSpans(List<FreeSpan> free) {
            free.sort(Comparator.comparingInt(FreeSpan::baseTriangle));
            int count = 0;
            for (int index = 0; index < free.size(); index++) {
                FreeSpan next = free.get(index);
                if (count > 0) {
                    FreeSpan previous = free.get(count - 1);
                    int end = Math.addExact(previous.baseTriangle, previous.capacityTriangles);
                    if (next.baseTriangle < end) throw new IllegalStateException("Overlapping material free intervals");
                    if (next.baseTriangle == end) {
                        free.set(count - 1, new FreeSpan(previous.baseTriangle,
                            Math.addExact(previous.capacityTriangles, next.capacityTriangles)));
                        continue;
                    }
                }
                free.set(count++, next);
            }
            free.subList(count, free.size()).clear();
        }

        private static int trimFreeTail(int highWater, List<FreeSpan> free) {
            while (!free.isEmpty()) {
                FreeSpan tail = free.get(free.size() - 1);
                if (Math.addExact(tail.baseTriangle, tail.capacityTriangles) != highWater) break;
                highWater = tail.baseTriangle;
                free.remove(free.size() - 1);
            }
            return highWater;
        }

        /** Address-order first-fit using subtree capacity maxima; small hole sets avoid the tree. */
        private static final class FreeSpanAllocator {
            private final List<FreeSpan> free;
            private final int[] maximum;
            private final int leaves;

            FreeSpanAllocator(List<FreeSpan> free) {
                this.free = free;
                if (free.size() < 64) {
                    leaves = 0;
                    maximum = null;
                } else {
                    int capacity = 1;
                    while (capacity < free.size()) capacity = Math.multiplyExact(capacity, 2);
                    leaves = capacity;
                    maximum = new int[Math.multiplyExact(leaves, 2)];
                    for (int i = 0; i < free.size(); i++) maximum[leaves + i] = free.get(i).capacityTriangles;
                    for (int i = leaves - 1; i > 0; i--) maximum[i] = Math.max(maximum[i * 2], maximum[i * 2 + 1]);
                }
            }

            int take(int requested) {
                int index = -1;
                if (maximum == null) {
                    for (int i = 0; i < free.size(); i++) {
                        if (free.get(i).capacityTriangles >= requested) { index = i; break; }
                    }
                } else if (maximum[1] >= requested) {
                    int node = 1;
                    while (node < leaves) node = maximum[node * 2] >= requested ? node * 2 : node * 2 + 1;
                    index = node - leaves;
                }
                if (index < 0) return -1;
                FreeSpan range = free.get(index);
                FreeSpan remainder = new FreeSpan(Math.addExact(range.baseTriangle, requested),
                    range.capacityTriangles - requested);
                // The remainder stays inside its original interval, so its address-order rank
                // cannot change. Zero slots preserve the tree's indexes until allocation ends.
                free.set(index, remainder);
                if (maximum != null) {
                    int node = leaves + index;
                    maximum[node] = remainder.capacityTriangles;
                    for (node /= 2; node > 0; node /= 2) {
                        maximum[node] = Math.max(maximum[node * 2], maximum[node * 2 + 1]);
                    }
                }
                return range.baseTriangle;
            }
        }
    }

    private record Span(int baseTriangle, int capacityTriangles) {
    }

    private record FreeSpan(int baseTriangle, int capacityTriangles) {
    }
}
