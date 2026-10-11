package com.rtest.mixin.sodium;

import com.rtest.client.SodiumTerrainMeshCache;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers Sodium rebuilds (including lighting changes) and renderer lifecycle events. */
@Pseudo
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumSectionManagerMixin {
    @Inject(method = "scheduleRebuild", at = @At("HEAD"))
    private void rtest$invalidateRebuild(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        SodiumTerrainMeshCache.invalidate(new BlockPos(x * 16, y * 16, z * 16));
    }

    @Inject(method = {"onSectionAdded", "onSectionRemoved"}, at = @At("HEAD"))
    private void rtest$invalidateMembership(int x, int y, int z, CallbackInfo ci) {
        SodiumTerrainMeshCache.invalidate(new BlockPos(x * 16, y * 16, z * 16));
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtest$invalidateRenderer(CallbackInfo ci) {
        SodiumTerrainMeshCache.invalidateAll();
    }
}
