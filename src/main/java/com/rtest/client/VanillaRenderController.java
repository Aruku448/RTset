package com.rtest.client;

/**
 * Decides whether the vanilla level raster may be replaced for the current frame.
 *
 * <p>The decision is deliberately based on a completed RT presentation from an earlier frame.
 * Scene capture and Vulkan resource construction are asynchronous with respect to the level
 * renderer, so a scene snapshot alone is not sufficient to prevent a black frame.</p>
 */
public final class VanillaRenderController {
    public static final VanillaRenderController INSTANCE = new VanillaRenderController();

    private boolean worldSkipped;
    private boolean rtPresented;
    private boolean failed;

    private VanillaRenderController() {
    }

    public void beginFrame() {
        worldSkipped = false;
        rtPresented = false;
    }

    public boolean shouldCancelLevelRenderer(boolean rtReady) {
        return rtReady && !failed;
    }

    public void markWorldSkipped() {
        worldSkipped = true;
    }

    public boolean wasWorldSkippedThisFrame() {
        return worldSkipped;
    }

    public boolean wasRtPresentedThisFrame() {
        return worldSkipped && rtPresented;
    }

    public void markRtResult(boolean success) {
        rtPresented = success;
        if (worldSkipped && !success) {
            failed = true;
        }
    }

    public void reset() {
        worldSkipped = false;
        rtPresented = false;
        failed = false;
    }
}
