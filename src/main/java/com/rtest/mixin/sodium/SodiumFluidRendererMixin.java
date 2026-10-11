package com.rtest.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.rtest.client.SodiumTerrainMeshCache;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Count the fluid component separately and retain all ordinary block quads. */
@Pseudo
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class SodiumFluidRendererMixin {
    @WrapMethod(method = "render")
    private void rtest$countFluidVertices(LevelSlice level, BlockState state, FluidState fluid,
                                          BlockPos position, BlockPos offset, TranslucentGeometryCollector collector,
                                          ChunkModelBuilder meshBuilder, Material material,
                                          ColorProvider<FluidState> colors, FluidModel sprites,
                                          Operation<Void> original) {
        var build = SodiumTerrainMeshCache.active();
        if (build != null) build.beginFluid();
        try {
            original.call(level, state, fluid, position, offset, collector, meshBuilder, material, colors, sprites);
        } finally {
            if (build != null) build.endFluid();
        }
    }
}
