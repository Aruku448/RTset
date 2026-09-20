package com.rtest.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Camera;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Vector3fc;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
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
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkRayTracingPipelineCreateInfoKHR;
import org.lwjgl.vulkan.VkRayTracingShaderGroupCreateInfoKHR;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkStridedDeviceAddressRegionKHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.slf4j.Logger;

public final class RayTracingScene {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final class SceneGeometry {
        private static final int FLOATS_PER_TRIANGLE_MATERIAL = 28;
        private static final AtomicLong NEXT_REVISION = new AtomicLong();

        /** Geometry captured for one native Minecraft render section. */
        public static final class SectionGeometry {
            final int originX;
            final int originY;
            final int originZ;
            final float[] vertices;
            final float[] materialData;
            final int triangleCount;
            final int vertexFingerprint;

            private SectionGeometry(int originX, int originY, int originZ, float[] vertices, float[] materialData) {
                if (vertices.length == 0 || vertices.length % 9 != 0) {
                    throw new IllegalArgumentException("Section vertices must contain complete triangles");
                }
                if (materialData.length != vertices.length / 9 * FLOATS_PER_TRIANGLE_MATERIAL) {
                    throw new IllegalArgumentException("Section material data does not match the triangle count");
                }
                this.originX = originX;
                this.originY = originY;
                this.originZ = originZ;
                // SectionGeometry is published to the merge executor. Copy both arrays so a
                // compiled-section cache or capture accumulator can never mutate a worker input.
                float[] copiedVertices = vertices.clone();
                float[] copiedMaterialData = materialData.clone();
                // LightTree derives emitter normals from the vertex cross product, while hit
                // shading uses materialData's geometric normal. Normalize their winding at the
                // immutable section boundary as a final guard for compiled meshes and custom
                // BakedQuads that bypass addOrientedTriangle(). Preserve the UV association when
                // exchanging the second and third vertices.
                alignTriangleWinding(copiedVertices, copiedMaterialData);
                this.vertices = copiedVertices;
                this.materialData = copiedMaterialData;
                this.triangleCount = vertices.length / 9;
                this.vertexFingerprint = java.util.Arrays.hashCode(this.vertices);
            }

            private static void alignTriangleWinding(float[] vertices, float[] materialData) {
                int triangleCount = vertices.length / 9;
                for (int triangle = 0; triangle < triangleCount; triangle++) {
                    int vertexOffset = triangle * 9;
                    int materialOffset = triangle * FLOATS_PER_TRIANGLE_MATERIAL;
                    float normalX = materialData[materialOffset + 4];
                    float normalY = materialData[materialOffset + 5];
                    float normalZ = materialData[materialOffset + 6];
                    float normalLengthSquared = normalX * normalX
                        + normalY * normalY + normalZ * normalZ;
                    if (!(normalLengthSquared > 1.0E-10F)
                        || !Float.isFinite(normalLengthSquared)) {
                        continue;
                    }
                    float ax = vertices[vertexOffset + 3] - vertices[vertexOffset];
                    float ay = vertices[vertexOffset + 4] - vertices[vertexOffset + 1];
                    float az = vertices[vertexOffset + 5] - vertices[vertexOffset + 2];
                    float bx = vertices[vertexOffset + 6] - vertices[vertexOffset];
                    float by = vertices[vertexOffset + 7] - vertices[vertexOffset + 1];
                    float bz = vertices[vertexOffset + 8] - vertices[vertexOffset + 2];
                    float crossX = ay * bz - az * by;
                    float crossY = az * bx - ax * bz;
                    float crossZ = ax * by - ay * bx;
                    float areaSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
                    if (!(areaSquared > 1.0E-10F) || !Float.isFinite(areaSquared)
                        || crossX * normalX + crossY * normalY + crossZ * normalZ >= 0.0F) {
                        continue;
                    }
                    for (int component = 0; component < 3; component++) {
                        int first = vertexOffset + 3 + component;
                        int second = vertexOffset + 6 + component;
                        float swapped = vertices[first];
                        vertices[first] = vertices[second];
                        vertices[second] = swapped;
                    }
                    // UV01.xyzw stores UV0/UV1 and UV2.xy stores UV2. UV2.z/w are flags.
                    for (int component = 0; component < 2; component++) {
                        int first = materialOffset + 10 + component;
                        int second = materialOffset + 12 + component;
                        float swapped = materialData[first];
                        materialData[first] = materialData[second];
                        materialData[second] = swapped;
                    }
                }
            }

            public int triangleCount() {
                return this.triangleCount;
            }

            int vertexFingerprint() {
                return this.vertexFingerprint;
            }
        }

        final List<SectionGeometry> sections;
        final float[] vertices;
        final float[] materialData;
        final int[] pbrData;
        final int triangleCount;
        final int renderDistanceChunks;
        final long revision;
        final double originX;
        final double originY;
        final double originZ;
        final RayTracingLightTree.Data lightTree;

        private SceneGeometry(
            List<SectionGeometry> sections,
            float[] vertices,
            float[] materialData,
            int[] pbrData,
            int triangleCount,
            int renderDistanceChunks,
            double originX,
            double originY,
            double originZ
        ) {
            this(sections, vertices, materialData, pbrData, triangleCount, renderDistanceChunks,
                originX, originY, originZ, false, NEXT_REVISION.incrementAndGet());
        }

        private SceneGeometry(
            List<SectionGeometry> sections,
            float[] vertices,
            float[] materialData,
            int[] pbrData,
            int triangleCount,
            int renderDistanceChunks,
            double originX,
            double originY,
            double originZ,
            boolean allowEmpty
        ) {
            this(sections, vertices, materialData, pbrData, triangleCount, renderDistanceChunks,
                originX, originY, originZ, allowEmpty, NEXT_REVISION.incrementAndGet());
        }

        private SceneGeometry(
            List<SectionGeometry> sections,
            float[] vertices,
            float[] materialData,
            int[] pbrData,
            int triangleCount,
            int renderDistanceChunks,
            double originX,
            double originY,
            double originZ,
            boolean allowEmpty,
            long resetRevision
        ) {
            this(sections, vertices, materialData, pbrData, triangleCount, renderDistanceChunks,
                originX, originY, originZ, allowEmpty, resetRevision, true);
        }

        private SceneGeometry(
            List<SectionGeometry> sections,
            float[] vertices,
            float[] materialData,
            int[] pbrData,
            int triangleCount,
            int renderDistanceChunks,
            double originX,
            double originY,
            double originZ,
            boolean allowEmpty,
            long resetRevision,
            boolean buildLightTree
        ) {
            this(sections, vertices, materialData, pbrData, triangleCount, renderDistanceChunks,
                originX, originY, originZ, allowEmpty, resetRevision, buildLightTree, true);
        }

