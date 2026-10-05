package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.rtest.client.RayTracingScene.SceneGeometry;
import com.rtest.client.fsr.RtestFsrCamera;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Pure graphics display of the latest geometry and a completed irradiance field.
 * Caller retires the previous submission before publishing geometry/camera/descriptors or closing.
 * No BLAS, TLAS, ray queries or trace calls are part of this module.
 */
final class WorldRasterDisplay implements AutoCloseable {
    private static final int[] FORMATS={VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT,
        VK_FORMAT_R16G16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_FORMAT_R32G32B32A32_SFLOAT,VK_FORMAT_D32_SFLOAT};
    private final VulkanDevice device;
    private final VkDevice vk;
    private final int width,height;
    private final Image[] images=new Image[7];
    private NativeBuffer positions,camera,dummy,shadowDummy;
    private long shadowStaticView,shadowDynamicView,shadowSampler,shadowBuffer,shadowBytes;
    private long setLayout,pool,set,layout,renderPass,framebuffer,pipeline,dynamicPool;
    private final java.util.List<DynamicDraw> dynamicDraws=new java.util.ArrayList<>();
    private final java.util.Map<Long,DynamicDraw> dynamicBuffers=new java.util.HashMap<>();
    private final float[] sun={0,1,0,0},sunColor={1,1,1,0};
    record Texture(long view,long sampler) {}
    interface TextureResolver { Texture resolve(net.minecraft.resources.Identifier id); }
    private record DynamicDraw(long identity,NativeBuffer position,NativeBuffer previous,NativeBuffer material,long atlasSet,long textureSet,int[] kinds,int count,Texture texture) {}
    private long materialHandle,materialBytes,fieldHandle,fieldBytes,atlasView,atlasSampler;
    private int vertexCount;
    private SceneGeometry geometry;
    private RtestFsrCamera previousCamera;
    private boolean closed;

