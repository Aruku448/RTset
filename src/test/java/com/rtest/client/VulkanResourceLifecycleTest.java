package com.rtest.client;

import java.io.IOException;
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
        String pass = source("src/main/java/com/rtest/client/RayTracingVulkanPass.java");
        require(pass, "encoder.destroy();");
        require(pass, "private boolean closed;");
        require(pass, "entries.remove(key);");
        require(pass, "blasCache.commit(nextBlas);");
        require(pass, "blasCache.abort(nextBlas);");
        require(pass, "nextPbr.close();");
        require(pass, "encoder = new VulkanCommandEncoder(device);");
        require(pass, "private GpuFence pendingFrameFence;");
        require(pass, "waitForPreviousFrame(timing);");
        require(pass, "VulkanCommandEncoder frameEncoder = this.device.createCommandEncoder();");
        require(pass, "frameEncoder.execute(holder.commandBuffer);");
        require(pass, "commandBuffer = frameEncoder.allocateAndBeginTransientCommandBuffer();");
        String dispatch = pass.substring(pass.indexOf("int dispatch("), pass.indexOf("/** Replays the last completed FSR image"));
        reject(dispatch, "frameEncoder.submit();");
        require(dispatch, "GpuFence fence = frameEncoder.createFence();");
        require(dispatch, "this.pendingFrameFence = fence;");

        String fsr3 = source("src/main/java/com/rtest/client/fsr/RtestFsr3.java");
        require(fsr3, "this.nrd.cancel(this.nrdToken);");
        require(fsr3, "this.sundial.cancel(this.sundialToken);");

        String nrd = source("src/main/java/com/rtest/client/fsr/NrdDenoiser.java");
        require(nrd, "public void cancel(FrameToken token)");

        String skybox = source("src/main/java/com/rtest/client/RayTracingSkybox.java");
        require(skybox, "VulkanCommandEncoder encoder = null;");
        require(skybox, "encoder = new VulkanCommandEncoder(device);");
        require(skybox, "encoder.destroy();");

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
