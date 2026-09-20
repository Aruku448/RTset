package com.rtest.client.fsr;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;

/** Small VMA-backed buffer wrapper shared by the local FSR compute passes. */
final class RtestVulkanBuffer implements AutoCloseable {
    private final long allocator;
    private final long allocation;
    private final long handle;
    private final long deviceAddress;
    private final long mappedAddress;
    private final long size;
    private boolean destroyed;

    RtestVulkanBuffer(long allocator, long allocation, long handle, long deviceAddress,
                      long mappedAddress, long size) {
        this.allocator = allocator;
        this.allocation = allocation;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.mappedAddress = mappedAddress;
        this.size = size;
    }

    long handle() {
        return this.handle;
    }

    long deviceAddress() {
        return this.deviceAddress;
    }

    long mappedAddress() {
        if (this.mappedAddress == 0L) {
            throw new IllegalStateException("Buffer is not host visible");
        }
        return this.mappedAddress;
    }

    long size() {
        return this.size;
    }

    void put(long offset, ByteBuffer source) {
        long length = source.remaining();
        if (offset < 0L || offset + length > this.size) {
            throw new IndexOutOfBoundsException("Buffer write exceeds allocation");
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(source) + source.position(), this.mappedAddress() + offset, length);
        Vma.vmaFlushAllocation(this.allocator, this.allocation, offset, length);
    }

    void destroy() {
        close();
    }

    @Override
    public void close() {
        if (!this.destroyed) {
            this.destroyed = true;
            Vma.vmaDestroyBuffer(this.allocator, this.handle, this.allocation);
        }
    }
}
