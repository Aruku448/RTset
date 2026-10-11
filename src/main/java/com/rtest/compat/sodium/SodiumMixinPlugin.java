package com.rtest.compat.sodium;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Selects all terrain hooks together without loading any Sodium or Minecraft class. */
public final class SodiumMixinPlugin implements IMixinConfigPlugin {
    private boolean supported;

    @Override
    public void onLoad(String mixinPackage) {
        var loader = net.neoforged.fml.loading.FMLLoader.getCurrentOrNull();
        LoadingModList mods = loader == null ? null : loader.getLoadingModList();
        if (mods == null) return;
        for (var mod : mods.getMods()) {
            if (!mod.getModId().equals("sodium")) continue;
            String version = mod.getVersion().toString();
            supported = version.equals("0.9.2+mc26.2");
            com.mojang.logging.LogUtils.getLogger().info(
                "RTest Sodium terrain bridge: {} (Sodium {})",
                supported ? "enabled" : "unsupported version; using CPU capture", version);
            break;
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return supported; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
    @Override public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) { }
}
