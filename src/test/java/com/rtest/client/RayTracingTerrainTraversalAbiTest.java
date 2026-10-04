package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Dependency-free behavior contract for the terrain traversal ABI. */
public final class RayTracingTerrainTraversalAbiTest {
    private RayTracingTerrainTraversalAbiTest() { }

    public static void main(String[] args) {
        RayTracingTerrainTraversalAbi.Bounds rootBounds =
            new RayTracingTerrainTraversalAbi.Bounds(-1, -1, 0.2F, 1, 1, 0.4F);
        RayTracingTerrainTraversalAbi.Bounds childBounds =
            new RayTracingTerrainTraversalAbi.Bounds(-0.5F, -0.5F, 0.2F, 0.5F, 0.5F, 0.4F);
        int readyRenderableBlas = RayTracingTerrainTraversalAbi.NODE_FLAG_READY
            | RayTracingTerrainTraversalAbi.NODE_FLAG_RENDERABLE
            | RayTracingTerrainTraversalAbi.NODE_FLAG_HAS_BLAS;
        var root = new RayTracingTerrainTraversalAbi.NodeMetadata(rootBounds,
            RayTracingTerrainTraversalAbi.INVALID_INDEX, 1, 1, 0, 0, 0x123456, 12,
            readyRenderableBlas, 1.0F);
        var child = new RayTracingTerrainTraversalAbi.NodeMetadata(childBounds,
            0, RayTracingTerrainTraversalAbi.INVALID_INDEX, 0, 1, 1, 0x234567, 4,
            readyRenderableBlas, 10_000.0F);
        List<RayTracingTerrainTraversalAbi.NodeMetadata> nodes = List.of(root, child);

        ByteBuffer nodeBytes = RayTracingTerrainTraversalAbi.nodeMetadataBuffer(nodes);
        if (nodeBytes.remaining() != 128 || nodeBytes.getInt(32) != RayTracingTerrainTraversalAbi.INVALID_INDEX
                || nodeBytes.getInt(52) != 0x123456 || nodeBytes.getInt(60) != readyRenderableBlas) {
            throw new AssertionError("node metadata layout is not stable");
        }

        ByteBuffer instances = ByteBuffer.allocate(2 * RayTracingTerrainTraversalAbi.TLAS_INSTANCE_BYTES)
            .order(ByteOrder.nativeOrder());
        RayTracingTerrainTraversalAbi.writeTlasInstances(instances, 2,
            List.of(new RayTracingTerrainTraversalAbi.InstanceInput(child, 0x1122334455667788L, true)),
            0x99L);
        int childOffset = RayTracingTerrainTraversalAbi.TLAS_INSTANCE_BYTES;
        int packed = instances.getInt(childOffset + RayTracingTerrainTraversalAbi.INSTANCE_CUSTOM_INDEX_OFFSET);
        if ((packed & 0x00ffffff) != 0x234567
                || ((packed >>> 24) & 0xff) != RayTracingTerrainTraversalAbi.TERRAIN_INSTANCE_MASK
                || instances.getInt(childOffset + 52) != 0x01000000
                || instances.getLong(childOffset + 56) != 0x1122334455667788L) {
            throw new AssertionError("64-byte TLAS record layout is invalid");
        }
        if ((instances.getInt(48) >>> 24) != RayTracingTerrainTraversalAbi.UNTRACED_INSTANCE_MASK
                || instances.getLong(56) != 0x99L) {
            throw new AssertionError("unused slot must remain active while staying invisible to ray masks");
        }

        float[] identity = { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
        var selected = RayTracingTerrainTraversalAbi.selectNodes(nodes, identity, 0, 0,
            1280, 720, 100, null, RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z);
        if (selected.size() != 1 || selected.get(0).node() != child) {
            throw new AssertionError("complete children did not replace the parent");
        }
        var fallbackChild = new RayTracingTerrainTraversalAbi.NodeMetadata(childBounds, 0,
            RayTracingTerrainTraversalAbi.INVALID_INDEX, 0, 1, 1, 0x234567, 4,
            RayTracingTerrainTraversalAbi.NODE_FLAG_HAS_BLAS, 10_000.0F);
        selected = RayTracingTerrainTraversalAbi.selectNodes(List.of(root, fallbackChild), identity, 0, 0,
            1280, 720, 100, null, RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z);
        if (selected.size() != 1 || selected.get(0).node() != root) {
            throw new AssertionError("parent fallback was not selected while child was unavailable");
        }

        float[] depth = { 0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F,
            0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F, 0.2F };
        var hiz = new RayTracingTerrainTraversalAbi.HiZPyramid(4, 4, depth,
            RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z);
        var rect = new RayTracingTerrainTraversalAbi.ScreenRect(0.1F, 0.1F, 0.9F, 0.9F,
            0.8F, 0.9F, true);
        if (!RayTracingTerrainTraversalAbi.isHiZOccluded(rect, hiz, 4, 4,
                RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z)) {
            throw new AssertionError("forward conservative Hi-Z rejection failed");
        }
        depth[5] = 0.95F;
        hiz = new RayTracingTerrainTraversalAbi.HiZPyramid(4, 4, depth,
            RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z);
        if (RayTracingTerrainTraversalAbi.isHiZOccluded(rect, hiz, 4, 4,
                RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z)) {
            throw new AssertionError("Hi-Z rejected a rectangle with a visible texel");
        }
        System.out.println("Terrain traversal ABI contract passed");
    }
}
