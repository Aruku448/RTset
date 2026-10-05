package com.rtest.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.rtest.client.RayTracingScene.SceneGeometry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/** Point-sun depth visibility. No RT or AS commands; caller retires borrowed draw resources first. */
final class WorldRasterSunShadow implements AutoCloseable {
    record Draw(long positionBuffer,long materialBuffer,long materialBytes,long textureView,
                long sampler,int firstVertex,int vertexCount) { }
    private record Bound(Draw draw,long set) { }
    private final VulkanDevice device;
    private final VkDevice vk;
    private final int resolution;
    private final Depth[] maps=new Depth[2];
    private final List<Bound> dynamics=new ArrayList<>();
    private NativeBuffer matrix;
    private long setLayout,staticPool,dynamicPool,layout,renderPass,pipeline,sampler;
    private Bound terrain;
    private SceneGeometry scene;
    private float sx,sy,sz;
    private boolean dirty=true,closed;
    private double[] cachedCenter;
    private double cachedRadius;
    static final String VERTEX="""
        #version 450
        layout(set=0,binding=0,std430) readonly buffer Positions {vec4 vertices[];} positions;
        layout(set=0,binding=1,std430) readonly buffer Materials {vec4 entries[];} materials;
        layout(set=0,binding=2,std140) uniform Shadow {mat4 lightMatrix;vec4 params;} shadow;
        layout(location=0) out vec2 uv;
        layout(location=1) flat out uint materialIndex;
        void main(){
            vec4 vertex=positions.vertices[gl_VertexIndex];
            materialIndex=floatBitsToUint(vertex.w)*7u;
            vec4 uv01=materials.entries[materialIndex+2u];
            vec4 uv2=materials.entries[materialIndex+3u];
            uint corner=uint(gl_VertexIndex)%3u;
            uv=corner==0u?uv01.xy:(corner==1u?uv01.zw:uv2.xy);
            gl_Position=shadow.lightMatrix*vec4(vertex.xyz,1);
        }
        """;
    static final String FRAGMENT="""
        #version 450
        layout(set=0,binding=1,std430) readonly buffer Materials {vec4 entries[];} materials;
        layout(set=0,binding=3) uniform sampler2D atlas;
        layout(location=0) in vec2 uv;
        layout(location=1) flat in uint materialIndex;
        void main(){
            vec4 uvInfo=materials.entries[materialIndex+3u];
            // RGB transmission requires separate layered visibility; omit it from opaque depth.
            if(materials.entries[materialIndex+5u].w>1.0 || materials.entries[materialIndex+6u].w>1.001)discard;
            if(uvInfo.z>0.5 && uvInfo.w>0.5 && texture(atlas,uv).a<0.5)discard;
        }
        """;
    WorldRasterSunShadow(VulkanDevice device,int resolution) {
        if(resolution<1)throw new IllegalArgumentException("shadow resolution");
        this.device=device;this.vk=device.vkDevice();this.resolution=resolution;
        try {
            matrix=NativeBuffer.create(device,80,VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,true);
            createPipeline();
            maps[0]=new Depth();maps[1]=new Depth();
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var out=stack.mallocLong(1);
                check(vkCreateSampler(vk,VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER).borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE)
                    .maxLod(0),null,out));sampler=out.get(0);
            }
        } catch(Throwable error){close();throw error;}
    }
    long staticView(){return maps[0].view;}
    long dynamicView(){return maps[1].view;}
    long sampler(){return sampler;}
    long matrixBuffer(){return matrix.buffer;}
    long matrixBytes(){return matrix.size;}
    void publishStatic(Draw draw,SceneGeometry geometry,float x,float y,float z) {
        boolean changed=scene!=geometry || sx!=x || sy!=y || sz!=z || terrain==null || !terrain.draw.equals(draw);
        if(!changed)return;
        check(vkResetDescriptorPool(vk,staticPool,0));terrain=new Bound(draw,bind(draw,staticPool));
        boolean geometryChanged=scene!=geometry;
        scene=geometry;sx=x;sy=y;sz=z;dirty=true;
        if (geometryChanged || cachedCenter==null) {
            double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
            double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
            for(var section:geometry.sections)for(int i=0;i<section.vertices.length;i+=3) {
                double px=section.originX-geometry.originX+(double)section.vertices[i];
                double py=section.originY-geometry.originY+(double)section.vertices[i+1];
                double pz=section.originZ-geometry.originZ+(double)section.vertices[i+2];
                minX=Math.min(minX,px);minY=Math.min(minY,py);minZ=Math.min(minZ,pz);
                maxX=Math.max(maxX,px);maxY=Math.max(maxY,py);maxZ=Math.max(maxZ,pz);
            }
            if(!Double.isFinite(minX)){minX=minY=minZ=-16;maxX=maxY=maxZ=16;}
            cachedCenter=new double[]{(minX+maxX)/2,(minY+maxY)/2,(minZ+maxZ)/2};
            cachedRadius=Math.sqrt((maxX-minX)*(maxX-minX)+(maxY-minY)*(maxY-minY)+(maxZ-minZ)*(maxZ-minZ))/2+16;
        }
        double[] center=cachedCenter; double radius=cachedRadius;
        try(var mapped=matrix.map()) {
            ByteBuffer out=mapped.buffer().order(ByteOrder.nativeOrder());
            writeProjection(out,center,radius,x,y,z);
            out.putFloat(64,1).putFloat(68,(float)(.05/(2*radius))).putFloat(72,0).putFloat(76,0);
        }
    }
    void publishDynamics(List<Draw> draws) {
        dynamics.clear();check(vkResetDescriptorPool(vk,dynamicPool,0));
        for(Draw draw:draws)if(draw.vertexCount>0)dynamics.add(new Bound(draw,bind(draw,dynamicPool)));
    }
    /** Orthographic standard-Z matrix, independent of display-camera position and orientation. */
    static void writeProjection(ByteBuffer out,double[] c,double radius,float x,float y,float z) {
        if (!(radius>0) || !Double.isFinite(radius)) throw new IllegalArgumentException("shadow radius");
        double len=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        if(!Double.isFinite(len) || len<1e-8){x=0;y=1;z=0;len=1;}
        double[] sun={x/len,y/len,z/len};
        double[] up=Math.abs(sun[1])>.95?new double[]{0,0,1}:new double[]{0,1,0};
        double[] right={up[1]*sun[2]-up[2]*sun[1],up[2]*sun[0]-up[0]*sun[2],up[0]*sun[1]-up[1]*sun[0]};
        double rlen=Math.sqrt(right[0]*right[0]+right[1]*right[1]+right[2]*right[2]);
        for(int i=0;i<3;i++)right[i]/=rlen;
        up=new double[]{sun[1]*right[2]-sun[2]*right[1],sun[2]*right[0]-sun[0]*right[2],sun[0]*right[1]-sun[1]*right[0]};
        for(int column=0;column<3;column++) {
            out.putFloat(column*16,(float)(right[column]/radius));
            out.putFloat(column*16+4,(float)(up[column]/radius));
            out.putFloat(column*16+8,(float)(-sun[column]/(2*radius)));out.putFloat(column*16+12,0);
        }
        out.putFloat(48,(float)(-dot(right,c)/radius));out.putFloat(52,(float)(-dot(up,c)/radius));
        out.putFloat(56,(float)(.5+dot(sun,c)/(2*radius)));out.putFloat(60,1);
    }
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    void record(VkCommandBuffer cmd) {
        if(terrain==null)return;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var memory=VkMemoryBarrier.calloc(1,stack).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_UNIFORM_READ_BIT|VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,0,memory,null,null);
            for(int layer=0;layer<2;layer++) {
                if(layer==0&&!dirty)continue;
                VkClearValue.Buffer clear=VkClearValue.calloc(1,stack);clear.get(0).depthStencil().depth(1).stencil(0);
                var begin=VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(renderPass).framebuffer(maps[layer].framebuffer).pClearValues(clear);
                begin.renderArea().extent().set(resolution,resolution);
                vkCmdBeginRenderPass(cmd,begin,VK_SUBPASS_CONTENTS_INLINE);vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline);
                if(layer==0)draw(cmd,terrain,stack);else for(var entity:dynamics)draw(cmd,entity,stack);
                vkCmdEndRenderPass(cmd);
            }
            dirty=false;
        }
    }
    private void draw(VkCommandBuffer cmd,Bound bound,MemoryStack stack) {
        vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,layout,0,stack.longs(bound.set),null);
        vkCmdDraw(cmd,bound.draw.vertexCount,1,bound.draw.firstVertex,0);
    }
    private long bind(Draw draw,long pool) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);
            check(vkAllocateDescriptorSets(vk,VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)),out));
            long set=out.get(0);var writes=VkWriteDescriptorSet.calloc(4,stack);
            long[] buffers={draw.positionBuffer,draw.materialBuffer,matrix.buffer};long[] bytes={VK_WHOLE_SIZE,draw.materialBytes,matrix.size};
            for(int i=0;i<3;i++)writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1)
                .descriptorType(i==2?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(VkDescriptorBufferInfo.calloc(1,stack).buffer(buffers[i]).range(bytes[i]));
            writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .pImageInfo(VkDescriptorImageInfo.calloc(1,stack).imageView(draw.textureView).sampler(draw.sampler).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
            vkUpdateDescriptorSets(vk,writes,null);return set;
        }
    }
    private void createPipeline() {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            var out=stack.mallocLong(1);var bindings=VkDescriptorSetLayoutBinding.calloc(4,stack);
            for(int i=0;i<4;i++)bindings.get(i).binding(i).descriptorCount(1)
                .descriptorType(i==2?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:i==3?VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT|VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(vk,VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,out));setLayout=out.get(0);
            var sizes=VkDescriptorPoolSize.calloc(3,stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            check(vkCreateDescriptorPool(vk,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes),null,out));staticPool=out.get(0);
            sizes.get(0).descriptorCount(8192);sizes.get(1).descriptorCount(4096);sizes.get(2).descriptorCount(4096);
            check(vkCreateDescriptorPool(vk,VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(4096).pPoolSizes(sizes),null,out));dynamicPool=out.get(0);
            check(vkCreatePipelineLayout(vk,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)),null,out));layout=out.get(0);
            var attachment=VkAttachmentDescription.calloc(1,stack).format(VK_FORMAT_D32_SFLOAT).samples(VK_SAMPLE_COUNT_1_BIT)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            var depth=VkAttachmentReference.calloc(stack).attachment(0).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            var subpass=VkSubpassDescription.calloc(1,stack).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).pDepthStencilAttachment(depth);
            var deps=VkSubpassDependency.calloc(2,stack);
            deps.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            deps.get(1).srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                .srcStageMask(VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT).srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            check(vkCreateRenderPass(vk,VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(attachment).pSubpasses(subpass).pDependencies(deps),null,out));renderPass=out.get(0);
            long vs=shader(VERTEX,Shaderc.shaderc_glsl_vertex_shader),fs=0;
            try {
                fs=shader(FRAGMENT,Shaderc.shaderc_glsl_fragment_shader);
                var stages=VkPipelineShaderStageCreateInfo.calloc(2,stack);
                stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(stack.UTF8("main"));
                stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(stack.UTF8("main"));
                var vertex=VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
                var assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                var viewport=VkViewport.calloc(1,stack).width(resolution).height(resolution).minDepth(0).maxDepth(1);
                var scissor=VkRect2D.calloc(1,stack);scissor.extent().set(resolution,resolution);
                var viewportState=VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().pViewports(viewport).pScissors(scissor);
                var raster=VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default().polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1);
                var ms=VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                var depthState=VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS);
                var blend=VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
                var pipelineInfo=VkGraphicsPipelineCreateInfo.calloc(1,stack).sType$Default().pStages(stages).pVertexInputState(vertex)
                    .pInputAssemblyState(assembly).pViewportState(viewportState).pRasterizationState(raster)
                    .pMultisampleState(ms).pDepthStencilState(depthState).pColorBlendState(blend).layout(layout).renderPass(renderPass);
                check(vkCreateGraphicsPipelines(vk,0,pipelineInfo,null,out));pipeline=out.get(0);
            } finally {vkDestroyShaderModule(vk,vs,null);if(fs!=0)vkDestroyShaderModule(vk,fs,null);}
        }
    }
    private long shader(String source,int kind) {
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,source,kind,"world-sun-shadow","main",options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var handle=stack.mallocLong(1);
                check(vkCreateShaderModule(vk,VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(Shaderc.shaderc_result_get_bytes(result)),null,handle));return handle.get(0);
            }
        } finally {if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
    private final class Depth implements AutoCloseable {
        long image,allocation,view,framebuffer;
        Depth() {
            try(MemoryStack stack=MemoryStack.stackPush()) {
                var info=VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_D32_SFLOAT)
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT|VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(resolution,resolution,1);var out=stack.mallocLong(1);var alloc=stack.mallocPointer(1);
                check(Vma.vmaCreateImage(device.vma(),info,VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE),out,alloc,null));
                image=out.get(0);allocation=alloc.get(0);
                try {
                    var viewInfo=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_D32_SFLOAT);
                    viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
                    check(vkCreateImageView(vk,viewInfo,null,out));view=out.get(0);
                    check(vkCreateFramebuffer(vk,VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(renderPass)
                        .pAttachments(stack.longs(view)).width(resolution).height(resolution).layers(1),null,out));framebuffer=out.get(0);
                } catch(Throwable error){close();throw error;}
            }
        }
        public void close(){if(framebuffer!=0)vkDestroyFramebuffer(vk,framebuffer,null);if(view!=0)vkDestroyImageView(vk,view,null);
            if(image!=0)Vma.vmaDestroyImage(device.vma(),image,allocation);framebuffer=0;view=0;image=0;}
    }
    private static void check(int status){if(status!=VK_SUCCESS)throw new IllegalStateException("Sun shadow Vulkan error "+status);}
    public void close() {
        if(closed)return;closed=true;
        for(Depth map:maps)if(map!=null)map.close();if(sampler!=0)vkDestroySampler(vk,sampler,null);
        if(pipeline!=0)vkDestroyPipeline(vk,pipeline,null);if(renderPass!=0)vkDestroyRenderPass(vk,renderPass,null);
        if(layout!=0)vkDestroyPipelineLayout(vk,layout,null);if(staticPool!=0)vkDestroyDescriptorPool(vk,staticPool,null);
        if(dynamicPool!=0)vkDestroyDescriptorPool(vk,dynamicPool,null);if(setLayout!=0)vkDestroyDescriptorSetLayout(vk,setLayout,null);
        if(matrix!=null)matrix.close();
    }
}
