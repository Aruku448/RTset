/* Exercises the production post-processing SPIR-V on Vulkan, with analytic
 * flat fields, exposure steps, foreground defocus, and moving highlights.
 * Usage: gpu_post_processing_smoke shader.spv hdr(0/1)
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>
#define OK(x)                                                                  \
  do {                                                                         \
    VkResult result_ = (x);                                                    \
    if (result_) {                                                             \
      fprintf(stderr, "%s: %d\n", #x, result_);                                \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
#define REQUIRE(x)                                                             \
  do {                                                                         \
    if (!(x)) {                                                                \
      fprintf(stderr, "FAIL %s line %d\n", #x, __LINE__);                      \
      exit(1);                                                                 \
    }                                                                          \
  } while (0)
static VkDevice dev;
static VkPhysicalDevice gpu;
static VkQueue queue;
static VkCommandPool cp;
static VkPipeline pipeline;
static VkPipelineLayout layout;
static VkDescriptorSet sets[7];
static int W = 65, H = 37, levels = 6, hdr;
static uint32_t memtype(uint32_t bits, VkMemoryPropertyFlags flags) {
  VkPhysicalDeviceMemoryProperties p;
  vkGetPhysicalDeviceMemoryProperties(gpu, &p);
  for (uint32_t i = 0; i < p.memoryTypeCount; i++)
    if ((bits & (1u << i)) && (p.memoryTypes[i].propertyFlags & flags) == flags)
      return i;
  exit(1);
}
typedef struct {
  VkBuffer buffer;
  VkDeviceMemory memory;
  void *map;
} Buffer;
static Buffer buf(size_t size, VkBufferUsageFlags usage) {
  Buffer b = {0};
  VkBufferCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                           .size = size,
                           .usage = usage};
  OK(vkCreateBuffer(dev, &ci, 0, &b.buffer));
  VkMemoryRequirements r;
  vkGetBufferMemoryRequirements(dev, b.buffer, &r);
  VkMemoryAllocateInfo ai = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = r.size,
      .memoryTypeIndex =
          memtype(r.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                        VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
  OK(vkAllocateMemory(dev, &ai, 0, &b.memory));
  OK(vkBindBufferMemory(dev, b.buffer, b.memory, 0));
  OK(vkMapMemory(dev, b.memory, 0, r.size, 0, &b.map));
  return b;
}
typedef struct {
  VkImage image;
  VkDeviceMemory memory;
  VkImageView view, mips[7];
  int w, h, n;
  VkFormat format;
} Image;
static Image img(int w, int h, int n, VkFormat format) {
  Image a = {.w = w, .h = h, .n = n, .format = format};
  VkImageCreateInfo ci = {.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
                          .imageType = VK_IMAGE_TYPE_2D,
                          .format = format,
                          .extent = {w, h, 1},
                          .mipLevels = n,
                          .arrayLayers = 1,
                          .samples = VK_SAMPLE_COUNT_1_BIT,
                          .tiling = VK_IMAGE_TILING_OPTIMAL,
                          .usage = VK_IMAGE_USAGE_STORAGE_BIT |
                                   VK_IMAGE_USAGE_SAMPLED_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_SRC_BIT};
  OK(vkCreateImage(dev, &ci, 0, &a.image));
  VkMemoryRequirements r;
  vkGetImageMemoryRequirements(dev, a.image, &r);
  VkMemoryAllocateInfo ai = {
      .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
      .allocationSize = r.size,
      .memoryTypeIndex =
          memtype(r.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)};
  OK(vkAllocateMemory(dev, &ai, 0, &a.memory));
  OK(vkBindImageMemory(dev, a.image, a.memory, 0));
  VkImageViewCreateInfo vi = {
      .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
      .image = a.image,
      .viewType = VK_IMAGE_VIEW_TYPE_2D,
      .format = format,
      .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, n, 0, 1}};
  OK(vkCreateImageView(dev, &vi, 0, &a.view));
  for (int i = 0; i < n; i++) {
    vi.subresourceRange.baseMipLevel = i;
    vi.subresourceRange.levelCount = 1;
    OK(vkCreateImageView(dev, &vi, 0, &a.mips[i]));
  }
  return a;
}
static Image images[11];
static Buffer params, readback, upload, depthUpload;
static VkCommandBuffer begin() {
  VkCommandBuffer cmd;
  VkCommandBufferAllocateInfo ai = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
      .commandPool = cp,
      .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
      .commandBufferCount = 1};
  OK(vkAllocateCommandBuffers(dev, &ai, &cmd));
  VkCommandBufferBeginInfo bi = {
      .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
      .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
  OK(vkBeginCommandBuffer(cmd, &bi));
  return cmd;
}
static void submit(VkCommandBuffer cmd) {
  OK(vkEndCommandBuffer(cmd));
  VkSubmitInfo si = {.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
                     .commandBufferCount = 1,
                     .pCommandBuffers = &cmd};
  OK(vkQueueSubmit(queue, 1, &si, 0));
  OK(vkQueueWaitIdle(queue));
  vkFreeCommandBuffers(dev, cp, 1, &cmd);
}
static void barrier(VkCommandBuffer cmd, VkPipelineStageFlags src,
                    VkPipelineStageFlags dst, VkAccessFlags a,
                    VkAccessFlags b) {
  VkMemoryBarrier mb = {.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                        .srcAccessMask = a,
                        .dstAccessMask = b};
  vkCmdPipelineBarrier(cmd, src, dst, 0, 1, &mb, 0, 0, 0, 0);
}
static void computeBarrier(VkCommandBuffer cmd) {
  barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
          VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
}
static void clear(VkCommandBuffer cmd, int i, float r, float g, float b,
                  float a) {
  VkClearColorValue c = {.float32 = {r, g, b, a}};
  VkImageSubresourceRange range = {VK_IMAGE_ASPECT_COLOR_BIT, 0, images[i].n, 0,
                                   1};
  vkCmdClearColorImage(cmd, images[i].image, VK_IMAGE_LAYOUT_GENERAL, &c, 1,
                       &range);
}
static void dispatch(VkCommandBuffer cmd, int phase, int level, int w, int h) {
  uint32_t push[] = {phase, level, levels, 0};
  vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
  vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1,
                          &sets[level], 0, 0);
  vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 16, push);
  vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
  computeBarrier(cmd);
}
static void put(int offset, float a, float b, float c, float d) {
  float v[] = {a, b, c, d};
  memcpy((char *)params.map + offset, v, 16);
}
static void defaults() {
  memset(params.map, 0, 176);
  put(0, 0, 0, 4, 1);
  put(16, 1, 1, 1, 1);
  put(32, 0, 6500, -7.5, 6);
  put(48, .01, .26, 10, 0);
  put(64, 0, 0, 0, 16);
  put(80, 0, 0, 0, 0);
  put(96, 0, 0, 0, 1);
  put(128, 1.f / 60, .05, 60000, 1);
  uint32_t dims[] = {W, H, hdr, 0};
  memcpy((char *)params.map + 144, dims, 16);
  put(160, 0, 10, 8, 0);
}
static float half(uint16_t x) {
  int exp = (x >> 10) & 31;
  float v = exp == 0    ? ldexpf(x & 1023, -24)
            : exp == 31 ? INFINITY
                        : ldexpf(1.f + (x & 1023) / 1024.f, exp - 15);
  return x & 32768 ? -v : v;
}
static float value(int x, int y, int channel) {
  size_t i = ((size_t)y * W + x) * 4 + channel;
  return hdr ? half(((uint16_t *)readback.map)[i])
             : ((uint8_t *)readback.map)[i] / 255.f;
}
static void frame(float intensity, int pattern, int reset) {
  ((float *)params.map)[35] = reset;
  VkCommandBuffer cmd = begin();
  barrier(cmd,
          VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
              VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT |
              VK_ACCESS_TRANSFER_READ_BIT,
          VK_ACCESS_TRANSFER_WRITE_BIT);
  clear(cmd, 0, intensity, intensity, intensity, 1);
  clear(cmd, 1, pattern == 2 ? .6f : 0, 0, 0, 0);
  clear(cmd, 2, .005, 0, 0, 0);
  if (pattern) {
    float *pixels = upload.map;
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        int lit = pattern == 4   ? (x == W / 2 && y == H / 2)
                  : pattern == 3 ? x < W / 2
                                 : x == W / 2;
        for (int c = 0; c < 4; c++)
          pixels[(y * W + x) * 4 + c] = c == 3 ? 1 : lit ? intensity : 0;
      }
    VkBufferImageCopy copy = {
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {W, H, 1}};
    vkCmdCopyBufferToImage(cmd, upload.buffer, images[0].image,
                           VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
  }
  if (pattern == 3) {
    float *pixels = depthUpload.map;
    for (int y = 0; y < H; y++)
      for (int x = 0; x < W; x++) {
        pixels[(y * W + x) * 4] = x < W / 2 ? .05f : .005f;
        for (int c = 1; c < 4; c++)
          pixels[(y * W + x) * 4 + c] = 0;
      }
    VkBufferImageCopy depthCopy = {
        .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
        .imageExtent = {W, H, 1}};
    vkCmdCopyBufferToImage(cmd, depthUpload.buffer, images[2].image,
                           VK_IMAGE_LAYOUT_GENERAL, 1, &depthCopy);
  }
  barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_HOST_WRITE_BIT,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
              VK_ACCESS_UNIFORM_READ_BIT);
  dispatch(cmd, 0, 0, 1, 1);
  dispatch(cmd, 6, 0, W, H);
  dispatch(cmd, 1, 0, W, H);
  dispatch(cmd, 5, 0, W, H);
  for (int l = 0; l < levels; l++) {
    dispatch(cmd, 2, l, fmax(1, (W / 2) >> l), fmax(1, (H / 2) >> l));
    dispatch(cmd, 7, l, fmax(1, (W / 2) >> l), fmax(1, (H / 2) >> l));
    dispatch(cmd, 8, l, fmax(1, (W / 2) >> l), fmax(1, (H / 2) >> l));
  }
  dispatch(cmd, 3, 0, W, H);
  dispatch(cmd, 4, 0, W, H);
  barrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
          VK_ACCESS_TRANSFER_READ_BIT);
  VkBufferImageCopy copy = {
      .imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1},
      .imageExtent = {W, H, 1}};
  vkCmdCopyImageToBuffer(cmd, images[7].image, VK_IMAGE_LAYOUT_GENERAL,
                         readback.buffer, 1, &copy);
  copy.bufferOffset = (size_t)W * H * 8;
  copy.imageExtent = (VkExtent3D){1, 1, 1};
  vkCmdCopyImageToBuffer(cmd, images[3].image, VK_IMAGE_LAYOUT_GENERAL,
                         readback.buffer, 1, &copy);
  barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
          VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT);
  submit(cmd);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++)
      for (int c = 0; c < 4; c++)
        REQUIRE(isfinite(value(x, y, c)));
}
int main(int argc, char **argv) {
  REQUIRE(argc == 3 || argc == 4);
  int radial = argc == 4;
  if (radial) {
    W = H = 257;
    levels = 7;
  }
  hdr = atoi(argv[2]);
  VkApplicationInfo app = {.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                           .pApplicationName =
                               "RTset post processing regression",
                           .apiVersion = VK_API_VERSION_1_2};
  VkInstanceCreateInfo ici = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                              .pApplicationInfo = &app};
  VkInstance instance;
  OK(vkCreateInstance(&ici, 0, &instance));
  uint32_t count = 0;
  OK(vkEnumeratePhysicalDevices(instance, &count, 0));
  REQUIRE(count);
  VkPhysicalDevice *devices = malloc(count * sizeof(*devices));
  OK(vkEnumeratePhysicalDevices(instance, &count, devices));
  uint32_t family = 0;
  for (uint32_t d = 0; d < count && !gpu; d++) {
    uint32_t n = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(devices[d], &n, 0);
    VkQueueFamilyProperties *qs = malloc(n * sizeof(*qs));
    vkGetPhysicalDeviceQueueFamilyProperties(devices[d], &n, qs);
    for (uint32_t q = 0; q < n; q++)
      if (qs[q].queueFlags & VK_QUEUE_COMPUTE_BIT) {
        gpu = devices[d];
        family = q;
        break;
      }
    free(qs);
  }
  free(devices);
  REQUIRE(gpu);
  VkPhysicalDeviceProperties props;
  vkGetPhysicalDeviceProperties(gpu, &props);
  float priority = 1;
  VkDeviceQueueCreateInfo qci = {.sType =
                                     VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                 .queueFamilyIndex = family,
                                 .queueCount = 1,
                                 .pQueuePriorities = &priority};
  VkPhysicalDeviceFeatures features = {.shaderStorageImageExtendedFormats =
                                           VK_TRUE};
  VkDeviceCreateInfo dci = {.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                            .queueCreateInfoCount = 1,
                            .pQueueCreateInfos = &qci,
                            .pEnabledFeatures = &features};
  OK(vkCreateDevice(gpu, &dci, 0, &dev));
  vkGetDeviceQueue(dev, family, 0, &queue);
  VkCommandPoolCreateInfo cpi = {.sType =
                                     VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                 .queueFamilyIndex = family};
  OK(vkCreateCommandPool(dev, &cpi, 0, &cp));
  images[0] = img(W, H, 1, VK_FORMAT_R32G32B32A32_SFLOAT);
  images[1] = img(W, H, 1, VK_FORMAT_R32G32B32A32_SFLOAT);
  images[2] = img(W, H, 1, VK_FORMAT_R32G32B32A32_SFLOAT);
  images[3] = img(1, 1, 1, VK_FORMAT_R32G32B32A32_SFLOAT);
  images[4] = img(W, H, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
  images[5] = img(W / 2, H / 2, levels, VK_FORMAT_R16G16B16A16_SFLOAT);
  images[6] = img(W, H, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
  images[7] = img(
      W, H, 1, hdr ? VK_FORMAT_R16G16B16A16_SFLOAT : VK_FORMAT_R8G8B8A8_UNORM);
  images[8] = img(W, H, 1, VK_FORMAT_R16G16B16A16_SFLOAT);
  images[9] =
      img(W, H, 1, VK_FORMAT_R16G16B16A16_SFLOAT); // spare image 10 not needed
  images[10] = img(W / 2, H / 2, levels, VK_FORMAT_R16G16B16A16_SFLOAT);
  params = buf(176, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
  readback = buf((size_t)W * H * 8 + 16, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
  upload = buf((size_t)W * H * 16, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
  depthUpload = buf((size_t)W * H * 16, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
  VkCommandBuffer cmd = begin();
  for (int i = 0; i < 11; i++) {
    VkImageMemoryBarrier b = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_GENERAL,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT |
                         VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = images[i].image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, images[i].n, 0, 1}};
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT |
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0, 0, 0, 0, 0, 1, &b);
  }
  submit(cmd);
  VkSampler sampler;
  VkSamplerCreateInfo sci = {
      .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
      .magFilter = VK_FILTER_LINEAR,
      .minFilter = VK_FILTER_LINEAR,
      .mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR,
      .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
      .maxLod = levels - 1};
  OK(vkCreateSampler(dev, &sci, 0, &sampler));
  VkDescriptorType types[15];
  VkDescriptorSetLayoutBinding bindings[15];
  for (int i = 0; i < 15; i++) {
    types[i] = (i == 0 || i == 1 || i == 2 || i == 5 || i == 13)
                   ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
               : i == 8  ? VK_DESCRIPTOR_TYPE_SAMPLER
               : i == 10 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                         : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    bindings[i] = (VkDescriptorSetLayoutBinding){
        .binding = i,
        .descriptorType = types[i],
        .descriptorCount = 1,
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT};
  }
  VkDescriptorSetLayout sl;
  VkDescriptorSetLayoutCreateInfo slci = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
      .bindingCount = 15,
      .pBindings = bindings};
  OK(vkCreateDescriptorSetLayout(dev, &slci, 0, &sl));
  VkDescriptorPoolSize sizes[] = {
      {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 5 * levels},
      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 8 * levels},
      {VK_DESCRIPTOR_TYPE_SAMPLER, levels},
      {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, levels}};
  VkDescriptorPool pool;
  VkDescriptorPoolCreateInfo pci = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
      .maxSets = levels,
      .poolSizeCount = 4,
      .pPoolSizes = sizes};
  OK(vkCreateDescriptorPool(dev, &pci, 0, &pool));
  VkDescriptorSetLayout layouts[7];
  for (int l = 0; l < levels; l++)
    layouts[l] = sl;
  VkDescriptorSetAllocateInfo sai = {
      .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
      .descriptorPool = pool,
      .descriptorSetCount = levels,
      .pSetLayouts = layouts};
  OK(vkAllocateDescriptorSets(dev, &sai, sets));
  int mapping[] = {0, 1, 2, 3, 4, 5, 5, 6, -1, 7, -1, 8, 9, 10, 10};
  for (int l = 0; l < levels; l++) {
    VkDescriptorImageInfo infos[15] = {0};
    VkWriteDescriptorSet writes[15] = {0};
    VkDescriptorBufferInfo bi = {.buffer = params.buffer, .range = 176};
    for (int i = 0; i < 15; i++) {
      writes[i] = (VkWriteDescriptorSet){
          .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
          .dstSet = sets[l],
          .dstBinding = i,
          .descriptorCount = 1,
          .descriptorType = types[i]};
      if (i == 10)
        writes[i].pBufferInfo = &bi;
      else {
        infos[i].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        if (i == 8)
          infos[i].sampler = sampler;
        else
          infos[i].imageView = i == 6    ? images[5].mips[l]
                               : i == 14 ? images[10].mips[l]
                                         : images[mapping[i]].view;
        writes[i].pImageInfo = &infos[i];
      }
    }
    vkUpdateDescriptorSets(dev, 15, writes, 0, 0);
  }
  VkPushConstantRange push = {VK_SHADER_STAGE_COMPUTE_BIT, 0, 16};
  VkPipelineLayoutCreateInfo plci = {
      .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
      .setLayoutCount = 1,
      .pSetLayouts = &sl,
      .pushConstantRangeCount = 1,
      .pPushConstantRanges = &push};
  OK(vkCreatePipelineLayout(dev, &plci, 0, &layout));
  FILE *f = fopen(argv[1], "rb");
  REQUIRE(f);
  fseek(f, 0, SEEK_END);
  size_t bytes = ftell(f);
  rewind(f);
  uint32_t *code = malloc(bytes);
  REQUIRE(fread(code, 1, bytes, f) == bytes);
  fclose(f);
  VkShaderModuleCreateInfo smci = {
      .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
      .codeSize = bytes,
      .pCode = code};
  VkShaderModule sm;
  OK(vkCreateShaderModule(dev, &smci, 0, &sm));
  VkComputePipelineCreateInfo pici = {
      .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
      .layout = layout,
      .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                .module = sm,
                .pName = "main"}};
  OK(vkCreateComputePipelines(dev, 0, 1, &pici, 0, &pipeline));
  free(code);
  if (radial) {
    defaults();
    put(80, 0, 1.2, 0, 0);
    frame(32, 4, 1);
    float worst = 0;
    for (int r = 4; r <= 32; r *= 2) {
      float axis = (value(W / 2 + r, H / 2, 0) + value(W / 2 - r, H / 2, 0) +
                    value(W / 2, H / 2 + r, 0) + value(W / 2, H / 2 - r, 0)) *
                   .25f;
      float t = r / sqrtf(2), diagonal = 0;
      for (int sx = -1; sx <= 1; sx += 2)
        for (int sy = -1; sy <= 1; sy += 2) {
          float x = W / 2 + sx * t, y = H / 2 + sy * t;
          int ix = floorf(x), iy = floorf(y);
          float fx = x - ix, fy = y - iy;
          diagonal += ((1 - fx) * (1 - fy) * value(ix, iy, 0) +
                       fx * (1 - fy) * value(ix + 1, iy, 0) +
                       (1 - fx) * fy * value(ix, iy + 1, 0) +
                       fx * fy * value(ix + 1, iy + 1, 0)) *
                      .25f;
        }
      float error = fabsf(axis - diagonal) / fmaxf(axis, diagonal);
      if (error > worst)
        worst = error;
      printf(
          "Bloom radius %d: axis %.8f diagonal %.8f directional_error %.3f\n",
          r, axis, diagonal, error);
    }
    REQUIRE(worst < .06f);
    printf("PASS circular bloom impulse response\n");
    goto cleanup;
  }
  defaults();
  frame(.5, 0, 1);
  float base = value(W / 2, H / 2, 0);
  REQUIRE(base > .1);
  for (int y = 0; y < H; y++)
    for (int x = 0; x < W; x++)
      REQUIRE(fabsf(value(x, y, 0) - base) < .012);
  put(80, 0, 1.2, 0, 0);
  frame(.5, 0, 1);
  float expectedGain = 1.24f / 1.12f;
  if (hdr)
    REQUIRE(fabsf(value(W / 2, H / 2, 0) / base - expectedGain) < .008);
  put(80, 0, 0, 0, 0);
  if (hdr) {
    put(0, 1, 0, 4, 1);
    frame(.5, 0, 1);
    REQUIRE(fabsf(value(W / 2, H / 2, 0) / base - 2) < .008);
    put(0, 0, 0, 4, 1);
    frame(20, 0, 1);
    REQUIRE(value(W / 2, H / 2, 0) > 10);
  } else {
    for (int tone = 0; tone < 4; tone++) {
      put(80, 0, 0, 0, tone);
      float previous = 0;
      for (int step = 0; step < 10; step++) {
        frame(powf(2, step - 7), 0, 1);
        float current = value(W / 2, H / 2, 0);
        REQUIRE(current + .012 >= previous);
        previous = current;
      }
    }
  }
  defaults();
  put(0, 0, .6, 4, 1);
  frame(1, 0, 1);
  float *state = (float *)((char *)readback.map + (size_t)W * H * 8);
  float old = state[0];
  frame(4, 0, 0);
  REQUIRE(state[0] > old && state[0] < .04);
  frame(4, 0, 1);
  REQUIRE(fabsf(state[0] - .04f) < .0001);
  defaults();
  frame(2, 1, 1);
  float sharp = value(W / 2, H / 2, 0);
  put(48, .01, 10, 30, 0);
  put(64, 1, 0, 0, 16);
  frame(2, 1, 1);
  REQUIRE(value(W / 2, H / 2, 0) < sharp * .8);
  REQUIRE(value(W / 2 + 2, H / 2, 0) > .001);
  defaults();
  frame(2, 1, 1);
  sharp = value(W / 2, H / 2, 0);
  put(64, 0, 1, 0, 16);
  frame(2, 2, 0);
  REQUIRE(value(W / 2, H / 2, 0) < sharp * .8);
  REQUIRE(value(W / 2 + 2, H / 2, 0) > .001);
  frame(2, 2, 1);
  REQUIRE(fabsf(value(W / 2, H / 2, 0) - sharp) < .012);
  defaults();
  frame(2, 3, 1);
  REQUIRE(value(W / 2 + 2, H / 2, 0) < .012);
  put(48, .01, 1, 10, 0);
  put(64, 1, 0, 0, 16);
  frame(2, 3, 1);
  REQUIRE(value(W / 2 + 2, H / 2, 0) > .02);
  printf("PASS %s %s: odd extent, bloom flat-field energy, exposure "
         "history/reset, tone monotonicity, HDR headroom, DOF, motion/reset, "
         "finite output\n",
         props.deviceName, hdr ? "HDR" : "SDR");
cleanup:
  vkDestroyPipeline(dev, pipeline, 0);
  vkDestroyShaderModule(dev, sm, 0);
  vkDestroyPipelineLayout(dev, layout, 0);
  vkDestroyDescriptorPool(dev, pool, 0);
  vkDestroyDescriptorSetLayout(dev, sl, 0);
  vkDestroySampler(dev, sampler, 0);
  for (int i = 0; i < 11; i++) {
    for (int l = 0; l < images[i].n; l++)
      vkDestroyImageView(dev, images[i].mips[l], 0);
    vkDestroyImageView(dev, images[i].view, 0);
    vkDestroyImage(dev, images[i].image, 0);
    vkFreeMemory(dev, images[i].memory, 0);
  }
  Buffer buffers[] = {params, readback, upload, depthUpload};
  for (int i = 0; i < 4; i++) {
    vkUnmapMemory(dev, buffers[i].memory);
    vkDestroyBuffer(dev, buffers[i].buffer, 0);
    vkFreeMemory(dev, buffers[i].memory, 0);
  }
  vkDestroyCommandPool(dev, cp, 0);
  vkDestroyDevice(dev, 0);
  vkDestroyInstance(instance, 0);
  return 0;
}
