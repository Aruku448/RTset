package com.rtest.mixin;

import com.rtest.client.RayTracingProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges vanilla client chunk/block invalidation to RTest's incremental scene scheduler. */
@Mixin(ClientLevel.class)
public final class ClientLevelDirtyMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void rtest$setBlocksDirty(
            BlockPos position,
            BlockState oldState,
            BlockState newState,
            CallbackInfo callbackInfo) {
        RayTracingProbe.markSectionDirty(position);
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void rtest$onChunkLoaded(ChunkPos chunkPosition, CallbackInfo callbackInfo) {
        ClientLevel level = (ClientLevel)(Object)this;
        RayTracingProbe.markChunkDirty(level, chunkPosition, true);
    }

    @Inject(method = "unload", at = @At("TAIL"))
    private void rtest$onChunkUnloaded(net.minecraft.world.level.chunk.LevelChunk chunk, CallbackInfo callbackInfo) {
        ClientLevel level = (ClientLevel)(Object)this;
        RayTracingProbe.markChunkDirty(level, chunk.getPos(), false);
    }
}
