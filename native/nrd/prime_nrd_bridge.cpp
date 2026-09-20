#include <NRD.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

#if defined(_WIN32)
#define PRIME_NRD_EXPORT extern "C" __declspec(dllexport)
#else
#define PRIME_NRD_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace
{
    constexpr uint32_t PRIME_NRD_ABI_VERSION = 8;
    constexpr nrd::Identifier PRIME_NRD_DENOISER_ID = 0;

    struct PrimeNrdCreateDesc
    {
        uint32_t width;
        uint32_t height;
        uint32_t denoiserKind;
    };

    struct PrimeNrdTextureInfo
    {
        uint32_t format;
        uint32_t downsampleFactor;
    };

    struct PrimeNrdPipelineRangeInfo
    {
        uint32_t descriptorType;
        uint32_t descriptorsNum;
    };

    struct PrimeNrdPipelineInfo
    {
        uint64_t spirvAddress;
        uint64_t spirvSize;
        uint64_t rangesAddress;
        uint32_t rangesNum;
        uint32_t hasConstantData;
        char shaderIdentifier[256];
    };

    struct PrimeNrdDescription
    {
        uint32_t abiVersion;
        uint32_t nrdVersion;
        uint32_t samplerOffset;
        uint32_t textureOffset;
        uint32_t constantBufferOffset;
        uint32_t storageTextureOffset;
        uint32_t constantBufferRegisterIndex;
        uint32_t samplersBaseRegisterIndex;
        uint32_t resourcesBaseRegisterIndex;
        uint32_t constantBufferMaxDataSize;
        uint64_t samplersAddress;
        uint32_t samplersNum;
        uint32_t pipelinesNum;
        uint64_t pipelinesAddress;
        uint32_t permanentPoolSize;
        uint32_t transientPoolSize;
        uint64_t permanentPoolAddress;
        uint64_t transientPoolAddress;
        uint32_t setsMaxNum;
        uint32_t constantBufferAndSamplersSpaceIndex;
        uint32_t resourcesSpaceIndex;
        uint32_t reserved;
        char shaderEntryPoint[32];
    };

    struct PrimeNrdFrameSettings
    {
        float viewToClip[16];
        float viewToClipPrev[16];
        float worldToView[16];
        float worldToViewPrev[16];
        float cameraJitter[2];
        float cameraJitterPrev[2];
        uint32_t width;
        uint32_t height;
        uint32_t previousWidth;
        uint32_t previousHeight;
        uint32_t frameIndex;
        uint32_t restart;
        float timeDeltaMilliseconds;
        float denoisingRange;
        uint32_t enableValidation;
    };

    struct PrimeNrdTuning
    {
        uint32_t hitDistanceReconstructionMode = 1;
        float diffusePrepassBlurRadius = 30.0f;
        float specularPrepassBlurRadius = 50.0f;
        float minHitDistanceWeight = 0.1f;
        float minBlurRadius = 1.0f;
        float maxBlurRadius = 30.0f;
        float lobeAngleFraction = 0.15f;
        float roughnessFraction = 0.15f;
        float planeDistanceSensitivity = 0.02f;
        float fastHistoryClampingSigmaScale = 2.0f;
        float fireflySuppressorMinRelativeScale = 2.0f;
        float disocclusionThreshold = 0.01f;
        uint32_t maxAccumulatedFrameNum = 30;
        uint32_t maxFastAccumulatedFrameNum = 6;
        uint32_t historyFixFrameNum = 3;
        uint32_t enableAntiFirefly = 1;
        uint32_t usePrepassOnlyForSpecularMotionEstimation = 0;
        uint32_t historyFixPixelStride = 14;
        float convergenceScale = 1.0f;
        float convergenceBase = 0.2f;
        float convergencePercent = 0.8f;
    };

    struct PrimeNrdResourceInfo
    {
        uint32_t descriptorType;
        uint32_t resourceType;
        uint32_t indexInPool;
        uint32_t reserved;
    };

    struct PrimeNrdDispatchInfo
    {
        uint64_t nameAddress;
        uint64_t resourcesAddress;
        uint64_t constantDataAddress;
        uint32_t resourcesNum;
        uint32_t constantDataSize;
        uint32_t pipelineIndex;
        uint32_t gridWidth;
        uint32_t gridHeight;
        uint32_t reserved;
    };

    struct PrimeNrdDispatchList
    {
        uint64_t dispatchesAddress;
        uint32_t dispatchesNum;
        uint32_t reserved;
    };

    struct PrimeNrdContext
    {
        nrd::Instance* instance = nullptr;
        PrimeNrdDescription description = {};
        std::vector<uint32_t> samplers;
        std::vector<PrimeNrdTextureInfo> permanentPool;
        std::vector<PrimeNrdTextureInfo> transientPool;
        std::vector<std::vector<PrimeNrdPipelineRangeInfo>> pipelineRanges;
        std::vector<PrimeNrdPipelineInfo> pipelines;
        std::vector<std::vector<PrimeNrdResourceInfo>> dispatchResources;
        std::vector<PrimeNrdDispatchInfo> dispatches;
        PrimeNrdTuning tuning = {};
        uint32_t denoiserProfile = 0;

        ~PrimeNrdContext()
        {
            if (instance != nullptr)
                nrd::DestroyInstance(*instance);
        }
    };

    uint32_t PackVersion(uint32_t major, uint32_t minor, uint32_t build)
    {
        return (major << 24) | (minor << 16) | build;
    }

    void CopyTexturePool(
        const nrd::TextureDesc* source,
        uint32_t count,
        std::vector<PrimeNrdTextureInfo>& destination)
    {
        destination.resize(count);
        for (uint32_t i = 0; i < count; i++)
        {
            destination[i].format = static_cast<uint32_t>(source[i].format);
            destination[i].downsampleFactor = source[i].downsampleFactor;
        }
    }

    nrd::Result ApplyDenoiserSettings(PrimeNrdContext& context, const PrimeNrdTuning& tuning)
    {
        if (tuning.hitDistanceReconstructionMode > 2)
            return nrd::Result::INVALID_ARGUMENT;

        PrimeNrdTuning sanitized = tuning;
        sanitized.diffusePrepassBlurRadius = std::max(sanitized.diffusePrepassBlurRadius, 0.0f);
        sanitized.specularPrepassBlurRadius = std::max(sanitized.specularPrepassBlurRadius, 0.0f);
        sanitized.minHitDistanceWeight = std::clamp(sanitized.minHitDistanceWeight, 0.0001f, 0.2f);
        sanitized.minBlurRadius = std::max(sanitized.minBlurRadius, 0.0f);
        sanitized.maxBlurRadius = std::max(sanitized.maxBlurRadius, sanitized.minBlurRadius);
        sanitized.lobeAngleFraction = std::max(sanitized.lobeAngleFraction, 0.0001f);
        sanitized.roughnessFraction = std::max(sanitized.roughnessFraction, 0.0001f);
        sanitized.planeDistanceSensitivity = std::max(sanitized.planeDistanceSensitivity, 0.0001f);
        sanitized.fastHistoryClampingSigmaScale = std::max(sanitized.fastHistoryClampingSigmaScale, 1.0f);
        sanitized.fireflySuppressorMinRelativeScale =
            std::clamp(sanitized.fireflySuppressorMinRelativeScale, 1.0f, 3.0f);
        sanitized.disocclusionThreshold = std::max(sanitized.disocclusionThreshold, 0.0001f);
        sanitized.maxAccumulatedFrameNum = std::min(sanitized.maxAccumulatedFrameNum, 63u);
        sanitized.maxFastAccumulatedFrameNum = std::min(
            sanitized.maxFastAccumulatedFrameNum, sanitized.maxAccumulatedFrameNum);
        sanitized.historyFixFrameNum = std::min(
            sanitized.historyFixFrameNum,
            sanitized.maxFastAccumulatedFrameNum > 0 ? sanitized.maxFastAccumulatedFrameNum - 1 : 0u);
        sanitized.historyFixPixelStride = std::max(sanitized.historyFixPixelStride, 1u);
        sanitized.convergenceScale = std::max(sanitized.convergenceScale, 0.0001f);
        sanitized.convergenceBase = std::clamp(sanitized.convergenceBase, 0.0f, 1.0f);
        sanitized.convergencePercent = std::clamp(sanitized.convergencePercent, 0.0f, 1.0f);

        nrd::ReblurSettings settings = {};
        settings.convergenceSettings.s = sanitized.convergenceScale;
        settings.convergenceSettings.b = sanitized.convergenceBase;
        settings.convergenceSettings.p = sanitized.convergencePercent;
        settings.maxAccumulatedFrameNum = sanitized.maxAccumulatedFrameNum;
        settings.maxFastAccumulatedFrameNum = sanitized.maxFastAccumulatedFrameNum;
        settings.historyFixFrameNum = sanitized.historyFixFrameNum;
        settings.historyFixBasePixelStride = sanitized.historyFixPixelStride;
        settings.historyFixAlternatePixelStride = sanitized.historyFixPixelStride;
        settings.fastHistoryClampingSigmaScale = sanitized.fastHistoryClampingSigmaScale;
        settings.diffusePrepassBlurRadius = sanitized.diffusePrepassBlurRadius;
        settings.specularPrepassBlurRadius = sanitized.specularPrepassBlurRadius;
        settings.minHitDistanceWeight = sanitized.minHitDistanceWeight;
        settings.minBlurRadius = sanitized.minBlurRadius;
        settings.maxBlurRadius = sanitized.maxBlurRadius;
        settings.lobeAngleFraction = sanitized.lobeAngleFraction;
        settings.roughnessFraction = sanitized.roughnessFraction;
        settings.planeDistanceSensitivity = sanitized.planeDistanceSensitivity;
        settings.fireflySuppressorMinRelativeScale = sanitized.fireflySuppressorMinRelativeScale;
        settings.hitDistanceReconstructionMode =
            static_cast<nrd::HitDistanceReconstructionMode>(sanitized.hitDistanceReconstructionMode);
        settings.enableAntiFirefly = sanitized.enableAntiFirefly != 0;
        settings.usePrepassOnlyForSpecularMotionEstimation =
            sanitized.usePrepassOnlyForSpecularMotionEstimation != 0;

        const nrd::Result result = nrd::SetDenoiserSettings(
            *context.instance, PRIME_NRD_DENOISER_ID, &settings);
        if (result == nrd::Result::SUCCESS)
            context.tuning = sanitized;
        return result;
    }

    bool BuildDescription(PrimeNrdContext& context)
    {
        const nrd::LibraryDesc* library = nrd::GetLibraryDesc();
        const nrd::InstanceDesc* instance = nrd::GetInstanceDesc(*context.instance);
        if (library == nullptr || instance == nullptr || instance->shaderEntryPoint == nullptr)
            return false;

        context.samplers.resize(instance->samplersNum);
        for (uint32_t i = 0; i < instance->samplersNum; i++)
            context.samplers[i] = static_cast<uint32_t>(instance->samplers[i]);

        CopyTexturePool(instance->permanentPool, instance->permanentPoolSize, context.permanentPool);
        CopyTexturePool(instance->transientPool, instance->transientPoolSize, context.transientPool);

        context.pipelineRanges.resize(instance->pipelinesNum);
        context.pipelines.resize(instance->pipelinesNum);
        for (uint32_t pipelineIndex = 0; pipelineIndex < instance->pipelinesNum; pipelineIndex++)
        {
            const nrd::PipelineDesc& source = instance->pipelines[pipelineIndex];
            if (source.computeShaderSPIRV.bytecode == nullptr || source.computeShaderSPIRV.size == 0)
                return false;

            std::vector<PrimeNrdPipelineRangeInfo>& ranges = context.pipelineRanges[pipelineIndex];
            ranges.resize(source.resourceRangesNum);
            for (uint32_t rangeIndex = 0; rangeIndex < source.resourceRangesNum; rangeIndex++)
            {
                ranges[rangeIndex].descriptorType =
                    static_cast<uint32_t>(source.resourceRanges[rangeIndex].descriptorType);
                ranges[rangeIndex].descriptorsNum = source.resourceRanges[rangeIndex].descriptorsNum;
            }

            PrimeNrdPipelineInfo& destination = context.pipelines[pipelineIndex];
            destination.spirvAddress = reinterpret_cast<uint64_t>(source.computeShaderSPIRV.bytecode);
            destination.spirvSize = source.computeShaderSPIRV.size;
            destination.rangesAddress = reinterpret_cast<uint64_t>(ranges.data());
            destination.rangesNum = static_cast<uint32_t>(ranges.size());
            destination.hasConstantData = source.hasConstantData ? 1u : 0u;
            std::memcpy(destination.shaderIdentifier, source.shaderIdentifier, sizeof(destination.shaderIdentifier));
            destination.shaderIdentifier[sizeof(destination.shaderIdentifier) - 1] = '\0';
        }

        PrimeNrdDescription& description = context.description;
        description.abiVersion = PRIME_NRD_ABI_VERSION;
        description.nrdVersion = PackVersion(library->versionMajor, library->versionMinor, library->versionBuild);
        description.samplerOffset = library->spirvBindingOffsets.samplerOffset;
        description.textureOffset = library->spirvBindingOffsets.textureOffset;
        description.constantBufferOffset = library->spirvBindingOffsets.constantBufferOffset;
        description.storageTextureOffset = library->spirvBindingOffsets.storageTextureAndBufferOffset;
        description.constantBufferRegisterIndex = instance->constantBufferRegisterIndex;
        description.samplersBaseRegisterIndex = instance->samplersBaseRegisterIndex;
        description.resourcesBaseRegisterIndex = instance->resourcesBaseRegisterIndex;
        description.constantBufferMaxDataSize = instance->constantBufferMaxDataSize;
        description.samplersAddress = reinterpret_cast<uint64_t>(context.samplers.data());
        description.samplersNum = static_cast<uint32_t>(context.samplers.size());
        description.pipelinesNum = static_cast<uint32_t>(context.pipelines.size());
        description.pipelinesAddress = reinterpret_cast<uint64_t>(context.pipelines.data());
        description.permanentPoolSize = static_cast<uint32_t>(context.permanentPool.size());
        description.transientPoolSize = static_cast<uint32_t>(context.transientPool.size());
        description.permanentPoolAddress = reinterpret_cast<uint64_t>(context.permanentPool.data());
        description.transientPoolAddress = reinterpret_cast<uint64_t>(context.transientPool.data());
        description.setsMaxNum = instance->descriptorPoolDesc.setsMaxNum;
        description.constantBufferAndSamplersSpaceIndex =
            instance->constantBufferAndSamplersSpaceIndex;
        description.resourcesSpaceIndex = instance->resourcesSpaceIndex;
        const size_t entryPointLength = std::min(
            std::strlen(instance->shaderEntryPoint),
            sizeof(description.shaderEntryPoint) - 1);
        std::memcpy(
            description.shaderEntryPoint,
            instance->shaderEntryPoint,
            entryPointLength);
        description.shaderEntryPoint[entryPointLength] = '\0';
        return true;
    }
}

