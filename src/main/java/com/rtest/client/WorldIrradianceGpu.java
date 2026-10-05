package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Owned double-bank SH field. Caller prepares only after the previous frame fence.
 * A same-queue world update writes the unpublished bank; submitted publishes it for the NEXT frame.
 * This does not create an asynchronous queue or make RT work free for display latency.
 */
final class WorldIrradianceGpu implements AutoCloseable {
    static final int BINDING=42;
    private final VulkanDevice device;
    NativeBuffer buffer;
    private WorldIrradianceField.Grid grid;
    private boolean reset=true, update;
    private int readBank, cursor, count;
    WorldIrradianceGpu(VulkanDevice device) {
        this.device=device;
        buffer=allocate(64);
        try(var mapped=buffer.map()) { for(int i=0;i<16;i++) mapped.buffer().putInt(i*4,0); }
        catch(RuntimeException|Error failure) { buffer.close();throw failure; }
    }
    private NativeBuffer allocate(long bytes) {
        return NativeBuffer.create(device,bytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,true);
    }
    /** Layout follows loaded scene extents, not camera rotation. Call on geometry publication only. */
    static WorldIrradianceField.Grid sceneGrid(RayTracingScene.SceneGeometry geometry, int generation,
                                               int minimumSamples, int maxAgeMs, float maxTraceDistance) {
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(var section:geometry.sections) {
            for(int i=0;i<section.vertices.length;i+=3) {
                double x=section.originX+section.vertices[i], y=section.originY+section.vertices[i+1], z=section.originZ+section.vertices[i+2];
                minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
                maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
            }
        }
        if(!Double.isFinite(minX)) { minX=geometry.originX;minY=geometry.originY;minZ=geometry.originZ;maxX=minX+8;maxY=minY+8;maxZ=minZ+8; }
        float spacing=(float)Math.max(8,Math.max(Math.max((maxX-minX)/29,(maxZ-minZ)/29),(maxY-minY)/13));
        float x=(float)(Math.floor(minX/spacing)*spacing-spacing),y=(float)(Math.floor(minY/spacing)*spacing-spacing),z=(float)(Math.floor(minZ/spacing)*spacing-spacing);
        int nx=Math.max(2,Math.min(34,(int)Math.ceil((maxX-x)/spacing)+2));
        int ny=Math.max(2,Math.min(18,(int)Math.ceil((maxY-y)/spacing)+2));
        int nz=Math.max(2,Math.min(34,(int)Math.ceil((maxZ-z)/spacing)+2));
        return new WorldIrradianceField.Grid(x,y,z,spacing,nx,ny,nz,generation,minimumSamples,maxAgeMs,maxTraceDistance);
    }
    /** Returns whether allocation/header changed; refresh binding 42 before recording either pipeline. */
    boolean prepare(WorldIrradianceField.Grid requested, boolean enabled, int clockMs, int budget, boolean worldDue) {
        boolean changed=grid==null || !grid.equals(requested);
        if(changed) {
            NativeBuffer candidate=allocate(requested.bytes());
            try(var mapped=candidate.map()) {
                ByteBuffer header=requested.initializeHeader(clockMs);
                mapped.flushOnlyRange(0,64);mapped.buffer().put(0,header,0,64);
            } catch(RuntimeException|Error failure) {candidate.close();throw failure;}
            buffer.close();buffer=candidate;grid=requested;readBank=0;cursor=0;reset=true;
        }
        count=enabled && worldDue ? Math.min(Math.max(0,budget),grid.count()) : 0;
        update=count>0;
        try(var mapped=buffer.map()) {
            mapped.flushOnlyRange(40,24);var b=mapped.buffer();
            b.putInt(40,clockMs).putInt(48,enabled && !reset ? 1 : 0);
            b.putInt(52,WorldIrradianceField.HEADER_WORDS+readBank*grid.count()*WorldIrradianceField.ROW_WORDS);
            b.putInt(56,WorldIrradianceField.HEADER_WORDS+(1-readBank)*grid.count()*WorldIrradianceField.ROW_WORDS);
            b.putInt(60,cursor);
            // Updates must accept observations even while the initial published snapshot is empty.
            if(update) b.putInt(48,1);
        }
        return changed;
    }
    void bind(long descriptorSet) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var info=VkDescriptorBufferInfo.calloc(1,stack).buffer(buffer.buffer).offset(0).range(buffer.size);
            var write=VkWriteDescriptorSet.calloc(1,stack).sType$Default().dstSet(descriptorSet).dstBinding(BINDING)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
            VK10.vkUpdateDescriptorSets(device.vkDevice(),write,null);
        }
    }
    void recordBeforeUpdate(VkCommandBuffer cmd,MemoryStack stack) {
        if(!update && !reset) return;
        var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default()
            .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT|VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT|VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd,VK10.VK_PIPELINE_STAGE_HOST_BIT|VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            |KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,barrier,null,null);
        if(reset) VK10.vkCmdFillBuffer(cmd,buffer.buffer,64,buffer.size-64,0);
        else {
            long bankBytes=(long)grid.count()*WorldIrradianceField.ROW_WORDS*4;
            var copy=VkBufferCopy.calloc(1,stack).srcOffset(64+readBank*bankBytes)
                .dstOffset(64+(1-readBank)*bankBytes).size(bankBytes);
            VK10.vkCmdCopyBuffer(cmd,buffer.buffer,buffer.buffer,copy);
        }
        barrier.srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT|VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd,VK10.VK_PIPELINE_STAGE_HOST_BIT|VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
            KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,barrier,null,null);
    }
    int trainingCount() { return count; }
    int firstIndex() { return cursor; }
    long generation() { return grid==null ? 0 : Integer.toUnsignedLong(grid.generation()); }
    void submitted() {
        if(update) { readBank=1-readBank;cursor=(cursor+count)%grid.count(); }
        reset=false;
    }
    @Override public void close() { buffer.close(); }
}
