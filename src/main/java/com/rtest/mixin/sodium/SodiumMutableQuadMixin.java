package com.rtest.mixin.sodium;

import com.rtest.client.SodiumTerrainMeshCache;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium retains full-bright emission as a flag, but loses intermediate model emission. */
@Pseudo
@Mixin(value = MutableQuadViewImpl.class, remap = false)
public abstract class SodiumMutableQuadMixin {
    @Inject(method = "fromBakedQuad", at = @At("HEAD"))
    private void rtest$preservePartialEmission(BakedQuad quad,
                                              CallbackInfoReturnable<MutableQuadViewImpl> ci) {
        int emission = quad.materialInfo().lightEmission();
        var build = SodiumTerrainMeshCache.active();
        if (build != null && emission > 0 && emission < 15) build.fail(SodiumTerrainMeshCache.Failure.PARTIAL_EMISSION);
    }
}