        /**
         * Creates a scene from arrays owned by the caller. Full captures and merged snapshots
         * already allocate fresh arrays and close their mutable capture session immediately;
         * copying them again here doubled the large scene payload on every dirty update.
         */
        private SceneGeometry(
            List<SectionGeometry> sections,
            float[] vertices,
            float[] materialData,
            int[] pbrData,
            int triangleCount,
            int renderDistanceChunks,
            double originX,
            double originY,
            double originZ,
            boolean allowEmpty,
            long resetRevision,
            boolean buildLightTree,
            boolean copyArrays
        ) {
            if ((!allowEmpty && vertices.length == 0) || vertices.length % 9 != 0) {
                throw new IllegalArgumentException("Scene vertices must contain complete triangles");
            }
            if (materialData.length != triangleCount * FLOATS_PER_TRIANGLE_MATERIAL) {
                throw new IllegalArgumentException("Scene material data does not match the triangle count");
            }
            if (renderDistanceChunks < 2) {
                throw new IllegalArgumentException("Render distance must contain at least two chunks");
            }
            // SceneGeometry is the immutable hand-off between the render thread and the single
            // merge worker. List.copyOf alone is insufficient because the array elements are
            // mutable Java arrays.
            this.sections = List.copyOf(sections);
            this.vertices = copyArrays ? vertices.clone() : vertices;
            this.materialData = copyArrays ? materialData.clone() : materialData;
            this.pbrData = copyArrays ? pbrData.clone() : pbrData;
            this.triangleCount = triangleCount;
            this.renderDistanceChunks = renderDistanceChunks;
            this.revision = resetRevision;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            // Prime builds its emissive-light hierarchy from the same immutable scene snapshot
            // that feeds the TLAS. Keep the RT variant scene-wide because its geometry table is
            // already flattened across sections.
            if (buildLightTree) {
                this.lightTree = RayTracingLightTree.build(this.sections, this.materialData,
                    originX, originY, originZ);
            } else {
                // Partial deltas are consumed only for their sections/materials by
                // replaceSections(); constructing a second emitter tree here would be pure
                // duplicate work. The merged immutable scene builds the one tree that reaches
                // Vulkan.
                this.lightTree = RayTracingLightTree.Data.create(List.of(),
                    new int[this.materialData.length / FLOATS_PER_TRIANGLE_MATERIAL]);
            }
        }

        public int triangleCount() {
            return this.triangleCount;
        }

        /**
         * Epoch for temporal consumers. Partial section commits preserve it because depth,
         * normal and motion rejection invalidate changed pixels locally; unrelated full scenes
         * receive a new epoch.
         */
        public long revision() {
            return this.revision;
        }

        public boolean requiresRecapture(Camera camera, int renderDistanceChunks) {
            // Section vertices are local to their Section and TLAS instances carry their
            // world transforms. Camera-window movement is handled by RayTracingProbe through
            // an incremental added/removed Section delta; this method remains the compatibility
            // check for a full snapshot when the configured render distance changes.
            return this.renderDistanceChunks != renderDistanceChunks;
        }

        /** Replaces only sections invalidated by a client-world block update. */
        SceneGeometry replaceSections(Collection<Long> dirtySectionOrigins, SceneGeometry replacements, Camera camera) {
            return replaceSections(dirtySectionOrigins, replacements, camera.position());
        }

        /** Merges an immutable capture result on the geometry worker. */
        SceneGeometry replaceSections(Collection<Long> dirtySectionOrigins, SceneGeometry replacements, Vec3 cameraPosition) {
            long startNanos = System.nanoTime();
            int previousSectionCount = this.sections.size();
            // Chunk/window invalidations can name sections that were already evicted or were
            // empty in the published scene. A zero-section delta for those keys is a no-op; do
            // not flatten and copy the entire world just to publish the same object again.
            if (replacements.sections.isEmpty() && !dirtySectionOrigins.isEmpty()) {
                boolean removesPublishedSection = false;
                for (SectionGeometry section : this.sections) {
                    if (dirtySectionOrigins.contains(sectionOriginKey(section))) {
                        removesPublishedSection = true;
                        break;
                    }
                }
                if (!removesPublishedSection) {
                    return this;
                }
            }
            Map<Long, SectionGeometry> merged = new LinkedHashMap<>();
            for (SectionGeometry section : this.sections) {
                merged.put(sectionOriginKey(section), section);
            }
            for (long origin : dirtySectionOrigins) {
                merged.remove(origin);
            }
            for (SectionGeometry section : replacements.sections) {
                merged.put(sectionOriginKey(section), section);
            }

            List<SectionGeometry> mergedSections = new ArrayList<>(merged.values());
            float[] vertices;
            float[] materials;
            if (mergedSections.isEmpty()) {
                FloatAccumulator fallbackVertices = new FloatAccumulator();
                FloatAccumulator fallbackMaterials = new FloatAccumulator();
                addFallbackGeometry(mergedSections, fallbackVertices, fallbackMaterials, cameraPosition);
                vertices = fallbackVertices.toArray();
                materials = fallbackMaterials.toArray();
            } else {
                int vertexLength = 0;
                int materialLength = 0;
                for (SectionGeometry section : mergedSections) {
                    vertexLength = Math.addExact(vertexLength, section.vertices.length);
                    materialLength = Math.addExact(materialLength, section.materialData.length);
                }
                vertices = new float[vertexLength];
                materials = new float[materialLength];
                int vertexOffset = 0;
                int materialOffset = 0;
                for (SectionGeometry section : mergedSections) {
                    System.arraycopy(section.vertices, 0, vertices, vertexOffset, section.vertices.length);
                    System.arraycopy(section.materialData, 0, materials, materialOffset, section.materialData.length);
                    vertexOffset += section.vertices.length;
                    materialOffset += section.materialData.length;
                }
            }
            SceneGeometry result = new SceneGeometry(
                mergedSections,
                vertices,
                materials,
                replacements.pbrData.length == 0 ? this.pbrData : replacements.pbrData,
                vertices.length / 9,
                this.renderDistanceChunks,
                this.originX,
                this.originY,
                this.originZ,
                false,
                this.revision,
                true,
                false
            );
            LOGGER.info(
                "RTest geometry CPU merge: dirty={}, oldSections={}, replacementSections={}, newSections={}, duration={} ms",
                dirtySectionOrigins.size(), previousSectionCount, replacements.sections.size(),
                mergedSections.size(), (System.nanoTime() - startNanos) / 1_000_000L);
            return result;
        }

        private static long sectionOriginKey(SectionGeometry section) {
            return new BlockPos(section.originX, section.originY, section.originZ).asLong();
        }

        /**
         * Incremental CPU capture session. The public interface deliberately exposes only
         * begin/step/build, so callers cannot accidentally rebuild the whole world in one render
         * callback. The implementation keeps the PBR cache alive across steps.
         */
        private static final class FloatAccumulator {
            private float[] values = new float[1024];
            private int size;

            void add(float value) {
                if (this.size == this.values.length) {
                    this.values = java.util.Arrays.copyOf(this.values,
                        Math.max(16, Math.multiplyExact(this.values.length, 2)));
                }
                this.values[this.size++] = value;
            }

            void addAll(float[] source) {
                int required = Math.addExact(this.size, source.length);
                if (required > this.values.length) {
                    int capacity = this.values.length;
                    while (capacity < required) {
                        capacity = Math.max(required, Math.multiplyExact(capacity, 2));
                    }
                    this.values = java.util.Arrays.copyOf(this.values, capacity);
                }
                System.arraycopy(source, 0, this.values, this.size, source.length);
                this.size = required;
            }

