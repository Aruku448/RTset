package com.rtest.client.fsr;

import org.joml.Matrix4f;

/**
 * Coordinate-system boundary shared by the ray generator, NRD frame settings and motion pass.
 *
 * <p>RTest traces camera-relative positions with a forward-positive, reversed-infinite-Z camera.
 * NRD's screen convention puts row zero at clip Y=+1, while the ray generator's image convention
 * puts it at clip Y=-1; the negative projection Y below is the same correction Prime applies to
 * Minecraft's projection before handing it to NRD.
 */
final class NrdCameraTransform {
    private static final float NEAR_PLANE = 0.05F;

    private NrdCameraTransform() {
    }

    static Matrix4f projectionForNrd(RtestFsrCamera camera) {
        // Prime's boundary flips the Minecraft projection's Y row so NRD's top-origin UV maps
        // to the same raygen image row. Keep the exact finite-depth projection when available;
        // the fallback is only for old callers that provide the compact camera basis.
        if (camera.projection() != null) {
            return new Matrix4f(camera.projection())
                    .m01(-camera.projection().m01())
                    .m11(-camera.projection().m11())
                    .m21(-camera.projection().m21())
                    .m31(-camera.projection().m31());
        }
        // Minecraft uses a conventional -Z view axis and reversed infinite depth: clip.z /
        // clip.w = near / -viewZ.
        return new Matrix4f().zero()
                .m00(camera.projectionM00())
                .m11(-camera.projectionM11())
                .m22(0.0F)
                .m23(-1.0F)
                .m32(NEAR_PLANE)
                .m33(0.0F);
    }

    static Matrix4f viewRotation(RtestFsrCamera camera) {
        if (camera.viewRotation() != null) {
            return new Matrix4f(camera.viewRotation());
        }
        // JOML is column-major. Rows are the camera right/up/forward dot products, matching the
        // dot-product camera basis used by RayTracingShaders.RAYGEN_SHADER.
        return new Matrix4f().identity()
                .m00(camera.rightX()).m01(camera.upX()).m02(-camera.forwardX())
                .m10(camera.rightY()).m11(camera.upY()).m12(-camera.forwardY())
                .m20(camera.rightZ()).m21(camera.upZ()).m22(-camera.forwardZ())
                .m30(0.0F).m31(0.0F).m32(0.0F).m33(1.0F);
    }

    static Matrix4f currentClipToWorld(RtestFsrCamera camera) {
        return projectionForNrd(camera).mul(viewRotation(camera)).invert();
    }

    static Matrix4f previousWorldToView(RtestFsrCamera current, RtestFsrCamera previous) {
        return viewRotation(previous).translate(
                (float) (current.x() - previous.x()),
                (float) (current.y() - previous.y()),
                (float) (current.z() - previous.z()));
    }

    static Matrix4f previousWorldToClip(RtestFsrCamera current, RtestFsrCamera previous) {
        return projectionForNrd(previous).mul(previousWorldToView(current, previous));
    }
}
