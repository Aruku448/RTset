package com.rtest.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.rtest.client.SodiumTerrainMeshCache;
import com.rtest.compat.sodium.SodiumBuildOutputCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class SodiumChunkMeshingMixin {
    @Shadow @Final private ChunkRenderContext renderContext;
    @Unique private SodiumTerrainMeshCache.Token rtest$terrainToken;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void rtest$recordGeneration(CallbackInfo ci) {
        this.rtest$terrainToken = SodiumTerrainMeshCache.begin(
            ((ChunkBuilderMeshingTask)(Object)this).getRenderSection().getPosition());
    }

    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput rtest$captureBuild(ChunkBuildContext context, CancellationToken cancellation,
                                               Operation<ChunkBuildOutput> original) {
        try (var build = SodiumTerrainMeshCache.open(this.rtest$terrainToken)) {
            if (!this.renderContext.getRenderers().isEmpty()) build.fail(SodiumTerrainMeshCache.Failure.MESH_APPENDER);
            ChunkBuildOutput output = original.call(context, cancellation);
            if (output == null) return null;
            int stride = ChunkMeshFormats.getCurrent().getVertexFormat().getVertexSize();
            long vertices = 0;
            for (var mesh : output.meshes.values()) {
                int bytes = mesh.getVertexData().getLength();
                if (stride <= 0 || bytes % stride != 0) {
                    build.fail(SodiumTerrainMeshCache.Failure.TOPOLOGY);
                    break;
                }
                vertices += bytes / stride;
            }
            ((SodiumBuildOutputCapture)output).rtest$setTerrainCapture(build.finish(vertices));
            return output;
        }
    }
}