            float[] toArray() {
                return java.util.Arrays.copyOf(this.values, this.size);
            }

            int size() {
                return this.size;
            }

            void clear() {
                this.values = new float[0];
                this.size = 0;
            }
        }

        public static final class CaptureSession implements AutoCloseable {
            private final ClientLevel level;
            private final Vec3 cameraPosition;
            private final BlockStateModelSet modelSet;
            private final FluidStateModelSet fluidModelSet;
            private final BlockColors blockColors;
            private final int renderDistanceChunks;
            private final List<BlockPos> sectionOrigins;
            private final List<BlockPos> windowOrigins;
            private final List<SectionGeometry> sections = new ArrayList<>();
            private final FloatAccumulator allVertices = new FloatAccumulator();
            private final FloatAccumulator allMaterialData = new FloatAccumulator();
            private final RayTracingPbrMaterials pbrMaterials;
            private final boolean ownsPbrMaterials;
            private int cursor;
            private int compiledMeshSections;
            private int cpuFallbackSections;
            private int fluidSections;
            private boolean closed;

            private CaptureSession(
                ClientLevel level,
                Camera camera,
                BlockStateModelSet modelSet,
                FluidStateModelSet fluidModelSet,
                BlockColors blockColors,
                int renderDistanceChunks,
                List<BlockPos> sectionOrigins,
                List<BlockPos> windowOrigins,
                RayTracingPbrMaterials pbrMaterials,
                boolean ownsPbrMaterials
            ) {
                this.level = level;
                this.cameraPosition = camera.position();
                this.modelSet = modelSet;
                this.fluidModelSet = fluidModelSet;
                this.blockColors = blockColors;
                this.renderDistanceChunks = renderDistanceChunks;
                this.sectionOrigins = List.copyOf(sectionOrigins);
                this.windowOrigins = List.copyOf(windowOrigins);
                this.pbrMaterials = pbrMaterials;
                this.ownsPbrMaterials = ownsPbrMaterials;
                LOGGER.info("RTest queued incremental capture of {} sections within {} chunks", this.sectionOrigins.size(), renderDistanceChunks);
            }

            public static CaptureSession begin(
                ClientLevel level,
                ResourceManager resourceManager,
                Camera camera,
                BlockStateModelSet modelSet,
                FluidStateModelSet fluidModelSet,
                BlockColors blockColors,
                int renderDistanceChunks
            ) {
                List<BlockPos> windowOrigins = requestedSectionOrigins(level, camera, renderDistanceChunks);
                return new CaptureSession(
                    level,
                    camera,
                    modelSet,
                    fluidModelSet,
                    blockColors,
                    renderDistanceChunks,
                    captureSectionOrigins(level, windowOrigins),
                    windowOrigins,
                    new RayTracingPbrMaterials(resourceManager),
                    true
                );
            }

            static CaptureSession beginShared(
                ClientLevel level,
                Camera camera,
                BlockStateModelSet modelSet,
                FluidStateModelSet fluidModelSet,
                BlockColors blockColors,
                int renderDistanceChunks,
                List<BlockPos> sectionOrigins,
                List<BlockPos> windowOrigins,
                RayTracingPbrMaterials pbrMaterials
            ) {
                return new CaptureSession(
                    level,
                    camera,
                    modelSet,
                    fluidModelSet,
                    blockColors,
                    renderDistanceChunks,
                    sectionOrigins,
                    windowOrigins,
                    pbrMaterials,
                    false
                );
            }

            /** Captures at most {@code sectionBudget} sections on this call. */
            public boolean step(int sectionBudget) {
                if (this.closed) {
                    throw new IllegalStateException("Scene capture session is closed");
                }
                if (sectionBudget <= 0) {
                    throw new IllegalArgumentException("Section capture budget must be positive");
                }
                int end = Math.min(this.sectionOrigins.size(), this.cursor + sectionBudget);
                while (this.cursor < end) {
                    BlockPos origin = this.sectionOrigins.get(this.cursor++);
                    CompiledSectionMeshCache.CompiledMesh compiled = CompiledSectionMeshCache.get(origin);
                    boolean fluidEnabled = RayTracingClientConfig.INSTANCE.fluidRtEnabled.get();
                    boolean containsFluid = fluidEnabled && containsFluid(this.level, origin);
                    boolean pbrNeedsSpriteCapture = RayTracingClientConfig.INSTANCE.pbrTerrainCpuCaptureEnabled.get();
                    // Vanilla's compiled mesh has only a render-layer bit; it cannot carry the
                    // block/biome tint and IOR needed by colored glass. Rewalk glass sections so
                    // the CPU path emits the complete optical material instead of the generic
                    // translucent fallback used by CompiledSectionMeshCache.
                    // These checks cannot change the source selection while sprite-aware PBR
                    // capture is enabled, so skip the extra section scans in that mode.
                    boolean hasGlass = !pbrNeedsSpriteCapture && containsGlass(this.level, origin);
                    boolean containsEmitter;
                    if (pbrNeedsSpriteCapture) {
                        containsEmitter = false;
                    } else {
                        containsEmitter = containsEmissiveBlock(this.level, origin);
                    }
                    // MeshData contains UVs but no sprite/material identity. Once PBR is enabled,
                    // using it would silently discard companion maps for ordinary terrain. Keep
                    // the sprite-aware CPU path as the correctness default; the opt-out is an
                    // explicit performance trade-off for users who accept neutral PBR defaults.
                    CompiledSectionMeshCache.CompiledMesh source = pbrNeedsSpriteCapture
                        || containsFluid || hasGlass || containsEmitter
                        ? null : compiled;
                    SectionGeometry section = captureSection(
                        this.level, this.modelSet, this.fluidModelSet, this.blockColors,
                        this.pbrMaterials, origin, source, fluidEnabled);
                    if (containsFluid) {
                        this.fluidSections++;
                    }
                    if (source != null) {
                        this.compiledMeshSections++;
                    } else {
                        this.cpuFallbackSections++;
                    }
                    if (section != null) {
                        this.sections.add(section);
                        this.allVertices.addAll(section.vertices);
                        this.allMaterialData.addAll(section.materialData);
                    }
                }
                return this.isComplete();
            }

            public boolean isComplete() {
                return this.cursor >= this.sectionOrigins.size();
            }

            public int processedSections() {
                return this.cursor;
            }

            public int totalSections() {
                return this.sectionOrigins.size();
            }

            /** The requested window, including sections that captured as empty. */
            List<BlockPos> sectionOrigins() {
                return this.sectionOrigins;
            }

            /** The requested loaded-chunk window, including empty sections. */
            List<BlockPos> windowOrigins() {
                return this.windowOrigins;
            }

            public int renderDistanceChunks() {
                return this.renderDistanceChunks;
            }

            public SceneGeometry build() {
                return buildInternal(true);
            }

            SceneGeometry buildPartial() {
                return buildInternal(false);
            }

