package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Contracts for the RT-only entity path and dynamic PBR material upload. */
public final class EntityRasterFallbackContractTest {
    private EntityRasterFallbackContractTest() {
    }

    public static void main(String[] args) throws Exception {
        String probe = source("src/main/java/com/rtest/client/RayTracingProbe.java");
        String dynamic = source("src/main/java/com/rtest/client/DynamicEntityGeometry.java");
        String mixin = source("src/main/java/com/rtest/mixin/ItemEntityCaptureMixin.java");
        String renderer = source("src/main/java/com/rtest/mixin/LevelRendererMixin.java");
        String vulkanPass = source("src/main/java/com/rtest/client/RayTracingVulkanPass.java");
        String hand = source("src/main/java/com/rtest/mixin/GameRendererMixin.java");
        String modelCapture = source("src/main/java/com/rtest/mixin/PlayerModelCaptureMixin.java");
        String levelCapture = source("src/main/java/com/rtest/mixin/LevelPlayerCaptureMixin.java");
        String levelRenderer = source("src/main/java/com/rtest/mixin/LevelRendererMixin.java");
        String captureBridge = source("src/main/java/com/rtest/client/FirstPersonCaptureStorage.java");

        reject(probe, "entityFallback.replay(");
        reject(probe, "blockEntityFallback.replay(");
        reject(probe, "hasUnsafeRasterFallback()");
        require(probe, "representedEntityIds()");
        require(probe, "if (!RayTracingClientConfig.INSTANCE.dynamicEntityMvpEnabled.get())");
        require(probe, "terrain-only RT output");
        require(dynamic, "fallback++");
        require(dynamic, "DYNAMIC_MODEL_TRIANGLE_CAPACITY");
        require(dynamic, "DYNAMIC_ITEM_TRIANGLE_CAPACITY");
        require(dynamic, "representedEntityIds()");
        require(dynamic, "isTransientCaptureMiss(Frame current, Frame previous)");
        require(dynamic, "representedBlockEntityIds()");
        require(mixin, "LivingEntityGeometryAdapter.beginEntity(state, camera.pos);");
        require(renderer, "featureRenderDispatcher.prepareFrame(this.submitNodeStorage)");
        require(renderer, "rtest$firstPersonCaptureStorage()");
        require(renderer, "firstPersonStorage.getSubmitsPerOrder().clear()");
        reject(renderer, "RayTracingProbe.finishDeferredEntityCapture()");
        require(renderer, "preparedFrame.close()");
        require(renderer, "getSubmitsPerOrder().clear()");
        require(renderer, "abortLevelRenderPreparation(throwable)");
        require(probe, "DynamicEntityGeometry.isTransientCaptureMiss(captured, lastEntityFrame)");
        require(dynamic, "previous.dynamicFrame().activeCount() > 0");
        String dispatch = vulkanPass.substring(vulkanPass.indexOf("int dispatch("),
            vulkanPass.indexOf("/** Replays the last completed FSR image"));
        require(dispatch, "this.topLevelUpdatePending = this.topLevelUpdatePending\n                        || (canUpdateTopLevel && dynamicTlasChanged);");
        require(dispatch, "boolean forceDynamicInstanceWrite = !this.topLevelBuilt;");
        require(dispatch, "updateDynamicInstances(effectiveDynamicFrame,\n                        forceDynamicInstanceWrite)");
        require(vulkanPass, "this.dynamicHistoryResetPending = true;");
        require(vulkanPass, "this.fsr.requestReset();");
        require(vulkanPass, "if (this.dynamicHistoryResetPending)");
        require(vulkanPass, "this.dynamicHistoryResetPending = false;");
        if (dispatch.indexOf("updateDynamicInstances(effectiveDynamicFrame,")
            > dispatch.indexOf("if (!this.topLevelBuilt)")) {
            throw new AssertionError("dynamic TLAS instances are updated after TLAS construction");
        }
        require(hand, "renderRtAfterHandCapture()");
        require(modelCapture, "shouldSuppressVanillaWorldModels()");
        require(modelCapture, "model.renderToBuffer(pose, buffer, light, overlay, color);");
        require(modelCapture, "LivingEntityGeometryAdapter.isEmissive(submit.renderType())");
        require(modelCapture, "LivingEntityGeometryAdapter.emissionFor(submit.renderType())");
        String livingCapture = source("src/main/java/com/rtest/client/LivingEntityGeometryAdapter.java");
        require(livingCapture, "pipeline == RenderPipelines.EYES");
        require(livingCapture, "pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE");
        require(livingCapture, "name.startsWith(\"eyes[\")");
        require(source("src/main/java/com/rtest/client/RayTracingShaders.java"), "bool pipelineEmissive = optical.z > 0.5;");
        require(source("src/main/java/com/rtest/client/RayTracingShaders.java"),
            "surface.z = max(surface.z, max(camera.pbrSettings.z, camera.pbrParallaxSettings.y));");
        reject(levelCapture, "LivingEntityGeometryAdapter.endWorldDraw();");
        reject(levelCapture, "BlockEntityModelGeometryAdapter.endWorldDraw();");
        require(levelCapture, "new SubmitNodeStorage()");
        require(levelCapture, "poseStack, this.rtest$isolatedFirstPersonBody");
        reject(levelCapture, "poseStack, output);");
        require(levelRenderer, "RayTracingProbe.endDeferredEntityCapture();");
        require(levelRenderer, "import com.rtest.client.FirstPersonCaptureStorage;");
        require(levelCapture, "import com.rtest.client.FirstPersonCaptureStorage;");
        require(captureBridge, "package com.rtest.client;");
        if (Files.exists(Path.of("src/main/java/com/rtest/mixin/FirstPersonCaptureStorage.java"))) {
            throw new AssertionError("runtime bridge must not live in the configured Mixin package");
        }

        System.out.println("RT-only entity contracts passed");
    }

    private static String source(String path) throws Exception {
        // Git/Gradle may preserve CRLF on Windows; contract fragments use LF so normalize
        // before checking multi-line source invariants.
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("RT entity contract is missing: " + fragment);
        }
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) {
            throw new AssertionError("RT entity path still contains native fallback: " + fragment);
        }
    }
}
