package com.rtest.client.fsr;

import org.joml.Matrix4f;

/** Camera contract shared by the ray generator, FSR and NRD temporal reprojection. */
public record RtestFsrCamera(
        float projectionM00,
        float projectionM11,
        double x,
        double y,
        double z,
        float forwardX,
        float forwardY,
        float forwardZ,
        float rightX,
        float rightY,
        float rightZ,
        float upX,
        float upY,
        float upZ,
        Matrix4f projection,
        Matrix4f viewRotation,
        Matrix4f inverseViewProjection) {
    /** Compatibility constructor for callers that only provide the ray-generator camera basis. */
    public RtestFsrCamera(
            float projectionM00,
            float projectionM11,
            double x,
            double y,
            double z,
            float forwardX,
            float forwardY,
            float forwardZ,
            float rightX,
            float rightY,
            float rightZ,
            float upX,
            float upY,
            float upZ) {
        this(projectionM00, projectionM11, x, y, z,
                forwardX, forwardY, forwardZ, rightX, rightY, rightZ, upX, upY, upZ,
                null, null, null);
    }

    public RtestFsrCamera {
        projection = projection == null ? null : new Matrix4f(projection);
        viewRotation = viewRotation == null ? null : new Matrix4f(viewRotation);
        inverseViewProjection = inverseViewProjection == null
                ? null : new Matrix4f(inverseViewProjection);
    }

    public static boolean isCut(RtestFsrCamera previous, RtestFsrCamera current) {
        if (previous == null || current == null) {
            return true;
        }
        double dx = current.x - previous.x;
        double dy = current.y - previous.y;
        double dz = current.z - previous.z;
        double movementSquared = dx * dx + dy * dy + dz * dz;
        float dot = current.forwardX * previous.forwardX
                + current.forwardY * previous.forwardY
                + current.forwardZ * previous.forwardZ;
        return movementSquared > 64.0 || dot < 0.65F
                || Math.abs(current.projectionM00 - previous.projectionM00) > 1.0e-4F
                || Math.abs(current.projectionM11 - previous.projectionM11) > 1.0e-4F;
    }
}
