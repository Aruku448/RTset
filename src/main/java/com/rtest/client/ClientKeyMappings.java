package com.rtest.client;

import com.rtest.RTest;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = RTest.MOD_ID, value = Dist.CLIENT)
public final class ClientKeyMappings {
    public static final KeyMapping RUN_RAY_TRACING_SMOKE_TEST = new KeyMapping(
        "key.rtest.runRayTracingSmokeTest",
        GLFW.GLFW_KEY_F8,
        KeyMapping.Category.MISC
    );
    public static final KeyMapping OPEN_RAY_TRACING_SETTINGS = new KeyMapping(
        "key.rtest.openRayTracingSettings",
        GLFW.GLFW_KEY_F9,
        KeyMapping.Category.MISC
    );

    private ClientKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(RUN_RAY_TRACING_SMOKE_TEST);
        event.register(OPEN_RAY_TRACING_SETTINGS);
    }
}
