package com.rtest.client;

import java.nio.ByteBuffer;

/** Pure scheduling/invalidation math. Camera pose is deliberately absent from light identity. */
final class PersistentRtPolicy {
    static final int HEADER_WORDS = 32;
    static final int ROW_WORDS = 24;
    static final int SLOTS = 65536;
    static final int BANK_BYTES = SLOTS * ROW_WORDS * Integer.BYTES;
    static final int ALLOCATION_BYTES = HEADER_WORDS * Integer.BYTES + 2 * BANK_BYTES;
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
