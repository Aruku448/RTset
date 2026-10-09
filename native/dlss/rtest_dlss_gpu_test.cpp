// Vulkan fixture adapted from Prime dev e1917423, GPL-3.0-only.
// See third_party/PRIME-LICENSE.txt. Only the isolated image/readback harness is reused.
// Exercises RTest's bridge, SR and RR; no game performance/image-quality claim.
#include "rtest_dlss.cpp"
#include <atomic>
#include <limits>
#include <stdexcept>
#include "sl_matrix_helpers.h"
struct TestSize { uint32_t render_width, render_height; };
template <class T> T vk_symbol(const char *name);
std::atomic_uint32_t validation_errors{};
bool abort_on_validation_error{true};
bool omit_specular_distance{};
VkInstance dispatch_instance{};
VkDevice dispatch_device{};

int evaluate_frame(VkPhysicalDevice physical, VkDevice device, uint32_t family, void *context,
                   TestSize size) {
    const auto create_image = vk_symbol<PFN_vkCreateImage>("vkCreateImage");
    const auto requirements =
            vk_symbol<PFN_vkGetImageMemoryRequirements>("vkGetImageMemoryRequirements");
    const auto memory_properties = vk_symbol<PFN_vkGetPhysicalDeviceMemoryProperties>(
            "vkGetPhysicalDeviceMemoryProperties");
    const auto allocate = vk_symbol<PFN_vkAllocateMemory>("vkAllocateMemory");
    const auto bind = vk_symbol<PFN_vkBindImageMemory>("vkBindImageMemory");
    const auto create_view = vk_symbol<PFN_vkCreateImageView>("vkCreateImageView");
    const auto create_pool = vk_symbol<PFN_vkCreateCommandPool>("vkCreateCommandPool");
    const auto allocate_cmd = vk_symbol<PFN_vkAllocateCommandBuffers>("vkAllocateCommandBuffers");
    const auto begin = vk_symbol<PFN_vkBeginCommandBuffer>("vkBeginCommandBuffer");
    const auto barrier = vk_symbol<PFN_vkCmdPipelineBarrier>("vkCmdPipelineBarrier");
    const auto clear = vk_symbol<PFN_vkCmdClearColorImage>("vkCmdClearColorImage");
    const auto end = vk_symbol<PFN_vkEndCommandBuffer>("vkEndCommandBuffer");
    const auto get_queue = vk_symbol<PFN_vkGetDeviceQueue>("vkGetDeviceQueue");
    const auto submit = vk_symbol<PFN_vkQueueSubmit>("vkQueueSubmit");
    const auto wait = vk_symbol<PFN_vkQueueWaitIdle>("vkQueueWaitIdle");
    const auto create_buffer = vk_symbol<PFN_vkCreateBuffer>("vkCreateBuffer");
    const auto buffer_requirements =
            vk_symbol<PFN_vkGetBufferMemoryRequirements>("vkGetBufferMemoryRequirements");
    const auto bind_buffer = vk_symbol<PFN_vkBindBufferMemory>("vkBindBufferMemory");
    const auto copy = vk_symbol<PFN_vkCmdCopyImageToBuffer>("vkCmdCopyImageToBuffer");
    const auto map = vk_symbol<PFN_vkMapMemory>("vkMapMemory");
    const auto unmap = vk_symbol<PFN_vkUnmapMemory>("vkUnmapMemory");
    VkPhysicalDeviceMemoryProperties mp{};
    memory_properties(physical, &mp);
    auto type = [&](uint32_t bits, VkMemoryPropertyFlags flags) {
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((bits & (1 << i)) && (mp.memoryTypes[i].propertyFlags & flags) == flags)
                return i;
        return UINT32_MAX;
    };
    VkImage images[8]{};
    VkImageView views[8]{};
    VkDeviceMemory allocations[8]{};
    RtestSlFrame frame{};
    frame.reset = 1;
    frame.camera_near = .1f;
    frame.camera_far = 1000;
    frame.camera_fov = 1.2f;
    frame.camera_aspect = 16.f / 9.f;
    frame.camera_up[1] = 1;
    frame.camera_right[0] = 1;
    frame.camera_forward[2] = 1;
    sl::float4x4 id;
    identity(id);
    std::memcpy(frame.world_to_view, &id, 64);
    std::memcpy(frame.view_to_world, &id, 64);
    std::memcpy(frame.clip_to_previous_clip, &id, 64);
    std::memcpy(frame.previous_clip_to_clip, &id, 64);
    frame.view_to_clip[0] = 1 / (std::tan(frame.camera_fov / 2) * frame.camera_aspect);
    frame.view_to_clip[5] = 1 / std::tan(frame.camera_fov / 2);
    frame.view_to_clip[10] = frame.camera_far / (frame.camera_far - frame.camera_near);
    frame.view_to_clip[11] = 1;
    frame.view_to_clip[14] = -frame.camera_near * frame.view_to_clip[10];
    sl::float4x4 projection, inverse;
    matrix(projection, frame.view_to_clip);
    sl::matrixFullInvert(inverse, projection);
    std::memcpy(frame.clip_to_view, &inverse, 64);
    const VkFormat formats[] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT,       VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT,
                                VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R32_SFLOAT,
                                VK_FORMAT_R16G16_SFLOAT};
    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    for (uint32_t i = 0; i < 8; ++i) {
        auto &desc = frame.images[i];
        desc.width = i == 6 ? 1920 : size.render_width;
        desc.height = i == 6 ? 1080 : size.render_height;
        desc.format = formats[i];
        desc.layout = VK_IMAGE_LAYOUT_GENERAL;
        desc.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                     VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ci.imageType = VK_IMAGE_TYPE_2D;
        ci.format = formats[i];
        ci.extent = {desc.width, desc.height, 1};
        ci.mipLevels = ci.arrayLayers = 1;
        ci.samples = VK_SAMPLE_COUNT_1_BIT;
        ci.usage = desc.usage;
        if (create_image(device, &ci, nullptr, &images[i]))
            throw std::runtime_error("Vulkan test resource operation failed");
        VkMemoryRequirements mr{};
        requirements(device, images[i], &mr);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = mr.size;
        ai.memoryTypeIndex = type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (allocate(device, &ai, nullptr, &allocations[i]) ||
            bind(device, images[i], allocations[i], 0))
            throw std::runtime_error("Vulkan test resource operation failed");
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = images[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = formats[i];
        vi.subresourceRange = range;
        if (create_view(device, &vi, nullptr, &views[i]))
            throw std::runtime_error("Vulkan test resource operation failed");
        desc.image = (uint64_t)images[i];
        desc.view = (uint64_t)views[i];
        desc.memory = (uint64_t)allocations[i];
    }
    VkBuffer readback{};
    VkDeviceMemory host_memory{};
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = 1920 * 1080 * 8;
    bci.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (create_buffer(device, &bci, nullptr, &readback))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkMemoryRequirements br{};
    buffer_requirements(device, readback, &br);
    VkMemoryAllocateInfo bai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    bai.allocationSize = br.size;
    bai.memoryTypeIndex = type(br.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                                          VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (allocate(device, &bai, nullptr, &host_memory) ||
        bind_buffer(device, readback, host_memory, 0))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandPool pool{};
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.queueFamilyIndex = family;
    if (create_pool(device, &pci, nullptr, &pool))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandBuffer cmd{};
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    if (allocate_cmd(device, &cai, &cmd))
        throw std::runtime_error("Vulkan test resource operation failed");
    VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    cbi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (begin(cmd, &cbi) != VK_SUCCESS)
        throw std::runtime_error("vkBeginCommandBuffer failed");
    const VkClearColorValue colors[] = {
            {{.25f, .3f, .4f, 1}},
            {{static_cast<Owner*>(context)->mode == 1 ? .01f : 10.0f, 0, 0, 0}},
            {{0, 0, 0, 0}},
            {{0, 0, -1, 1}},
            {{.5f, .5f, .5f, 1}},
            {{.04f, .04f, .04f, 1}},
            {{std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::quiet_NaN(),
              std::numeric_limits<float>::quiet_NaN(), 0}},
            {{0, 0, 0, 0}},
            {{0, 0, 0, 0}}}; // Static fixture: both dense motion fields are zero input pixels.
    for (uint32_t i = 0; i < 8; ++i) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        ib.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.image = images[i];
        ib.subresourceRange = range;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                nullptr, 0, nullptr, 1, &ib);
        clear(cmd, images[i], VK_IMAGE_LAYOUT_GENERAL, &colors[i], 1, &range);
    }
    VkMemoryBarrier ready{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    ready.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    ready.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1,
            &ready, 0, nullptr, 0, nullptr);
    frame.command_buffer = (uint64_t)cmd;

    int result = rtest_sl_evaluate(context, &frame);
    std::printf("rtest_sl_evaluate=%d error=%s\n", result, rtest_sl_error());
    if (!result) {
        VkImageMemoryBarrier ib{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        ib.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ib.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        ib.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        ib.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        ib.srcQueueFamilyIndex = ib.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ib.image = images[6];
        ib.subresourceRange = range;
        barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0,
                nullptr, 0, nullptr, 1, &ib);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {1920, 1080, 1};
        copy(cmd, images[6], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &region);
        VkMemoryBarrier host_read{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        host_read.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        host_read.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host_read,
                0, nullptr, 0, nullptr);
    }
    if (end(cmd) != VK_SUCCESS)
        throw std::runtime_error("vkEndCommandBuffer failed");
    VkQueue queue{};
    get_queue(device, family, 0, &queue);
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cmd;
    // An exception may leave a partial SDK recording unsafe to submit. Destroying
    // this unsubmitted command pool is its cancellation proof.
    VkResult submitted = result != 0 ? VK_ERROR_UNKNOWN : submit(queue, 1, &si, VK_NULL_HANDLE);
    VkResult completed = wait(queue);
    std::printf("queueSubmit=%d queueWaitIdle=%d\n", int(submitted), int(completed));
    if (!result && !submitted && !completed) {
        void *bytes{};
        if (map(device, host_memory, 0, VK_WHOLE_SIZE, 0, &bytes) != VK_SUCCESS)
            throw std::runtime_error("vkMapMemory failed");
        auto data = (uint16_t *)bytes;
        uint64_t nonfinite = 0, nonzero = 0;
        for (size_t p = 0; p < 1920 * 1080; ++p)
            for (size_t c = 0; c < 3; ++c) {
                const auto v = data[4 * p + c];
                nonfinite += ((v & 0x7c00) == 0x7c00);
                nonzero += (v & 0x7fff) != 0;
            }
        std::printf("output_rgb_half nonfinite=%llu nonzero=%llu center=%x,%x,%x\n", nonfinite,
                    nonzero, data[4 * (540 * 1920 + 960)], data[4 * (540 * 1920 + 960) + 1],
                    data[4 * (540 * 1920 + 960) + 2]);
        unmap(device, host_memory);
        if (nonfinite || nonzero != 1920u * 1080u * 3u)
            result = 5;
    }
    // Reuse the same image owner across temporal history and a subsequent reset. A fresh
    // command buffer avoids re-recording SDK bookkeeping from the first evaluation.
    for (uint32_t temporal = 1; !result && temporal < 3; ++temporal) {
        if (allocate_cmd(device, &cai, &cmd) || begin(cmd, &cbi))
            throw std::runtime_error("Temporal command allocation failed");
        VkImageMemoryBarrier output_ready{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        output_ready.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        output_ready.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        output_ready.srcQueueFamilyIndex = output_ready.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        output_ready.image = images[6]; output_ready.subresourceRange = range;
        output_ready.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        output_ready.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                0, 0, nullptr, 0, nullptr, 1, &output_ready);
        frame.command_buffer = (uint64_t)cmd;
        frame.frame_index = temporal == 2 ? 0 : temporal; // SDK logical tokens must not use sampling indices.
        frame.reset = temporal == 2;
        frame.jitter[0] = temporal == 1 ? .25f : -.25f;
        frame.jitter[1] = temporal == 1 ? -.125f : .125f;
        result = rtest_sl_evaluate(context, &frame);
        if (result) break; // Never submit any partially recorded SDK failure.
        output_ready.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        output_ready.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
        output_ready.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &output_ready);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {1920, 1080, 1};
        copy(cmd, images[6], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback, 1, &region);
        VkMemoryBarrier host_read{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        host_read.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; host_read.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &host_read, 0, nullptr, 0, nullptr);
        if (end(cmd)) throw std::runtime_error("Temporal recording failed");
        si.pCommandBuffers = &cmd;
        submitted = submit(queue, 1, &si, VK_NULL_HANDLE); completed = wait(queue);
        if (submitted || completed) { result = 7; break; }
        void* bytes{};
        if(map(device,host_memory,0,VK_WHOLE_SIZE,0,&bytes)) throw std::runtime_error("Temporal map failed");
        auto data=static_cast<uint16_t*>(bytes); uint64_t nonfinite=0, nonzero=0;
        for(size_t p=0;p<1920u*1080u;p++) for(size_t c=0;c<3;c++) {
            auto v=data[4*p+c]; nonfinite+=((v&0x7c00)==0x7c00); nonzero+=(v&0x7fff)!=0;
        }
        unmap(device,host_memory);
        std::printf("temporal=%u reset=%u nonfinite=%llu nonzero=%llu\n",temporal,frame.reset,nonfinite,nonzero);
        if(nonfinite || nonzero!=1920u*1080u*3u) result=5;
    }
    vk_symbol<PFN_vkDestroyCommandPool>("vkDestroyCommandPool")(device, pool, nullptr);
    for (uint32_t i = 0; i < 8; ++i) {
        vk_symbol<PFN_vkDestroyImageView>("vkDestroyImageView")(device, views[i], nullptr);
        vk_symbol<PFN_vkDestroyImage>("vkDestroyImage")(device, images[i], nullptr);
        vk_symbol<PFN_vkFreeMemory>("vkFreeMemory")(device, allocations[i], nullptr);
    }
    vk_symbol<PFN_vkDestroyBuffer>("vkDestroyBuffer")(device, readback, nullptr);
    vk_symbol<PFN_vkFreeMemory>("vkFreeMemory")(device, host_memory, nullptr);
    return result ? result : (submitted != VK_SUCCESS || completed != VK_SUCCESS ? 7 : 0);
}


