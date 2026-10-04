package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;

/** Contract test for the 64-byte Vulkan TLAS instance ABI. */
public final class DynamicTlasInstanceWriterTest {
    public static void main(String[] args) {
        int untracedMask = DynamicTlasInstanceWriter.UNTRACED_INSTANCE_MASK;
        if (untracedMask == 0
                || (untracedMask & DynamicTlasInstanceWriter.PRIMARY_RAY_MASK) != 0
                || (untracedMask & DynamicTlasInstanceWriter.SECONDARY_RAY_MASK) != 0) {
            throw new AssertionError("reserved TLAS slot mask must stay active and invisible to all ray types");
        }
        var registry = new DynamicInstanceRegistry();
        registry.beginFrame();
        registry.upsert(7L, DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(1, 2),
            DynamicInstanceRegistry.Transform.translation(3, 4, 5),
            DynamicInstanceRegistry.FLAG_OPAQUE, false);
        var frame = registry.finish();
        ByteBuffer bytes = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.write(bytes, 2, frame, Map.of(7L, 0x1234L), Map.of(7L, 9), 0x9999L);
        if (bytes.getFloat(12) != 3 || bytes.getFloat(28) != 4 || bytes.getFloat(44) != 5) {
            throw new AssertionError("dynamic transform was not encoded");
        }
        if ((bytes.getInt(48) & 0x00ffffff) != 9
            || ((bytes.getInt(48) >>> 24) & 0xff) != DynamicTlasInstanceWriter.ALL_RAY_MASK
            || bytes.getLong(56) != 0x1234L) {
            throw new AssertionError("active instance ABI fields are invalid");
        }
        if (((bytes.getInt(112) >>> 24) & 0xff) != DynamicTlasInstanceWriter.UNTRACED_INSTANCE_MASK
            || bytes.getLong(120) != 0x9999L) {
            throw new AssertionError("inactive slot was not masked with dummy BLAS");
        }
        if (bytes.getInt(52) != (DynamicTlasInstanceWriter.FACING_CULL_DISABLE << 24)) {
            throw new AssertionError("Instance flags corrupted the SBT offset");
        }
        DynamicInstanceRegistry.Instance[] instancesBySlot = new DynamicInstanceRegistry.Instance[2];
        DynamicTlasInstanceWriter.indexInstancesBySlot(frame, 2, instancesBySlot);
        ByteBuffer indexedBytes = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.write(indexedBytes, 2, instancesBySlot,
            Map.of(7L, 0x1234L), Map.of(7L, 9), 0x9999L, 0, 0, 0);
        if (!java.util.Arrays.equals(bytes.array(), indexedBytes.array())) {
            throw new AssertionError("Indexed TLAS writes differ from the public frame writer");
        }
        DynamicTlasInstanceWriter.write(bytes, 2, frame, Map.of(), Map.of(), 0x9999L);
        if ((bytes.getInt(48) >>> 24) != DynamicTlasInstanceWriter.UNTRACED_INSTANCE_MASK) {
            throw new AssertionError("Missing player BLAS must stay active but invisible to trace masks");
        }

        var firstPersonRegistry = new DynamicInstanceRegistry();
        firstPersonRegistry.beginFrame();
        firstPersonRegistry.upsert(8L, DynamicInstanceRegistry.Family.FIRST_PERSON_BODY,
            new DynamicInstanceRegistry.GeometryKey(3, 4),
            DynamicInstanceRegistry.Transform.identity(),
            DynamicInstanceRegistry.FLAG_OPAQUE | DynamicInstanceRegistry.FLAG_FIRST_PERSON_BODY, false);
        var firstPersonFrame = firstPersonRegistry.finish();
        ByteBuffer firstPersonBytes = ByteBuffer.allocate(DynamicTlasInstanceWriter.INSTANCE_SIZE)
            .order(ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.write(firstPersonBytes, 1, firstPersonFrame,
            Map.of(8L, 0x5678L), Map.of(8L, 11), 0x9999L);
        int firstPersonMask = (firstPersonBytes.getInt(48) >>> 24) & 0xff;
        if (firstPersonMask != DynamicTlasInstanceWriter.FIRST_PERSON_BODY_MASK
            || (firstPersonMask & DynamicTlasInstanceWriter.PRIMARY_RAY_MASK) != 0
            || (firstPersonMask & DynamicTlasInstanceWriter.SECONDARY_RAY_MASK) == 0) {
            throw new AssertionError("first-person body visibility mask does not split primary and secondary rays");
        }

        registry.beginFrame();
        registry.upsert(7L, DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(1, 2),
            DynamicInstanceRegistry.Transform.translation(8, 4, 5),
            DynamicInstanceRegistry.FLAG_CUTOUT, false);
        var movedFrame = registry.finish();
        ByteBuffer metadata = ByteBuffer.allocate(2 * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT)
            .order(ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.writeMotionMetadata(metadata, 2, movedFrame, 1, 2, 3);
        int slotOffset = movedFrame.instances().get(0).slot() * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT;
        if (metadata.getFloat(slotOffset + 12) != 7.0F
            || metadata.getFloat(slotOffset + 48 + 12) != 2.0F) {
            throw new AssertionError("Current and previous dynamic transforms were not encoded");
        }
        if (metadata.getInt(slotOffset + 96) != DynamicInstanceRegistry.FLAG_CUTOUT
            || metadata.getInt(slotOffset + 100) != 1
            || metadata.getInt(slotOffset + 104) != 0) {
            throw new AssertionError("Dynamic motion metadata flags are invalid");
        }
        byte[] firstMetadata = metadata.array().clone();
        DynamicInstanceRegistry.Instance[] metadataInstancesBySlot = new DynamicInstanceRegistry.Instance[2];
        DynamicTlasInstanceWriter.indexInstancesBySlot(movedFrame, 2, metadataInstancesBySlot);
        ByteBuffer indexedMetadata = ByteBuffer.allocate(2 * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT)
            .order(ByteOrder.nativeOrder());
        DynamicTlasInstanceWriter.writeMotionMetadata(indexedMetadata, 2,
            metadataInstancesBySlot, Set.of(), 1, 2, 3);
        if (!java.util.Arrays.equals(firstMetadata, indexedMetadata.array())) {
            throw new AssertionError("Indexed motion metadata differs from the public frame writer");
        }
        DynamicTlasInstanceWriter.writeMotionMetadata(indexedMetadata, 2,
            metadataInstancesBySlot, Set.of(), 1, 2, 3);
        if (!java.util.Arrays.equals(firstMetadata, indexedMetadata.array())) {
            throw new AssertionError("Stable motion metadata did not serialize deterministically");
        }
        DynamicTlasInstanceWriter.writeMotionMetadata(metadata, 2, movedFrame, Set.of(7L), 1, 2, 3);
        if ((metadata.getInt(slotOffset + 96) & DynamicInstanceRegistry.FLAG_HISTORY_RESET) == 0
            || metadata.getInt(slotOffset + 104) != 1) {
            throw new AssertionError("Mesh changes must reset dynamic motion history");
        }
    }
}
