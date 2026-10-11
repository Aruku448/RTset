package com.rtest.mixin.sodium;

import com.rtest.client.SodiumTerrainMeshCache;
import com.rtest.compat.sodium.SodiumBuildOutputCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ChunkBuildOutput.class, remap = false)
public abstract class SodiumChunkBuildOutputMixin implements SodiumBuildOutputCapture {
    @Unique private SodiumTerrainMeshCache.Pending rtest$terrainCapture;

    @Override public void rtest$setTerrainCapture(SodiumTerrainMeshCache.Pending pending) { rtest$terrainCapture = pending; }
    @Override public SodiumTerrainMeshCache.Pending rtest$getTerrainCapture() { return rtest$terrainCapture; }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtest$releaseCapture(CallbackInfo ci) { rtest$terrainCapture = null; }
}
