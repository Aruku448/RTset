package com.rtest.client;

import net.minecraft.client.renderer.SubmitNodeStorage;

/** Runtime bridge added to LevelRenderer by the first-person capture mixin. */
public interface FirstPersonCaptureStorage {
    SubmitNodeStorage rtest$firstPersonCaptureStorage();
}
