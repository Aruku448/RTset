package com.rtest.client;

import java.util.List;

/** Contract test for native post-RT overlay ordering and fallback semantics. */
public final class NativeEffectsCompositionTest {
    public static void main(String[] args) {
        var composition = new NativeEffectsComposition();
        var frame = composition.plan(true, true, true, true, true);
        if (!frame.worldOverlayOrder().equals(List.of(
            NativeEffectsComposition.Layer.PARTICLES,
            NativeEffectsComposition.Layer.CLOUDS,
            NativeEffectsComposition.Layer.WEATHER,
            NativeEffectsComposition.Layer.WORLD_BORDER))) {
            throw new AssertionError("native world overlay order changed");
        }
        if (!frame.nativeRasterFallback() || frame.frame() != 1L) {
            throw new AssertionError("overlay fallback/frame contract is invalid");
        }
        var empty = composition.plan(false, false, false, false, false);
        if (!empty.worldOverlayOrder().isEmpty() || empty.frame() != 2L) {
            throw new AssertionError("empty overlay plan is invalid");
        }
    }
}
