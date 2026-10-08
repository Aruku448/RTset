package com.rtest.client;

import net.minecraft.client.renderer.SubmitNodeStorage;

/** Keeps the local renderer's submissions isolated from vanilla raster playback. */
public final class FirstPersonPlayerCaptureStorage extends SubmitNodeStorage {
    public void begin() {
        getSubmitsPerOrder().clear();
    }
}
