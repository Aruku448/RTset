package com.rtest.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression contract for native Vulkan ownership and scene-cache teardown. */
public final class VulkanResourceLifecycleTest {
    private VulkanResourceLifecycleTest() {
    }

    public static void main(String[] args) throws IOException {
        if (RayTracingVulkanPass.needsIncrementalDynamicBuild(false, true, true)) {
            throw new AssertionError("pending animation updates must not starve the first TLAS build");
        }
        if (!RayTracingVulkanPass.needsIncrementalDynamicBuild(false, false, true)) {
            throw new AssertionError("an unbuilt dynamic BLAS is required by the first TLAS build");
        }
        if (!RayTracingVulkanPass.needsIncrementalDynamicBuild(true, true, true)) {
            throw new AssertionError("pending dynamic updates must resume after first presentation");
        }
        if (RayTracingVulkanPass.shouldAdoptDynamicFrame(false, false, false)) {
            throw new AssertionError("dynamic snapshots must stay frozen while the first TLAS is incomplete");
        }
        if (!RayTracingVulkanPass.shouldAdoptDynamicFrame(false, true, false)) {
            throw new AssertionError("live dynamic snapshots must resume after the TLAS is available");
        }
        if (RayTracingVulkanPass.shouldAdoptDynamicFrame(true, true, false)) {
            throw new AssertionError("offline rendering must keep its frozen dynamic snapshot");
        }
        if (VulkanAccelerationResources.scratchBufferSize(1024L, 256L) != 1279L) {
            throw new AssertionError("scratch allocation must reserve alignment headroom");
        }
        ByteBuffer projection = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
        RayTracingVulkanPass.writeTerrainTraversalProjection(projection,
            new com.rtest.client.fsr.RtestFsrCamera(
                1.0F, 1.0F, 10.0, 20.0, 30.0,
                0.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 0.0F,
                0.0F, 1.0F, 0.0F));
        if (projection.getFloat(0) != 1.0F || projection.getFloat(4) != 0.0F
                || projection.getFloat(8) != 0.0F || projection.getFloat(12) != 0.0F
                || projection.getFloat(16) != 0.0F || projection.getFloat(20) != 1.0F
                || projection.getFloat(24) != 0.0F || projection.getFloat(28) != 0.0F
                || projection.getFloat(44) != 1.0F || projection.getFloat(48) != -10.0F
                || projection.getFloat(52) != -20.0F || projection.getFloat(56) != 0.05F
                || projection.getFloat(60) != -30.0F) {
            throw new AssertionError("terrain traversal projection must be written column-major");
        }
        int reservedTlasCapacity = RayTracingVulkanPass.tlasInstanceCapacity(4097, 8192);
        if (reservedTlasCapacity < 4097 || reservedTlasCapacity > 8192
                || RayTracingVulkanPass.tlasInstanceCapacity(100, 110) != 110) {
            throw new AssertionError("TLAS capacity reservation must fit the live count and device limit");
        }
        long highDeviceAddress = Long.parseUnsignedLong("ffff8001924458d8", 16);
        long alignedHighAddress = VulkanAccelerationResources.alignDeviceAddress(highDeviceAddress, 256L);
        if (Long.toUnsignedString(alignedHighAddress, 16).equals("ffff800192445900") == false) {
            throw new AssertionError("high-bit scratch device addresses must align as unsigned values");
        }
        VulkanAccelerationResources.validateBuildArguments(
            false, 0x1004L, 36L, 1,
            0x2000L, 0x2100L, 0x11ffL, 1024L, 256L, 2048L, 1L, false);
        boolean rejectedShortInput = false;
        try {
            VulkanAccelerationResources.validateBuildArguments(
                false, 0x1004L, 35L, 1,
                0x2000L, 0x2100L, 0x11ffL, 1024L, 256L, 2048L, 1L, false);
        } catch (IllegalStateException expected) {
            rejectedShortInput = true;
        }
        if (!rejectedShortInput) {
            throw new AssertionError("AS validation must reject a vertex range shorter than one triangle");
        }
        boolean rejectedUnalignedInstances = false;
        try {
            VulkanAccelerationResources.validateBuildArguments(
                true, 0x1008L, 64L, 1,
                0x2000L, 0x2100L, 0x11ffL, 1024L, 256L, 2048L, 1L, false);
        } catch (IllegalStateException expected) {
            rejectedUnalignedInstances = true;
        }
        if (!rejectedUnalignedInstances) {
            throw new AssertionError("AS validation must enforce 16-byte TLAS instance alignment");
        }
        String pass = source("src/main/java/com/rtest/client/RayTracingVulkanPass.java");
        String dynamicInstances = source("src/main/java/com/rtest/client/RayTracingDynamicInstances.java");
        String resourceMatch = pass.substring(pass.indexOf("boolean matches("), pass.indexOf("boolean usesGeometry("));
        require(pass, "encoder.destroy();");
        require(pass, "private boolean closed;");
        require(dynamicInstances, "entries.remove(key);");
        require(pass, "blasCache.commit(nextBlas);");
        require(pass, "reuseTopLevel = requiredInstanceCount <= this.topLevel.primitiveCount;");
        require(pass, "primitiveCount(topLevel.primitiveCount)");
        require(pass, "this.blasCache.retireCompleted();");
        require(pass, "UNTRACED_INSTANCE_MASK");
        require(pass, "Math.toIntExact(dynamicMaterialBase(geometry))");
        require(pass, "blasCache.abort(nextBlas)");
        require(pass, "RtResourceRollback.attempt(throwable, nextPbr::close)");
        require(pass, "rollback.restore(throwable);");
        require(pass, "encoder = new VulkanCommandEncoder(device);");
        require(pass, "private GpuFence pendingFrameFence;");
        require(dynamicInstances, "retired.add(previous);");
        require(dynamicInstances, "void retireCompleted()");
        require(pass, "if (completed) {");
        require(pass, "waitForPreviousFrame(timing);");
        require(pass, "VulkanCommandEncoder frameEncoder = this.device.createCommandEncoder();");
        require(pass, "frameEncoder.execute(holder.commandBuffer);");
        require(pass, "commandBuffer = frameEncoder.allocateAndBeginTransientCommandBuffer();");
        require(pass, "private long scratchDeviceAddress()");
        require(pass, "return this.scratchBuffer.alignedDeviceAddress(alignment);");
        require(pass, "scratchBufferSize(scratchSize, scratchAlignment)");
        require(pass, "validateBuildArguments(");
        require(pass, "topLevelBuildInfo(");
        require(pass, "validateUniqueBlasKeys(");
        require(pass, "private record SectionKey(int x, int y, int z, int lodLevel)");
        require(pass, "section.terrainNodeKey().level()");
        require(pass, "boolean contiguousChildren = children.size() == 8;");
        require(pass, "buildTerrainTraversalMetadata(nextGeometry)");
        require(pass, "updateTerrainTraversalBufferDescriptors(nextTerrainNodeMetadata, nextTerrainBlasAddresses)");
        reject(resourceMatch, "this.geometry == geometry");
        require(pass, "Attempted to build a closed acceleration structure resource");
        require(dynamicInstances, "DynamicCachedBlas replace(VulkanDevice device");
        require(dynamicInstances, "dynamicBlasCache.replace(device, key, dynamicMesh)");
        // Multi-frame animation throttling was reverted; changed geometry updates every frame.
        require(dynamicInstances, "DYNAMIC_BLAS_UPDATE_INTERVAL_FRAMES = 1");
        require(dynamicInstances, "shouldReplaceDynamicBlas(cached, dynamicFrameNumber)");
        reject(pass, "cached.updateVertices(mesh.vertices())");
        String dispatch = pass.substring(pass.indexOf("int dispatch("), pass.indexOf("/** Replays the last completed FSR image"));
        reject(dispatch, "frameEncoder.submit();");
        require(dispatch, "GpuFence fence = frameEncoder.createFence();");
        require(dispatch, "this.pendingFrameFence = fence;");

        String resources = source("src/main/java/com/rtest/client/VulkanAccelerationResources.java");
        require(resources, "alignDeviceAddress(baseAddress, alignment)");
        require(resources, "static void validateBuildArguments(");
        require(resources, "static long scratchBufferSize(");
        require(resources, "final class AccelerationStructure implements AutoCloseable");
        require(resources, "final class NativeBuffer implements AutoCloseable");

        String fsr3 = source("src/main/java/com/rtest/client/fsr/RtestFsr3.java");
        require(fsr3, "this.nrd.cancel(this.nrdToken);");

        String nrd = source("src/main/java/com/rtest/client/fsr/NrdDenoiser.java");
        require(nrd, "public void cancel(FrameToken token)");

        String skybox = source("src/main/java/com/rtest/client/RayTracingSkybox.java");
        require(skybox, "VulkanCommandEncoder encoder = null;");
        require(skybox, "encoder = new VulkanCommandEncoder(device);");
        require(skybox, "encoder.destroy();");
        require(skybox, "cdfGeometry.writeTables(mapped.buffer());");
        require(skybox, "importance.close();");
        require(skybox, "finally { cdf.close(); }");
        require(pass, "binding(38)");
        require(pass, "dstBinding(38)");
        require(pass, "dstBinding(39)");
        require(pass, "dstBinding(40)");
        require(pass, "int[] sbtGroupOrder = {0, 1, 2, 5, 3, 4, 6};");
        String cdf = source("src/main/java/com/rtest/client/SkyCdfAcceleration.java");
        require(cdf, "encoder.destroy();");
        require(cdf, "scratch.close();");
        require(cdf, "if (!success) owner.close();");
        require(cdf, "VulkanAccelerationResources.validateBuildArguments(");
        int cdfEnd = cdf.indexOf("VK10.vkEndCommandBuffer(command)");
        int cdfExecute = cdf.indexOf("encoder.execute(command)");
        int cdfFence = cdf.indexOf("encoder.createFence()");
        int cdfSubmit = cdf.indexOf("encoder.submit()");
        if (!(cdfEnd >= 0 && cdfExecute > cdfEnd && cdfFence > cdfExecute && cdfSubmit > cdfFence)) {
            throw new AssertionError("Sky CDF build command must be enqueued before its completion fence and submit; otherwise TLAS is unbuilt");
        }

        String probe = source("src/main/java/com/rtest/client/RayTracingProbe.java");
        require(probe, "LevelEvent.Unload");
        require(probe, "stopSmokeTestResources();");
        require(probe, "Close NativeImages here, on the client/render thread");
        reject(probe, "whenComplete((ignored, failure) -> closePbrMaterials(materials))");
        require(probe, "capturedWindowOrigins");
        require(probe, "boolean relevant = invalidateDirtySection(section);");
        require(probe, "if (relevant) {");

        String cache = source("src/main/java/com/rtest/client/CompiledSectionMeshCache.java");
        require(cache, "MAX_BYTES");
        require(cache, "cachedBytes");
        require(cache, "cachedBytes = 0L");

        String fsr = source("src/main/java/com/rtest/client/fsr/RtestFsr3Upscaler.java");
        require(fsr, "Pass displayPass = null;");
        require(fsr, "buffer.destroy();");

        String context = source("src/main/java/com/rtest/client/fsr/RtestVulkanContext.java");
        require(context, "Vma.vmaDestroyImage(this.device.vma(), image, allocationPointer.get(0));");
        require(context, "Vma.vmaDestroyBuffer(this.device.vma(), handle, allocation);");

        System.out.println("Vulkan resource lifecycle contract passed");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Vulkan lifecycle contract is missing: " + fragment);
        }
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) {
            throw new AssertionError("Vulkan lifecycle contract must not contain: " + fragment);
        }
    }
}
