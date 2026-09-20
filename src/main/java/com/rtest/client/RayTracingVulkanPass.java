package com.rtest.client;

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
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
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
        /**
         * Block-entity identities whose captured geometry reached the TLAS in the last dispatched
         * frame. Everything else has no RT pixels and is replayed as vanilla raster after the copy.
         */
        private static volatile Set<Long> representedBlockEntities = Set.of();
        private final VulkanDevice device;
        private final VkDevice vkDevice;
        // One pass-owned encoder is reused for incremental AS builds and RT/FSR submissions.
        // Keeping its command pools alive across frames lets a fence retire work without racing
        // per-frame encoder destruction on drivers with strict command-pool lifetime rules.
        private final VulkanCommandEncoder encoder;
        private final List<CachedBlas> sectionBlas;
        private final List<DynamicCachedBlas> dynamicBlas;
        private final int dynamicSlotCapacity;
        private NativeBuffer instanceBuffer;
        private NativeBuffer dynamicMotionMetadataBuffer;
        private NativeBuffer scratchBuffer;
        private final NativeBuffer outputBuffer;
        private final NativeBuffer cameraBuffer;
        private NativeBuffer materialBuffer;
        private NativeBuffer lightDataBuffer;
        private NativeBuffer pbrBuffer;
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
        // A replacement TLAS has no valid correspondence with the previous secondary-ray
        // geometry. Keep this pending until a complete RT submission carries the reset metadata.
        private boolean dynamicHistoryResetPending = true;
        // Geometry publication rewrites the whole material SSBO, so dynamic model ranges must be
        // repopulated once. Stable frames then avoid mapping/flushing the large host-visible SSBO.
        private boolean dynamicMaterialWritePending = true;
        private int frameIndex;
        private int lastPbrPackedMode = Integer.MIN_VALUE;
        private float lastPbrNormalStrength = Float.NaN;
        private float lastPbrEmissionStrength = Float.NaN;
        private float lastEmissionScale = Float.NaN;
        private float lastPbrWetnessStrength = Float.NaN;
        private float lastPbrParallaxDepth = Float.NaN;
        private int lastPbrParallaxFlags = Integer.MIN_VALUE;
        private boolean lastVolumetricLightingEnabled;
        private float lastVolumetricLightingStrength = Float.NaN;
        private float lastVolumetricFogDensity = Float.NaN;
        private int lastVolumetricLightingQuality = Integer.MIN_VALUE;
        private long dispatchTimingFrame;
        private final RayTracingFrameTiming dispatchTiming = new RayTracingFrameTiming();
        private RtestFsrCamera previousFsrCamera;
        private DynamicEntityGeometry.Frame dynamicFrame;
        private final DynamicBlasCache dynamicBlasCache;
        // Mutable entity/block-entity BLAS are owned per instance, never shared by matching poses.
        private final Map<Long, DynamicCachedBlas> playerModelBlas = new HashMap<>();
        private final Map<Long, DynamicCachedBlas> livingModelBlas = new HashMap<>();
        private final Map<Long, DynamicCachedBlas> blockEntityModelBlas = new HashMap<>();
        private final Map<Long, DynamicCachedBlas> itemModelBlas = new HashMap<>();
        // updateDynamicInstances runs on the render thread and completes before the next call.
        // Reusing these bounded scratch tables removes several HashMap/HashSet allocations from
        // every frame without extending any data lifetime past the upload.
        private final Map<Long, Long> dynamicAddressesScratch = new HashMap<>(96);
        private final Map<Long, Integer> dynamicMaterialBasesScratch = new HashMap<>(96);
        private final Set<Long> livePlayersScratch = new HashSet<>(96);
        private final Set<Long> liveLivingScratch = new HashSet<>(96);
        private final Set<Long> liveBlockEntitiesScratch = new HashSet<>(96);
        private final Set<Long> liveItemsScratch = new HashSet<>(96);
        private final Set<Long> historyResetIdentitiesScratch = new HashSet<>(96);
        private boolean reportedPlayerAnimationUpdate;
        private boolean reportedItemMaterial;
        private long dynamicTlasUpdateCount;
        private long dynamicBlasBuildCommandCount;
        private long dynamicMetadataUploadCount;
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
        private int lastCenterPixel;
        // Keep one RT submission in flight. The encoder and all mapped scene buffers are
        // persistent resources, so the next frame must retire this submission before reusing
        // them. Geometry/resource publication can also use this retirement point if a submission
        // is still pending after an early-return path.
        private GpuFence pendingFrameFence;
        private int pendingFrameTimingFrame;
        private int pendingFrameGpuIndex;
        private boolean closed;
        private static final int GPU_TIMESTAMP_COUNT = 4;
        private final long gpuTimestampQueryPool;
        private final double gpuTimestampPeriodNs;
        private final boolean gpuTimestampsAvailable;

        private RayTracingVulkanPass(
            VulkanDevice device,
            VulkanCommandEncoder encoder,
            List<CachedBlas> sectionBlas,
            List<DynamicCachedBlas> dynamicBlas,
            int dynamicSlotCapacity,
            NativeBuffer instanceBuffer,
            NativeBuffer dynamicMotionMetadataBuffer,
            NativeBuffer scratchBuffer,
            NativeBuffer outputBuffer,
            NativeBuffer cameraBuffer,
            NativeBuffer materialBuffer,
            NativeBuffer lightDataBuffer,
            NativeBuffer pbrBuffer,
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
            DynamicBlasCache dynamicBlasCache
        ) {
            this.device = device;
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
            this.dynamicBlas = dynamicBlas;
            this.dynamicSlotCapacity = dynamicSlotCapacity;
            this.instanceBuffer = instanceBuffer;
            this.dynamicMotionMetadataBuffer = dynamicMotionMetadataBuffer;
            this.scratchBuffer = scratchBuffer;
            this.outputBuffer = outputBuffer;
            this.cameraBuffer = cameraBuffer;
            this.materialBuffer = materialBuffer;
            this.lightDataBuffer = lightDataBuffer;
            this.pbrBuffer = pbrBuffer;
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
            this.dynamicBlasCache = dynamicBlasCache;
        }

        private static final class DynamicBlasCache implements AutoCloseable {
            private final Map<Long, DynamicCachedBlas> entries = new HashMap<>();

            DynamicCachedBlas acquire(VulkanDevice device, long topologyKey, DynamicPlaceholderGeometry.Mesh mesh) {
                DynamicCachedBlas cached = entries.get(topologyKey);
                int fingerprint = java.util.Arrays.hashCode(mesh.vertices());
                if (cached != null && cached.fingerprint == fingerprint
                    && cached.triangleCount == mesh.triangleCount()
                    && java.util.Arrays.equals(cached.topologyVertices, mesh.vertices())) {
                    return cached;
                }
                int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                NativeBuffer vertices = NativeBuffer.create(device, (long)mesh.vertices().length * Float.BYTES, usage, true);
                AccelerationStructure bottomLevel = null;
                DynamicCachedBlas candidate = null;
                try {
                    try (NativeBuffer.Mapped mapped = vertices.map()) {
                        mapped.buffer().asFloatBuffer().put(mesh.vertices());
                    }
                    bottomLevel = AccelerationStructure.createBottomLevel(device, vertices, mesh.triangleCount());
                    candidate = new DynamicCachedBlas(topologyKey, fingerprint, mesh.triangleCount(),
                        mesh.vertices(), vertices, bottomLevel);
                } catch (Throwable throwable) {
                    if (candidate != null) {
                        candidate.close();
                    } else if (bottomLevel != null) {
                        bottomLevel.close();
                        vertices.close();
                    } else {
                        vertices.close();
                    }
                    throw throwable;
                }
                try {
                    DynamicCachedBlas previous = entries.put(topologyKey, candidate);
                    if (previous != null && previous != candidate) {
                        previous.close();
                    }
                    return candidate;
                } catch (Throwable throwable) {
                    // The old entry remains valid if publication or its replacement fails.
                    if (entries.get(topologyKey) != candidate) {
                        candidate.close();
                    }
                    throw throwable;
                }
            }

            void release(long key) {
                DynamicCachedBlas cached = entries.remove(key);
                if (cached != null) cached.close();
            }

            @Override
            public void close() {
                Throwable failure = null;
                for (DynamicCachedBlas cached : entries.values()) {
                    try {
                        cached.close();
                    } catch (Throwable cleanupFailure) {
                        if (failure == null) {
                            failure = cleanupFailure;
                        } else {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                entries.clear();
                rethrow(failure);
            }
        }

        private static final class DynamicCachedBlas implements AutoCloseable {
            private final long topologyKey;
            private int fingerprint;
            private final int triangleCount;
            private final float[] topologyVertices;
            private final NativeBuffer vertexBuffer;
            private final AccelerationStructure bottomLevel;
            private boolean built;
            private boolean pendingUpdate;
            private float[] uploadedVertices;
            private boolean closed;

            private DynamicCachedBlas(long topologyKey, int fingerprint, int triangleCount,
                                      float[] topologyVertices, NativeBuffer vertexBuffer,
                                      AccelerationStructure bottomLevel) {
                this.topologyKey = topologyKey;
                this.fingerprint = fingerprint;
                this.triangleCount = triangleCount;
                this.topologyVertices = java.util.Arrays.copyOf(topologyVertices, topologyVertices.length);
                this.vertexBuffer = vertexBuffer;
                this.bottomLevel = bottomLevel;
            }

            private boolean updateVertices(float[] vertices) {
                if (vertices.length * Float.BYTES != vertexBuffer.size) {
                    throw new IllegalArgumentException("Dynamic mesh topology changed during BLAS UPDATE");
                }
                if (java.util.Arrays.equals(uploadedVertices, vertices)) return false;
                try (NativeBuffer.Mapped mapped = vertexBuffer.map()) {
                    mapped.buffer().asFloatBuffer().put(vertices);
                }
                uploadedVertices = java.util.Arrays.copyOf(vertices, vertices.length);
                // Keep the cache fingerprint in sync with the uploaded geometry. Otherwise an
                // animated model is mistaken for a topology replacement on every frame, causing
                // a fresh BLAS allocation instead of an in-place update.
                fingerprint = java.util.Arrays.hashCode(vertices);
                pendingUpdate = true;
                return true;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    bottomLevel.close();
                } finally {
                    vertexBuffer.close();
                }
            }
        }

        static final class BlasCache implements AutoCloseable {
            private final Map<SectionKey, CachedBlas> entries = new HashMap<>();

            CachedBlas acquire(VulkanDevice device, SceneGeometry.SectionGeometry section) {
                SectionKey key = new SectionKey(section.originX, section.originY, section.originZ);
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
                for (CachedBlas candidate : candidates) {
                    CachedBlas previous = entries.put(candidate.key, candidate);
                    if (previous != null && previous != candidate) {
                        previous.close();
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
                entries.entrySet().removeIf(entry -> !active.contains(entry.getKey()) && closeEntry(entry.getValue()));
            }

            private static boolean closeEntry(CachedBlas entry) {
                entry.close();
                return true;
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
                rethrow(failure);
            }
        }

        private record SectionKey(int x, int y, int z) {
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
            DynamicEntityGeometry.Frame dynamicFrame
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
            List<DynamicCachedBlas> dynamicBlas = new ArrayList<>();
            int dynamicSlotCapacity = RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get() ? 64 : 0;
            DynamicBlasCache dynamicBlasCache = new DynamicBlasCache();
            NativeBuffer instanceBuffer = null;
            NativeBuffer dynamicMotionMetadataBuffer = null;
            NativeBuffer scratchBuffer = null;
            NativeBuffer outputBuffer = null;
            NativeBuffer cameraBuffer = null;
            NativeBuffer materialBuffer = null;
            NativeBuffer lightDataBuffer = null;
            NativeBuffer pbrBuffer = null;
            NativeBuffer shaderBindingTable = null;
            AccelerationStructure topLevel = null;
            VulkanCommandEncoder encoder = null;
            long descriptorSetLayout = 0L;
            long descriptorPool = 0L;
            long pipelineLayout = 0L;
            long pipeline = 0L;
            long[] shaderModules = new long[7];
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
                if (RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get()) {
                    DynamicPlaceholderGeometry.Mesh playerMesh = DynamicPlaceholderGeometry.box(0.25F, 0.6F, 1.0F, false);
                    DynamicPlaceholderGeometry.Mesh itemMesh = DynamicPlaceholderGeometry.box(1.0F, 0.35F, 0.05F, true);
                    dynamicBlas.add(dynamicBlasCache.acquire(device, 0x504C415945524CL, playerMesh));
                    dynamicBlas.add(dynamicBlasCache.acquire(device, 0x4C4956494E474CL, playerMesh));
                    dynamicBlas.add(dynamicBlasCache.acquire(device, 0x4954454D4CL, itemMesh));
                    com.mojang.logging.LogUtils.getLogger().info(
                        "RTest allocated {} dynamic placeholder BLAS resources", dynamicBlas.size());
                }
                instanceBuffer = NativeBuffer.create(
                    device,
                    (long)(sectionBlas.size() + dynamicSlotCapacity) * VkAccelerationStructureInstanceKHR.SIZEOF,
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
                try (NativeBuffer.Mapped mapped = instanceBuffer.map()) {
                    long address = MemoryUtil.memAddress(mapped.buffer());
                    int materialTriangleOffset = 0;
                    for (int i = 0; i < sectionBlas.size(); i++) {
                        SceneGeometry.SectionGeometry section = geometry.sections.get(i);
                        CachedBlas cached = sectionBlas.get(i);
                        VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                            address + (long)i * VkAccelerationStructureInstanceKHR.SIZEOF
                        );
                        instance.transform(transform -> {
                            FloatBuffer matrix = transform.matrix();
                            matrix.put(0, 1.0F).put(1, 0.0F).put(2, 0.0F).put(3, (float)(section.originX - geometry.originX));
                            matrix.put(4, 0.0F).put(5, 1.0F).put(6, 0.0F).put(7, (float)(section.originY - geometry.originY));
                            matrix.put(8, 0.0F).put(9, 0.0F).put(10, 1.0F).put(11, (float)(section.originZ - geometry.originZ));
                        });
                        instance.instanceCustomIndex(materialTriangleOffset);
                        instance.mask(DynamicTlasInstanceWriter.ALL_RAY_MASK);
                        instance.instanceShaderBindingTableRecordOffset(0);
                        instance.flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
                        instance.accelerationStructureReference(cached.bottomLevel.deviceAddress);
                        materialTriangleOffset += section.triangleCount;
                    }
                    long dummyAddress = sectionBlas.get(0).bottomLevel.deviceAddress;
                    for (int slot = 0; slot < dynamicSlotCapacity; slot++) {
                        VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                            address + (long)(sectionBlas.size() + slot) * VkAccelerationStructureInstanceKHR.SIZEOF);
                        instance.mask(0).instanceCustomIndex(0).instanceShaderBindingTableRecordOffset(0)
                            .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                            .accelerationStructureReference(dummyAddress);
                    }
                }

                topLevel = AccelerationStructure.createTopLevel(device, instanceBuffer,
                    sectionBlas.size() + dynamicSlotCapacity);
                long scratchSize = topLevel.scratchSize;
                for (CachedBlas cached : sectionBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                for (DynamicCachedBlas cached : dynamicBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                scratchBuffer = NativeBuffer.create(
                    device,
                    scratchSize,
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
                long materialFloatCount = materialFloatCount(geometry, dynamicSlotCapacity);
                materialBuffer = NativeBuffer.create(
                    device,
                    materialFloatCount * Float.BYTES,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true
                );
                writeMaterialBuffer(materialBuffer, geometry, dynamicSlotCapacity);
                lightDataBuffer = uploadIntBuffer(device, geometry.lightTree.words(),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
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

                ShaderModule raygen = ShaderModule.create(device, RayTracingShaders.RAYGEN_SHADER, Shaderc.shaderc_glsl_raygen_shader);
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

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(27, stack);
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
                        .descriptorCount(1);
                    poolSizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(5);
                    poolSizes.get(2).type(VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
                    poolSizes.get(3).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(3 + PLAYER_SKIN_DESCRIPTOR_COUNT);
                    poolSizes.get(4).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(16);
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
                        .buffer(materialBuffer.buffer).offset(0).range(materialBuffer.size);
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
                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(27, stack);
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
                    VK10.vkUpdateDescriptorSets(vkDevice, writes, null);

                    VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                        .pSetLayouts(stack.longs(descriptorSetLayout));
                    VulkanUtils.crashIfFailure(
                        device,
                        VK10.vkCreatePipelineLayout(vkDevice, pipelineLayoutInfo, null, handle),
                        "Failed to create ray-tracing pipeline layout"
                    );
                    pipelineLayout = handle.get(0);

                    VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(7, stack);
                    stages.get(0).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR).module(raygen.handle).pName(stack.UTF8("main"));
                    stages.get(1).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(miss.handle).pName(stack.UTF8("main"));
                    stages.get(2).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR).module(shadowMiss.handle).pName(stack.UTF8("main"));
                    stages.get(3).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(closestHit.handle).pName(stack.UTF8("main"));
                    stages.get(4).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(anyHit.handle).pName(stack.UTF8("main"));
                    stages.get(5).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR).module(shadowClosestHit.handle).pName(stack.UTF8("main"));
                    stages.get(6).sType$Default().stage(KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR).module(shadowAnyHit.handle).pName(stack.UTF8("main"));
                    // Group 3 (primary) and group 4 (shadow) share the alpha-testing Any Hit shader.
                    VkRayTracingShaderGroupCreateInfoKHR.Buffer groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(5, stack);
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
                    ByteBuffer shaderHandles = MemoryUtil.memAlloc(handleSize * 5);
                    try {
                        VulkanUtils.crashIfFailure(
                            device,
                            KHRRayTracingPipeline.vkGetRayTracingShaderGroupHandlesKHR(vkDevice, pipeline, 0, 5, shaderHandles),
                            "Failed to retrieve ray-tracing shader group handles"
                        );
                        shaderBindingTable = NativeBuffer.create(
                            device,
                            (long)stride * 5L,
                            KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR
                                | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            true
                        );
                        try (NativeBuffer.Mapped mapped = shaderBindingTable.map()) {
                            for (int i = 0; i < 5; i++) {
                                MemoryUtil.memCopy(
                                    MemoryUtil.memAddress(shaderHandles) + (long)i * handleSize,
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
                        encoder,
                        sectionBlas,
                        dynamicBlas,
                        dynamicSlotCapacity,
                        instanceBuffer,
                        dynamicMotionMetadataBuffer,
                        scratchBuffer,
                        outputBuffer,
                        cameraBuffer,
                        materialBuffer,
                        lightDataBuffer,
                        pbrBuffer,
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
                        dynamicBlasCache
                    );
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
                if (pipeline != 0L) VK10.vkDestroyPipeline(vkDevice, pipeline, null);
                if (pipelineLayout != 0L) VK10.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
                if (descriptorPool != 0L) VK10.vkDestroyDescriptorPool(vkDevice, descriptorPool, null);
                if (descriptorSetLayout != 0L) VK10.vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
                for (long shaderModule : shaderModules) if (shaderModule != 0L) VK10.vkDestroyShaderModule(vkDevice, shaderModule, null);
                closeDuringFailure(topLevel, throwable);
                closeDuringFailure(shaderBindingTable, throwable);
                closeDuringFailure(pbrBuffer, throwable);
                closeDuringFailure(lightDataBuffer, throwable);
                closeDuringFailure(materialBuffer, throwable);
                closeDuringFailure(cameraBuffer, throwable);
                closeDuringFailure(outputBuffer, throwable);
                closeDuringFailure(scratchBuffer, throwable);
                closeDuringFailure(instanceBuffer, throwable);
                closeDuringFailure(dynamicMotionMetadataBuffer, throwable);
                closeDuringFailure(dynamicBlasCache, throwable);
                try {
                    blasCache.abort(sectionBlas);
                } catch (Throwable cleanupFailure) {
                    throwable.addSuppressed(cleanupFailure);
                }
                throw throwable;
            }
        }

        boolean matches(VulkanDevice candidateDevice, int renderWidth, int renderHeight,
                        int width, int height, long image, long imageView, GpuFormat format,
                        SceneGeometry geometry, long atlasImageView, long atlasSampler, RtestFsr3 fsr) {
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
                && this.fsr == fsr;
        }

        boolean usesGeometry(SceneGeometry candidate) {
            return this.geometry == candidate;
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
            int previousSectionCount = this.sectionBlas.size();
            List<CachedBlas> nextBlas = new ArrayList<>();
            Set<SectionKey> activeKeys = new HashSet<>();
            NativeBuffer nextInstance = null;
            NativeBuffer nextMaterial = null;
            NativeBuffer nextLightData = null;
            NativeBuffer nextPbr = null;
            AccelerationStructure nextTopLevel = null;
            NativeBuffer nextScratch = null;
            boolean reuseTopLevel = false;
            boolean reuseInstance = false;
            boolean reuseScratch = false;
            boolean reuseMaterial = false;
            boolean reusePbr = false;
            try {
                int geometryUsage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                    | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
                for (SceneGeometry.SectionGeometry section : nextGeometry.sections) {
                    CachedBlas cached = blasCache.acquire(this.device, section);
                    nextBlas.add(cached);
                    activeKeys.add(cached.key);
                }
                long requiredInstanceSize = (long)(nextBlas.size() + this.dynamicSlotCapacity)
                    * VkAccelerationStructureInstanceKHR.SIZEOF;
                reuseInstance = requiredInstanceSize <= this.instanceBuffer.size;
                nextInstance = reuseInstance
                    ? this.instanceBuffer
                    : NativeBuffer.create(this.device, requiredInstanceSize, geometryUsage, true);
                writeInstanceBuffer(nextInstance, nextGeometry, nextBlas, this.dynamicSlotCapacity);
                reuseTopLevel = nextBlas.size() == this.sectionBlas.size();
                nextTopLevel = reuseTopLevel
                    ? this.topLevel
                    : AccelerationStructure.createTopLevel(this.device, nextInstance, nextBlas.size() + dynamicSlotCapacity);
                long scratchSize = nextTopLevel.scratchSize;
                for (CachedBlas cached : nextBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                for (DynamicCachedBlas cached : dynamicBlas) {
                    scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
                }
                reuseScratch = reuseTopLevel && scratchSize <= this.scratchBuffer.size;
                nextScratch = reuseScratch
                    ? this.scratchBuffer
                    : NativeBuffer.create(
                        this.device,
                        scratchSize,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        false);
                long nextMaterialFloatCount = materialFloatCount(nextGeometry, dynamicSlotCapacity);
                reuseMaterial = nextMaterialFloatCount * Float.BYTES <= this.materialBuffer.size;
                nextMaterial = reuseMaterial
                    ? this.materialBuffer
                    : NativeBuffer.create(this.device, nextMaterialFloatCount * Float.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
                writeMaterialBuffer(nextMaterial, nextGeometry, dynamicSlotCapacity);
                nextLightData = uploadIntBuffer(this.device, nextGeometry.lightTree.words(),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                if (this.pbrMaterials == null) {
                    int[] nextPbrData = nextGeometry.pbrData;
                    reusePbr = nextPbrData.length * (long)Integer.BYTES <= this.pbrBuffer.size;
                    nextPbr = reusePbr ? this.pbrBuffer : uploadIntBuffer(this.device, nextPbrData,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
                } else {
                    // Dynamic PBR uses a fixed host-visible SSBO. Geometry replacement must not
                    // rebuild its payload or replace binding 5; newly discovered maps are applied
                    // by synchronizePbrMaterials() as append-only writes.
                    reusePbr = true;
                    nextPbr = this.pbrBuffer;
                }
                long pbrRange = nextPbr.size;
                updateSceneDescriptors(nextTopLevel, nextMaterial, nextLightData, nextPbr,
                    nextMaterialFloatCount * Float.BYTES, pbrRange,
                    this.pbrMaterials == null);
            } catch (Throwable throwable) {
                if (nextTopLevel != null && !reuseTopLevel) nextTopLevel.close();
                if (nextPbr != null && !reusePbr) nextPbr.close();
                if (nextLightData != null) nextLightData.close();
                if (nextMaterial != null && !reuseMaterial) nextMaterial.close();
                if (nextScratch != null && !reuseScratch) nextScratch.close();
                if (nextInstance != null && !reuseInstance) nextInstance.close();
                blasCache.abort(nextBlas);
                throw throwable;
            }

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
            this.sectionBlas.clear();
            this.sectionBlas.addAll(nextBlas);
            this.instanceBuffer = nextInstance;
            this.scratchBuffer = nextScratch;
            this.materialBuffer = nextMaterial;
            this.lightDataBuffer = nextLightData;
            this.pbrBuffer = nextPbr;
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
            this.dynamicMaterialWritePending = true;
            // NRD only receives motion for the primary hit. A static primary surface can still
            // reflect a changed dynamic/scene TLAS, so preserve no specular history across this
            // resource publication.
            this.dynamicHistoryResetPending = true;
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
            oldLightData.close();
            if (!reusePbr) {
                oldPbr.close();
            }
            LOGGER.info(
                "RTest geometry publish: oldSections={}, newSections={}, reuseInstance={}, reuseMaterial={}, reuseTopLevel={}, duration={} ms",
                previousSectionCount, nextBlas.size(), reuseInstance, reuseMaterial, reuseTopLevel,
                (System.nanoTime() - startNanos) / 1_000_000L);
        }

        // Player body overlays plus both held-item hands share one dynamic BLAS. Keep enough
        // material slots for a fully decorated body and a high-poly baked item without silently
        // dropping the player instance.
        private static final int DYNAMIC_PLAYER_MATERIAL_TRIANGLES =
            DynamicEntityGeometry.DYNAMIC_MODEL_TRIANGLE_CAPACITY;
        private static final int DYNAMIC_PLACEHOLDER_TRIANGLES = 12;
        private static final int DYNAMIC_ITEM_MATERIAL_TRIANGLES =
            DynamicEntityGeometry.DYNAMIC_ITEM_TRIANGLE_CAPACITY;
        private static final int DYNAMIC_SLOT_MATERIAL_TRIANGLES =
            DYNAMIC_PLAYER_MATERIAL_TRIANGLES + DYNAMIC_ITEM_MATERIAL_TRIANGLES;
        private static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;

        private static final float[] DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL =
            DynamicPlaceholderGeometry.box(0.20F, 0.55F, 0.90F, false).materialData();
        private static final float[] DYNAMIC_ITEM_PLACEHOLDER_MATERIAL =
            DynamicPlaceholderGeometry.box(1.0F, 0.35F, 0.05F, true).materialData();

        private static long materialFloatCount(SceneGeometry geometry, int capacity) {
            return (long)geometry.materialData.length
                + DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL.length
                + DYNAMIC_ITEM_PLACEHOLDER_MATERIAL.length
                + (long)capacity * DYNAMIC_SLOT_MATERIAL_TRIANGLES * MATERIAL_FLOATS_PER_TRIANGLE;
        }

        /** Writes the scene and fixed dynamic ranges directly into the mapped SSBO. */
        private static void writeMaterialBuffer(
            NativeBuffer buffer, SceneGeometry geometry, int capacity
        ) {
            try (NativeBuffer.Mapped mapped = buffer.map()) {
                FloatBuffer destination = mapped.buffer().asFloatBuffer();
                destination.put(geometry.materialData);
                destination.put(DYNAMIC_PLAYER_PLACEHOLDER_MATERIAL);
                destination.put(DYNAMIC_ITEM_PLACEHOLDER_MATERIAL);
                int dynamicPadding = capacity * DYNAMIC_SLOT_MATERIAL_TRIANGLES * MATERIAL_FLOATS_PER_TRIANGLE;
                for (int index = 0; index < dynamicPadding; index++) {
                    destination.put(0.0F);
                }
            }
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
            boolean tlasChanged = frame.dynamicFrame().instances().stream()
                .anyMatch(instance -> instance.historyReset()
                    || !instance.currentTransform().equals(instance.previousTransform()));
            // The one-frame history-reset bit must also be cleared on the following frame. The
            // fixed 64-slot metadata upload is only 7 KiB and is cheaper than retaining stale flags.
            boolean metadataChanged = true;
            Map<Long, Long> addresses = this.dynamicAddressesScratch;
            Map<Long, Integer> materialBases = this.dynamicMaterialBasesScratch;
            Set<Long> livePlayers = this.livePlayersScratch;
            Set<Long> liveLiving = this.liveLivingScratch;
            Set<Long> liveBlockEntities = this.liveBlockEntitiesScratch;
            Set<Long> liveItems = this.liveItemsScratch;
            Set<Long> historyResetIdentities = this.historyResetIdentitiesScratch;
            addresses.clear();
            materialBases.clear();
            livePlayers.clear();
            liveLiving.clear();
            liveBlockEntities.clear();
            liveItems.clear();
            historyResetIdentities.clear();
            if (this.dynamicHistoryResetPending) {
                for (DynamicInstanceRegistry.Instance instance : frame.dynamicFrame().instances()) {
                    if (instance.active()) {
                        historyResetIdentities.add(instance.identity());
                    }
                }
            }
            // dispatch waits for its previous frame fence before returning, so the previous use is
            // complete. Stable dynamic frames do not need to map/flush the full material SSBO.
            boolean materialUploadNeeded = this.dynamicMaterialWritePending
                || !frame.dynamicFrame().changedGeometry().isEmpty();
            try (NativeBuffer.Mapped mapped = materialUploadNeeded ? materialBuffer.map() : null) {
                FloatBuffer materials = mapped == null ? null : mapped.buffer().asFloatBuffer();
                for (DynamicEntityGeometry.Instance instance : frame.instances()) {
                    long id = instance.snapshot().identity();
                    int slot = instance.snapshot().slot();
                    if (slot >= dynamicSlotCapacity || !instance.snapshot().active()) continue;
                    if (instance.family() == DynamicEntityGeometry.Family.PLAYER_BODY
                        || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_BODY
                        || instance.family() == DynamicEntityGeometry.Family.LIVING_BODY
                        || instance.family() == DynamicEntityGeometry.Family.BLOCK_ENTITY_MODEL
                        || instance.family() == DynamicEntityGeometry.Family.PARTICLE) {
                        boolean player = instance.family() == DynamicEntityGeometry.Family.PLAYER_BODY
                            || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_BODY;
                        boolean blockEntity =
                            instance.family() == DynamicEntityGeometry.Family.BLOCK_ENTITY_MODEL;
                        PlayerModelGeometryAdapter.Mesh mesh = player ? frame.playerMeshes().get(id)
                            : blockEntity ? frame.blockEntityMeshes().get(id)
                            : frame.livingMeshes().get(id);
                        if (mesh != null && mesh.triangleCount() <= DYNAMIC_PLAYER_MATERIAL_TRIANGLES) {
                            // Block-entity identities and cache keys occupy their own high-bit
                            // namespace; their per-slot material range is the same bounded model
                            // range as player and living models.
                            long key = blockEntity ? 0x5000000000000000L | (id & 0x0fffffffffffffffL)
                                : 0x7000000000000000L | (id & 0xffffffffL);
                            Map<Long, DynamicCachedBlas> cacheMap = player ? playerModelBlas
                                : blockEntity ? blockEntityModelBlas : livingModelBlas;
                            DynamicCachedBlas cached = cacheMap.get(id);
                            DynamicPlaceholderGeometry.Mesh dynamicMesh =
                                new DynamicPlaceholderGeometry.Mesh(mesh.vertices(), mesh.materialData());
                            if (cached != null && cached.triangleCount != mesh.triangleCount()) {
                                // acquire() publishes the replacement only after its complete
                                // BLAS allocation succeeds; keep the old entry/list membership
                                // untouched on an allocation failure.
                                DynamicCachedBlas previous = cached;
                                cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                                dynamicBlas.remove(previous);
                                dynamicBlas.add(cached);
                                cached.uploadedVertices = mesh.vertices();
                                cacheMap.put(id, cached);
                                historyResetIdentities.add(id);
                                tlasChanged = true;
                            } else if (cached == null) {
                                cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                                cached.uploadedVertices = mesh.vertices();
                                cacheMap.put(id, cached);
                                dynamicBlas.add(cached);
                                historyResetIdentities.add(id);
                                tlasChanged = true;
                            } else if (cached.updateVertices(mesh.vertices())) {
                                historyResetIdentities.add(id);
                                metadataChanged = true;
                            }
                            if (player) {
                                livePlayers.add(id);
                            } else if (blockEntity) {
                                liveBlockEntities.add(id);
                            } else {
                                liveLiving.add(id);
                            }
                            int base = geometry.triangleCount() + DYNAMIC_PLACEHOLDER_TRIANGLES
                                + DYNAMIC_ITEM_MATERIAL_TRIANGLES + slot * DYNAMIC_SLOT_MATERIAL_TRIANGLES;
                            if (materials != null && (this.dynamicMaterialWritePending
                                    || frame.dynamicFrame().changedGeometry().contains(id))) {
                                materials.position(base * MATERIAL_FLOATS_PER_TRIANGLE);
                            // Each selector-2 triangle already carries its own living-texture
                            // descriptor index in optical.x. A player BLAS can contain skin,
                            // several armor PNGs and a trim atlas, so the dynamic/TLAS slot must
                            // never replace those per-layer texture indices.
                                materials.put(mesh.materialData());
                            }
                            materialBases.put(id, base);
                            addresses.put(id, cached.bottomLevel.deviceAddress);
                            continue;
                        }
                    } else if (instance.family() == DynamicEntityGeometry.Family.ITEM_PLACEHOLDER
                        || instance.family() == DynamicEntityGeometry.Family.FIRST_PERSON_ITEM) {
                        ItemModelGeometryAdapter.Mesh mesh = frame.itemMeshes().get(id);
                        if (mesh != null && mesh.triangleCount() <= DYNAMIC_ITEM_MATERIAL_TRIANGLES) {
                            long key = 0x6000000000000000L | (id & 0xffffffffL);
                            DynamicCachedBlas cached = itemModelBlas.get(id);
                            DynamicPlaceholderGeometry.Mesh dynamicMesh =
                                new DynamicPlaceholderGeometry.Mesh(mesh.vertices(), mesh.materialData());
                            if (cached != null && cached.triangleCount != mesh.triangleCount()) {
                                // See the player path above: publish the new BLAS before
                                // removing the old list entry so a failed replacement is atomic.
                                DynamicCachedBlas previous = cached;
                                cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                                dynamicBlas.remove(previous);
                                dynamicBlas.add(cached);
                                cached.uploadedVertices = mesh.vertices();
                                itemModelBlas.put(id, cached);
                                historyResetIdentities.add(id);
                                tlasChanged = true;
                            } else if (cached == null) {
                                cached = dynamicBlasCache.acquire(device, key, dynamicMesh);
                                cached.uploadedVertices = mesh.vertices();
                                itemModelBlas.put(id, cached);
                                dynamicBlas.add(cached);
                                historyResetIdentities.add(id);
                                tlasChanged = true;
                            } else if (cached.updateVertices(mesh.vertices())) {
                                historyResetIdentities.add(id);
                                metadataChanged = true;
                            }
                            liveItems.add(id);
                            int base = geometry.triangleCount() + DYNAMIC_PLACEHOLDER_TRIANGLES
                                + DYNAMIC_ITEM_MATERIAL_TRIANGLES
                                + slot * DYNAMIC_SLOT_MATERIAL_TRIANGLES
                                + DYNAMIC_PLAYER_MATERIAL_TRIANGLES;
                            if (materials != null && (this.dynamicMaterialWritePending
                                    || frame.dynamicFrame().changedGeometry().contains(id))) {
                                materials.position(base * MATERIAL_FLOATS_PER_TRIANGLE);
                                materials.put(mesh.materialData());
                            }
                            if (!reportedItemMaterial) {
                                reportedItemMaterial = true;
                                float[] material = mesh.materialData();
                                LOGGER.info(
                                    "RTest item material upload: id={}, base={}, triangles={}, selector={}, pbrMapIndex={}, opticalX={}, uv=({}, {})..({}, {})",
                                    id, base, mesh.triangleCount(), material[15], material[19], material[24],
                                    material[8], material[9], material[10], material[11]);
                            }
                            materialBases.put(id, base);
                            addresses.put(id, cached.bottomLevel.deviceAddress);
                            continue;
                        }
                    }

                    long topology = switch (instance.family()) {
                        case PLAYER_BODY, FIRST_PERSON_BODY -> 0x504C415945524CL;
                        case LIVING_BODY -> 0x4C4956494E474CL;
                        case BLOCK_ENTITY_MODEL -> instance.topology();
                        case ITEM_PLACEHOLDER, FIRST_PERSON_ITEM -> 0x4954454D4CL;
                        case PARTICLE -> 0x5041525449434C45L;
                    };
                    for (DynamicCachedBlas cached : dynamicBlas) {
                        if (cached.topologyKey == topology) {
                            addresses.put(id, cached.bottomLevel.deviceAddress);
                            materialBases.put(id, geometry.triangleCount()
                                + (instance.family() == DynamicEntityGeometry.Family.ITEM_PLACEHOLDER
                                    ? DYNAMIC_PLACEHOLDER_TRIANGLES : 0));
                            break;
                        }
                    }
                }
                this.dynamicMaterialWritePending = false;
            }
            var players = playerModelBlas.entrySet().iterator();
            while (players.hasNext()) {
                var entry = players.next();
                if (!livePlayers.contains(entry.getKey())) {
                    DynamicCachedBlas cached = entry.getValue();
                    dynamicBlas.remove(cached);
                    dynamicBlasCache.release(cached.topologyKey);
                    tlasChanged = true;
                    metadataChanged = true;
                    players.remove();
                }
            }
            var living = livingModelBlas.entrySet().iterator();
            while (living.hasNext()) {
                var entry = living.next();
                if (!liveLiving.contains(entry.getKey())) {
                    DynamicCachedBlas cached = entry.getValue();
                    dynamicBlas.remove(cached);
                    dynamicBlasCache.release(cached.topologyKey);
                    tlasChanged = true;
                    metadataChanged = true;
                    living.remove();
                }
            }
            var blockEntities = blockEntityModelBlas.entrySet().iterator();
            while (blockEntities.hasNext()) {
                var entry = blockEntities.next();
                if (!liveBlockEntities.contains(entry.getKey())) {
                    DynamicCachedBlas cached = entry.getValue();
                    dynamicBlas.remove(cached);
                    dynamicBlasCache.release(cached.topologyKey);
                    tlasChanged = true;
                    metadataChanged = true;
                    blockEntities.remove();
                }
            }
            var items = itemModelBlas.entrySet().iterator();
            while (items.hasNext()) {
                var entry = items.next();
                if (!liveItems.contains(entry.getKey())) {
                    DynamicCachedBlas cached = entry.getValue();
                    dynamicBlas.remove(cached);
                    dynamicBlasCache.release(cached.topologyKey);
                    tlasChanged = true;
                    metadataChanged = true;
                    items.remove();
                }
            }
            long scratchSize = scratchBuffer.size;
            for (DynamicCachedBlas cached : dynamicBlas) {
                scratchSize = Math.max(scratchSize, cached.bottomLevel.scratchSize);
            }
            if (scratchSize > scratchBuffer.size) {
                NativeBuffer replacement = NativeBuffer.create(device, scratchSize,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
                NativeBuffer previousScratch = scratchBuffer;
                // Publish ownership before closing the old allocation. If destruction reports a
                // runtime/device failure, the replacement is still reachable from the pass and
                // will be released by close().
                scratchBuffer = replacement;
                previousScratch.close();
            }
            if (tlasChanged || forceInstanceWrite) {
                if (tlasChanged) {
                    this.dynamicTlasUpdateCount++;
                }
                try (NativeBuffer.Mapped mapped = instanceBuffer.map()) {
                    ByteBuffer buffer = mapped.buffer();
                    buffer.position(sectionBlas.size() * VkAccelerationStructureInstanceKHR.SIZEOF);
                    DynamicTlasInstanceWriter.write(buffer, dynamicSlotCapacity, frame.dynamicFrame(), addresses,
                        materialBases, sectionBlas.get(0).bottomLevel.deviceAddress,
                        (float)geometry.originX, (float)geometry.originY, (float)geometry.originZ);
                }
            }
            if (metadataChanged) {
                this.dynamicMetadataUploadCount++;
                try (NativeBuffer.Mapped mapped = dynamicMotionMetadataBuffer.map()) {
                    DynamicTlasInstanceWriter.writeMotionMetadata(mapped.buffer(), dynamicSlotCapacity,
                        frame.dynamicFrame(), historyResetIdentities,
                        (float)geometry.originX, (float)geometry.originY, (float)geometry.originZ);
                }
            }
            // Publish exactly the block entities whose geometry reached the TLAS this frame.
            Set<Long> represented = new HashSet<>();
            for (Long identity : materialBases.keySet()) {
                if ((identity >>> 48) == 0x8001L) {
                    represented.add(identity);
                }
            }
            representedBlockEntities = Set.copyOf(represented);
            return tlasChanged;
        }

        private static NativeBuffer createInstanceBuffer(
            VulkanDevice device,
            SceneGeometry geometry,
            List<CachedBlas> sectionBlas,
            int dynamicSlotCapacity,
            int usage
        ) {
            NativeBuffer buffer = NativeBuffer.create(
                device, (long)(sectionBlas.size() + dynamicSlotCapacity) * VkAccelerationStructureInstanceKHR.SIZEOF, usage, true);
            writeInstanceBuffer(buffer, geometry, sectionBlas, dynamicSlotCapacity);
            return buffer;
        }

        private static void writeInstanceBuffer(
            NativeBuffer buffer,
            SceneGeometry geometry,
            List<CachedBlas> sectionBlas,
            int dynamicSlotCapacity
        ) {
            try (NativeBuffer.Mapped mapped = buffer.map()) {
                long address = MemoryUtil.memAddress(mapped.buffer());
                int materialTriangleOffset = 0;
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
                    instance.instanceCustomIndex(materialTriangleOffset);
                    instance.mask(DynamicTlasInstanceWriter.ALL_RAY_MASK);
                    instance.instanceShaderBindingTableRecordOffset(0);
                    instance.flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR);
                    instance.accelerationStructureReference(cached.bottomLevel.deviceAddress);
                    materialTriangleOffset += section.triangleCount;
                }
                long dummyAddress = sectionBlas.get(0).bottomLevel.deviceAddress;
                for (int slot = 0; slot < dynamicSlotCapacity; slot++) {
                    VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                        address + (long)(sectionBlas.size() + slot) * VkAccelerationStructureInstanceKHR.SIZEOF);
                    instance.mask(0).instanceCustomIndex(0).instanceShaderBindingTableRecordOffset(0)
                        .flags(KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(dummyAddress);
                }
            }
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
            int pbrPackedMode = config.pbrPackedMode();
            float pbrNormalStrength = config.pbrNormalStrength.get().floatValue();
            float pbrEmissionStrength = config.pbrEmissionStrength.get().floatValue();
            float emissionScale = config.emissionScale.get().floatValue();
            float pbrWetnessStrength = config.pbrWetnessStrength.get().floatValue();
            float pbrParallaxDepth = config.pbrParallaxDepth.get().floatValue();
            int pbrParallaxFlags = config.pbrParallaxFlags();
            boolean volumetricLightingEnabled = config.volumetricLightingEnabled.get();
            float volumetricLightingStrength = config.volumetricLightingStrength.get().floatValue();
            float volumetricFogDensity = config.volumetricFogDensity.get().floatValue();
            int volumetricLightingQuality = config.volumetricLightingQuality.get();
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
                || this.lastVolumetricLightingQuality != volumetricLightingQuality) {
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
                this.lastVolumetricLightingQuality = volumetricLightingQuality;
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
                float timeOfDay = clockTicks / 24000.0F;
                buffer.putFloat(0, (float)(position.x - geometry.originX));
                buffer.putFloat(4, (float)(position.y - geometry.originY));
                buffer.putFloat(8, (float)(position.z - geometry.originZ));
                buffer.putFloat(12, 0.0F);
                buffer.putFloat(16, forward.x()).putFloat(20, forward.y()).putFloat(24, forward.z()).putFloat(28, 0.0F);
                buffer.putFloat(32, -left.x()).putFloat(36, -left.y()).putFloat(40, -left.z()).putFloat(44, 0.0F);
                buffer.putFloat(48, up.x()).putFloat(52, up.y()).putFloat(56, up.z()).putFloat(60, 0.0F);
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
                buffer.putFloat(96, RayTracingClientConfig.INSTANCE.sunIntensity.get().floatValue())
                    .putFloat(100, RayTracingClientConfig.INSTANCE.shadowStrength.get().floatValue())
                    // settings.z carries the optional Prime-inspired aerial volume strength;
                    // zero keeps the shader path completely dormant without changing the UBO ABI.
                    .putFloat(104, RayTracingClientConfig.INSTANCE.volumetricLightingEnabled.get()
                        ? RayTracingClientConfig.INSTANCE.volumetricLightingStrength.get().floatValue()
                        : 0.0F)
                    // settings.w carries the atmosphere quality tier: 1=performance,
                    // 2=balanced, 3=quality.
                    .putFloat(108, RayTracingClientConfig.INSTANCE.volumetricLightingQuality.get());
                // environment.x carries the visible atmosphere fog-density multiplier; the other
                // components remain the authored RT environment baseline.
                // environment.y/z carry the authored sun and ambient color temperatures (Kelvin).
                buffer.putFloat(112, RayTracingClientConfig.INSTANCE.volumetricFogDensity.get().floatValue())
                    .putFloat(116, RayTracingClientConfig.INSTANCE.sunColorTemperature.get().floatValue())
                    .putFloat(120, RayTracingClientConfig.INSTANCE.ambientColorTemperature.get().floatValue())
                    .putFloat(124, 1.0F);
                buffer.putFloat(128, Float.intBitsToFloat(currentFrame))
                    .putFloat(132, Float.intBitsToFloat(0x243f6a88))
                    .putFloat(136, jitter.x())
                    .putFloat(140, jitter.y());
                putCameraState(buffer, 144, previous, geometry);
                buffer.putFloat(224, jitter.x()).putFloat(228, jitter.y())
                    .putFloat(232, 0.0F).putFloat(236, 0.0F);
                // x=time of day, y=rain, z=thunder, w=night factor.
                buffer.putFloat(240, timeOfDay).putFloat(244, rain)
                    .putFloat(248, thunder).putFloat(252, night);
                int dynamicMaterialStart = geometry.triangleCount()
                    + DYNAMIC_PLACEHOLDER_TRIANGLES + DYNAMIC_ITEM_MATERIAL_TRIANGLES;
                boolean signalSplitEnabled = RayTracingClientConfig.INSTANCE.nrdEnabled.get()
                    && RayTracingClientConfig.INSTANCE.nrdStrength.get().floatValue() > 0.0001f
                    && !(RayTracingClientConfig.INSTANCE.sundialDenoiserEnabled.get()
                        && RayTracingClientConfig.INSTANCE.sundialDenoiserStrength.get().floatValue() > 0.0001f);
                buffer.putFloat(256, dynamicMaterialStart)
                    .putFloat(260, DYNAMIC_SLOT_MATERIAL_TRIANGLES)
                    .putFloat(264, dynamicSlotCapacity)
                    .putFloat(268, signalSplitEnabled ? 1.0F : 0.0F);
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
                    .putFloat(296, 0.0F)
                    .putFloat(300, pbrParallaxFlags);
            }
            previousFsrCamera = currentCamera;
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
                return;
            }
            this.pendingFrameFence = null;
            long fenceWaitStart = System.nanoTime();
            try {
                if (!fence.awaitCompletion(5_000_000_000L)) {
                    throw new IllegalStateException("Timed out waiting for previous RTest RT submission");
                }
            } finally {
                timing.add(RayTracingFrameTiming.Segment.FENCE_WAIT_CPU, fenceWaitStart);
                closeAndCapture(fence);
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                double[] gpuMilliseconds = readGpuTimestamps(stack);
                if (gpuMilliseconds != null && this.pendingFrameGpuIndex % 120 == 0) {
                    LOGGER.info(
                        "RTest gpu_timing frame={} rt_ms={} post_rt_ms={} total_ms={} period_ns={}",
                        this.pendingFrameGpuIndex,
                        formatGpuMs(gpuMilliseconds[0]),
                        formatGpuMs(gpuMilliseconds[1]),
                        formatGpuMs(gpuMilliseconds[2]),
                        gpuTimestampPeriodNs);
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
                this.pendingFrameTimingFrame = (int)timingFrame;
                this.pendingFrameGpuIndex = frameIndex;
                int rebuiltDynamics = 0;
                this.dynamicBlasBuildCommandCount += holder.dynamicBuilds.size();
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
                this.dynamicHistoryResetPending = false;
                this.fsr.submitted(fsrToken);
                if (frameIndex % 120 == 0) {
                    LOGGER.info(
                        "RTest dynamic RT scheduling: frame={}, TLAS updates={}, BLAS build commands={}, metadata uploads={}",
                        frameIndex, dynamicTlasUpdateCount, dynamicBlasBuildCommandCount, dynamicMetadataUploadCount);
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
            try (MemoryStack blasStack = MemoryStack.stackPush()) {
                var info = blas.buildInfo(blasStack, scratchBuffer.deviceAddress(), update);
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
                for (DynamicCachedBlas cached : dynamicBlas) {
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
                for (DynamicCachedBlas cached : dynamicBlas) {
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
                        VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = topLevel.buildInfo(
                            tlasStack, scratchBuffer.deviceAddress(), topLevelUpdatePending);
                        VkAccelerationStructureBuildRangeInfoKHR tlasRange = VkAccelerationStructureBuildRangeInfoKHR
                            .calloc(tlasStack).primitiveCount(sectionBlas.size() + dynamicSlotCapacity);
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
            this.fsr.prepareForRayTracing(commandBuffer);
            List<DynamicCachedBlas> dynamicBuilds = new ArrayList<>();
            barrier(commandBuffer, stack, VK10.VK_PIPELINE_STAGE_HOST_BIT, VK10.VK_ACCESS_HOST_WRITE_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                    | KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);
            // No outer '!built' gate: an already-built BLAS with new animation vertices also runs.
            for (DynamicCachedBlas cached : dynamicBlas) {
                if (cached.built && !cached.pendingUpdate) continue;
                // BUILD into the existing, correctly-sized AS storage. This avoids UPDATE's
                // strict source-geometry identity contract while retaining stable AS addresses.
                recordBlas(commandBuffer, stack, cached.bottomLevel, false);
                dynamicBuilds.add(cached);
            }
            if (this.topLevelBuilt && this.topLevelUpdatePending) {
                barrier(commandBuffer, stack,
                    VK10.VK_PIPELINE_STAGE_HOST_BIT,
                    VK10.VK_ACCESS_HOST_WRITE_BIT,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
                try (MemoryStack tlasStack = MemoryStack.stackPush()) {
                    VkAccelerationStructureBuildGeometryInfoKHR.Buffer tlasInfo = topLevel.buildInfo(
                        tlasStack, scratchBuffer.deviceAddress(), true);
                    VkAccelerationStructureBuildRangeInfoKHR tlasRange = VkAccelerationStructureBuildRangeInfoKHR
                        .calloc(tlasStack).primitiveCount(sectionBlas.size() + dynamicSlotCapacity);
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
                .deviceAddress(sbtAddress + sbtStride).stride(sbtStride).size(2L * sbtStride);
            VkStridedDeviceAddressRegionKHR hit = VkStridedDeviceAddressRegionKHR.calloc(stack)
                .deviceAddress(sbtAddress + 3L * sbtStride).stride(sbtStride).size(2L * sbtStride);
            VkStridedDeviceAddressRegionKHR callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            writeGpuTimestamp(commandBuffer, 0, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            KHRRayTracingPipeline.vkCmdTraceRaysKHR(commandBuffer, raygen, miss, hit, callable, outputWidth, outputHeight, 1);
            writeGpuTimestamp(commandBuffer, 1, KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR);
            this.fsr.recordAfterRayTracing(commandBuffer, fsrToken);
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
            return new VkCommandBufferHolder(commandBuffer, dynamicBuilds);
            } catch (Throwable throwable) {
                if (!commandBufferEnded) {
                    // The pass owns this direct command buffer, not VulkanCommandEncoder's
                    // private currentCommandBuffer slot; finish it before encoder.destroy().
                    VK10.vkEndCommandBuffer(commandBuffer);
                }
                throw throwable;
            }
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
            double[] milliseconds = new double[3];
            milliseconds[0] = (values.get(1) - values.get(0)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[1] = (values.get(2) - values.get(1)) * gpuTimestampPeriodNs / 1_000_000.0;
            milliseconds[2] = (values.get(3) - values.get(0)) * gpuTimestampPeriodNs / 1_000_000.0;
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
                image, oldLayout, newLayout, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
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
            int aspectMask
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
                .baseMipLevel(0).levelCount(1)
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
            failure = closeAndCapture(cameraBuffer, failure);
            failure = closeAndCapture(materialBuffer, failure);
            failure = closeAndCapture(lightDataBuffer, failure);
            failure = closeAndCapture(pbrBuffer, failure);
            failure = closeAndCapture(scratchBuffer, failure);
            failure = closeAndCapture(instanceBuffer, failure);
            failure = closeAndCapture(dynamicMotionMetadataBuffer, failure);
            failure = closeAndCapture(dynamicBlasCache, failure);
            rethrow(failure);
        }

    private record VkCommandBufferHolder(org.lwjgl.vulkan.VkCommandBuffer commandBuffer,
                                         List<DynamicCachedBlas> dynamicBuilds) {
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

    static final class AccelerationStructure implements AutoCloseable {
        private final VulkanDevice device;
        private final NativeBuffer storage;
        private final long handle;
        private final long deviceAddress;
        private final long scratchSize;
        private NativeBuffer inputBuffer;
        private final boolean topLevel;
        private final int primitiveCount;
        private boolean closed;

        private AccelerationStructure(
            VulkanDevice device,
            NativeBuffer storage,
            long handle,
            long deviceAddress,
            long scratchSize,
            NativeBuffer inputBuffer,
            boolean topLevel,
            int primitiveCount
        ) {
            this.device = device;
            this.storage = storage;
            this.handle = handle;
            this.deviceAddress = deviceAddress;
            this.scratchSize = scratchSize;
            this.inputBuffer = inputBuffer;
            this.topLevel = topLevel;
            this.primitiveCount = primitiveCount;
        }

        private static AccelerationStructure createBottomLevel(VulkanDevice device, NativeBuffer vertices, int primitiveCount) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR
                    .calloc(stack)
                    .sType$Default()
                    .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(3L * Float.BYTES)
                    .maxVertex(primitiveCount * 3 - 1)
                    .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
                triangles.vertexData(address -> address.deviceAddress(vertices.deviceAddress()));
                VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack)
                    .sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(0);
                geometry.geometry().triangles(triangles);
                return create(
                    device,
                    geometry,
                    primitiveCount,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    vertices,
                    false
                );
            }
        }

        private static AccelerationStructure createTopLevel(VulkanDevice device, NativeBuffer instances, int instanceCount) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureGeometryInstancesDataKHR data = VkAccelerationStructureGeometryInstancesDataKHR
                    .calloc(stack).sType$Default().arrayOfPointers(false);
                data.data(address -> address.deviceAddress(instances.deviceAddress()));
                VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack)
                    .sType$Default().geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);
                geometry.geometry().instances(data);
                return create(
                    device,
                    geometry,
                    instanceCount,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                    instances,
                    true
                );
            }
        }

        private static AccelerationStructure create(
            VulkanDevice device,
            VkAccelerationStructureGeometryKHR geometry,
            int primitiveCount,
            int type,
            NativeBuffer inputBuffer,
            boolean topLevel
        ) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAccelerationStructureBuildGeometryInfoKHR sizeInfo = VkAccelerationStructureBuildGeometryInfoKHR
                    .calloc(stack).sType$Default().type(type).flags(
                        KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                            | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .geometryCount(1).pGeometries(VkAccelerationStructureGeometryKHR.calloc(1, stack));
                sizeInfo.pGeometries().get(0).set(geometry);
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    device.vkDevice(),
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    sizeInfo,
                    stack.ints(primitiveCount),
                    sizes
                );
                long alignment = Math.max(1L, RayTracingSupport.queryLimits(device).minScratchAlignment());
                NativeBuffer storage = NativeBuffer.create(
                    device,
                    alignUp(sizes.accelerationStructureSize(), alignment),
                    KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                        | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    false
                );
                long accelerationStructure = VK10.VK_NULL_HANDLE;
                try {
                    VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                        .sType$Default().buffer(storage.buffer).offset(0).size(sizes.accelerationStructureSize()).type(type);
                    LongBuffer handle = stack.callocLong(1);
                    VulkanUtils.crashIfFailure(
                        device,
                        KHRAccelerationStructure.vkCreateAccelerationStructureKHR(device.vkDevice(), createInfo, null, handle),
                        "Failed to create acceleration structure"
                    );
                    accelerationStructure = handle.get(0);
                    VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR
                        .calloc(stack).sType$Default().accelerationStructure(accelerationStructure);
                    long deviceAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(device.vkDevice(), addressInfo);
                    return new AccelerationStructure(
                        device,
                        storage,
                        accelerationStructure,
                        deviceAddress,
                        Math.max(sizes.buildScratchSize(), sizes.updateScratchSize()),
                        inputBuffer,
                        topLevel,
                        primitiveCount
                    );
                } catch (Throwable throwable) {
                    if (accelerationStructure != VK10.VK_NULL_HANDLE) {
                        KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(
                            device.vkDevice(), accelerationStructure, null);
                    }
                    storage.close();
                    throw throwable;
                }
            }
        }

        private VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(MemoryStack stack, long scratchAddress, boolean update) {
            return buildInfo(stack, handle, inputBuffer.deviceAddress(), scratchAddress, primitiveCount, topLevel, update);
        }

        // Pure command encoding is also exercised without a Vulkan device by the contract tests.
        static VkAccelerationStructureBuildGeometryInfoKHR.Buffer buildInfo(MemoryStack stack, long handle,
                long inputAddress, long scratchAddress, int primitiveCount, boolean topLevel, boolean update) {
            VkAccelerationStructureGeometryKHR.Buffer geometries = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            if (topLevel) {
                VkAccelerationStructureGeometryInstancesDataKHR instances = VkAccelerationStructureGeometryInstancesDataKHR
                    .calloc(stack).sType$Default().arrayOfPointers(false);
                instances.data(address -> address.deviceAddress(inputAddress));
                geometries.get(0).sType$Default()
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0)
                    .geometry().instances(instances);
            } else {
                VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR
                    .calloc(stack)
                    .sType$Default()
                    .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(3L * Float.BYTES)
                    .maxVertex(primitiveCount * 3 - 1)
                    .indexType(KHRAccelerationStructure.VK_INDEX_TYPE_NONE_KHR);
                triangles.vertexData(address -> address.deviceAddress(inputAddress));
                geometries.get(0).sType$Default()
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(0)
                    .geometry().triangles(triangles);
            }

            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
            info.get(0).sType$Default()
                .type(topLevel
                    ? KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR
                    : KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                    | KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR)
                .mode(update ? KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .srcAccelerationStructure(update ? handle : VK10.VK_NULL_HANDLE)
                .dstAccelerationStructure(handle)
                .geometryCount(1)
                .pGeometries(geometries)
                .scratchData(address -> address.deviceAddress(scratchAddress));
            return info;
        }

        private void rebindInputBuffer(NativeBuffer inputBuffer) {
            if (!this.topLevel) {
                throw new IllegalStateException("Only TLAS input buffers can be rebound");
            }
            this.inputBuffer = inputBuffer;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
            } finally {
                storage.close();
            }
        }
    }

    private static final class NativeBuffer implements AutoCloseable {
        private final VulkanDevice device;
        private final long buffer;
        private final long allocation;
        private final long size;
        private boolean closed;

        private NativeBuffer(VulkanDevice device, long buffer, long allocation, long size) {
            this.device = device;
            this.buffer = buffer;
            this.allocation = allocation;
            this.size = size;
        }

        private static NativeBuffer create(VulkanDevice device, long size, int usage, boolean hostVisible) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                org.lwjgl.vulkan.VkBufferCreateInfo bufferInfo = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType$Default().size(size).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                if (hostVisible) {
                    allocationInfo.requiredFlags(VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
                }
                LongBuffer bufferHandle = stack.callocLong(1);
                PointerBuffer allocationHandle = stack.callocPointer(1);
                VulkanUtils.crashIfFailure(
                    device,
                    Vma.vmaCreateBuffer(device.vma(), bufferInfo, allocationInfo, bufferHandle, allocationHandle, null),
                    "Failed to create ray-tracing buffer"
                );
                long buffer = bufferHandle.get(0);
                long allocation = allocationHandle.get(0);
                try {
                    return new NativeBuffer(device, buffer, allocation, size);
                } catch (Throwable throwable) {
                    Vma.vmaDestroyBuffer(device.vma(), buffer, allocation);
                    throw throwable;
                }
            }
        }

        private Mapped map() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer mapped = stack.callocPointer(1);
                VulkanUtils.crashIfFailure(device, Vma.vmaMapMemory(device.vma(), allocation, mapped), "Failed to map ray-tracing buffer");
                try {
                    return new Mapped(MemoryUtil.memByteBuffer(mapped.get(0), Math.toIntExact(size)));
                } catch (Throwable throwable) {
                    Vma.vmaUnmapMemory(device.vma(), allocation);
                    throw throwable;
                }
            }
        }

        private final class Mapped implements AutoCloseable {
            private final ByteBuffer buffer;
            private boolean closed;

            private Mapped(ByteBuffer buffer) {
                this.buffer = buffer;
            }

            private ByteBuffer buffer() {
                return this.buffer;
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    Vma.vmaFlushAllocation(device.vma(), allocation, 0L, VK10.VK_WHOLE_SIZE);
                } finally {
                    Vma.vmaUnmapMemory(device.vma(), allocation);
                }
            }
        }

        private long deviceAddress() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var addressInfo = org.lwjgl.vulkan.VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer);
                return VK12.vkGetBufferDeviceAddress(device.vkDevice(), addressInfo);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Vma.vmaDestroyBuffer(device.vma(), buffer, allocation);
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

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1L) / alignment * alignment;
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
