package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;

/** Owns Vulkan buffers and acceleration structures used by the ray-tracing pass. */
final class VulkanAccelerationResources {
    static long alignUp(long value, long alignment) {
        return (value + alignment - 1L) / alignment * alignment;
    }

    /** Aligns a Vulkan device address as an unsigned 64-bit value. */
    static long alignDeviceAddress(long value, long alignment) {
        if (alignment <= 0L || (alignment & (alignment - 1L)) != 0L) {
            throw new IllegalArgumentException("Vulkan device-address alignment must be a positive power of two");
        }
        return (value + alignment - 1L) & -alignment;
    }

    /**
     * Reject malformed AS arguments before they reach a native driver. RADV has historically
     * dereferenced a bad build descriptor while recording the command, turning a recoverable
     * Java-side resource bug into a JVM SIGSEGV. Keep these checks independent of Vulkan handles
     * so the contract test can exercise the arithmetic without a device.
     */
    static void validateBuildArguments(
        boolean topLevel,
        long inputAddress,
        long inputBufferSize,
        int primitiveCount,
        long scratchBase,
        long scratchAddress,
        long scratchBufferSize,
        long requiredScratchSize,
        long scratchAlignment,
        long storageSize,
        long handle,
        boolean update
    ) {
        if (inputAddress == 0L || inputBufferSize <= 0L) {
            throw new IllegalStateException("AS input buffer has no valid device address or storage");
        }
        if (primitiveCount <= 0) {
            throw new IllegalStateException("AS build has no primitives: " + primitiveCount);
        }
        long inputAlignment = topLevel ? 16L : 4L;
        if ((inputAddress & (inputAlignment - 1L)) != 0L) {
            throw new IllegalStateException(
                "AS input address is misaligned: address=0x" + Long.toUnsignedString(inputAddress, 16)
                    + ", alignment=" + inputAlignment);
        }
        long requiredInputBytes;
        try {
            requiredInputBytes = topLevel
                ? Math.multiplyExact((long)primitiveCount, (long)VkAccelerationStructureInstanceKHR.SIZEOF)
                : Math.multiplyExact(Math.multiplyExact((long)primitiveCount, 3L), 3L * Float.BYTES);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("AS input byte count overflow", overflow);
        }
        if (requiredInputBytes > inputBufferSize) {
            throw new IllegalStateException(
                "AS input range exceeds its buffer: required=" + requiredInputBytes
                    + ", size=" + inputBufferSize + ", primitives=" + primitiveCount);
        }
        if (scratchAlignment <= 0L || (scratchAlignment & (scratchAlignment - 1L)) != 0L) {
            throw new IllegalStateException("AS scratch alignment is not a power of two: " + scratchAlignment);
        }
        if (scratchBase == 0L || scratchAddress == 0L || scratchBufferSize <= 0L
            || requiredScratchSize <= 0L) {
            throw new IllegalStateException("AS scratch buffer is unavailable");
        }
        if ((scratchAddress & (scratchAlignment - 1L)) != 0L) {
            throw new IllegalStateException(
                "AS scratch address is misaligned: address=0x" + Long.toUnsignedString(scratchAddress, 16)
                    + ", alignment=" + scratchAlignment);
        }
        long scratchOffset = scratchAddress - scratchBase;
        if (scratchOffset < 0L || scratchOffset > scratchBufferSize
            || requiredScratchSize > scratchBufferSize - scratchOffset) {
            throw new IllegalStateException(
                "AS scratch range exceeds its buffer: base=0x" + Long.toUnsignedString(scratchBase, 16)
                    + ", address=0x" + Long.toUnsignedString(scratchAddress, 16)
                    + ", required=" + requiredScratchSize + ", size=" + scratchBufferSize);
        }
        if (storageSize <= 0L || handle == VK10.VK_NULL_HANDLE) {
            throw new IllegalStateException("AS destination has no valid storage or handle");
        }
        if (update && handle == VK10.VK_NULL_HANDLE) {
            throw new IllegalStateException("AS UPDATE requires a source acceleration structure");
        }
    }

