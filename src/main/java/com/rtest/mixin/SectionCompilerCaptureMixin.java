package com.rtest.mixin;

import com.rtest.client.CompiledSectionMeshCache;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copies vanilla terrain mesh data before the asynchronous compiler releases it. */
@Mixin(SectionCompiler.class)
public final class SectionCompilerCaptureMixin {
    @Inject(method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;", at = @At("RETURN"))
    private void rtest$captureCompiledMesh(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack builders,
            List<?> additionalRenderers,
            CallbackInfoReturnable<SectionCompiler.Results> callbackInfo) {
        CompiledSectionMeshCache.publish(sectionPos, callbackInfo.getReturnValue());
    }
}
