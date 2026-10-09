#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <vulkan/vulkan.h>
#include "sl.h"
#include "sl_dlss.h"
#include "sl_dlss_d.h"
#include "sl_helpers_vk.h"
#include "rtest_dlss.h"
#include <array>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#define API extern "C" __declspec(dllexport)
namespace {
static_assert(sizeof(RtestSlImage) == 48);
static_assert(offsetof(RtestSlFrame, images) == 472);
static_assert(sizeof(RtestSlFrame) == 856);
std::mutex mutex;
thread_local char error[1024]{};
thread_local bool evaluating{}, logged_error{};
int fail(const char* s, int code=-1) { std::snprintf(error,sizeof(error),"%s (%d)",s,code); return code; }
int check(sl::Result r,const char* op) { return r==sl::Result::eOk ? 0 : fail(op,int(r)); }
void log(sl::LogType t,const char* s) {
    if(t==sl::LogType::eError) {
        if(evaluating) logged_error=true;
        std::snprintf(error,sizeof(error),"%s",s ? s : "Streamline error");
    }
}
HMODULE module{};
bool initialized{}, attached{};
uint32_t capabilities{}, viewport_counter{};
std::wstring directory;
const wchar_t* path{};
std::string requirements;
PFun_slInit *init{};
PFun_slShutdown *shutdown_sdk{};
PFun_slGetFeatureRequirements *get_requirements{};
PFun_slSetVulkanInfo *set_vulkan{};
PFun_slIsFeatureSupported *supported{};
PFun_slGetFeatureFunction *get_function{};
PFun_slGetNewFrameToken *get_token{};
PFun_slSetConstants *set_constants{};
PFun_slEvaluateFeature *evaluate{};
PFun_slFreeResources *free_resources{};
PFun_slDLSSSetOptions *sr_options{};
PFun_slDLSSGetOptimalSettings *sr_optimal{};
PFun_slDLSSDSetOptions *rr_options{};
PFun_slDLSSDGetOptimalSettings *rr_optimal{};
using BeforePresent=VkResult(VkQueue,const VkPresentInfoKHR*,bool&);
using AfterPresent=VkResult();
BeforePresent* before_present{};
AfterPresent* after_present{};
struct Owner {
    uint32_t mode, width, height, render_width, render_height;
    sl::ViewportHandle viewport;
    sl::DLSSOptions sr;
    sl::DLSSDOptions rr;
    bool recorded{};
    Owner():viewport(++viewport_counter){}
};
std::vector<Owner*> owners;
template<class T> bool load(const char* name,T*& fn) { fn=reinterpret_cast<T*>(GetProcAddress(module,name)); return fn!=nullptr; }
template<class T> int feature(sl::Feature id,const char* name,T*& fn) {
    void* p{}; int r=check(get_function(id,name,p),name); fn=reinterpret_cast<T*>(p); return r ? r : (fn ? 0 : fail(name));
}
sl::Feature id(uint32_t mode) { return mode==2 ? sl::kFeatureDLSS_RR : sl::kFeatureDLSS; }
bool owns(Owner* p) { for(auto* o:owners) if(p==o) return true; return false; }
void matrix(sl::float4x4& m,const float* f) { std::memcpy(&m,f,64); }
void identity(sl::float4x4& m) { std::memset(&m,0,64); for(int i=0;i<4;i++) (&m[i].x)[i]=1; }
void append(const char* prefix,uint32_t n,const char** names) {
    for(uint32_t i=0;i<n;i++) { requirements+=prefix; requirements+=names[i]; requirements+='\n'; }
}
sl::Resource resource(const RtestSlImage& img) {
    sl::Resource r; r.type=sl::ResourceType::eTex2d;
    r.native=reinterpret_cast<void*>(img.image); r.view=reinterpret_cast<void*>(img.view); r.memory=reinterpret_cast<void*>(img.memory);
    r.state=img.layout; r.width=img.width; r.height=img.height; r.nativeFormat=img.format;
    r.mipLevels=r.arrayLayers=1; r.usage=img.usage; return r;
}
}
API uint32_t rtest_sl_abi() { return 1; }
API const char* rtest_sl_error() { return error; }
API int rtest_sl_bootstrap() try {
    std::lock_guard lock(mutex);
    if(initialized) return 0;
    HMODULE self{}; wchar_t filename[32768];
    if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
        reinterpret_cast<LPCWSTR>(&rtest_sl_bootstrap),&self)) return fail("Cannot locate DLSS bridge");
    DWORD n=GetModuleFileNameW(self,filename,32768);
    if(!n||n>=32768) return fail("Cannot locate DLSS bridge path");
    directory.assign(filename,n); directory.resize(directory.find_last_of(L"\\/")); path=directory.c_str();
    module=LoadLibraryExW((directory+L"\\sl.interposer.dll").c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!module) return fail("Cannot load NVIDIA Streamline",int(GetLastError()));