    static long scratchBufferSize(long requiredScratchSize, long alignment) {
        if (requiredScratchSize <= 0L || alignment <= 0L) {
            throw new IllegalArgumentException("Acceleration-structure scratch size and alignment must be positive");
        }
        // The base address of a VkBuffer is not required to satisfy the AS scratch alignment.
        // Reserve enough headroom to move the address forward to the next aligned byte.
        return Math.addExact(requiredScratchSize, alignment - 1L);
    }
}

final class AccelerationStructure implements AutoCloseable {
    final VulkanDevice device;
    final NativeBuffer storage;
    final long handle;
    final long deviceAddress;
    final long scratchSize;
    NativeBuffer inputBuffer;
    final boolean topLevel;
    final int primitiveCount;
    boolean closed;

    private AccelerationStructure(
        VulkanDevice device,
        NativeBuffer storage,
        long handle,
        long deviceAddress,
        long scratchSize,
        NativeBuffer inputBuffer,
        boolean topLevel,
        int primitiveCount
    ) {
        this.device = device;
        this.storage = storage;
        this.handle = handle;
        this.deviceAddress = deviceAddress;
        this.scratchSize = scratchSize;
        this.inputBuffer = inputBuffer;
        this.topLevel = topLevel;
        this.primitiveCount = primitiveCount;
    }

