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
    private int readBank, epoch, workMode, recordedCopyBytes, cursor, trainingEpoch;
    private int budget, lightEpoch;
    private RayTracingScene.SceneGeometry scene;
    private Object medium;
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

    void prepare(long descriptorSet, RayTracingScene.SceneGeometry geometry, Object atmosphere, float skyOpacity, ByteBuffer camera, int mode) {
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
        long signature = PersistentRtPolicy.lightSignature(camera)
            ^ ((long)Math.round(skyOpacity * 100) * 0x100000001b3L)
            ^ (Double.doubleToLongBits(config.atmosphereAltitudeOffsetMeters.get()) * 0x100000001b3L);
        long now = System.nanoTime();
        if (!PersistentRtPolicy.sameStaticScene(scene, geometry) || medium != atmosphere || light != signature || requested != lastEnabled) {
            lightEpoch++;
            clearPending = true; scene = geometry; medium = atmosphere; light = signature; nextRefresh = 0;
        }
        scene = geometry;
        lastEnabled = requested; enabled = requested; workMode = mode;
        if (++epoch == 0) { epoch = 1; clearPending = true; }
        budget = config.persistentWorldTrainingBudget.get();
        refresh = requested && (clearPending || (budget > 0 && now >= nextRefresh));
        if (clearPending) cursor = 0;
        if (refresh) trainingEpoch++;
        if (refresh) nextRefresh = now + config.persistentRtUpdateIntervalMs.get() * 1_000_000L;
        try (var mapped = buffer.map()) {
            ByteBuffer b = mapped.buffer(); mapped.flushOnlyRange(0, 64);
            b.putInt(0, enabled ? 1 : 0).putInt(4, PersistentRtPolicy.HEADER_WORDS + readBank * PersistentRtPolicy.SLOTS * PersistentRtPolicy.ROW_WORDS)
                .putInt(8, PersistentRtPolicy.HEADER_WORDS + (1 - readBank) * PersistentRtPolicy.SLOTS * PersistentRtPolicy.ROW_WORDS)
                .putInt(12, epoch).putInt(16, refresh ? 1 : 0).putInt(20, (int)(now / 1_000_000L))
                .putInt(24, config.persistentWorldMaxAgeMs.get()).putInt(28, config.persistentRtMinimumSamples.get())
                .putInt(32, PersistentRtPolicy.SLOTS - 1).putInt(36, mode)
                .putInt(40, budget).putInt(44, cursor).putInt(48, config.persistentRtStatistics.get() ? 1 : 0)
                .putInt(52, allocated ? PersistentRtPolicy.JOB_BASE_WORDS : 0).putInt(56, trainingEpoch);
            // Jobs contain stable absolute-world seeds, independent of the current camera.
            b.putFloat(128, (float)geometry.originX).putFloat(132, (float)geometry.originY)
                .putFloat(136, (float)geometry.originZ).putInt(140, lightEpoch)
                .putFloat(144, config.atmosphereAltitudeOffsetMeters.get())
                .putInt(156, allocated ? PersistentRtPolicy.JOB_INDEX_BASE_WORDS : 0);
            if (clearPending) b.putInt(152, 0);
            mapped.flushOnlyRange(128, 32);
        }
    }

    void recordBeforeTrace(VkCommandBuffer cmd, MemoryStack stack) {
        recordedCopyBytes = 0;
        if (!enabled && !RayTracingClientConfig.INSTANCE.persistentRtStatistics.get()) return;
        var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_TRANSFER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_HOST_BIT | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
        VK10.vkCmdFillBuffer(cmd, buffer.buffer, 64, 64, 0);
        if (enabled && refresh) {
            if (clearPending) VK10.vkCmdFillBuffer(cmd, buffer.buffer, 256, buffer.size - 256, 0);
            else {
                var region = VkBufferCopy.calloc(1, stack).srcOffset(256L + (long)readBank * PersistentRtPolicy.BANK_BYTES)
                    .dstOffset(256L + (long)(1 - readBank) * PersistentRtPolicy.BANK_BYTES).size(PersistentRtPolicy.BANK_BYTES);
                VK10.vkCmdCopyBuffer(cmd, buffer.buffer, buffer.buffer, region);
                recordedCopyBytes = PersistentRtPolicy.BANK_BYTES;
            }
        }
        barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT | VK10.VK_ACCESS_HOST_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT | VK10.VK_PIPELINE_STAGE_HOST_BIT,
            KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, barrier, null, null);
    }
    int trainingCount() { return enabled && refresh ? budget : 0; }
    void submitted() { if (enabled && refresh) {
        readBank = 1 - readBank; clearPending = false; cursor += budget;
    } }
    void logRetired(int frame) {
        if (frame % 120 != 0 || !RayTracingClientConfig.INSTANCE.persistentRtStatistics.get()) return;
        try (var mapped = buffer.map()) {
            Vma.vmaInvalidateAllocation(device.vma(), buffer.allocation, 64, 96);
            mapped.flushOnlyWrittenRanges(); var b = mapped.buffer();
            com.mojang.logging.LogUtils.getLogger().info("RTest persistent_rt frame={} enabled={} mode={} refresh={} sampled_queries={} sampled_hits={} sampled_primary={} sampled_secondary={} sampled_writes={} sampled_fallback={} sampled_world_excluded={} sampled_claim_losses={} sampled_budget_denied={} sampled_world_segments={} active_jobs={} exact_reservation_attempts={} exact_admitted={} training_budget={} snapshot_copy_bytes={} allocated_bytes={}",
                frame, enabled, workMode, refresh, b.getInt(64), b.getInt(68), b.getInt(80), b.getInt(84), b.getInt(88), b.getInt(72), b.getInt(76), b.getInt(92), b.getInt(108),
                b.getInt(100), b.getInt(152), b.getInt(120), Math.min(b.getInt(120), b.getInt(40)), b.getInt(40), recordedCopyBytes, buffer.size);
        }
    }
    @Override public void close() { buffer.close(); }
}