            private SceneGeometry buildInternal(boolean allowFallback) {
                if (!this.isComplete()) {
                    throw new IllegalStateException("Scene capture is not complete");
                }
                if (this.closed) {
                    throw new IllegalStateException("Scene capture session is closed");
                }
                if (allowFallback && this.sections.isEmpty()) {
                    addFallbackGeometry(this.sections, this.allVertices, this.allMaterialData, this.cameraPosition);
                }
                // A partial capture is only a section delta consumed by replaceSections().
                // Flattening the delta and packing the complete PBR table here created a second
                // full-scene allocation before the merge worker even started its one required
                // flattening pass.
                if (!allowFallback) {
                    return new SceneGeometry(
                        this.sections,
                        new float[0],
                        new float[0],
                        new int[0],
                        0,
                        this.renderDistanceChunks,
                        this.cameraPosition.x,
                        this.cameraPosition.y,
                        this.cameraPosition.z,
                        true,
                        NEXT_REVISION.incrementAndGet(),
                        false,
                        false
                    );
                }
                int[] pbrData = this.pbrMaterials.packedData();
                LOGGER.info("RTest loaded {} PBR companion texture sets during scene capture", this.pbrMaterials.loadedMapCount());
                LOGGER.info("RTest scene capture used {} compiled MeshData sections and {} CPU fallback sections",
                    this.compiledMeshSections, this.cpuFallbackSections);
                if (this.fluidSections > 0) {
                    LOGGER.info("RTest fluid capture inspected {} sections; fluid-aware CPU path={} (fallback={})",
                        this.fluidSections, RayTracingClientConfig.INSTANCE.fluidRtEnabled.get(),
                        !RayTracingClientConfig.INSTANCE.fluidRtEnabled.get());
                }
                var origin = this.cameraPosition;
                return new SceneGeometry(
                    this.sections,
                    this.allVertices.toArray(),
                    this.allMaterialData.toArray(),
                    pbrData,
                    this.allVertices.size() / 9,
                    this.renderDistanceChunks,
                    origin.x,
                    origin.y,
                    origin.z,
                    !allowFallback,
                    NEXT_REVISION.incrementAndGet(),
                    allowFallback,
                    false
                );
            }

            @Override
            public void close() {
                if (!this.closed) {
                    this.closed = true;
                    this.sections.clear();
                    this.allVertices.clear();
                    this.allMaterialData.clear();
                    if (this.ownsPbrMaterials) {
                        this.pbrMaterials.close();
                    }
                }
            }
        }

        public static SceneGeometry capture(
            ClientLevel level,
            ResourceManager resourceManager,
            Camera camera,
            BlockStateModelSet modelSet,
            FluidStateModelSet fluidModelSet,
            BlockColors blockColors,
            int renderDistanceChunks
        ) {
            try (CaptureSession session = CaptureSession.begin(
                level, resourceManager, camera, modelSet, fluidModelSet, blockColors, renderDistanceChunks)) {
                while (!session.step(Math.max(1, session.totalSections()))) {
                    // Synchronous compatibility wrapper for the smoke-test API.
                }
                return session.build();
            }
        }

        private static boolean containsFluid(ClientLevel level, BlockPos sectionOrigin) {
            LevelChunkSection section = section(level, sectionOrigin);
            return section != null && section.hasFluid();
        }

        private static boolean containsEmissiveBlock(ClientLevel level, BlockPos sectionOrigin) {
            LevelChunkSection section = section(level, sectionOrigin);
            return section != null && section.maybeHas(state -> state.getLightEmission() > 0);
        }

        private static boolean containsGlass(ClientLevel level, BlockPos sectionOrigin) {
            LevelChunkSection section = section(level, sectionOrigin);
            return section != null && section.maybeHas(state -> {
                var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                return key != null && key.getPath().contains("glass");
            });
        }

        private static LevelChunkSection section(ClientLevel level, BlockPos sectionOrigin) {
            LevelChunk chunk = level.getChunkSource().getChunk(
                sectionOrigin.getX() >> 4,
                sectionOrigin.getZ() >> 4,
                ChunkStatus.FULL,
                false);
            if (chunk == null) {
                return null;
            }
            int sectionIndex = level.getSectionIndexFromSectionY(sectionOrigin.getY() >> 4);
            LevelChunkSection[] sections = chunk.getSections();
            return sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
        }

        private static SectionGeometry captureSection(
            ClientLevel level,
            BlockStateModelSet modelSet,
            FluidStateModelSet fluidModelSet,
            BlockColors blockColors,
            RayTracingPbrMaterials pbrMaterials,
            BlockPos sectionOrigin,
            CompiledSectionMeshCache.CompiledMesh compiled,
            boolean fluidRtEnabled
        ) {
            if (compiled != null) {
                return new SectionGeometry(
                    sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ(),
                    compiled.vertices, compiled.materialData);
            }
            FloatAccumulator sectionVertices = new FloatAccumulator();
            FloatAccumulator sectionMaterialData = new FloatAccumulator();
            List<BlockStateModelPart> parts = new ArrayList<>();
            // One mutable position and one reusable RandomSource per Section instead of 4096
            // BlockPos and 4096 RandomSource instances. Both are strictly call-local, so no
            // capture state is shared across sections or threads.
            BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
            RandomSource random = RandomSource.create();
            for (int x = sectionOrigin.getX(); x < sectionOrigin.getX() + 16; x++) {
                for (int y = sectionOrigin.getY(); y < sectionOrigin.getY() + 16; y++) {
                    for (int z = sectionOrigin.getZ(); z < sectionOrigin.getZ() + 16; z++) {
                        position.set(x, y, z);
                        BlockState state = level.getBlockState(position);
                        FluidState fluidState = state.getFluidState();
                        if (fluidRtEnabled && !fluidState.isEmpty()) {
                            addFluidGeometry(sectionVertices, sectionMaterialData, sectionOrigin, level, fluidModelSet, position, state, fluidState);
                        }
                        if (state.getRenderShape() != RenderShape.MODEL) {
                            continue;
                        }
                        BlockStateModel model = modelSet.get(state);
                        parts.clear();
                        // setSeed matches RandomSource.create(seed) while reusing one instance.
                        random.setSeed(state.getSeed(position));
                        model.collectParts(level, position, state, random, parts);
                        for (BlockStateModelPart part : parts) {
                            for (Direction direction : Direction.values()) {
                                BlockPos neighborPos = position.relative(direction);
                                BlockState neighbor = level.getBlockState(neighborPos);
                                // Match vanilla face culling instead of only testing
                                // isSolidRender(). Two adjacent glass blocks do not satisfy that
                                // test, so their shared internal faces used to reach the path
                                // tracer as bogus dielectric interfaces.
                                if (!Block.shouldRenderFace(level, position, state, neighbor, direction)) {
                                    continue;
                                }
                                addQuads(sectionVertices, sectionMaterialData, sectionOrigin, position, state,
                                    part.getQuads(direction), pbrMaterials, blockColors, level);
                            }
                            addQuads(sectionVertices, sectionMaterialData, sectionOrigin, position, state,
                                part.getQuads(null), pbrMaterials, blockColors, level);
                        }
                    }
                }
            }
            if (sectionVertices.size() == 0) {
                return null;
            }
            return new SectionGeometry(
                sectionOrigin.getX(), sectionOrigin.getY(), sectionOrigin.getZ(),
                toFloatArray(sectionVertices), toFloatArray(sectionMaterialData));
        }

