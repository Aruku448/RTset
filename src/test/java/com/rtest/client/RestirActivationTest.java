package com.rtest.client;

import com.electronwill.nightconfig.core.CommentedConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.client.gui.components.CycleButton;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Runs the actual F9 callbacks and config reload against an isolated in-memory config. */
public final class RestirActivationTest {
    public static void main(String[] args) throws Exception {
        CommentedConfig data = CommentedConfig.inMemory();
        RayTracingClientConfig.RESTIR_SPEC.correct(data);
        Class<?> loaded = Class.forName("net.neoforged.fml.config.LoadedConfig");
        Constructor<?> ctor = loaded.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        RayTracingClientConfig.RESTIR_SPEC.acceptConfig((IConfigSpec.ILoadedConfig) ctor.newInstance(data, null, null));
        CommentedConfig auditData = CommentedConfig.inMemory();
        RayTracingClientConfig.AUDIT_SPEC.correct(auditData);
        RayTracingClientConfig.AUDIT_SPEC.acceptConfig((IConfigSpec.ILoadedConfig) ctor.newInstance(auditData, null, null));
        CommentedConfig clientData = CommentedConfig.inMemory();
        RayTracingClientConfig.SPEC.correct(clientData);
        RayTracingClientConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) ctor.newInstance(clientData, null, null));
        try {
            CycleButton<?> direct = toggle("restirDirectEnabled", RayTracingClientConfig.INSTANCE.restirDirectEnabled);
            CycleButton<?> suffix = toggle("restirSuffixEnabled", RayTracingClientConfig.INSTANCE.restirSuffixEnabled);
            expectMode(0);
            press(direct);
            expectMode(1);
            press(suffix);
            expectMode(3);
            RayTracingClientConfig.INSTANCE.rayCostAuditProfile.set("no_restir");
            expectMode(0);
            RayTracingClientConfig.INSTANCE.rayCostAuditProfile.set("no_gi");
            expectMode(1);
            RayTracingClientConfig.INSTANCE.rayCostAuditProfile.set("baseline");
            expectMode(3);
            RayTracingClientConfig.RESTIR_SPEC.afterReload();
            expectMode(3);
            CommentedConfig oldSharedConfig = CommentedConfig.inMemory();
            RayTracingClientConfig.SPEC.correct(oldSharedConfig);
            RayTracingClientConfig.SPEC.correct(oldSharedConfig);
            expectMode(3);
            if (oldSharedConfig.contains("restirDirectEnabled")) {
                throw new AssertionError("ReSTIR settings must not be stored in the shared config");
            }
            if (!Boolean.TRUE.equals(data.get("restirDirectEnabled")) || !Boolean.TRUE.equals(data.get("restirSuffixEnabled"))) {
                throw new AssertionError("F9 callbacks did not write both config fields");
            }
            press(direct);
            expectMode(2);
            press(suffix);
            expectMode(0);
            System.out.println("ReSTIR activation: actual F9 callbacks enable/disable modes 0/1/2/3 and survive config cache reload");
        } finally {
            RayTracingClientConfig.RESTIR_SPEC.acceptConfig(null);
            RayTracingClientConfig.AUDIT_SPEC.acceptConfig(null);
            RayTracingClientConfig.SPEC.acceptConfig(null);
        }
    }

    private static CycleButton<?> toggle(String name, ModConfigSpec.BooleanValue value) throws Exception {
        Method factory = RayTracingSettingsScreen.class.getDeclaredMethod("restirToggle", String.class, ModConfigSpec.BooleanValue.class);
        factory.setAccessible(true);
        Object entry = factory.invoke(null, name, value);
        var widgets = entry.getClass().getDeclaredField("widgets");
        widgets.setAccessible(true);
        return (CycleButton<?>) ((List<?>) widgets.get(entry)).getFirst();
    }

    private static void press(CycleButton<?> button) throws Exception {
        Method cycle = CycleButton.class.getDeclaredMethod("cycleValue", int.class);
        cycle.setAccessible(true);
        cycle.invoke(button, 1);
    }

    private static void expectMode(int expected) {
        int actual = RayTracingRestirShader.requestedMode();
        if (actual != expected) throw new AssertionError("Expected mode " + expected + ", got " + actual);
    }
}
