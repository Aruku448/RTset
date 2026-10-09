package com.rtest.client.fsr;

import java.lang.foreign.*;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Per-resolution DLSS resource owner. Closed only after the RT pass has retired its submissions. */
final class DlssReconstruction implements AutoCloseable {
    private static final int USAGE = VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private final RtestVulkanContext context;
    private final RtestUpscalerMode mode;
    private final List<RtestVulkanImage> owned = new ArrayList<>();
    private final RtestVulkanImage[] images = new RtestVulkanImage[8];
    private MemorySegment handle = MemorySegment.NULL;
    private DlssImagePass prepare, finish;
    private boolean initialized, closed;

    DlssReconstruction(RtestVulkanContext context, RtestUpscalerMode mode, RtestFsrQualityMode quality,
                       RtestVulkanImage scene, RtestVulkanImage motion, RtestVulkanImage depth,
                       NrdDenoiser guides, RtestVulkanImage destination) {
        this.context = context; this.mode = mode;
        try {
            int rw=scene.width(), rh=scene.height();
            images[0]=image(rw,rh,VK12.VK_FORMAT_R16G16B16A16_SFLOAT,"color");
            images[1]=mode==RtestUpscalerMode.DLSS_RR ? guides.viewZ() : depth;
            images[2]=image(rw,rh,VK12.VK_FORMAT_R16G16_SFLOAT,"motion");
            images[3]=image(rw,rh,VK12.VK_FORMAT_R16G16B16A16_SFLOAT,"normal roughness");
            images[4]=image(rw,rh,VK12.VK_FORMAT_R16G16B16A16_SFLOAT,"albedo");
            images[5]=image(rw,rh,VK12.VK_FORMAT_R16G16B16A16_SFLOAT,"specular albedo");
            images[6]=image(destination.width(),destination.height(),VK12.VK_FORMAT_R16G16B16A16_SFLOAT,"output");
            images[7]=image(rw,rh,VK12.VK_FORMAT_R32_SFLOAT,"specular hit distance");
            prepare=new DlssImagePass(context,"dlss_prepare",scene,motion,guides.material(),guides.specularMaterial(),
                guides.primaryPosition(),guides.noisySpecular(),images[0],images[2],images[3],images[4],images[5],images[7]);
            finish=new DlssImagePass(context,"dlss_finish",images[6],destination);
            try(Arena arena=Arena.ofConfined()) {
                MemorySegment out=arena.allocate(ValueLayout.ADDRESS);
                DlssRuntime.check("create",DlssRuntime.invoke("create",mode.nativeMode,destination.width(),destination.height(),
                    RtestUpscalerMode.quality(quality),rw,rh,out));
                handle=out.get(ValueLayout.ADDRESS,0);
            }
        } catch(Throwable failure) { try { close(); } catch(Throwable cleanup) { failure.addSuppressed(cleanup); } throw failure; }
    }
    RtestUpscalerMode mode() { return mode; }
    private RtestVulkanImage image(int w,int h,int format,String name) {
        RtestVulkanImage image=context.createImage2D(w,h,format,USAGE,"RTest DLSS "+name); owned.add(image); return image;
    }
    void record(VkCommandBuffer command,RtestFsr3Upscaler.FrameToken token) {
        if(closed) throw new IllegalStateException("DLSS owner closed");
        if(!initialized) {
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var barriers=VkImageMemoryBarrier2.calloc(owned.size(),stack);
                for(int i=0;i<owned.size();i++) barriers.get(i).sType$Default().srcStageMask(VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .dstStageMask(VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT).dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT|VK12.VK_ACCESS_SHADER_WRITE_BIT)
                    .oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED).image(owned.get(i).image())
                    .subresourceRange(r->r.aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
                KHRSynchronization2.vkCmdPipelineBarrier2KHR(command,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            }
            initialized=true;
        }
        barrier(command);
        prepare.record(command,images[0].width(),images[0].height(),mode==RtestUpscalerMode.DLSS_RR?1:0);
        barrier(command);
        try(Arena arena=Arena.ofConfined()) {
            MemorySegment frame=arena.allocate(856,8); ByteBuffer b=frame.asByteBuffer().order(ByteOrder.nativeOrder());
            b.putLong(0,command.address()); b.putInt(8,token.frameIndex()); b.putInt(12,token.reset()?1:0);
            // RayGen offsets the sampled pixel; reconstruction uses the opposite projection offset.
            b.putFloat(16,-token.jitter().x()); b.putFloat(20,-token.jitter().y());
            RtestFsrCamera camera=token.camera(); RtestFsrCamera previous=token.previousCamera();
            b.putFloat(24,0.05f); b.putFloat(28,65504f);
            b.putFloat(32,(float)(2*Math.atan(1/Math.abs(camera.projectionM11()))));
            b.putFloat(36,(float)images[6].width()/images[6].height());
            vector(b,52,camera.upX(),camera.upY(),camera.upZ());
            vector(b,64,camera.rightX(),camera.rightY(),camera.rightZ());
            vector(b,76,camera.forwardX(),camera.forwardY(),camera.forwardZ());
            Matrix4f view=camera.viewRotation()==null?new Matrix4f():new Matrix4f(camera.viewRotation());
            Matrix4f projection=camera.projection()==null?new Matrix4f().setPerspective(
                b.getFloat(32),b.getFloat(36),0.05f,65504f,true):new Matrix4f(camera.projection());
            Matrix4f clipToPrevious=new Matrix4f();
            if(!token.reset() && previous!=null && previous.projection()!=null && previous.viewRotation()!=null) {
                clipToPrevious.set(previous.projection()).mul(previous.viewRotation())
                    .translate((float)(camera.x()-previous.x()),(float)(camera.y()-previous.y()),(float)(camera.z()-previous.z()))
                    .mul(new Matrix4f(view).invert()).mul(new Matrix4f(projection).invert());
            }
            matrix(b,88,view); matrix(b,152,new Matrix4f(view).invert());
            matrix(b,216,projection); matrix(b,280,new Matrix4f(projection).invert());
            matrix(b,344,clipToPrevious); matrix(b,408,new Matrix4f(clipToPrevious).invert());
            for(int i=0;i<8;i++) {
                RtestVulkanImage image=images[i]; int offset=472+i*48;
                b.putLong(offset,image.image()); b.putLong(offset+8,image.view()); b.putLong(offset+16,image.memory());
                b.putInt(offset+24,image.width()); b.putInt(offset+28,image.height()); b.putInt(offset+32,image.format());
                b.putInt(offset+36,VK12.VK_IMAGE_LAYOUT_GENERAL); b.putInt(offset+40,USAGE);
            }
            // A failed SDK recording is never submitted or consumed as valid reconstruction.
            DlssRuntime.check("evaluate",DlssRuntime.invoke("evaluate",handle,frame));
        }
        barrier(command);
        finish.record(command,images[6].width(),images[6].height(),0);
        barrier(command);
    }
    private static void vector(ByteBuffer b,int offset,float x,float y,float z) {
        b.putFloat(offset,x); b.putFloat(offset+4,y); b.putFloat(offset+8,z);
    }
    // JOML column-major storage is the transposed row-vector matrix expected by Streamline.
    private static void matrix(ByteBuffer b,int offset,Matrix4f matrix) {
        float[] data=new float[16]; matrix.get(data); for(int i=0;i<16;i++) b.putFloat(offset+i*4,data[i]);
    }
    private static void barrier(VkCommandBuffer command) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var barrier=VkMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                .srcAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT|VK12.VK_ACCESS_MEMORY_WRITE_BIT)
                .dstStageMask(VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT).dstAccessMask(VK12.VK_ACCESS_MEMORY_READ_BIT|VK12.VK_ACCESS_MEMORY_WRITE_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(command,VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }
    @Override public void close() {
        if(closed) return;
        if(handle.address()!=0) { DlssRuntime.check("retire",DlssRuntime.invoke("destroy",handle)); handle=MemorySegment.NULL; }
        closed=true;
        if(finish!=null) finish.close(); if(prepare!=null) prepare.close();
        for(int i=owned.size()-1;i>=0;i--) owned.get(i).close();
        owned.clear();
    }
}
