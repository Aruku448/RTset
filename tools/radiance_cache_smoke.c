/* Isolated Vulkan radiance-cache experiment. No production Minecraft integration.
 * Usage: radiance_cache_smoke shader.spv cache.seed cache.expected
 * ./gradlew radianceCacheExperimentTest -Pradiance_cache_dump=/tmp/rc --offline
 * The four GPU stages run with explicit compute dependencies; CPU oracle validates every word.
 */
#include <vulkan/vulkan.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#define CHECK(call) do { VkResult result=(call); if(result!=VK_SUCCESS) { fprintf(stderr,"%s: %d\n",#call,result); exit(1); } } while(0)
static void *read_file(const char *path,size_t *size) {
    FILE *f=fopen(path,"rb"); if(!f){perror(path);exit(1);}
    if(fseek(f,0,SEEK_END))exit(1);
    long length=ftell(f); if(length<=0 || length%4)exit(1);
    *size=(size_t)length; rewind(f); void *data=malloc(*size); if(!data)exit(1);
    if(fread(data,1,*size,f)!=*size)exit(1);
    fclose(f); return data;
}
static void dispatch(VkCommandBuffer cmd,VkPipelineLayout layout,uint32_t phase,uint32_t count) {
    uint32_t push[]={phase,count,0};vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,12,push);
    vkCmdDispatch(cmd,(count+127)/128,1,1);
}
static int compare_double(const void *a,const void *b) { double x=*(const double*)a,y=*(const double*)b; return (x>y)-(x<y); }
int main(int argc,char **argv) {
    if(argc!=4){fprintf(stderr,"Usage: %s shader.spv cache.seed cache.expected\n",argv[0]);return 1;}
    size_t code_bytes,seed_bytes,expected_bytes;
    uint32_t *code=read_file(argv[1],&code_bytes),*seed=read_file(argv[2],&seed_bytes),*expected=read_file(argv[3],&expected_bytes);
    if(seed_bytes!=expected_bytes || seed_bytes<64 || seed[0]!=0x52434331u
       || !seed[1] || (seed[1] & (seed[1]-1)) || seed[2]>32768 || !seed[9]) {
        fprintf(stderr,"bad radiance-cache seed\n");return 1;
    }
    uint64_t train_end=16ULL+16ULL*seed[2], query_end=train_end+16ULL*seed[3];
    uint64_t cache_end=query_end+8ULL*seed[1], output_end=cache_end+4ULL*seed[3];
    float variance_limit;memcpy(&variance_limit,seed+10,4);
    if(seed[4]!=16 || seed[5]!=train_end || seed[6]!=query_end || seed[7]!=cache_end
       || output_end!=seed_bytes/4 || !isfinite(variance_limit) || variance_limit<0) {
        fprintf(stderr,"bad radiance-cache offsets/variance\n");return 1;
    }
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="RTest radiance cache experiment",.apiVersion=VK_API_VERSION_1_2};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkInstance instance;CHECK(vkCreateInstance(&ici,NULL,&instance));
    uint32_t count=0;CHECK(vkEnumeratePhysicalDevices(instance,&count,NULL));if(!count)return 1;
    VkPhysicalDevice *devices=malloc(count*sizeof(*devices));CHECK(vkEnumeratePhysicalDevices(instance,&count,devices));
    VkPhysicalDevice physical=VK_NULL_HANDLE;uint32_t family=0,timestamp_bits=0;
    for(uint32_t d=0;d<count && !physical;d++) {
        uint32_t nc=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[d],&nc,NULL);
        VkQueueFamilyProperties *properties=malloc(nc*sizeof(*properties));vkGetPhysicalDeviceQueueFamilyProperties(devices[d],&nc,properties);
        for(uint32_t q=0;q<nc;q++)if((properties[q].queueFlags & VK_QUEUE_COMPUTE_BIT) && properties[q].timestampValidBits){physical=devices[d];family=q;timestamp_bits=properties[q].timestampValidBits;break;}
        free(properties);
    }
    free(devices);if(!physical)return 1;
    VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(physical,&props);
    if(seed_bytes>props.limits.maxStorageBufferRange || props.limits.maxComputeWorkGroupInvocations<128
       || props.limits.maxComputeWorkGroupSize[0]<128){fprintf(stderr,"device buffer/workgroup limit\n");return 1;}
    float priority=1;
    VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    VkDevice device;CHECK(vkCreateDevice(physical,&dci,NULL,&device));VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
    VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=seed_bytes,.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer;CHECK(vkCreateBuffer(device,&bci,NULL,&buffer));VkMemoryRequirements req;vkGetBufferMemoryRequirements(device,buffer,&req);
    VkPhysicalDeviceMemoryProperties memory;vkGetPhysicalDeviceMemoryProperties(physical,&memory);
    uint32_t type=UINT32_MAX;
    // Prefer VRAM/BAR memory, report the actual flags; results are not game frame timing.
    for(int pass=0;pass<2 && type==UINT32_MAX;pass++) {
        VkMemoryPropertyFlags required=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if(pass==0)required|=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        for(uint32_t i=0;i<memory.memoryTypeCount;i++)if((req.memoryTypeBits & (1u<<i)) &&
            (memory.memoryTypes[i].propertyFlags & required)==required){type=i;break;}
    }
    if(type==UINT32_MAX){fprintf(stderr,"No coherent host-visible memory\n");return 1;}
    VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=req.size,.memoryTypeIndex=type};
    VkDeviceMemory allocation;CHECK(vkAllocateMemory(device,&mai,NULL,&allocation));CHECK(vkBindBufferMemory(device,buffer,allocation,0));
    void *mapped;CHECK(vkMapMemory(device,allocation,0,req.size,0,&mapped));memset(mapped,0xcd,seed_bytes);
    memcpy(mapped,seed,seed_bytes);
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
    VkQueryPoolCreateInfo qpci={.sType=VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO,.queryType=VK_QUERY_TYPE_TIMESTAMP,.queryCount=5};
    CHECK(vkCreateQueryPool(device,&qpci,NULL,&query_pool));
    VkCommandBufferBeginInfo begin={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,.flags=0};CHECK(vkBeginCommandBuffer(cmd,&begin));
    vkCmdResetQueryPool(cmd,query_pool,0,5);
    vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,query_pool,0);
    VkMemoryBarrier barrier={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT,.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&barrier,0,NULL,0,NULL);
    vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,NULL);
    uint32_t stage_counts[]={seed[1],seed[2],seed[2],seed[3]};
    for(uint32_t phase=0;phase<4;phase++) {
        if(stage_counts[phase] && (stage_counts[phase]+127ULL)/128>props.limits.maxComputeWorkGroupCount[0]) {
            fprintf(stderr,"dispatch limit\n");return 1;
        }
        if(stage_counts[phase])dispatch(cmd,layout,phase,stage_counts[phase]);
        barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,1,&barrier,0,NULL,0,NULL);
        vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,query_pool,phase+1);
    }
    barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&barrier,0,NULL,0,NULL);
    CHECK(vkEndCommandBuffer(cmd));
    VkFenceCreateInfo fci={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VkFence fence;CHECK(vkCreateFence(device,&fci,NULL,&fence));
    VkSubmitInfo submit={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&cmd};
    double timings[5][31];
    for(int iteration=-5;iteration<31;iteration++) {
        CHECK(vkResetFences(device,1,&fence));
        CHECK(vkQueueSubmit(queue,1,&submit,fence));
        CHECK(vkWaitForFences(device,1,&fence,VK_TRUE,10000000000ULL));
        uint64_t timestamps[5];
        CHECK(vkGetQueryPoolResults(device,query_pool,0,5,sizeof(timestamps),timestamps,sizeof(uint64_t),VK_QUERY_RESULT_64_BIT));
        uint64_t mask=timestamp_bits==64?UINT64_MAX:(1ULL<<timestamp_bits)-1;
        if(iteration>=0) {
            timings[0][iteration]=((timestamps[4]-timestamps[0]) & mask)*props.limits.timestampPeriod/1e6;
            for(int phase=0;phase<4;phase++)timings[phase+1][iteration]=
                ((timestamps[phase+1]-timestamps[phase]) & mask)*props.limits.timestampPeriod/1e6;
        }
    }
    const char *names[]={"total","clear","elect","accumulate","query"};
    for(int phase=0;phase<5;phase++) {
        qsort(timings[phase],31,sizeof(double),compare_double);
        printf("GPU cache %s: samples=31 warmup=5 median_ms=%.6f min_ms=%.6f max_ms=%.6f\n",
            names[phase],timings[phase][15],timings[phase][0],timings[phase][30]);
    }
    size_t mismatches=0,hits=0;uint32_t *actual=mapped;double max_error=0;
    for(size_t i=0;i<seed_bytes/4;i++) {
        int same=actual[i]==expected[i];
        if(i>=seed[7] && (i-seed[7])%4!=3) {
            float a,e;memcpy(&a,actual+i,4);memcpy(&e,expected+i,4);
            double error=fabs((double)a-e);if(error>max_error)max_error=error;
            same=isfinite(a) && isfinite(e) && error<=2e-6*fmax(1.0,fabs(e));
        }
        if(!same){if(mismatches<8)fprintf(stderr,"word %zu expected %08x got %08x\n",i,expected[i],actual[i]);mismatches++;}
    }
    for(uint32_t i=0;i<seed[3];i++)if(actual[seed[7]+4*i+3]==0x3f800000u)hits++;
    printf("GPU radiance cache on %s: memory_flags=0x%x slots=%u training=%u queries=%u hits=%zu fallbacks=%zu compared_words=%zu mismatches=%zu max_rgb_error=%.9g\n",
        props.deviceName,memory.memoryTypes[type].propertyFlags,seed[1],seed[2],seed[3],hits,(size_t)seed[3]-hits,seed_bytes/4,mismatches,max_error);
    vkDestroyQueryPool(device,query_pool,NULL);
    vkDestroyFence(device,fence,NULL);vkDestroyCommandPool(device,command_pool,NULL);vkDestroyPipeline(device,pipeline,NULL);
    vkDestroyPipelineLayout(device,layout,NULL);vkDestroyDescriptorPool(device,pool,NULL);vkDestroyDescriptorSetLayout(device,set_layout,NULL);
    vkDestroyShaderModule(device,shader,NULL);vkUnmapMemory(device,allocation);vkDestroyBuffer(device,buffer,NULL);vkFreeMemory(device,allocation,NULL);
    vkDestroyDevice(device,NULL);vkDestroyInstance(instance,NULL);free(code);free(seed);free(expected);
    return mismatches?1:0;
}
