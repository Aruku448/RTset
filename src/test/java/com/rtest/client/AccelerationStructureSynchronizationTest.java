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
        System.out.println("Actual AS synchronization access classes passed (not GPU runtime validation)");
    }
}
