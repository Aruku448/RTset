package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Contracts for block-entity ownership and dynamic RT admission. */
public final class BlockEntityRasterFallbackContractTest {
    public static void main(String[] args) throws Exception {
        assertIdentityFilter();
        assertSeamRegistered();
        System.out.println("Dynamic block-entity RT contracts passed");
    }

    /**
     * The RT pass publishes represented identities by high-bit namespace. The filter must accept every
     * block-entity identity and reject every signed 32-bit entity id, or the fallback would either drop
     * a visible block entity or draw an RT-owned one twice.
     */
    private static void assertIdentityFilter() {
        var dimension = net.minecraft.resources.Identifier.withDefaultNamespace("overworld");
        var type = net.minecraft.resources.Identifier.withDefaultNamespace("chest");
        for (int x = -4; x <= 4; x++) {
            for (int z = 0; z < 4; z++) {
                long identity = BlockEntityModelGeometryAdapter.stableIdentity(
                    dimension, type, new net.minecraft.core.BlockPos(x, 64, z));
                if ((identity >>> 48) != 0x8001L) {
                    throw new AssertionError("Block-entity identity escaped the represented namespace");
                }
            }
        }
        for (int entityId : new int[] {0, 1, 42, Integer.MAX_VALUE, -1, Integer.MIN_VALUE}) {
            long widened = entityId;
            if ((widened >>> 48) == 0x8001L) {
                throw new AssertionError("Entity id would be mistaken for a represented block entity");
            }
        }
    }

    private static void assertSeamRegistered() throws Exception {
        String mixins = Files.readString(Path.of("src/main/resources/rtest.mixins.json"));
        String probe = Files.readString(Path.of(
            "src/main/java/com/rtest/client/RayTracingProbe.java"));
        String dynamic = Files.readString(Path.of(
            "src/main/java/com/rtest/client/DynamicEntityGeometry.java"));
        if (mixins.contains("BlockEntityStateCaptureMixin")) {
            throw new AssertionError("Native block-entity fallback mixin must remain disabled");
        }
        if (probe.contains("blockEntityFallback.replay(")
            || !dynamic.contains("representedBlockEntityIds()")) {
            throw new AssertionError("Block entities must be owned by the RT dynamic TLAS");
        }
        if (!Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"))
            .contains("representedBlockEntities = Set.copyOf(represented)")) {
            throw new AssertionError("RT pass no longer publishes which block entities reached the TLAS");
        }
    }
}