    static AccelerationStructure createBottomLevel(VulkanDevice device, NativeBuffer vertices, int primitiveCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR
                .calloc(stack)
                .sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3L * Float.BYTES)
                .maxVertex(primitiveCount * 3 - 1)
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
            triangles.vertexData(address -> address.deviceAddress(vertices.deviceAddress()));
            VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack)
                .sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(0);
            geometry.geometry().triangles(triangles);
            return create(
                device,
                geometry,
                primitiveCount,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                vertices,
                false
            );
        }
    }

    static AccelerationStructure createTopLevel(VulkanDevice device, NativeBuffer instances, int instanceCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryInstancesDataKHR data = VkAccelerationStructureGeometryInstancesDataKHR
                .calloc(stack).sType$Default().arrayOfPointers(false);
            data.data(address -> address.deviceAddress(instances.deviceAddress()));
            VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack)
                .sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(0);
            geometry.geometry().instances(data);
            return create(
                device,
                geometry,
                instanceCount,
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                instances,
                true
            );
        }
    }

    private static AccelerationStructure create(
        VulkanDevice device,
        VkAccelerationStructureGeometryKHR geometry,
        int primitiveCount,
        int type,
        NativeBuffer inputBuffer,
        boolean topLevel
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildGeometryInfoKHR sizeInfo = VkAccelerationStructureBuildGeometryInfoKHR
                .calloc(stack).sType$Default().type(type).flags(
                    KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(1).pGeometries(VkAccelerationStructureGeometryKHR.calloc(1, stack));
            sizeInfo.pGeometries().get(0).set(geometry);
            VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                device.vkDevice(),
                KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                sizeInfo,
                stack.ints(primitiveCount),
                sizes
            );
            long alignment = Math.max(1L, RayTracingSupport.queryLimits(device).minScratchAlignment());
            NativeBuffer storage = NativeBuffer.create(
                device,
                VulkanAccelerationResources.alignUp(sizes.accelerationStructureSize(), alignment),
                KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                false
            );
            long accelerationStructure = VK10.VK_NULL_HANDLE;
            try {
                VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType$Default().buffer(storage.buffer).offset(0).size(sizes.accelerationStructureSize()).type(type);
                LongBuffer handle = stack.callocLong(1);
                VulkanUtils.crashIfFailure(
                    device,
                    KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device.vkDevice(), createInfo, null, handle),
                    "Failed to create acceleration structure"
                );
                accelerationStructure = handle.get(0);
                VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR
                    .calloc(stack).sType$Default().accelerationStructure(accelerationStructure);
                long deviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device.vkDevice(), addressInfo);
                return new AccelerationStructure(
                    device,
                    storage,
                    accelerationStructure,
                    deviceAddress,
                    Math.max(sizes.buildScratchSize(), sizes.updateScratchSize()),
                    inputBuffer,
                    topLevel,
                    primitiveCount
                );
            } catch (Throwable throwable) {
                if (accelerationStructure != VK10.VK_NULL_HANDLE) {
                    KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                        device.vkDevice(), accelerationStructure, null);
                }
                storage.close();
                throw throwable;
            }
        }
    }

    VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(MemoryStack stack, long scratchAddress, boolean update) {
        return buildInfo(stack, inputBuffer.deviceAddress(), scratchAddress, update);
    }

    VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(
        MemoryStack stack, long inputAddress, long scratchAddress, boolean update) {
        return buildInfo(stack, handle, inputAddress, scratchAddress, primitiveCount, topLevel, update);
    }

    // Pure command encoding is also exercised without a Vulkan device by the contract tests.
    static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(MemoryStack stack, long handle,
            long inputAddress, long scratchAddress, int primitiveCount, boolean topLevel, boolean update) {
        VkAccelerationStructureGeometryKHR.Buffer geometries = VkAccelerationStructureGeometryKHR.calloc(1, stack);
        if (topLevel) {
            VkAccelerationStructureGeometryInstancesDataKHR instances = VkAccelerationStructureGeometryInstancesDataKHR
                .calloc(stack).sType$Default().arrayOfPointers(false);
            instances.data(address -> address.deviceAddress(inputAddress));
            geometries.get(0).sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(0)
                .geometry().instances(instances);
        } else {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR
                .calloc(stack)
                .sType$Default()
                .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                .vertexStride(3L * Float.BYTES)
                .maxVertex(primitiveCount * 3 - 1)
                .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
            triangles.vertexData(address -> address.deviceAddress(inputAddress));
            geometries.get(0).sType$Default()
                .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                .flags(0)
                .geometry().triangles(triangles);
        }

        VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        info.get(0).sType$Default()
            .type(topLevel
                ? KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                : KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
            .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
            .mode(update ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
            .srcAccelerationStructure(update ? handle : VK10.VK_NULL_HANDLE)
            .dstAccelerationStructure(handle)
            .geometryCount(1)
            .pGeometries(geometries)
            .scratchData(address -> address.deviceAddress(scratchAddress));
        return info;
    }

    void rebindInputBuffer(NativeBuffer inputBuffer) {
        if (!this.topLevel) {
            throw new IllegalStateException("Only TLAS input buffers can be rebound");
        }
        this.inputBuffer = inputBuffer;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
        } finally {
            storage.close();
        }
    }
}

final class NativeBuffer implements AutoCloseable {
    final VulkanDevice device;
    final long buffer;
    final long allocation;
    final long size;
    boolean closed;

    private NativeBuffer(VulkanDevice device, long buffer, long allocation, long size) {
        this.device = device;
        this.buffer = buffer;
        this.allocation = allocation;
        this.size = size;
    }

    static NativeBuffer create(VulkanDevice device, long size, int usage, boolean hostVisible) {
        long alignment = (usage & KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR) != 0
            ? 16L : 1L;
        return createAligned(device, size, usage, hostVisible, alignment);
    }

