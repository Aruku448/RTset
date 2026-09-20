package com.rtest.client;

/** Contract test for the retained, currently inactive visibility-policy reference. */
public final class RayTracingVisibilityPolicyTest {
    private RayTracingVisibilityPolicyTest() {
    }

    public static void main(String[] args) {
        RayTracingVisibilityPolicy.Settings disabled =
            new RayTracingVisibilityPolicy.Settings(false, 32.0D, false);
        if (!RayTracingVisibilityPolicy.keepSection(0, 96.0D, false, false, disabled)) {
            throw new AssertionError("disabled visibility policy must retain geometry");
        }

        RayTracingVisibilityPolicy.Settings cave =
            new RayTracingVisibilityPolicy.Settings(true, 32.0D, false);
        if (RayTracingVisibilityPolicy.keepSection(0, 96.0D, false, false, cave)
                || !RayTracingVisibilityPolicy.keepSection(0, 96.0D, true, false, cave)
                || RayTracingVisibilityPolicy.keepSection(0, 0.0D, false, false, cave)) {
            throw new AssertionError("cave policy did not preserve visible or camera-level sections");
        }
        if (!RayTracingVisibilityPolicy.keepSection(0, 96.0D, false, true, cave)) {
            throw new AssertionError("cave policy removed an indirect emissive source");
        }

        RayTracingVisibilityPolicy.Settings screenOnly =
            new RayTracingVisibilityPolicy.Settings(false, 32.0D, true);
        if (!RayTracingVisibilityPolicy.keepSection(64, 96.0D, true, false, screenOnly)
                || !RayTracingVisibilityPolicy.keepSection(64, 96.0D, false, true, screenOnly)
                || RayTracingVisibilityPolicy.keepSection(64, 96.0D, false, false, screenOnly)) {
            throw new AssertionError("screen-only policy did not retain visible/emissive sections");
        }
        System.out.println("Ray-tracing visibility policy contract passed");
    }
}