static_assert(sizeof(PrimeNrdCreateDesc) == 12);
static_assert(sizeof(PrimeNrdTextureInfo) == 8);
static_assert(sizeof(PrimeNrdPipelineRangeInfo) == 8);
static_assert(sizeof(PrimeNrdPipelineInfo) == 288);
static_assert(sizeof(PrimeNrdDescription) == 136);
static_assert(sizeof(PrimeNrdFrameSettings) == 308);
static_assert(sizeof(PrimeNrdTuning) == 84);
static_assert(sizeof(PrimeNrdResourceInfo) == 16);
static_assert(sizeof(PrimeNrdDispatchInfo) == 48);
static_assert(sizeof(PrimeNrdDispatchList) == 16);

PRIME_NRD_EXPORT uint32_t primeNrdGetAbiVersion()
{
    return PRIME_NRD_ABI_VERSION;
}

PRIME_NRD_EXPORT int32_t primeNrdCreate(
    const PrimeNrdCreateDesc* createDesc,
    PrimeNrdContext** output)
{
    if (createDesc == nullptr || output == nullptr || createDesc->width == 0 || createDesc->height == 0
        || createDesc->denoiserKind > 2)
        return -1;

    *output = nullptr;
    PrimeNrdContext* context = new (std::nothrow) PrimeNrdContext();
    if (context == nullptr)
        return -2;

    try
    {
    // Transparent branches expose a complete primary-surface replacement. They therefore need
    // the same diffuse/specular separation as an ordinary primary surface; filtering their mixed
    // radiance as diffuse destroys the replacement surface's texture and glossy response.
    const nrd::Denoiser selectedDenoiser = nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR;
    const nrd::DenoiserDesc denoiser = {PRIME_NRD_DENOISER_ID, selectedDenoiser};
    context->denoiserProfile = createDesc->denoiserKind;
    const nrd::InstanceCreationDesc creation = {{}, &denoiser, 1};
    nrd::Result result = nrd::CreateInstance(creation, context->instance);
    if (result != nrd::Result::SUCCESS || !BuildDescription(*context))
    {
        delete context;
        return static_cast<int32_t>(result == nrd::Result::SUCCESS ? nrd::Result::FAILURE : result);
    }

    nrd::ReblurSettings settings = {};
    settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_3X3;
    settings.diffusePrepassBlurRadius = 30.0f;
    settings.specularPrepassBlurRadius = 50.0f;
    if (context->denoiserProfile != 0)
    {
        // The delta interface itself is deterministic, but the promoted PSR selects one ordinary
        // diffuse/specular continuation. NRD explicitly recommends hit-distance reconstruction
        // for this probabilistic lobe split. Keep diffuse texture samples untouched; retain a
        // narrow specular pre-pass only for motion estimation, which is the recommended clean-
        // signal mode and avoids feeding its spatial blur into the visible result.
        settings.hitDistanceReconstructionMode = nrd::HitDistanceReconstructionMode::AREA_3X3;
        settings.diffusePrepassBlurRadius = 0.0f;
        settings.specularPrepassBlurRadius = 12.0f;
        settings.usePrepassOnlyForSpecularMotionEstimation = true;
        settings.maxBlurRadius = 12.0f;
    }
    result = nrd::SetDenoiserSettings(*context->instance, PRIME_NRD_DENOISER_ID, &settings);
    if (result != nrd::Result::SUCCESS)
    {
        delete context;
        return static_cast<int32_t>(result);
    }

    *output = context;
    return 0;
    }
    catch (...)
    {
        // NRD's C++ API may throw std::bad_alloc while building the copied description. Keep
        // creation failure ownership-complete; the context destructor also destroys a partially
        // created NRD instance.
        delete context;
        return -2;
    }
}

