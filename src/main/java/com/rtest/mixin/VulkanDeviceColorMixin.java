package com.rtest.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.rtest.client.NativeColorManagement;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Injects the HDR sRGB-to-linear decode into native fragment shaders while HDR is active. */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceColorMixin {
    @Unique
    private static final ThreadLocal<RenderPipeline> RTEST_CURRENT_PIPELINE = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<ShaderType> RTEST_CURRENT_SHADER_TYPE = new ThreadLocal<>();

    @Inject(method = "compilePipeline", at = @At("HEAD"))
    private void rtest$beginPipelineCompilation(
            RenderPipeline pipeline,
            ShaderSource shaderSource,
            CallbackInfoReturnable<VulkanRenderPipeline> callbackInfo) {
        RTEST_CURRENT_PIPELINE.set(pipeline);
    }

    @Inject(method = "compilePipeline", at = @At("RETURN"))
    private void rtest$endPipelineCompilation(
            RenderPipeline pipeline,
            ShaderSource shaderSource,
            CallbackInfoReturnable<VulkanRenderPipeline> callbackInfo) {
        RTEST_CURRENT_PIPELINE.remove();
    }

    @Inject(method = "getOrCompileShader", at = @At("HEAD"))
    private void rtest$beginShaderCompilation(
            Identifier id,
            ShaderType shaderType,
            ShaderDefines defines,
            ShaderSource shaderSource,
            CallbackInfoReturnable<IntermediaryShaderModule> callbackInfo) {
        RTEST_CURRENT_SHADER_TYPE.set(shaderType);
    }

    @Inject(method = "getOrCompileShader", at = @At("RETURN"))
    private void rtest$endShaderCompilation(
            Identifier id,
            ShaderType shaderType,
            ShaderDefines defines,
            ShaderSource shaderSource,
            CallbackInfoReturnable<IntermediaryShaderModule> callbackInfo) {
        RTEST_CURRENT_SHADER_TYPE.remove();
    }

    @ModifyArg(
        method = "compileShader",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;injectDefines(Ljava/lang/String;Lnet/minecraft/client/renderer/ShaderDefines;)Ljava/lang/String;"
        ),
        index = 0
    )
    private String rtest$convertNativeShader(String source) {
        // Keep the runtime path reversible while validating compositor transfer semantics. The
        // transformation remains unit-tested and can be enabled explicitly once the surface is known
        // to perform the matching linear-to-display conversion.
        if (!com.rtest.client.RayTracingClientConfig.INSTANCE.nativeColorDecodeEnabled.get()) {
            return source;
        }
        return NativeColorManagement.transform(
                RTEST_CURRENT_PIPELINE.get(), RTEST_CURRENT_SHADER_TYPE.get(), source);
    }
}
