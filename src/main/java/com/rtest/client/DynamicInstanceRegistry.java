package com.rtest.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** CPU-only lifecycle registry for dynamic geometry adapters. */
public final class DynamicInstanceRegistry {
    public static final int FLAG_OPAQUE = 1;
    public static final int FLAG_CUTOUT = 1 << 1;
    public static final int FLAG_TRANSLUCENT = 1 << 2;
    public static final int FLAG_EMISSIVE = 1 << 3;
    public static final int FLAG_HISTORY_RESET = 1 << 4;
    public static final int FLAG_FIRST_PERSON_BODY = 1 << 5;

    public enum Family { BLOCK_ENTITY, ENTITY, FIRST_PERSON_ITEM, FIRST_PERSON_BODY }

    /** Renderer-independent affine 3x4 transform in row-major order. */
    public record Transform(
        float m00, float m01, float m02, float m03,
        float m10, float m11, float m12, float m13,
        float m20, float m21, float m22, float m23
    ) {
        public static Transform identity() {
            return new Transform(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        }

        public static Transform translation(float x, float y, float z) {
            return new Transform(1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z);
        }
    }

    public record GeometryKey(long topologyKey, long materialKey) { }

    public record Instance(long identity, Family family, GeometryKey geometryKey, int slot, int generation,
                           Transform currentTransform, Transform previousTransform, int flags,
                           boolean active, boolean historyReset) {
        public boolean masked() { return !active; }
    }

    public record Frame(long frame, List<Instance> instances, Set<Long> changedGeometry, int activeCount) {
        public Frame {
            // finish() creates both collections as private snapshots and never mutates them
            // after this record is constructed. Do not copy the entire frame again per tick.
            instances = java.util.Collections.unmodifiableList(instances);
            changedGeometry = java.util.Collections.unmodifiableSet(changedGeometry);
        }
    }

    private static final int DEFAULT_RETIRE_FRAMES = 2;
    private final Map<Long, Entry> entries = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final Map<Integer, Integer> slotGenerations = new HashMap<>();
    private final int retireFrames;
    private final int maxSlots;
    private long frame;
    private int nextSlot;

    public DynamicInstanceRegistry() { this(DEFAULT_RETIRE_FRAMES); }

    public DynamicInstanceRegistry(int retireFrames) {
        this(retireFrames, Integer.MAX_VALUE);
    }

    public DynamicInstanceRegistry(int retireFrames, int maxSlots) {
        if (retireFrames < 0) throw new IllegalArgumentException("retireFrames must not be negative");
        if (maxSlots < 1) throw new IllegalArgumentException("maxSlots must be positive");
        this.retireFrames = retireFrames;
        this.maxSlots = maxSlots;
    }

    public long frame() { return frame; }

    public void beginFrame() {
        frame++;
        entries.values().forEach(entry -> entry.seen = false);
    }

    /** Copies one numeric snapshot; no Minecraft render state is retained. */
    /**
     * Adds one snapshot, returning false when the fixed GPU slot budget is exhausted. A rejected
     * instance is intentionally not marked RT-represented; callers must keep its vanilla path.
     */
    public boolean upsert(long identity, Family family, GeometryKey geometryKey, Transform transform,
                          int flags, boolean discontinuity) {
        if (family == null || geometryKey == null || transform == null) {
            throw new NullPointerException("Dynamic instance fields must not be null");
        }
        Entry entry = entries.get(identity);
        boolean isNew = entry == null;
        if (isNew) {
            int slot = allocateSlot();
            if (slot < 0) {
                return false;
            }
            entry = new Entry(identity, family, geometryKey, slot, generationFor(slot), transform);
            entries.put(identity, entry);
        }
        boolean wasInactive = !isNew && !entry.active;
        boolean geometryChanged = isNew || !entry.geometryKey.equals(geometryKey) || entry.family != family;
        Transform previous = isNew || wasInactive || discontinuity || geometryChanged
            ? transform : entry.currentTransform;
        entry.family = family;
        entry.geometryKey = geometryKey;
        entry.geometryChanged = geometryChanged;
        entry.previousTransform = previous;
        entry.currentTransform = transform;
        entry.flags = wasInactive || discontinuity || geometryChanged ? flags | FLAG_HISTORY_RESET : flags & ~FLAG_HISTORY_RESET;
        entry.historyReset = wasInactive || discontinuity || geometryChanged || isNew;
        entry.active = true;
        entry.seen = true;
        entry.lastSeenFrame = frame;
        return true;
    }

    public void remove(long identity) {
        Entry entry = entries.get(identity);
        if (entry != null) {
            entry.active = false;
            entry.seen = false;
            entry.lastSeenFrame = frame;
        }
    }

    /** Returns all slots, including masked slots, in deterministic GPU-upload order. */
    public Frame finish() {
        Set<Long> changed = new HashSet<>();
        List<Instance> result = new ArrayList<>();
        List<Long> expired = new ArrayList<>();
        int activeCount = 0;
        for (Entry entry : entries.values()) {
            if (!entry.seen) {
                entry.active = false;
                entry.historyReset = true;
                entry.flags |= FLAG_HISTORY_RESET;
                if (frame - entry.lastSeenFrame > retireFrames) expired.add(entry.identity);
            }
            if (entry.active) {
                activeCount++;
                if (entry.firstSeen || entry.geometryChanged) changed.add(entry.identity);
            }
            result.add(entry.snapshot());
            entry.firstSeen = false;
            entry.geometryChanged = false;
            entry.historyReset = false;
            entry.flags &= ~FLAG_HISTORY_RESET;
        }
        for (long identity : expired) {
            Entry entry = entries.remove(identity);
            if (entry != null) freeSlots.addLast(entry.slot);
        }
        result.sort(Comparator.comparingInt(Instance::slot));
        return new Frame(frame, result, changed, activeCount);
    }

    public int size() { return entries.size(); }

    public void clear() {
        entries.clear();
        freeSlots.clear();
        slotGenerations.clear();
        nextSlot = 0;
    }

    private int allocateSlot() {
        Integer slot = freeSlots.pollFirst();
        if (slot == null) {
            if (nextSlot >= maxSlots) {
                return -1;
            }
            slot = nextSlot++;
            slotGenerations.put(slot, 0);
        } else {
            slotGenerations.compute(slot, (ignored, generation) -> generation == null ? 1 : generation + 1);
        }
        return slot;
    }

    private int generationFor(int slot) { return slotGenerations.getOrDefault(slot, 0); }

    private static final class Entry {
        private final long identity;
        private Family family;
        private GeometryKey geometryKey;
        private final int slot;
        private final int generation;
        private Transform currentTransform;
        private Transform previousTransform;
        private int flags;
        private boolean active;
        private boolean seen;
        private boolean firstSeen = true;
        private boolean geometryChanged = true;
        private boolean historyReset;
        private long lastSeenFrame;

        private Entry(long identity, Family family, GeometryKey geometryKey, int slot, int generation, Transform transform) {
            this.identity = identity;
            this.family = family;
            this.geometryKey = geometryKey;
            this.slot = slot;
            this.generation = generation;
            this.currentTransform = transform;
            this.previousTransform = transform;
        }

        private Instance snapshot() {
            return new Instance(identity, family, geometryKey, slot, generation,
                currentTransform, previousTransform, flags, active, historyReset);
        }
    }
}
