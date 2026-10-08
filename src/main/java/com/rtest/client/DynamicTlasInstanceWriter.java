package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Writes the Vulkan VkAccelerationStructureInstanceKHR ABI without owning Vulkan resources. */
public final class DynamicTlasInstanceWriter {
    public static final int INSTANCE_SIZE = 64;
    public static final int FACING_CULL_DISABLE = 1;
    /**
     * Ray cull masks implement the primary/secondary TLAS split without duplicating BLAS/TLAS
     * resources. The high bit is reserved for the first-person player body.
     */
    public static final int PRIMARY_RAY_MASK = 0x3f;
    public static final int SECONDARY_RAY_MASK = 0x7e;
    public static final int ALL_RAY_MASK = 0x3f;
    /** Nonzero mask kept outside every shader ray mask so UPDATE never toggles inactive slots. */
    public static final int UNTRACED_INSTANCE_MASK = 0x80;
    public static final int FIRST_PERSON_BODY_MASK = 0x40;
    public static final int FIRST_PERSON_ITEM_MASK = 0x01;
    public static final int MOTION_METADATA_BYTES_PER_SLOT = 7 * 16;
    private static final int MOTION_METADATA_PREVIOUS_TRANSFORM_OFFSET = 3 * 16;
    private static final int MOTION_METADATA_FLAGS_OFFSET = 6 * 16;


    private DynamicTlasInstanceWriter() { }

    /**
     * Writes one fixed-capacity dynamic instance array. Empty slots use a nonzero, untraced mask
     * and a valid dummy BLAS address; every instance remains active for Vulkan TLAS UPDATE rules.
     */
    public static void write(ByteBuffer destination, int slotCount, DynamicInstanceRegistry.Frame frame,
                             Map<Long, Long> blasAddresses, Map<Long, Integer> materialBases,
                             long dummyBlasAddress) {
        write(destination, slotCount, frame, blasAddresses, materialBases, dummyBlasAddress, 0.0F, 0.0F, 0.0F);
    }