        private static void addFallbackGeometry(
            List<SectionGeometry> sections,
            FloatAccumulator allVertices,
            FloatAccumulator allMaterialData,
            Vec3 cameraPosition
        ) {
            BlockPos fallbackOrigin = new BlockPos(
                (int)Math.floor(cameraPosition.x / 16.0) * 16,
                (int)Math.floor(cameraPosition.y / 16.0) * 16,
                (int)Math.floor(cameraPosition.z / 16.0) * 16);
            FloatAccumulator fallbackVertices = new FloatAccumulator();
            FloatAccumulator fallbackMaterials = new FloatAccumulator();
            fallbackVertices.add(-1.0F); fallbackVertices.add(-1.0F); fallbackVertices.add(4.0F);
            fallbackVertices.add(1.0F); fallbackVertices.add(-1.0F); fallbackVertices.add(4.0F);
            fallbackVertices.add(0.0F); fallbackVertices.add(1.0F); fallbackVertices.add(4.0F);
            addMaterial(fallbackMaterials, 0x808080, 0.0F, 0.0F, -1.0F);
            sections.add(new SectionGeometry(
                fallbackOrigin.getX(), fallbackOrigin.getY(), fallbackOrigin.getZ(),
                toFloatArray(fallbackVertices), toFloatArray(fallbackMaterials)));
            allVertices.addAll(toFloatArray(fallbackVertices));
            allMaterialData.addAll(toFloatArray(fallbackMaterials));
        }

        /** Origins that are cheap to capture: loaded sections containing at least one block. */
        static List<BlockPos> loadedSectionOrigins(ClientLevel level, Camera camera, int renderDistanceChunks) {
            return collectSectionOrigins(level, camera, renderDistanceChunks, true);
        }

        /** Membership for the requested window, including loaded-but-empty sections. */
        static List<BlockPos> requestedSectionOrigins(ClientLevel level, Camera camera, int renderDistanceChunks) {
            return collectSectionOrigins(level, camera, renderDistanceChunks, false);
        }

        private static List<BlockPos> captureSectionOrigins(ClientLevel level, List<BlockPos> windowOrigins) {
            List<BlockPos> origins = new ArrayList<>();
            Map<Long, LevelChunk> chunks = new HashMap<>();
            for (BlockPos origin : windowOrigins) {
                long chunkKey = new net.minecraft.world.level.ChunkPos(
                    origin.getX() >> 4, origin.getZ() >> 4).pack();
                LevelChunk chunk = chunks.computeIfAbsent(chunkKey, ignored -> level.getChunkSource().getChunk(
                    origin.getX() >> 4, origin.getZ() >> 4, ChunkStatus.FULL, false));
                if (chunk == null) {
                    continue;
                }
                int sectionIndex = level.getSectionIndexFromSectionY(origin.getY() >> 4);
                LevelChunkSection[] sections = chunk.getSections();
                if (sectionIndex >= 0 && sectionIndex < sections.length
                    && !sections[sectionIndex].hasOnlyAir()) {
                    origins.add(origin);
                }
            }
            return origins;
        }

        private static List<BlockPos> collectSectionOrigins(
            ClientLevel level, Camera camera, int renderDistanceChunks, boolean nonEmptyOnly) {
            int cameraChunkX = (int)Math.floor(camera.position().x / 16.0);
            int cameraChunkZ = (int)Math.floor(camera.position().z / 16.0);
            List<BlockPos> origins = new ArrayList<>();
            int radius = Math.max(2, renderDistanceChunks);
            for (int chunkX = cameraChunkX - radius; chunkX <= cameraChunkX + radius; chunkX++) {
                for (int chunkZ = cameraChunkZ - radius; chunkZ <= cameraChunkZ + radius; chunkZ++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    LevelChunkSection[] sections = chunk.getSections();
                    for (int index = 0; index < sections.length; index++) {
                        if (nonEmptyOnly && sections[index].hasOnlyAir()) {
                            continue;
                        }
                        int sectionY = level.getSectionYFromSectionIndex(index);
                        origins.add(new BlockPos(chunkX * 16, sectionY * 16, chunkZ * 16));
                    }
                }
            }
            return origins;
        }


        private static float[] toFloatArray(FloatAccumulator values) {
            return values.toArray();
        }

        private record FluidVertex(float x, float y, float z, float u, float v) {
        }

