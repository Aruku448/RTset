package com.rtest.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;

/** Verifies the exact Vulkan structs passed by the real BLAS/TLAS command recorder. */
public final class PlayerBlasCommandTest {
    public static void main(String[] args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (boolean topLevel : new boolean[] {false, true}) {
                var build = RayTracingVulkanPass.AccelerationStructure.buildInfo(stack, 123L, 456L, 768L, 144, topLevel, false).get(0);
                var update = RayTracingVulkanPass.AccelerationStructure.buildInfo(stack, 123L, 456L, 768L, 144, topLevel, true).get(0);
                if (build.srcAccelerationStructure() != 0 || update.srcAccelerationStructure() != 123L
                    || update.dstAccelerationStructure() != 123L) {
                    throw new AssertionError("In-place UPDATE must name the existing source AS");
                }
                if (update.mode() != KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR
                    || (build.flags() & KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) == 0
                    || build.flags() != update.flags()) {
                    throw new AssertionError("BUILD/UPDATE flags must agree");
                }
                if (!topLevel) {
                    var triangles = update.pGeometries().get(0).geometry().triangles();
                    if (triangles.vertexData().deviceAddress() != 456L || triangles.vertexStride() != 12
                        || triangles.maxVertex() != 431) throw new AssertionError("Animation vertex input mismatch");
                    if (update.pGeometries().get(0).flags() != 0) throw new AssertionError("AnyHit must remain enabled for cutout");
                }
            }
        }
        System.out.println("Player BLAS/TLAS command contract passed");
    }
}
