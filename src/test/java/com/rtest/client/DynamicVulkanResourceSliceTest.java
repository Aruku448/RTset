package com.rtest.client;

/** Contract test for non-blocking frame slots and fence-protected retirement. */
public final class DynamicVulkanResourceSliceTest {
    public static void main(String[] args) {
        var slice = new DynamicVulkanResourceSlice(2);
        var fenceA = new FakeFence();
        var fenceB = new FakeFence();
        var first = slice.tryAcquireSlot();
        var second = slice.tryAcquireSlot();
        if (first == null || second == null || slice.tryAcquireSlot() != null) {
            throw new AssertionError("frame slots unexpectedly block or over-allocate");
        }
        slice.submit(first, fenceA);
        slice.submit(second, fenceB);
        fenceA.complete = true;
        var recycled = slice.tryAcquireSlot();
        if (recycled == null || recycled.index() != first.index()
            || recycled.generation() == first.generation()) {
            throw new AssertionError("completed frame slot was not recycled safely");
        }
        var resource = new FlagResource();
        var fenceResource = new FakeFence();
        slice.retire(resource, fenceResource);
        if (resource.closed || slice.collectRetired() != 0) {
            throw new AssertionError("in-flight resource was released early");
        }
        fenceResource.complete = true;
        if (slice.collectRetired() != 1 || !resource.closed) {
            throw new AssertionError("completed resource was not retired");
        }
        slice.close();
    }

    private static final class FakeFence implements DynamicVulkanResourceSlice.SubmissionFence {
        private boolean complete;
        @Override public boolean isComplete() { return complete; }
    }

    private static final class FlagResource implements DynamicVulkanResourceSlice.Resource {
        private boolean closed;
        @Override public void close() { closed = true; }
    }
}