    public static void write(ByteBuffer destination, int slotCount, DynamicInstanceRegistry.Frame frame,
                             Map<Long, Long> blasAddresses, Map<Long, Integer> materialBases,
                             long dummyBlasAddress, float originX, float originY, float originZ) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("Dynamic instance capacity must be positive");
        }
        DynamicInstanceRegistry.Instance[] instancesBySlot = new DynamicInstanceRegistry.Instance[slotCount];
        indexInstancesBySlot(frame, slotCount, instancesBySlot);
        write(destination, slotCount, instancesBySlot, blasAddresses, materialBases,
            dummyBlasAddress, originX, originY, originZ);
    }

    static void indexInstancesBySlot(DynamicInstanceRegistry.Frame frame, int slotCount,
                                     DynamicInstanceRegistry.Instance[] instancesBySlot) {
        if (slotCount < 1 || instancesBySlot.length < slotCount) {
            throw new IllegalArgumentException("Dynamic instance index is smaller than its capacity");
        }
        Arrays.fill(instancesBySlot, 0, slotCount, null);
        for (DynamicInstanceRegistry.Instance instance : frame.instances()) {
            int slot = instance.slot();
            if (slot >= 0 && slot < slotCount) {
                instancesBySlot[slot] = instance;
            }
        }
    }

    static void write(ByteBuffer destination, int slotCount,
                      DynamicInstanceRegistry.Instance[] instancesBySlot,
                      Map<Long, Long> blasAddresses, Map<Long, Integer> materialBases,
                      long dummyBlasAddress, float originX, float originY, float originZ) {
        destination.order(ByteOrder.nativeOrder());
        if (slotCount < 1 || instancesBySlot.length < slotCount
            || destination.remaining() < (long)slotCount * INSTANCE_SIZE) {
            throw new IllegalArgumentException("TLAS instance buffer is smaller than its capacity");
        }
        for (int slot = 0; slot < slotCount; slot++) {
            int offset = destination.position() + slot * INSTANCE_SIZE;
            DynamicInstanceRegistry.Instance instance = instancesBySlot[slot];
            long address = instance == null ? dummyBlasAddress : blasAddresses.getOrDefault(instance.identity(), dummyBlasAddress);
            int material = instance == null ? 0 : materialBases.getOrDefault(instance.identity(), 0);
            writeTransform(destination, offset, instance == null
                ? DynamicInstanceRegistry.Transform.identity() : instance.currentTransform(), originX, originY, originZ);
            // This is the equivalent of separate primary/secondary TLASes: the forced
            // first-person body is excluded by PRIMARY_RAY_MASK, but remains in
            // SECONDARY_RAY_MASK for reflections and continuation rays.
            boolean firstPersonBody = instance != null
                && (instance.family() == DynamicInstanceRegistry.Family.FIRST_PERSON_BODY
                    || (instance.flags() & DynamicInstanceRegistry.FLAG_FIRST_PERSON_BODY) != 0);
            int mask = instance != null && instance.active() && blasAddresses.containsKey(instance.identity())
                ? (firstPersonBody ? FIRST_PERSON_BODY_MASK
                    : (instance.flags() & DynamicInstanceRegistry.FLAG_PRIMARY_BODY_VIEW) != 0
                        ? FIRST_PERSON_ITEM_MASK
                    : instance.family() == DynamicInstanceRegistry.Family.FIRST_PERSON_ITEM
                        ? FIRST_PERSON_ITEM_MASK : ALL_RAY_MASK) : UNTRACED_INSTANCE_MASK;
            destination.putInt(offset + 48, (material & 0x00ffffff) | (mask << 24));
            // VkAccelerationStructureInstanceKHR packs SBT offset in bits 0..23 and flags in 24..31.
            destination.putInt(offset + 52, FACING_CULL_DISABLE << 24);
            destination.putLong(offset + 56, address);
        }
    }

    /**
     * Writes the current and previous object-to-world transforms used by temporal reprojection.
     * Each slot occupies seven vec4 values: three current rows, three previous rows, and metadata.
     * Metadata is stored as uint bit patterns so shader flags remain lossless.
     */
    public static void writeMotionMetadata(ByteBuffer destination, int slotCount,
                                           DynamicInstanceRegistry.Frame frame,
                                           float originX, float originY, float originZ) {
        writeMotionMetadata(destination, slotCount, frame, Set.of(), originX, originY, originZ);
    }

    public static void writeMotionMetadata(ByteBuffer destination, int slotCount,
                                           DynamicInstanceRegistry.Frame frame,
                                           Set<Long> historyResetIdentities,
                                           float originX, float originY, float originZ) {
        if (slotCount < 1) {
            throw new IllegalArgumentException("Dynamic instance capacity must be positive");
        }
        DynamicInstanceRegistry.Instance[] instancesBySlot = new DynamicInstanceRegistry.Instance[slotCount];
        indexInstancesBySlot(frame, slotCount, instancesBySlot);
        writeMotionMetadata(destination, slotCount, instancesBySlot, historyResetIdentities,
            originX, originY, originZ);
    }

    static void writeMotionMetadata(ByteBuffer destination, int slotCount,
                                    DynamicInstanceRegistry.Instance[] instancesBySlot,
                                    Set<Long> historyResetIdentities,
                                    float originX, float originY, float originZ) {
        destination.order(ByteOrder.nativeOrder());
        if (slotCount < 1 || instancesBySlot.length < slotCount
            || destination.remaining() < (long)slotCount * MOTION_METADATA_BYTES_PER_SLOT) {
            throw new IllegalArgumentException("Dynamic motion metadata buffer is smaller than its capacity");
        }
        for (int slot = 0; slot < slotCount; slot++) {
            int offset = destination.position() + slot * MOTION_METADATA_BYTES_PER_SLOT;
            DynamicInstanceRegistry.Instance instance = instancesBySlot[slot];
            boolean active = instance != null && instance.active();
            DynamicInstanceRegistry.Transform current = active
                ? instance.currentTransform() : DynamicInstanceRegistry.Transform.identity();
            DynamicInstanceRegistry.Transform previous = active
                ? instance.previousTransform() : current;
            writeTransform(destination, offset, current, originX, originY, originZ);
            writeTransform(destination, offset + MOTION_METADATA_PREVIOUS_TRANSFORM_OFFSET,
                previous, originX, originY, originZ);
            boolean historyReset = active
                && (instance.historyReset() || historyResetIdentities.contains(instance.identity()));
            destination.putInt(offset + MOTION_METADATA_FLAGS_OFFSET,
                active ? instance.flags() | (historyReset ? DynamicInstanceRegistry.FLAG_HISTORY_RESET : 0) : 0);
            destination.putInt(offset + MOTION_METADATA_FLAGS_OFFSET + 4, active ? 1 : 0);
            destination.putInt(offset + MOTION_METADATA_FLAGS_OFFSET + 8, historyReset ? 1 : 0);
            destination.putInt(offset + MOTION_METADATA_FLAGS_OFFSET + 12,
                instance == null ? 0xffffffff : instance.generation());
        }
    }

    private static void writeTransform(ByteBuffer buffer, int offset, DynamicInstanceRegistry.Transform transform,
                                       float originX, float originY, float originZ) {
        buffer.putFloat(offset, transform.m00()).putFloat(offset + 4, transform.m01())
            .putFloat(offset + 8, transform.m02()).putFloat(offset + 12, transform.m03() - originX);
        buffer.putFloat(offset + 16, transform.m10()).putFloat(offset + 20, transform.m11())
            .putFloat(offset + 24, transform.m12()).putFloat(offset + 28, transform.m13() - originY);
        buffer.putFloat(offset + 32, transform.m20()).putFloat(offset + 36, transform.m21())
            .putFloat(offset + 40, transform.m22()).putFloat(offset + 44, transform.m23() - originZ);
    }
}
