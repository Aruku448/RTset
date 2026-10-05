package com.rtest.client;

import java.nio.ByteBuffer;

/** Pure scheduling/invalidation math. Camera pose is deliberately absent from light identity. */
final class PersistentRtPolicy {
    static final int HEADER_WORDS = 64;
    static final int ROW_WORDS = 24;
    static final int SLOTS = 65536;
    static final int BANK_BYTES = SLOTS * ROW_WORDS * Integer.BYTES;
    static final int JOB_BASE_WORDS = HEADER_WORDS + 2 * SLOTS * ROW_WORDS;
    static final int JOB_INDEX_BASE_WORDS = JOB_BASE_WORDS + SLOTS * ROW_WORDS;
    static final int ALLOCATION_BYTES = HEADER_WORDS * Integer.BYTES + 3 * BANK_BYTES + SLOTS * Integer.BYTES;
    static int mode(String value) {
        return switch (value) { case "current_direct" -> 1; case "current_visibility" -> 2; default -> 0; };
    }
    static long lightSignature(ByteBuffer b) {
        long h = 0xcbf29ce484222325L;
        // Sun/moon directions have bounded bins; intensity/environment changes invalidate.
        for (int offset : new int[]{80,84,88,12,124,232}) h = (h ^ Math.round(b.getFloat(offset) * 200)) * 0x100000001b3L;
        for (int offset : new int[]{244,248,252}) h = (h ^ Math.round(b.getFloat(offset) * 20)) * 0x100000001b3L;
        h = (h ^ Math.round(b.getFloat(96) * 8)) * 0x100000001b3L;
        for (int offset : new int[]{28,76,92,100,104,108,112,116,120,236,272,276,280,284,288,292,300})
            h = (h ^ Integer.toUnsignedLong(b.getInt(offset))) * 0x100000001b3L;
        return h;
    }
    static boolean sameStaticScene(RayTracingScene.SceneGeometry previous, RayTracingScene.SceneGeometry next) {
        if (previous == next) return true;
        if (previous == null || previous.revision != next.revision
                || !java.util.Arrays.equals(previous.pbrData, next.pbrData)) return false;
        if (previous.sections.isEmpty() || next.sections.isEmpty()) {
            return previous.sections.isEmpty() && next.sections.isEmpty()
                && previous.originX == next.originX && previous.originY == next.originY && previous.originZ == next.originZ
                && java.util.Arrays.equals(previous.vertices, next.vertices)
                && java.util.Arrays.equals(previous.materialData, next.materialData);
        }
        return sameSections(previous.sections, next.sections);
    }
    static boolean sameSections(java.util.List<RayTracingScene.SceneGeometry.SectionGeometry> previous,
                                java.util.List<RayTracingScene.SceneGeometry.SectionGeometry> next) {
        if (previous.size() != next.size()) return false;
        var byKey = new java.util.HashMap<RayTracingTerrainLod.NodeKey, RayTracingScene.SceneGeometry.SectionGeometry>();
        for (var section : previous) if (byKey.put(section.terrainNodeKey, section) != null) return false;
        for (var section : next) {
            var old = byKey.remove(section.terrainNodeKey);
            if (old == null) return false;
            if (old == section) continue;
            if (old.originX != section.originX || old.originY != section.originY || old.originZ != section.originZ
                    || old.vertexFingerprint != section.vertexFingerprint || old.materialFingerprint != section.materialFingerprint
                    || !java.util.Arrays.equals(old.vertices, section.vertices)
                    || !java.util.Arrays.equals(old.materialData, section.materialData)) return false;
        }
        return byKey.isEmpty();
    }
    static boolean fresh(int now, int timestamp, int maximumAge) {
        return Integer.toUnsignedLong(now - timestamp) <= maximumAge;
    }
    static int hash(int[] key, int mask) {
        int h = 0x811c9dc5;
        for (int value : key) h = (h ^ value) * 16777619;
        h ^= h >>> 16; h *= 0x7feb352d; h ^= h >>> 15; h *= 0x846ca68b; h ^= h >>> 16;
        return h & mask;
    }
}
