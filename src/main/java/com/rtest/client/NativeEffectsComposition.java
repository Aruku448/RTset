package com.rtest.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderer-independent contract for the post-RT vanilla effects boundary.
 *
 * <p>This phase records ordering and fallback state only. It does not call vanilla rendering a
 * second time and does not pretend that an AfterLevel callback can recover already overwritten
 * pixels. A future overlay pass can consume this immutable plan at the correct Frame Graph seam.</p>
 */
public final class NativeEffectsComposition {
    public enum Layer {
        PARTICLES,
        CLOUDS,
        WEATHER,
        WORLD_BORDER,
        SCREEN_EFFECTS,
        ENTITY_OUTLINE,
        GAME_RENDERER_POST
    }

    public record Frame(long frame, List<Layer> worldOverlayOrder, boolean nativeRasterFallback,
                        boolean particles, boolean clouds, boolean weather, boolean worldBorder) {
        public Frame {
            worldOverlayOrder = List.copyOf(worldOverlayOrder);
        }
    }

    private long frame;

    /** Builds the stable composition order without issuing draw calls. */
    public Frame plan(boolean particles, boolean clouds, boolean weather, boolean worldBorder,
                      boolean nativeRasterFallback) {
        List<Layer> order = new ArrayList<>();
        if (particles) order.add(Layer.PARTICLES);
        if (clouds) order.add(Layer.CLOUDS);
        if (weather) order.add(Layer.WEATHER);
        if (worldBorder) order.add(Layer.WORLD_BORDER);
        frame++;
        return new Frame(frame, order, nativeRasterFallback, particles, clouds, weather, worldBorder);
    }
}