    WorldRasterDisplay(VulkanDevice device,int width,int height,long atlasView,long atlasSampler) {
        this.device=device;this.vk=device.vkDevice();this.width=width;this.height=height;
        this.atlasView=atlasView;this.atlasSampler=atlasSampler;
        if(width<1||height<1)throw new IllegalArgumentException("Invalid raster extent");
        try {
            camera=NativeBuffer.create(device,192,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
            dummy=NativeBuffer.create(device,256,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
            try(var mapped=dummy.map()){for(int i=0;i<256;i+=4)mapped.buffer().putInt(i,0);}
            fieldHandle=dummy.buffer;fieldBytes=dummy.size;
            shadowDummy=NativeBuffer.create(device,80,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
            try(var mapped=shadowDummy.map()){for(int i=0;i<80;i+=4)mapped.buffer().putInt(i,0);}
            shadowStaticView=atlasView;shadowDynamicView=atlasView;shadowSampler=atlasSampler;shadowBuffer=shadowDummy.buffer;shadowBytes=80;
            positions=NativeBuffer.create(device,16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
            for(int i=0;i<images.length;i++)images[i]=new Image(FORMATS[i],i==6);
            createPipeline();
        } catch(Throwable error) { close();throw error; }
    }

    void setSunShadow(long staticView,long dynamicView,long sampler,long buffer,long bytes) {
        if (shadowStaticView==staticView && shadowDynamicView==dynamicView && shadowSampler==sampler
                && shadowBuffer==buffer && shadowBytes==bytes) return;
        shadowStaticView=staticView;shadowDynamicView=dynamicView;shadowSampler=sampler;shadowBuffer=buffer;shadowBytes=bytes;
        if(geometry!=null)updateDescriptors();
    }
    WorldRasterSunShadow.Draw staticShadowDraw() {
        return new WorldRasterSunShadow.Draw(positions.buffer,materialHandle,materialBytes,atlasView,atlasSampler,0,vertexCount);
    }
    java.util.List<WorldRasterSunShadow.Draw> dynamicShadowDraws(TextureResolver resolver,DynamicRasterSnapshot.Frame snapshot) {
        // The texture handles are already resolved during publishDynamics; return the same triangle ranges.
        var result=new java.util.ArrayList<WorldRasterSunShadow.Draw>();
        for(var draw:dynamicDraws) {
            int first=0;
            while(first<draw.kinds.length) {
                int kind=draw.kinds[first],end=first+1;
                while(end<draw.kinds.length&&draw.kinds[end]==kind)end++;
                Texture texture=kind==0?new Texture(atlasView,atlasSampler):draw.texture;
                if(texture!=null)result.add(new WorldRasterSunShadow.Draw(draw.position.buffer,draw.material.buffer,draw.material.size,texture.view,texture.sampler,first*3,(end-first)*3));
                first=end;
            }
        }
        return java.util.List.copyOf(result);
    }

    void setIrradianceBuffer(long handle,long bytes) {
        if(fieldHandle==handle && fieldBytes==bytes)return;
        fieldHandle=handle==0?dummy.buffer:handle;fieldBytes=handle==0?dummy.size:bytes;
        if(geometry!=null)updateDescriptors();
    }
    void publishGeometry(SceneGeometry next,long materialHandle,long materialBytes) {
        if(next==geometry&&this.materialHandle==materialHandle&&this.materialBytes==materialBytes)return;
        long vertices=next.sections.isEmpty()?next.vertices.length/3:next.sections.stream().mapToLong(section->(long)section.triangleCount*3).sum();
        NativeBuffer candidate=NativeBuffer.create(device,Math.max(16,Math.multiplyExact(vertices,16)),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
        try(var mapped=candidate.map()) {
            ByteBuffer out=mapped.buffer().order(ByteOrder.nativeOrder());
            if(next.sections.isEmpty()) {
                for(int i=0;i<next.vertices.length;i+=3)out.putFloat(next.vertices[i]).putFloat(next.vertices[i+1]).putFloat(next.vertices[i+2]).putInt(i/9);
            }
            for(var section:next.sections) {
                int base=next.materialLayout.baseTriangle(section);
                for(int i=0;i<section.vertices.length;i+=3) {
                    out.putFloat(section.vertices[i]+(float)(section.originX-next.originX));
                    out.putFloat(section.vertices[i+1]+(float)(section.originY-next.originY));
                    out.putFloat(section.vertices[i+2]+(float)(section.originZ-next.originZ));
                    out.putInt(base+i/9);
                }
            }
        } catch(Throwable error){candidate.close();throw error;}
        positions.close();positions=candidate;vertexCount=Math.toIntExact(vertices);
        this.geometry=next;this.materialHandle=materialHandle;this.materialBytes=materialBytes;
        updateDescriptors();
    }

    void updateLighting(float x,float y,float z,float intensity,float r,float g,float b) {
        sun[0]=x;sun[1]=y;sun[2]=z;sun[3]=Math.max(intensity,0);
        sunColor[0]=r;sunColor[1]=g;sunColor[2]=b;
    }

    /** Entity triangles select their atlas or captured texture independently; held items retain atlas UVs. */
    void publishDynamics(DynamicRasterSnapshot.Frame snapshot,TextureResolver resolver) {
        dynamicDraws.clear();
        check(vkResetDescriptorPool(vk,dynamicPool,0));
        var keep=new java.util.HashSet<Long>();
        if(snapshot!=null && geometry!=null) for(var draw:snapshot.draws())
            if(draw.primaryVisible() && draw.family()!=DynamicEntityGeometry.Family.FIRST_PERSON_ITEM)keep.add(draw.instance().identity());
        var stale=dynamicBuffers.entrySet().iterator();
        while(stale.hasNext()) { var entry=stale.next();if(!keep.contains(entry.getKey())) {closeDynamic(entry.getValue());stale.remove();} }
        if(snapshot==null||geometry==null)return;
        for(var draw:snapshot.draws()) {
            if(!draw.primaryVisible()||draw.family()==DynamicEntityGeometry.Family.FIRST_PERSON_ITEM)continue;
            var texture=draw.texture()==null?null:resolver.resolve(draw.texture());
            int count=draw.triangleCount()*3;
            long identity=draw.instance().identity();
            DynamicDraw cached=dynamicBuffers.get(identity);
            if(cached!=null && cached.count!=count) {closeDynamic(cached);dynamicBuffers.remove(identity);cached=null;}
            boolean reused=cached!=null;
            NativeBuffer current=null,previous=null,material=null;
            try {
                current=reused?cached.position:NativeBuffer.create(device,(long)count*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
                previous=reused?cached.previous:NativeBuffer.create(device,(long)count*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
                material=reused?cached.material:NativeBuffer.create(device,(long)draw.triangleCount()*28*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,true);
                var xyz=draw.positions();var data=draw.materials();int[] kinds=reused?cached.kinds:new int[draw.triangleCount()];
                try(var a=current.map();var b=previous.map();var m=material.map()) {
                    var dst=a.buffer().order(ByteOrder.nativeOrder());var old=b.buffer().order(ByteOrder.nativeOrder());
                    for(int i=0;i<count;i++) {
                        float x=xyz.get(i*3),y=xyz.get(i*3+1),z=xyz.get(i*3+2);
                        putTransformed(dst,draw.instance().currentTransform(),x,y,z,i/3);
                        putTransformed(old,draw.instance().historyReset()?draw.instance().currentTransform():draw.instance().previousTransform(),x,y,z,i/3);
                    }
                    var materialOut=m.buffer().order(ByteOrder.nativeOrder()).asFloatBuffer();materialOut.put(data);
                    for(int i=0;i<kinds.length;i++) {
                        kinds[i]=data.get(i*28+15)>1.5?1:0;
                        transformNormal(materialOut,i*28+4,draw.instance().currentTransform(),data.get(i*28+4),data.get(i*28+5),data.get(i*28+6));
                    }
                }
                long atlasSet=allocateDynamicSet(current,previous,material,new Texture(atlasView,atlasSampler));
                long textureSet=texture==null?0:allocateDynamicSet(current,previous,material,texture);
                var uploaded=new DynamicDraw(identity,current,previous,material,atlasSet,textureSet,kinds,count,texture);
                dynamicDraws.add(uploaded);dynamicBuffers.put(identity,uploaded);
            } catch(Throwable error) { if(!reused) {if(current!=null)current.close();if(previous!=null)previous.close();if(material!=null)material.close();}throw error; }
        }
    }
    private void putTransformed(ByteBuffer out,DynamicInstanceRegistry.Transform t,float x,float y,float z,int triangle) {
        out.putFloat(t.m00()*x+t.m01()*y+t.m02()*z+t.m03()-(float)geometry.originX);
        out.putFloat(t.m10()*x+t.m11()*y+t.m12()*z+t.m13()-(float)geometry.originY);
        out.putFloat(t.m20()*x+t.m21()*y+t.m22()*z+t.m23()-(float)geometry.originZ);
        out.putInt(triangle);
    }
    private long allocateDynamicSet(NativeBuffer current,NativeBuffer previous,NativeBuffer material,Texture texture) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var handle=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(vk,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(dynamicPool).pSetLayouts(stack.longs(setLayout)),handle));
            long result=handle.get(0);writeDescriptors(result,current,previous,material.buffer,material.size,texture.view,texture.sampler);return result;
        }
    }
    private static void closeDynamic(DynamicDraw draw) {draw.position.close();draw.previous.close();draw.material.close();}
    private void retireDynamics() {
        for(var draw:dynamicBuffers.values())closeDynamic(draw);
        dynamicBuffers.clear();dynamicDraws.clear();
        if(dynamicPool!=0)check(vkResetDescriptorPool(vk,dynamicPool,0));
    }
    static void transformNormal(java.nio.FloatBuffer output,int offset,DynamicInstanceRegistry.Transform t,float x,float y,float z) {
        float c00=t.m11()*t.m22()-t.m12()*t.m21(),c01=t.m12()*t.m20()-t.m10()*t.m22(),c02=t.m10()*t.m21()-t.m11()*t.m20();
        float c10=t.m02()*t.m21()-t.m01()*t.m22(),c11=t.m00()*t.m22()-t.m02()*t.m20(),c12=t.m01()*t.m20()-t.m00()*t.m21();
        float c20=t.m01()*t.m12()-t.m02()*t.m11(),c21=t.m02()*t.m10()-t.m00()*t.m12(),c22=t.m00()*t.m11()-t.m01()*t.m10();
        float determinant=t.m00()*c00+t.m01()*c01+t.m02()*c02;
        if(Math.abs(determinant)<1e-12)return;
        float nx=(c00*x+c01*y+c02*z)/determinant,ny=(c10*x+c11*y+c12*z)/determinant,nz=(c20*x+c21*y+c22*z)/determinant;
        float norm=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(norm>1e-12)output.put(offset,nx/norm).put(offset+1,ny/norm).put(offset+2,nz/norm);
    }
    void resetHistory() { previousCamera=null; }

    void updateCamera(RtestFsrCamera latest) { updateCamera(latest,0,0); }
    void updateCamera(RtestFsrCamera latest,float jitterX,float jitterY) {
        if(geometry==null)throw new IllegalStateException("Publish raster geometry first");
        try(var mapped=camera.map()) {
            ByteBuffer out=mapped.buffer().order(ByteOrder.nativeOrder());
            writeProjection(out,0,latest,geometry.originX,geometry.originY,geometry.originZ);
            // Match RT image rows: +camera.up increases image Y. RT rays sample pixel+jitter,
            // so raster geometry moves by -jitter in both image axes.
            for(int column=0;column<4;column++) {
                float w=out.getFloat(column*16+12);
                out.putFloat(column*16,out.getFloat(column*16)-2*jitterX/width*w);
                out.putFloat(column*16+4,out.getFloat(column*16+4)-2*jitterY/height*w);
            }
            writeProjection(out,64,previousCamera==null?latest:previousCamera,geometry.originX,geometry.originY,geometry.originZ);
            out.putFloat(128,(float)geometry.originX).putFloat(132,(float)geometry.originY).putFloat(136,(float)geometry.originZ).putFloat(140,0);
            out.putFloat(144,width).putFloat(148,height).putFloat(152,jitterX).putFloat(156,jitterY);
            for(int i=0;i<4;i++){out.putFloat(160+i*4,sun[i]);out.putFloat(176+i*4,sunColor[i]);}
        }
        previousCamera=latest;
    }

    /** Reversed Z: clip z=near, clip w=view distance; double camera-origin subtraction. */
    static void writeProjection(ByteBuffer out,int offset,RtestFsrCamera c,double ox,double oy,double oz) {
        float cx=(float)(c.x()-ox),cy=(float)(c.y()-oy),cz=(float)(c.z()-oz);
        float sx=c.projectionM00(),sy=c.projectionM11();
        float[] columns={sx*c.rightX(),sy*c.upX(),0,c.forwardX(),
            sx*c.rightY(),sy*c.upY(),0,c.forwardY(),
            sx*c.rightZ(),sy*c.upZ(),0,c.forwardZ(),
            -sx*(c.rightX()*cx+c.rightY()*cy+c.rightZ()*cz),
            -sy*(c.upX()*cx+c.upY()*cy+c.upZ()*cz),0.05f,
            -(c.forwardX()*cx+c.forwardY()*cy+c.forwardZ()*cz)};
        for(int i=0;i<16;i++)out.putFloat(offset+i*4,columns[i]);
    }

    void record(VkCommandBuffer cmd) {
        if(geometry==null)throw new IllegalStateException("Raster geometry unavailable");
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memory=VkMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_UNIFORM_READ_BIT|VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,memory,null,null);
            VkClearValue.Buffer clears=VkClearValue.calloc(7,stack);
            clears.get(0).color().float32(0,0.12f).float32(1,0.18f).float32(2,0.25f).float32(3,1);
            clears.get(6).depthStencil().depth(0).stencil(0);
            VkRenderPassBeginInfo begin=VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).framebuffer(framebuffer).pClearValues(clears);
            begin.renderArea().extent().set(width,height);
            vkCmdBeginRenderPass(cmd,begin,VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(set),null);
            vkCmdDraw(cmd,vertexCount,1,0,0);
            for(var draw:dynamicDraws) {
                int first=0;
                while(first<draw.kinds.length) {
                    int kind=draw.kinds[first],end=first+1;
                    while(end<draw.kinds.length&&draw.kinds[end]==kind)end++;
                    long descriptor=kind==0?draw.atlasSet:draw.textureSet;
                    if(descriptor!=0) {
                        vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(descriptor),null);
                        vkCmdDraw(cmd,(end-first)*3,1,first*3,0);
                    }
                    first=end;
                }
            }
            vkCmdEndRenderPass(cmd);
        }
    }