    static NativeBuffer createAligned(VulkanDevice device, long size, int usage, boolean hostVisible, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Buffer alignment must be a positive power of two");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo bufferInfo = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                .sType$Default().size(size).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            if (hostVisible) {
                allocationInfo.requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
            }
            LongBuffer bufferHandle = stack.callocLong(1);
            PointerBuffer allocationHandle = stack.callocPointer(1);
            VulkanUtils.crashIfFailure(
                device,
                // Buffer memory requirements alone may not guarantee the device-address
                // alignment required by TLAS input or shader binding tables.
                Vma.vmaCreateBufferWithAlignment(device.vma(), bufferInfo, allocationInfo,
                    alignment, bufferHandle, allocationHandle, null),
                "Failed to create ray-tracing buffer"
            );
            long buffer = bufferHandle.get(0);
            long allocation = allocationHandle.get(0);
            try {
                return new NativeBuffer(device, buffer, allocation, size);
            } catch (Throwable throwable) {
                Vma.vmaDestroyBuffer(device.vma(), buffer, allocation);
                throw throwable;
            }
        }
    }

    Mapped map() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.callocPointer(1);
            VulkanUtils.crashIfFailure(device, Vma.vmaMapMemory(device.vma(), allocation, mapped), "Failed to map ray-tracing buffer");
            try {
                return new Mapped(MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(size)));
            } catch (Throwable throwable) {
                Vma.vmaUnmapMemory(device.vma(), allocation);
                throw throwable;
            }
        }
    }

    final class Mapped implements AutoCloseable {
        private final ByteBuffer buffer;
        private final List<long[]> flushRanges = new ArrayList<>();
        private boolean flushWhole = true;
        boolean closed;

        private Mapped(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        ByteBuffer buffer() {
            return this.buffer;
        }

        /** Opt into explicit coverage, including a legitimate no-write/no-flush mapping. */
        void flushOnlyWrittenRanges() {
            if (closed) throw new IllegalStateException("Mapped buffer is closed");
            this.flushWhole = false;
        }

        /** Limit an incremental write's visibility flush to the touched byte ranges. */
        void flushOnlyRange(long offset, long length) {
            if (closed) throw new IllegalStateException("Mapped buffer is closed");
            if (offset < 0L || length <= 0L || length > size || offset > size - length) {
                throw new IllegalArgumentException("Mapped buffer flush range is out of bounds");
            }
            this.flushWhole = false;
            this.flushRanges.add(new long[] {offset, length});
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (this.flushWhole) {
                    Vma.vmaFlushAllocation(device.vma(), allocation, 0L, VK10.VK_WHOLE_SIZE);
                } else {
                    coalesceFlushRanges(this.flushRanges);
                    for (long[] range : this.flushRanges) {
                        Vma.vmaFlushAllocation(device.vma(), allocation, range[0], range[1]);
                    }
                }
            } finally {
                Vma.vmaUnmapMemory(device.vma(), allocation);
            }
        }
    }

    /** Union of validated byte intervals; preserve gaps and let VMA handle atom alignment. */
    static void coalesceFlushRanges(java.util.List<long[]> ranges) {
        ranges.sort(java.util.Comparator.comparingLong(range -> range[0]));
        int count = 0;
        for (int index = 0; index < ranges.size(); index++) {
            long[] next = ranges.get(index);
            if (count > 0) {
                long[] previous = ranges.get(count - 1);
                long end = Math.addExact(previous[0], previous[1]);
                if (next[0] <= end) {
                    previous[1] = Math.max(end, Math.addExact(next[0], next[1])) - previous[0];
                    continue;
                }
            }
            ranges.set(count++, next);
        }
        ranges.subList(count, ranges.size()).clear();
    }

    long deviceAddress() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var addressInfo = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
            return VK12.vkGetBufferDeviceAddress(device.vkDevice(), addressInfo);
        }
    }

    long alignedDeviceAddress(long alignment) {
        long baseAddress = deviceAddress();
        long alignedAddress = VulkanAccelerationResources.alignDeviceAddress(baseAddress, alignment);
        long offset = alignedAddress - baseAddress;
        if (offset < 0L || offset >= size) {
            throw new IllegalStateException(
                "AS scratch alignment does not fit in the allocated buffer: alignment=" + alignment
                    + ", offset=" + offset + ", size=" + size);
        }
        return alignedAddress;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Vma.vmaDestroyBuffer(device.vma(), buffer, allocation);
    }
}