#define LOAD(x,n) if(!load(#n,x)) return fail("Missing export " #n)
    LOAD(init,slInit); LOAD(shutdown_sdk,slShutdown); LOAD(get_requirements,slGetFeatureRequirements);
    LOAD(set_vulkan,slSetVulkanInfo); LOAD(supported,slIsFeatureSupported); LOAD(get_function,slGetFeatureFunction);
    LOAD(get_token,slGetNewFrameToken); LOAD(set_constants,slSetConstants); LOAD(evaluate,slEvaluateFeature); LOAD(free_resources,slFreeResources);
#undef LOAD
    static const sl::Feature features[]={sl::kFeatureDLSS,sl::kFeatureDLSS_RR};
    sl::Preferences p; p.pathsToPlugins=&path; p.numPathsToPlugins=1;
    p.featuresToLoad=features; p.numFeaturesToLoad=2; p.renderAPI=sl::RenderAPI::eVulkan;
    p.flags=sl::PreferenceFlags::eUseManualHooking|sl::PreferenceFlags::eDisableCLStateTracking|sl::PreferenceFlags::eDisableDebugText|sl::PreferenceFlags::eUseFrameBasedResourceTagging;
    p.engine=sl::EngineType::eCustom; p.engineVersion="RTset"; p.projectId="df7cad31-d9c1-483e-9d53-2545debd751c";
    p.logMessageCallback=log; int result=check(init(p,sl::kSDKVersion),"slInit"); if(result) return result;
    initialized=true; requirements.clear();
    for(auto f:features) {
        sl::FeatureRequirements r; result=check(get_requirements(f,r),"slGetFeatureRequirements"); if(result) return result;
        if(r.vkNumComputeQueuesRequired>1||r.vkNumGraphicsQueuesRequired>1||r.vkNumOpticalFlowQueuesRequired) return fail("Unexpected DLSS queue requirements");
        append("D:",r.vkNumDeviceExtensions,r.vkDeviceExtensions); append("I:",r.vkNumInstanceExtensions,r.vkInstanceExtensions);
        append("12:",r.vkNumFeatures12,r.vkFeatures12); append("13:",r.vkNumFeatures13,r.vkFeatures13);
    }
    return 0;
} catch(...) { return fail("Exception during DLSS bootstrap",-4); }
API const char* rtest_sl_requirements() { return requirements.c_str(); }
API int rtest_sl_attach(uint64_t instance,uint64_t physical,uint64_t device,uint32_t family,uint32_t index) try {
    std::lock_guard lock(mutex); if(!initialized||attached) return fail("Invalid DLSS device attachment");
    sl::VulkanInfo v; v.instance=reinterpret_cast<VkInstance>(instance); v.physicalDevice=reinterpret_cast<VkPhysicalDevice>(physical); v.device=reinterpret_cast<VkDevice>(device);
    v.graphicsQueueFamily=v.computeQueueFamily=family; v.graphicsQueueIndex=v.computeQueueIndex=index;
    int result=check(set_vulkan(v),"slSetVulkanInfo"); if(result) return result;
    sl::AdapterInfo a; a.vkPhysicalDevice=reinterpret_cast<void*>(physical);
    if(supported(sl::kFeatureDLSS,a)==sl::Result::eOk && !feature(sl::kFeatureDLSS,"slDLSSSetOptions",sr_options) && !feature(sl::kFeatureDLSS,"slDLSSGetOptimalSettings",sr_optimal)) capabilities|=1;
    if(supported(sl::kFeatureDLSS_RR,a)==sl::Result::eOk && !feature(sl::kFeatureDLSS_RR,"slDLSSDSetOptions",rr_options) && !feature(sl::kFeatureDLSS_RR,"slDLSSDGetOptimalSettings",rr_optimal)) capabilities|=2;
    if(!capabilities) return fail("DLSS is unsupported on the attached device");
    if((result=feature(sl::kFeatureCommon,"slHookVkPresent",before_present)) || (result=feature(sl::kFeatureCommon,"slHookVkAfterPresent",after_present))) return result;
    attached=true; return 0;
} catch(...) { return fail("Exception during DLSS attachment",-4); }
API uint32_t rtest_sl_capabilities() { return capabilities; }
API int rtest_sl_size(uint32_t mode,uint32_t width,uint32_t height,uint32_t quality,uint32_t* out) try {
    std::lock_guard lock(mutex);
    if(!attached || !(capabilities&mode) || (mode!=1&&mode!=2) || quality>4 || !width||!height||!out) return fail("Invalid DLSS extent query");
    static const sl::DLSSMode modes[]={sl::DLSSMode::eDLAA,sl::DLSSMode::eMaxQuality,sl::DLSSMode::eBalanced,sl::DLSSMode::eMaxPerformance,sl::DLSSMode::eUltraPerformance};
    int result;
    if(mode==1) { sl::DLSSOptions o; o.mode=modes[quality]; o.outputWidth=width; o.outputHeight=height; sl::DLSSOptimalSettings s; result=check(sr_optimal(o,s),"DLSS optimal settings"); out[0]=s.optimalRenderWidth; out[1]=s.optimalRenderHeight; }
    else { sl::DLSSDOptions o; o.mode=modes[quality]; o.outputWidth=width; o.outputHeight=height; sl::DLSSDOptimalSettings s; result=check(rr_optimal(o,s),"RR optimal settings"); out[0]=s.optimalRenderWidth; out[1]=s.optimalRenderHeight; }
    return result ? result : (!out[0]||!out[1] ? fail("DLSS returned empty extent") : 0);
} catch(...) { return fail("Exception during DLSS extent query",-4); }
API int rtest_sl_create(uint32_t mode,uint32_t width,uint32_t height,uint32_t quality,uint32_t rw,uint32_t rh,void** out) try {
    std::lock_guard lock(mutex); if(out) *out=nullptr;
    if(!attached || !out || (mode!=1&&mode!=2) || !(capabilities&mode) || quality>4 || !width||!height||!rw||!rh || !owners.empty()) return fail("Invalid DLSS owner creation");
    static const sl::DLSSMode modes[]={sl::DLSSMode::eDLAA,sl::DLSSMode::eMaxQuality,sl::DLSSMode::eBalanced,sl::DLSSMode::eMaxPerformance,sl::DLSSMode::eUltraPerformance};
    auto* o=new Owner(); o->mode=mode; o->width=width; o->height=height; o->render_width=rw; o->render_height=rh;
    o->sr.mode=o->rr.mode=modes[quality]; o->sr.outputWidth=o->rr.outputWidth=width; o->sr.outputHeight=o->rr.outputHeight=height;
    o->sr.colorBuffersHDR=o->rr.colorBuffersHDR=sl::Boolean::eTrue;
    o->rr.normalRoughnessMode=sl::DLSSDNormalRoughnessMode::ePacked;
    o->rr.dlaaPreset=o->rr.qualityPreset=o->rr.balancedPreset=o->rr.performancePreset=o->rr.ultraPerformancePreset=sl::DLSSDPreset::ePresetF;
    identity(o->rr.worldToCameraView); identity(o->rr.cameraViewToWorld);
    int result=mode==1 ? check(sr_options(o->viewport,o->sr),"DLSS set options") : check(rr_options(o->viewport,o->rr),"RR set options");
    if(result) { delete o; return result; } owners.push_back(o); *out=o; return 0;
} catch(...) { return fail("Exception during DLSS owner creation",-4); }
API int rtest_sl_evaluate(void* handle,const RtestSlFrame* f) try {
    std::lock_guard lock(mutex); auto* o=static_cast<Owner*>(handle);
    if(!owns(o)||!f||!f->command_buffer||f->reset>1) return fail("Invalid DLSS frame");
    for(int i=0;i<8;i++) {
        if(o->mode==1 && i!=0&&i!=1&&i!=2&&i!=6) continue;
        auto& img=f->images[i];
        const uint32_t formats[]={VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT,VK_FORMAT_R16G16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R16G16B16A16_SFLOAT,VK_FORMAT_R32_SFLOAT};
        if(!img.image||!img.view||img.layout!=VK_IMAGE_LAYOUT_GENERAL||img.reserved||img.format!=formats[i]||
           (img.usage&(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_STORAGE_BIT))!=(VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_STORAGE_BIT)||
           img.width!=(i==6?o->width:o->render_width)||img.height!=(i==6?o->height:o->render_height)) return fail("Invalid DLSS image contract");
    }
    if(f->camera_near<=0 || f->camera_far<=f->camera_near || f->camera_fov<=0 || f->camera_fov>=3.141593f || f->camera_aspect<=0) return fail("Invalid DLSS camera frustum");
    for(size_t p=offsetof(RtestSlFrame,jitter);p<offsetof(RtestSlFrame,images);p+=4) {
        float x; std::memcpy(&x,reinterpret_cast<const char*>(f)+p,4); if(!std::isfinite(x)) return fail("Nonfinite DLSS camera constants");
    }
    int result;
    if(o->mode==2) { matrix(o->rr.worldToCameraView,f->world_to_view); matrix(o->rr.cameraViewToWorld,f->view_to_world); if((result=check(rr_options(o->viewport,o->rr),"RR frame options"))) return result; }
    sl::FrameToken* token{}; if((result=check(get_token(token,nullptr),"DLSS frame token"))) return result;
    sl::Constants c; matrix(c.cameraViewToClip,f->view_to_clip); matrix(c.clipToCameraView,f->clip_to_view);
    matrix(c.clipToPrevClip,f->clip_to_previous_clip); matrix(c.prevClipToClip,f->previous_clip_to_clip); identity(c.clipToLensClip);
    c.jitterOffset={f->jitter[0],f->jitter[1]}; c.mvecScale={1.0f/o->render_width,1.0f/o->render_height};
    c.cameraPos={f->camera_position[0],f->camera_position[1],f->camera_position[2]};
    c.cameraUp={f->camera_up[0],f->camera_up[1],f->camera_up[2]}; c.cameraRight={f->camera_right[0],f->camera_right[1],f->camera_right[2]}; c.cameraFwd={f->camera_forward[0],f->camera_forward[1],f->camera_forward[2]};
    c.cameraNear=f->camera_near; c.cameraFar=f->camera_far; c.cameraFOV=f->camera_fov; c.cameraAspectRatio=f->camera_aspect;
    c.depthInverted=o->mode==1?sl::Boolean::eTrue:sl::Boolean::eFalse; c.cameraMotionIncluded=sl::Boolean::eTrue;
    c.motionVectors3D=c.motionVectorsJittered=c.motionVectorsDilated=c.orthographicProjection=sl::Boolean::eFalse;
    c.reset=f->reset?sl::Boolean::eTrue:sl::Boolean::eFalse;
    if((result=check(set_constants(c,*token,o->viewport),"DLSS constants"))) return result;
    const sl::BufferType types[]={sl::kBufferTypeScalingInputColor,o->mode==1?sl::kBufferTypeDepth:sl::kBufferTypeLinearDepth,sl::kBufferTypeMotionVectors,sl::kBufferTypeNormalRoughness,sl::kBufferTypeAlbedo,sl::kBufferTypeSpecularAlbedo,sl::kBufferTypeScalingOutputColor,sl::kBufferTypeSpecularHitDistance};
    std::array<sl::Resource,8> resources; std::array<sl::ResourceTag,8> tags; std::array<const sl::BaseStructure*,9> inputs{}; inputs[0]=&o->viewport; uint32_t count=1;
    for(int i=0;i<8;i++) {
        if(o->mode==1 && i!=0&&i!=1&&i!=2&&i!=6) continue;
        resources[i]=resource(f->images[i]); sl::Extent extent{0,0,f->images[i].width,f->images[i].height};
        tags[i]=sl::ResourceTag(&resources[i],types[i],sl::ResourceLifecycle::eValidUntilEvaluate,&extent); inputs[count++]=&tags[i];
    }
    evaluating=true; logged_error=false; o->recorded=true;
    auto status=evaluate(id(o->mode),*token,inputs.data(),count,reinterpret_cast<sl::CommandBuffer*>(f->command_buffer)); evaluating=false;
    if(status==sl::Result::eErrorExceptionHandler) return fail("DLSS recording exception: discard command buffer",-100);
    if(status!=sl::Result::eOk) return check(status,"DLSS evaluate: discard command buffer");
    return logged_error ? -5 : 0;
} catch(...) { evaluating=false; return fail("DLSS recording exception: discard command buffer",-100); }
API int rtest_sl_destroy(void* handle) try {
    std::lock_guard lock(mutex); auto* o=static_cast<Owner*>(handle); if(!owns(o)) return fail("Invalid DLSS retirement");
    if(o->recorded) { int r=check(free_resources(id(o->mode),o->viewport),"DLSS retirement"); if(r) return r; }
    owners.clear(); delete o; return 0;
} catch(...) { return fail("Exception during DLSS retirement",-4); }
API int rtest_sl_present(uint64_t queue,uint64_t info) try {
    std::lock_guard lock(mutex); auto q=reinterpret_cast<VkQueue>(queue); auto p=reinterpret_cast<const VkPresentInfoKHR*>(info);
    static HMODULE loader=LoadLibraryExW(L"vulkan-1.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    static auto real=reinterpret_cast<PFN_vkQueuePresentKHR>(GetProcAddress(loader,"vkQueuePresentKHR"));
    if(!real) return VK_ERROR_INITIALIZATION_FAILED;
    if(!attached) return real(q,p);
    bool skip=false; VkResult r=before_present(q,p,skip); if(r!=VK_SUCCESS) return r;
    if(!skip) r=real(q,p); VkResult a=after_present(); return r==VK_SUCCESS?a:r;
} catch(...) { return VK_ERROR_UNKNOWN; }
API int rtest_sl_shutdown() try {
    std::lock_guard lock(mutex); if(!owners.empty()) return fail("DLSS owners remain at device shutdown");
    if(initialized) { int r=check(shutdown_sdk(),"slShutdown"); if(r) return r; }
    initialized=attached=false; capabilities=0; if(module) FreeLibrary(module); module=nullptr; return 0;
} catch(...) { return fail("Exception during DLSS shutdown",-4); }
