package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renderer-independent terrain LOD primitives. This class deliberately has no Minecraft,
 * Vulkan, or Voxy dependency; callers provide immutable snapshots of native sections.
 *
 * <p>The hierarchy uses the same spatial convention as Voxy: level 0 is one 16^3 section,
 * level 1 is 32^3 blocks, and level 2 is 64^3 blocks. Parent meshes are always generated from
 * the original {@link SectionInput} values. A parent is never made by reducing a child mesh.</p>
 */
public final class RayTracingTerrainLod {
    public static final int SECTION_SIZE = 16;
    public static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    public static final int MAX_HIERARCHY_LEVEL = 2;
    private static final int BINS_PER_AXIS = 8;
    private static final float AREA_EPSILON = 1.0e-10F;

    /** Stable coordinates in the level's node grid, not block coordinates. */
    public record NodeKey(int level, int x, int y, int z) {
        public NodeKey {
            if (level < 0 || level > MAX_HIERARCHY_LEVEL) {
                throw new IllegalArgumentException("level must be in [0, " + MAX_HIERARCHY_LEVEL + "]");
            }
        }

        public int sectionsPerAxis() { return 1 << level; }
        public int worldSize() { return SECTION_SIZE << level; }

        /** A canonical, storage-independent identity useful for logs and request keys. */
        public String stableId() { return level + ":" + x + ":" + y + ":" + z; }

        public NodeKey parent() {
            return level >= MAX_HIERARCHY_LEVEL ? null : new NodeKey(level + 1,
                Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
        }

        public NodeKey child(int childX, int childY, int childZ) {
            if (level <= 0 || childX < 0 || childX > 1
                    || childY < 0 || childY > 1 || childZ < 0 || childZ > 1) {
                throw new IllegalArgumentException("invalid hierarchy child");
            }
            return new NodeKey(level - 1, x * 2 + childX, y * 2 + childY, z * 2 + childZ);
        }
    }

    /** World-space, half-open node bounds. */
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Bounds {
            if (!(maxX > minX && maxY > minY && maxZ > minZ)) {
                throw new IllegalArgumentException("Invalid terrain node bounds");
            }
        }

        public double diagonal() {
            return Math.sqrt((maxX - minX) * (maxX - minX)
                + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ));
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

    /** A node is ready iff its immutable coarse mesh is non-null and non-empty. */
    public static final class Node {
        private final NodeKey key;
        private final Bounds bounds;
        private final Mesh mesh;

        public Node(NodeKey key, Mesh mesh) {
            this.key = Objects.requireNonNull(key, "key");
            this.bounds = boundsFor(key);
            this.mesh = mesh;
        }

        public NodeKey key() { return key; }
        public Bounds bounds() { return bounds; }
        public Mesh mesh() { return mesh; }
        public boolean ready() { return mesh != null && mesh.triangleCount() > 0; }

        /** Rehydrates an immutable node from a proxy-store payload without exposing Mesh's constructor. */
        public static Node fromMesh(NodeKey key, float[] vertices, float[] materialData) {
            return new Node(key, new Mesh(vertices, materialData));
        }
    }

    public enum GeometryChoice { NATIVE, COARSE }

