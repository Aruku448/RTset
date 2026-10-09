package com.rtest.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderCheckpointAccessor {
    @Accessor("checkpointStorage")
    CheckpointExtension.CheckpointStorage rtest$checkpointStorage();
}
