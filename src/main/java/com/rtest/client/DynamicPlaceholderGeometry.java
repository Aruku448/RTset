package com.rtest.client;

/** Renderer-independent fixed-topology geometry for the first dynamic TLAS validation. */
public final class DynamicPlaceholderGeometry {
    private static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;

    public record Mesh(float[] vertices, float[] materialData) {
        public Mesh {
            if (vertices.length % 9 != 0 || materialData.length != vertices.length / 9 * MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("Placeholder mesh/material stride mismatch");
            }
        }

        public int triangleCount() { return vertices.length / 9; }
    }

    private DynamicPlaceholderGeometry() { }

    /** Creates a 12-triangle unit box with stable opaque/cutout material slots. */
    public static Mesh box(float red, float green, float blue, boolean cutout) {
        float[][] faces = {
            {-0.5F, -0.5F,  0.5F,  0.5F, -0.5F,  0.5F,  0.5F,  0.5F,  0.5F, -0.5F,  0.5F,  0.5F},
            { 0.5F, -0.5F, -0.5F, -0.5F, -0.5F, -0.5F, -0.5F,  0.5F, -0.5F,  0.5F,  0.5F, -0.5F},
            {-0.5F,  0.5F,  0.5F,  0.5F,  0.5F,  0.5F,  0.5F,  0.5F, -0.5F, -0.5F,  0.5F, -0.5F},
            {-0.5F, -0.5F, -0.5F,  0.5F, -0.5F, -0.5F,  0.5F, -0.5F,  0.5F, -0.5F, -0.5F,  0.5F},
            { 0.5F, -0.5F, -0.5F,  0.5F,  0.5F, -0.5F,  0.5F,  0.5F,  0.5F,  0.5F, -0.5F,  0.5F},
            {-0.5F, -0.5F,  0.5F, -0.5F,  0.5F,  0.5F, -0.5F,  0.5F, -0.5F, -0.5F, -0.5F, -0.5F}
        };
        float[] vertices = new float[6 * 2 * 3 * 3];
        float[] materials = new float[6 * 2 * MATERIAL_FLOATS_PER_TRIANGLE];
        int vertex = 0;
        int material = 0;
        for (float[] face : faces) {
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int index : order) {
                vertices[vertex++] = face[index * 3];
                vertices[vertex++] = face[index * 3 + 1];
                vertices[vertex++] = face[index * 3 + 2];
            }
            float ax = face[3] - face[0];
            float ay = face[4] - face[1];
            float az = face[5] - face[2];
            float bx = face[6] - face[0];
            float by = face[7] - face[1];
            float bz = face[8] - face[2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float length = (float)Math.sqrt(nx * nx + ny * ny + nz * nz);
            nx /= length; ny /= length; nz /= length;
            for (int triangle = 0; triangle < 2; triangle++) {
                materials[material++] = red;
                materials[material++] = green;
                materials[material++] = blue;
                materials[material++] = 1.0F;
                materials[material++] = nx;
                materials[material++] = ny;
                materials[material++] = nz;
                materials[material++] = 0.0F;
                materials[material++] = 0.0F; materials[material++] = 0.0F;
                materials[material++] = 1.0F; materials[material++] = 0.0F;
                materials[material++] = 0.0F; materials[material++] = 1.0F;
                materials[material++] = cutout ? 1.0F : 0.0F;
                // Placeholder geometry has no atlas texture; use its fixed tint directly.
                materials[material++] = 0.0F;
                materials[material++] = 0.12F; materials[material++] = 1.0F;
                materials[material++] = 1.0F; materials[material++] = 0.0F;
                materials[material++] = 0.82F; materials[material++] = 0.0F;
                materials[material++] = 0.0F; materials[material++] = cutout ? 0.04F : 0.04F;
                materials[material++] = 0.0F; materials[material++] = 0.0F;
                materials[material++] = 0.0F; materials[material++] = 1.0F;
            }
        }
        return new Mesh(vertices, materials);
    }
}