    /** Camera data used by the pure selection contract; no renderer or Minecraft types leak in. */
    public record View(double cameraX, double cameraY, double cameraZ,
                       double viewportHeight, double verticalFovRadians) {
        public View {
            if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)
                    || !(viewportHeight > 0.0) || !Double.isFinite(viewportHeight)
                    || !(verticalFovRadians > 0.0 && verticalFovRadians < Math.PI)
                    || !Double.isFinite(verticalFovRadians)) {
                throw new IllegalArgumentException("invalid LOD view");
            }
        }
    }

    /** Distance and projected-size thresholds. Enter values are deliberately more conservative. */
    public record Hysteresis(double enterDistance, double exitDistance,
                             double enterScreenError, double exitScreenError) {
        public Hysteresis {
            if (!Double.isFinite(enterDistance) || !Double.isFinite(exitDistance)
                    || enterDistance < exitDistance || exitDistance < 0.0
                    || !Double.isFinite(enterScreenError) || !Double.isFinite(exitScreenError)
                    || enterScreenError < exitScreenError || exitScreenError < 0.0) {
                throw new IllegalArgumentException("invalid LOD hysteresis thresholds");
            }
        }
    }

    /** Pure metrics exposed so callers can inspect or replace the renderer's selection policy. */
    public record ErrorMetric(double distance, double screenError) {
        public ErrorMetric {
            if (!Double.isFinite(distance) || distance < 0.0
                    || !Double.isFinite(screenError) || screenError < 0.0) {
                throw new IllegalArgumentException("invalid LOD error metric");
            }
        }
    }

    /** Result of hierarchy selection. Sets are immutable and never contain overlapping levels. */
    public record Selection(Set<NodeKey> nodes, Set<NodeKey> nativeFallbacks) {
        public Selection {
            nodes = Set.copyOf(nodes);
            nativeFallbacks = Set.copyOf(nativeFallbacks);
            for (NodeKey fallback : nativeFallbacks) {
                if (fallback.level() != 0) {
                    throw new IllegalArgumentException("native fallbacks must be level 0");
                }
            }
        }

        public boolean contains(NodeKey key) { return nodes.contains(key); }
        public boolean usesNativeFallback(NodeKey key) { return nativeFallbacks.contains(key); }
    }

    /** Immutable multi-level hierarchy. It is safe to publish between a worker and render thread. */
    public static final class Hierarchy {
        private final Map<NodeKey, Node> nodes;
        private final Set<NodeKey> roots;

        private Hierarchy(Map<NodeKey, Node> nodes) {
            this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            LinkedHashSet<NodeKey> rootKeys = new LinkedHashSet<>();
            for (NodeKey key : nodes.keySet()) {
                boolean hasAvailableAncestor = false;
                for (NodeKey ancestor = key.parent(); ancestor != null; ancestor = ancestor.parent()) {
                    if (nodes.containsKey(ancestor)) {
                        hasAvailableAncestor = true;
                        break;
                    }
                }
                if (!hasAvailableAncestor) rootKeys.add(key);
            }
            this.roots = Set.copyOf(rootKeys);
        }

        public Map<NodeKey, Node> nodes() { return nodes; }
        public Set<NodeKey> roots() { return roots; }
        public Node node(NodeKey key) { return nodes.get(key); }
        public boolean contains(NodeKey key) { return nodes.containsKey(key); }

        /** Returns available direct children, including a level-0 child across a missing level. */
        public List<NodeKey> children(NodeKey parent) {
            List<NodeKey> result = new ArrayList<>();
            for (NodeKey key : nodes.keySet()) {
                if (key.level() == parent.level() - 1 && isDescendantOf(key, parent)) {
                    result.add(key);
                }
            }
            if (!result.isEmpty() || parent.level() == 0) return List.copyOf(result);
            // A streamed hierarchy may omit an intermediate node. Expose the nearest available
            // descendants so an unavailable parent can still fall back to native sections.
            int nearestLevel = -1;
            for (NodeKey key : nodes.keySet()) {
                if (key.level() < parent.level() && isDescendantOf(key, parent)
                        && key.level() > nearestLevel) {
                    nearestLevel = key.level();
                }
            }
            if (nearestLevel >= 0) {
                for (NodeKey key : nodes.keySet()) {
                    if (key.level() == nearestLevel && isDescendantOf(key, parent)) result.add(key);
                }
            }
            return List.copyOf(result);
        }

        /** Selects a mutually exclusive cut through the hierarchy. */
        public Selection select(View view, Hysteresis hysteresis) {
            return select(view, hysteresis, Set.of());
        }

        public Selection select(View view, Hysteresis hysteresis, Set<NodeKey> previous) {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(hysteresis, "hysteresis");
            Set<NodeKey> prior = previous == null ? Set.of() : Set.copyOf(previous);
            LinkedHashSet<NodeKey> selected = new LinkedHashSet<>();
            LinkedHashSet<NodeKey> nativeFallbacks = new LinkedHashSet<>();
            for (NodeKey root : roots) {
                selectFrom(root, view, hysteresis, prior, selected, nativeFallbacks);
            }
            return new Selection(selected, nativeFallbacks);
        }

        private void selectFrom(NodeKey key, View view, Hysteresis hysteresis, Set<NodeKey> previous,
                                Set<NodeKey> selected, Set<NodeKey> nativeFallbacks) {
            Node node = nodes.get(key);
            List<NodeKey> children = children(key);
            boolean descendantsReady = children.stream().anyMatch(child -> hasReadyDescendant(child));
            boolean keepParent = node != null && node.ready()
                && (!descendantsReady || shouldKeepCoarse(node, view, hysteresis, previous.contains(key)));
            if (keepParent) {
                selected.add(key);
                return;
            }
            if (!children.isEmpty()) {
                for (NodeKey child : children) {
                    selectFrom(child, view, hysteresis, previous, selected, nativeFallbacks);
                }
                return;
            }
            if (node != null && node.ready()) {
                selected.add(key);
            } else if (key.level() == 0) {
                nativeFallbacks.add(key);
            }
        }

        private boolean hasReadyDescendant(NodeKey key) {
            Node node = nodes.get(key);
            if (node != null && node.ready()) return true;
            for (NodeKey child : children(key)) {
                if (hasReadyDescendant(child)) return true;
            }
            return false;
        }

        private static boolean shouldKeepCoarse(Node node, View view, Hysteresis hysteresis,
                                                boolean wasSelected) {
            ErrorMetric metric = error(node, view);
            if (wasSelected) {
                return metric.distance() >= hysteresis.exitDistance()
                    && metric.screenError() <= hysteresis.exitScreenError();
            }
            return metric.distance() >= hysteresis.enterDistance()
                && metric.screenError() <= hysteresis.enterScreenError();
        }
    }

    private RayTracingTerrainLod() { }

    public static Bounds boundsFor(NodeKey key) {
        int size = key.worldSize();
        return new Bounds((double) key.x() * size, (double) key.y() * size, (double) key.z() * size,
                (double) (key.x() + 1L) * size, (double) (key.y() + 1L) * size,
                (double) (key.z() + 1L) * size);
    }

    /** Computes distance and projected node size without depending on a graphics API. */
    public static ErrorMetric error(Node node, View view) {
        Bounds b = node.bounds();
        double dx = distanceToInterval(view.cameraX(), b.minX(), b.maxX());
        double dy = distanceToInterval(view.cameraY(), b.minY(), b.maxY());
        double dz = distanceToInterval(view.cameraZ(), b.minZ(), b.maxZ());
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double focalLength = view.viewportHeight() / (2.0 * Math.tan(view.verticalFovRadians() / 2.0));
        return new ErrorMetric(distance, b.diagonal() * focalLength / Math.max(distance, 1.0e-6));
    }

    /** Builds a level node from an immutable section snapshot collection. */
    public static Node buildNode(NodeKey key, Iterable<SectionInput> sections) {
        if (sections == null) throw new IllegalArgumentException("sections is null");
        Bounds bounds = boundsFor(key);
        List<Candidate> candidates = new ArrayList<>();
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
            addCandidates(candidates, section, bounds);
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

    /**
     * Builds level 0, 1 and 2 nodes from the same raw section collection. Every parent call is
     * made with its own raw group, making accidental child-to-parent mip chains impossible.
     */
    public static Hierarchy buildHierarchy(SectionInput... sourceSections) {
        return buildHierarchy(Arrays.asList(sourceSections.clone()));
    }

    public static Hierarchy buildHierarchy(Iterable<SectionInput> sourceSections) {
        if (sourceSections == null) throw new IllegalArgumentException("sourceSections is null");
        List<SectionInput> source = new ArrayList<>();
        for (SectionInput section : sourceSections) source.add(Objects.requireNonNull(section, "section"));
        Map<NodeKey, List<SectionInput>> grouped = new LinkedHashMap<>();
        for (SectionInput section : source) {
            int sx = Math.floorDiv(section.originX(), SECTION_SIZE);
            int sy = Math.floorDiv(section.originY(), SECTION_SIZE);
            int sz = Math.floorDiv(section.originZ(), SECTION_SIZE);
            for (int level = 0; level <= MAX_HIERARCHY_LEVEL; level++) {
                NodeKey key = new NodeKey(level, Math.floorDiv(sx, 1 << level),
                    Math.floorDiv(sy, 1 << level), Math.floorDiv(sz, 1 << level));
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(section);
            }
        }
        Map<NodeKey, Node> nodes = new LinkedHashMap<>();
        for (Map.Entry<NodeKey, List<SectionInput>> entry : grouped.entrySet()) {
            nodes.put(entry.getKey(), buildNode(entry.getKey(), entry.getValue()));
        }
        return new Hierarchy(nodes);
    }

    /** Creates a partial/stale publication for tests and streaming clients. */
    public static Hierarchy fromNodes(Iterable<Node> publishedNodes) {
        if (publishedNodes == null) throw new IllegalArgumentException("publishedNodes is null");
        Map<NodeKey, Node> nodes = new LinkedHashMap<>();
        for (Node node : publishedNodes) {
            Objects.requireNonNull(node, "node");
            nodes.put(node.key(), node);
        }
        return new Hierarchy(nodes);
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

    private static boolean isDescendantOf(NodeKey child, NodeKey ancestor) {
        if (child.level() >= ancestor.level()) return false;
        NodeKey current = child.parent();
        while (current != null && current.level() <= ancestor.level()) {
            if (current.equals(ancestor)) return true;
            current = current.parent();
        }
        return false;
    }

    private static double distanceToInterval(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0.0;
    }

    private static void addCandidates(List<Candidate> out, SectionInput section, Bounds bounds) {
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
            float cx = (v[0] + v[3] + v[6]) / 3F;
            float cy = (v[1] + v[4] + v[7]) / 3F;
            float cz = (v[2] + v[5] + v[8]) / 3F;
            out.add(new Candidate(v, material, cx, cy, cz, area2, stableTriangleKey(v, material),
                bin(cx, cy, cz, bounds)));
        }
    }

    private static boolean opaque(float[] m) {
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
