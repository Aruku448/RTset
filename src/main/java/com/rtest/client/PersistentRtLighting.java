package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.Vma;

/** Same-queue persistent snapshots; all host mutations run after the previous frame fence. */
final class PersistentRtLighting implements AutoCloseable {
    private final VulkanDevice device;
    NativeBuffer buffer;
    private boolean allocated, enabled, clearPending = true, lastEnabled;
    private int readBank, epoch, workMode;
    private Object scene, medium;
    private long light, nextRefresh;
    private boolean refresh;

    PersistentRtLighting(VulkanDevice device) {
        this.device = device;
        buffer = NativeBuffer.create(device, PersistentRtPolicy.HEADER_WORDS * 4L,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try (var mapped = buffer.map()) {
            for (int i = 0; i < PersistentRtPolicy.HEADER_WORDS; i++) mapped.buffer().putInt(i * 4, 0);
        } catch (RuntimeException | Error failure) { buffer.close(); throw failure; }
    }

    void prepare(long descriptorSet, Object geometry, Object atmosphere, float skyOpacity, ByteBuffer camera, int mode) {
        var config = RayTracingClientConfig.INSTANCE;
        boolean requested = config.persistentRtEnabled.get() && mode == 0;
        if (requested && !allocated) {
            NativeBuffer candidate = NativeBuffer.create(device, PersistentRtPolicy.ALLOCATION_BYTES,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(candidate.buffer).offset(0).range(candidate.size);
                var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(descriptorSet).dstBinding(41)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
                VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
            }
            buffer.close(); buffer = candidate; allocated = true; clearPending = true;
        }
        long signature = PersistentRtPolicy.lightSignature(camera) ^ ((long)Math.round(skyOpacity * 100) * 0x100000001b3L);
        long now = System.nanoTime();
        if (scene != geometry || medium != atmosphere || light != signature || requested != lastEnabled) {
            clearPending = true; scene = geometry; medium = atmosphere; light = signature; nextRefresh = 0;
        }
        lastEnabled = requested; enabled = requested; workMode = mode;
        refresh = requested && (clearPending || now >= nextRefresh);
        if (refresh) nextRefresh = now + config.persistentRtUpdateIntervalMs.get() * 1_000_000L;
        if (++epoch == 0) { epoch = 1; clearPending = true; }
        try (var mapped = buffer.map()) {
            ByteBuffer b = mapped.buffer(); mapped.flushOnlyRange(0, 64);
            b.putInt(0, enabled ? 1 : 0).putInt(4, PersistentRtPolicy.HEADER_WORDS + readBank * PersistentRtPolicy.SLOTS * PersistentRtPolicy.ROW_WORDS)
                .putInt(8, PersistentRtPolicy.HEADER_WORDS + (1 - readBank) * PersistentRtPolicy.SLOTS * PersistentRtPolicy.ROW_WORDS)
                .putInt(12, epoch).putInt(16, refresh ? 1 : 0).putInt(20, (int)(now / 1_000_000L))
                .putInt(24, config.persistentRtMaxAgeMs.get()).putInt(28, config.persistentRtMinimumSamples.get())
                .putInt(32, PersistentRtPolicy.SLOTS - 1).putInt(36, mode).putInt(48, config.persistentRtStatistics.get() ? 1 : 0);
        }
    }

    void recordBeforeTrace(VkCommandBuffer cmd, MemoryStack stack) {
        var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_HOST_BIT | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
        VK10.vkCmdFillBuffer(cmd, buffer.buffer, 64, 64, 0);
        if (enabled) {
            if (clearPending) VK10.vkCmdFillBuffer(cmd, buffer.buffer, 128, buffer.size - 128, 0);
            else {
                var region = VkBufferCopy.calloc(1, stack).srcOffset(128L + (long)readBank * PersistentRtPolicy.BANK_BYTES)
                    .dstOffset(128L + (long)(1 - readBank) * PersistentRtPolicy.BANK_BYTES).size(PersistentRtPolicy.BANK_BYTES);
                VK10.vkCmdCopyBuffer(cmd, buffer.buffer, buffer.buffer, region);
            }
        }
        barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_HOST_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_HOST_BIT,
            KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, barrier, null, null);
    }
    void submitted() { if (enabled) { readBank = 1 - readBank; clearPending = false; } }
    void logRetired(int frame) {
        if (frame % 120 != 0 || !RayTracingClientConfig.INSTANCE.persistentRtStatistics.get()) return;
        try (var mapped = buffer.map()) {
            Vma.vmaInvalidateAllocation(device.vma(), buffer.allocation, 64, 64);
            mapped.flushOnlyWrittenRanges(); var b = mapped.buffer();
            com.mojang.logging.LogUtils.getLogger().info("RTest persistent_rt frame={} enabled={} mode={} refresh={} sampled_queries={} sampled_hits={} sampled_primary={} sampled_secondary={} sampled_writes={} allocated_bytes={}",
                frame, enabled, workMode, refresh, b.getInt(64), b.getInt(68), b.getInt(80), b.getInt(84), b.getInt(88), buffer.size);
        }
    }
    @Override public void close() { buffer.close(); }
}
