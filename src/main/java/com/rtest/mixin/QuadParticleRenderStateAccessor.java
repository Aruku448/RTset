package com.rtest.mixin;

import java.util.Map;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(QuadParticleRenderState.class)
public interface QuadParticleRenderStateAccessor {
    @Accessor("particles")
    Map<SingleQuadParticle.Layer, Object> rtest$getParticles();
}