        private static void addFluidGeometry(
            FloatAccumulator vertices,
            FloatAccumulator materialData,
            BlockPos sectionOrigin,
            ClientLevel level,
            FluidStateModelSet fluidModelSet,
            BlockPos blockPos,
            BlockState blockState,
            FluidState fluidState
        ) {
            Fluid fluid = fluidState.getType();
            FluidModel model = fluidModelSet.get(fluidState);
            if (model == null) {
                return;
            }
            BlockState aboveState = level.getBlockState(blockPos.above());
            BlockState belowState = level.getBlockState(blockPos.below());
            FluidState aboveFluid = aboveState.getFluidState();
            FluidState belowFluid = belowState.getFluidState();
            float height = fluidHeight(level, fluid, blockPos);
            if (height < 0.0F) {
                return;
            }
            float north = fluidHeight(level, fluid, blockPos.north());
            float south = fluidHeight(level, fluid, blockPos.south());
            float west = fluidHeight(level, fluid, blockPos.west());
            float east = fluidHeight(level, fluid, blockPos.east());
            float northWest = averageFluidHeight(level, fluid, height, north, west, blockPos.north().west());
            float northEast = averageFluidHeight(level, fluid, height, north, east, blockPos.north().east());
            float southWest = averageFluidHeight(level, fluid, height, south, west, blockPos.south().west());
            float southEast = averageFluidHeight(level, fluid, height, south, east, blockPos.south().east());
            int tint = model.fluidTintSource() == null
                ? 0xFFFFFF
                : model.fluidTintSource().colorInWorld(fluidState, blockState, level, blockPos) & 0xFFFFFF;
            MaterialProperties properties = fluidMaterialProperties(fluid);
            TextureAtlasSprite still = model.stillMaterial().sprite();
            TextureAtlasSprite flowing = model.flowingMaterial().sprite();

            if (!fluid.isSame(aboveFluid.getType()) && !aboveState.isSolidRender()) {
                addFluidQuad(
                    vertices,
                    materialData,
                    sectionOrigin,
                    blockPos,
                    new FluidVertex(0.0F, northWest, 0.0F, still.getU0(), still.getV0()),
                    new FluidVertex(0.0F, southWest, 1.0F, still.getU0(), still.getV1()),
                    new FluidVertex(1.0F, southEast, 1.0F, still.getU1(), still.getV1()),
                    new FluidVertex(1.0F, northEast, 0.0F, still.getU1(), still.getV0()),
                    tint,
                    Float.NaN,
                    Float.NaN,
                    Float.NaN,
                    properties
                );
            }

            if (!fluid.isSame(belowFluid.getType()) && !belowState.isSolidRender()) {
                addFluidQuad(
                    vertices,
                    materialData,
                    sectionOrigin,
                    blockPos,
                    new FluidVertex(0.0F, 0.0F, 0.0F, still.getU0(), still.getV0()),
                    new FluidVertex(1.0F, 0.0F, 0.0F, still.getU1(), still.getV0()),
                    new FluidVertex(1.0F, 0.0F, 1.0F, still.getU1(), still.getV1()),
                    new FluidVertex(0.0F, 0.0F, 1.0F, still.getU0(), still.getV1()),
                    tint,
                    0.0F,
                    -1.0F,
                    0.0F,
                    properties
                );
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = blockPos.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (!FluidRenderer.shouldRenderFace(fluidState, blockState, direction, neighborState)) {
                    continue;
                }
                float h0;
                float h1;
                FluidVertex v0;
                FluidVertex v1;
                FluidVertex v2;
                FluidVertex v3;
                switch (direction) {
                    case NORTH -> {
                        h0 = northWest;
                        h1 = northEast;
                        v0 = new FluidVertex(0.0F, h0, 0.001F, flowing.getU(0.0F), flowing.getV((1.0F - h0) * 0.5F));
                        v1 = new FluidVertex(1.0F, h1, 0.001F, flowing.getU(0.5F), flowing.getV((1.0F - h1) * 0.5F));
                        v2 = new FluidVertex(1.0F, 0.0F, 0.001F, flowing.getU(0.5F), flowing.getV(0.5F));
                        v3 = new FluidVertex(0.0F, 0.0F, 0.001F, flowing.getU(0.0F), flowing.getV(0.5F));
                    }
                    case SOUTH -> {
                        h0 = southEast;
                        h1 = southWest;
                        v0 = new FluidVertex(1.0F, h0, 0.999F, flowing.getU(0.0F), flowing.getV((1.0F - h0) * 0.5F));
                        v1 = new FluidVertex(0.0F, h1, 0.999F, flowing.getU(0.5F), flowing.getV((1.0F - h1) * 0.5F));
                        v2 = new FluidVertex(0.0F, 0.0F, 0.999F, flowing.getU(0.5F), flowing.getV(0.5F));
                        v3 = new FluidVertex(1.0F, 0.0F, 0.999F, flowing.getU(0.0F), flowing.getV(0.5F));
                    }
                    case WEST -> {
                        h0 = southWest;
                        h1 = northWest;
                        v0 = new FluidVertex(0.001F, h0, 1.0F, flowing.getU(0.0F), flowing.getV((1.0F - h0) * 0.5F));
                        v1 = new FluidVertex(0.001F, h1, 0.0F, flowing.getU(0.5F), flowing.getV((1.0F - h1) * 0.5F));
                        v2 = new FluidVertex(0.001F, 0.0F, 0.0F, flowing.getU(0.5F), flowing.getV(0.5F));
                        v3 = new FluidVertex(0.001F, 0.0F, 1.0F, flowing.getU(0.0F), flowing.getV(0.5F));
                    }
                    case EAST -> {
                        h0 = northEast;
                        h1 = southEast;
                        v0 = new FluidVertex(0.999F, h0, 0.0F, flowing.getU(0.0F), flowing.getV((1.0F - h0) * 0.5F));
                        v1 = new FluidVertex(0.999F, h1, 1.0F, flowing.getU(0.5F), flowing.getV((1.0F - h1) * 0.5F));
                        v2 = new FluidVertex(0.999F, 0.0F, 1.0F, flowing.getU(0.5F), flowing.getV(0.5F));
                        v3 = new FluidVertex(0.999F, 0.0F, 0.0F, flowing.getU(0.0F), flowing.getV(0.5F));
                    }
                    default -> throw new AssertionError(direction);
                }
                addFluidQuad(vertices, materialData, sectionOrigin, blockPos, v0, v1, v2, v3, tint,
                    direction.getStepX(), direction.getStepY(), direction.getStepZ(), properties);
            }
        }

        private static MaterialProperties fluidMaterialProperties(Fluid fluid) {
            var key = BuiltInRegistries.FLUID.getKey(fluid);
            String name = key == null ? "" : key.getPath();
            FluidGeometryCapture.Surface surface = FluidGeometryCapture.surface(name);
            return new MaterialProperties(
                surface.roughness(), surface.metallic(), surface.emission(), surface.reflectivity(),
                new OpticalProperties(surface.ior(), surface.absorptionR(), surface.absorptionG(),
                    surface.absorptionB(), surface.opacity(), 0.0F));
        }

        private static float fluidHeight(ClientLevel level, Fluid fluid, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            FluidState fluidState = state.getFluidState();
            if (fluid.isSame(fluidState.getType())) {
                return fluid.isSame(level.getFluidState(pos.above()).getType()) ? 1.0F : fluidState.getOwnHeight();
            }
            return state.isSolid() ? -1.0F : 0.0F;
        }

        private static float averageFluidHeight(ClientLevel level, Fluid fluid, float self, float first, float second, BlockPos corner) {
            if (first >= 1.0F || second >= 1.0F) {
                return 1.0F;
            }
            float cornerHeight = fluidHeight(level, fluid, corner);
            if (cornerHeight >= 1.0F) {
                return 1.0F;
            }
            float total = 0.0F;
            float count = 0.0F;
            if (cornerHeight >= 0.0F) {
                total += cornerHeight;
                count += 1.0F;
            }
            if (self >= 0.0F) {
                total += self;
                count += 1.0F;
            }
            if (first >= 0.0F) {
                total += first;
                count += 1.0F;
            }
            if (second >= 0.0F) {
                total += second;
                count += 1.0F;
            }
            return count == 0.0F ? 0.0F : total / count;
        }

        private static void addFluidQuad(
            FloatAccumulator vertices,
            FloatAccumulator materialData,
            BlockPos sectionOrigin,
            BlockPos blockPos,
            FluidVertex v0,
            FluidVertex v1,
            FluidVertex v2,
            FluidVertex v3,
            int color,
            float normalX,
            float normalY,
            float normalZ,
            MaterialProperties properties
        ) {
            addFluidTriangle(vertices, materialData, sectionOrigin, blockPos, v0, v1, v2, color, normalX, normalY, normalZ, properties);
            addFluidTriangle(vertices, materialData, sectionOrigin, blockPos, v0, v2, v3, color, normalX, normalY, normalZ, properties);
        }

