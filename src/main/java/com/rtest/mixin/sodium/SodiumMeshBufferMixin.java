package com.rtest.mixin.sodium;

import com.rtest.client.SodiumTerrainMeshCache;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Account for fluid vertices without copying raster fluid geometry into the RT block cache. */
@Pseudo
@Mixin(value = ChunkMeshBufferBuilder.class, remap = false)
public abstract class SodiumMeshBufferMixin {
    @Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V",
        at = @At("RETURN"))
    private void rtest$countSubmittedVertices(ChunkVertexEncoder.Vertex[] vertices, int materialBits,
                                             CallbackInfo ci) {
        var build = SodiumTerrainMeshCache.active();
        if (build != null) build.submitted(vertices.length);
    }
}