PRIME_NRD_EXPORT int32_t primeNrdGetDescription(
    const PrimeNrdContext* context,
    PrimeNrdDescription* output)
{
    if (context == nullptr || output == nullptr)
        return -1;
    *output = context->description;
    return 0;
}

PRIME_NRD_EXPORT int32_t primeNrdSetDenoiserSettings(
    PrimeNrdContext* context,
    const PrimeNrdTuning* input)
{
    if (context == nullptr || input == nullptr)
        return -1;
    return static_cast<int32_t>(ApplyDenoiserSettings(*context, *input));
}

PRIME_NRD_EXPORT int32_t primeNrdSetFrameSettings(
    PrimeNrdContext* context,
    const PrimeNrdFrameSettings* input)
{
    if (context == nullptr || input == nullptr || input->width == 0 || input->height == 0)
        return -1;

    nrd::CommonSettings settings = {};
    std::memcpy(settings.viewToClipMatrix, input->viewToClip, sizeof(input->viewToClip));
    std::memcpy(settings.viewToClipMatrixPrev, input->viewToClipPrev, sizeof(input->viewToClipPrev));
    std::memcpy(settings.worldToViewMatrix, input->worldToView, sizeof(input->worldToView));
    std::memcpy(settings.worldToViewMatrixPrev, input->worldToViewPrev, sizeof(input->worldToViewPrev));
    std::memcpy(settings.cameraJitter, input->cameraJitter, sizeof(input->cameraJitter));
    std::memcpy(settings.cameraJitterPrev, input->cameraJitterPrev, sizeof(input->cameraJitterPrev));
    settings.motionVectorScale[0] = 1.0f;
    settings.motionVectorScale[1] = 1.0f;
    settings.motionVectorScale[2] = 1.0f;
    settings.resourceSize[0] = static_cast<uint16_t>(input->width);
    settings.resourceSize[1] = static_cast<uint16_t>(input->height);
    settings.resourceSizePrev[0] = static_cast<uint16_t>(input->previousWidth);
    settings.resourceSizePrev[1] = static_cast<uint16_t>(input->previousHeight);
    settings.rectSize[0] = static_cast<uint16_t>(input->width);
    settings.rectSize[1] = static_cast<uint16_t>(input->height);
    settings.rectSizePrev[0] = static_cast<uint16_t>(input->previousWidth);
    settings.rectSizePrev[1] = static_cast<uint16_t>(input->previousHeight);
    settings.timeDeltaBetweenFrames = std::max(input->timeDeltaMilliseconds, 0.0f);
    settings.denoisingRange = std::max(input->denoisingRange, 1.0f);
    settings.disocclusionThreshold = context->tuning.disocclusionThreshold;
    settings.frameIndex = input->frameIndex;
    settings.accumulationMode = input->restart != 0
        ? nrd::AccumulationMode::RESTART
        : nrd::AccumulationMode::CONTINUE;
    settings.isMotionVectorInWorldSpace = false;
    settings.enableValidation = input->enableValidation != 0;

    return static_cast<int32_t>(nrd::SetCommonSettings(*context->instance, settings));
}

