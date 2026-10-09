package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** CPU hot-path integration contracts. GPU resources/retirement are covered by existing tests. */
public final class RayTracingRuntimeCleanupTest {
    private RayTracingRuntimeCleanupTest() { }

    public static void main(String[] args) throws Exception {
        com.rtest.client.fsr.NrdImageListTest.main(args);
        String pass = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java")).replace("\r\n", "\n");
        String dynamic = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingDynamicInstances.java")).replace("\r\n", "\n");
        if (count(pass, "RayTracingSupport.queryLimits(") != 1 || dynamic.contains("RayTracingSupport.queryLimits(")) {
            throw new AssertionError("device limits must be queried once during pass creation, not every update");
        }
        require(pass, "private final RayTracingSupport.Limits accelerationLimits;");
        require(pass, "this.accelerationLimits = accelerationLimits;");
        require(pass, "this.device,\n                    this.accelerationLimits,");
        require(dynamic, "this.scratchAlignment = Math.max(1L, accelerationLimits.minScratchAlignment());");
        if (count(dynamic, "java.util.Arrays.equals(cached.uploadedVertices, mesh.vertices())") != 2) {
            throw new AssertionError("each mesh family must compare vertex data only once");
        }
        if (count(dynamic, "else if (verticesChanged)") != 2
                || count(dynamic, "else if (verticesChanged && shouldReplaceDynamicBlas") != 2) {
            throw new AssertionError("replacement/defer branches must reuse the comparison");
        }
        System.out.println("CPU runtime cleanup integration contracts passed (not frame-time measurements)");
    }

    private static int count(String source, String token) {
        return (source.length() - source.replace(token, "").length()) / token.length();
    }

    private static void require(String source, String token) {
        if (!source.contains(token)) throw new AssertionError("missing CPU cleanup contract: " + token);
    }
}
