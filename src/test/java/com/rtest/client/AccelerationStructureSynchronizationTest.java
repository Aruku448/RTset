package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Checks actual command-recorder barriers against Vulkan resource access classes. */
public final class AccelerationStructureSynchronizationTest {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        var barriers = Pattern.compile("(?<![A-Za-z])barrier\\(commandBuffer, stack,([^;]+)\\);", Pattern.DOTALL).matcher(source);
        int traceReads = 0, computeInputs = 0, hostInputs = 0;
        while (barriers.find()) {
            String[] fields = barriers.group(1).split(",");
            if (fields.length != 4) throw new AssertionError("Unrecognized recorder barrier");
            if (fields[0].contains("ACCELERATION_STRUCTURE_BUILD")
                    && fields[1].contains("ACCELERATION_STRUCTURE_WRITE")
                    && fields[2].contains("RAY_TRACING_SHADER")) {
                traceReads++;
                if (!fields[3].contains("ACCELERATION_STRUCTURE_READ"))
                    throw new AssertionError("AS build -> trace lacks AS_READ; SHADER_READ does not cover traversal");
            }
            if (fields[0].contains("HOST_BIT") && fields[1].contains("HOST_WRITE")
                    && fields[2].contains("ACCELERATION_STRUCTURE_BUILD")) {
                hostInputs++;
                if (!fields[3].contains("SHADER_READ"))
                    throw new AssertionError("Host -> AS build input lacks SHADER_READ");
            }
            if (fields[0].contains("COMPUTE_SHADER") && fields[1].contains("SHADER_WRITE")
                    && fields[2].contains("ACCELERATION_STRUCTURE_BUILD")) {
                computeInputs++;
                if (!fields[3].contains("SHADER_READ"))
                    throw new AssertionError("Compute -> AS build instance input lacks SHADER_READ");
            }
        }
        if (traceReads != 3 || computeInputs != 1 || hostInputs != 4)
            throw new AssertionError("Missing actual build/traversal or terrain input path");
        String allocationSource = Files.readString(Path.of("src/main/java/com/rtest/client/VulkanAccelerationResources.java")).replace("\r\n", "\n");
        if (!Pattern.compile("BUILD_INPUT_READ_ONLY_BIT_KHR\\) != 0\\s*\\? 16L : 1L", Pattern.DOTALL)
                .matcher(allocationSource).find()
                || !allocationSource.contains("vmaCreateBufferWithAlignment(device.vma(), bufferInfo, allocationInfo,\n                    alignment, bufferHandle")
                || !allocationSource.contains("return createAligned(device, size, usage, hostVisible, alignment);")) {
            throw new AssertionError("AS input allocation must explicitly guarantee 16-byte alignment");
        }
        if (!Pattern.compile("shaderBindingTable = NativeBuffer.createAligned\\(.*?true, baseAlignment", Pattern.DOTALL)
                .matcher(source).find() || !source.contains("sbtAddress & (baseAlignment - 1L)")) {
            throw new AssertionError("SBT allocation must guarantee and validate shaderGroupBaseAlignment");
        }
        int tlasRecorder = source.indexOf("private VkAccelerationStructureBuildGeometryInfoKHR.Buffer topLevelBuildInfo(");
        int tlasRecorderEnd = source.indexOf("private void buildAccelerationStructuresIncrementally", tlasRecorder);
        String tlasSource = source.substring(tlasRecorder, tlasRecorderEnd);
        if (!tlasSource.contains("update = update && !RayTracingClientConfig.INSTANCE.forceTlasBuild.get();")
                || !tlasSource.contains("return this.topLevel.buildInfo(stack, inputAddress, scratchAddress, update);")) {
            throw new AssertionError("Diagnostic full BUILD must govern the shared TLAS recorder");
        }
        System.out.println("AS synchronization, input/SBT allocation and TLAS diagnostic contracts passed (not GPU runtime validation)");
    }
}
