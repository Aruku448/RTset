package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Camera-independent corner radiance atlas. Prepare after previous fence; publish only on submission. */
final class WorldSurfaceRadiance implements AutoCloseable {
    static final String GLSL;
    static {try(var in=WorldSurfaceRadiance.class.getResourceAsStream("/rtest/shaders/world_surface_radiance.glsl")) {
        GLSL=new String(java.util.Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
    }catch(java.io.IOException e){throw new ExceptionInInitializerError(e);}}
    final VulkanDevice device;
    NativeBuffer buffer,positions;
    private int capacity,staticVertices,dynamicVertices,allocatedStaticVertices;
    private boolean geometryUpload;
    private long lastPositions;
    private int vertices,generation,bank,cursor,count;
    private boolean reset;
    WorldSurfaceRadiance(VulkanDevice device){this.device=device;buffer=allocate(64);positions=allocatePositions(16);}
    private NativeBuffer allocate(long bytes){return NativeBuffer.create(device,bytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,true);}
    private NativeBuffer allocatePositions(long bytes){return NativeBuffer.create(device,bytes,VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,false);}
    void prepare(WorldRasterDisplay raster,int nextGeneration,boolean due,int budget){
        staticVertices=raster.surfaceVertexCount();dynamicVertices=due?raster.prepareSurfaceLayout():dynamicVertices;
        int nextVertices=Math.addExact(staticVertices,dynamicVertices);
        if(nextVertices>capacity||staticVertices!=allocatedStaticVertices){
            int nextCapacity=Math.addExact(staticVertices,Math.max(65536,dynamicVertices));
            NativeBuffer next=allocate(64L+Math.max(1L,nextCapacity)*64),nextPositions;
            try{nextPositions=allocatePositions(Math.max(16L,(long)nextCapacity*16));}catch(RuntimeException|Error failure){next.close();throw failure;}
            buffer.close();positions.close();buffer=next;positions=nextPositions;capacity=nextCapacity;allocatedStaticVertices=staticVertices;reset=true;geometryUpload=true;
        }
        vertices=nextVertices;
        if(lastPositions!=raster.surfacePositions()){lastPositions=raster.surfacePositions();geometryUpload=true;reset=true;}
        if(nextGeneration!=generation){generation=nextGeneration;reset=true;cursor=0;bank=0;}
        int staticWork=due?Math.min(staticVertices,Math.max(0,budget)):0;
        count=staticWork+(due&&budget>0?dynamicVertices:0);
        try(var mapped=buffer.map()){mapped.flushOnlyRange(0,64);var b=mapped.buffer().order(ByteOrder.nativeOrder());
            b.putInt(0,vertices).putInt(4,generation).putInt(8,16+bank*capacity*8).putInt(12,16+(1-bank)*capacity*8)
                .putInt(16,cursor).putInt(20,vertices>0?1:0).putInt(24,staticVertices).putInt(28,due&&budget>0?dynamicVertices:0).putInt(32,raster.dynamicLightingKey()&0x1ffffff);}
    }
    void bind(long set){try(var stack=MemoryStack.stackPush()){
        var writes=VkWriteDescriptorSet.calloc(2,stack);
        for(int i=0;i<2;i++)writes.get(i).sType$Default().dstSet(set).dstBinding(43+i).descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(i==0?buffer.buffer:positions.buffer).range(i==0?buffer.size:positions.size));
        VK10.vkUpdateDescriptorSets(device.vkDevice(),writes,null);
    }}
    void recordBeforeUpdate(VkCommandBuffer cmd,MemoryStack stack,WorldRasterDisplay raster){
        if(!reset&&count==0)return;
        var barrier=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT|VK10.VK_ACCESS_HOST_WRITE_BIT)
            .dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT|VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd,VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,barrier,null,null);
        if(reset)VK10.vkCmdFillBuffer(cmd,buffer.buffer,64,buffer.size-64,0);
        else {long bytes=(long)capacity*32;var copy=VkBufferCopy.calloc(1,stack).srcOffset(64+bank*bytes).dstOffset(64+(1-bank)*bytes).size(bytes);VK10.vkCmdCopyBuffer(cmd,buffer.buffer,buffer.buffer,copy);}
        if(count>0&&dynamicVertices>0){
            barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT);
            VK10.vkCmdPipelineBarrier(cmd,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,0,barrier,null,null);
            VK10.vkCmdFillBuffer(cmd,buffer.buffer,64L+(long)(1-bank)*capacity*32+(long)staticVertices*32,(long)dynamicVertices*32,0);
        }
        if(geometryUpload && staticVertices>0){var copy=VkBufferCopy.calloc(1,stack).size((long)staticVertices*16);VK10.vkCmdCopyBuffer(cmd,raster.surfacePositions(),positions.buffer,copy);geometryUpload=false;}
        if(count>0)raster.recordSurfacePositions(cmd,stack,positions.buffer,staticVertices);
        barrier.srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT|VK10.VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT|VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd,VK10.VK_PIPELINE_STAGE_TRANSFER_BIT|VK10.VK_PIPELINE_STAGE_HOST_BIT,
            KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,barrier,null,null);
    }
    int count(){return count;}
    void submitted(){reset=false;if(count>0){bank=1-bank;cursor=staticVertices>0?(cursor+Math.max(0,count-dynamicVertices))%staticVertices:0;}}
    @Override public void close(){buffer.close();positions.close();}
}
