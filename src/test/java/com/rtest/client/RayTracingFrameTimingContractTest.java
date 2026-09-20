package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies the low-risk CPU timing contract without requiring a Vulkan device. */
public final class RayTracingFrameTimingContractTest {
    private RayTracingFrameTimingContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String smoke = source("src/main/java/com/rtest/client/RayTracingSmokeTest.java");
        String pass = source("src/main/java/com/rtest/client/RayTracingVulkanPass.java");
        String timing = source("src/main/java/com/rtest/client/RayTracingFrameTiming.java");

        require(smoke, "Segment.RESOURCE_MATCH");
        require(smoke, "Segment.RESOURCE_REBUILD");
        require(smoke, "Segment.GEOMETRY_UPDATE");
        require(smoke, "timing.log(LOGGER, \"smoke_run_cpu\"");
        require(pass, "Segment.DYNAMIC_UPDATE");
        require(pass, "Segment.PBR_SYNC");
        require(pass, "Segment.COMMAND_RECORD");
        require(pass, "Segment.FENCE_WAIT_CPU");
        require(pass, "Segment.READBACK");
        require(pass, "The center-pixel readback is diagnostic only");
        require(pass, "timing.log(LOGGER, \"vulkan_dispatch_cpu\"");
        require(pass, "this.atlasSampler == atlasSampler");
        require(pass, "this.targetImageView == imageView");
        require(pass, "this.outputFormat == format");
        require(pass, "this.fsr.renderWidth() == renderWidth");
        require(smoke, "targetView.vkImageView()");
        require(timing, "fence_wait_cpu_us");
        require(pass, "vkCmdWriteTimestamp");
        require(pass, "RTest gpu_timing frame={}");

        System.out.println("Ray-tracing frame timing contract passed");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Frame timing contract is missing: " + fragment);
        }
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) {
            throw new AssertionError("Frame timing contract must not contain: " + fragment);
        }
    }
}
