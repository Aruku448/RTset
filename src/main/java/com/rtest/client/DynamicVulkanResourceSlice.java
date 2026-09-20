package com.rtest.client;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Ownership seam for dynamic Vulkan resources. The class deliberately owns no Vulkan handles yet;
 * it defines the frame-slot and fence rules that the Vulkan adapter must obey.
 */
public final class DynamicVulkanResourceSlice implements AutoCloseable {
    public interface SubmissionFence {
        boolean isComplete();
    }

    public interface Resource extends AutoCloseable {
        @Override
        void close();
    }

    public record Slot(int index, long generation, boolean inFlight) { }

    private static final class FrameSlot {
        private final int index;
        private long generation;
        private SubmissionFence fence;
        private boolean inFlight;
        private boolean reserved;

        private FrameSlot(int index) { this.index = index; }

        private boolean available() {
            if (inFlight && fence != null && fence.isComplete()) {
                inFlight = false;
                fence = null;
            }
            return !inFlight && !reserved;
        }

        private Slot snapshot() { return new Slot(index, generation, inFlight); }
    }

    private final List<FrameSlot> slots;
    private final ArrayDeque<RetiredResource> retired = new ArrayDeque<>();
    private boolean closed;

    public DynamicVulkanResourceSlice(int frameSlotCount) {
        if (frameSlotCount < 2) throw new IllegalArgumentException("At least two frame slots are required");
        this.slots = new ArrayList<>(frameSlotCount);
        for (int i = 0; i < frameSlotCount; i++) slots.add(new FrameSlot(i));
    }

    /** Returns a free slot; never waits and never calls device-idle synchronization. */
    public Slot tryAcquireSlot() {
        checkOpen();
        for (FrameSlot slot : slots) {
            if (slot.available()) {
                slot.generation++;
                slot.reserved = true;
                return slot.snapshot();
            }
        }
        return null;
    }

    /** Marks a slot in flight until its submission fence completes. */
    public void submit(Slot acquired, SubmissionFence fence) {
        checkOpen();
        if (fence == null) throw new NullPointerException("fence");
        FrameSlot slot = slot(acquired);
        if (!slot.reserved || slot.inFlight || slot.generation != acquired.generation()) {
            throw new IllegalStateException("Slot is not an acquired writable frame slot");
        }
        slot.reserved = false;
        slot.fence = fence;
        slot.inFlight = true;
    }

    /** Retires a resource; the caller supplies the fence that protects its last GPU use. */
    public void retire(Resource resource, SubmissionFence fence) {
        checkOpen();
        if (resource == null || fence == null) throw new NullPointerException("resource/fence");
        retired.addLast(new RetiredResource(resource, fence));
        collectRetired();
    }

    public int collectRetired() {
        int released = 0;
        while (!retired.isEmpty() && retired.peekFirst().fence.isComplete()) {
            retired.removeFirst().resource.close();
            released++;
        }
        return released;
    }

    public int pendingRetirements() { return retired.size(); }

    private FrameSlot slot(Slot acquired) {
        if (acquired == null || acquired.index() < 0 || acquired.index() >= slots.size()) {
            throw new IllegalArgumentException("Unknown frame slot");
        }
        return slots.get(acquired.index());
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Dynamic Vulkan resource slice is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // A slice is normally closed during device/world teardown. Unlike frame acquisition,
        // teardown must wait: closing a retired resource merely because the owner is going away
        // would still let the GPU dereference freed Vulkan/VMA memory.
        while (!retired.isEmpty()) {
            RetiredResource pending = retired.peekFirst();
            while (!pending.fence.isComplete()) {
                Thread.yield();
            }
            retired.removeFirst().resource.close();
        }
    }

    private record RetiredResource(Resource resource, SubmissionFence fence) { }
}
