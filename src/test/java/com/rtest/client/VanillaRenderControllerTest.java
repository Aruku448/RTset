package com.rtest.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Contracts for fail-safe vanilla cancellation and the balanced LevelRenderer seam. */
public final class VanillaRenderControllerTest {
    private VanillaRenderControllerTest() {
    }

    public static void main(String[] args) throws IOException {
        VanillaRenderController controller = VanillaRenderController.INSTANCE;
        controller.reset();
        controller.beginFrame();
        require(!controller.shouldCancelLevelRenderer(false), "an unready RT frame must keep vanilla");
        require(controller.shouldCancelLevelRenderer(true), "a ready RT frame may cancel vanilla");
        controller.markWorldSkipped();
        controller.markRtResult(false);
        require(!controller.wasRtPresentedThisFrame(), "a failed frame must keep vanilla hand features");
        require(!controller.shouldCancelLevelRenderer(true), "a failed skipped frame must disable cancellation");
        controller.reset();
        controller.beginFrame();
        controller.markWorldSkipped();
        controller.markRtResult(true);
        require(controller.wasRtPresentedThisFrame(), "a successful replacement must suppress duplicate vanilla hand features");
        controller.reset();

        String mixin = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/LevelRendererMixin.java"));
        require(mixin.contains("RayTracingProbe.shouldCancelVanillaLevelRenderer()"),
            "LevelRenderer must consult the controller before cancelling");
        require(mixin.contains("RenderSystem.getModelViewStack().popMatrix();"),
            "LevelRenderer cancellation must balance its model-view push");
        require(mixin.contains("callbackInfo.cancel();"),
            "LevelRenderer cancellation must actually cancel the callback");
        require(mixin.contains("getSubmitsPerOrder().clear()"),
            "cancelled deferred nodes must be discarded");
        require(mixin.contains("abortLevelRenderPreparation(throwable)"),
            "preparation exceptions must fall back to vanilla");
        String itemCapture = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/ItemFeatureRendererCaptureMixin.java"));
        require(!itemCapture.contains("callback.cancel()"),
            "item submission must remain available when the post-hand RT pass fails");
        String hand = Files.readString(Path.of(
            "src/main/java/com/rtest/mixin/GameRendererMixin.java"));
        require(hand.contains("RayTracingProbe.shouldCaptureFirstPersonItem()"),
            "F8-off frames must not open the RT first-person capture scope");
        require(hand.contains("renderRtAfterHandCapture()")
                && hand.contains("renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V"),
            "RT must consume the current hand submission before screen effects");
        System.out.println("Vanilla render controller contracts passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
