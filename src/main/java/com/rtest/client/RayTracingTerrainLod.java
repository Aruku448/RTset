package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Renderer-independent terrain LOD primitives. This class deliberately has no Minecraft,
 * Vulkan, or Voxy dependency; callers must provide a snapshot of a section's arrays.
 *
 * <p>Coordinates in {@link SectionInput#vertices()} are section-local and are translated by
 * the supplied origin. A level zero node is one 16^3 section; each increment doubles the
 * number of sections on every axis. The reduction is intentionally conservative: it keeps one
 * source triangle per spatial bin rather than inventing a watertight terrain surface. It is
 * therefore a coarse visibility fallback, not a replacement for a mesher or collision data.</p>
 */
public final class RayTracingTerrainLod {
    public static final int SECTION_SIZE = 16;
    public static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    private static final int BINS_PER_AXIS = 8;
    private static final float AREA_EPSILON = 1.0e-10F;

    /** Stable coordinates in section space, not block space. */
    public record NodeKey(int level, int x, int y, int z) {
        public NodeKey {
            if (level < 0 || level > 20) {
                throw new IllegalArgumentException("level must be in [0, 20]");
            }
        }

        public int sectionsPerAxis() { return 1 << level; }
        public int worldSize() { return SECTION_SIZE << level; }
    }

    /** World-space, half-open node bounds. */
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds {
            if (!(maxX > minX && maxY > minY && maxZ > minZ)) {
                throw new IllegalArgumentException("Invalid terrain node bounds");
            }
        }
    }

    /** Immutable snapshot boundary; the arrays are copied both on input and on access. */
    public record SectionInput(int originX, int originY, int originZ,
                               float[] vertices, float[] materialData) {
        public SectionInput {
            if (vertices == null || materialData == null || vertices.length % 9 != 0
                    || materialData.length != vertices.length / 9 * MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("Section arrays do not match the terrain ABI");
            }
            vertices = vertices.clone();
            materialData = materialData.clone();
        }

        @Override public float[] vertices() { return vertices.clone(); }
        @Override public float[] materialData() { return materialData.clone(); }
    }

    /** Opaque coarse data ready for a renderer-independent upload. */
    public static final class Mesh {
        private final float[] vertices;
        private final float[] materialData;
        private final long fingerprint;

        private Mesh(float[] vertices, float[] materialData) {
            this.vertices = vertices.clone();
            this.materialData = materialData.clone();
            this.fingerprint = RayTracingTerrainLod.fingerprint(this.vertices, this.materialData);
        }

        public float[] vertices() { return vertices.clone(); }
        public float[] materialData() { return materialData.clone(); }
        public int triangleCount() { return vertices.length / 9; }
        public long fingerprint() { return fingerprint; }
    }

    /** A node is ready iff its coarse mesh is non-null; null is useful while an async build runs. */
    public static final class Node {
        private final NodeKey key;
        private final Bounds bounds;
        private final Mesh mesh;

        public Node(NodeKey key, Mesh mesh) {
            this.key = key;
            this.bounds = boundsFor(key);
            this.mesh = mesh;
        }

        public NodeKey key() { return key; }
        public Bounds bounds() { return bounds; }
        public Mesh mesh() { return mesh; }
        public boolean ready() { return mesh != null && mesh.triangleCount() > 0; }
    }

    public enum GeometryChoice { NATIVE, COARSE }

    private RayTracingTerrainLod() { }

    public static Bounds boundsFor(NodeKey key) {
        int size = key.worldSize();
        return new Bounds((double) key.x() * size, (double) key.y() * size, (double) key.z() * size,
                (double) (key.x() + 1L) * size, (double) (key.y() + 1L) * size,
                (double) (key.z() + 1L) * size);
    }

    /** Builds a level node from an immutable section snapshot collection. */
    public static Node buildNode(NodeKey key, Iterable<SectionInput> sections) {
        if (sections == null) throw new IllegalArgumentException("sections is null");
        Bounds bounds = boundsFor(key);
        List<Candidate> candidates = new ArrayList<>();
        int size = key.worldSize();
        int minSectionX = Math.multiplyExact(key.x(), key.sectionsPerAxis());
        int minSectionY = Math.multiplyExact(key.y(), key.sectionsPerAxis());
        int minSectionZ = Math.multiplyExact(key.z(), key.sectionsPerAxis());
        for (SectionInput section : sections) {
            int sx = Math.floorDiv(section.originX(), SECTION_SIZE);
            int sy = Math.floorDiv(section.originY(), SECTION_SIZE);
            int sz = Math.floorDiv(section.originZ(), SECTION_SIZE);
            if (sx < minSectionX || sy < minSectionY || sz < minSectionZ
                    || sx >= minSectionX + key.sectionsPerAxis()
                    || sy >= minSectionY + key.sectionsPerAxis()
                    || sz >= minSectionZ + key.sectionsPerAxis()) continue;
            addCandidates(candidates, section, bounds, size);
        }
        Candidate[] selected = new Candidate[BINS_PER_AXIS * BINS_PER_AXIS * BINS_PER_AXIS];
        for (Candidate candidate : candidates) {
            int bin = bin(candidate.cx, candidate.cy, candidate.cz, bounds);
            Candidate old = selected[bin];
            if (old == null || candidate.area > old.area || (candidate.area == old.area && candidate.tie < old.tie)) {
                selected[bin] = candidate;
            }
        }
        List<Candidate> ordered = new ArrayList<>();
        for (Candidate candidate : selected) if (candidate != null) ordered.add(candidate);
        ordered.sort(Comparator.comparingInt(candidate -> candidate.bin));
        float[] vertices = new float[ordered.size() * 9];
        float[] materials = new float[ordered.size() * MATERIAL_FLOATS_PER_TRIANGLE];
        int vo = 0, mo = 0;
        for (Candidate candidate : ordered) {
            System.arraycopy(candidate.vertices, 0, vertices, vo, 9);
            System.arraycopy(candidate.material, 0, materials, mo, MATERIAL_FLOATS_PER_TRIANGLE);
            vo += 9;
            mo += MATERIAL_FLOATS_PER_TRIANGLE;
        }
        return new Node(key, new Mesh(vertices, materials));
    }

    public static Node buildNode(NodeKey key, SectionInput... sections) {
        return buildNode(key, Arrays.asList(sections.clone()));
    }

    /** Distance is measured in XZ only. Hysteresis requires enterDistance >= exitDistance. */
    public static GeometryChoice select(Node node, double cameraX, double cameraZ,
                                        double enterDistance, double exitDistance,
                                        GeometryChoice previous) {
        if (enterDistance < exitDistance || exitDistance < 0 || !Double.isFinite(enterDistance)) {
            throw new IllegalArgumentException("Invalid LOD hysteresis distances");
        }
        if (node == null || !node.ready()) return GeometryChoice.NATIVE;
        Bounds b = node.bounds();
        double dx = distanceToInterval(cameraX, b.minX(), b.maxX());
        double dz = distanceToInterval(cameraZ, b.minZ(), b.maxZ());
        double distance = Math.hypot(dx, dz);
        if (previous == GeometryChoice.COARSE) return distance <= exitDistance ? GeometryChoice.NATIVE : GeometryChoice.COARSE;
        return distance >= enterDistance ? GeometryChoice.COARSE : GeometryChoice.NATIVE;
    }

    private static double distanceToInterval(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0.0;
    }

    private static void addCandidates(List<Candidate> out, SectionInput section, Bounds bounds, int size) {
        float[] inputVertices = section.vertices();
        float[] inputMaterials = section.materialData();
        for (int triangle = 0; triangle < inputVertices.length / 9; triangle++) {
            int vo = triangle * 9, mo = triangle * MATERIAL_FLOATS_PER_TRIANGLE;
            float[] v = new float[9];
            boolean finite = true;
            for (int i = 0; i < 9; i++) {
                v[i] = inputVertices[vo + i] + (i % 3 == 0 ? section.originX() : i % 3 == 1 ? section.originY() : section.originZ());
                finite &= Float.isFinite(v[i]);
            }
            float[] material = Arrays.copyOfRange(inputMaterials, mo, mo + MATERIAL_FLOATS_PER_TRIANGLE);
            for (float value : material) finite &= Float.isFinite(value);
            if (!finite || !opaque(material)) continue;
            float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
            float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float area2 = nx * nx + ny * ny + nz * nz;
            if (!(area2 > AREA_EPSILON) || !Float.isFinite(area2)) continue;
            out.add(new Candidate(v, material, (v[0] + v[3] + v[6]) / 3F,
                    (v[1] + v[4] + v[7]) / 3F, (v[2] + v[5] + v[8]) / 3F,
                    area2, stableTriangleKey(v, material), bin((v[0] + v[3] + v[6]) / 3F,
                    (v[1] + v[4] + v[7]) / 3F, (v[2] + v[5] + v[8]) / 3F, bounds)));
        }
    }

    private static boolean opaque(float[] m) {
        // Seven vec4 records: tint, normal, uv01, uv2, lighting, surface, optical.
        // The ABI stores opacity at 3, emission at surface.z (22), absorption at 24..26,
        // and IOR at optical.x (27). Metallic/reflectivity are valid opaque properties and
        // must not be mistaken for transmission. Alpha-test/cutout remains eligible.
        return m[3] >= 0.999F && m[22] <= 1.0e-6F
                && Math.abs(m[24]) <= 1.0e-6F && Math.abs(m[25]) <= 1.0e-6F
                && Math.abs(m[26]) <= 1.0e-6F && Math.abs(m[27] - 1.0F) <= 1.0e-4F;
    }

    private static int bin(float x, float y, float z, Bounds b) {
        int ix = binAxis(x, b.minX(), b.maxX()), iy = binAxis(y, b.minY(), b.maxY()), iz = binAxis(z, b.minZ(), b.maxZ());
        return (iy * BINS_PER_AXIS + iz) * BINS_PER_AXIS + ix;
    }

    private static int binAxis(float value, double min, double max) {
        int result = (int) Math.floor((value - min) / (max - min) * BINS_PER_AXIS);
        return Math.max(0, Math.min(BINS_PER_AXIS - 1, result));
    }

    private static long fingerprint(float[] vertices, float[] materials) {
        long hash = 0xcbf29ce484222325L;
        for (float value : vertices) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        for (float value : materials) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        return hash;
    }

    private static long stableTriangleKey(float[] vertices, float[] material) {
        long hash = 0xcbf29ce484222325L;
        for (float value : vertices) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        for (float value : material) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        return hash;
    }

    private record Candidate(float[] vertices, float[] material, float cx, float cy, float cz,
                             float area, long tie, int bin) { }
}