        private static void addFluidTriangle(
            FloatAccumulator vertices,
            FloatAccumulator materialData,
            BlockPos sectionOrigin,
            BlockPos blockPos,
            FluidVertex v0,
            FluidVertex v1,
            FluidVertex v2,
            int color,
            float normalX,
            float normalY,
            float normalZ,
            MaterialProperties properties
        ) {
            if (Float.isNaN(normalX)) {
                float ax = v1.x() - v0.x();
                float ay = v1.y() - v0.y();
                float az = v1.z() - v0.z();
                float bx = v2.x() - v0.x();
                float by = v2.y() - v0.y();
                float bz = v2.z() - v0.z();
                normalX = ay * bz - az * by;
                normalY = az * bx - ax * bz;
                normalZ = ax * by - ay * bx;
                float length = (float)Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                if (length > 1.0E-5F) {
                    normalX /= length;
                    normalY /= length;
                    normalZ /= length;
                }
            }
            addVertex(vertices, sectionOrigin, blockPos, v0.x(), v0.y(), v0.z());
            addVertex(vertices, sectionOrigin, blockPos, v1.x(), v1.y(), v1.z());
            addVertex(vertices, sectionOrigin, blockPos, v2.x(), v2.y(), v2.z());
            addFluidMaterial(materialData, color, properties, normalX, normalY, normalZ,
                v0.u(), v0.v(), v1.u(), v1.v(), v2.u(), v2.v());
        }

        private static void addFluidMaterial(
            FloatAccumulator materialData,
            int color,
            MaterialProperties properties,
            float normalX,
            float normalY,
            float normalZ,
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2
        ) {
            materialData.add(((color >> 16) & 0xff) / 255.0F);
            materialData.add(((color >> 8) & 0xff) / 255.0F);
            materialData.add((color & 0xff) / 255.0F);
            // Tint alpha carries the fluid opacity through the existing material ABI.
            materialData.add(properties.optical().opacity());
            materialData.add(normalX);
            materialData.add(normalY);
            materialData.add(normalZ);
            materialData.add(0.0F);
            materialData.add(u0);
            materialData.add(v0);
            materialData.add(u1);
            materialData.add(v1);
            materialData.add(u2);
            materialData.add(v2);
            materialData.add(0.0F);
            // UV2.w remains the texture-kind flag; lighting.y stores optical dispersion.
            materialData.add(1.0F);
            materialData.add(properties.optical().dispersionScale());
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(properties.roughness());
            materialData.add(properties.metallic());
            materialData.add(properties.emission());
            materialData.add(1.0F + properties.reflectivity());
            materialData.add(properties.optical().absorptionR());
            materialData.add(properties.optical().absorptionG());
            materialData.add(properties.optical().absorptionB());
            materialData.add(properties.optical().ior());
        }

        private static void addQuads(
            FloatAccumulator vertices,
            FloatAccumulator materialData,
            BlockPos sectionOrigin,
            BlockPos blockPos,
            BlockState state,
            List<BakedQuad> quads,
            RayTracingPbrMaterials pbrMaterials,
            BlockColors blockColors,
            ClientLevel level
        ) {
            String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            for (BakedQuad quad : quads) {
                // Resource packs can incorrectly classify glass as cutout/solid. The optical
                // material must remain transmissive even when the render-layer metadata lies;
                // otherwise the ray tracer paints the pane but never carries its RGB filter.
                boolean knownTransmissive = blockName.contains("glass") || blockName.contains("water");
                boolean translucent = quad.materialInfo().layer().translucent() || knownTransmissive;
                // Treat every non-translucent quad as alpha-testable. Some Minecraft foliage
                // materials are exposed as SOLID by the model layer even though their atlas
                // sprite contains transparent pixels; the shader still applies the 0.5 cutoff.
                boolean alphaTest = !translucent;
                int tint = 0xFFFFFF;
                MaterialProperties properties = materialProperties(state, quad);
                int tintIndex = quad.materialInfo().tintIndex();
                if (tintIndex >= 0) {
                    BlockTintSource tintSource = blockColors.getTintSource(state, tintIndex);
                    if (tintSource != null) {
                        tint = tintSource.colorInWorld(state, level, blockPos) & 0xFFFFFF;
                    }
                }
                Vector3fc p0 = quad.position0();
                Vector3fc p1 = quad.position1();
                Vector3fc p2 = quad.position2();
                Vector3fc p3 = quad.position3();
                // FaceBakery normally emits a winding that agrees with direction(), but a
                // resource-pack face rotation can leave those two contracts opposite. The BLAS
                // is intentionally two-sided, while the emissive LightTree derives its normal
                // from the triangle cross product and sampleAreaLight rejects a back-facing
                // emitter. Keep the triangle winding, material normal, UVs and tangent frame in
                // the same orientation so a visible lamp cannot become a zero-power light only
                // because the model was rotated.
                addOrientedTriangle(vertices, materialData, sectionOrigin, blockPos,
                    p0, p1, p2, quad.direction(), tint, properties, translucent, alphaTest,
                    quad, pbrMaterials, 0, 1, 2);
                addOrientedTriangle(vertices, materialData, sectionOrigin, blockPos,
                    p0, p2, p3, quad.direction(), tint, properties, translucent, alphaTest,
                    quad, pbrMaterials, 0, 2, 3);
            }
        }

        private static void addOrientedTriangle(
            FloatAccumulator vertices,
            FloatAccumulator materialData,
            BlockPos sectionOrigin,
            BlockPos blockPos,
            Vector3fc p0,
            Vector3fc p1,
            Vector3fc p2,
            Direction direction,
            int tint,
            MaterialProperties properties,
            boolean translucent,
            boolean alphaTest,
            BakedQuad quad,
            RayTracingPbrMaterials pbrMaterials,
            int uv0Index,
            int uv1Index,
            int uv2Index
        ) {
            if (direction != null && hasOppositeWinding(p0, p1, p2, direction)) {
                Vector3fc swappedPosition = p1;
                p1 = p2;
                p2 = swappedPosition;
                int swappedIndex = uv1Index;
                uv1Index = uv2Index;
                uv2Index = swappedIndex;
            }
            addTriangle(vertices, sectionOrigin, blockPos, p0, p1, p2);
            addMaterial(materialData, tint, properties, translucent, alphaTest, direction, quad,
                pbrMaterials, uv0Index, uv1Index, uv2Index);
        }

        private static boolean hasOppositeWinding(Vector3fc p0, Vector3fc p1, Vector3fc p2,
                                                  Direction direction) {
            float ax = p1.x() - p0.x();
            float ay = p1.y() - p0.y();
            float az = p1.z() - p0.z();
            float bx = p2.x() - p0.x();
            float by = p2.y() - p0.y();
            float bz = p2.z() - p0.z();
            float crossX = ay * bz - az * by;
            float crossY = az * bx - ax * bz;
            float crossZ = ax * by - ay * bx;
            float areaSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
            if (!(areaSquared > 1.0E-10F) || !Float.isFinite(areaSquared)) {
                return false;
            }
            float directionDot = crossX * direction.getStepX()
                + crossY * direction.getStepY()
                + crossZ * direction.getStepZ();
            return directionDot < 0.0F;
        }

        private static void addTriangle(
            FloatAccumulator vertices,
            BlockPos sectionOrigin,
            BlockPos blockPos,
            Vector3fc p0,
            Vector3fc p1,
            Vector3fc p2
        ) {
            addVertex(vertices, sectionOrigin, blockPos, p0);
            addVertex(vertices, sectionOrigin, blockPos, p1);
            addVertex(vertices, sectionOrigin, blockPos, p2);
        }

        private record MaterialProperties(float roughness, float metallic, float emission, float reflectivity, OpticalProperties optical) {
        }