    long colorImage(){return images[0].image;}
    long depthImage(){return images[1].image;}
    long motionImage(){return images[2].image;}
    long normalImage(){return images[3].image;}
    long materialImage(){return images[4].image;}
    long positionImage(){return images[5].image;}
    int width(){return width;} int height(){return height;}

    /** Outputs are TRANSFER_SRC after record; supplied destinations must be GENERAL. */
    void copyOutputs(VkCommandBuffer cmd,long color,long depth,long motion,long reactive,long transparency) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            long[] destinations={color,depth,motion};
            for(int i=0;i<destinations.length;i++) {
                VkImageMemoryBarrier.Buffer barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(destinations[i]);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,null,barrier);
                VkImageCopy.Buffer copy=VkImageCopy.calloc(1,stack);
                copy.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                copy.extent().set(width,height,1);
                vkCmdCopyImage(cmd,images[i].image,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,destinations[i],VK_IMAGE_LAYOUT_GENERAL,copy);
            }
            VkImageSubresourceRange.Buffer range=VkImageSubresourceRange.calloc(1,stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            VkClearColorValue zero=VkClearColorValue.calloc(stack);
            for(long target:new long[]{reactive,transparency}) {
                VkImageMemoryBarrier.Buffer barrier=VkImageMemoryBarrier.calloc(1,stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(target);
                barrier.subresourceRange().set(range.get(0));
                vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,null,null,barrier);
                vkCmdClearColorImage(cmd,target,VK_IMAGE_LAYOUT_GENERAL,zero,range);
            }
            VkMemoryBarrier.Buffer ready=VkMemoryBarrier.calloc(1,stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT);
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,ready,null,null);
        }
    }

    private void updateDescriptors() {
        writeDescriptors(set,positions,positions,materialHandle,materialBytes,atlasView,atlasSampler);
        for(var draw:dynamicDraws) {
            // Field publication changes must be propagated to live dynamic descriptors as well.
            try(MemoryStack stack=MemoryStack.stackPush()) {
                VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(draw.textureSet==0?1:2,stack);
                for(int i=0;i<writes.capacity();i++)writes.get(i).sType$Default().dstSet(i==0?draw.atlasSet:draw.textureSet)
                    .dstBinding(3).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                    .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(fieldHandle).range(fieldBytes));
                vkUpdateDescriptorSets(vk,writes,null);
                updateShadowDescriptors(draw.atlasSet);if(draw.textureSet!=0)updateShadowDescriptors(draw.textureSet);
            }
        }
    }
    private void updateShadowDescriptors(long target) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(3,stack);
            for(int i=0;i<2;i++)writes.get(i).sType$Default().dstSet(target).dstBinding(7+i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(i==0?shadowStaticView:shadowDynamicView).sampler(shadowSampler).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            writes.get(2).sType$Default().dstSet(target).dstBinding(9).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(shadowBuffer).range(shadowBytes));
            vkUpdateDescriptorSets(vk,writes,null);
        }
    }
    private void writeDescriptors(long target,NativeBuffer current,NativeBuffer previous,long material,long bytes,long textureView,long textureSampler) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            long[] handles={current.buffer,material,camera.buffer,fieldHandle};
            long[] sizes={current.size,bytes,camera.size,fieldBytes};
            VkWriteDescriptorSet.Buffer writes=VkWriteDescriptorSet.calloc(6,stack);
            for(int i=0;i<4;i++) {
                writes.get(i).sType$Default().dstSet(target).dstBinding(i).descriptorType(i==2?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(handles[i]).range(sizes[i]));
            }
            writes.get(4).sType$Default().dstSet(target).dstBinding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(textureView).sampler(textureSampler).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            writes.get(5).sType$Default().dstSet(target).dstBinding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(previous.buffer).range(previous.size));
            vkUpdateDescriptorSets(vk,writes,null);
            updateShadowDescriptors(target);
        }
    }

    private void createPipeline() {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            LongBuffer result=stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer bindings=VkDescriptorSetLayoutBinding.calloc(9,stack);
            for(int i=0;i<6;i++)bindings.get(i).binding(i).descriptorCount(1)
                .descriptorType(i==2?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:(i==4?VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER))
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(6).binding(7).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(7).binding(8).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(8).binding(9).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(vk,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,result));setLayout=result.get(0);
            VkDescriptorPoolSize.Buffer sizes=VkDescriptorPoolSize.calloc(3,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(4);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(2);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
            check(vkCreateDescriptorPool(vk,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,result));pool=result.get(0);
            sizes.get(0).descriptorCount(4096*4);sizes.get(1).descriptorCount(4096*2);sizes.get(2).descriptorCount(4096*3);
            check(vkCreateDescriptorPool(vk,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(4096).pPoolSizes(sizes),null,result));dynamicPool=result.get(0);
            check(vkAllocateDescriptorSets(vk,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)),result));set=result.get(0);
            check(vkCreatePipelineLayout(vk,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)),null,result));layout=result.get(0);
            VkAttachmentDescription.Buffer attachments=VkAttachmentDescription.calloc(7,stack);
            VkAttachmentReference.Buffer colors=VkAttachmentReference.calloc(6,stack);
            for(int i=0;i<7;i++) {
                attachments.get(i).format(FORMATS[i]).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(i==6?VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                if(i<6)colors.get(i).attachment(i).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            VkAttachmentReference depth=VkAttachmentReference.calloc(stack).attachment(6).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpasses=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(6).pColorAttachments(colors).pDepthStencilAttachment(depth);
            VkSubpassDependency.Buffer deps=VkSubpassDependency.calloc(2,stack);
            deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT|VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT|VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT).srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            check(vkCreateRenderPass(vk,VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachments).pSubpasses(subpasses).pDependencies(deps),null,result));renderPass=result.get(0);
            LongBuffer views=stack.mallocLong(7);for(Image image:images)views.put(image.view);views.flip();
            check(vkCreateFramebuffer(vk,VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass).pAttachments(views).width(width).height(height).layers(1),null,result));framebuffer=result.get(0);
            long vs=shader(WorldRasterShaders.VERTEX,Shaderc.shaderc_glsl_vertex_shader),fs=0;
            try {
                fs=shader(WorldRasterShaders.fragment(),Shaderc.shaderc_glsl_fragment_shader);
                VkPipelineShaderStageCreateInfo.Buffer stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
                stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(stack.UTF8("main"));
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(stack.UTF8("main"));
                VkPipelineVertexInputStateCreateInfo vertex=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
                VkPipelineInputAssemblyStateCreateInfo assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                // Match the existing RT/FSR presentation row convention; do not add a Y flip.
                VkViewport.Buffer viewport=VkViewport.calloc(1,stack).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
                VkRect2D.Buffer scissor=VkRect2D.calloc(1,stack);scissor.extent().set(width,height);
                VkPipelineViewportStateCreateInfo viewportState=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().pViewports(viewport).pScissors(scissor);
                VkPipelineRasterizationStateCreateInfo raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1);
                VkPipelineMultisampleStateCreateInfo samples=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineDepthStencilStateCreateInfo depthState=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_GREATER);
                VkPipelineColorBlendAttachmentState.Buffer blendAttachments=VkPipelineColorBlendAttachmentState.calloc(6,stack);
                for(int i=0;i<6;i++)blendAttachments.get(i).colorWriteMask(15);
                VkPipelineColorBlendStateCreateInfo blend=VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendAttachments);
                VkGraphicsPipelineCreateInfo.Buffer info=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages).pVertexInputState(vertex)
                    .pInputAssemblyState(assembly).pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(samples).pDepthStencilState(depthState)
                    .pColorBlendState(blend).layout(layout).renderPass(renderPass).subpass(0);
                check(vkCreateGraphicsPipelines(vk,0,info,null,result));pipeline=result.get(0);
            } finally {vkDestroyShaderModule(vk,vs,null);if(fs!=0)vkDestroyShaderModule(vk,fs,null);}
        }
    }

    private long shader(String source,int kind) {
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try {
            Shaderc.shaderc_compile_options_set_optimization_level(options,Shaderc.shaderc_optimization_level_performance);
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,source,kind,"world-raster","main",options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
            try(MemoryStack stack=MemoryStack.stackPush()) {
                LongBuffer handle=stack.mallocLong(1);
                check(vkCreateShaderModule(vk,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(Shaderc.shaderc_result_get_bytes(result)),null,handle));return handle.get(0);
            }
        } finally {if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
    private static void check(int result){if(result!=VK_SUCCESS)throw new IllegalStateException("Raster Vulkan error "+result);}

    private final class Image implements AutoCloseable {
        long image,allocation,view;
        Image(int format,boolean depth) {
            try(MemoryStack stack=MemoryStack.stackPush()) {
                VkImageCreateInfo info=VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(depth?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width,height,1);
                LongBuffer imageOut=stack.mallocLong(1);var allocOut=stack.mallocPointer(1);
                check(Vma.vmaCreateImage(device.vma(),info,VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE),imageOut,allocOut,null));
                image=imageOut.get(0);allocation=allocOut.get(0);
                try {
                    VkImageViewCreateInfo viewInfo=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                    viewInfo.subresourceRange().aspectMask(depth?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                    check(vkCreateImageView(vk,viewInfo,null,imageOut));view=imageOut.get(0);
                } catch(Throwable error){close();throw error;}
            }
        }
        public void close(){if(view!=0)vkDestroyImageView(vk,view,null);if(image!=0)Vma.vmaDestroyImage(device.vma(),image,allocation);view=0;image=0;}
    }
    public void close() {
        if(closed)return;closed=true;
        if(pipeline!=0)vkDestroyPipeline(vk,pipeline,null);if(framebuffer!=0)vkDestroyFramebuffer(vk,framebuffer,null);
        if(renderPass!=0)vkDestroyRenderPass(vk,renderPass,null);if(layout!=0)vkDestroyPipelineLayout(vk,layout,null);
        retireDynamics();if(dynamicPool!=0)vkDestroyDescriptorPool(vk,dynamicPool,null);
        if(pool!=0)vkDestroyDescriptorPool(vk,pool,null);if(setLayout!=0)vkDestroyDescriptorSetLayout(vk,setLayout,null);
        for(Image image:images)if(image!=null)image.close();if(positions!=null)positions.close();if(camera!=null)camera.close();if(dummy!=null)dummy.close();if(shadowDummy!=null)shadowDummy.close();
    }
}
