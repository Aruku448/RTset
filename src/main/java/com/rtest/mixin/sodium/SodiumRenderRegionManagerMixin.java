package com.rtest.mixin.sodium;

import com.rtest.client.RayTracingProbe;
import com.rtest.client.SodiumTerrainMeshCache;
import com.rtest.compat.sodium.SodiumBuildOutputCapture;
import java.util.Collection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class SodiumRenderRegionManagerMixin {
    // This collection contains accepted results, unlike the worker completion queue.
    @Inject(method = "uploadResults(Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V", at = @At("HEAD"))
    private void rtest$acceptTerrain(Collection<BuilderTaskOutput> results, UniformBufferManager uniforms,
                                     CallbackInfo ci) {
        for (var result : results) {
            if (!(result instanceof SodiumBuildOutputCapture capture) || result.section.isDisposed()) continue;
            var pending = capture.rtest$getTerrainCapture();
            if (pending != null && SodiumTerrainMeshCache.publish(pending)) {
                RayTracingProbe.onSodiumSectionMeshReady(SectionPos.of(pending.token().sectionKey()).origin());
            } else if (pending == null) {
                // An accepted unsupported rebuild must not leave an older snapshot reusable.
                var origin = result.section.getPosition().origin();
                SodiumTerrainMeshCache.invalidate(origin);
                RayTracingProbe.onSodiumSectionMeshReady(origin);
            }
            capture.rtest$setTerrainCapture(null);
        }
    }
}
