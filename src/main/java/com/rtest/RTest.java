package com.rtest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import com.rtest.client.RayTracingClientConfig;
import com.rtest.client.RayTracingProbe;

@Mod(RTest.MOD_ID)
public final class RTest {
    public static final String MOD_ID = "rtest";

    private static final PreparableReloadListener RT_RESOURCE_RELOAD_GUARD =
        new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(SharedState state, Executor preparationExecutor,
                                                   PreparationBarrier barrier, Executor applyExecutor) {
                // Only publish a generation marker from the reload executor. Native PBR images and
                // Vulkan state are invalidated later at the render seam, on the client thread.
                return barrier.wait((Void)null)
                    .thenRunAsync(RayTracingProbe::markResourcesReloaded, applyExecutor);
            }

            @Override
            public String getName() {
                return "RTest RT resource generation";
            }
        };

    public RTest(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, RayTracingClientConfig.SPEC);
        modEventBus.addListener(AddClientReloadListenersEvent.class, RTest::addReloadListener);
    }

    private static void addReloadListener(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(MOD_ID, "rt_resource_generation"),
            RT_RESOURCE_RELOAD_GUARD);
    }
}
