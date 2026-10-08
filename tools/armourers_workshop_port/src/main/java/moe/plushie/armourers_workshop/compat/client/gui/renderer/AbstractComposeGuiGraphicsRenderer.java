package moe.plushie.armourers_workshop.compat.client.gui.renderer;

import com.apple.library.coregraphics.CGGraphicsElement;
import com.mojang.blaze3d.vertex.PoseStack;
import moe.plushie.armourers_workshop.api.client.IBufferSource;
import moe.plushie.armourers_workshop.api.client.IGraphicsContext;
import moe.plushie.armourers_workshop.api.client.IGraphicsElement;
import moe.plushie.armourers_workshop.api.core.math.IPoseStack;
import moe.plushie.armourers_workshop.compat.client.math.AbstractPoseStack;
import moe.plushie.armourers_workshop.compat.client.renderer.graphics.AbstractGraphicsRenderer;
import moe.plushie.armourers_workshop.compat.client.renderer.vertex.AbstractBufferSource;
import moe.plushie.armourers_workshop.core.client.gui.element.StateGuiElement.Flush;
import moe.plushie.armourers_workshop.core.client.gui.element.ClipGuiElement;
import moe.plushie.armourers_workshop.core.client.other.SceneGraphicsContext;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

public class AbstractComposeGuiGraphicsRenderer extends PictureInPictureRenderer<AbstractComposeGuiGraphicsState> {
   private final NativeGuiLayers<AbstractComposeGuiGraphicsRenderer> layerRenderers = new NativeGuiLayers<>();
   private final boolean layerRenderer;

   public AbstractComposeGuiGraphicsRenderer() { this(false); }
   private AbstractComposeGuiGraphicsRenderer(boolean layerRenderer) { this.layerRenderer = layerRenderer; }

   public void prepare(AbstractComposeGuiGraphicsState renderState, GuiRenderState guiRenderState, FeatureRenderDispatcher featureRenderDispatcher, int i) {
      if (renderState.isEmpty()) return;
      if (layerRenderer) {
         super.prepare(renderState, guiRenderState, featureRenderDispatcher, i);
      } else {
         // BlitRenderState retains the texture view until all GUI layers have been prepared.
         // Give background/foreground independent targets so later clears cannot overwrite them.
         layerRenderers.target(renderState.toString(), () -> new AbstractComposeGuiGraphicsRenderer(true))
            .prepare(renderState, guiRenderState, featureRenderDispatcher, i);
      }
   }

   @Override public void close() {
      layerRenderers.close(AbstractComposeGuiGraphicsRenderer::close);
      super.close();
   }

   protected void renderToTexture(
      AbstractComposeGuiGraphicsState renderState, IPoseStack poseStack, IBufferSource bufferSource, SubmitNodeCollector submitNodeCollector
   ) {
      AbstractComposeGuiGraphicsRenderer.OffscreenDispatcherImpl dispatcher = new AbstractComposeGuiGraphicsRenderer.OffscreenDispatcherImpl(
         poseStack, bufferSource, submitNodeCollector
      );
      renderState.forEach((snapshot, element) -> {
         if (element instanceof Flush) {
            dispatcher.flush();
         } else {
            if (element instanceof ClipGuiElement) dispatcher.flush();
            poseStack.pushPose();
            poseStack.multiply(snapshot.ctm().pose());
            poseStack.multiply(snapshot.ctm().normal());
            dispatcher.submit(element);
            dispatcher.flush();
            poseStack.popPose();
         }
      });
      dispatcher.flush();
   }

   protected void renderToTexture(AbstractComposeGuiGraphicsState renderState, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
      int width = renderState.x1() - renderState.x0();
      int height = renderState.y1() - renderState.y0();
      poseStack.translate(-width / 2.0F, -height, 0.0F);
      SubmitNodeCollector previous = AbstractBufferSource.setActiveCollector(submitNodeCollector);
      try (NativeGuiClip.Scope scope = NativeGuiClip.begin(poseStack.last().pose())) {
         this.renderToTexture(renderState, AbstractPoseStack.wrap(poseStack), AbstractBufferSource.wrap(submitNodeCollector), submitNodeCollector);
      } finally {
         AbstractBufferSource.setActiveCollector(previous);
      }
   }

   public float getNear() {
      return -10000.0F;
   }

   public float getFar() {
      return 10000.0F;
   }

   protected String getTextureLabel() {
      return "core-graphics-implement";
   }

   public Class<AbstractComposeGuiGraphicsState> getRenderStateClass() {
      return AbstractComposeGuiGraphicsState.class;
   }

   private static class OffscreenDispatcherImpl {
      private final IPoseStack poseStack;
      private final IBufferSource bufferSource;
      private final SubmitNodeCollector submitNodeCollector;
      private int gamePassTotal = 0;
      private int normalPassTotal = 0;
      private IBufferSource gameBufferSource;
      private SceneGraphicsContext gameContext;

      public OffscreenDispatcherImpl(IPoseStack poseStack, IBufferSource bufferSource, SubmitNodeCollector submitNodeCollector) {
         this.poseStack = poseStack;
         this.bufferSource = bufferSource;
         this.submitNodeCollector = submitNodeCollector;
      }

      public void submit(CGGraphicsElement element) {
         if (element instanceof IGraphicsElement element1) {
            this.gameContext().draw(element1);
            this.gamePassTotal++;
         } else {
            element.render(this.poseStack, this.bufferSource);
            this.normalPassTotal++;
         }
      }

      public void flush() {
         if (this.gameBufferSource != null && this.gamePassTotal != 0) {
            this.gameContext.flush();
            this.gameBufferSource.endBatch();
            this.gamePassTotal = 0;
         }

         if (this.normalPassTotal != 0) {
            this.bufferSource.endBatch();
            this.normalPassTotal = 0;
         }
      }

      public IGraphicsContext gameContext() {
         if (this.gameContext == null) {
            this.gameContext = AbstractGraphicsRenderer.wrap(AbstractPoseStack.unwrap(this.poseStack), this.submitNodeCollector);
            this.gameBufferSource = AbstractBufferSource.wrap(this.submitNodeCollector);
         }

         return this.gameContext;
      }
   }
}
