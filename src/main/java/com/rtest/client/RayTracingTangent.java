package com.rtest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Encodes the UV tangent frame into the two unused lighting slots of the 7-vec4 material ABI. */
final class RayTracingTangent {
    private RayTracingTangent() {
    }

    record Frame(float angle, float handedness) {
        static final Frame INVALID = new Frame(0.0F, 0.0F);

        boolean valid() {
            return Math.abs(handedness) > 0.5F;
        }
    }

    static Vector3f geometricNormal(BakedQuad quad, int a, int b, int c) {
        // direction() is a six-way culling tag. Crossed foliage and rotated models can
        // have a diagonal plane, so its actual winding must define the shading frame.
        Vector3fc p0 = quad.position(a), p1 = quad.position(b), p2 = quad.position(c);
        float ax = p1.x() - p0.x(), ay = p1.y() - p0.y(), az = p1.z() - p0.z();
        float bx = p2.x() - p0.x(), by = p2.y() - p0.y(), bz = p2.z() - p0.z();
        Vector3f normal = new Vector3f(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx);
        float lengthSquared = normal.lengthSquared();
        if (Float.isFinite(lengthSquared) && lengthSquared > 1.0E-20F) {
            return normal.mul(1.0F / (float)Math.sqrt(lengthSquared));
        }
        return new Vector3f(quad.direction().getStepX(), quad.direction().getStepY(),
            quad.direction().getStepZ());
    }

    static Frame fromBakedQuad(BakedQuad quad, int a, int b, int c,
                               float normalX, float normalY, float normalZ) {
        Vector3fc p0 = quad.position(a);
        Vector3fc p1 = quad.position(b);
        Vector3fc p2 = quad.position(c);
        return fromTriangle(
            p0.x(), p0.y(), p0.z(),
            p1.x(), p1.y(), p1.z(),
            p2.x(), p2.y(), p2.z(),
            UVPair.unpackU(quad.packedUV(a)), UVPair.unpackV(quad.packedUV(a)),
            UVPair.unpackU(quad.packedUV(b)), UVPair.unpackV(quad.packedUV(b)),
            UVPair.unpackU(quad.packedUV(c)), UVPair.unpackV(quad.packedUV(c)),
            normalX, normalY, normalZ);
    }

    static Frame fromBakedQuad(PoseStack.Pose pose, BakedQuad quad, int a, int b, int c,
                               float normalX, float normalY, float normalZ) {
        Vector3f p0 = pose.pose().transformPosition(quad.position(a), new Vector3f());
        Vector3f p1 = pose.pose().transformPosition(quad.position(b), new Vector3f());
        Vector3f p2 = pose.pose().transformPosition(quad.position(c), new Vector3f());
        return fromTriangle(
            p0.x, p0.y, p0.z,
            p1.x, p1.y, p1.z,
            p2.x, p2.y, p2.z,
            UVPair.unpackU(quad.packedUV(a)), UVPair.unpackV(quad.packedUV(a)),
            UVPair.unpackU(quad.packedUV(b)), UVPair.unpackV(quad.packedUV(b)),
            UVPair.unpackU(quad.packedUV(c)), UVPair.unpackV(quad.packedUV(c)),
            normalX, normalY, normalZ);
    }

    static Frame fromTriangle(float p0x, float p0y, float p0z,
                              float p1x, float p1y, float p1z,
                              float p2x, float p2y, float p2z,
                              float u0, float v0, float u1, float v1,
                              float u2, float v2,
                              float normalX, float normalY, float normalZ) {
        float du1 = u1 - u0;
        float dv1 = v1 - v0;
        float du2 = u2 - u0;
        float dv2 = v2 - v0;
        float determinant = du1 * dv2 - du2 * dv1;
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-8F) {
            return Frame.INVALID;
        }

        float normalLength = (float)Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
        if (!Float.isFinite(normalLength) || normalLength < 1.0E-6F) {
            return Frame.INVALID;
        }
        float nx = normalX / normalLength;
        float ny = normalY / normalLength;
        float nz = normalZ / normalLength;
        float e1x = p1x - p0x;
        float e1y = p1y - p0y;
        float e1z = p1z - p0z;
        float e2x = p2x - p0x;
        float e2y = p2y - p0y;
        float e2z = p2z - p0z;
        float inverse = 1.0F / determinant;
        float tx = (e1x * dv2 - e2x * dv1) * inverse;
        float ty = (e1y * dv2 - e2y * dv1) * inverse;
        float tz = (e1z * dv2 - e2z * dv1) * inverse;
        float normalProjection = tx * nx + ty * ny + tz * nz;
        tx -= normalProjection * nx;
        ty -= normalProjection * ny;
        tz -= normalProjection * nz;
        float tangentLength = (float)Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!Float.isFinite(tangentLength) || tangentLength < 1.0E-6F) {
            return Frame.INVALID;
        }
        tx /= tangentLength;
        ty /= tangentLength;
        tz /= tangentLength;

        // The UV determinant alone assumes the supplied normal follows vertex winding.
        // Model normals and mirrored poses need not do so. Resolve +V from the actual
        // position/UV derivative, then compare it with the shader's cross(N, T).
        float bx = (e2x * du1 - e1x * du2) * inverse;
        float by = (e2y * du1 - e1y * du2) * inverse;
        float bz = (e2z * du1 - e1z * du2) * inverse;
        float orientation = (ny * tz - nz * ty) * bx
            + (nz * tx - nx * tz) * by + (nx * ty - ny * tx) * bz;
        if (!Float.isFinite(orientation) || orientation == 0.0F) {
            return Frame.INVALID;
        }

        // This is the same deterministic fallback frame used by the shader. Storing only
        // the rotation around the normal is enough because the normal already occupies xyz.
        float referenceX;
        float referenceY;
        float referenceZ;
        if (Math.abs(ny) < 0.999F) {
            referenceX = -nz;
            referenceY = 0.0F;
            referenceZ = nx;
        } else {
            referenceX = 0.0F;
            referenceY = nz;
            referenceZ = -ny;
        }
        float referenceLength = (float)Math.sqrt(
            referenceX * referenceX + referenceY * referenceY + referenceZ * referenceZ);
        referenceX /= referenceLength;
        referenceY /= referenceLength;
        referenceZ /= referenceLength;
        float bitangentX = ny * referenceZ - nz * referenceY;
        float bitangentY = nz * referenceX - nx * referenceZ;
        float bitangentZ = nx * referenceY - ny * referenceX;
        float cosine = tx * referenceX + ty * referenceY + tz * referenceZ;
        float sine = tx * bitangentX + ty * bitangentY + tz * bitangentZ;
        return new Frame((float)Math.atan2(sine, cosine), orientation < 0.0F ? -1.0F : 1.0F);
    }
}