template<class T> T vk_symbol(const char* name) {
    static HMODULE loader=LoadLibraryExW(L"vulkan-1.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto gipa=reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(loader,"vkGetInstanceProcAddr"));
    auto gdpa=reinterpret_cast<PFN_vkGetDeviceProcAddr>(GetProcAddress(loader,"vkGetDeviceProcAddr"));
    bool physical=std::strncmp(name,"vkGetPhysicalDevice",19)==0;
    auto p=dispatch_device && !physical ? gdpa(dispatch_device,name) : nullptr;
    if(!p) p=gipa(dispatch_instance,name);
    if(!p) p=reinterpret_cast<PFN_vkVoidFunction>(GetProcAddress(loader,name));
    if(!p) throw std::runtime_error(name);
    return reinterpret_cast<T>(p);
}
VkBool32 VKAPI_CALL validation(VkDebugUtilsMessageSeverityFlagBitsEXT severity,VkDebugUtilsMessageTypeFlagsEXT,
    const VkDebugUtilsMessengerCallbackDataEXT* data,void*) {
    if(severity>=VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) ++validation_errors;
    if(severity>=VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) std::printf("VALIDATION: %s\n",data->pMessage);
    return severity>=VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? VK_TRUE : VK_FALSE;
}
int main(int argc,char** argv) try {
    const bool strict_sync=argc<=1 || std::strcmp(argv[1],"--core-validation")!=0;
    if(rtest_sl_abi()!=1) throw std::runtime_error("ABI mismatch");
    int result=rtest_sl_bootstrap();
    if(result) throw std::runtime_error(rtest_sl_error());
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.pApplicationName="RTest DLSS fixture"; app.apiVersion=VK_API_VERSION_1_2;
    const char* layers[]={"VK_LAYER_KHRONOS_validation"};
    const char* extensions[]={VK_EXT_DEBUG_UTILS_EXTENSION_NAME,VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME,
        "VK_KHR_external_semaphore_capabilities","VK_KHR_get_physical_device_properties2","VK_KHR_external_memory_capabilities"};
    VkValidationFeatureEnableEXT sync=VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT;
    VkValidationFeaturesEXT validation_info{VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT}; validation_info.enabledValidationFeatureCount=1; validation_info.pEnabledValidationFeatures=&sync;
    VkDebugUtilsMessengerCreateInfoEXT debug{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
    debug.messageSeverity=VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
    debug.messageType=VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
    debug.pfnUserCallback=validation; debug.pNext=strict_sync?&validation_info:nullptr;
    std::printf("Vulkan 1.2 validation mode=%s\n",strict_sync?"strict synchronization":"core (synchronization disabled explicitly)");
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pNext=&debug; ici.pApplicationInfo=&app;
    ici.enabledLayerCount=1; ici.ppEnabledLayerNames=layers; ici.enabledExtensionCount=5; ici.ppEnabledExtensionNames=extensions;
    VkInstance instance{};
    if(vk_symbol<PFN_vkCreateInstance>("vkCreateInstance")(&ici,nullptr,&instance)) throw std::runtime_error("vkCreateInstance failed");
    dispatch_instance=instance;
    debug.pNext=nullptr;
    VkDebugUtilsMessengerEXT messenger{};
    if(vk_symbol<PFN_vkCreateDebugUtilsMessengerEXT>("vkCreateDebugUtilsMessengerEXT")(instance,&debug,nullptr,&messenger)) throw std::runtime_error("debug messenger failed");
    uint32_t count=0; vk_symbol<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices")(instance,&count,nullptr);
    std::vector<VkPhysicalDevice> devices(count); vk_symbol<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices")(instance,&count,devices.data());
    VkPhysicalDevice physical{}; VkPhysicalDeviceProperties props{};
    for(auto d:devices) {
        vk_symbol<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties")(d,&props);
        if(props.vendorID==0x10de) { physical=d; break; }
    }
    if(!physical) throw std::runtime_error("NVIDIA adapter required for the GPU fixture");
    std::printf("adapter=%s driver=0x%x\n",props.deviceName,props.driverVersion);
    vk_symbol<PFN_vkGetPhysicalDeviceQueueFamilyProperties>("vkGetPhysicalDeviceQueueFamilyProperties")(physical,&count,nullptr);
    std::vector<VkQueueFamilyProperties> queues(count); vk_symbol<PFN_vkGetPhysicalDeviceQueueFamilyProperties>("vkGetPhysicalDeviceQueueFamilyProperties")(physical,&count,queues.data());
    uint32_t family=0;
    for(;family<count;family++) if((queues[family].queueFlags&(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT))==(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT)) break;
    float priority=1; VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; q.queueFamilyIndex=family; q.queueCount=1; q.pQueuePriorities=&priority;
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    f12.bufferDeviceAddress=f12.timelineSemaphore=f12.descriptorIndexing=VK_TRUE;
    VkPhysicalDevicePrivateDataFeatures private_data{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES}; private_data.privateData=VK_TRUE; f12.pNext=&private_data;
    VkPhysicalDeviceSynchronization2Features sync2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES}; sync2.synchronization2=VK_TRUE; private_data.pNext=&sync2;
    VkPhysicalDeviceFeatures features{}; features.shaderStorageImageExtendedFormats=features.shaderStorageImageWriteWithoutFormat=features.shaderStorageImageReadWithoutFormat=VK_TRUE;
    const char* device_extensions[]={"VK_NVX_binary_import","VK_NVX_image_view_handle","VK_KHR_push_descriptor","VK_KHR_buffer_device_address","VK_EXT_private_data","VK_KHR_synchronization2"};
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.pNext=&f12; dci.pEnabledFeatures=&features;
    dci.queueCreateInfoCount=1; dci.pQueueCreateInfos=&q; dci.enabledExtensionCount=6; dci.ppEnabledExtensionNames=device_extensions;
    VkDevice device{};
    if(vk_symbol<PFN_vkCreateDevice>("vkCreateDevice")(physical,&dci,nullptr,&device)) throw std::runtime_error("vkCreateDevice failed");
    dispatch_device=device;
    result=rtest_sl_attach((uint64_t)instance,(uint64_t)physical,(uint64_t)device,family,0);
    std::printf("attach=%d capabilities=%u error=%s\n",result,rtest_sl_capabilities(),rtest_sl_error());
    for(uint32_t mode=1;!result&&mode<=2;mode++) for(uint32_t quality=0;!result&&quality<=4;quality++) {
        uint32_t dimensions[2]{};
        result=rtest_sl_size(mode,1920,1080,quality,dimensions);
        void* owner{};
        if(!result) result=rtest_sl_create(mode,1920,1080,quality,dimensions[0],dimensions[1],&owner);
        std::printf("mode=%u quality=%u extent=%ux%u create=%d error=%s\n",mode,quality,dimensions[0],dimensions[1],result,rtest_sl_error());
        if(!result) result=evaluate_frame(physical,device,family,owner,{dimensions[0],dimensions[1]});
        if(owner) { int retired=rtest_sl_destroy(owner); if(!result) result=retired; }
    }
    int retired=rtest_sl_shutdown(); if(!result) result=retired;
    vk_symbol<PFN_vkDestroyDevice>("vkDestroyDevice")(device,nullptr); dispatch_device=nullptr;
    vk_symbol<PFN_vkDestroyDebugUtilsMessengerEXT>("vkDestroyDebugUtilsMessengerEXT")(instance,messenger,nullptr);
    vk_symbol<PFN_vkDestroyInstance>("vkDestroyInstance")(instance,nullptr);
    std::printf("validation_errors=%u result=%d\n",validation_errors.load(),result);
    return result?4:(validation_errors.load()?6:0);
} catch(const std::exception& e) { std::fprintf(stderr,"DLSS fixture failed: %s\n",e.what()); return 1; }
