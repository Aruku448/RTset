package com.rtest.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure fluid-surface geometry and material policy.  Minecraft adapters provide the
 * cell heights and sprite coordinates; this class deliberately has no renderer or
 * Vulkan dependencies, so its boundary rules can be tested independently.
 */
public final class FluidGeometryCapture {
    private FluidGeometryCapture() {
    }

    public record Surface(float roughness, float metallic, float emission, float reflectivity,
                          float ior, float absorptionR, float absorptionG, float absorptionB,
                          float opacity) {
        public Surface {
            if (!(ior >= 1.0F) || !(opacity > 0.0F && opacity <= 1.0F)) {
                throw new IllegalArgumentException("Invalid fluid optical properties");
            }
        }
    }

    public record Cell(float northWest, float northEast, float southWest, float southEast,
                       boolean topBoundary, boolean bottomBoundary, boolean[] sideBoundaries) {
        public Cell {
            if (sideBoundaries == null || sideBoundaries.length != 4) {
                throw new IllegalArgumentException("Four horizontal boundary flags are required");
            }
            sideBoundaries = sideBoundaries.clone();
        }

        @Override
        public boolean[] sideBoundaries() {
            return sideBoundaries.clone();
        }
    }

    public record Mesh(float[] vertices, float[] normals) {
        public Mesh {
            if (vertices.length != normals.length || vertices.length % 9 != 0) {
                throw new IllegalArgumentException("Fluid mesh arrays must contain complete triangles");
            }
        }

        public int triangleCount() {
            return vertices.length / 9;
        }
    }

    /** Generates local unit-cell triangles without Minecraft or Vulkan types. */
    public static Mesh generate(Cell cell) {
        List<Float> vertices = new ArrayList<>();
        List<Float> normals = new ArrayList<>();
        if (cell.topBoundary()) {
            quad(vertices, normals, 0.0F, cell.northWest(), 0.0F, 1.0F, cell.northEast(), 0.0F,
                1.0F, cell.southEast(), 1.0F, 0.0F, cell.southWest(), 1.0F, 0.0F, 1.0F, 0.0F);
        }
        if (cell.bottomBoundary()) {
            quad(vertices, normals, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, -1.0F, 0.0F);
        }
        boolean[] sides = cell.sideBoundaries();
        if (sides[0]) side(vertices, normals, 0.0F, cell.northWest(), cell.northEast(), 0.0F, -1.0F);
        if (sides[1]) side(vertices, normals, 1.0F, cell.southEast(), cell.southWest(), 0.0F, 1.0F);
        if (sides[2]) side(vertices, normals, 0.0F, cell.southWest(), cell.northWest(), 1.0F, -1.0F);
        if (sides[3]) side(vertices, normals, 1.0F, cell.northEast(), cell.southEast(), 1.0F, 1.0F);
        return new Mesh(toArray(vertices), toArray(normals));
    }

    private static void side(List<Float> vertices, List<Float> normals, float edge, float high0, float high1,
                             float axis, float normal) {
        if (axis == 0.0F) {
            quad(vertices, normals, 0.0F, high0, edge, 1.0F, high1, edge,
                1.0F, 0.0F, edge, 0.0F, 0.0F, edge, 0.0F, 0.0F, normal);
        } else {
            quad(vertices, normals, edge, high0, 1.0F, edge, high1, 0.0F,
                edge, 0.0F, 0.0F, edge, 0.0F, 1.0F, normal, 0.0F, 0.0F);
        }
    }

    private static void quad(List<Float> vertices, List<Float> normals,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz) {
        triangle(vertices, normals, x0, y0, z0, x1, y1, z1, x2, y2, z2, nx, ny, nz);
        triangle(vertices, normals, x0, y0, z0, x2, y2, z2, x3, y3, z3, nx, ny, nz);
    }

    private static void triangle(List<Float> vertices, List<Float> normals,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float x2, float y2, float z2, float nx, float ny, float nz) {
        for (float value : new float[] {x0, y0, z0, x1, y1, z1, x2, y2, z2}) vertices.add(value);
        for (int i = 0; i < 3; i++) { normals.add(nx); normals.add(ny); normals.add(nz); }
    }

    private static float[] toArray(List<Float> values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    /** Returns the number of triangles emitted by the boundary decisions. */
    public static int triangleCount(Cell cell) {
        int faces = (cell.topBoundary() ? 1 : 0) + (cell.bottomBoundary() ? 1 : 0);
        for (boolean boundary : cell.sideBoundaries()) {
            if (boundary) {
                faces++;
            }
        }
        return faces * 2;
    }

    /** Minecraft fluids are translucent surfaces, never alpha-cutout geometry. */
    public static boolean isTranslucent() {
        return true;
    }

    /** Conservative defaults for vanilla and unregistered/modded fluids. */
    public static Surface surface(String registryPath) {
        String name = registryPath == null ? "" : registryPath.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("lava")) {
            return new Surface(0.28F, 0.0F, 0.55F, 0.08F, 1.33F, 0.42F, 0.12F, 0.025F, 0.94F);
        }
        if (name.contains("water")) {
            // Extinction is per-channel loss, not the water's display tint: red must be
            // absorbed most, blue least. These values are the linear Rec.2020 equivalent of
            // Prime's default #3f76e4 water filter over its 16-block reference depth.
            return new Surface(0.12F, 0.0F, 0.0F, 0.04F, 1.333F, 0.13F, 0.108F, 0.021F, 0.68F);
        }
        // A mod fluid has no portable API for IOR/absorption in vanilla.  Treat it as
        // transmissive with safe water-like defaults; resource-specific adapters can refine it.
        return new Surface(0.2F, 0.0F, 0.0F, 0.04F, 1.333F, 0.03F, 0.03F, 0.03F, 0.8F);
    }

    public static boolean sameFluid(String firstId, String secondId) {
        return firstId != null && firstId.equals(secondId);
    }
}
