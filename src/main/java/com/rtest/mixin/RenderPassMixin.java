package com.rtest.mixin;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.rtest.client.HdrSupport;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Adapts native graphics pipelines to the HDR main render target at the render-pass boundary. */
@Mixin(RenderPass.class)
public abstract class RenderPassMixin {
    @Shadow
    @Final
    private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private RenderPipeline rtest$adaptHdrPipeline(RenderPipeline pipeline) {
        if (!HdrSupport.isActive() || !this.rtest$hasHdrAttachment()) {
            return pipeline;
        }

        ColorTargetState[] states = pipeline.getColorTargetStates();
        RenderPipeline.Builder builder = null;
        for (int index = 0; index < states.length && index < this.colorAttachments.size(); index++) {
            ColorTargetState state = states[index];
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = this.colorAttachments.get(index);
            if (state == null || attachment == null || attachment.textureView() == null) {
                continue;
            }
            if (attachment.textureView().texture().getFormat() == GpuFormat.RGBA16_FLOAT
                    && state.format() == GpuFormat.RGBA8_UNORM) {
                if (builder == null) {
                    builder = pipeline.toBuilder();
                }
                builder.withColorTargetState(index, new ColorTargetState(
                        state.blendFunction(), GpuFormat.RGBA16_FLOAT, state.writeMask()));
            }
        }
        return builder == null ? pipeline : builder.build();
    }

    private boolean rtest$hasHdrAttachment() {
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : this.colorAttachments) {
            if (attachment != null
                    && attachment.textureView() != null
                    && attachment.textureView().texture().getFormat() == GpuFormat.RGBA16_FLOAT) {
                return true;
            }
        }
        return false;
    }
}
