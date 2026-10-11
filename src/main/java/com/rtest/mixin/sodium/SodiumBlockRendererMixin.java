package com.rtest.mixin.sodium;

import com.rtest.compat.sodium.SodiumTerrainCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture only quads actually submitted, after transforms/tint and invalid-quad rejection. */
@Pseudo
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class SodiumBlockRendererMixin extends AbstractBlockRenderContext {
    // This state is inherited by BlockRenderer; shadowing it on the subclass fails at runtime.
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

    @Inject(method = "bufferQuad", at = @At(value = "INVOKE", target =
        "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"))
    private void rtest$captureQuad(MutableQuadViewImpl quad, float[] brightnesses, Material material,
                                   CallbackInfo ci) {
        SodiumTerrainCapture.capture(this.state, quad, this.vertices);
    }
}
