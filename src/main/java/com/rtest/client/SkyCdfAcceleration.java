package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/** Static, bounded AS owner, built once per skybox and destroyed after all referencing passes. */
final class SkyCdfAcceleration implements AutoCloseable {
    final NativeBuffer[] vertices = new NativeBuffer[6];
    final AccelerationStructure[] blases = new AccelerationStructure[6];
    NativeBuffer instances, metadata;
    AccelerationStructure tlas;

    static SkyCdfAcceleration create(VulkanDevice device, SkyCdfGeometry geometry) {
        SkyCdfAcceleration owner = new SkyCdfAcceleration();
        VulkanCommandEncoder encoder = null;
        NativeBuffer scratch = null;
        boolean success = false;
        try {
            int usage = VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            owner.metadata = NativeBuffer.create(device, SkyCdfGeometry.METADATA_BYTES,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            try (NativeBuffer.Mapped m = owner.metadata.map()) { geometry.writeBounds(m.buffer()); }
            long maxScratch = 0;
            for (int axis = 0; axis < 6; axis++) {
                float[] data = geometry.vertices[axis];
                owner.vertices[axis] = NativeBuffer.create(device, (long)data.length * 4, usage, true);
                try (NativeBuffer.Mapped m = owner.vertices[axis].map()) {
                    for (float f : data) m.buffer().putFloat(f);
                }
                owner.blases[axis] = AccelerationStructure.createBottomLevel(device, owner.vertices[axis], data.length / 9);
                maxScratch = Math.max(maxScratch, owner.blases[axis].scratchSize);
            }
            owner.instances = NativeBuffer.create(device, 6L * VkAccelerationStructureInstanceKHR.SIZEOF, usage, true);
            try (NativeBuffer.Mapped m = owner.instances.map()) {
                for (int axis = 0; axis < 6; axis++) {
                    var instance = VkAccelerationStructureInstanceKHR.create(
                        MemoryUtil.memAddress(m.buffer()) + (long)axis * VkAccelerationStructureInstanceKHR.SIZEOF);
                    instance.transform(t -> {
                        for (int i = 0; i < 12; i++) t.matrix().put(i, 0);
                        t.matrix().put(0, 1).put(5, 1).put(10, 1);
                    });
                    instance.instanceCustomIndex(axis * SkyImportanceTable.COUNT).mask(1 << axis)
                        .instanceShaderBindingTableRecordOffset(0)
                        .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(owner.blases[axis].deviceAddress);
                }
            }
            owner.tlas = AccelerationStructure.createTopLevel(device, owner.instances, 6);
            maxScratch = Math.max(maxScratch, owner.tlas.scratchSize);
            long alignment = RayTracingSupport.queryLimits(device).minScratchAlignment();
            scratch = NativeBuffer.create(device, VulkanAccelerationResources.scratchBufferSize(maxScratch, alignment),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
            encoder = new VulkanCommandEncoder(device);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var command = encoder.allocateAndBeginTransientCommandBuffer();
                for (AccelerationStructure blas : owner.blases) record(command, stack, blas, scratch, alignment);
                record(command, stack, owner.tlas, scratch, alignment);
                com.mojang.blaze3d.vulkan.VulkanUtils.crashIfFailure(device,
                    VK10.vkEndCommandBuffer(command), "Failed to end sky CDF build command buffer");
                // allocateAndBeginTransientCommandBuffer is not the encoder's current buffer.
                // It must be explicitly queued before a fence can cover its AS builds.
                encoder.execute(command);
            }
            try (var fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(5_000_000_000L)) throw new IllegalStateException("Sky CDF build timed out");
            }
            long residentBytes = owner.metadata.size + owner.instances.size + owner.tlas.storage.size;
            for (int axis = 0; axis < 6; axis++) residentBytes += owner.vertices[axis].size + owner.blases[axis].storage.size;
            com.mojang.logging.LogUtils.getLogger().info(
                "RTest sky CDF hardware: normalized ribbons, triangles={}, static_resource_bytes={}, scratch_bytes={}",
                12 * SkyImportanceTable.COUNT, residentBytes, scratch.size);
            success = true;
            return owner;
        } finally {
            try { if (encoder != null) encoder.destroy(); }
            finally {
                try { if (scratch != null) scratch.close(); }
                finally { if (!success) owner.close(); }
            }
        }
    }

    private static void record(VkCommandBuffer command, MemoryStack stack, AccelerationStructure as,
                               NativeBuffer scratch, long alignment) {
        long base = scratch.deviceAddress(), address = scratch.alignedDeviceAddress(alignment);
        VulkanAccelerationResources.validateBuildArguments(as.topLevel, as.inputBuffer.deviceAddress(),
            as.inputBuffer.size, as.primitiveCount, base, address, scratch.size, as.scratchSize, alignment,
            as.storage.size, as.handle, false);
        var info = as.buildInfo(stack, address, false);
        var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(stack).primitiveCount(as.primitiveCount);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(command, info, stack.pointers(range.address()));
        var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
            .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
                | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR)
            .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR
                | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        VK10.vkCmdPipelineBarrier(command, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
            KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            0, barrier, null, null);
    }

    @Override public void close() {
        try { if (tlas != null) { tlas.close(); tlas = null; } }
        finally {
            try { if (instances != null) { instances.close(); instances = null; } }
            finally {
                closeBlases(0);
            }
        }
    }

    private void closeBlases(int axis) {
        if (axis == 6) {
            if (metadata != null) { metadata.close(); metadata = null; }
            return;
        }
        try { if (blases[axis] != null) { blases[axis].close(); blases[axis] = null; } }
        finally {
            try { if (vertices[axis] != null) { vertices[axis].close(); vertices[axis] = null; } }
            finally { closeBlases(axis + 1); }
        }
    }
}