PRIME_NRD_EXPORT int32_t primeNrdGetDispatches(
    PrimeNrdContext* context,
    PrimeNrdDispatchList* output)
{
    if (context == nullptr || output == nullptr)
        return -1;

    const nrd::Identifier identifier = PRIME_NRD_DENOISER_ID;
    const nrd::DispatchDesc* sourceDispatches = nullptr;
    uint32_t dispatchCount = 0;
    const nrd::Result result = nrd::GetComputeDispatches(
        *context->instance,
        &identifier,
        1,
        sourceDispatches,
        dispatchCount);
    if (result != nrd::Result::SUCCESS)
        return static_cast<int32_t>(result);

    context->dispatchResources.clear();
    context->dispatchResources.resize(dispatchCount);
    context->dispatches.clear();
    context->dispatches.resize(dispatchCount);
    for (uint32_t dispatchIndex = 0; dispatchIndex < dispatchCount; dispatchIndex++)
    {
        const nrd::DispatchDesc& source = sourceDispatches[dispatchIndex];
        std::vector<PrimeNrdResourceInfo>& resources = context->dispatchResources[dispatchIndex];
        resources.resize(source.resourcesNum);
        for (uint32_t resourceIndex = 0; resourceIndex < source.resourcesNum; resourceIndex++)
        {
            resources[resourceIndex].descriptorType =
                static_cast<uint32_t>(source.resources[resourceIndex].descriptorType);
            resources[resourceIndex].resourceType =
                static_cast<uint32_t>(source.resources[resourceIndex].type);
            resources[resourceIndex].indexInPool = source.resources[resourceIndex].indexInPool;
        }

        PrimeNrdDispatchInfo& destination = context->dispatches[dispatchIndex];
        destination.nameAddress = reinterpret_cast<uint64_t>(source.name);
        destination.resourcesAddress = reinterpret_cast<uint64_t>(resources.data());
        destination.constantDataAddress = reinterpret_cast<uint64_t>(source.constantBufferData);
        destination.resourcesNum = static_cast<uint32_t>(resources.size());
        destination.constantDataSize = source.constantBufferDataSize;
        destination.pipelineIndex = source.pipelineIndex;
        destination.gridWidth = source.gridWidth;
        destination.gridHeight = source.gridHeight;
    }

    output->dispatchesAddress = reinterpret_cast<uint64_t>(context->dispatches.data());
    output->dispatchesNum = static_cast<uint32_t>(context->dispatches.size());
    output->reserved = 0;
    return 0;
}

PRIME_NRD_EXPORT void primeNrdDestroy(PrimeNrdContext* context)
{
    delete context;
}
