package com.rtest.client;

import static com.rtest.client.RayTracingMaterialBuffer.canUseIncrementalMaterialWrite;
import static com.rtest.client.RayTracingMaterialBuffer.clearDynamicMaterialSlots;
import static com.rtest.client.RayTracingMaterialBuffer.dynamicMaterialBase;
import static com.rtest.client.RayTracingMaterialBuffer.hasChangedMaterialSections;
import static com.rtest.client.RayTracingMaterialBuffer.materialFloatCount;
import static com.rtest.client.RayTracingMaterialBuffer.restoreStaticMaterialData;
import static com.rtest.client.RayTracingMaterialBuffer.sameDynamicMaterialBase;
import static com.rtest.client.RayTracingMaterialBuffer.writeChangedSectionMaterials;
import static com.rtest.client.RayTracingMaterialBuffer.writeMaterialBuffer;

import com.rtest.client.RayTracingDynamicInstances.DynamicCachedBlas;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_PLAYER_MATERIAL_TRIANGLES;
import static com.rtest.client.RayTracingDynamicInstances.DYNAMIC_SLOT_MATERIAL_TRIANGLES;
import static com.rtest.client.RayTracingDynamicInstances.MATERIAL_FLOATS_PER_TRIANGLE;


import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.neoforged.neoforge.client.IRenderableSection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceOrHostAddressConstKHR;
import org.lwjgl.vulkan.VkDeviceOrHostAddressKHR;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.rtest.client.RayTracingScene.SceneGeometry;
import com.rtest.client.fsr.RtestFsr3;
import com.rtest.client.fsr.RtestFsr3Upscaler;
import com.rtest.client.fsr.RtestFsrCamera;
import com.rtest.client.fsr.RtestFsrSettings;

    final class RayTracingVulkanPass implements AutoCloseable {
        private static final Logger LOGGER = LogUtils.getLogger();
        private enum TemporalResetReason {
            GEOMETRY_PUBLICATION
        }

        /**
         * Block-entity identities whose captured geometry reached the TLAS in the last dispatched
         * frame. Everything else has no RT pixels and is replayed as vanilla raster after the copy.
         */
        private static volatile Set<Long> representedBlockEntities = Set.of();
        private final VulkanDevice device;
        // Physical-device AS limits are immutable for this pass/device lifetime.
        private final RayTracingSupport.Limits accelerationLimits;
        private final VkDevice vkDevice;
        // One pass-owned encoder is reused for incremental AS builds and RT/FSR submissions.
        // Keeping its command pools alive across frames lets a fence retire work without racing
        // per-frame encoder destruction on drivers with strict command-pool lifetime rules.
        private final VulkanCommandEncoder encoder;
        private final List<CachedBlas> sectionBlas;
        private final BlasCache blasCache;
        private final RayTracingDynamicInstances dynamicInstances;
        private final int dynamicSlotCapacity;
        private NativeBuffer instanceBuffer;
        private NativeBuffer dynamicMotionMetadataBuffer;
        private NativeBuffer scratchBuffer;
        private final NativeBuffer outputBuffer;
        private final NativeBuffer cameraBuffer;
        private NativeBuffer materialBuffer;
        private NativeBuffer lightDataBuffer;
        private NativeBuffer pbrBuffer;
        private PersistentRtLighting persistentLighting;
        private int lastEvaluationMode = -1;
        private boolean lastPersistentEnabled;
        // Optional compute-side terrain traversal resources. They are separate from the RT
        // descriptor set so the fixed ray shader ABI remains unchanged.
        private NativeBuffer terrainNodeMetadataBuffer;
        private NativeBuffer terrainBlasAddressBuffer;
        private NativeBuffer terrainTraversalParamsBuffer;
        private final long terrainTraversalDescriptorSetLayout;
        private final long terrainTraversalDescriptorPool;
        private final long terrainTraversalDescriptorSet;
        private final long[] terrainHiZDescriptorSets;
        private final long terrainTraversalPipelineLayout;
        private final long terrainTraversalPipeline;
        private final long terrainTraversalShaderModule;
        private final long terrainHiZPipeline;
        private final long terrainHiZShaderModule;
        private int terrainTraversalNodeCount;
        private int terrainTraversalNodeCapacity;
        private final boolean terrainTraversalEnabled;
        private final RayTracingPbrMaterials pbrMaterials;
        private int pbrMapCount;
        private SceneGeometry geometry;
        private final long atlasImageView;
        private final long atlasSampler;
        private final long itemAtlasImageView;
        private final long itemAtlasSampler;
        private final long targetImage;
        private final long targetImageView;
        private final GpuFormat outputFormat;
        private final int displayWidth;
        private final int displayHeight;
        private final int outputWidth;
        private final int outputHeight;
        private final RtestFsr3 fsr;
        private final NativeBuffer shaderBindingTable;
        private RayTracingAtmosphere atmosphere;
        private final NativeBuffer atmosphereCameraBuffer;
        private final boolean atmosphereRequested;
        private int atmosphereDensitySteps;
        private int requestedAtmosphereDensitySteps;
        private long atmosphereDensityChangedAt;
        private int lastAtmosphereAltitudeOffsetMeters = Integer.MIN_VALUE;
        private final boolean skyboxTextureEnabled;
        private float atmosphereEyeRadiusKm;
        private long pendingAtmosphereToken;
        private long pendingMoonAtmosphereToken;
        private float currentMoonDirectionY = 1.0F;
        private boolean currentMoonSkyActive;
        // Set per frame from the same gate the raygen uses (physical LUT + volume enabled), so the
        // post-NRD aerial composite never reads an L image the raygen left unwritten.
        private boolean aerialPerspectiveEnabled;
        // One TLAS is shared by all rays. VkAccelerationStructureInstanceKHR.mask plus the
        // ray cull mask in RayTracingShaders provide the primary/secondary visibility split:
        // the first-person body is available to continuation/reflection rays without occluding
        // the camera ray. This avoids a second TLAS, duplicate BLAS memory, and a second build.
        private AccelerationStructure topLevel;
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long pipelineLayout;
        private final long pipeline;
        private final long[] shaderModules;
        private final int sbtStride;
        private static final int BLAS_BUILDS_PER_FRAME = 16;
        static boolean needsIncrementalDynamicBuild(
            boolean topLevelBuilt,
            boolean blasBuilt,
            boolean pendingUpdate
        ) {
            // Before the first TLAS exists, every dynamic BLAS only needs one valid build.
            // Continuously animated block entities can mark already-built BLASes dirty every
            // frame; rebuilding those first would consume the whole batch forever and starve
            // the still-unbuilt static scene. Normal animation updates resume after the first
            // TLAS has been published.
            return !blasBuilt || (topLevelBuilt && pendingUpdate);
        }

        static boolean shouldAdoptDynamicFrame(
            boolean offlineActive,
            boolean topLevelBuilt,
            boolean missingFrame
        ) {
            // Freeze one complete dynamic snapshot while the first/replacement TLAS is being
            // assembled. Otherwise a crowded animated scene can replace unbuilt BLAS entries
            // faster than the bounded build batch can consume them.
            return missingFrame || (!offlineActive && topLevelBuilt);
        }
        private static final int PLAYER_SKIN_DESCRIPTOR_COUNT = 64;
        private final long[] livingEntityTextureViews = new long[PLAYER_SKIN_DESCRIPTOR_COUNT];
        private final long[] livingEntityTextureSamplers = new long[PLAYER_SKIN_DESCRIPTOR_COUNT];
        private final long[] livingEntityTextureViewsScratch = new long[PLAYER_SKIN_DESCRIPTOR_COUNT];
        private final long[] livingEntityTextureSamplersScratch = new long[PLAYER_SKIN_DESCRIPTOR_COUNT];
        private boolean topLevelBuilt;
        private boolean topLevelUpdatePending;
        private int frameIndex;
        private int lastPbrPackedMode = Integer.MIN_VALUE;
        private float lastPbrNormalStrength = Float.NaN;
        private float lastPbrEmissionStrength = Float.NaN;
        private float lastEmissionScale = Float.NaN;
        private float lastPbrWetnessStrength = Float.NaN;
        private float lastPbrParallaxDepth = Float.NaN;
        private int lastPbrParallaxFlags = Integer.MIN_VALUE;
        private float lastSkyboxTextureOpacity = Float.NaN;
        private boolean lastSkyboxDaylightOpacityEnabled;
        private boolean lastMoonEnabled;
        private float lastSunAngularRadiusDegrees = Float.NaN;
        private float lastSunIntensity = Float.NaN;
        private boolean lastSunDaylightIntensityEnabled;
        private float lastSunDaylightPeakIntensity = Float.NaN;
        private int lastSunShadowSamples = -1;
        private int lastMoonPhaseToken = -1;
        private float lastMoonIntensity = Float.NaN;
        private boolean lastVolumetricLightingEnabled;
        private float lastVolumetricLightingStrength = Float.NaN;
        private float lastVolumetricFogDensity = Float.NaN;
        private int lastVolumetricLightingQuality = Integer.MIN_VALUE;
        private long dispatchTimingFrame;
        private final RayTracingFrameTiming dispatchTiming = new RayTracingFrameTiming();
        private RtestFsrCamera previousFsrCamera;
        private DynamicEntityGeometry.Frame dynamicFrame;
        private boolean reportedPlayerAnimationUpdate;
        // Minecraft advances its world clock at 20 TPS. Keep the RT sun phase
        // pinned to that clock instead of allowing render FPS to create a second
        // time source between ClientLevel ticks.
        private ClientLevel sunClockLevel;
        private long sunClockTicks = Long.MIN_VALUE;
        private float sunClockOffset = Float.NaN;
        private float synchronizedSunAngle;
        private float currentSunDirectionX;
        private float currentSunDirectionY = 1.0F;
        private float currentSunDirectionZ;
        private boolean presentedFrame;
        private boolean terrainTraversalPrimed;
        private boolean terrainHiZInitialized;
        private int lastCenterPixel;
        // Keep one RT submission in flight. The encoder and all mapped scene buffers are
        // persistent resources, so the next frame must retire this submission before reusing
        // them. Geometry/resource publication can also use this retirement point if a submission
        // is still pending after an early-return path.
        private GpuFence pendingFrameFence;
        private final RtBlasRetirement dynamicBlasRetirement = new RtBlasRetirement();
        private int pendingFrameTimingFrame;
        private int pendingFrameGpuIndex;
        private boolean closed;
        // 0..3 retain display RT/post/total; 4..5 bracket traversal/AS, 6..7 sky LUT,
        // 8..9 snapshot preparation, 10..11 independent world lighting dispatch.
        private static final int GPU_TIMESTAMP_COUNT = 12;
        private final long gpuTimestampQueryPool;
        private final double gpuTimestampPeriodNs;
        private final boolean gpuTimestampsAvailable;

        private RayTracingVulkanPass(
            VulkanDevice device,
            RayTracingSupport.Limits accelerationLimits,
            VulkanCommandEncoder encoder,
            List<CachedBlas> sectionBlas,
            BlasCache blasCache,
            RayTracingDynamicInstances dynamicInstances,
            int dynamicSlotCapacity,
            NativeBuffer instanceBuffer,
            NativeBuffer dynamicMotionMetadataBuffer,
            NativeBuffer scratchBuffer,
            NativeBuffer outputBuffer,
            NativeBuffer cameraBuffer,
            NativeBuffer materialBuffer,
            NativeBuffer lightDataBuffer,
            NativeBuffer pbrBuffer,
            NativeBuffer terrainNodeMetadataBuffer,
            NativeBuffer terrainBlasAddressBuffer,
            NativeBuffer terrainTraversalParamsBuffer,
            long terrainTraversalDescriptorSetLayout,
            long terrainTraversalDescriptorPool,
            long terrainTraversalDescriptorSet,
            long[] terrainHiZDescriptorSets,
            long terrainTraversalPipelineLayout,
            long terrainTraversalPipeline,
            long terrainTraversalShaderModule,
            long terrainHiZPipeline,
            long terrainHiZShaderModule,
            int terrainTraversalNodeCount,
            boolean terrainTraversalEnabled,
            RayTracingPbrMaterials pbrMaterials,
            SceneGeometry geometry,
            long atlasImageView,
            long atlasSampler,
            long itemAtlasImageView,
            long itemAtlasSampler,
            long targetImage,
            long targetImageView,
            GpuFormat outputFormat,
            int displayWidth,
            int displayHeight,
            int outputWidth,
            int outputHeight,
            RtestFsr3 fsr,
            NativeBuffer shaderBindingTable,
            AccelerationStructure topLevel,
            long descriptorSetLayout,
            long descriptorPool,
            long descriptorSet,
            long pipelineLayout,
            long pipeline,
            long[] shaderModules,
            int sbtStride,
            DynamicEntityGeometry.Frame dynamicFrame,
            RayTracingAtmosphere atmosphere,
            NativeBuffer atmosphereCameraBuffer,
            boolean atmosphereRequested
        ) {
            this.atmosphere = atmosphere;
            this.atmosphereDensitySteps = atmosphereDensitySteps();
            this.requestedAtmosphereDensitySteps = this.atmosphereDensitySteps;
            this.atmosphereCameraBuffer = atmosphereCameraBuffer;
            this.atmosphereRequested = atmosphereRequested;
            this.skyboxTextureEnabled = RayTracingClientConfig.INSTANCE.skyboxTextureEnabled.get();
            this.device = device;
            this.accelerationLimits = accelerationLimits;
            this.vkDevice = device.vkDevice();
            long queryPool = 0L;
            double timestampPeriod = 0.0;
            boolean timestampAvailable = false;
            try (MemoryStack timestampStack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties timestampProperties = VkPhysicalDeviceProperties.calloc(timestampStack);
                VK12.vkGetPhysicalDeviceProperties(this.vkDevice.getPhysicalDevice(), timestampProperties);
                timestampPeriod = timestampProperties.limits().timestampPeriod();
                boolean timestampSupported = timestampProperties.limits().timestampComputeAndGraphics();
                if (timestampPeriod > 0.0 && timestampSupported) {
                    VkQueryPoolCreateInfo queryInfo = VkQueryPoolCreateInfo.calloc(timestampStack)
                        .sType$Default()
                        .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP)
                        .queryCount(GPU_TIMESTAMP_COUNT);
                    LongBuffer queryHandle = timestampStack.mallocLong(1);
                    timestampAvailable = VK10.vkCreateQueryPool(
                        this.vkDevice, queryInfo, null, queryHandle) == VK10.VK_SUCCESS;
                    if (timestampAvailable) {
                        queryPool = queryHandle.get(0);
                    }
                }
            }
            this.gpuTimestampQueryPool = queryPool;
            this.gpuTimestampPeriodNs = timestampPeriod;
            this.gpuTimestampsAvailable = timestampAvailable;
            if (!timestampAvailable) {
                LOGGER.info("RTest GPU timestamps unavailable; using CPU timing only");
            }
            this.encoder = encoder;
            this.sectionBlas = sectionBlas;
            this.blasCache = blasCache;
            this.dynamicInstances = dynamicInstances;
            this.dynamicSlotCapacity = dynamicSlotCapacity;
            this.instanceBuffer = instanceBuffer;
            this.dynamicMotionMetadataBuffer = dynamicMotionMetadataBuffer;
            this.scratchBuffer = scratchBuffer;
            this.outputBuffer = outputBuffer;
            this.cameraBuffer = cameraBuffer;
            this.materialBuffer = materialBuffer;
            this.lightDataBuffer = lightDataBuffer;
            this.pbrBuffer = pbrBuffer;
            this.terrainNodeMetadataBuffer = terrainNodeMetadataBuffer;
            this.terrainBlasAddressBuffer = terrainBlasAddressBuffer;
            this.terrainTraversalParamsBuffer = terrainTraversalParamsBuffer;
            this.terrainTraversalDescriptorSetLayout = terrainTraversalDescriptorSetLayout;
            this.terrainTraversalDescriptorPool = terrainTraversalDescriptorPool;
            this.terrainTraversalDescriptorSet = terrainTraversalDescriptorSet;
            this.terrainHiZDescriptorSets = terrainHiZDescriptorSets.clone();
            this.terrainTraversalPipelineLayout = terrainTraversalPipelineLayout;
            this.terrainTraversalPipeline = terrainTraversalPipeline;
            this.terrainTraversalShaderModule = terrainTraversalShaderModule;
            this.terrainHiZPipeline = terrainHiZPipeline;
            this.terrainHiZShaderModule = terrainHiZShaderModule;
            this.terrainTraversalNodeCount = terrainTraversalNodeCount;
            this.terrainTraversalNodeCapacity = terrainNodeMetadataBuffer == null ? 0
                : (int)(terrainNodeMetadataBuffer.size / RayTracingTerrainTraversalAbi.NODE_METADATA_BYTES);
            this.terrainTraversalEnabled = terrainTraversalEnabled;
            this.pbrMaterials = pbrMaterials;
            this.pbrMapCount = pbrMaterials == null ? geometry.pbrData[0] : pbrMaterials.loadedMapCount();
            this.geometry = geometry;
            this.atlasImageView = atlasImageView;
            this.atlasSampler = atlasSampler;
            this.itemAtlasImageView = itemAtlasImageView;
            this.itemAtlasSampler = itemAtlasSampler;
            this.targetImage = targetImage;
            this.targetImageView = targetImageView;
            this.outputFormat = outputFormat;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.fsr = fsr;
            this.shaderBindingTable = shaderBindingTable;
            this.topLevel = topLevel;
            this.descriptorSetLayout = descriptorSetLayout;
            this.descriptorPool = descriptorPool;
            this.descriptorSet = descriptorSet;
            this.pipelineLayout = pipelineLayout;
            this.pipeline = pipeline;
            this.shaderModules = shaderModules;
            this.sbtStride = sbtStride;
            this.dynamicFrame = dynamicFrame;
        }

        private GpuLightTreeBuilder gpuLightTreeBuilder;

        private void buildLightTree(NativeBuffer buffer, RayTracingLightTree.Data data) {
            if (!data.gpuBuild()) return;
            if (gpuLightTreeBuilder == null) gpuLightTreeBuilder = new GpuLightTreeBuilder(device);
            gpuLightTreeBuilder.buildOrFallback(buffer, data);
        }

        static final class BlasCache implements AutoCloseable {
            private final Map<SectionKey, CachedBlas> entries = new HashMap<>();
            private final List<CachedBlas> retired = new ArrayList<>();

            CachedBlas acquire(VulkanDevice device, SceneGeometry.SectionGeometry section) {
                SectionKey key = new SectionKey(section.originX, section.originY, section.originZ,
                    section.terrainNodeKey().level());
                CachedBlas current = entries.get(key);
                if (current != null && current.vertexFingerprint == section.vertexFingerprint()
                    && current.triangleCount == section.triangleCount()) {
                    return current;
                }
                // Keep the old value alive until the caller commits the complete geometry
                // transaction. The currently bound TLAS may still reference it if a later
                // allocation or descriptor update fails.
                int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                NativeBuffer vertexBuffer = NativeBuffer.create(device, (long)section.vertices.length * Float.BYTES, usage, true);
                AccelerationStructure bottomLevel = null;
                try {
                    try (NativeBuffer.Mapped mapped = vertexBuffer.map()) {
                        mapped.buffer().asFloatBuffer().put(section.vertices);
                    }
                    bottomLevel = AccelerationStructure.createBottomLevel(device, vertexBuffer, section.triangleCount);
                    current = new CachedBlas(key, section.vertexFingerprint(), section.triangleCount, vertexBuffer, bottomLevel);
                    return current;
                } catch (Throwable throwable) {
                    if (bottomLevel != null) {
                        bottomLevel.close();
                    }
                    vertexBuffer.close();
                    throw throwable;
                }
            }

            /** Publishes candidates only after the surrounding pass transaction succeeded. */
            void commit(List<CachedBlas> candidates) {
                validateUniqueBlasKeys(candidates);
                for (CachedBlas candidate : candidates) {
                    CachedBlas previous = entries.put(candidate.key, candidate);
                    if (previous != null && previous != candidate) {
                        retired.add(previous);
                    }
                }
            }

            /** Closes only candidates that were not already owned by the shared cache. */
            void abort(List<CachedBlas> candidates) {
                for (CachedBlas candidate : candidates) {
                    if (entries.get(candidate.key) != candidate) {
                        candidate.close();
                    }
                }
            }

            void trim(Set<SectionKey> active) {
                if (entries.size() <= 2048) {
                    return;
                }
                var iterator = entries.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<SectionKey, CachedBlas> entry = iterator.next();
                    if (!active.contains(entry.getKey())) {
                        retired.add(entry.getValue());
                        iterator.remove();
                    }
                }
            }

            /** Call only after the TLAS build/update using its source instance set has fenced. */
            void retireCompleted() {
                for (CachedBlas entry : retired) entry.close();
                retired.clear();
            }

            @Override
            public void close() {
                Throwable failure = null;
                for (CachedBlas entry : entries.values()) {
                    try {
                        entry.close();
                    } catch (Throwable cleanupFailure) {
                        if (failure == null) {
                            failure = cleanupFailure;
                        } else {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                entries.clear();
                for (CachedBlas entry : retired) {
                    try {
                        entry.close();
                    } catch (Throwable cleanupFailure) {
                        if (failure == null) {
                            failure = cleanupFailure;
                        } else {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                retired.clear();
                rethrow(failure);
            }
        }

        private record SectionKey(int x, int y, int z, int lodLevel) {
        }

        static int tlasInstanceCapacity(int requiredInstances, long maxInstanceCount) {
            if (requiredInstances < 1 || maxInstanceCount < requiredInstances) {
                throw new IllegalArgumentException("TLAS instance count exceeds device capacity");
            }
            long maximum = Math.min(maxInstanceCount, Integer.MAX_VALUE);
            long slack = Math.min(maximum - requiredInstances,
                Math.max(64L, (long)requiredInstances / 8L));
            long desired = requiredInstances + slack;
            long rounded = (desired + 63L) & ~63L;
            return (int)Math.max(requiredInstances, Math.min(maximum, rounded));
        }

        private static final class CachedBlas implements AutoCloseable {
            private final SectionKey key;
            private final int vertexFingerprint;
            private final int triangleCount;
            private final NativeBuffer vertexBuffer;
            private final AccelerationStructure bottomLevel;
            private boolean built;
            private boolean closed;

            private CachedBlas(SectionKey key, int vertexFingerprint, int triangleCount, NativeBuffer vertexBuffer,
                               AccelerationStructure bottomLevel) {
                this.key = key;
                this.vertexFingerprint = vertexFingerprint;
                this.triangleCount = triangleCount;
                this.vertexBuffer = vertexBuffer;
                this.bottomLevel = bottomLevel;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    this.bottomLevel.close();
                } finally {
                    this.vertexBuffer.close();
                }
            }
        }

        /** A duplicate section origin would make cache commit close a live candidate in-place. */
        private static void validateUniqueBlasKeys(List<CachedBlas> candidates) {
            Set<SectionKey> seen = new HashSet<>();
            for (CachedBlas candidate : candidates) {
                if (!seen.add(candidate.key)) {
                    throw new IllegalStateException("Duplicate section BLAS key: " + candidate.key);
                }
            }
        }

        static RayTracingVulkanPass create(
            VulkanDevice device,
            int outputWidth,
            int outputHeight,
            int displayWidth,
            int displayHeight,
            long targetImage,
            long targetImageView,
            GpuFormat outputFormat,
            SceneGeometry geometry,
            long atlasImageView,
            long atlasSampler,
            RayTracingSkybox skybox,
            RayTracingPbrMaterials pbrMaterials,
            BlasCache blasCache,
            RtestFsr3 fsr,
            DynamicEntityGeometry.Frame dynamicFrame,
            boolean terrainTraversalEnabled
        ) {
            if (outputWidth <= 0 || outputHeight <= 0) {
                throw new IllegalArgumentException("Ray-tracing output dimensions must be positive");
            }
            if (geometry == null || geometry.triangleCount <= 0) {
                throw new IllegalArgumentException("Ray-tracing geometry must contain triangles");
            }
            if (fsr == null || fsr.renderWidth() != outputWidth || fsr.renderHeight() != outputHeight
                || displayWidth <= 0 || displayHeight <= 0) {
                throw new IllegalArgumentException("FSR 3 resources must match the RT and display extents");
            }
            var nrd = fsr.nrd();
            VkDevice vkDevice = device.vkDevice();
            long[] itemAtlasBinding = resolveTextureHandles(TextureAtlas.LOCATION_ITEMS, atlasImageView, atlasSampler);
            long itemAtlasImageView = itemAtlasBinding[0];
            long itemAtlasSampler = itemAtlasBinding[1];
            List<CachedBlas> sectionBlas = new ArrayList<>();
            int dynamicSlotCapacity = RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get() ? 64 : 0;
            RayTracingDynamicInstances dynamicInstances = new RayTracingDynamicInstances(dynamicSlotCapacity);
            NativeBuffer instanceBuffer = null;
            NativeBuffer dynamicMotionMetadataBuffer = null;
            NativeBuffer scratchBuffer = null;
            NativeBuffer outputBuffer = null;
            NativeBuffer cameraBuffer = null;
            NativeBuffer materialBuffer = null;
            NativeBuffer lightDataBuffer = null;
            GpuLightTreeBuilder lightTreeBuilder = null;
            NativeBuffer pbrBuffer = null;
            PersistentRtLighting persistentLighting = null;
            NativeBuffer terrainNodeMetadataBuffer = null;
            NativeBuffer terrainBlasAddressBuffer = null;
            NativeBuffer terrainTraversalParamsBuffer = null;
            NativeBuffer shaderBindingTable = null;
            NativeBuffer atmosphereCameraBuffer = null;
            RayTracingAtmosphere atmosphere = null;
            boolean atmosphereRequested = RayTracingClientConfig.INSTANCE.primeAtmosphereEnabled.get();
            AccelerationStructure topLevel = null;
            VulkanCommandEncoder encoder = null;
            long descriptorSetLayout = 0L;
            long descriptorPool = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            long terrainTraversalDescriptorSetLayout = 0L;
            long terrainTraversalDescriptorPool = 0L;
            long terrainTraversalDescriptorSet = 0L;
            long[] terrainHiZDescriptorSets = new long[0];
            long terrainTraversalPipelineLayout = 0L;
            long terrainTraversalPipeline = 0L;
            long terrainTraversalShaderModule = 0L;
            long terrainHiZPipeline = 0L;
            long terrainHiZShaderModule = 0L;
            int terrainTraversalNodeCount = 0;
            long[] shaderModules = new long[9];
            RayTracingSupport.Limits limits = RayTracingSupport.queryLimits(device);
            if (limits == null || limits.maxRayRecursionDepth() < 1) {
                throw new IllegalStateException("Ray-tracing device must support recursion depth 1");
            }

            try {
                int geometryUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                Set<SectionKey> activeKeys = new HashSet<>();
                for (SceneGeometry.SectionGeometry section : geometry.sections) {
                    CachedBlas cached = blasCache.acquire(device, section);
                    sectionBlas.add(cached);
                    activeKeys.add(cached.key);
                }
                validateUniqueBlasKeys(sectionBlas);
                int initialInstanceCount = sectionBlas.size() + dynamicSlotCapacity;
                int initialTopLevelCapacity = tlasInstanceCapacity(
                    initialInstanceCount, limits.maxInstanceCount());
                if (RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
                    DynamicPlaceholderGeometry.Mesh playerMesh = DynamicPlaceholderGeometry.box(0.25F, 0.6F, 1.0F, false);
                    DynamicPlaceholderGeometry.Mesh itemMesh = DynamicPlaceholderGeometry.box(1.0F, 0.35F, 0.05F, true);
                    dynamicInstances.addPlaceholder(device, 0x504C415945524CL, playerMesh);
                    dynamicInstances.addPlaceholder(device, 0x4C4956494E474CL, playerMesh);
                    dynamicInstances.addPlaceholder(device, 0x4954454D4CL, itemMesh);
                    com.mojang.logging.LogUtils.getLogger().info(
                        "RTest allocated {} dynamic placeholder BLAS resources", dynamicInstances.blases().size());
                }
                instanceBuffer = NativeBuffer.create(
                    device,
                    (long)initialTopLevelCapacity * VkAccelerationStructureInstanceKHR.SIZEOF,
                    geometryUsage,
                    true
                );
                dynamicMotionMetadataBuffer = NativeBuffer.create(
                    device,
                    (long)Math.max(1, dynamicSlotCapacity)
                        * DynamicTlasInstanceWriter.MOTION_METADATA_BYTES_PER_SLOT,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true
                );
                writeInstanceBuffer(instanceBuffer, geometry, sectionBlas,
                    dynamicSlotCapacity, initialTopLevelCapacity);

                topLevel = AccelerationStructure.createTopLevel(device, instanceBuffer,
                    initialTopLevelCapacity);
                long scratchSize = topLevel.scratchSize;
                for (CachedBlas cached : sectionBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                for (DynamicCachedBlas cached : dynamicInstances.blases()) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                scratchBuffer = NativeBuffer.create(
                    device,
                    VulkanAccelerationResources.scratchBufferSize(scratchSize, Math.max(1L,
                        limits.minScratchAlignment())),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    false
                );
                long outputSize = Math.multiplyExact(Math.multiplyExact((long)outputWidth, (long)outputHeight), 4L);
                outputBuffer = NativeBuffer.create(
                    device,
                    outputSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true
                );
                cameraBuffer = NativeBuffer.create(device, 304, VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
                persistentLighting = new PersistentRtLighting(device);
                long materialFloatCount = materialFloatCount(geometry, dynamicSlotCapacity);
                materialBuffer = NativeBuffer.create(
                    device,
                    RayTracingMaterialBuffer.allocationBytes(materialFloatCount * Float.BYTES, 0),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true
                );
                writeMaterialBuffer(materialBuffer, geometry, dynamicSlotCapacity);
                lightDataBuffer = uploadLightDataBuffer(device, geometry.lightTree);
                if (geometry.lightTree.gpuBuild()) {
                    lightTreeBuilder = new GpuLightTreeBuilder(device);
                    lightTreeBuilder.buildOrFallback(lightDataBuffer, geometry.lightTree);
                }

                int[] initialPbrData = pbrMaterials == null ? geometry.pbrData : pbrMaterials.packedData();
                long pbrBufferSize = pbrMaterials == null
                    ? (long)initialPbrData.length * Integer.BYTES
                    : pbrMaterials.gpuBufferSizeBytes();
                pbrBuffer = NativeBuffer.create(
                    device,
                    pbrBufferSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true
                );
                try (NativeBuffer.Mapped mapped = pbrBuffer.map()) {
                    mapped.buffer().asIntBuffer().put(initialPbrData);
                }

                if (terrainTraversalEnabled) {
                    List<RayTracingTerrainTraversalAbi.NodeMetadata> traversalNodes =
                        buildTerrainTraversalMetadata(geometry);
                    terrainTraversalNodeCount = traversalNodes.size();
                    int terrainTraversalNodeCapacity = terrainTraversalCapacity(terrainTraversalNodeCount);
                    terrainNodeMetadataBuffer = NativeBuffer.create(device,
                        (long)terrainTraversalNodeCapacity * RayTracingTerrainTraversalAbi.NODE_METADATA_BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                    terrainBlasAddressBuffer = NativeBuffer.create(device,
                        (long)terrainTraversalNodeCapacity * Long.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                    writeTerrainTraversalBuffers(geometry, sectionBlas, traversalNodes,
                        terrainNodeMetadataBuffer, terrainBlasAddressBuffer);
                    // std140: mat4 + four vec4 values (camera, viewport, config, sceneOrigin)
                    // plus one vec4 dummy BLAS address, for a stable 144-byte contract.
                    terrainTraversalParamsBuffer = NativeBuffer.create(device, 144,
                        VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
                    ShaderModule traversal = ShaderModule.create(device,
                        loadShaderResource("rtest/shaders/terrain_traversal.comp"),
                        Shaderc.shaderc_glsl_compute_shader);
                    terrainTraversalShaderModule = traversal.handle;

                    try (MemoryStack traversalStack = MemoryStack.stackPush()) {
                        VkDescriptorSetLayoutBinding.Buffer traversalBindings =
                            VkDescriptorSetLayoutBinding.calloc(6, traversalStack);
                        traversalBindings.get(0).binding(0)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        traversalBindings.get(1).binding(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        traversalBindings.get(2).binding(2)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        traversalBindings.get(3).binding(3)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        traversalBindings.get(4).binding(4)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        traversalBindings.get(5).binding(5)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                        VkDescriptorSetLayoutCreateInfo traversalLayoutInfo =
                            VkDescriptorSetLayoutCreateInfo.calloc(traversalStack).sType$Default()
                                .pBindings(traversalBindings);
                        LongBuffer traversalHandle = traversalStack.callocLong(1);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkCreateDescriptorSetLayout(vkDevice, traversalLayoutInfo, null, traversalHandle),
                            "Failed to create terrain traversal descriptor set layout");
                        terrainTraversalDescriptorSetLayout = traversalHandle.get(0);

                        int mipLevels = fsr.terrainHiZMipLevels();
                        int descriptorSetCount = 1 + Math.max(0, mipLevels - 1);
                        VkDescriptorPoolSize.Buffer traversalPoolSizes = VkDescriptorPoolSize.calloc(4, traversalStack);
                        traversalPoolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(3 * descriptorSetCount);
                        traversalPoolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(descriptorSetCount);
                        traversalPoolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(descriptorSetCount);
                        traversalPoolSizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(descriptorSetCount);
                        VkDescriptorPoolCreateInfo traversalPoolInfo = VkDescriptorPoolCreateInfo.calloc(traversalStack)
                            .sType$Default().maxSets(descriptorSetCount).pPoolSizes(traversalPoolSizes);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkCreateDescriptorPool(vkDevice, traversalPoolInfo, null, traversalHandle),
                            "Failed to create terrain traversal descriptor pool");
                        terrainTraversalDescriptorPool = traversalHandle.get(0);
                        LongBuffer setLayouts = traversalStack.mallocLong(descriptorSetCount);
                        for (int index = 0; index < descriptorSetCount; index++) {
                            setLayouts.put(terrainTraversalDescriptorSetLayout);
                        }
                        setLayouts.flip();
                        VkDescriptorSetAllocateInfo traversalAllocate = VkDescriptorSetAllocateInfo.calloc(traversalStack)
                            .sType$Default().descriptorPool(terrainTraversalDescriptorPool)
                            .pSetLayouts(setLayouts);
                        LongBuffer setHandles = traversalStack.callocLong(descriptorSetCount);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkAllocateDescriptorSets(vkDevice, traversalAllocate, setHandles),
                            "Failed to allocate terrain traversal descriptor sets");
                        terrainTraversalDescriptorSet = setHandles.get(0);
                        terrainHiZDescriptorSets = new long[mipLevels];
                        for (int level = 1; level < mipLevels; level++) {
                            terrainHiZDescriptorSets[level] = setHandles.get(level);
                        }

                        VkDescriptorBufferInfo.Buffer nodeInfo = VkDescriptorBufferInfo.calloc(1, traversalStack)
                            .buffer(terrainNodeMetadataBuffer.buffer).offset(0)
                            .range(terrainNodeMetadataBuffer.size);
                        VkDescriptorBufferInfo.Buffer addressInfo = VkDescriptorBufferInfo.calloc(1, traversalStack)
                            .buffer(terrainBlasAddressBuffer.buffer).offset(0)
                            .range(terrainBlasAddressBuffer.size);
                        VkDescriptorBufferInfo.Buffer traversalParamsInfo = VkDescriptorBufferInfo.calloc(1, traversalStack)
                            .buffer(terrainTraversalParamsBuffer.buffer).offset(0)
                            .range(144);
                        VkDescriptorBufferInfo.Buffer instanceInfo = VkDescriptorBufferInfo.calloc(1, traversalStack)
                            .buffer(instanceBuffer.buffer).offset(0).range(instanceBuffer.size);
                        VkDescriptorImageInfo.Buffer sourceImages = VkDescriptorImageInfo.calloc(descriptorSetCount, traversalStack);
                        VkDescriptorImageInfo.Buffer targetImages = VkDescriptorImageInfo.calloc(descriptorSetCount, traversalStack);
                        VkWriteDescriptorSet.Buffer traversalWrites = VkWriteDescriptorSet.calloc(6 * descriptorSetCount, traversalStack);
                        int writeIndex = 0;
                        for (int setIndex = 0; setIndex < descriptorSetCount; setIndex++) {
                            long set = setHandles.get(setIndex);
                            int reductionLevel = setIndex;
                            long sourceView = reductionLevel == 0
                                ? fsr.terrainHiZView() : fsr.terrainHiZMipView(reductionLevel - 1);
                            int targetLevel = reductionLevel == 0 ? Math.min(1, mipLevels - 1) : reductionLevel;
                            sourceImages.get(setIndex).sampler(atlasSampler).imageView(sourceView)
                                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                            targetImages.get(setIndex).imageView(fsr.terrainHiZMipView(targetLevel))
                                .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(0)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(nodeInfo);
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(1)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(addressInfo);
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(2)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                                .pImageInfo(VkDescriptorImageInfo.create(sourceImages.get(setIndex).address(), 1));
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(3)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(traversalParamsInfo);
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(4)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(instanceInfo);
                            traversalWrites.get(writeIndex++).sType$Default().dstSet(set).dstBinding(5)
                                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .pImageInfo(VkDescriptorImageInfo.create(targetImages.get(setIndex).address(), 1));
                        }
                        VK10.vkUpdateDescriptorSets(vkDevice, traversalWrites, null);

                        VkPushConstantRange.Buffer traversalPushConstants = VkPushConstantRange.calloc(1, traversalStack)
                            .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
                        VkPipelineLayoutCreateInfo traversalPipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(traversalStack)
                            .sType$Default().pSetLayouts(traversalStack.longs(terrainTraversalDescriptorSetLayout))
                            .pPushConstantRanges(traversalPushConstants);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkCreatePipelineLayout(vkDevice, traversalPipelineLayoutInfo, null, traversalHandle),
                            "Failed to create terrain traversal pipeline layout");
                        terrainTraversalPipelineLayout = traversalHandle.get(0);
                        VkPipelineShaderStageCreateInfo traversalStage = VkPipelineShaderStageCreateInfo.calloc(traversalStack)
                            .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(terrainTraversalShaderModule).pName(traversalStack.UTF8("main"));
                        VkComputePipelineCreateInfo.Buffer traversalPipelineInfo = VkComputePipelineCreateInfo.calloc(1, traversalStack);
                        traversalPipelineInfo.get(0).sType$Default().stage(traversalStage).layout(terrainTraversalPipelineLayout);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkCreateComputePipelines(vkDevice, 0L, traversalPipelineInfo, null, traversalHandle),
                            "Failed to create terrain traversal compute pipeline");
                        terrainTraversalPipeline = traversalHandle.get(0);
                        ShaderModule hizShader = ShaderModule.create(device,
                            loadShaderResource("rtest/shaders/terrain_hiz.comp"),
                            Shaderc.shaderc_glsl_compute_shader);
                        terrainHiZShaderModule = hizShader.handle;
                        VkPipelineShaderStageCreateInfo hizStage = VkPipelineShaderStageCreateInfo.calloc(traversalStack)
                            .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(terrainHiZShaderModule).pName(traversalStack.UTF8("main"));
                        VkComputePipelineCreateInfo.Buffer hizPipelineInfo = VkComputePipelineCreateInfo.calloc(1, traversalStack);
                        hizPipelineInfo.get(0).sType$Default().stage(hizStage).layout(terrainTraversalPipelineLayout);
                        VulkanUtils.crashIfFailure(device,
                            VK10.vkCreateComputePipelines(vkDevice, 0L, hizPipelineInfo, null, traversalHandle),
                            "Failed to create terrain Hi-Z compute pipeline");
                        terrainHiZPipeline = traversalHandle.get(0);
                    }
                }

                if (atmosphereRequested) {
                    atmosphere = createOptionalAtmosphere(device, atmosphereDensitySteps());
                    if (atmosphere != null) {
                        atmosphereCameraBuffer = NativeBuffer.create(
                            device, 16, VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true);
                    }
                }
                boolean physicalAtmosphere = atmosphere != null;
                String raygenSource = physicalAtmosphere
                    ? RayTracingShaders.RAYGEN_SHADER.replace("#version 460",
                        "#version 460\n#define RTEST_ATMOSPHERE_LUT 1")
                    : RayTracingShaders.RAYGEN_SHADER;
                ShaderModule raygen = ShaderModule.create(device, raygenSource, Shaderc.shaderc_glsl_raygen_shader);
                shaderModules[0] = raygen.handle;
                ShaderModule miss = ShaderModule.create(device, RayTracingShaders.MISS_SHADER, Shaderc.shaderc_glsl_miss_shader);
                shaderModules[1] = miss.handle;
                ShaderModule shadowMiss = ShaderModule.create(device, RayTracingShaders.SHADOW_MISS_SHADER, Shaderc.shaderc_glsl_miss_shader);
                shaderModules[2] = shadowMiss.handle;
                ShaderModule closestHit = ShaderModule.create(device, RayTracingShaders.CLOSEST_HIT_SHADER, Shaderc.shaderc_glsl_closesthit_shader);
                shaderModules[3] = closestHit.handle;
                ShaderModule anyHit = ShaderModule.create(device, RayTracingShaders.ANY_HIT_SHADER, Shaderc.shaderc_glsl_anyhit_shader);
                shaderModules[4] = anyHit.handle;
                ShaderModule shadowClosestHit = ShaderModule.create(device, RayTracingShaders.SHADOW_CLOSEST_HIT_SHADER, Shaderc.shaderc_glsl_closesthit_shader);
                shaderModules[5] = shadowClosestHit.handle;
                ShaderModule shadowAnyHit = ShaderModule.create(device, RayTracingShaders.SHADOW_ANY_HIT_SHADER, Shaderc.shaderc_glsl_anyhit_shader);
                shaderModules[6] = shadowAnyHit.handle;
                ShaderModule skyCdfMiss = ShaderModule.create(device, SkyImportanceShader.MISS, Shaderc.shaderc_glsl_miss_shader);
                shaderModules[7] = skyCdfMiss.handle;
                ShaderModule skyCdfHit = ShaderModule.create(device, SkyImportanceShader.HIT, Shaderc.shaderc_glsl_closesthit_shader);
                shaderModules[8] = skyCdfHit.handle;

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(physicalAtmosphere ? 42 : 31, stack);
                    bindings.get(physicalAtmosphere ? 41 : 30).binding(41)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                        .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(physicalAtmosphere ? 39 : 28).binding(39)
                        .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(physicalAtmosphere ? 40 : 29).binding(40)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(physicalAtmosphere ? 38 : 27).binding(38)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                        .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(0).binding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    bindings.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                        );
                    bindings.get(3).binding(3).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
                        );
                    bindings.get(4).binding(4).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR
                        );
                    bindings.get(5).binding(5).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                    bindings.get(6).binding(6).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    for (int binding = 7; binding <= 16; binding++) {
                        bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    }
                    bindings.get(17).binding(17).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(PLAYER_SKIN_DESCRIPTOR_COUNT).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                    bindings.get(18).binding(18).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                    bindings.get(19).binding(19).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                    bindings.get(20).binding(20).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    for (int binding = 21; binding <= 25; binding++) {
                        bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    }
                    bindings.get(26).binding(26).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(
                            KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR
                                | KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                    if (physicalAtmosphere) {
                        for (int binding = 27; binding <= 28; binding++) {
                            bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        }
                        bindings.get(29).binding(29).descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        // Physical finite-segment aerial perspective: L output, pinned medium, the
                        // bank-zero optical-depth and scattering-source samplers, and the
                        // mean/ground/high multiple-scattering images.
                        bindings.get(30).binding(30).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        bindings.get(31).binding(31).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        bindings.get(32).binding(32).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        bindings.get(33).binding(33).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        for (int binding = 34; binding <= 36; binding++) {
                            bindings.get(binding).binding(binding).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                                .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                        }
                        bindings.get(37).binding(37).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .descriptorCount(1).stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                    }
                    VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                        .pBindings(bindings);
                    LongBuffer handle = stack.callocLong(1);
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkCreateDescriptorSetLayout(vkDevice, layoutInfo, null, handle),
                        "Failed to create ray-tracing descriptor set layout"
                    );
                    descriptorSetLayout = handle.get(0);

                    // Vulkan requires one pool-size entry per descriptor type. Keep the counts
                    // consolidated instead of repeating STORAGE_BUFFER/COMBINED_IMAGE_SAMPLER.
                    VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
                    poolSizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                        .descriptorCount(2);
                    poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(9);
                    poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(physicalAtmosphere ? 2 : 1);
                    poolSizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(5 + PLAYER_SKIN_DESCRIPTOR_COUNT);
                    poolSizes.get(4).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(physicalAtmosphere ? 23 : 16);
                    VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                        .maxSets(1).pPoolSizes(poolSizes);
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkCreateDescriptorPool(vkDevice, poolInfo, null, handle),
                        "Failed to create ray-tracing descriptor pool"
                    );
                    descriptorPool = handle.get(0);

                    VkDescriptorSetAllocateInfo allocateInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorSetLayout));
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkAllocateDescriptorSets(vkDevice, allocateInfo, handle),
                        "Failed to allocate ray-tracing descriptor set"
                    );
                    long descriptorSet = handle.get(0);

                    VkWriteDescriptorSetAccelerationStructureKHR accelerationInfo = VkWriteDescriptorSetAccelerationStructureKHR
                        .calloc(stack)
                        .sType$Default()
                        .pAccelerationStructures(stack.longs(topLevel.handle));
                    VkDescriptorBufferInfo.Buffer resultInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(outputBuffer.buffer).offset(0).range(outputSize);
                    VkDescriptorBufferInfo.Buffer cameraInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(cameraBuffer.buffer).offset(0).range(304);
                    VkDescriptorBufferInfo.Buffer materialInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(materialBuffer.buffer).offset(0).range(materialFloatCount * Float.BYTES);
                    VkDescriptorBufferInfo.Buffer pbrInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(pbrBuffer.buffer).offset(0).range(pbrBuffer.size);
                    VkDescriptorBufferInfo.Buffer dynamicMotionInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(dynamicMotionMetadataBuffer.buffer).offset(0)
                        .range(dynamicMotionMetadataBuffer.size);
                    VkDescriptorBufferInfo.Buffer lightDataInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(lightDataBuffer.buffer).offset(0).range(lightDataBuffer.size);
                    VkDescriptorImageInfo.Buffer atlasInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(atlasSampler).imageView(atlasImageView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer skyboxInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(atlasSampler).imageView(skybox.imageView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer fsrImages = VkDescriptorImageInfo.calloc(10, stack);
                    fsrImages.get(0).imageView(fsr.sceneColorView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(1).imageView(fsr.motionView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(2).imageView(fsr.depthView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(3).imageView(fsr.reactiveView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(4).imageView(fsr.transparencyView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(5).imageView(nrd.noisyDiffuseView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(6).imageView(nrd.noisySpecularView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(7).imageView(nrd.normalRoughnessView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(8).imageView(nrd.viewZView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    fsrImages.get(9).imageView(nrd.motionView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer skinInfos = VkDescriptorImageInfo.calloc(PLAYER_SKIN_DESCRIPTOR_COUNT, stack);
                    for (int skin = 0; skin < PLAYER_SKIN_DESCRIPTOR_COUNT; skin++) {
                        skinInfos.get(skin).sampler(atlasSampler).imageView(atlasImageView)
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    }
                    VkDescriptorImageInfo.Buffer itemAtlasInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(itemAtlasSampler).imageView(itemAtlasImageView)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdMaterialInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.materialView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdDirectDiffuseInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.directDiffuseView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdIndirectDiffuseInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.indirectDiffuseView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdEmissionInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.emissionView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdPrimaryPositionInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.primaryPositionView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorImageInfo.Buffer nrdSpecularMaterialInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .imageView(nrd.specularMaterialView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    VkDescriptorBufferInfo.Buffer skyImportanceInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(skybox.importanceBuffer()).offset(0).range(skybox.importanceSize());
                    var skyCdfAccelerationInfo = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType$Default().pAccelerationStructures(stack.longs(skybox.cdfHandle()));
                    var skyCdfMetadataInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(skybox.cdfMetadataBuffer()).offset(0).range(SkyCdfGeometry.METADATA_BYTES);
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(physicalAtmosphere ? 42 : 31, stack);
                    var persistentInfo = VkDescriptorBufferInfo.calloc(1, stack).buffer(persistentLighting.buffer.buffer)
                        .offset(0).range(persistentLighting.buffer.size);
                    writes.get(physicalAtmosphere ? 41 : 30).sType$Default().dstSet(descriptorSet).dstBinding(41)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(persistentInfo);
                    writes.get(physicalAtmosphere ? 39 : 28).sType$Default().pNext(skyCdfAccelerationInfo)
                        .dstSet(descriptorSet).dstBinding(39).descriptorCount(1)
                        .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                    writes.get(physicalAtmosphere ? 40 : 29).sType$Default().dstSet(descriptorSet).dstBinding(40)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(skyCdfMetadataInfo);
                    writes.get(physicalAtmosphere ? 38 : 27).sType$Default().dstSet(descriptorSet).dstBinding(38)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(skyImportanceInfo);
                    writes.get(0).sType$Default().pNext(accelerationInfo).dstSet(descriptorSet).dstBinding(0)
                        .descriptorCount(1).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                    writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(resultInfo);
                    writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(cameraInfo);
                    writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(materialInfo);
                    writes.get(4).sType$Default().dstSet(descriptorSet).dstBinding(4).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasInfo);
                    writes.get(5).sType$Default().dstSet(descriptorSet).dstBinding(5).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(pbrInfo);
                    writes.get(6).sType$Default().dstSet(descriptorSet).dstBinding(6).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(skyboxInfo);
                    writes.get(17).sType$Default().dstSet(descriptorSet).dstBinding(17)
                        .descriptorCount(PLAYER_SKIN_DESCRIPTOR_COUNT)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(skinInfos);
                    writes.get(18).sType$Default().dstSet(descriptorSet).dstBinding(18)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(itemAtlasInfo);
                    writes.get(19).sType$Default().dstSet(descriptorSet).dstBinding(19)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(dynamicMotionInfo);
                    writes.get(20).sType$Default().dstSet(descriptorSet).dstBinding(20)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdMaterialInfo);
                    writes.get(21).sType$Default().dstSet(descriptorSet).dstBinding(21)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdDirectDiffuseInfo);
                    writes.get(22).sType$Default().dstSet(descriptorSet).dstBinding(22)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdIndirectDiffuseInfo);
                    writes.get(23).sType$Default().dstSet(descriptorSet).dstBinding(23)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdEmissionInfo);
                    writes.get(24).sType$Default().dstSet(descriptorSet).dstBinding(24)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdPrimaryPositionInfo);
                    writes.get(25).sType$Default().dstSet(descriptorSet).dstBinding(25)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(nrdSpecularMaterialInfo);
                    writes.get(26).sType$Default().dstSet(descriptorSet).dstBinding(26)
                        .descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(lightDataInfo);
                    for (int binding = 7; binding <= 16; binding++) {
                        writes.get(binding).sType$Default().dstSet(descriptorSet).dstBinding(binding).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(VkDescriptorImageInfo.create(fsrImages.get(binding - 7).address(), 1));
                    }
                    if (physicalAtmosphere) {
                        VkDescriptorImageInfo.Buffer skyInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.skyView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer transmittanceInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.cameraTransmittanceView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorBufferInfo.Buffer atmosphereInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(atmosphereCameraBuffer.buffer).offset(0).range(16);
                        writes.get(27).sType$Default().dstSet(descriptorSet).dstBinding(27).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(skyInfo);
                        writes.get(28).sType$Default().dstSet(descriptorSet).dstBinding(28).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(transmittanceInfo);
                        writes.get(29).sType$Default().dstSet(descriptorSet).dstBinding(29).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).pBufferInfo(atmosphereInfo);
                        VkDescriptorImageInfo.Buffer aerialLInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(fsr.physicalAerialLView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorBufferInfo.Buffer mediumInfo = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(atmosphere.mediumBuffer()).offset(0).range(atmosphere.mediumSize());
                        VkDescriptorImageInfo.Buffer opticalDepthInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(atmosphere.sampler()).imageView(atmosphere.opticalDepthView())
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer scatteringSourceInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .sampler(atmosphere.sampler()).imageView(atmosphere.scatteringSourceView())
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer meanInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.incidentMeanView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer groundInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.groundView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer highInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.highView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        VkDescriptorImageInfo.Buffer moonSkyInfo = VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(atmosphere.moonSkyView()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                        writes.get(30).sType$Default().dstSet(descriptorSet).dstBinding(30).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(aerialLInfo);
                        writes.get(31).sType$Default().dstSet(descriptorSet).dstBinding(31).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(mediumInfo);
                        writes.get(32).sType$Default().dstSet(descriptorSet).dstBinding(32).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(opticalDepthInfo);
                        writes.get(33).sType$Default().dstSet(descriptorSet).dstBinding(33).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(scatteringSourceInfo);
                        writes.get(34).sType$Default().dstSet(descriptorSet).dstBinding(34).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(meanInfo);
                        writes.get(35).sType$Default().dstSet(descriptorSet).dstBinding(35).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(groundInfo);
                        writes.get(36).sType$Default().dstSet(descriptorSet).dstBinding(36).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(highInfo);
                        writes.get(37).sType$Default().dstSet(descriptorSet).dstBinding(37).descriptorCount(1)
                            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(moonSkyInfo);
                    }
                    VK10.vkUpdateDescriptorSets(vkDevice, writes, null);

                    VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout))
                        .pPushConstantRanges(org.lwjgl.vulkan.VkPushConstantRange.calloc(1, stack)
                            .stageFlags(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR).offset(0).size(4));
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkCreatePipelineLayout(vkDevice, pipelineLayoutInfo, null, handle),
                        "Failed to create ray-tracing pipeline layout"
                    );
                    pipelineLayout = handle.get(0);

                    VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(9, stack);
                    stages.get(0).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(raygen.handle).pName(stack.UTF8("main"));
                    stages.get(1).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(miss.handle).pName(stack.UTF8("main"));
                    stages.get(2).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(shadowMiss.handle).pName(stack.UTF8("main"));
                    stages.get(3).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(closestHit.handle).pName(stack.UTF8("main"));
                    stages.get(4).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(anyHit.handle).pName(stack.UTF8("main"));
                    stages.get(5).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(shadowClosestHit.handle).pName(stack.UTF8("main"));
                    stages.get(6).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(shadowAnyHit.handle).pName(stack.UTF8("main"));
                    stages.get(7).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(skyCdfMiss.handle).pName(stack.UTF8("main"));
                    stages.get(8).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(skyCdfHit.handle).pName(stack.UTF8("main"));
                    // Group 3 (primary) and group 4 (shadow) share the alpha-testing Any Hit shader.
                    VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(7, stack);
                    groups.get(5).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(7).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                        .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(6).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(8)
                        .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(0).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(0).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                        .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(1).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(1).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                        .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(2).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR)
                        .generalShader(2).closestHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR)
                        .anyHitShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(3).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(3)
                        .anyHitShader(4).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    groups.get(4).sType$Default().type(KHRRayTracingPipeline.VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .generalShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR).closestHitShader(5)
                        .anyHitShader(6).intersectionShader(KHRRayTracingPipeline.VK_SHADER_UNUSED_KHR);
                    VkRayTracingPipelineCreateInfoKHR.Buffer pipelineInfo = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack);
                    pipelineInfo.sType$Default().pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(pipelineLayout);
                    VulkanUtils.crashIfFailure(
                        device,
                        KHRRayTracingPipeline.vkCreateRayTracingPipelinesKHR(vkDevice, 0L, 0L, pipelineInfo, null, handle),
                        "Failed to create ray-tracing pipeline"
                    );
                    pipeline = handle.get(0);

                    int handleSize = limits.shaderGroupHandleSize();
                    int handleAlignment = limits.shaderGroupHandleAlignment();
                    int baseAlignment = limits.shaderGroupBaseAlignment();
                    int stride = alignUp(handleSize, Math.max(handleAlignment, baseAlignment));
                    ByteBuffer shaderHandles = MemoryUtil.memAlloc(handleSize * 7);
                    try {
                        VulkanUtils.crashIfFailure(
                            device,
                            KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(vkDevice, pipeline, 0, 7, shaderHandles),
                            "Failed to retrieve ray-tracing shader group handles"
                        );
                        shaderBindingTable = NativeBuffer.create(
                            device,
                            (long)stride * 7L,
                            KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR
                                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            true
                        );
                        try (NativeBuffer.Mapped mapped = shaderBindingTable.map()) {
                            int[] sbtGroupOrder = {0, 1, 2, 5, 3, 4, 6};
                            for (int i = 0; i < sbtGroupOrder.length; i++) {
                                MemoryUtil.memCopy(
                                    MemoryUtil.memAddress(shaderHandles) + (long)sbtGroupOrder[i] * handleSize,
                                    MemoryUtil.memAddress(mapped.buffer()) + (long)i * stride,
                                    handleSize
                                );
                            }
                        }
                    } finally {
                        MemoryUtil.memFree(shaderHandles);
                    }

                    // VulkanDevice.createCommandEncoder() returns Minecraft's shared encoder;
                    // this pass owns its command pools/transient memory, so construct a private
                    // encoder before using the explicit destroy() lifecycle below.
                    encoder = new VulkanCommandEncoder(device);
                    RayTracingVulkanPass resources = new RayTracingVulkanPass(
                        device,
                        limits,
                        encoder,
                        sectionBlas,
                        blasCache,
                        dynamicInstances,
                        dynamicSlotCapacity,
                        instanceBuffer,
                        dynamicMotionMetadataBuffer,
                        scratchBuffer,
                        outputBuffer,
                        cameraBuffer,
                        materialBuffer,
                        lightDataBuffer,
                        pbrBuffer,
                        terrainNodeMetadataBuffer,
                        terrainBlasAddressBuffer,
                        terrainTraversalParamsBuffer,
                        terrainTraversalDescriptorSetLayout,
                        terrainTraversalDescriptorPool,
                        terrainTraversalDescriptorSet,
                        terrainHiZDescriptorSets,
                        terrainTraversalPipelineLayout,
                        terrainTraversalPipeline,
                        terrainTraversalShaderModule,
                        terrainHiZPipeline,
                        terrainHiZShaderModule,
                        terrainTraversalNodeCount,
                        terrainTraversalEnabled,
                        pbrMaterials,
                        geometry,
                        atlasImageView,
                        atlasSampler,
                        itemAtlasImageView,
                        itemAtlasSampler,
                        targetImage,
                        targetImageView,
                        outputFormat,
                        displayWidth,
                        displayHeight,
                        outputWidth,
                        outputHeight,
                        fsr,
                        shaderBindingTable,
                        topLevel,
                        descriptorSetLayout,
                        descriptorPool,
                        descriptorSet,
                        pipelineLayout,
                        pipeline,
                        shaderModules,
                        stride,
                        dynamicFrame,
                        atmosphere,
                        atmosphereCameraBuffer,
                        atmosphereRequested
                    );
                    resources.gpuLightTreeBuilder = lightTreeBuilder;
                    resources.persistentLighting = persistentLighting;
                    blasCache.commit(sectionBlas);
                    blasCache.trim(activeKeys);
                    encoder = null;
                    return resources;
                }
            } catch (Throwable throwable) {
                if (encoder != null) {
                    try {
                        encoder.destroy();
                    } catch (Throwable cleanupFailure) {
                        // A submit timeout can leave the encoder's transient-memory cursor in
                        // the pre-begin state, so its destroy() may fail before waitIdle().
                        // Explicitly wait before releasing any pass-owned VMA/AS resources.
                        throwable.addSuppressed(cleanupFailure);
                        try {
                            device.graphicsQueue().waitIdle();
                        } catch (Throwable waitFailure) {
                            throwable.addSuppressed(waitFailure);
                        }
                    }
                }
                if (terrainHiZPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, terrainHiZPipeline, null);
                if (terrainTraversalPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, terrainTraversalPipeline, null);
                if (terrainTraversalPipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vkDevice, terrainTraversalPipelineLayout, null);
                if (terrainTraversalDescriptorPool != 0L) VK10.vkDestroyDescriptorPool(vkDevice, terrainTraversalDescriptorPool, null);
                if (terrainTraversalDescriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vkDevice, terrainTraversalDescriptorSetLayout, null);
                if (terrainHiZShaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, terrainHiZShaderModule, null);
                if (terrainTraversalShaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, terrainTraversalShaderModule, null);
                if (pipeline != 0L) VK10.vkDestroyPipeline(vkDevice, pipeline, null);
                if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
                if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(vkDevice, descriptorPool, null);
                if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
                for (long shaderModule : shaderModules) if (shaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, shaderModule, null);
                closeDuringFailure(lightTreeBuilder, throwable);
                closeDuringFailure(topLevel, throwable);
                closeDuringFailure(shaderBindingTable, throwable);
                closeDuringFailure(pbrBuffer, throwable);
                closeDuringFailure(persistentLighting, throwable);
                closeDuringFailure(terrainTraversalParamsBuffer, throwable);
                closeDuringFailure(terrainBlasAddressBuffer, throwable);
                closeDuringFailure(terrainNodeMetadataBuffer, throwable);
                closeDuringFailure(lightDataBuffer, throwable);
                closeDuringFailure(materialBuffer, throwable);
                closeDuringFailure(atmosphereCameraBuffer, throwable);
                closeDuringFailure(atmosphere, throwable);
                closeDuringFailure(cameraBuffer, throwable);
                closeDuringFailure(outputBuffer, throwable);
                closeDuringFailure(scratchBuffer, throwable);
                closeDuringFailure(instanceBuffer, throwable);
                closeDuringFailure(dynamicMotionMetadataBuffer, throwable);
                closeDuringFailure(dynamicInstances, throwable);
                try {
                    blasCache.abort(sectionBlas);
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
                throw throwable;
            }
        }

        private static RayTracingAtmosphere createOptionalAtmosphere(VulkanDevice device, int aerosolDensitySteps) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
                var limits = properties.limits();
                if (limits.maxImageDimension2D() < 8193
                    || limits.maxPerStageDescriptorStorageImages() < 27
                    || limits.maxDescriptorSetStorageImages() < 27
                    // RayGen's physical variant adds the pinned-medium SSBO to its existing five
                    // read buffers (result/materials/pbr/dynamic-motion/light-tree).
                    || limits.maxPerStageDescriptorStorageBuffers() < 6
                    || limits.maxDescriptorSetStorageBuffers() < 6
                    || limits.maxDescriptorSetSamplers() < 5 + PLAYER_SKIN_DESCRIPTOR_COUNT
                    || limits.maxDescriptorSetSampledImages() < 5 + PLAYER_SKIN_DESCRIPTOR_COUNT
                    || limits.maxPerStageDescriptorUniformBuffers() < 2
                    || limits.maxDescriptorSetUniformBuffers() < 2
                    || limits.maxPushConstantsSize() < 128) {
                    throw new IllegalStateException("Prime atmosphere descriptor/push-constant limits unavailable");
                }
            }
            try {
                return RayTracingAtmosphere.create(device, aerosolDensitySteps);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Prime atmosphere initialization failed", failure);
            }
        }

        private static int atmosphereDensitySteps() {
            return Math.round(RayTracingClientConfig.INSTANCE.volumetricFogDensity.get().floatValue()
                * com.rtest.client.atmosphere.AtmosphereSettings.STEPS_PER_UNIT);
        }

        private static com.rtest.client.atmosphere.AtmosphereSettings atmosphereSettings() {
            return new com.rtest.client.atmosphere.AtmosphereSettings(atmosphereDensitySteps(),
                RayTracingClientConfig.INSTANCE.atmosphereAltitudeOffsetMeters.get());
        }

        /** The previous frame has retired before this runs; the complete replacement is fenced. */
        private void updateAtmosphereMedium() {
            if (this.atmosphere == null) return;
            int requested = atmosphereDensitySteps();
            if (requested != this.requestedAtmosphereDensitySteps) {
                this.requestedAtmosphereDensitySteps = requested;
                this.atmosphereDensityChangedAt = System.nanoTime();
            }
            if (requested == this.atmosphereDensitySteps
                || System.nanoTime() - this.atmosphereDensityChangedAt < 500_000_000L) return;

            RayTracingAtmosphere replacement;
            try {
                replacement = RayTracingAtmosphere.create(this.device, requested);
            } catch (RuntimeException failure) {
                if (failure instanceof RayTracingAtmosphere.UnretiredWorkException
                    || failure instanceof RayTracingAtmosphere.DeviceLostException
                    || failure.getSuppressed().length != 0
                    || String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
                    throw failure;
                }
                LOGGER.error("Atmosphere density rebuild failed; retaining generation {}", this.atmosphereDensitySteps, failure);
                this.atmosphereDensityChangedAt = System.nanoTime();
                return;
            }
            if (requested != atmosphereDensitySteps()) {
                replacement.close();
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var writes = VkWriteDescriptorSet.calloc(9, stack);
                int[] bindings = {27, 28, 31, 32, 33, 34, 35, 36, 37};
                long[] views = {replacement.skyView(), replacement.cameraTransmittanceView(), 0L,
                    replacement.opticalDepthView(), replacement.scatteringSourceView(),
                    replacement.incidentMeanView(), replacement.groundView(), replacement.highView(),
                    replacement.moonSkyView()};
                for (int i = 0; i < bindings.length; i++) {
                    if (bindings[i] == 31) {
                        var info = VkDescriptorBufferInfo.calloc(1, stack)
                            .buffer(replacement.mediumBuffer()).offset(0).range(replacement.mediumSize());
                        writes.get(i).sType$Default().dstSet(this.descriptorSet).dstBinding(31)
                            .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
                    } else {
                        boolean sampled = bindings[i] == 32 || bindings[i] == 33;
                        var info = VkDescriptorImageInfo.calloc(1, stack).imageView(views[i])
                            .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .sampler(sampled ? replacement.sampler() : 0L);
                        writes.get(i).sType$Default().dstSet(this.descriptorSet).dstBinding(bindings[i])
                            .descriptorCount(1).descriptorType(sampled ? VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                : VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
                    }
                }
                VK10.vkUpdateDescriptorSets(this.vkDevice, writes, null);
            } catch (Throwable failure) {
                replacement.close();
                throw failure;
            }
            RayTracingAtmosphere previous = this.atmosphere;
            this.atmosphere = replacement;
            this.atmosphereDensitySteps = requested;
            this.fsr.requestReset();
            previous.close();
            LOGGER.info("Prime atmosphere medium rebuilt: aerosol_density={}", requested / 100.0F);
        }

        boolean matches(VulkanDevice candidateDevice, int renderWidth, int renderHeight,
                        int width, int height, long image, long imageView, GpuFormat format,
                        SceneGeometry geometry, long atlasImageView, long atlasSampler, RtestFsr3 fsr,
                        boolean terrainTraversalEnabled) {
            return this.device == candidateDevice
                && this.fsr.renderWidth() == renderWidth
                && this.fsr.renderHeight() == renderHeight
                && this.displayWidth == width
                && this.displayHeight == height
                && this.targetImage == image
                && this.targetImageView == imageView
                && this.outputFormat == format
                && this.atlasImageView == atlasImageView
                && this.atlasSampler == atlasSampler
                && this.fsr == fsr
                && this.atmosphereRequested == RayTracingClientConfig.INSTANCE.primeAtmosphereEnabled.get()
                && this.skyboxTextureEnabled == RayTracingClientConfig.INSTANCE.skyboxTextureEnabled.get()
                // Geometry-owned BLAS/TLAS and GPU traversal metadata are replaced together by
                // updateGeometry(). Do not tear down the RT pipeline and FSR temporal history for
                // every streamed section publication.
                && this.terrainTraversalEnabled == terrainTraversalEnabled;
        }

        boolean usesGeometry(SceneGeometry candidate) {
            return this.geometry == candidate;
        }

        private static int terrainTraversalCapacity(int nodeCount) {
            int capacity = 16;
            while (capacity < nodeCount) {
                if (capacity > Integer.MAX_VALUE / 2) return nodeCount;
                capacity <<= 1;
            }
            return capacity;
        }

        private static List<RayTracingTerrainTraversalAbi.NodeMetadata> buildTerrainTraversalMetadata(
                SceneGeometry geometry) {
            Map<RayTracingTerrainLod.NodeKey, Integer> traversalIndices = new HashMap<>();
            for (int index = 0; index < geometry.sections.size(); index++) {
                traversalIndices.put(geometry.sections.get(index).terrainNodeKey(), index);
            }
            Map<Integer, List<Integer>> traversalChildren = new HashMap<>();
            for (int index = 0; index < geometry.sections.size(); index++) {
                SceneGeometry.SectionGeometry childSection = geometry.sections.get(index);
                RayTracingTerrainLod.NodeKey childKey = childSection.terrainNodeKey();
                // Coarse meshes contain opaque terrain only. Keep transparent, fluid, emissive,
                // and other native-only leaves outside every coarse parent.
                if (childKey.level() == 0 && !childSection.isOpaqueTerrain()) continue;
                RayTracingTerrainLod.NodeKey parent = childKey.parent();
                Integer parentIndex = parent == null ? null : traversalIndices.get(parent);
                if (parentIndex != null) {
                    traversalChildren.computeIfAbsent(parentIndex, ignored -> new ArrayList<>()).add(index);
                }
            }
            List<RayTracingTerrainTraversalAbi.NodeMetadata> traversalNodes =
                new ArrayList<>(geometry.sections.size());
            for (int index = 0; index < geometry.sections.size(); index++) {
                SceneGeometry.SectionGeometry section = geometry.sections.get(index);
                RayTracingTerrainLod.NodeKey nodeKey = section.terrainNodeKey();
                RayTracingTerrainLod.Bounds nodeBounds = RayTracingTerrainLod.boundsFor(nodeKey);
                boolean hierarchyParticipant = nodeKey.level() > 0 || section.isOpaqueTerrain();
                int flags = RayTracingTerrainTraversalAbi.NODE_FLAG_READY
                    | RayTracingTerrainTraversalAbi.NODE_FLAG_RENDERABLE
                    | RayTracingTerrainTraversalAbi.NODE_FLAG_HAS_BLAS;
                Integer parentIndex = !hierarchyParticipant || nodeKey.parent() == null
                    ? null : traversalIndices.get(nodeKey.parent());
                List<Integer> children = traversalChildren.getOrDefault(index, List.of());
                if (children.size() > 1) children.sort(Integer::compare);
                // Shader child links use a contiguous firstChild + bitmask range. A sparse
                // parent remains a conservative leaf instead of claiming a partial child set.
                // A partial child list is not a complete spatial cut. It can happen when the
                // GPU candidate compaction removes distant native leaves; treating the remaining
                // children as complete would mask the parent and leave holes in the missing
                // octants. Descend only when all eight direct octants are resident.
                boolean contiguousChildren = children.size() == 8;
                for (int child = 1; contiguousChildren && child < children.size(); child++) {
                    contiguousChildren = children.get(child) == children.get(0) + child;
                }
                int firstChild = contiguousChildren
                    ? children.get(0) : RayTracingTerrainTraversalAbi.INVALID_INDEX;
                int childMask = contiguousChildren ? (1 << children.size()) - 1 : 0;
                float lodErrorPixels = nodeKey.level() == 0 ? 0.0F : nodeKey.level() * 16.0F;
                traversalNodes.add(new RayTracingTerrainTraversalAbi.NodeMetadata(
                    new RayTracingTerrainTraversalAbi.Bounds(
                        (float)nodeBounds.minX(), (float)nodeBounds.minY(), (float)nodeBounds.minZ(),
                        (float)nodeBounds.maxX(), (float)nodeBounds.maxY(), (float)nodeBounds.maxZ()),
                    parentIndex == null ? RayTracingTerrainTraversalAbi.INVALID_INDEX : parentIndex,
                    firstChild, childMask, nodeKey.level(), index,
                    geometry.materialLayout.baseTriangle(section),
                    section.triangleCount(), flags, lodErrorPixels));
            }
            return traversalNodes;
        }

        private static void writeTerrainTraversalBuffers(
                SceneGeometry geometry,
                List<CachedBlas> sectionBlas,
                List<RayTracingTerrainTraversalAbi.NodeMetadata> traversalNodes,
                NativeBuffer metadataBuffer,
                NativeBuffer addressBuffer) {
            int nodeCount = traversalNodes.size();
            if (nodeCount != geometry.sections.size() || nodeCount != sectionBlas.size()) {
                throw new IllegalStateException("Terrain traversal nodes, geometry, and BLAS arrays diverged");
            }
            ByteBuffer metadata = RayTracingTerrainTraversalAbi.nodeMetadataBuffer(traversalNodes);
            try (NativeBuffer.Mapped mapped = metadataBuffer.map()) {
                ByteBuffer destination = mapped.buffer();
                destination.clear();
                destination.put(metadata);
            }
            try (NativeBuffer.Mapped mapped = addressBuffer.map()) {
                ByteBuffer addresses = mapped.buffer().order(ByteOrder.nativeOrder());
                addresses.clear();
                for (int index = 0; index < nodeCount; index++) {
                    addresses.putLong(sectionBlas.get(index).bottomLevel.deviceAddress);
                }
            }
        }

        private void updateTerrainTraversalBufferDescriptors(
                NativeBuffer metadataBuffer, NativeBuffer addressBuffer) {
            if (!this.terrainTraversalEnabled) return;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int setCount = 1;
                for (long descriptorSet : this.terrainHiZDescriptorSets) {
                    if (descriptorSet != 0L) setCount++;
                }
                VkDescriptorBufferInfo.Buffer metadataInfo = VkDescriptorBufferInfo.calloc(setCount, stack);
                VkDescriptorBufferInfo.Buffer addressInfo = VkDescriptorBufferInfo.calloc(setCount, stack);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(setCount * 2, stack);
                int setIndex = 0;
                setIndex = writeTerrainTraversalBufferSet(this.terrainTraversalDescriptorSet,
                    setIndex, metadataInfo, addressInfo, writes, metadataBuffer, addressBuffer);
                for (long descriptorSet : this.terrainHiZDescriptorSets) {
                    if (descriptorSet != 0L) {
                        setIndex = writeTerrainTraversalBufferSet(descriptorSet, setIndex,
                            metadataInfo, addressInfo, writes, metadataBuffer, addressBuffer);
                    }
                }
                VK10.vkUpdateDescriptorSets(this.vkDevice, writes, null);
            }
        }

        private static int writeTerrainTraversalBufferSet(
                long descriptorSet,
                int index,
                VkDescriptorBufferInfo.Buffer metadataInfo,
                VkDescriptorBufferInfo.Buffer addressInfo,
                VkWriteDescriptorSet.Buffer writes,
                NativeBuffer metadataBuffer,
                NativeBuffer addressBuffer) {
            metadataInfo.get(index).buffer(metadataBuffer.buffer).offset(0).range(metadataBuffer.size);
            addressInfo.get(index).buffer(addressBuffer.buffer).offset(0).range(addressBuffer.size);
            writes.get(index * 2).sType$Default().dstSet(descriptorSet).dstBinding(0)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(metadataInfo.position(index));
            writes.get(index * 2 + 1).sType$Default().dstSet(descriptorSet).dstBinding(1)
                .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(addressInfo.position(index));
            return index + 1;
        }

        /**
         * Replaces only scene-owned buffers and the TLAS. The ray-tracing pipeline, SBT, FSR
         * images and descriptor layouts remain alive, while the BLAS cache reuses unchanged
         * sections. Retire the pending submission here as well as in dispatch(), because geometry
         * publication can close or rewrite buffers before the next dispatch begins.
         */
        void updateGeometry(SceneGeometry nextGeometry, BlasCache blasCache) {
            this.dispatchTiming.reset();
            waitForPreviousFrame(this.dispatchTiming);
            long startNanos = System.nanoTime();
            if (this.usesGeometry(nextGeometry)) {
                return;
            }
            long blasAcquireNanos = 0, sceneBuffersNanos = 0, materialNanos = 0;
            long lightUploadNanos = 0, descriptorsNanos = 0, phaseStart = 0;
            int previousSectionCount = this.sectionBlas.size();
            List<CachedBlas> nextBlas = new ArrayList<>();
            Set<SectionKey> activeKeys = new HashSet<>();
            NativeBuffer nextInstance = null;
            NativeBuffer nextMaterial = null;
            NativeBuffer nextLightData = null;
            NativeBuffer nextPbr = null;
            NativeBuffer nextTerrainNodeMetadata = this.terrainNodeMetadataBuffer;
            NativeBuffer nextTerrainBlasAddresses = this.terrainBlasAddressBuffer;
            AccelerationStructure nextTopLevel = null;
            NativeBuffer nextScratch = null;
            boolean reuseTopLevel = false;
            boolean reuseInstance = false;
            boolean reuseScratch = false;
            boolean reuseMaterial = false;
            boolean reuseLightData = false;
            boolean reuseLightAllocation = false;
            boolean reusePbr = false;
            boolean incrementalMaterialWrite = false;
            boolean dynamicMaterialRangesReset = false;
            boolean materialWriteMayHaveChanged = false;
            boolean reuseTerrainTraversalBuffers = true;
            RtResourceRollback rollback = new RtResourceRollback();
            int nextTerrainTraversalNodeCount = this.terrainTraversalNodeCount;
            int nextTerrainTraversalNodeCapacity = this.terrainTraversalNodeCapacity;
            try {
                int geometryUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                phaseStart = System.nanoTime();
                for (SceneGeometry.SectionGeometry section : nextGeometry.sections) {
                    CachedBlas cached = blasCache.acquire(this.device, section);
                    nextBlas.add(cached);
                    activeKeys.add(cached.key);
                }
                validateUniqueBlasKeys(nextBlas);
                blasAcquireNanos = System.nanoTime() - phaseStart;
                phaseStart = System.nanoTime();
                List<RayTracingTerrainTraversalAbi.NodeMetadata> nextTerrainTraversalMetadata = null;
                if (this.terrainTraversalEnabled) {
                    nextTerrainTraversalMetadata = buildTerrainTraversalMetadata(nextGeometry);
                    nextTerrainTraversalNodeCount = nextTerrainTraversalMetadata.size();
                    if (nextTerrainTraversalNodeCount != nextGeometry.sections.size()) {
                        throw new IllegalStateException("Terrain traversal metadata count differs from scene geometry");
                    }
                    if (nextTerrainTraversalNodeCount > nextTerrainTraversalNodeCapacity) {
                        nextTerrainTraversalNodeCapacity = terrainTraversalCapacity(nextTerrainTraversalNodeCount);
                        NativeBuffer grownMetadata = NativeBuffer.create(this.device,
                            (long)nextTerrainTraversalNodeCapacity
                                * RayTracingTerrainTraversalAbi.NODE_METADATA_BYTES,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                        try {
                            NativeBuffer grownAddresses = NativeBuffer.create(this.device,
                                (long)nextTerrainTraversalNodeCapacity * Long.BYTES,
                                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                            nextTerrainNodeMetadata = grownMetadata;
                            nextTerrainBlasAddresses = grownAddresses;
                        } catch (Throwable allocationFailure) {
                            RtResourceRollback.attempt(allocationFailure, grownMetadata::close);
                            throw allocationFailure;
                        }
                        reuseTerrainTraversalBuffers = false;
                    }
                }
                int requiredInstanceCount = nextBlas.size() + this.dynamicSlotCapacity;
                reuseTopLevel = requiredInstanceCount <= this.topLevel.primitiveCount;
                int nextTopLevelCapacity = reuseTopLevel ? this.topLevel.primitiveCount
                    : tlasInstanceCapacity(requiredInstanceCount,
                        this.accelerationLimits.maxInstanceCount());
                long requiredInstanceSize = (long)nextTopLevelCapacity
                    * VkAccelerationStructureInstanceKHR.SIZEOF;
                reuseInstance = requiredInstanceSize <= this.instanceBuffer.size;
                nextInstance = reuseInstance
                    ? this.instanceBuffer
                    : NativeBuffer.create(this.device, requiredInstanceSize, geometryUsage, true);
                if (reuseInstance) {
                    rollback.before(() -> {
                        writeInstanceBuffer(this.instanceBuffer, this.geometry, this.sectionBlas,
                            this.dynamicSlotCapacity, this.topLevel.primitiveCount);
                        // Static inputs are restored, dynamic slots were zeroed. Force re-emission
                        // and a fence-protected TLAS rebuild before any old-geometry trace.
                        this.topLevelUpdatePending = this.topLevelUpdatePending || this.topLevelBuilt;
                        this.topLevelBuilt = false;
                    });
                }
                writeInstanceBuffer(nextInstance, nextGeometry, nextBlas,
                    this.dynamicSlotCapacity, nextTopLevelCapacity);
                nextTopLevel = reuseTopLevel
                    ? this.topLevel
                    : AccelerationStructure.createTopLevel(this.device, nextInstance, nextTopLevelCapacity);
                long scratchSize = nextTopLevel.scratchSize;
                for (CachedBlas cached : nextBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                for (DynamicCachedBlas cached : dynamicInstances.blases()) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                long scratchAlignment = Math.max(1L, this.accelerationLimits.minScratchAlignment());
                long requiredScratchBufferSize = VulkanAccelerationResources.scratchBufferSize(scratchSize, scratchAlignment);
                reuseScratch = reuseTopLevel && requiredScratchBufferSize <= this.scratchBuffer.size;
                nextScratch = reuseScratch
                    ? this.scratchBuffer
                    : NativeBuffer.create(
                        this.device,
                        requiredScratchBufferSize,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        false);
                sceneBuffersNanos = System.nanoTime() - phaseStart;
                phaseStart = System.nanoTime();
                long nextMaterialFloatCount = materialFloatCount(nextGeometry, dynamicSlotCapacity);
                reuseMaterial = nextMaterialFloatCount * Float.BYTES <= this.materialBuffer.size;
                nextMaterial = reuseMaterial
                    ? this.materialBuffer
                    : NativeBuffer.create(this.device,
                        RayTracingMaterialBuffer.allocationBytes(nextMaterialFloatCount * Float.BYTES, this.materialBuffer.size),
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                // SectionGeometry instances are preserved by replaceSections() for clean
                // sections. When their per-index material spans are unchanged, those identities
                // are enough to update only replaced sections; the dynamic ranges remain at the
                // same absolute offsets and must not be cleared out from under the next frame.
                boolean dynamicMaterialBaseStable = sameDynamicMaterialBase(this.geometry, nextGeometry);
                incrementalMaterialWrite = canUseIncrementalMaterialWrite(
                    this.geometry, nextGeometry, reuseMaterial, this.materialBuffer, nextMaterial);
                if (incrementalMaterialWrite) {
                    materialWriteMayHaveChanged = hasChangedMaterialSections(this.geometry, nextGeometry);
                    if (materialWriteMayHaveChanged) {
                        writeChangedSectionMaterials(nextMaterial, this.geometry, nextGeometry, false);
                    }
                    dynamicMaterialRangesReset = !dynamicMaterialBaseStable;
                    if (dynamicMaterialRangesReset) {
                        clearDynamicMaterialSlots(nextMaterial, nextGeometry, dynamicSlotCapacity);
                    }
                } else {
                    // A full static rewrite is still safe without clearing dynamic rows when
                    // the old allocation and dynamic base are preserved. New allocations or a
                    // shifted base need the original zero initialization.
                    dynamicMaterialRangesReset = !reuseMaterial || !dynamicMaterialBaseStable;
                    materialWriteMayHaveChanged = reuseMaterial;
                    writeMaterialBuffer(nextMaterial, nextGeometry, dynamicSlotCapacity,
                        dynamicMaterialRangesReset);
                }
                materialNanos = System.nanoTime() - phaseStart;
                phaseStart = System.nanoTime();
                int[] nextLightWords = nextGeometry.lightTree.words();
                reuseLightData = java.util.Arrays.equals(this.geometry.lightTree.words(), nextLightWords);
                // GPU seed words are not the finished device tree. Never delta-write or
                // rollback against a seed; build changed trees in a separate allocation.
                reuseLightAllocation = reuseLightData || (!nextGeometry.lightTree.gpuBuild()
                    && !this.geometry.lightTree.gpuBuild()
                    && (long)nextLightWords.length * Integer.BYTES <= this.lightDataBuffer.size);
                if (reuseLightAllocation) {
                    nextLightData = this.lightDataBuffer;
                    if (!reuseLightData) {
                        // Register rollback before any in-place write, including partially failed uploads.
                        rollback.before(() -> {
                            try (NativeBuffer.Mapped mapped = this.lightDataBuffer.map()) {
                                RayTracingMaterialBuffer.writeChangedLightWords(mapped,
                                    nextLightWords, this.geometry.lightTree.words());
                            }
                        });
                        try (NativeBuffer.Mapped mapped = nextLightData.map()) {
                            RayTracingMaterialBuffer.writeChangedLightWords(mapped,
                                this.geometry.lightTree.words(), nextLightWords);
                        }
                    }
                } else {
                    nextLightData = uploadLightDataBuffer(this.device, nextGeometry.lightTree);
                    buildLightTree(nextLightData, nextGeometry.lightTree);
                }
                if (this.pbrMaterials == null) {
                    int[] nextPbrData = nextGeometry.pbrData;
                    // Capacity alone is not content equality. Keep changed immutable snapshots
                    // in a new allocation so a failed publication leaves old PBR bytes intact.
                    reusePbr = java.util.Arrays.equals(this.geometry.pbrData, nextPbrData);
                    nextPbr = reusePbr ? this.pbrBuffer : uploadIntBuffer(this.device, nextPbrData,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                } else {
                    // Dynamic PBR uses a fixed host-visible SSBO. Geometry replacement must not
                    // rebuild its payload or replace binding 5; newly discovered maps are applied
                    // by synchronizePbrMaterials() as append-only writes.
                    reusePbr = true;
                    nextPbr = this.pbrBuffer;
                }
                lightUploadNanos = System.nanoTime() - phaseStart;
                phaseStart = System.nanoTime();
                long pbrRange = nextPbr.size;
                rollback.before(() -> updateSceneDescriptors(this.topLevel, this.materialBuffer,
                    this.lightDataBuffer, this.pbrBuffer,
                    materialFloatCount(this.geometry, this.dynamicSlotCapacity) * Float.BYTES,
                    this.pbrBuffer.size, this.pbrMaterials == null));
                updateSceneDescriptors(nextTopLevel, nextMaterial, nextLightData, nextPbr,
                    nextMaterialFloatCount * Float.BYTES, pbrRange,
                    this.pbrMaterials == null);
                if (nextTerrainTraversalMetadata != null) {
                    if (reuseTerrainTraversalBuffers) {
                        rollback.before(() -> writeTerrainTraversalBuffers(this.geometry, this.sectionBlas,
                            buildTerrainTraversalMetadata(this.geometry),
                            this.terrainNodeMetadataBuffer, this.terrainBlasAddressBuffer));
                    }
                    writeTerrainTraversalBuffers(nextGeometry, nextBlas, nextTerrainTraversalMetadata,
                        nextTerrainNodeMetadata, nextTerrainBlasAddresses);
                    rollback.before(() -> updateTerrainTraversalBufferDescriptors(
                        this.terrainNodeMetadataBuffer, this.terrainBlasAddressBuffer));
                    updateTerrainTraversalBufferDescriptors(nextTerrainNodeMetadata, nextTerrainBlasAddresses);
                }
            } catch (Throwable throwable) {
                // Reused allocations are written in place only after the previous submission's
                // fence retired. If descriptor or another replacement step fails afterwards,
                // restore the old static snapshot before leaving the published pass untouched.
                // A base-changing full write may have disturbed old dynamic rows; mark them
                // pending so the next old-geometry dispatch repopulates active slots.
                if (dynamicMaterialRangesReset && nextMaterial == this.materialBuffer) {
                    this.dynamicInstances.markMaterialWritePending();
                }
                if (materialWriteMayHaveChanged && nextMaterial == this.materialBuffer) {
                    try {
                        if (incrementalMaterialWrite) {
                            writeChangedSectionMaterials(nextMaterial, this.geometry, nextGeometry, true);
                        } else {
                            restoreStaticMaterialData(nextMaterial, this.geometry);
                        }
                    } catch (Throwable restoreFailure) {
                        throwable.addSuppressed(restoreFailure);
                    }
                }
                // Undo bindings and partially written reused inputs before destroying candidates.
                // Every cleanup is attempted even if another restore/destroy throws.
                rollback.restore(throwable);
                if (nextTopLevel != null && !reuseTopLevel) RtResourceRollback.attempt(throwable, nextTopLevel::close);
                if (nextPbr != null && !reusePbr) RtResourceRollback.attempt(throwable, nextPbr::close);
                if (nextLightData != null && !reuseLightAllocation) RtResourceRollback.attempt(throwable, nextLightData::close);
                if (nextMaterial != null && !reuseMaterial) RtResourceRollback.attempt(throwable, nextMaterial::close);
                if (nextScratch != null && !reuseScratch) RtResourceRollback.attempt(throwable, nextScratch::close);
                if (nextInstance != null && !reuseInstance) RtResourceRollback.attempt(throwable, nextInstance::close);
                if (!reuseTerrainTraversalBuffers) {
                    if (nextTerrainBlasAddresses != null) RtResourceRollback.attempt(throwable, nextTerrainBlasAddresses::close);
                    if (nextTerrainNodeMetadata != null) RtResourceRollback.attempt(throwable, nextTerrainNodeMetadata::close);
                }
                RtResourceRollback.attempt(throwable, () -> blasCache.abort(nextBlas));
                throw throwable;
            }

            descriptorsNanos = System.nanoTime() - phaseStart;
            // Publish BLAS candidates only after every replacement buffer and descriptor update
            // succeeded. This keeps the old TLAS inputs valid on any failure path.
            blasCache.commit(nextBlas);
            blasCache.trim(activeKeys);
            AccelerationStructure oldTopLevel = this.topLevel;
            NativeBuffer oldInstance = this.instanceBuffer;
            boolean canUpdateTopLevel = reuseTopLevel && this.topLevelBuilt;
            NativeBuffer oldScratch = this.scratchBuffer;
            NativeBuffer oldMaterial = this.materialBuffer;
            NativeBuffer oldLightData = this.lightDataBuffer;
            NativeBuffer oldPbr = this.pbrBuffer;
            NativeBuffer oldTerrainNodeMetadata = this.terrainNodeMetadataBuffer;
            NativeBuffer oldTerrainBlasAddresses = this.terrainBlasAddressBuffer;
            this.sectionBlas.clear();
            this.sectionBlas.addAll(nextBlas);
            this.instanceBuffer = nextInstance;
            this.scratchBuffer = nextScratch;
            this.materialBuffer = nextMaterial;
            this.lightDataBuffer = nextLightData;
            this.pbrBuffer = nextPbr;
            this.terrainNodeMetadataBuffer = nextTerrainNodeMetadata;
            this.terrainBlasAddressBuffer = nextTerrainBlasAddresses;
            this.terrainTraversalNodeCount = nextTerrainTraversalNodeCount;
            this.terrainTraversalNodeCapacity = nextTerrainTraversalNodeCapacity;
            this.pbrMapCount = this.pbrMaterials == null ? nextGeometry.pbrData[0] : this.pbrMaterials.loadedMapCount();
            this.topLevel = nextTopLevel;
            if (reuseTopLevel) {
                this.topLevel.rebindInputBuffer(nextInstance);
                this.topLevelUpdatePending = canUpdateTopLevel;
            } else {
                this.topLevelUpdatePending = false;
            }
            this.geometry = nextGeometry;
            this.topLevelBuilt = false;
            // Only a new allocation or a shifted base initialized/cleared the dynamic ranges.
            // Both incremental writes and full static fallbacks with a stable base preserve
            // their old contents, so retain the prior pending state instead of forcing a
            // redundant dynamic material upload.
            if (dynamicMaterialRangesReset) this.dynamicInstances.markMaterialWritePending();
            // NRD only receives motion for the primary hit. A static primary surface can still
            // reflect a changed dynamic/scene TLAS, so preserve no specular history across this
            // resource publication.
            this.dynamicInstances.markHistoryResetPending();
            this.fsr.requestReset();
            if (!reuseTopLevel) {
                oldTopLevel.close();
            }
            if (!reuseInstance) {
                oldInstance.close();
            }
            if (!reuseScratch) {
                oldScratch.close();
            }
            if (!reuseMaterial) {
                oldMaterial.close();
            }
            if (!reuseLightAllocation) {
                oldLightData.close();
            }
            if (!reusePbr) {
                oldPbr.close();
            }
            if (!reuseTerrainTraversalBuffers) {
                oldTerrainNodeMetadata.close();
                oldTerrainBlasAddresses.close();
            }
            LOGGER.info(
                "RTest geometry publish: oldSections={}, newSections={}, reuseTopLevel={}, rebuildInstanceBuffer={}, rebuildMaterialBuffer={}, reuseLightData={}, incrementalMaterialWrite={}, temporalReset={}, resetReason={}, duration={} ms, blas_acquire_ms={}, scene_buffers_ms={}, material_write_ms={}, light_pbr_upload_ms={}, descriptors_ms={}, retire_ms={}, material_capacity_bytes={}, material_live_bytes={}, reuseLightAllocation={}, light_capacity_bytes={}",
                previousSectionCount, nextBlas.size(), reuseTopLevel, !reuseInstance, !reuseMaterial,
                reuseLightData,
                incrementalMaterialWrite, true, TemporalResetReason.GEOMETRY_PUBLICATION,
                (System.nanoTime() - startNanos) / 1_000_000L,
                formatGpuMs(blasAcquireNanos / 1_000_000.0), formatGpuMs(sceneBuffersNanos / 1_000_000.0),
                formatGpuMs(materialNanos / 1_000_000.0), formatGpuMs(lightUploadNanos / 1_000_000.0),
                formatGpuMs(descriptorsNanos / 1_000_000.0), formatGpuMs((System.nanoTime() - phaseStart - descriptorsNanos) / 1_000_000.0),
                this.materialBuffer.size, materialFloatCount(nextGeometry, dynamicSlotCapacity) * Float.BYTES,
                reuseLightAllocation, this.lightDataBuffer.size);
        }

        private void updateLivingEntityTextureDescriptors(DynamicEntityGeometry.Frame frame) {
            long[] nextViews = this.livingEntityTextureViewsScratch;
            long[] nextSamplers = this.livingEntityTextureSamplersScratch;
            PlayerSkinBinding fallback = resolvePlayerSkinBinding(null);
            java.util.Arrays.fill(nextViews, fallback.view());
            java.util.Arrays.fill(nextSamplers, fallback.sampler());
            for (Map.Entry<Integer, Identifier> entry : frame.livingTextureSlots().entrySet()) {
                int slot = entry.getKey();
                if (slot > 0 && slot < PLAYER_SKIN_DESCRIPTOR_COUNT) {
                    LivingEntityGeometryAdapter.TextureBinding prepared = LivingEntityGeometryAdapter.textureBinding(slot);
                    PlayerSkinBinding binding = prepared == null
                        ? resolvePlayerSkinBinding(entry.getValue())
                        : new PlayerSkinBinding(prepared.view(), prepared.sampler());
                    nextViews[slot] = binding.view();
                    nextSamplers[slot] = binding.sampler();
                }
            }
            if (java.util.Arrays.equals(this.livingEntityTextureViews, nextViews)
                && java.util.Arrays.equals(this.livingEntityTextureSamplers, nextSamplers)) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(
                    PLAYER_SKIN_DESCRIPTOR_COUNT, stack);
                for (int slot = 0; slot < PLAYER_SKIN_DESCRIPTOR_COUNT; slot++) {
                    infos.get(slot).sampler(nextSamplers[slot]).imageView(nextViews[slot])
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                }
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0).sType$Default().dstSet(this.descriptorSet).dstBinding(17)
                    .descriptorCount(PLAYER_SKIN_DESCRIPTOR_COUNT)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(infos);
                VK10.vkUpdateDescriptorSets(this.vkDevice, write, null);
            }
            System.arraycopy(nextViews, 0, this.livingEntityTextureViews, 0, PLAYER_SKIN_DESCRIPTOR_COUNT);
            System.arraycopy(nextSamplers, 0, this.livingEntityTextureSamplers, 0, PLAYER_SKIN_DESCRIPTOR_COUNT);
        }

        private static long[] resolveTextureHandles(Identifier texturePath, long fallbackView, long fallbackSampler) {
            try {
                AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(texturePath);
                GpuTextureView view = texture.getTextureView();
                GpuSampler sampler = texture.getSampler();
                if (view instanceof VulkanGpuTextureView vulkanView && !vulkanView.isClosed()
                    && sampler instanceof VulkanGpuSampler vulkanSampler) {
                    LOGGER.info("RTest item atlas binding resolved: view={}, sampler={}",
                        vulkanView.vkImageView(), vulkanSampler.vkSampler());
                    return new long[] {vulkanView.vkImageView(), vulkanSampler.vkSampler()};
                }
            } catch (RuntimeException ignored) {
                // Atlas loading may still be in progress during the first RT pass.
            }
            LOGGER.warn("RTest item atlas binding fell back to block atlas: view={}, sampler={}",
                fallbackView, fallbackSampler);
            return new long[] {fallbackView, fallbackSampler};
        }

        private PlayerSkinBinding resolvePlayerSkinBinding(Identifier texturePath) {
            Identifier lookupPath = texturePath != null
                ? texturePath : MissingTextureAtlasSprite.getLocation();
            try {
                AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(lookupPath);
                GpuTextureView view = texture.getTextureView();
                GpuSampler sampler = texture.getSampler();
                if (view instanceof VulkanGpuTextureView vulkanView && !vulkanView.isClosed()
                    && sampler instanceof VulkanGpuSampler vulkanSampler) {
                    return new PlayerSkinBinding(vulkanView.vkImageView(), vulkanSampler.vkSampler());
                }
            } catch (RuntimeException ignored) {
                // The vanilla skin may still be downloading or a resource reload may be in
                // progress. Keep the atlas fallback and retry next frame.
            }
            return new PlayerSkinBinding(this.atlasImageView, this.atlasSampler);
        }

        private record PlayerSkinBinding(long view, long sampler) {
        }

        private boolean updateDynamicInstances(DynamicEntityGeometry.Frame frame,
                                               boolean forceInstanceWrite) {
            updateLivingEntityTextureDescriptors(frame);
            boolean tlasChanged;
            try {
                tlasChanged = this.dynamicInstances.update(
                    frame,
                    forceInstanceWrite,
                    this.device,
                    this.accelerationLimits,
                    this.geometry,
                    this.sectionBlas.size(),
                    this.sectionBlas.get(0).bottomLevel.deviceAddress,
                    this.instanceBuffer,
                    this.dynamicMotionMetadataBuffer,
                    this.materialBuffer,
                    this.scratchBuffer,
                    this.dispatchTimingFrame
                );
            } finally {
                // The updater publishes a replacement before retiring the previous allocation,
                // including when a later upload in the same frame throws.
                this.scratchBuffer = this.dynamicInstances.scratchBuffer();
            }
            representedBlockEntities = this.dynamicInstances.representedBlockEntities();
            return tlasChanged;
        }

        private static NativeBuffer createInstanceBuffer(
            VulkanDevice device,
            SceneGeometry geometry,
            List<CachedBlas> sectionBlas,
            int dynamicSlotCapacity,
            int instanceCapacity,
            int usage
        ) {
            NativeBuffer buffer = NativeBuffer.create(
                device, (long)instanceCapacity * VkAccelerationStructureInstanceKHR.SIZEOF, usage, true);
            writeInstanceBuffer(buffer, geometry, sectionBlas, dynamicSlotCapacity, instanceCapacity);
            return buffer;
        }

        private static void writeInstanceBuffer(
            NativeBuffer buffer,
            SceneGeometry geometry,
            List<CachedBlas> sectionBlas,
            int dynamicSlotCapacity,
            int instanceCapacity
        ) {
            int minimumCapacity = sectionBlas.size() + dynamicSlotCapacity;
            if (instanceCapacity < minimumCapacity) {
                throw new IllegalArgumentException("TLAS input capacity is smaller than its live instances");
            }
            try (NativeBuffer.Mapped mapped = buffer.map()) {
                long address = MemoryUtil.memAddress(mapped.buffer());
                for (int i = 0; i < sectionBlas.size(); i++) {
                    SceneGeometry.SectionGeometry section = geometry.sections.get(i);
                    CachedBlas cached = sectionBlas.get(i);
                    VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                        address + (long)i * VkAccelerationStructureInstanceKHR.SIZEOF);
                    instance.transform(transform -> {
                        FloatBuffer matrix = transform.matrix();
                        matrix.put(0, 1.0F).put(1, 0.0F).put(2, 0.0F)
                            .put(3, (float)(section.originX - geometry.originX));
                        matrix.put(4, 0.0F).put(5, 1.0F).put(6, 0.0F)
                            .put(7, (float)(section.originY - geometry.originY));
                        matrix.put(8, 0.0F).put(9, 0.0F).put(10, 1.0F)
                            .put(11, (float)(section.originZ - geometry.originZ));
                    });
                    instance.instanceCustomIndex(geometry.materialLayout.baseTriangle(section));
                    instance.mask(DynamicTlasInstanceWriter.ALL_RAY_MASK);
                    instance.instanceShaderBindingTableRecordOffset(0);
                    instance.flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
                    instance.accelerationStructureReference(cached.bottomLevel.deviceAddress);
                }
                long dummyAddress = sectionBlas.get(0).bottomLevel.deviceAddress;
                for (int slot = 0; slot < dynamicSlotCapacity; slot++) {
                    VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                        address + (long)(sectionBlas.size() + slot) * VkAccelerationStructureInstanceKHR.SIZEOF);
                    writeUntracedInstance(instance, dummyAddress);
                }
                for (int slot = minimumCapacity; slot < instanceCapacity; slot++) {
                    VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                        address + (long)slot * VkAccelerationStructureInstanceKHR.SIZEOF);
                    writeUntracedInstance(instance, dummyAddress);
                }
            }
        }

        private static void writeUntracedInstance(
            VkAccelerationStructureInstanceKHR instance, long dummyBlasAddress
        ) {
            instance.transform(transform -> {
                FloatBuffer matrix = transform.matrix();
                matrix.put(0, 1.0F).put(1, 0.0F).put(2, 0.0F).put(3, 0.0F);
                matrix.put(4, 0.0F).put(5, 1.0F).put(6, 0.0F).put(7, 0.0F);
                matrix.put(8, 0.0F).put(9, 0.0F).put(10, 1.0F).put(11, 0.0F);
            });
            instance.mask(DynamicTlasInstanceWriter.UNTRACED_INSTANCE_MASK)
                .instanceCustomIndex(0).instanceShaderBindingTableRecordOffset(0)
                .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                .accelerationStructureReference(dummyBlasAddress);
        }

        private static NativeBuffer uploadFloatBuffer(VulkanDevice device, float[] values, int usage) {
            NativeBuffer buffer = NativeBuffer.create(device, (long)values.length * Float.BYTES, usage, true);
            try {
                writeFloatBuffer(buffer, values);
                return buffer;
            } catch (Throwable throwable) {
                buffer.close();
                throw throwable;
            }
        }

        private static void writeFloatBuffer(NativeBuffer buffer, float[] values) {
            try (NativeBuffer.Mapped mapped = buffer.map()) {
                mapped.buffer().asFloatBuffer().put(values);
            }
        }

        private static NativeBuffer uploadLightDataBuffer(VulkanDevice device, RayTracingLightTree.Data data) {
            NativeBuffer buffer = NativeBuffer.create(device,
                RayTracingMaterialBuffer.allocationBytes((long)data.words().length * Integer.BYTES, 0),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
            try {
                try (NativeBuffer.Mapped mapped = buffer.map()) {
                    RayTracingMaterialBuffer.writeLightData(mapped, data);
                }
                return buffer;
            } catch (Throwable throwable) {
                RtResourceRollback.attempt(throwable, buffer::close);
                throw throwable;
            }
        }

        private static NativeBuffer uploadIntBuffer(VulkanDevice device, int[] values, int usage) {
            NativeBuffer buffer = NativeBuffer.create(device, (long)values.length * Integer.BYTES, usage, true);
            try {
                try (NativeBuffer.Mapped mapped = buffer.map()) {
                    mapped.buffer().asIntBuffer().put(values);
                }
                return buffer;
            } catch (Throwable throwable) {
                buffer.close();
                throw throwable;
            }
        }

        private void updateSceneDescriptors(
            AccelerationStructure nextTopLevel,
            NativeBuffer nextMaterial,
            NativeBuffer nextLightData,
            NativeBuffer nextPbr,
            long materialRange,
            long pbrRange,
            boolean updatePbrDescriptor
        ) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkWriteDescriptorSetAccelerationStructureKHR accelerationInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType$Default().pAccelerationStructures(stack.longs(nextTopLevel.handle));
                VkDescriptorBufferInfo.Buffer materialInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(nextMaterial.buffer).offset(0).range(materialRange);
                VkDescriptorBufferInfo.Buffer lightDataInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(nextLightData.buffer).offset(0).range(nextLightData.size);
                VkDescriptorBufferInfo.Buffer pbrInfo = updatePbrDescriptor
                    ? VkDescriptorBufferInfo.calloc(1, stack).buffer(nextPbr.buffer).offset(0).range(pbrRange)
                    : null;
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(updatePbrDescriptor ? 4 : 3, stack);
                writes.get(0).sType$Default().pNext(accelerationInfo).dstSet(this.descriptorSet)
                    .dstBinding(0).descriptorCount(1)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                writes.get(1).sType$Default().dstSet(this.descriptorSet).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(materialInfo);
                writes.get(2).sType$Default().dstSet(this.descriptorSet).dstBinding(26).descriptorCount(1)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(lightDataInfo);
                if (updatePbrDescriptor) {
                    writes.get(3).sType$Default().dstSet(this.descriptorSet).dstBinding(5).descriptorCount(1)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(pbrInfo);
                }
                VK10.vkUpdateDescriptorSets(this.vkDevice, writes, null);
            }
        }

        private RtestFsrCamera cameraState(Camera camera) {
            var forward = camera.forwardVector();
            var left = camera.leftVector();
            var up = camera.upVector();
            float aspect = (float)this.displayWidth / (float)this.displayHeight;
            float tanHalfFov = (float)Math.tan(Math.toRadians(camera.getFov()) * 0.5);
            var position = camera.position();
            Matrix4f viewRotation = camera.getViewRotationMatrix(new Matrix4f());
            Matrix4f viewProjection = camera.getViewRotationProjectionMatrix(new Matrix4f());
            Matrix4f projection = new Matrix4f(viewProjection)
                .mul(new Matrix4f(viewRotation).invert());
            Matrix4f inverseViewProjection = new Matrix4f(viewProjection).invert();
            return new RtestFsrCamera(
                1.0F / (tanHalfFov * aspect),
                1.0F / tanHalfFov,
                position.x, position.y, position.z,
                forward.x(), forward.y(), forward.z(),
                -left.x(), -left.y(), -left.z(),
                up.x(), up.y(), up.z(),
                projection, viewRotation, inverseViewProjection);
        }

        private void updateCamera(ClientLevel level, Camera camera, RtestFsr3Upscaler.FrameToken token,
                                  RtestFsrCamera currentCamera) {
            int currentFrame = frameIndex++;
            RtestFsrCamera previous = previousFsrCamera == null ? currentCamera : previousFsrCamera;
            RtestFsrSettings.Jitter jitter = token.jitter();
            RayTracingClientConfig config = RayTracingClientConfig.INSTANCE;
            float sunIntensity = config.sunIntensity.get().floatValue();
            boolean sunDaylightIntensityEnabled = config.sunDaylightIntensityEnabled.get();
            float sunDaylightPeakIntensity = config.sunDaylightPeakIntensity.get().floatValue();
            float skyLightLevel = level.environmentAttributes().getDimensionValue(EnvironmentAttributes.SKY_LIGHT_LEVEL);
            float effectiveSunIntensity = SkyboxOpacityCurve.resolveSunIntensity(sunDaylightIntensityEnabled,
                sunIntensity, sunDaylightPeakIntensity, skyLightLevel);
            float sunAngularRadiusDegrees = config.sunAngularRadiusDegrees.get().floatValue();
            int sunShadowSamples = config.sunShadowSamples.get();
            int pbrPackedMode = config.pbrPackedMode();
            float pbrNormalStrength = config.pbrNormalStrength.get().floatValue();
            float pbrEmissionStrength = config.pbrEmissionStrength.get().floatValue();
            float emissionScale = config.emissionScale.get().floatValue();
            float pbrWetnessStrength = config.pbrWetnessStrength.get().floatValue();
            float pbrParallaxDepth = config.pbrParallaxDepth.get().floatValue();
            int pbrParallaxFlags = config.pbrParallaxFlags() | (config.skyImportanceSamplingEnabled.get() ? 8 : 0)
                | (config.skyCdfHardwareEnabled.get() ? 16 : 0);
            boolean volumetricLightingEnabled = config.volumetricLightingEnabled.get();
            float volumetricLightingStrength = config.volumetricLightingStrength.get().floatValue();
            float volumetricFogDensity = config.volumetricFogDensity.get().floatValue();
            int atmosphereAltitudeOffsetMeters = config.atmosphereAltitudeOffsetMeters.get();
            int volumetricLightingQuality = config.volumetricLightingQuality.get();
            float skyboxTextureOpacity = config.skyboxTextureOpacity.get().floatValue();
            boolean skyboxDaylightOpacityEnabled = config.skyboxDaylightOpacityEnabled.get();
            boolean moonEnabled = config.moonEnabled.get();
            this.aerialPerspectiveEnabled = this.atmosphere != null
                && level.dimensionType().hasSkyLight()
                && volumetricLightingEnabled
                && volumetricLightingStrength > 1.0e-4f;
            if (this.lastPbrPackedMode != pbrPackedMode
                || Float.compare(this.lastPbrNormalStrength, pbrNormalStrength) != 0
                || Float.compare(this.lastPbrEmissionStrength, pbrEmissionStrength) != 0
                || Float.compare(this.lastEmissionScale, emissionScale) != 0
                || Float.compare(this.lastPbrWetnessStrength, pbrWetnessStrength) != 0
                || Float.compare(this.lastPbrParallaxDepth, pbrParallaxDepth) != 0
                || this.lastPbrParallaxFlags != pbrParallaxFlags
                || this.lastVolumetricLightingEnabled != volumetricLightingEnabled
                || Float.compare(this.lastVolumetricLightingStrength, volumetricLightingStrength) != 0
                || Float.compare(this.lastVolumetricFogDensity, volumetricFogDensity) != 0
                || this.lastAtmosphereAltitudeOffsetMeters != atmosphereAltitudeOffsetMeters
                || this.lastVolumetricLightingQuality != volumetricLightingQuality
                || Float.compare(this.lastSkyboxTextureOpacity, skyboxTextureOpacity) != 0
                || this.lastSkyboxDaylightOpacityEnabled != skyboxDaylightOpacityEnabled
                || this.lastMoonEnabled != moonEnabled
                || Float.compare(this.lastSunIntensity, sunIntensity) != 0
                || this.lastSunDaylightIntensityEnabled != sunDaylightIntensityEnabled
                || Float.compare(this.lastSunDaylightPeakIntensity, sunDaylightPeakIntensity) != 0
                || Float.compare(this.lastSunAngularRadiusDegrees, sunAngularRadiusDegrees) != 0
                || this.lastSunShadowSamples != sunShadowSamples) {
                // Material interpretation changes invalidate temporal samples just like a
                // geometry replacement; otherwise the old BRDF leaks into the new PBR mode.
                this.fsr.requestReset();
                this.lastPbrPackedMode = pbrPackedMode;
                this.lastPbrNormalStrength = pbrNormalStrength;
                this.lastPbrEmissionStrength = pbrEmissionStrength;
                this.lastEmissionScale = emissionScale;
                this.lastPbrWetnessStrength = pbrWetnessStrength;
                this.lastPbrParallaxDepth = pbrParallaxDepth;
                this.lastPbrParallaxFlags = pbrParallaxFlags;
                this.lastVolumetricLightingEnabled = volumetricLightingEnabled;
                this.lastVolumetricLightingStrength = volumetricLightingStrength;
                this.lastVolumetricFogDensity = volumetricFogDensity;
                this.lastAtmosphereAltitudeOffsetMeters = atmosphereAltitudeOffsetMeters;
                this.lastVolumetricLightingQuality = volumetricLightingQuality;
                this.lastSkyboxTextureOpacity = skyboxTextureOpacity;
                this.lastSkyboxDaylightOpacityEnabled = skyboxDaylightOpacityEnabled;
                this.lastMoonEnabled = moonEnabled;
                this.lastSunIntensity = sunIntensity;
                this.lastSunDaylightIntensityEnabled = sunDaylightIntensityEnabled;
                this.lastSunDaylightPeakIntensity = sunDaylightPeakIntensity;
                this.lastSunAngularRadiusDegrees = sunAngularRadiusDegrees;
                this.lastSunShadowSamples = sunShadowSamples;
                LOGGER.info("RTest volumetric lighting config: enabled={}, strength={}, fogDensity={}, quality={}",
                    volumetricLightingEnabled, volumetricLightingStrength, volumetricFogDensity,
                    volumetricLightingQuality);
            }
            try (NativeBuffer.Mapped mapped = cameraBuffer.map()) {
                ByteBuffer buffer = mapped.buffer();
                Vector3d position = new Vector3d(currentCamera.x(), currentCamera.y(), currentCamera.z());
                Vector3f forward = new Vector3f(
                    currentCamera.forwardX(), currentCamera.forwardY(), currentCamera.forwardZ());
                Vector3f left = new Vector3f(
                    -currentCamera.rightX(), -currentCamera.rightY(), -currentCamera.rightZ());
                Vector3f up = new Vector3f(
                    currentCamera.upX(), currentCamera.upY(), currentCamera.upZ());
                float aspect = currentCamera.projectionM11() / currentCamera.projectionM00();
                float tanHalfFov = 1.0F / currentCamera.projectionM11();
                BlockPos cameraBlock = BlockPos.containing(position.x, position.y, position.z);
                var cameraFluid = level.getFluidState(cameraBlock);
                boolean cameraInWater = cameraFluid.is(net.minecraft.tags.FluidTags.WATER)
                    && position.y - cameraBlock.getY() < cameraFluid.getHeight(level, cameraBlock);
                long liveClockTicks = Math.floorMod(level.getOverworldClockTime(), 24000L);
                float liveRain = Math.max(0.0F, Math.min(1.0F, level.getRainLevel(1.0F)));
                float liveThunder = Math.max(0.0F, Math.min(1.0F, level.getThunderLevel(1.0F)));
                OfflineRenderController.Environment environment = OfflineRenderController.environment(
                    liveClockTicks, liveRain, liveThunder);
                long clockTicks = environment.clockTicks();
                float configuredSunOffset = RayTracingClientConfig.INSTANCE.sunAngleOffset.get().floatValue();
                if (sunClockLevel != level || sunClockTicks != clockTicks
                    || Float.compare(sunClockOffset, configuredSunOffset) != 0) {
                    // EnvironmentAttributeSystem is invalidated by ClientLevel.tick;
                    // this cache makes the 20 TPS contract explicit for the RT path.
                    synchronizedSunAngle = (float)Math.toRadians(
                        level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, cameraBlock)
                            + configuredSunOffset
                    );
                    sunClockLevel = level;
                    sunClockTicks = clockTicks;
                    sunClockOffset = configuredSunOffset;
                }
                float sunAngle = synchronizedSunAngle;
                float rain = environment.rain();
                float thunder = environment.thunder();
                float sunAzimuth = (float)Math.toRadians(
                    RayTracingClientConfig.INSTANCE.sunAzimuthOffset.get().floatValue());
                float sunHeight = (float)Math.cos(sunAngle);
                float daylight = Math.max(0.0F, Math.min(1.0F, (sunHeight + 0.12F) / 0.30F));
                daylight = daylight * daylight * (3.0F - 2.0F * daylight);
                if (!level.dimensionType().hasSkyLight()) {
                    daylight = 0.0F;
                }
                float night = 1.0F - daylight;
                float moonAngle = (float)Math.toRadians(level.environmentAttributes().getValue(
                    EnvironmentAttributes.MOON_ANGLE, cameraBlock) + configuredSunOffset);
                float moonHorizontal = (float)Math.sin(moonAngle);
                float moonDirectionX = -moonHorizontal * (float)Math.cos(sunAzimuth);
                float moonDirectionY = (float)Math.cos(moonAngle);
                float moonDirectionZ = moonHorizontal * (float)Math.sin(sunAzimuth);
                int moonPhase = level.dimensionType().hasSkyLight()
                    ? level.environmentAttributes().getValue(EnvironmentAttributes.MOON_PHASE, cameraBlock).index()
                    : 4;
                this.currentMoonSkyActive = moonEnabled && level.dimensionType().hasSkyLight()
                    && this.lastMoonIntensity > 0.0F && moonPhase != 4;
                float moonPhaseToken = this.currentMoonSkyActive
                    ? moonPhase + 1.0F + this.lastMoonIntensity * 0.25F : 0.0F;
                this.currentMoonDirectionY = moonDirectionY;
                float timeOfDay = clockTicks / 24000.0F;
                buffer.putFloat(0, (float)(position.x - geometry.originX));
                buffer.putFloat(4, (float)(position.y - geometry.originY));
                buffer.putFloat(8, (float)(position.z - geometry.originZ));
                // Spare origin.w/environment.w/jitter.zw: moon xyz and phase+1+intensity/4.
                // Preserve the 304-byte camera layout; zero phase token disables the moon.
                buffer.putFloat(12, moonDirectionX);
                buffer.putFloat(16, forward.x()).putFloat(20, forward.y()).putFloat(24, forward.z()).putFloat(28, (float)Math.toRadians(sunAngularRadiusDegrees));
                buffer.putFloat(32, -left.x()).putFloat(36, -left.y()).putFloat(40, -left.z()).putFloat(44, sunShadowSamples);
                buffer.putFloat(48, up.x()).putFloat(52, up.y()).putFloat(56, up.z())
                    .putFloat(60, PersistentRtPolicy.mode(config.rtEvaluationMode.get()));
                buffer.putFloat(64, tanHalfFov).putFloat(68, aspect)
                    // parameters.z carries the RT diagnostic view selector; it is never used to
                    // scale physical path throughput.
                    .putFloat(72, RayTracingClientConfig.INSTANCE.debugView.get())
                    .putFloat(76, RayTracingClientConfig.INSTANCE.giBounces.get());
                float maxTraceDistance = (float)((geometry.renderDistanceChunks + 1) * 16.0 * Math.sqrt(2.0));
                // Minecraft's SkyRenderer rotates the sun quad to the world-space direction
                // (-sin(angle), cos(angle), 0) before the optional azimuth turn. Keep this sign
                // identical for direct-light sampling, shadow rays, the RT sun disk and NRD's
                // history key; mirroring the horizontal component makes light and shadows appear
                // on opposite sides of the scene.
                float sunHorizontal = (float)Math.sin(sunAngle);
                this.currentSunDirectionX = -sunHorizontal * (float)Math.cos(sunAzimuth);
                this.currentSunDirectionY = (float)Math.cos(sunAngle);
                this.currentSunDirectionZ = sunHorizontal * (float)Math.sin(sunAzimuth);
                buffer.putFloat(80, this.currentSunDirectionX)
                    .putFloat(84, this.currentSunDirectionY)
                    .putFloat(88, this.currentSunDirectionZ)
                    .putFloat(92, maxTraceDistance);
                buffer.putFloat(96, effectiveSunIntensity)
                    .putFloat(100, RayTracingClientConfig.INSTANCE.shadowStrength.get().floatValue())
                    // settings.z carries the optional Prime-inspired aerial volume strength;
                    // zero keeps the shader path completely dormant without changing the UBO ABI.
                    .putFloat(104, RayTracingClientConfig.INSTANCE.volumetricLightingEnabled.get()
                        ? RayTracingClientConfig.INSTANCE.volumetricLightingStrength.get().floatValue()
                        : 0.0F)
                    // settings.w carries the atmosphere quality tier: 1=performance,
                    // 2=balanced, 3=quality.
                    .putFloat(108, RayTracingClientConfig.INSTANCE.volumetricLightingQuality.get());
                // environment.x remains the legacy RGB fog input. In physical mode the same
                // user control builds a new medium generation instead of scaling final color.
                // environment.y/z carry the authored sun and ambient color temperatures (Kelvin).
                buffer.putFloat(112, RayTracingClientConfig.INSTANCE.volumetricFogDensity.get().floatValue())
                    .putFloat(116, RayTracingClientConfig.INSTANCE.sunColorTemperature.get().floatValue())
                    .putFloat(120, RayTracingClientConfig.INSTANCE.ambientColorTemperature.get().floatValue())
                    .putFloat(124, moonDirectionY);
                // random.w carries the Minecraft game tick for animated PBR companion maps;
                // render-frame jitter remains in the dedicated jitter fields below.
                buffer.putFloat(128, Float.intBitsToFloat(currentFrame))
                    .putFloat(132, Float.intBitsToFloat(0x243f6a88))
                    .putFloat(136, jitter.x())
                    .putFloat(140, Float.intBitsToFloat((int)level.getGameTime()));
                putCameraState(buffer, 144, previous, geometry);
                buffer.putFloat(224, jitter.x()).putFloat(228, jitter.y())
                    .putFloat(232, moonDirectionZ).putFloat(236, moonPhaseToken);
                // x=time of day, y=rain, z=thunder, w=night factor.
                buffer.putFloat(240, timeOfDay).putFloat(244, rain)
                    .putFloat(248, thunder).putFloat(252, night);
                int dynamicMaterialStart = Math.toIntExact(dynamicMaterialBase(geometry));
                // beginFrame resolved this mode before the camera upload, so the shader
                // never writes guides for a different consumer than this frame schedules.
                buffer.putFloat(256, dynamicMaterialStart)
                    .putFloat(260, DYNAMIC_SLOT_MATERIAL_TRIANGLES)
                    .putFloat(264, dynamicSlotCapacity)
                    .putFloat(268, this.fsr.denoiserMode().shaderSignal());
                // x packs format in the low byte and feature flags above it; y normal strength;
                // z authored emission multiplier; w rain wetness strength.
                buffer.putFloat(272, pbrPackedMode)
                    .putFloat(276, pbrNormalStrength)
                    .putFloat(280, pbrEmissionStrength);
                buffer.putFloat(284, pbrWetnessStrength);
                // y carries the area-light radiance calibration. Keeping it separate from
                // pbrSettings.z lets authored emissive pixels illuminate the room at the same
                // scale as vanilla block lights without increasing the visible lamp surface.
                buffer.putFloat(288, pbrParallaxDepth)
                    .putFloat(292, emissionScale)
                    // Previously unused lane: legacy PNG sampling; atmosphere LUT is independent.
                    .putFloat(296, this.skyboxTextureEnabled ? 1.0F : 0.0F)
                    .putFloat(300, pbrParallaxFlags | (cameraInWater ? 4 : 0));
                int evaluationMode = PersistentRtPolicy.mode(config.rtEvaluationMode.get());
                if (evaluationMode == 2) {
                    buffer.putFloat(104, 0.0F);
                    this.aerialPerspectiveEnabled = false;
                }
                this.persistentLighting.prepare(this.descriptorSet, this.geometry, this.atmosphere,
                    SkyboxOpacityCurve.resolveOpacity(skyboxDaylightOpacityEnabled, skyboxTextureOpacity,
                        skyLightLevel, level.getOverworldClockTime()), buffer, evaluationMode);
            }
            if (this.atmosphere != null) {
                this.atmosphereEyeRadiusKm = com.rtest.client.atmosphere.AtmosphereCoordinates.eyeRadiusKm(
                    currentCamera.y(), atmosphereSettings());
                // Read unquantized dimension sky brightness, not a block/player light sample.
                // Daily/weather changes must not repeatedly reset NRD/FSR histories.
                float effectiveSkyboxTextureOpacity = SkyboxOpacityCurve.resolveOpacity(
                    skyboxDaylightOpacityEnabled, skyboxTextureOpacity, skyLightLevel, level.getOverworldClockTime());
                try (NativeBuffer.Mapped mapped = this.atmosphereCameraBuffer.map()) {
                    mapped.buffer().putFloat(0, this.atmosphereEyeRadiusKm)
                        .putFloat(4, effectiveSkyboxTextureOpacity)
                        .putFloat(8, RayTracingClientConfig.INSTANCE.volumetricShadowSamples.get())
                        .putFloat(12, level.dimensionType().hasSkyLight() ? 1.0F : 0.0F);
                }
            }
            updateTerrainTraversalCamera(currentCamera);
            previousFsrCamera = currentCamera;
        }

        private void updateTerrainTraversalCamera(RtestFsrCamera currentCamera) {
            if (!this.terrainTraversalEnabled || this.terrainTraversalParamsBuffer == null) {
                return;
            }
            try (NativeBuffer.Mapped mapped = this.terrainTraversalParamsBuffer.map()) {
                ByteBuffer buffer = mapped.buffer().order(ByteOrder.nativeOrder());
                writeTerrainTraversalProjection(buffer, currentCamera);
                float cameraX = (float)currentCamera.x();
                float cameraY = (float)currentCamera.y();
                float cameraZ = (float)currentCamera.z();
                float renderDistance = (float)((this.geometry.renderDistanceChunks + 1) * 16.0 * Math.sqrt(2.0));
                buffer.putFloat(64, cameraX).putFloat(68, cameraY).putFloat(72, cameraZ)
                    .putFloat(76, renderDistance);
                buffer.putFloat(80, this.outputWidth).putFloat(84, this.outputHeight)
                    .putFloat(88, 0.0F).putFloat(92, 0.0F);
                buffer.putInt(96, this.terrainTraversalNodeCount)
                    .putInt(100, this.sectionBlas.size() + this.dynamicSlotCapacity)
                    .putInt(104, 1) // reversed-Z
                    .putInt(108, 0);
                buffer.putFloat(112, (float)this.geometry.originX)
                    .putFloat(116, (float)this.geometry.originY)
                    .putFloat(120, (float)this.geometry.originZ)
                    .putFloat(124, 0.0F);
                long dummyAddress = this.sectionBlas.get(0).bottomLevel.deviceAddress;
                buffer.putInt(128, (int)dummyAddress)
                    .putInt(132, (int)(dummyAddress >>> 32))
                    .putInt(136, 0).putInt(140, 0);
            }
        }

        /** Writes the column-major camera projection shared by the GPU terrain traversal shader. */
        static void writeTerrainTraversalProjection(ByteBuffer buffer, RtestFsrCamera currentCamera) {
            if (buffer == null || currentCamera == null || buffer.capacity() < 64) {
                throw new IllegalArgumentException("terrain traversal projection buffer is too small");
            }
            float rightX = currentCamera.rightX();
            float rightY = currentCamera.rightY();
            float rightZ = currentCamera.rightZ();
            float upX = currentCamera.upX();
            float upY = currentCamera.upY();
            float upZ = currentCamera.upZ();
            float forwardX = currentCamera.forwardX();
            float forwardY = currentCamera.forwardY();
            float forwardZ = currentCamera.forwardZ();
            float cameraX = (float)currentCamera.x();
            float cameraY = (float)currentCamera.y();
            float cameraZ = (float)currentCamera.z();
            float tanHalfFov = 1.0F / currentCamera.projectionM11();
            float aspect = currentCamera.projectionM11() / currentCamera.projectionM00();
            float scaleX = 1.0F / Math.max(tanHalfFov * aspect, 1.0e-5F);
            float scaleY = 1.0F / Math.max(tanHalfFov, 1.0e-5F);
            // This is a conservative reversed-Z projection: clip z is the near-depth
            // constant and clip w is forward distance, matching the FSR depth 0.05/viewZ
            // convention used by the traversal shader.
            // GLSL mat4 values are column-major. The four clip rows are
            //   x = scaleX * dot(right, world-camera)
            //   y = scaleY * dot(up, world-camera)
            //   z = 0.05
            //   w = dot(forward, world-camera)
            // Each four-float group is one column in the std140 mat4.
            float rightTranslation = -scaleX * (rightX * cameraX + rightY * cameraY + rightZ * cameraZ);
            float upTranslation = -scaleY * (upX * cameraX + upY * cameraY + upZ * cameraZ);
            float forwardTranslation = -(forwardX * cameraX + forwardY * cameraY + forwardZ * cameraZ);
            buffer.putFloat(0, scaleX * rightX).putFloat(4, scaleY * upX)
                .putFloat(8, 0.0F).putFloat(12, forwardX);
            buffer.putFloat(16, scaleX * rightY).putFloat(20, scaleY * upY)
                .putFloat(24, 0.0F).putFloat(28, forwardY);
            buffer.putFloat(32, scaleX * rightZ).putFloat(36, scaleY * upZ)
                .putFloat(40, 0.0F).putFloat(44, forwardZ);
            buffer.putFloat(48, rightTranslation).putFloat(52, upTranslation)
                .putFloat(56, 0.05F).putFloat(60, forwardTranslation);
        }

        private static void putCameraState(ByteBuffer buffer, int offset, RtestFsrCamera camera,
                                           SceneGeometry geometry) {
            buffer.putFloat(offset, (float)(camera.x() - geometry.originX))
                .putFloat(offset + 4, (float)(camera.y() - geometry.originY))
                .putFloat(offset + 8, (float)(camera.z() - geometry.originZ))
                .putFloat(offset + 12, 0.0F);
            buffer.putFloat(offset + 16, camera.forwardX()).putFloat(offset + 20, camera.forwardY())
                .putFloat(offset + 24, camera.forwardZ()).putFloat(offset + 28, 0.0F);
            buffer.putFloat(offset + 32, camera.rightX()).putFloat(offset + 36, camera.rightY())
                .putFloat(offset + 40, camera.rightZ()).putFloat(offset + 44, 0.0F);
            buffer.putFloat(offset + 48, camera.upX()).putFloat(offset + 52, camera.upY())
                .putFloat(offset + 56, camera.upZ()).putFloat(offset + 60, 0.0F);
            float previousTan = 1.0F / camera.projectionM11();
            float previousAspect = camera.projectionM11() / camera.projectionM00();
            buffer.putFloat(offset + 64, previousTan).putFloat(offset + 68, previousAspect)
                .putFloat(offset + 72, 0.0F).putFloat(offset + 76, 0.0F);
        }

        private void synchronizePbrMaterials() {
            if (this.pbrMaterials == null) {
                return;
            }
            long startNanos = System.nanoTime();
            List<RayTracingPbrMaterials.GpuUpdate> updates =
                this.pbrMaterials.gpuUpdatesAfter(this.pbrMapCount);
            if (updates.isEmpty()) {
                return;
            }
            int mapCount = this.pbrMaterials.loadedMapCount();
            // Keep binding 5 and its descriptor range stable. Metadata and pixels are appended to
            // the same host-visible allocation; publish the map count last so shaders cannot see a
            // newly advertised slot before its metadata/pixels are visible.
            try (NativeBuffer.Mapped mapped = this.pbrBuffer.map()) {
                IntBuffer destination = mapped.buffer().asIntBuffer();
                for (RayTracingPbrMaterials.GpuUpdate update : updates) {
                    int[] metadata = update.metadata();
                    for (int index = 0; index < metadata.length; index++) {
                        destination.put(update.metadataOffset() + index, metadata[index]);
                    }
                    int[] normalPixels = update.normalPixels();
                    if (normalPixels != null) {
                        for (int index = 0; index < normalPixels.length; index++) {
                            destination.put(update.normalOffset() + index, normalPixels[index]);
                        }
                    }
                    int[] specularPixels = update.specularPixels();
                    if (specularPixels != null) {
                        for (int index = 0; index < specularPixels.length; index++) {
                            destination.put(update.specularOffset() + index, specularPixels[index]);
                        }
                    }
                }
                destination.put(0, mapCount);
            }
            this.pbrMapCount = mapCount;
            LOGGER.info("RTest appended {} dynamic PBR companion texture sets (duration={} ms)",
                updates.size(), (System.nanoTime() - startNanos) / 1_000_000L);
        }

        /**
         * Retires the previous RT submission immediately before its mapped resources are reused.
         * The old implementation created and destroyed a command encoder for every frame while
         * leaving its fence pending; on RADV that allowed command-pool teardown to race the queue
         * and caused native SIGSEGVs. A persistent encoder owns the pool for its whole pass life,
         * and this single retirement point keeps the mapped buffers, TLAS inputs, query pool and
         * FSR history synchronized. The normal dispatch path invokes it after submit so the
         * active Minecraft target is complete before later hand/UI passes run.
         */
        private void waitForPreviousFrame(RayTracingFrameTiming timing) {
            GpuFence fence = this.pendingFrameFence;
            if (fence == null) {
                // No submission is not evidence that the old TLAS stopped referencing retired BLAS.
                return;
            }
            long fenceWaitStart = System.nanoTime();
            boolean completed = false;
            try {
                if (!fence.awaitCompletion(5_000_000_000L)) {
                    throw new IllegalStateException("Timed out waiting for previous RTest RT submission");
                }
                completed = true;
            } finally {
                timing.add(RayTracingFrameTiming.Segment.FENCE_WAIT_CPU, fenceWaitStart);
                // Keep an unresolved fence attached to the pass. close() can make one final
                // cleanup attempt without forgetting that GPU work may still own resources.
                if (completed) {
                    this.pendingFrameFence = null;
                    closeAndCapture(fence);
                }
            }
            if (this.pendingAtmosphereToken != 0L) {
                this.atmosphere.completed(this.pendingAtmosphereToken);
                this.pendingAtmosphereToken = 0L;
            }
            if (this.pendingMoonAtmosphereToken != 0L) {
                this.atmosphere.moonCompleted(this.pendingMoonAtmosphereToken);
                this.pendingMoonAtmosphereToken = 0L;
            }
            if (this.dynamicBlasRetirement.completed()) this.dynamicInstances.retireCompleted();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                this.persistentLighting.logRetired(this.pendingFrameGpuIndex);
                double[] gpuMilliseconds = readGpuTimestamps(stack);
                if (gpuMilliseconds != null && this.pendingFrameGpuIndex % 120 == 0) {
                    LOGGER.info(
                        "RTest gpu_timing frame={} rt_ms={} post_rt_ms={} total_ms={} terrain_traversal_ms={} period_ns={} atmosphere_lut_ms={} pre_trace_ms={} rt_pipeline_ms={} cache_prepare_ms={} world_lighting_ms={}",
                        this.pendingFrameGpuIndex,
                        formatGpuMs(gpuMilliseconds[0]),
                        formatGpuMs(gpuMilliseconds[1]),
                        formatGpuMs(gpuMilliseconds[2]),
                        formatGpuMs(gpuMilliseconds[3]),
                        gpuTimestampPeriodNs,
                        formatGpuMs(gpuMilliseconds[4]),
                        formatGpuMs(gpuMilliseconds[5]),
                        formatGpuMs(gpuMilliseconds[6]),
                        formatGpuMs(gpuMilliseconds[7]),
                        formatGpuMs(gpuMilliseconds[8]));
                }
                if (this.terrainTraversalEnabled && this.pendingFrameGpuIndex % 120 == 0) {
                    logTerrainTraversalStats(this.pendingFrameGpuIndex);
                }
                // The center-pixel readback is diagnostic only and is intentionally performed
                // after the fence, never while the current submission is still writing output.
                if (this.pendingFrameTimingFrame % 120 == 0) {
                    long readbackStart = System.nanoTime();
                    try (NativeBuffer.Mapped mapped = outputBuffer.map()) {
                        Vma.vmaInvalidateAllocation(device.vma(), outputBuffer.allocation, 0L, VK10.VK_WHOLE_SIZE);
                        int centerOffset = Math.multiplyExact(
                            Math.addExact(Math.multiplyExact(outputHeight / 2, outputWidth), outputWidth / 2),
                            4
                        );
                        this.lastCenterPixel = mapped.buffer().getInt(centerOffset);
                    } finally {
                        timing.add(RayTracingFrameTiming.Segment.READBACK, readbackStart);
                    }
                }
            }
            this.presentedFrame = true;
        }

        int dispatch(ClientLevel level, Camera camera, DynamicEntityGeometry.Frame dynamicFrame) {
            long timingFrame = ++this.dispatchTimingFrame;
            RayTracingFrameTiming timing = this.dispatchTiming;
            timing.reset();
            boolean rendered = false;
            try {
            // Prime offline sessions freeze dynamic geometry together with the camera. Normal
            // rendering also freezes one snapshot until the first/replacement TLAS is complete,
            // then resumes accepting the newest animation frame.
            if (shouldAdoptDynamicFrame(
                    OfflineRenderController.active(), this.topLevelBuilt, this.dynamicFrame == null)) {
                this.dynamicFrame = dynamicFrame;
            }
            DynamicEntityGeometry.Frame effectiveDynamicFrame = this.dynamicFrame;
            // Scene capture and resource matching happen before dispatch() on the render thread.
            // Retire the previous GPU frame only now, immediately before any mapped buffer or
            // temporal image is written again, so that work can overlap with that capture.
            waitForPreviousFrame(timing);
            updateAtmosphereMedium();
            // Cleared before any early return so a frame that did not rebuild its dynamic instances
            // never claims block entities are represented.
            representedBlockEntities = Set.of();
            if (this.dynamicSlotCapacity > 0 && effectiveDynamicFrame != null) {
                long dynamicUpdateStart = System.nanoTime();
                try {
                    boolean canUpdateTopLevel = this.topLevelBuilt || this.topLevelUpdatePending;
                    // Geometry publication recreates or rebinds the instance buffer and clears
                    // every dynamic slot. Re-emit the current frame even when transforms did not
                    // change; otherwise an unchanged entity stays masked in the replacement TLAS.
                    boolean forceDynamicInstanceWrite = !this.topLevelBuilt;
                    boolean dynamicTlasChanged = updateDynamicInstances(effectiveDynamicFrame,
                        forceDynamicInstanceWrite);
                    // A geometry replacement can leave a valid, already-built TLAS with a
                    // freshly zeroed dynamic instance range. Preserve that pending update while
                    // the replacement TLAS is rebuilt; otherwise unchanged entities remain
                    // permanently masked after the rebuild.
                    this.topLevelUpdatePending = this.topLevelUpdatePending
                        || (canUpdateTopLevel && dynamicTlasChanged);
                } finally {
                    timing.add(RayTracingFrameTiming.Segment.DYNAMIC_UPDATE, dynamicUpdateStart);
                }
            }
            if (!this.topLevelBuilt) {
                this.buildAccelerationStructuresIncrementally(timing);
                if (!this.topLevelBuilt) {
                    // Keep the last completed RT frame visible while the next batch of
                    // BLAS/TLAS work completes. Falling through to vanilla here causes a
                    // one-frame flash whenever a Section update invalidates the TLAS.
                    if (this.presentedFrame) {
                        replayLastDisplay(this.device.createCommandEncoder(), timing, false);
                        return this.lastCenterPixel;
                    }
                    return 0;
                }
            }
            long pbrSyncStart = System.nanoTime();
            try {
                synchronizePbrMaterials();
            } finally {
                timing.add(RayTracingFrameTiming.Segment.PBR_SYNC, pbrSyncStart);
            }
            RtestFsrCamera currentCamera = OfflineRenderController.camera(
                cameraState(camera), this.geometry.revision());
            // Lunar phase is now an illumination source. Resolve its discrete change before
            // beginFrame snapshots the reset token, not after camera upload.
            int nextMoonPhaseToken = RayTracingClientConfig.INSTANCE.moonEnabled.get()
                && level.dimensionType().hasSkyLight()
                ? level.environmentAttributes().getValue(EnvironmentAttributes.MOON_PHASE,
                    BlockPos.containing(currentCamera.x(), currentCamera.y(), currentCamera.z())).index() + 1 : 0;
            float nextMoonIntensity = RayTracingClientConfig.INSTANCE.moonIntensity.get().floatValue();
            if (this.lastMoonPhaseToken != nextMoonPhaseToken
                || Float.compare(this.lastMoonIntensity, nextMoonIntensity) != 0) {
                this.fsr.requestReset();
                this.lastMoonPhaseToken = nextMoonPhaseToken;
                this.lastMoonIntensity = nextMoonIntensity;
            }
            int evaluationMode = PersistentRtPolicy.mode(RayTracingClientConfig.INSTANCE.rtEvaluationMode.get());
            boolean persistentEnabled = RayTracingClientConfig.INSTANCE.persistentRtEnabled.get();
            if (evaluationMode != this.lastEvaluationMode || persistentEnabled != this.lastPersistentEnabled) {
                this.fsr.requestReset();
                this.lastEvaluationMode = evaluationMode;
                this.lastPersistentEnabled = persistentEnabled;
            }
            RtestFsr3Upscaler.FrameToken fsrToken = this.fsr.beginFrame(
                currentCamera, this.geometry.revision(), this.atlasImageView, this.atlasSampler);
            updateCamera(level, camera, fsrToken, currentCamera);
            this.fsr.setSunDirection(
                this.currentSunDirectionX, this.currentSunDirectionY, this.currentSunDirectionZ);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBufferHolder holder;
                // Minecraft owns one frame encoder and keeps its command buffer open across
                // renderLevel(), hand rendering, and GUI rendering. Append RT to that encoder so
                // its submission is ordered after the frame clear/world passes. A separate
                // encoder can submit successfully yet be overwritten by Minecraft's still
                // pending clear/render commands at the end of the frame.
                VulkanCommandEncoder frameEncoder = this.device.createCommandEncoder();
                long commandRecordStart = System.nanoTime();
                try {
                    holder = recordAccelerationStructuresAndDispatch(
                        frameEncoder, stack, fsrToken);
                    frameEncoder.execute(holder.commandBuffer);
                } finally {
                    timing.add(RayTracingFrameTiming.Segment.COMMAND_RECORD, commandRecordStart);
                }
                // createCommandEncoder() returns Minecraft's frame-owned encoder. Do not submit
                // here: the hand/UI passes are recorded after this seam and Minecraft must keep
                // the whole frame in one ordered queue submission. The fence captures this
                // encoder's current submit index and is retired at the start of the next RT
                // frame, after Minecraft has actually submitted the frame.
                GpuFence fence = frameEncoder.createFence();
                this.pendingFrameFence = fence;
                this.dynamicBlasRetirement.submitted(holder.tlasBuildRecorded);
                this.pendingFrameTimingFrame = (int)timingFrame;
                this.pendingFrameGpuIndex = frameIndex;
                int rebuiltDynamics = 0;
                this.dynamicInstances.recordBlasBuildCommands(holder.dynamicBuilds.size());
                for (DynamicCachedBlas cached : holder.dynamicBuilds) {
                    if (cached.built) rebuiltDynamics++;
                    cached.built = true;
                    cached.pendingUpdate = false;
                }
                if (rebuiltDynamics > 0 && !reportedPlayerAnimationUpdate) {
                    reportedPlayerAnimationUpdate = true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "RTest dynamic animation: {} BLAS rebuild submitted before TLAS/trace", rebuiltDynamics);
                }
                this.topLevelUpdatePending = false;
                this.dynamicInstances.historyResetSubmitted();
                this.fsr.submitted(fsrToken);
                this.persistentLighting.submitted();
                if (frameIndex % 120 == 0) {
                    RayTracingDynamicInstances.Stats dynamicStats = this.dynamicInstances.stats();
                    LOGGER.info(
                        "RTest dynamic RT scheduling: frame={}, TLAS updates={}, BLAS build commands={}, deferred BLAS updates={}, material uploads={}, metadata uploads={}",
                        frameIndex, dynamicStats.tlasUpdates(), dynamicStats.blasBuildCommands(),
                        dynamicStats.deferredBlasUpdates(), dynamicStats.materialUploads(),
                        dynamicStats.metadataUploads());
                }
                rendered = true;
                return this.lastCenterPixel;
            }
            } finally {
                timing.log(LOGGER, "vulkan_dispatch_cpu", timingFrame, rendered);
            }
        }

        /** Replays the last completed FSR image while the replacement AS is being built. */
        boolean replayLastPresentation(RayTracingFrameTiming timing) {
            if (!this.presentedFrame || this.closed) {
                return false;
            }
            waitForPreviousFrame(timing);
            // This path is entered after dispatch failed and the pass is about to be closed, so
            // it must submit the recovery copy immediately. The normal in-frame replay above
            // stays appended to Minecraft's shared submission.
            replayLastDisplay(this.device.createCommandEncoder(), timing, true);
            return true;
        }

        /** Replays the completed RT image into Minecraft's current frame without submitting early.
         * This is used while a GUI is paused: no new ray-tracing work is recorded, but the last
         * RT world remains underneath the GUI and Minecraft owns the final submission ordering.
         */
        boolean replayLastPresentationInCurrentFrame(RayTracingFrameTiming timing) {
            if (!this.presentedFrame || this.closed) {
                return false;
            }
            waitForPreviousFrame(timing);
            replayLastDisplay(this.device.createCommandEncoder(), timing, false);
            return true;
        }

        private void replayLastDisplay(VulkanCommandEncoder frameEncoder,
                                       RayTracingFrameTiming timing,
                                       boolean submitImmediately) {
            VkCommandBuffer commandBuffer = null;
            long commandRecordStart = System.nanoTime();
            boolean commandBufferEnded = false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                commandBuffer = frameEncoder.allocateAndBeginTransientCommandBuffer();
                imageBarrier(commandBuffer, stack,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        | KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT
                        | KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    this.fsr.displayImage(),
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL);
                imageBarrier(commandBuffer, stack,
                    VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                    this.targetImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkImageCopy.Buffer imageCopy = VkImageCopy.calloc(1, stack);
                imageCopy.srcSubresource(VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1));
                imageCopy.dstSubresource(VkImageSubresourceLayers.calloc(stack)
                        .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                        .baseArrayLayer(0).layerCount(1));
                imageCopy.srcOffset().set(0, 0, 0);
                imageCopy.dstOffset().set(0, 0, 0);
                imageCopy.extent().set(displayWidth, displayHeight, 1);
                VK10.vkCmdCopyImage(commandBuffer, this.fsr.displayImage(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                    this.targetImage, VK10.VK_IMAGE_LAYOUT_GENERAL, imageCopy);
                imageBarrier(commandBuffer, stack,
                    KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                    // Minecraft presents this target with GpuSurface.blitFromTexture().
                    // That path performs a transfer read from the target image, so publish
                    // the copy for TRANSFER_READ rather than leaving the dependency scoped to
                    // a later color-attachment pass.
                    KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                    KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                    this.targetImage,
                    VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_LAYOUT_GENERAL);
                int endResult = VK10.vkEndCommandBuffer(commandBuffer);
                commandBufferEnded = true;
                VulkanUtils.crashIfFailure(device, endResult,
                    "Failed to end last-frame replay command buffer");
                frameEncoder.execute(commandBuffer);
                timing.add(RayTracingFrameTiming.Segment.COMMAND_RECORD, commandRecordStart);
                GpuFence fence = frameEncoder.createFence();
                if (submitImmediately) {
                    try (fence) {
                        frameEncoder.submit();
                        long fenceWaitStart = System.nanoTime();
                        try {
                            if (!fence.awaitCompletion(5_000_000_000L)) {
                                throw new IllegalStateException("Timed out waiting for last-frame replay");
                            }
                        } finally {
                            timing.add(RayTracingFrameTiming.Segment.FENCE_WAIT_CPU, fenceWaitStart);
                        }
                    }
                } else {
                    // The frame-owned encoder is submitted by Minecraft after hand and GUI
                    // recording. Retire this fence at the start of the next RT frame just like a
                    // normal dispatch; submitting here would reorder the rest of the frame.
                    this.pendingFrameFence = fence;
                    this.dynamicBlasRetirement.submitted(false);
                    this.pendingFrameTimingFrame = (int)this.dispatchTimingFrame;
                    this.pendingFrameGpuIndex = this.frameIndex;
                }
            } catch (Throwable throwable) {
                if (commandBuffer != null && !commandBufferEnded) {
                    VK10.vkEndCommandBuffer(commandBuffer);
                }
                throw throwable;
            }
        }

        int outputWidth() {
            return this.outputWidth;
        }

        int outputHeight() {
            return this.outputHeight;
        }

        private void recordBlas(org.lwjgl.vulkan.VkCommandBuffer commandBuffer, MemoryStack stack,
                                AccelerationStructure blas, boolean update) {
            if (blas.closed || blas.storage.closed || blas.inputBuffer == null || blas.inputBuffer.closed) {
                throw new IllegalStateException("Attempted to build a closed acceleration structure resource");
            }
            try (MemoryStack blasStack = MemoryStack.stackPush()) {
                long scratchAlignment = Math.max(1L, this.accelerationLimits.minScratchAlignment());
                long scratchBase = this.scratchBuffer.deviceAddress();
                long scratchAddress = VulkanAccelerationResources.alignDeviceAddress(scratchBase, scratchAlignment);
                long inputAddress = blas.inputBuffer.deviceAddress();
                VulkanAccelerationResources.validateBuildArguments(
                    blas.topLevel,
                    inputAddress,
                    blas.inputBuffer.size,
                    blas.primitiveCount,
                    scratchBase,
                    scratchAddress,
                    this.scratchBuffer.size,
                    blas.scratchSize,
                    scratchAlignment,
                    blas.storage.size,
                    blas.handle,
                    update
                );
                var info = blas.buildInfo(blasStack, inputAddress, scratchAddress, update);
                var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(blasStack).primitiveCount(blas.primitiveCount);
                KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, info, blasStack.pointers(range.address()));
            }
            // Every build reuses the same scratch region. Serialize BOTH reads and writes.
            barrier(commandBuffer, stack,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                    | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                    | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        }

        /** Vulkan's AS scratch alignment applies to the device address, not just buffer size. */
        private long scratchDeviceAddress() {
            long alignment = Math.max(1L, this.accelerationLimits.minScratchAlignment());
            return this.scratchBuffer.alignedDeviceAddress(alignment);
        }

        private VkAccelerationStructureBuildGeometryInfoKHR.Buffer topLevelBuildInfo(
            MemoryStack stack, boolean update) {
            if (this.topLevel == null || this.topLevel.closed || this.topLevel.storage.closed
                || this.topLevel.inputBuffer == null || this.topLevel.inputBuffer.closed) {
                throw new IllegalStateException("Attempted to build a closed TLAS resource");
            }
            long scratchAlignment = Math.max(1L, this.accelerationLimits.minScratchAlignment());
            long scratchBase = this.scratchBuffer.deviceAddress();
            long scratchAddress = VulkanAccelerationResources.alignDeviceAddress(scratchBase, scratchAlignment);
            long inputAddress = this.topLevel.inputBuffer.deviceAddress();
            VulkanAccelerationResources.validateBuildArguments(
                true,
                inputAddress,
                this.topLevel.inputBuffer.size,
                this.topLevel.primitiveCount,
                scratchBase,
                scratchAddress,
                this.scratchBuffer.size,
                this.topLevel.scratchSize,
                scratchAlignment,
                this.topLevel.storage.size,
                this.topLevel.handle,
                update
            );
            return this.topLevel.buildInfo(stack, inputAddress, scratchAddress, update);
        }

        private void buildAccelerationStructuresIncrementally(RayTracingFrameTiming timing) {
            List<Object> newlyBuilt = new ArrayList<>();
            long commandRecordStart = System.nanoTime();
            VkCommandBuffer commandBuffer = null;
            boolean commandBufferEnded = false;
            try {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
                barrier(commandBuffer, stack,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);
                for (DynamicCachedBlas cached : this.dynamicInstances.blases()) {
                    if (!needsIncrementalDynamicBuild(this.topLevelBuilt, cached.built, cached.pendingUpdate)
                            || newlyBuilt.size() >= BLAS_BUILDS_PER_FRAME) {
                        continue;
                    }
                    // Rebuild in place instead of using VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR.
                    // Animated submit paths may preserve the primitive count while changing quad
                    // order or other geometry details that Vulkan requires to remain identical for
                    // UPDATE. RADV reports such violations asynchronously as DEVICE_LOST at present.
                    recordBlas(commandBuffer, stack, cached.bottomLevel, false);
                    newlyBuilt.add(cached);
                }
                for (CachedBlas cached : sectionBlas) {
                    if (cached.built || newlyBuilt.size() >= BLAS_BUILDS_PER_FRAME) {
                        continue;
                    }
                    recordBlas(commandBuffer, stack, cached.bottomLevel, false);
                    newlyBuilt.add(cached);
                }
                boolean allBlasBuilt = true;
                for (DynamicCachedBlas cached : this.dynamicInstances.blases()) {
                    if (!cached.built && !newlyBuilt.contains(cached)) {
                        allBlasBuilt = false;
                        break;
                    }
                }
                for (CachedBlas cached : sectionBlas) {
                    if (!cached.built && !newlyBuilt.contains(cached)) {
                        allBlasBuilt = false;
                        break;
                    }
                }
                if (!newlyBuilt.isEmpty()) {
                    barrier(commandBuffer, stack,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                }
                if (allBlasBuilt) {
                    barrier(commandBuffer, stack,
                        VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_ACCESS_HOST_WRITE_BIT,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                    try (MemoryStack tlasStack = MemoryStack.stackPush()) {
                        VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = topLevelBuildInfo(
                        tlasStack, topLevelUpdatePending);
                        VkAccelerationStructureBuildRangeInfoKHR tlasRange = VkAccelerationStructureBuildRangeInfoKHR
                            .calloc(tlasStack).primitiveCount(topLevel.primitiveCount);
                        PointerBuffer tlasRanges = tlasStack.mallocPointer(1).put(tlasRange.address());
                        tlasRanges.flip();
                        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, tlasInfo, tlasRanges);
                    }
                    barrier(commandBuffer, stack,
                        KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                        KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                        KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                        VK12.VK_ACCESS_SHADER_READ_BIT);
                }
                int endResult = VK10.vkEndCommandBuffer(commandBuffer);
                commandBufferEnded = true;
                VulkanUtils.crashIfFailure(device, endResult,
                    "Failed to end incremental acceleration-structure command buffer");
                encoder.execute(commandBuffer);
                timing.add(RayTracingFrameTiming.Segment.COMMAND_RECORD, commandRecordStart);
                try (GpuFence fence = encoder.createFence()) {
                    encoder.submit();
                    long fenceWaitStart = System.nanoTime();
                    try {
                        if (!fence.awaitCompletion(5_000_000_000L)) {
                            throw new IllegalStateException("Timed out waiting for incremental acceleration-structure build");
                        }
                    } finally {
                        timing.add(RayTracingFrameTiming.Segment.FENCE_WAIT_CPU, fenceWaitStart);
                    }
                }
                for (Object cached : newlyBuilt) {
                    if (cached instanceof CachedBlas section) {
                        section.built = true;
                    } else if (cached instanceof DynamicCachedBlas dynamic) {
                        dynamic.built = true;
                        dynamic.pendingUpdate = false;
                    }
                }
                    if (allBlasBuilt) {
                        this.topLevelBuilt = true;
                        this.topLevelUpdatePending = false;
                        // UPDATE may have read the source TLAS's old BLAS references. The build
                        // fence above has retired, so replaced/removed BLAS resources are now safe.
                        this.blasCache.retireCompleted();
                        this.dynamicInstances.retireCompleted();
                    }
                }
            } catch (Throwable throwable) {
                if (commandBuffer != null && !commandBufferEnded) {
                    // VulkanCommandEncoder cannot end a command buffer allocated directly by
                    // allocateAndBeginTransientCommandBuffer during its destroy() path.
                    VK10.vkEndCommandBuffer(commandBuffer);
                }
                throw throwable;
            }
        }

        private void restoreTerrainTraversalInstances() {
            if (this.instanceBuffer == null) {
                return;
            }
            try (NativeBuffer.Mapped mapped = this.instanceBuffer.map()) {
                ByteBuffer buffer = mapped.buffer().order(ByteOrder.nativeOrder());
                for (int index = 0; index < this.sectionBlas.size(); index++) {
                    int offset = index * VkAccelerationStructureInstanceKHR.SIZEOF
                        + RayTracingTerrainTraversalAbi.INSTANCE_CUSTOM_INDEX_OFFSET;
                    int customIndex = buffer.getInt(offset) & RayTracingTerrainTraversalAbi.INSTANCE_CUSTOM_INDEX_MASK;
                    buffer.putInt(offset, customIndex
                        | (RayTracingTerrainTraversalAbi.TERRAIN_INSTANCE_MASK << 24));
                }
            }
        }

        private void recordTerrainTraversal(VkCommandBuffer commandBuffer, MemoryStack stack) {
            // The previous FSR depth image is still in GENERAL here. Build mip 0 from that
            // history, reduce it conservatively, then let the node traversal sample the complete
            // pyramid. The first frame is intentionally skipped because depth is uninitialized.
            barrier(commandBuffer, stack,
                VK10.VK_PIPELINE_STAGE_HOST_BIT,
                VK10.VK_ACCESS_HOST_WRITE_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
            recordTerrainHiZ(commandBuffer, stack);
            VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                this.terrainTraversalPipeline);
            VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                this.terrainTraversalPipelineLayout, 0, stack.longs(this.terrainTraversalDescriptorSet), null);
            int groups = (this.terrainTraversalNodeCount + 63) / 64;
            VK10.vkCmdDispatch(commandBuffer, Math.max(1, groups), 1, 1);
            // VkAccelerationStructureBuildGeometryInfoKHR reads the same instance buffer as
            // the compute shader writes. Keep this dependency explicit before the TLAS UPDATE.
            barrier(commandBuffer, stack,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
        }

        private void recordTerrainHiZ(VkCommandBuffer commandBuffer, MemoryStack stack) {
            int width = (int)this.fsr.terrainHiZWidth();
            int height = (int)this.fsr.terrainHiZHeight();
            imageBarrier(commandBuffer, stack,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR
                    | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                this.fsr.depthImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            imageBarrier(commandBuffer, stack,
                this.terrainHiZInitialized ? VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                    : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                this.terrainHiZInitialized ? VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT : 0L,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                this.fsr.terrainHiZImage(),
                this.terrainHiZInitialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1);
            VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
            copy.srcSubresource(VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                    .baseArrayLayer(0).layerCount(1));
            copy.dstSubresource(VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                    .baseArrayLayer(0).layerCount(1));
            copy.srcOffset().set(0, 0, 0);
            copy.dstOffset().set(0, 0, 0);
            copy.extent().set(width, height, 1);
            VK10.vkCmdCopyImage(commandBuffer, this.fsr.depthImage(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                this.fsr.terrainHiZImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
            imageBarrier(commandBuffer, stack,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_ACCESS_SHADER_READ_BIT,
                this.fsr.terrainHiZImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1);

            int mipLevels = this.fsr.terrainHiZMipLevels();
            for (int level = 1; level < mipLevels; level++) {
                int sourceWidth = Math.max(1, width >> (level - 1));
                int sourceHeight = Math.max(1, height >> (level - 1));
                int targetWidth = Math.max(1, width >> level);
                int targetHeight = Math.max(1, height >> level);
                imageBarrier(commandBuffer, stack,
                    this.terrainHiZInitialized ? VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                        : VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    this.terrainHiZInitialized ? VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT : 0L,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    this.fsr.terrainHiZImage(),
                    this.terrainHiZInitialized ? VK10.VK_IMAGE_LAYOUT_GENERAL : VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_ASPECT_COLOR_BIT, level, 1);
                VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.terrainHiZPipeline);
                VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE,
                    this.terrainTraversalPipelineLayout, 0, stack.longs(this.terrainHiZDescriptorSets[level]), null);
                ByteBuffer push = stack.malloc(16).order(ByteOrder.nativeOrder());
                push.putInt(0, sourceWidth).putInt(4, sourceHeight).putInt(8, 1).putInt(12, 0);
                VK10.vkCmdPushConstants(commandBuffer, this.terrainTraversalPipelineLayout,
                    VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
                VK10.vkCmdDispatch(commandBuffer, (targetWidth + 7) / 8, (targetHeight + 7) / 8, 1);
                imageBarrier(commandBuffer, stack,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_ACCESS_SHADER_READ_BIT,
                    this.fsr.terrainHiZImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    VK10.VK_IMAGE_ASPECT_COLOR_BIT, level, 1);
            }
            imageBarrier(commandBuffer, stack,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                this.fsr.depthImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, VK10.VK_IMAGE_LAYOUT_GENERAL);
            this.terrainHiZInitialized = true;
        }

        private VkCommandBufferHolder recordAccelerationStructuresAndDispatch(
            VulkanCommandEncoder frameEncoder,
            MemoryStack stack,
            RtestFsr3Upscaler.FrameToken fsrToken) {
            VkCommandBuffer commandBuffer = frameEncoder.allocateAndBeginTransientCommandBuffer();
            boolean commandBufferEnded = false;
            try {
            if (gpuTimestampsAvailable) {
                VK10.vkCmdResetQueryPool(commandBuffer, gpuTimestampQueryPool, 0, GPU_TIMESTAMP_COUNT);
            }
            writeGpuTimestamp(commandBuffer, 6, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            if (this.atmosphere != null) {
                this.pendingAtmosphereToken = this.atmosphere.recordSky(commandBuffer,
                    this.atmosphereEyeRadiusKm, this.currentSunDirectionY);
                if (this.currentMoonSkyActive) {
                    this.pendingMoonAtmosphereToken = this.atmosphere.recordMoonSky(commandBuffer,
                        this.atmosphereEyeRadiusKm, this.currentMoonDirectionY);
                }
            }
            writeGpuTimestamp(commandBuffer, 7, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            boolean terrainHistoryUsable = this.terrainTraversalEnabled && this.terrainTraversalPrimed
                && !fsrToken.reset() && !fsrToken.cameraCut();
            boolean terrainTraversalMaskReset = this.terrainTraversalEnabled
                && this.terrainTraversalPrimed && !terrainHistoryUsable;
            writeGpuTimestamp(commandBuffer, 4, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            if (this.terrainTraversalEnabled && !terrainHistoryUsable) {
                // A reset/cut invalidates the depth history just like it invalidates FSR/NRD;
                // rebuilding Hi-Z from an old view could reject a newly visible node.
                this.terrainHiZInitialized = false;
            }
            if (terrainTraversalMaskReset) {
                restoreTerrainTraversalInstances();
            }
            if (terrainHistoryUsable) {
                recordTerrainTraversal(commandBuffer, stack);
            }
            this.fsr.prepareForRayTracing(commandBuffer);
            List<DynamicCachedBlas> dynamicBuilds = new ArrayList<>();
            barrier(commandBuffer, stack, VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_ACCESS_HOST_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                    | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);
            // No outer '!built' gate: an already-built BLAS with new animation vertices also runs.
            for (DynamicCachedBlas cached : this.dynamicInstances.blases()) {
                if (cached.built && !cached.pendingUpdate) continue;
                // BUILD into the existing, correctly-sized AS storage. This avoids UPDATE's
                // strict source-geometry identity contract while retaining stable AS addresses.
                recordBlas(commandBuffer, stack, cached.bottomLevel, false);
                dynamicBuilds.add(cached);
            }
            boolean tlasBuildRecorded = this.topLevelBuilt && (this.topLevelUpdatePending
                || terrainHistoryUsable || terrainTraversalMaskReset);
            if (tlasBuildRecorded) {
                barrier(commandBuffer, stack,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    VK10.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                try (MemoryStack tlasStack = MemoryStack.stackPush()) {
                    VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = topLevelBuildInfo(
                        tlasStack, true);
                    VkAccelerationStructureBuildRangeInfoKHR tlasRange = VkAccelerationStructureBuildRangeInfoKHR
                        .calloc(tlasStack).primitiveCount(topLevel.primitiveCount);
                    PointerBuffer tlasRanges = tlasStack.mallocPointer(1).put(tlasRange.address());
                    tlasRanges.flip();
                    KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(commandBuffer, tlasInfo, tlasRanges);
                }
                barrier(commandBuffer, stack,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            } else if (!dynamicBuilds.isEmpty()) {
                // An in-place BLAS rebuild can happen without a TLAS UPDATE when only animated
                // vertices changed. It still needs an explicit build-write -> trace-read dependency.
                barrier(commandBuffer, stack,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK12.VK_ACCESS_SHADER_READ_BIT);
            }
            writeGpuTimestamp(commandBuffer, 5,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

            writeGpuTimestamp(commandBuffer, 8, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
            this.persistentLighting.recordBeforeTrace(commandBuffer, stack);
            writeGpuTimestamp(commandBuffer, 9, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
            VK10.vkCmdBindPipeline(commandBuffer, KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            VK10.vkCmdBindDescriptorSets(
                commandBuffer,
                KHRRayTracingPipeline.VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR,
                pipelineLayout,
                0,
                stack.longs(descriptorSet),
                null
            );
            long sbtAddress = shaderBindingTable.deviceAddress();
            VkStridedDeviceAddressRegionKHR raygen = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtAddress).stride(sbtStride).size(sbtStride);
            VkStridedDeviceAddressRegionKHR miss = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtAddress + sbtStride).stride(sbtStride).size(3L * sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtAddress + 4L * sbtStride).stride(sbtStride).size(3L * sbtStride);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR, 0, stack.ints(0));
            writeGpuTimestamp(commandBuffer, 0, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(commandBuffer, raygen, miss, hit, callable, outputWidth, outputHeight, 1);
            writeGpuTimestamp(commandBuffer, 1, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            writeGpuTimestamp(commandBuffer, 10, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            int worldWork = this.persistentLighting.trainingCount();
            if (worldWork > 0) {
                barrier(commandBuffer, stack, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_ACCESS_SHADER_WRITE_BIT | VK10.VK_ACCESS_SHADER_READ_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                    VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                VK10.vkCmdPushConstants(commandBuffer, pipelineLayout,
                    KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR, 0, stack.ints(1));
                KHRRayTracingPipeline.vkCmdTraceRaysKHR(commandBuffer, raygen, miss, hit, callable, worldWork, 1, 1);
            }
            writeGpuTimestamp(commandBuffer, 11, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            this.fsr.recordAfterRayTracing(commandBuffer, fsrToken, this.aerialPerspectiveEnabled);
            this.terrainTraversalPrimed = this.terrainTraversalEnabled;
            writeGpuTimestamp(commandBuffer, 2, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            imageBarrier(commandBuffer, stack,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK12.VK_ACCESS_SHADER_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                this.fsr.displayImage(),
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
            // The target is Minecraft's main attachment. This command buffer is appended to the
            // shared encoder after the LevelRenderer frame graph, so synchronize the preceding
            // color-attachment writes before replacing it with the FSR display image.
            imageBarrier(commandBuffer, stack,
                VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                targetImage,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkImageCopy.Buffer imageCopy = VkImageCopy.calloc(1, stack);
            imageCopy.srcSubresource(VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                    .baseArrayLayer(0).layerCount(1));
            imageCopy.dstSubresource(VkImageSubresourceLayers.calloc(stack)
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                    .baseArrayLayer(0).layerCount(1));
            imageCopy.srcOffset().set(0, 0, 0);
            imageCopy.dstOffset().set(0, 0, 0);
            imageCopy.extent().set(displayWidth, displayHeight, 1);
            VK10.vkCmdCopyImage(commandBuffer, this.fsr.displayImage(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                    targetImage, VK10.VK_IMAGE_LAYOUT_GENERAL, imageCopy);
            writeGpuTimestamp(commandBuffer, 3, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarrier(commandBuffer, stack,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                // The normal frame's final operation is the swapchain transfer blit.
                KHRSynchronization2.VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,
                KHRSynchronization2.VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                targetImage,
                VK10.VK_IMAGE_LAYOUT_GENERAL,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
            int endResult = VK10.vkEndCommandBuffer(commandBuffer);
            commandBufferEnded = true;
            VulkanUtils.crashIfFailure(device, endResult, "Failed to end ray-tracing command buffer");
            return new VkCommandBufferHolder(commandBuffer, dynamicBuilds, tlasBuildRecorded);
            } catch (Throwable throwable) {
                if (!commandBufferEnded) {
                    // The pass owns this direct command buffer, not VulkanCommandEncoder's
                    // private currentCommandBuffer slot; finish it before encoder.destroy().
                    VK10.vkEndCommandBuffer(commandBuffer);
                }
                if (this.pendingAtmosphereToken != 0L) {
                    try {
                        this.atmosphere.abandon(this.pendingAtmosphereToken);
                        this.pendingAtmosphereToken = 0L;
                    } catch (Throwable rollbackFailure) {
                        throwable.addSuppressed(rollbackFailure);
                    }
                }
                if (this.pendingMoonAtmosphereToken != 0L) {
                    try {
                        this.atmosphere.moonAbandon(this.pendingMoonAtmosphereToken);
                        this.pendingMoonAtmosphereToken = 0L;
                    } catch (Throwable rollbackFailure) {
                        throwable.addSuppressed(rollbackFailure);
                    }
                }
                throw throwable;
            }
        }

        private void logTerrainTraversalStats(int frame) {
            long triangleCount = 0L;
            long vertexBytes = 0L;
            long blasStorageBytes = 0L;
            int coarseSections = 0;
            for (SceneGeometry.SectionGeometry section : this.geometry.sections) {
                triangleCount += section.triangleCount();
                if (section.terrainNodeKey().level() > 0) coarseSections++;
            }
            for (CachedBlas cached : this.sectionBlas) {
                vertexBytes += cached.vertexBuffer.size;
                blasStorageBytes += cached.bottomLevel.storage.size;
            }
            long tlasStorageBytes = this.topLevel == null ? 0L : this.topLevel.storage.size;
            LOGGER.info(
                "RTest terrain traversal stats: frame={} nodes={} coarse_sections={} native_sections={} triangles={} "
                    + "blas_storage_bytes={} tlas_storage_bytes={} instance_bytes={} metadata_bytes={} address_bytes={} "
                    + "hiz={}x{} mips={} dynamic_slots={}",
                frame,
                this.terrainTraversalNodeCount,
                coarseSections,
                this.geometry.sections.size() - coarseSections,
                triangleCount,
                blasStorageBytes,
                tlasStorageBytes,
                this.instanceBuffer == null ? 0L : this.instanceBuffer.size,
                this.terrainNodeMetadataBuffer == null ? 0L : this.terrainNodeMetadataBuffer.size,
                this.terrainBlasAddressBuffer == null ? 0L : this.terrainBlasAddressBuffer.size,
                this.fsr.terrainHiZWidth(), this.fsr.terrainHiZHeight(), this.fsr.terrainHiZMipLevels(),
                this.dynamicSlotCapacity);
        }

        private void writeGpuTimestamp(VkCommandBuffer commandBuffer, int queryIndex, int stageMask) {
            if (gpuTimestampsAvailable) {
                VK10.vkCmdWriteTimestamp(commandBuffer, stageMask, gpuTimestampQueryPool, queryIndex);
            }
        }

        private double[] readGpuTimestamps(MemoryStack stack) {
            if (!gpuTimestampsAvailable) {
                return null;
            }
            LongBuffer values = stack.mallocLong(GPU_TIMESTAMP_COUNT);
            int result = VK10.vkGetQueryPoolResults(
                vkDevice, gpuTimestampQueryPool, 0, GPU_TIMESTAMP_COUNT,
                values, Long.BYTES, VK10.VK_QUERY_RESULT_64_BIT);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.debug("RTest GPU timestamp query unavailable: result={}", result);
                return null;
            }
            double[] milliseconds = new double[9];
            milliseconds[0] = (values.get(1) - values.get(0)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[1] = (values.get(2) - values.get(1)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[2] = (values.get(3) - values.get(0)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[3] = this.terrainTraversalEnabled
                ? (values.get(5) - values.get(4)) * gpuTimestampPeriodNs / 1_000_000.0 : 0.0;
            milliseconds[4] = (values.get(7) - values.get(6)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[5] = (values.get(0) - values.get(4)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[6] = (values.get(3) - values.get(6)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[7] = (values.get(9) - values.get(8)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[8] = (values.get(11) - values.get(10)) * gpuTimestampPeriodNs / 1_000_000.0;
            return milliseconds;
        }

        private static double formatGpuMs(double value) {
            return Math.round(value * 1000.0) / 1000.0;
        }

        private static void imageBarrier(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            long image,
            int oldLayout,
            int newLayout
        ) {
            imageBarrier(commandBuffer, stack, sourceStage, sourceAccess, destinationStage, destinationAccess,
                image, oldLayout, newLayout, VK10.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1);
        }

        private static void imageBarrier(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess,
            long image,
            int oldLayout,
            int newLayout,
            int aspectMask,
            int baseMipLevel,
            int levelCount
        ) {
            VkImageMemoryBarrier2.Buffer imageBarrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage).dstAccessMask(destinationAccess)
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
            imageBarrier.subresourceRange(new VkImageSubresourceRange(stack.malloc(VkImageSubresourceRange.SIZEOF))
                .aspectMask(aspectMask)
                .baseMipLevel(baseMipLevel).levelCount(levelCount)
                .baseArrayLayer(0).layerCount(1));
            VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(imageBarrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependencyInfo);
        }

        private static void barrier(
            org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
            MemoryStack stack,
            long sourceStage,
            long sourceAccess,
            long destinationStage,
            long destinationAccess
        ) {
            VkMemoryBarrier2.Buffer memoryBarrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
            VkDependencyInfo dependencyInfo = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(memoryBarrier);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependencyInfo);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Throwable failure = null;
            GpuFence pendingFence = this.pendingFrameFence;
            this.pendingFrameFence = null;
            if (pendingFence != null) {
                try {
                    if (!pendingFence.awaitCompletion(5_000_000_000L)) {
                        throw new IllegalStateException("Timed out waiting for pending RTest RT submission during close");
                    }
                } catch (Throwable waitFailure) {
                    failure = waitFailure;
                } finally {
                    failure = closeAndCapture(pendingFence, failure);
                }
            }
            closed = true;
            // The encoder owns two command pools, transient upload allocators, a semaphore and
            // a destruction queue. Waiting for the queue is not enough; omitting destroy() leaks
            // that native state every time the RT pass is recreated. Continue wrapper teardown
            // even if one native close reports a device-loss/runtime failure.
            failure = closeAndCapture(() -> encoder.destroy(), failure);
            if (failure != null) {
                // VulkanCommandEncoder.destroy() normally waits for the graphics queue. Keep a
                // fallback for a failed submit/timeout path where its internal cleanup throws
                // before reaching that wait.
                try {
                    device.graphicsQueue().waitIdle();
                } catch (Throwable waitFailure) {
                    failure.addSuppressed(waitFailure);
                }
            }
            if (gpuTimestampQueryPool != 0L) {
                VK10.vkDestroyQueryPool(vkDevice, gpuTimestampQueryPool, null);
            }
            if (terrainHiZPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, terrainHiZPipeline, null);
            if (terrainTraversalPipeline != 0L) VK10.vkDestroyPipeline(vkDevice, terrainTraversalPipeline, null);
            if (terrainTraversalPipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vkDevice, terrainTraversalPipelineLayout, null);
            if (terrainTraversalDescriptorPool != 0L) VK10.vkDestroyDescriptorPool(vkDevice, terrainTraversalDescriptorPool, null);
            if (terrainTraversalDescriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vkDevice, terrainTraversalDescriptorSetLayout, null);
            if (terrainHiZShaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, terrainHiZShaderModule, null);
            if (terrainTraversalShaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, terrainTraversalShaderModule, null);
            VK10.vkDestroyPipeline(vkDevice, pipeline, null);
            VK10.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
            VK10.vkDestroyDescriptorPool(vkDevice, descriptorPool, null);
            VK10.vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
            for (long shaderModule : shaderModules) {
                if (shaderModule != 0L) {
                    VK10.vkDestroyShaderModule(vkDevice, shaderModule, null);
                }
            }
            failure = closeAndCapture(topLevel, failure);
            failure = closeAndCapture(shaderBindingTable, failure);
            failure = closeAndCapture(outputBuffer, failure);
            failure = closeAndCapture(atmosphereCameraBuffer, failure);
            failure = closeAndCapture(atmosphere, failure);
            failure = closeAndCapture(cameraBuffer, failure);
            failure = closeAndCapture(materialBuffer, failure);
            failure = closeAndCapture(gpuLightTreeBuilder, failure);
            failure = closeAndCapture(lightDataBuffer, failure);
            failure = closeAndCapture(pbrBuffer, failure);
            failure = closeAndCapture(persistentLighting, failure);
            failure = closeAndCapture(terrainTraversalParamsBuffer, failure);
            failure = closeAndCapture(terrainBlasAddressBuffer, failure);
            failure = closeAndCapture(terrainNodeMetadataBuffer, failure);
            failure = closeAndCapture(scratchBuffer, failure);
            failure = closeAndCapture(instanceBuffer, failure);
            failure = closeAndCapture(dynamicMotionMetadataBuffer, failure);
            failure = closeAndCapture(dynamicInstances, failure);
            rethrow(failure);
        }

    private record VkCommandBufferHolder(org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
                                         List<DynamicCachedBlas> dynamicBuilds, boolean tlasBuildRecorded) {
    }

    private static String loadShaderResource(String path) {
        try (var stream = RayTracingVulkanPass.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing shader resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not read shader resource: " + path, exception);
        }
    }

    private static final class GpuLightTreeBuilder implements AutoCloseable {
        final VulkanDevice device;
        long setLayout, pool, set, layout, pipeline, shader;
        boolean disabled;

        GpuLightTreeBuilder(VulkanDevice device) {
            this.device = device;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var vk = device.vkDevice();
                var h = stack.callocLong(1);
                var binding = VkDescriptorSetLayoutBinding.calloc(1, stack).binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
                VulkanUtils.crashIfFailure(device, VK10.vkCreateDescriptorSetLayout(vk,
                    VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binding), null, h), "Light tree descriptor layout");
                setLayout = h.get(0);
                var size = VkDescriptorPoolSize.calloc(1, stack).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
                VulkanUtils.crashIfFailure(device, VK10.vkCreateDescriptorPool(vk,
                    VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(size), null, h), "Light tree descriptor pool");
                pool = h.get(0);
                VulkanUtils.crashIfFailure(device, VK10.vkAllocateDescriptorSets(vk,
                    VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), h), "Light tree descriptor set");
                set = h.get(0);
                var push = VkPushConstantRange.calloc(1, stack).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(12);
                VulkanUtils.crashIfFailure(device, VK10.vkCreatePipelineLayout(vk,
                    VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, h), "Light tree pipeline layout");
                layout = h.get(0);
                shader = ShaderModule.create(device, loadShaderResource("rtest/shaders/light_tree_build.comp"), Shaderc.shaderc_glsl_compute_shader).handle;
                var stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shader).pName(stack.UTF8("main"));
                var info = VkComputePipelineCreateInfo.calloc(1, stack);
                info.get(0).sType$Default().stage(stage).layout(layout);
                VulkanUtils.crashIfFailure(device, VK10.vkCreateComputePipelines(vk, 0L, info, null, h), "Light tree compute pipeline");
                pipeline = h.get(0);
            } catch (RuntimeException failure) {
                close();
                disabled = true;
                com.mojang.logging.LogUtils.getLogger().warn("RTest GPU light tree initialization failed; CPU heap fallback", failure);
            }
        }

        void buildOrFallback(NativeBuffer buffer, RayTracingLightTree.Data data) {
            long start = System.nanoTime();
            if (!disabled) {
                try {
                    int dispatches = build(buffer, data.emitterCount());
                    long hostBytes = (8L + data.words()[7] - data.words()[4]) * Integer.BYTES;
                    com.mojang.logging.LogUtils.getLogger().info("RTest GPU light tree: emitters={}, nodes={}, dispatches={}, host_upload_bytes={}, build_submit_wait_ms={}",
                        data.emitterCount(), data.words()[0], dispatches, hostBytes, (System.nanoTime()-start)/1_000_000.0);
                    return;
                } catch (RuntimeException failure) {
                    disabled = true;
                    com.mojang.logging.LogUtils.getLogger().warn("RTest GPU light tree build failed; CPU heap fallback", failure);
                }
            }
            try (var mapped = buffer.map()) {
                for (int word : data.completeOnCpu()) mapped.buffer().putInt(word);
            }
        }

        private int build(NativeBuffer buffer, int n) {
            VulkanCommandEncoder encoder = new VulkanCommandEncoder(device);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.buffer).offset(0).range(buffer.size);
                var write = VkWriteDescriptorSet.calloc(1, stack).sType$Default().dstSet(set).dstBinding(0)
                    .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(info);
                VK10.vkUpdateDescriptorSets(device.vkDevice(), write, null);
                var cmd = encoder.allocateAndBeginTransientCommandBuffer();
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK10.VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, stack.longs(set), null);
                dispatch(cmd, stack, n-1, n, false);
                int dispatches = 1;
                barrier.srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
                for (int first = Integer.highestOneBit(n-1)-1; first >= 255; first = (first-1)/2) {
                    VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                    dispatch(cmd, stack, first, Math.min(first+1, n-1-first), false);
                    dispatches++;
                }
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
                // The top 255 nodes fit in one group: 128 -> 64 -> ... -> 1.
                dispatch(cmd, stack, 127, 128, true);
                dispatches++;
                barrier.dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT);
                VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, barrier, null, null);
                VulkanUtils.crashIfFailure(device, VK10.vkEndCommandBuffer(cmd), "Light tree end command");
                encoder.execute(cmd);
                try (var fence = encoder.createFence()) {
                    encoder.submit();
                    if (!fence.awaitCompletion(5_000_000_000L)) {
                        device.graphicsQueue().waitIdle();
                        throw new IllegalStateException("GPU light tree build timed out");
                    }
                }
                return dispatches;
            } finally {
                // Retire command pools before any fallback host write or publication.
                try { encoder.destroy(); }
                catch (RuntimeException failure) { device.graphicsQueue().waitIdle(); throw failure; }
            }
        }

        private void dispatch(VkCommandBuffer cmd, MemoryStack stack, int first, int count, boolean fused) {
            VK10.vkCmdPushConstants(cmd, layout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, stack.ints(first, count, fused ? 1 : 0));
            VK10.vkCmdDispatch(cmd, Math.min(65535, (count+127)/128), 1, 1);
        }

        @Override public void close() {
            var vk = device.vkDevice();
            if (pipeline != 0) VK10.vkDestroyPipeline(vk, pipeline, null);
            if (shader != 0) VK10.vkDestroyShaderModule(vk, shader, null);
            if (layout != 0) VK10.vkDestroyPipelineLayout(vk, layout, null);
            if (pool != 0) VK10.vkDestroyDescriptorPool(vk, pool, null);
            if (setLayout != 0) VK10.vkDestroyDescriptorSetLayout(vk, setLayout, null);
            pipeline = shader = layout = pool = setLayout = set = 0;
        }
    }

    private static final class ShaderModule {
        private final long handle;
        private boolean close;

        private ShaderModule(long handle) {
            this.handle = handle;
        }

        private static ShaderModule create(VulkanDevice device, String source, int kind) {
            long compiler = Shaderc.shaderc_compiler_initialize();
            long options = Shaderc.shaderc_compile_options_initialize();
            long result = 0L;
            long shaderModule = 0L;
            // Pass the source through freshly allocated native buffers instead of LWJGL's
            // CharSequence overload. That overload copies the whole source onto the thread's
            // 64 KiB MemoryStack, and the ray-generation shader is larger than that. The source
            // buffer must NOT be NUL-terminated: LWJGL passes its remaining() as the byte length,
            // and a trailing NUL is reported by glslang as an unexpected token.
            ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
            ByteBuffer sourceName = MemoryUtil.memASCII("rtest_rt.glsl", true);
            ByteBuffer entryPoint = MemoryUtil.memASCII("main", true);
            try {
                Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
                Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
                result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, kind, sourceName, entryPoint, options);
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    throw new IllegalStateException("Shaderc ray-tracing compilation failed: " + Shaderc.shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                    LongBuffer handle = stack.callocLong(1);
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkCreateShaderModule(device.vkDevice(), moduleInfo, null, handle),
                        "Failed to create ray-tracing shader module"
                    );
                    shaderModule = handle.get(0);
                    try {
                        return new ShaderModule(shaderModule);
                    } catch (Throwable throwable) {
                        VK10.vkDestroyShaderModule(device.vkDevice(), shaderModule, null);
                        shaderModule = 0L;
                        throw throwable;
                    }
                }
            } finally {
                MemoryUtil.memFree(sourceBytes);
                MemoryUtil.memFree(sourceName);
                MemoryUtil.memFree(entryPoint);
                if (result != 0L) Shaderc.shaderc_result_release(result);
                Shaderc.shaderc_compile_options_release(options);
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }

    private static void closeDuringFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static Throwable closeAndCapture(AutoCloseable resource) {
        return closeAndCapture(resource, null);
    }

    private static Throwable closeAndCapture(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Throwable cleanupFailure) {
            if (failure == null) {
                return cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Vulkan resource teardown failed", failure);
    }

    private static int alignUp(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    boolean hasPresentedFrame() {
        return this.presentedFrame && !this.closed;
    }

    static Set<Long> representedBlockEntities() {
        return representedBlockEntities;
    }

    static void clearRepresentedBlockEntities() {
        representedBlockEntities = Set.of();
    }
}
