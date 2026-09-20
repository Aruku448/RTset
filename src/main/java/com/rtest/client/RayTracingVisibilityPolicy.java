package com.rtest.client;

/**
 * Standalone reference for conservative Section-level visibility filtering.
 *
 * <p>This policy is intentionally not wired into the active capture path. It is
 * retained as the design reference for the frustum/height/emissive-candidate
 * idea discussed earlier, so the experimental code is available without
 * reintroducing its runtime behavior.
 */
public final class RayTracingVisibilityPolicy {
    private RayTracingVisibilityPolicy() {
    }

    public record Settings(
        boolean caveCullingEnabled,
        double caveCullingHeight,
        boolean screenOnlyCaptureEnabled
    ) {
    }

    /**
     * Returns whether a Section remains a capture candidate.
     *
     * <p>Visible Sections and Sections containing emissive sources are always
     * retained. Screen-only mode drops other Sections. Cave mode additionally
     * drops low, non-visible Sections once the camera has reached them.
     */
    public static boolean keepSection(
        int sectionY,
        double cameraY,
        boolean screenVisible,
        boolean hasEmissiveSource,
        Settings settings
    ) {
        if (!settings.caveCullingEnabled() && !settings.screenOnlyCaptureEnabled()) {
            return true;
        }
        if (screenVisible || hasEmissiveSource) {
            return true;
        }
        if (settings.screenOnlyCaptureEnabled()) {
            return false;
        }
        boolean lowSection = sectionY <= settings.caveCullingHeight();
        boolean cameraAtOrAboveSection = cameraY >= sectionY;
        return !(lowSection && cameraAtOrAboveSection);
    }
}