        private record OpticalProperties(float ior, float absorptionR, float absorptionG, float absorptionB,
                                         float opacity, float dispersionScale) {
        }

        private static MaterialProperties materialProperties(BlockState state, BakedQuad quad) {
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            boolean metal = name.contains("iron")
                || name.contains("gold")
                || name.contains("copper")
                || name.contains("netherite")
                || name.contains("anvil");
            boolean translucent = quad.materialInfo().layer().translucent()
                || name.contains("glass") || name.contains("water");
            // Prime treats vanilla glass and water as exact smooth dielectric interfaces. A
            // non-zero GGX lobe turns colored transmission into a noisy optional branch.
            float roughness = translucent ? 0.0F : (metal ? 0.38F : 0.88F);
            float metallic = metal ? 0.72F : 0.0F;
            // BakedQuad.materialInfo().lightEmission() only carries the model JSON "light_emission"
            // field, which vanilla models (lantern, glowstone, sea_lantern, torch, ...) never author.
            // The block-state light level is the authoritative source; keep the model value as an
            // override so resource-pack models that self-illuminate still contribute.
            int blockLightLevel = Math.max(state.getLightEmission(),
                quad.materialInfo().lightEmission());
            // Block light feeds both the visible glow and the light-tree emitters. The scalar map
            // alone is far below the sun's effective irradiance scale, so an indoor scene renders
            // nearly black next to clipped lamps; emissionScale recalibrates it.
            float emission = RayTracingEmission.fromMinecraftLevel(blockLightLevel)
                * RayTracingClientConfig.INSTANCE.emissionScale.get().floatValue();
            float reflectivity = metal ? 0.72F : 0.04F;
            return new MaterialProperties(roughness, metallic, emission, reflectivity, opticalProperties(name, translucent));
        }

        private static OpticalProperties opticalProperties(String name, boolean transmissive) {
            if (name.contains("water")) {
                return new OpticalProperties(1.333F, 0.015F, 0.045F, 0.09F, 1.0F, 0.0F);
            }
            if (name.contains("glass")) {
                float absorption = name.contains("stained_glass") ? 0.08F : 0.015F;
                return new OpticalProperties(1.5F, absorption, absorption, absorption, 1.0F, 0.0F);
            }
            return transmissive
                ? new OpticalProperties(1.5F, 0.02F, 0.02F, 0.02F, 1.0F, 0.0F)
                : new OpticalProperties(1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F);
        }

        private static void addMaterial(
            FloatAccumulator materialData,
            int color,
            MaterialProperties properties,
            boolean translucent,
            boolean alphaTest,
            Direction direction,
            BakedQuad quad,
            RayTracingPbrMaterials pbrMaterials,
            int uv0Index,
            int uv1Index,
            int uv2Index
        ) {
            RayTracingPbrMaterials.Sample pbr = pbrMaterials.sample(
                quad.materialInfo().sprite(),
                quad.packedUV(uv0Index),
                quad.packedUV(uv1Index),
                quad.packedUV(uv2Index)
            );
            // Keep the geometric face normal in the material; GPU PBR sampling applies the normal map per hit.
            float normalX = direction.getStepX();
            float normalY = direction.getStepY();
            float normalZ = direction.getStepZ();
            RayTracingTangent.Frame tangent = RayTracingTangent.fromBakedQuad(
                quad, uv0Index, uv1Index, uv2Index, normalX, normalY, normalZ);
            float roughness = pbr.hasSpecular() ? pbr.roughness() : properties.roughness();
            float metallic = pbr.hasSpecular() ? Math.max(properties.metallic(), pbr.metallic()) : properties.metallic();
            float reflectivity = pbr.hasSpecular() ? pbr.reflectivity() : properties.reflectivity();
            float emission = RayTracingPbrMaterials.resolveLightTreeEmission(
                properties.emission(), pbr.emission(), pbr.hasEmission());
            materialData.add(((color >> 16) & 0xff) / 255.0F);
            materialData.add(((color >> 8) & 0xff) / 255.0F);
            materialData.add((color & 0xff) / 255.0F);
            materialData.add(1.0F);
            materialData.add(normalX);
            materialData.add(normalY);
            materialData.add(normalZ);
            materialData.add(0.0F);
            addUv(materialData, quad.packedUV(uv0Index));
            addUv(materialData, quad.packedUV(uv1Index));
            addUv(materialData, quad.packedUV(uv2Index));
            materialData.add(alphaTest ? 1.0F : 0.0F);
            // UV2.w remains the texture-kind flag; lighting.x stores the UV tangent rotation,
            // lighting.y stores optical dispersion, lighting.z stores tangent handedness, and
            // lighting.w remains the PBR map index.
            materialData.add(1.0F);
            materialData.add(tangent.angle());
            materialData.add(properties.optical().dispersionScale());
            materialData.add(tangent.handedness());
            materialData.add((float)pbr.mapIndex());
            materialData.add(roughness);
            materialData.add(metallic);
            // Surface emission is the CPU-decoded LabPBR _s alpha scalar when authored;
            // vanilla block light remains the fallback. Do not put alpha in lighting metadata.
            materialData.add(emission);
            materialData.add((translucent ? 1.0F : 0.0F) + reflectivity);
            materialData.add(properties.optical().absorptionR());
            materialData.add(properties.optical().absorptionG());
            materialData.add(properties.optical().absorptionB());
            materialData.add(properties.optical().ior());
        }

        private static void addMaterial(FloatAccumulator materialData, int color, float normalX, float normalY, float normalZ) {
            materialData.add(((color >> 16) & 0xff) / 255.0F);
            materialData.add(((color >> 8) & 0xff) / 255.0F);
            materialData.add((color & 0xff) / 255.0F);
            materialData.add(1.0F);
            materialData.add(normalX);
            materialData.add(normalY);
            materialData.add(normalZ);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            // Slot 14 is the alpha-test flag and must not be skipped: the fallback material
            // previously emitted 27 floats, which violates the 28-float per-triangle ABI and
            // makes SectionGeometry construction throw whenever the fallback path runs.
            materialData.add(0.0F);
            // UV2.w remains the texture-kind flag; reserved lighting metadata is neutral.
            materialData.add(1.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.88F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(0.04F);
            materialData.add(0.0F);
            materialData.add(0.0F);
            materialData.add(1.0F);
        }

        private static void addUv(FloatAccumulator materialData, long packedUv) {
            materialData.add(UVPair.unpackU(packedUv));
            materialData.add(UVPair.unpackV(packedUv));
        }

        private static void addVertex(FloatAccumulator vertices, BlockPos sectionOrigin, BlockPos blockPos, Vector3fc local) {
            addVertex(vertices, sectionOrigin, blockPos, local.x(), local.y(), local.z());
        }

        private static void addVertex(FloatAccumulator vertices, BlockPos sectionOrigin, BlockPos blockPos, float x, float y, float z) {
            vertices.add((float)(blockPos.getX() + x - sectionOrigin.getX()));
            vertices.add((float)(blockPos.getY() + y - sectionOrigin.getY()));
            vertices.add((float)(blockPos.getZ() + z - sectionOrigin.getZ()));
        }
    }


    private RayTracingScene() {
    }
}
