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
        verifyProjectionConventions(identity, readyRenderableBlas);
        var oddHiZ = new RayTracingTerrainTraversalAbi.HiZPyramid(5, 1,
            new float[] {.8F, .8F, .8F, .8F, 0}, RayTracingTerrainTraversalAbi.DepthConvention.REVERSED_Z);
        if (oddHiZ.width(1) != 2 || oddHiZ.sample(1, 1, 0) != 0)
            throw new AssertionError("Odd Vulkan mip must include the last source texel");
        System.out.println("Terrain traversal ABI contract passed");
    }
    private static void verifyProjectionConventions(float[] identity, int flags) {
        var reversed = RayTracingTerrainTraversalAbi.DepthConvention.REVERSED_Z;
        var forward = RayTracingTerrainTraversalAbi.DepthConvention.FORWARD_Z;
        var nearCrossing = new RayTracingTerrainTraversalAbi.Bounds(-.2F, -.2F, .8F, .2F, .2F, 1.2F);
        var rect = RayTracingTerrainTraversalAbi.projectBounds(nearCrossing, identity, reversed);
        if (!rect.intersectsViewport() || rect.depthNear() != 1 || Math.abs(rect.depthFar() - .8F) > 1e-6F)
            throw new AssertionError("Reversed-Z node crossing near plane must stay visible with clamped depth");
        var farCrossing = new RayTracingTerrainTraversalAbi.Bounds(-.2F, -.2F, -.2F, .2F, .2F, .2F);
        if (!RayTracingTerrainTraversalAbi.projectBounds(farCrossing, identity, reversed).intersectsViewport())
            throw new AssertionError("Reversed-Z node crossing far plane must stay visible");
        for (var convention : new RayTracingTerrainTraversalAbi.DepthConvention[] {forward, reversed}) {
            for (float z : new float[] {-2, 2}) {
                var outside = new RayTracingTerrainTraversalAbi.Bounds(-.2F, -.2F, z, .2F, .2F, z + .1F);
                if (RayTracingTerrainTraversalAbi.projectBounds(outside, identity, convention).intersectsViewport())
                    throw new AssertionError("Whole node outside depth slab must be rejected");
            }
            var outsideXY = new RayTracingTerrainTraversalAbi.Bounds(2, 2, .2F, 3, 3, .8F);
            if (RayTracingTerrainTraversalAbi.projectBounds(outsideXY, identity, convention).intersectsViewport())
                throw new AssertionError("Whole node outside viewport must be rejected");
        }
        var bounds = new RayTracingTerrainTraversalAbi.Bounds(-.2F, -.2F, .2F, .2F, .2F, .8F);
        var node = new RayTracingTerrainTraversalAbi.NodeMetadata(bounds, -1, -1, 0, 0, 0, 0, 1, flags, 10000);
        // A reversed-Z occluder at .5 is behind this node's nearest .8 surface. Using the
        // forward nearest .2 would incorrectly discard the entire node.
        var hiz = new RayTracingTerrainTraversalAbi.HiZPyramid(1, 1, new float[] {.5F}, reversed);
        if (RayTracingTerrainTraversalAbi.selectNodes(List.of(node), identity, 0, 0, 100, 100, 100, hiz, reversed).size() != 1)
            throw new AssertionError("Selection must forward depth convention to projection");
    }

}
