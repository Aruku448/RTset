package com.rtest.client.fsr;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.shaderc.Shaderc;

/** A small storage-image compute pass; all resources are borrowed from the reconstruction owner. */
final class DlssImagePass implements AutoCloseable {
    private final RtestVulkanContext context;
    private long setLayout, pool, set, layout, pipeline;
    DlssImagePass(RtestVulkanContext context, String shader, RtestVulkanImage... images) {
        this.context = context;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(images.length, stack);
            for (int i=0;i<images.length;i++) bindings.get(i).binding(i).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                .descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
            LongBuffer out=stack.mallocLong(1);
            RtestVulkanContext.check(VK12.vkCreateDescriptorSetLayout(context.vkDevice(),
                VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings),null,out),"DLSS guide descriptor layout");
            setLayout=out.get(0);
            var push=VkPushConstantRange.calloc(1,stack).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(4);
            RtestVulkanContext.check(VK12.vkCreatePipelineLayout(context.vkDevice(), VkPipelineLayoutCreateInfo.calloc(stack)
                .sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push),null,out),"DLSS guide pipeline layout");
            layout=out.get(0);
            long module=compile(context,shader,stack);
            try {
                var stage=VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));
                var info=VkComputePipelineCreateInfo.calloc(1,stack); info.get(0).sType$Default().stage(stage).layout(layout);
                out.put(0,0L); int result=VK12.vkCreateComputePipelines(context.vkDevice(),0,info,null,out);
                pipeline=out.get(0); RtestVulkanContext.check(result,"DLSS guide compute pipeline");
            } finally { VK12.vkDestroyShaderModule(context.vkDevice(),module,null); }
            var sizes=VkDescriptorPoolSize.calloc(1,stack).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(images.length);
            RtestVulkanContext.check(VK12.vkCreateDescriptorPool(context.vkDevice(),VkDescriptorPoolCreateInfo.calloc(stack)
                .sType$Default().maxSets(1).pPoolSizes(sizes),null,out),"DLSS guide descriptor pool"); pool=out.get(0);
            RtestVulkanContext.check(VK12.vkAllocateDescriptorSets(context.vkDevice(),VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)),out),"DLSS guide descriptor set"); set=out.get(0);
            var infos=VkDescriptorImageInfo.calloc(images.length,stack); var writes=VkWriteDescriptorSet.calloc(images.length,stack);
            for(int i=0;i<images.length;i++) {
                infos.get(i).imageView(images[i].view()).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(set).dstBinding(i).descriptorCount(1).descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.get(i).address(),1));
            }
            VK12.vkUpdateDescriptorSets(context.vkDevice(),writes,null);
        } catch(Throwable failure) { close(); throw failure; }
    }
    private static long compile(RtestVulkanContext context,String shader,MemoryStack stack) {
        long compiler=Shaderc.shaderc_compiler_initialize(), options=Shaderc.shaderc_compile_options_initialize(), result=0;
        try {
            byte[] source;
            try(var in=DlssImagePass.class.getResourceAsStream("/prime/shaders/"+shader+".comp")) {
                if(in==null) throw new IllegalStateException("Missing DLSS guide shader "+shader);
                source=in.readAllBytes();
            } catch(java.io.IOException e) { throw new IllegalStateException(e); }
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,new String(source,StandardCharsets.UTF_8),Shaderc.shaderc_glsl_compute_shader,shader,"main",options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new IllegalStateException(Shaderc.shaderc_result_get_error_message(result));
            LongBuffer output=stack.mallocLong(1);
            RtestVulkanContext.check(VK12.vkCreateShaderModule(context.vkDevice(),VkShaderModuleCreateInfo.calloc(stack)
                .sType$Default().pCode(Shaderc.shaderc_result_get_bytes(result)),null,output),"DLSS guide shader module");
            return output.get(0);
        } finally {
            if(result!=0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler);
        }
    }
    void record(VkCommandBuffer command,int width,int height,int value) {
        try(MemoryStack stack=MemoryStack.stackPush()) {
            VK12.vkCmdBindPipeline(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            VK12.vkCmdBindDescriptorSets(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,stack.longs(set),null);
            VK12.vkCmdPushConstants(command,layout,VK12.VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(value));
            VK12.vkCmdDispatch(command,(width+7)/8,(height+7)/8,1);
        }
    }
    public void close() {
        if(pool!=0) VK12.vkDestroyDescriptorPool(context.vkDevice(),pool,null);
        if(pipeline!=0) VK12.vkDestroyPipeline(context.vkDevice(),pipeline,null);
        if(layout!=0) VK12.vkDestroyPipelineLayout(context.vkDevice(),layout,null);
        if(setLayout!=0) VK12.vkDestroyDescriptorSetLayout(context.vkDevice(),setLayout,null);
        pool=pipeline=layout=setLayout=0;
    }
}
