package com.rtest.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.state.level.QuadParticleRenderState$Storage")
public interface QuadParticleStorageAccessor {
    @Accessor("floatValues")
    float[] rtest$getFloatValues();

    @Accessor("intValues")
    int[] rtest$getIntValues();

    @Accessor("currentParticleIndex")
    int rtest$getParticleCount();
}
