/* Actual world irradiance GLSL fixture: unpublished-bank isolation, SH irradiance energy,
 * sequence progress after count saturation, NaN rejection, bounds, TTL and generation checks.
 * ./gradlew worldIrradianceFieldTest -Pworld_irradiance_dump=/tmp/world-irradiance --offline
 * cc -O2 -Wall -Wextra tools/gpu_world_irradiance_smoke.c -lvulkan -o /tmp/gpu_world_irradiance_smoke
 * /tmp/gpu_world_irradiance_smoke /tmp/world-irradiance.{spv,seed,expected}
 * Three RGB irradiance outputs permit absolute error 1e-4; all nine contract flags are exact.
 */
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#define CHECK(call) do { VkResult result=(call); if(result!=VK_SUCCESS) { fprintf(stderr,"%s: %d\n",#call,result); exit(1); } } while(0)
static void *read_file(const char *path,size_t *size) {
    FILE *f=fopen(path,"rb"); if(!f){perror(path);exit(1);}
    if(fseek(f,0,SEEK_END))exit(1);
    long length=ftell(f); if(length<=0 || length%4)exit(1);
    *size=(size_t)length; rewind(f); void *data=malloc(*size); if(!data)exit(1);
    if(fread(data,1,*size,f)!=*size)exit(1);
    fclose(f); return data;
}
static void dispatch(VkCommandBuffer cmd,VkPipelineLayout layout,uint32_t first,uint32_t count,uint32_t fused) {
    uint32_t push[]={first,count,fused};vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,12,push);
    vkCmdDispatch(cmd,(count+127)/128,1,1);
}
int main(int argc,char **argv) {
    if(argc!=4){fprintf(stderr,"Usage: %s shader.spv tree.seed tree.expected\n",argv[0]);return 1;}
    size_t code_bytes,seed_bytes,expected_bytes;
    uint32_t *code=read_file(argv[1],&code_bytes),*seed=read_file(argv[2],&seed_bytes),*expected=read_file(argv[3],&expected_bytes);
    if(seed_bytes!=expected_bytes || seed_bytes<256){fprintf(stderr,"bad seed\n");return 1;}
    uint32_t trainers=8,queries=1,output=(uint32_t)(seed_bytes/4-12);
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="RTest world irradiance smoke",.apiVersion=VK_API_VERSION_1_2};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkInstance instance;CHECK(vkCreateInstance(&ici,NULL,&instance));
    uint32_t count=0;CHECK(vkEnumeratePhysicalDevices(instance,&count,NULL));if(!count)return 1;
    VkPhysicalDevice *devices=malloc(count*sizeof(*devices));CHECK(vkEnumeratePhysicalDevices(instance,&count,devices));
    VkPhysicalDevice physical=VK_NULL_HANDLE;uint32_t family=0;
    for(uint32_t d=0;d<count && !physical;d++) {
        uint32_t nc=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[d],&nc,NULL);
        VkQueueFamilyProperties *properties=malloc(nc*sizeof(*properties));vkGetPhysicalDeviceQueueFamilyProperties(devices[d],&nc,properties);
        for(uint32_t q=0;q<nc;q++)if((properties[q].queueFlags & VK_QUEUE_COMPUTE_BIT) && properties[q].timestampValidBits){physical=devices[d];family=q;break;}
        free(properties);
    }
    free(devices);if(!physical)return 1;
    VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(physical,&props);
    float priority=1;
    VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    VkDevice device;CHECK(vkCreateDevice(physical,&dci,NULL,&device));VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
    VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=seed_bytes,.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer;CHECK(vkCreateBuffer(device,&bci,NULL,&buffer));VkMemoryRequirements req;vkGetBufferMemoryRequirements(device,buffer,&req);
    VkPhysicalDeviceMemoryProperties memory;vkGetPhysicalDeviceMemoryProperties(physical,&memory);
    uint32_t type=UINT32_MAX;
    for(uint32_t i=0;i<memory.memoryTypeCount;i++)if((req.memoryTypeBits & (1u<<i)) &&
        (memory.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
        (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)){type=i;break;}
    if(type==UINT32_MAX){fprintf(stderr,"No coherent host-visible memory\n");return 1;}
    VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=req.size,.memoryTypeIndex=type};
    VkDeviceMemory allocation;CHECK(vkAllocateMemory(device,&mai,NULL,&allocation));CHECK(vkBindBufferMemory(device,buffer,allocation,0));
    void *mapped;CHECK(vkMapMemory(device,allocation,0,req.size,0,&mapped));memcpy(mapped,seed,seed_bytes);
    VkShaderModuleCreateInfo smi={.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,.codeSize=code_bytes,.pCode=code};
    VkShaderModule shader;CHECK(vkCreateShaderModule(device,&smi,NULL,&shader));
    VkDescriptorSetLayoutBinding binding={.binding=0,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1,.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT};
    VkDescriptorSetLayoutCreateInfo slci={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,.bindingCount=1,.pBindings=&binding};
    VkDescriptorSetLayout set_layout;CHECK(vkCreateDescriptorSetLayout(device,&slci,NULL,&set_layout));
    VkDescriptorPoolSize pool_size={.type=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1};
    VkDescriptorPoolCreateInfo pci={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,.maxSets=1,.poolSizeCount=1,.pPoolSizes=&pool_size};
    VkDescriptorPool pool;CHECK(vkCreateDescriptorPool(device,&pci,NULL,&pool));
    VkDescriptorSetAllocateInfo sai={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,.descriptorPool=pool,.descriptorSetCount=1,.pSetLayouts=&set_layout};
    VkDescriptorSet set;CHECK(vkAllocateDescriptorSets(device,&sai,&set));
    VkDescriptorBufferInfo dbi={.buffer=buffer,.offset=0,.range=seed_bytes};
    VkWriteDescriptorSet write={.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=set,.dstBinding=0,.descriptorCount=1,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.pBufferInfo=&dbi};
    vkUpdateDescriptorSets(device,1,&write,0,NULL);
    VkPushConstantRange push={.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT,.offset=0,.size=12};
    VkPipelineLayoutCreateInfo plci={.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,.setLayoutCount=1,.pSetLayouts=&set_layout,.pushConstantRangeCount=1,.pPushConstantRanges=&push};
    VkPipelineLayout layout;CHECK(vkCreatePipelineLayout(device,&plci,NULL,&layout));
    VkComputePipelineCreateInfo cpci={.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,.layout=layout,
        .stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_COMPUTE_BIT,.module=shader,.pName="main"}};
    VkPipeline pipeline;CHECK(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cpci,NULL,&pipeline));
    VkCommandPoolCreateInfo cpi={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,.queueFamilyIndex=family};
    VkCommandPool command_pool;CHECK(vkCreateCommandPool(device,&cpi,NULL,&command_pool));
    VkCommandBufferAllocateInfo cai={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=command_pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    VkCommandBuffer cmd;CHECK(vkAllocateCommandBuffers(device,&cai,&cmd));
    VkQueryPool query_pool;
    VkQueryPoolCreateInfo qpci={.sType=VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,.queryType=VK_QUERY_TYPE_TIMESTAMP,.queryCount=2};
    CHECK(vkCreateQueryPool(device,&qpci,NULL,&query_pool));
    VkCommandBufferBeginInfo begin={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=0};CHECK(vkBeginCommandBuffer(cmd,&begin));
    vkCmdResetQueryPool(cmd,query_pool,0,2);
    vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,query_pool,0);
    VkMemoryBarrier barrier={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT,.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,NULL,0,NULL);
    vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,NULL);
    dispatch(cmd,layout,0,trainers,0);
    barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,NULL,0,NULL);
    dispatch(cmd,layout,0,queries,1);
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,NULL,0,NULL);
    dispatch(cmd,layout,0,queries,2);
    barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,NULL,0,NULL);
    vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,query_pool,1);
    CHECK(vkEndCommandBuffer(cmd));
    VkFenceCreateInfo fci={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VkFence fence;CHECK(vkCreateFence(device,&fci,NULL,&fence));
    VkSubmitInfo submit={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&cmd};
    CHECK(vkQueueSubmit(queue,1,&submit,fence));
    CHECK(vkWaitForFences(device,1,&fence,VK_TRUE,10000000000ULL));
    size_t mismatches=0;uint32_t *actual=mapped;
    for(size_t i=output;i<output+12;i++) {
        if(i>=output+2 && i<=output+4) {
            float got,want;memcpy(&got,actual+i,4);memcpy(&want,expected+i,4);
            if(got==got && got>want-0.0001f && got<want+0.0001f)continue;
        } else if(actual[i]==expected[i])continue;
        fprintf(stderr,"output %zu expected %08x got %08x\n",i-output,expected[i],actual[i]);mismatches++;
    }
    printf("GPU world irradiance on %s: outputs=12 mismatches=%zu\n",props.deviceName,mismatches);
    vkDestroyQueryPool(device,query_pool,NULL);
    vkDestroyFence(device,fence,NULL);vkDestroyCommandPool(device,command_pool,NULL);vkDestroyPipeline(device,pipeline,NULL);
    vkDestroyPipelineLayout(device,layout,NULL);vkDestroyDescriptorPool(device,pool,NULL);vkDestroyDescriptorSetLayout(device,set_layout,NULL);
    vkDestroyShaderModule(device,shader,NULL);vkUnmapMemory(device,allocation);vkDestroyBuffer(device,buffer,NULL);vkFreeMemory(device,allocation,NULL);
    vkDestroyDevice(device,NULL);vkDestroyInstance(instance,NULL);free(code);free(seed);free(expected);
    return mismatches?1:0;
}
