package com.rtest.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * level 1 is 32^3 blocks, and level 2 is 64^3 blocks. Coarse nodes share cached
 * section-local surface voxels. Parent samples reduce those voxels, never a child mesh.
 * Surface triangles are conservatively voxelized into occupied cells, then meshed at
 * occupied/empty transitions. This closes the old single-plane proxies; it is not a full
 * block-state mip chain because the snapshot boundary contains surfaces, not solid volumes.</p>
 */
public final class RayTracingTerrainLod {
    public static final int SECTION_SIZE = 16;
    public static final int MATERIAL_FLOATS_PER_TRIANGLE = 28;
    public static final int MAX_HIERARCHY_LEVEL = 2;
    private static final int LEVEL_ONE_BINS_PER_AXIS = 8;
    private static final int LEVEL_TWO_BINS_PER_AXIS = 8;
    private static final int SECTION_VOXELS_PER_AXIS = 4;
    private static final float AREA_EPSILON = 1.0e-10F;
    private static final int[] NORMAL_WINDING = {0, 1, 2, 0, 2, 3};
    private static final int[] REVERSED_WINDING = {0, 3, 2, 0, 2, 1};

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

    /**
     * Returns whether a native level-0 section must remain in the GPU traversal candidate set.
     *
     * <p>The GPU path needs a conservative fallback while a coarse proxy is missing. Once a
     * ready coarse ancestor exists, keeping every covered native leaf in the TLAS defeats the
     * purpose of GPU traversal: the instance mask can hide the leaf, but the TLAS still pays for
     * it on every build and ray. Near the camera we retain native sections for detail.</p>
     */
    public static boolean keepGpuNativeLeaf(NodeKey leaf, Set<NodeKey> coarseNodes,
                                            double distanceToCamera, double nativeRadius) {
        if (leaf == null || leaf.level() != 0 || coarseNodes == null
                || !Double.isFinite(distanceToCamera) || distanceToCamera < 0.0
                || !Double.isFinite(nativeRadius) || nativeRadius < 0.0) {
            throw new IllegalArgumentException("invalid GPU native leaf filter input");
        }
        if (distanceToCamera <= nativeRadius) return true;
        for (NodeKey ancestor = leaf.parent(); ancestor != null; ancestor = ancestor.parent()) {
            if (coarseNodes.contains(ancestor)) return false;
        }
        return true;
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

    /** Immutable snapshot boundary; public callers receive defensive copies. */
    public static final class SectionInput {
        private final int originX;
        private final int originY;
        private final int originZ;
        private final float[] vertices;
        private final float[] materialData;
        private final long contentFingerprint;

        public SectionInput(int originX, int originY, int originZ,
                            float[] vertices, float[] materialData) {
            this(originX, originY, originZ, vertices, materialData, true, 0, false);
        }

        private SectionInput(int originX, int originY, int originZ,
                             float[] vertices, float[] materialData, boolean copyArrays,
                             long knownContentFingerprint, boolean hasKnownContentFingerprint) {
            if (vertices == null || materialData == null || vertices.length % 9 != 0
                    || materialData.length != vertices.length / 9 * MATERIAL_FLOATS_PER_TRIANGLE) {
                throw new IllegalArgumentException("Section arrays do not match the terrain ABI");
            }
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.vertices = copyArrays ? vertices.clone() : vertices;
            this.materialData = copyArrays ? materialData.clone() : materialData;
            this.contentFingerprint = hasKnownContentFingerprint
                ? knownContentFingerprint
                : combineHashes(this.vertices, this.materialData);
        }

        /** Trusted package path for immutable SectionGeometry arrays; no multi-GB copy. */
        static SectionInput viewOf(int originX, int originY, int originZ,
                                   float[] vertices, float[] materialData,
                                   long contentFingerprint) {
            return new SectionInput(originX, originY, originZ, vertices, materialData,
                false, contentFingerprint, true);
        }

        public int originX() { return originX; }
        public int originY() { return originY; }
        public int originZ() { return originZ; }
        public float[] vertices() { return vertices.clone(); }
        public float[] materialData() { return materialData.clone(); }
        float[] verticesView() { return vertices; }
        float[] materialDataView() { return materialData; }
        long contentFingerprint() { return contentFingerprint; }

        private static long combineHashes(float[] vertices, float[] materialData) {
            return ((long)Arrays.hashCode(vertices) << 32)
                ^ (Arrays.hashCode(materialData) & 0xffffffffL);
        }
    }

    /** Opaque coarse data ready for a renderer-independent upload. */
    public static final class Mesh {
        private final float[] vertices;
        private final float[] materialData;
        private final long fingerprint;

        private Mesh(float[] vertices, float[] materialData) {
            this(vertices, materialData, true);
        }

        private Mesh(float[] vertices, float[] materialData, boolean copy) {
            this.vertices = copy ? vertices.clone() : vertices;
            this.materialData = copy ? materialData.clone() : materialData;
            this.fingerprint = RayTracingTerrainLod.fingerprint(this.vertices, this.materialData);
        }

        public float[] vertices() { return vertices.clone(); }
        public float[] materialData() { return materialData.clone(); }
        public int triangleCount() { return vertices.length / 9; }
        public long fingerprint() { return fingerprint; }
        public long byteSize() { return ((long)vertices.length + materialData.length) * Float.BYTES; }
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
        private final Map<NodeKey, List<NodeKey>> childrenByParent;
        private final Set<NodeKey> roots;

        private Hierarchy(Map<NodeKey, Node> nodes) {
            this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            Map<NodeKey, List<NodeKey>> directChildren = new LinkedHashMap<>();
            Map<NodeKey, List<NodeKey>> descendants = new LinkedHashMap<>();
            for (NodeKey key : nodes.keySet()) {
                NodeKey parent = key.parent();
                if (parent == null) continue;
                directChildren.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(key);
                for (NodeKey ancestor = parent; ancestor != null; ancestor = ancestor.parent()) {
                    descendants.computeIfAbsent(ancestor, ignored -> new ArrayList<>()).add(key);
                }
            }
            Map<NodeKey, List<NodeKey>> indexedChildren = new LinkedHashMap<>();
            for (NodeKey key : nodes.keySet()) {
                List<NodeKey> direct = directChildren.get(key);
                if (direct != null) {
                    indexedChildren.put(key, List.copyOf(direct));
                    continue;
                }
                // A streamed hierarchy may omit an intermediate node. Pre-index its nearest
                // available descendants once instead of rescanning every node during selection.
                List<NodeKey> availableDescendants = descendants.get(key);
                if (availableDescendants == null || key.level() == 0) {
                    indexedChildren.put(key, List.of());
                    continue;
                }
                int nearestLevel = -1;
                for (NodeKey descendant : availableDescendants) {
                    if (descendant.level() < key.level()) {
                        nearestLevel = Math.max(nearestLevel, descendant.level());
                    }
                }
                if (nearestLevel < 0) {
                    indexedChildren.put(key, List.of());
                } else {
                    List<NodeKey> nearest = new ArrayList<>();
                    for (NodeKey descendant : availableDescendants) {
                        if (descendant.level() == nearestLevel) nearest.add(descendant);
                    }
                    indexedChildren.put(key, List.copyOf(nearest));
                }
            }
            this.childrenByParent = Collections.unmodifiableMap(indexedChildren);
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
            return childrenByParent.getOrDefault(parent, List.of());
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

    /** Builds a node without retaining a worker cache after this call. */
    public static Node buildNode(NodeKey key, Iterable<SectionInput> sections) {
        return new Builder().buildNode(key, sections);
    }

    /** Worker-confined cache. Its budget includes source arrays retained by samples. */
    public static final class Builder {
        private static final long CACHE_BYTES = 64L << 20;
        private static final int CACHE_ENTRIES = 1024;
        private final LinkedHashMap<SectionCacheKey, SectionVoxels> cache =
            new LinkedHashMap<>(64, 0.75F, true);
        private final Samples scratch = new Samples(8);
        private final MeshScratch meshScratch = new MeshScratch();
        private volatile long retainedBytes;
        private volatile long cacheHits;
        private volatile long cacheMisses;

        public long cacheHits() { return cacheHits; }
        public long cacheMisses() { return cacheMisses; }
        public long retainedBytes() { return retainedBytes; }

        public void clear() {
            cache.clear();
            retainedBytes = 0;
        }

        public Node buildNode(NodeKey key, Iterable<SectionInput> sections) {
            return buildNode(key, sections, () -> false);
        }

        public Node buildNode(NodeKey key, Iterable<SectionInput> sections,
                              java.util.function.BooleanSupplier cancelled) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(sections, "sections");
            Bounds bounds = boundsFor(key);
            scratch.clear();
            try {
                for (SectionInput section : sections) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("LOD worker stopped");
                    }
                    int sx = Math.floorDiv(section.originX(), SECTION_SIZE);
                    int sy = Math.floorDiv(section.originY(), SECTION_SIZE);
                    int sz = Math.floorDiv(section.originZ(), SECTION_SIZE);
                    if (Math.floorDiv(sx, key.sectionsPerAxis()) != key.x()
                            || Math.floorDiv(sy, key.sectionsPerAxis()) != key.y()
                            || Math.floorDiv(sz, key.sectionsPerAxis()) != key.z()) continue;
                    if (key.level() == 0) {
                        selectCandidates(section, bounds, binsPerAxis(key), scratch.sections,
                            scratch.triangles, scratch.areas, scratch.ties, cancelled);
                    } else {
                        // Four-block sampling occurs once per native snapshot. Level two
                        // reuses its 2x2x2 reduction, including hidden directional samples.
                        SectionVoxels voxels = sectionVoxels(section, cancelled);
                        if (voxels.spansSection) {
                            // Custom models may extend outside their owning section. Keep
                            // their original coverage rather than clipping at the cache tile.
                            selectCandidates(section, bounds, binsPerAxis(key), scratch.sections,
                                scratch.triangles, scratch.areas, scratch.ties, cancelled);
                            continue;
                        }
                        Samples input = key.level() == 1 ? voxels.fine : voxels.coarse;
                        int width = key.level() == 1 ? 4 : 2;
                        int ox = Math.floorMod(sx, key.sectionsPerAxis()) * width;
                        int oy = Math.floorMod(sy, key.sectionsPerAxis()) * width;
                        int oz = Math.floorMod(sz, key.sectionsPerAxis()) * width;
                        for (int y = 0; y < width; y++) {
                            for (int z = 0; z < width; z++) {
                                for (int x = 0; x < width; x++) {
                                    int from = ((y * width + z) * width + x) * 7;
                                    int to = (((oy + y) * 8 + oz + z) * 8 + ox + x) * 7;
                                    for (int face = 0; face < 7; face++) {
                                        if (input.sections[from + face] != null) {
                                            selectSample(to + face, input.sections[from + face],
                                                input.triangles[from + face], input.areas[from + face],
                                                input.ties[from + face], scratch.sections,
                                                scratch.triangles, scratch.areas, scratch.ties);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return meshNode(key, bounds, scratch, meshScratch);
            } finally {
                // Do not pin the most recently built node's source snapshots in scratch.
                Arrays.fill(scratch.sections, null);
            }
        }

        private SectionVoxels sectionVoxels(SectionInput section, java.util.function.BooleanSupplier cancelled) {
            var key = new SectionCacheKey(section.originX(), section.originY(), section.originZ(),
                section.contentFingerprint());
            SectionVoxels result = cache.get(key);
            if (result != null && (result.source.vertices == section.vertices
                    && result.source.materialData == section.materialData
                    || Arrays.equals(result.source.vertices, section.vertices)
                        && Arrays.equals(result.source.materialData, section.materialData))) {
                cacheHits++;
                return result;
            }
            if (result != null) {
                retainedBytes -= result.bytes;
                cache.remove(key);
            }
            cacheMisses++;
            boolean spansSection = Math.floorMod(section.originX(), SECTION_SIZE) != 0
                || Math.floorMod(section.originY(), SECTION_SIZE) != 0
                || Math.floorMod(section.originZ(), SECTION_SIZE) != 0;
            for (float coordinate : section.verticesView()) {
                spansSection |= Float.isFinite(coordinate)
                    && (coordinate < -1.0e-4F || coordinate > SECTION_SIZE + 1.0e-4F);
            }
            Samples fine = new Samples(SECTION_VOXELS_PER_AXIS);
            if (!spansSection) selectCandidates(section, new Bounds(section.originX(), section.originY(), section.originZ(),
                (double)section.originX() + SECTION_SIZE, (double)section.originY() + SECTION_SIZE,
                (double)section.originZ() + SECTION_SIZE), SECTION_VOXELS_PER_AXIS,
                fine.sections, fine.triangles, fine.areas, fine.ties, cancelled);
            Samples coarse = new Samples(2);
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int from = ((y * 4 + z) * 4 + x) * 7;
                        int to = (((y >> 1) * 2 + (z >> 1)) * 2 + (x >> 1)) * 7;
                        for (int face = 0; face < 7; face++) {
                            if (fine.sections[from + face] != null) {
                                selectSample(to + face, section, fine.triangles[from + face],
                                    fine.areas[from + face], fine.ties[from + face], coarse.sections,
                                    coarse.triangles, coarse.areas, coarse.ties);
                            }
                        }
                    }
                }
            }
            long bytes = ((long)section.vertices.length + section.materialData.length) * Float.BYTES
                + (64L + 8L) * 7L * 24L + 256L;
            result = new SectionVoxels(section, fine, coarse, bytes, spansSection);
            // Oversized snapshots are usable for this node, but cannot evict the whole cache.
            if (bytes <= CACHE_BYTES) {
                while (!cache.isEmpty() && (retainedBytes + bytes > CACHE_BYTES
                        || cache.size() >= CACHE_ENTRIES)) {
                    var iterator = cache.entrySet().iterator();
                    retainedBytes -= iterator.next().getValue().bytes;
                    iterator.remove();
                }
                cache.put(key, result);
                retainedBytes += bytes;
            }
            return result;
        }
    }

    private record SectionCacheKey(int x, int y, int z, long fingerprint) { }
    private record SectionVoxels(SectionInput source, Samples fine, Samples coarse, long bytes, boolean spansSection) { }

    private static final class Samples {
        final SectionInput[] sections;
        final int[] triangles;
        final float[] areas;
        final long[] ties;

        Samples(int size) {
            int count = size * size * size * 7;
            sections = new SectionInput[count];
            triangles = new int[count];
            areas = new float[count];
            ties = new long[count];
            clear();
        }

        void clear() {
            Arrays.fill(sections, null);
            Arrays.fill(areas, -1.0F);
        }
    }

    private static final class MeshScratch {
        final int[] patches = new int[8 * 8 * 8 * 6 * 3];
        final int[] mask = new int[8 * 8];
        final double[] cellMin = new double[3];
        final double[] cellMax = new double[3];
        final float[] corners = new float[12];
        final float[] uv = new float[8];
    }

    private static Node meshNode(NodeKey key, Bounds bounds, Samples samples, MeshScratch scratch) {
        int size = binsPerAxis(key);
        int patchCount = 0;
        for (int face = 0; face < 6; face++) {
            int normal = face / 2;
            int uAxis = normal == 0 ? 1 : normal == 1 ? 2 : 0;
            for (int slice = 0; slice < size; slice++) {
                Arrays.fill(scratch.mask, -1);
                for (int v = 0; v < size; v++) {
                    for (int u = 0; u < size; u++) {
                        int bin = faceBin(slice, u, v, normal, uAxis, size);
                        if (samples.sections[bin * 7 + 6] == null
                                || !exposedFace(bin, face, size, samples.sections)) continue;
                        scratch.mask[v * size + u] = samples.sections[bin * 7 + face] == null
                            ? bin * 7 + 6 : bin * 7 + face;
                    }
                }
                for (int v = 0; v < size; v++) {
                    for (int u = 0; u < size; u++) {
                        int sample = scratch.mask[v * size + u];
                        if (sample < 0) continue;
                        int width = 1;
                        while (u + width < size && mergeCompatible(sample,
                                scratch.mask[v * size + u + width], samples)) width++;
                        int height = 1;
                        rows: while (v + height < size) {
                            for (int x = 0; x < width; x++) {
                                if (!mergeCompatible(sample,
                                        scratch.mask[(v + height) * size + u + x], samples)) break rows;
                            }
                            height++;
                        }
                        int patch = patchCount++ * 3;
                        scratch.patches[patch] = (faceBin(slice, u, v, normal, uAxis, size) << 3) | face;
                        scratch.patches[patch + 1] = width | (height << 8);
                        scratch.patches[patch + 2] = sample;
                        for (int row = 0; row < height; row++) {
                            Arrays.fill(scratch.mask, (v + row) * size + u,
                                (v + row) * size + u + width, -1);
                        }
                    }
                }
            }
        }
        float[] vertices = new float[patchCount * 18];
        float[] materials = new float[patchCount * MATERIAL_FLOATS_PER_TRIANGLE * 2];
        for (int patch = 0; patch < patchCount; patch++) {
            int packed = scratch.patches[patch * 3];
            int extent = scratch.patches[patch * 3 + 1];
            int sample = scratch.patches[patch * 3 + 2];
            writeProxyPatch(vertices, patch * 18, materials, patch * MATERIAL_FLOATS_PER_TRIANGLE * 2,
                samples.sections[sample], samples.triangles[sample] * 9,
                samples.triangles[sample] * MATERIAL_FLOATS_PER_TRIANGLE,
                packed >>> 3, packed & 7, bounds, size, extent & 255, extent >>> 8,
                scratch.cellMin, scratch.cellMax, scratch.corners, scratch.uv);
        }
        // Arrays are exclusively owned here, so avoid cloning the whole result again.
        return new Node(key, new Mesh(vertices, materials, false));
    }

    private static int faceBin(int slice, int u, int v, int normal, int uAxis, int size) {
        int x = normal == 0 ? slice : uAxis == 0 ? u : v;
        int y = normal == 1 ? slice : uAxis == 1 ? u : v;
        int z = normal == 2 ? slice : uAxis == 2 ? u : v;
        return (y * size + z) * size + x;
    }

    private static boolean mergeCompatible(int a, int b, Samples samples) {
        if (b < 0) return false;
        float[] left = samples.sections[a].materialDataView();
        float[] right = samples.sections[b].materialDataView();
        int lo = samples.triangles[a] * MATERIAL_FLOATS_PER_TRIANGLE;
        int ro = samples.triangles[b] * MATERIAL_FLOATS_PER_TRIANGLE;
        // The current ABI stores atlas UVs, without per-quad repeat bounds. Only
        // constant-UV faces can merge without stretching or crossing another tile.
        if (left[lo + 8] != left[lo + 10] || left[lo + 8] != left[lo + 12]
                || left[lo + 9] != left[lo + 11] || left[lo + 9] != left[lo + 13]) return false;
        for (int i = 0; i < MATERIAL_FLOATS_PER_TRIANGLE; i++) {
            if (Float.floatToIntBits(left[lo + i]) != Float.floatToIntBits(right[ro + i])) return false;
        }
        return true;
    }

    /** Emits a closed coarse voxel's exterior quad using a representative source material. */
    private static void writeProxyPatch(float[] outputVertices, int outputVertexOffset,
                                        float[] outputMaterials, int outputMaterialOffset,
                                        SectionInput section, int sourceVertexOffset,
                                        int sourceMaterialOffset, int bin, int face, Bounds bounds,
                                        int binsPerAxis, int width, int height,
                                        double[] cellMin, double[] cellMax, float[] corners,
                                        float[] proxyUv) {
        float[] source = section.verticesView();
        int ix = bin % binsPerAxis;
        int iz = bin / binsPerAxis % binsPerAxis;
        int iy = bin / (binsPerAxis * binsPerAxis);
        cellMin[0] = bounds.minX() + (bounds.maxX() - bounds.minX()) * ix / binsPerAxis;
        cellMax[0] = bounds.minX() + (bounds.maxX() - bounds.minX()) * (ix + 1) / binsPerAxis;
        cellMin[1] = bounds.minY() + (bounds.maxY() - bounds.minY()) * iy / binsPerAxis;
        cellMax[1] = bounds.minY() + (bounds.maxY() - bounds.minY()) * (iy + 1) / binsPerAxis;
        cellMin[2] = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) * iz / binsPerAxis;
        cellMax[2] = bounds.minZ() + (bounds.maxZ() - bounds.minZ()) * (iz + 1) / binsPerAxis;

        int normalAxis = face / 2;
        // These axis orders have a positive cross-product along the normal axis.
        int uAxis = normalAxis == 0 ? 1 : normalAxis == 1 ? 2 : 0;
        int vAxis = normalAxis == 0 ? 2 : normalAxis == 1 ? 0 : 1;
        double normalComponent = (face & 1) == 0 ? -1.0 : 1.0;
        double cellSize = (bounds.maxX() - bounds.minX()) / binsPerAxis;
        cellMax[uAxis] = cellMin[uAxis] + width * cellSize;
        cellMax[vAxis] = cellMin[vAxis] + height * cellSize;
        for (int corner = 0; corner < 4; corner++) {
            double u = (corner == 1 || corner == 2) ? cellMax[uAxis] : cellMin[uAxis];
            double v = corner >= 2 ? cellMax[vAxis] : cellMin[vAxis];
            double n = normalComponent < 0.0 ? cellMin[normalAxis] : cellMax[normalAxis];
            int offset = corner * 3;
            corners[offset + uAxis] = (float)u;
            corners[offset + vAxis] = (float)v;
            corners[offset + normalAxis] = (float)n;
        }

        // Stretch the representative tile once across a coarse face. Wrapping only the
        // four corners of a multi-block face collapses integer repeats to identical UVs;
        // interpolation cannot reconstruct the repeats and produces single-texel patches.
        float[] sourceMaterials = section.materialDataView();
        double sourceU0 = sourceMaterials[sourceMaterialOffset + 8];
        double sourceV0 = sourceMaterials[sourceMaterialOffset + 9];
        double sourceU1 = sourceMaterials[sourceMaterialOffset + 10];
        double sourceV1 = sourceMaterials[sourceMaterialOffset + 11];
        double sourceU2 = sourceMaterials[sourceMaterialOffset + 12];
        double sourceV2 = sourceMaterials[sourceMaterialOffset + 13];
        double p0u = source[sourceVertexOffset + uAxis];
        double p0v = source[sourceVertexOffset + vAxis];
        double p1u = source[sourceVertexOffset + 3 + uAxis];
        double p1v = source[sourceVertexOffset + 3 + vAxis];
        double p2u = source[sourceVertexOffset + 6 + uAxis];
        double p2v = source[sourceVertexOffset + 6 + vAxis];
        double determinant = (p1u - p0u) * (p2v - p0v) - (p2u - p0u) * (p1v - p0v);
        double minU = Math.min(sourceU0, Math.min(sourceU1, sourceU2));
        double maxU = Math.max(sourceU0, Math.max(sourceU1, sourceU2));
        double minV = Math.min(sourceV0, Math.min(sourceV1, sourceV2));
        double maxV = Math.max(sourceV0, Math.max(sourceV1, sourceV2));
        double minPositionU = Math.min(p0u, Math.min(p1u, p2u));
        double maxPositionU = Math.max(p0u, Math.max(p1u, p2u));
        double minPositionV = Math.min(p0v, Math.min(p1v, p2v));
        double maxPositionV = Math.max(p0v, Math.max(p1v, p2v));
        for (int corner = 0; corner < 4; corner++) {
            boolean upperU = corner == 1 || corner == 2;
            boolean upperV = corner >= 2;
            double cornerU = upperU ? maxPositionU : minPositionU;
            double cornerV = upperV ? maxPositionV : minPositionV;
            double mappedU = upperU ? maxU : minU;
            double mappedV = upperV ? maxV : minV;
            if (Math.abs(determinant) > AREA_EPSILON && Double.isFinite(determinant)) {
                double du = cornerU - p0u;
                double dv = cornerV - p0v;
                double bary1 = (du * (p2v - p0v) - (p2u - p0u) * dv) / determinant;
                double bary2 = ((p1u - p0u) * dv - du * (p1v - p0v)) / determinant;
                mappedU = sourceU0 + bary1 * (sourceU1 - sourceU0) + bary2 * (sourceU2 - sourceU0);
                mappedV = sourceV0 + bary1 * (sourceV1 - sourceV0) + bary2 * (sourceV2 - sourceV0);
            }
            proxyUv[corner * 2] = (float)Math.max(minU, Math.min(maxU, mappedU));
            proxyUv[corner * 2 + 1] = (float)Math.max(minV, Math.min(maxV, mappedV));
        }

        double tileArea = (maxU - minU) * (maxV - minV);
        if (tileArea > 0.0 && (uvArea2(proxyUv, 0, 1, 2) < tileArea * 1.0e-6
                || uvArea2(proxyUv, 0, 2, 3) < tileArea * 1.0e-6)) {
            // Skewed/custom source UVs can collapse after extrapolation and clamping.
            // Preserve a usable tile instead of letting half the quad sample a line.
            for (int corner = 0; corner < 4; corner++) {
                proxyUv[corner * 2] = (float)((corner == 1 || corner == 2) ? maxU : minU);
                proxyUv[corner * 2 + 1] = (float)(corner >= 2 ? maxV : minV);
            }
        }
        int[] order = normalComponent < 0.0 ? REVERSED_WINDING : NORMAL_WINDING;
        for (int vertex = 0; vertex < order.length; vertex++) {
            int sourceCorner = order[vertex] * 3;
            int destination = outputVertexOffset + vertex * 3;
            outputVertices[destination] = corners[sourceCorner];
            outputVertices[destination + 1] = corners[sourceCorner + 1];
            outputVertices[destination + 2] = corners[sourceCorner + 2];
        }
        System.arraycopy(section.materialDataView(), sourceMaterialOffset,
            outputMaterials, outputMaterialOffset, MATERIAL_FLOATS_PER_TRIANGLE);
        System.arraycopy(section.materialDataView(), sourceMaterialOffset,
            outputMaterials, outputMaterialOffset + MATERIAL_FLOATS_PER_TRIANGLE,
            MATERIAL_FLOATS_PER_TRIANGLE);
        for (int triangle = 0; triangle < 2; triangle++) {
            int materialOffset = outputMaterialOffset + triangle * MATERIAL_FLOATS_PER_TRIANGLE;
            for (int vertex = 0; vertex < 3; vertex++) {
                int corner = order[triangle * 3 + vertex];
                outputMaterials[materialOffset + 8 + vertex * 2] = proxyUv[corner * 2];
                outputMaterials[materialOffset + 9 + vertex * 2] = proxyUv[corner * 2 + 1];
            }
        }
    }

    private static double uvArea2(float[] uv, int a, int b, int c) {
        double au = (double)uv[b * 2] - uv[a * 2];
        double av = (double)uv[b * 2 + 1] - uv[a * 2 + 1];
        double bu = (double)uv[c * 2] - uv[a * 2];
        double bv = (double)uv[c * 2 + 1] - uv[a * 2 + 1];
        return Math.abs(au * bv - av * bu);
    }

    private static int dominantAxis(double x, double y, double z) {
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        return ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
    }

    public static Node buildNode(NodeKey key, SectionInput... sections) {
        return buildNode(key, Arrays.asList(sections.clone()));
    }

    /**
     * Builds all levels using one voxel cache. Parent sampling never consumes emitted mesh;
     * hidden directional source samples remain available to each coarser level.
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
        Builder builder = new Builder();
        for (Map.Entry<NodeKey, List<SectionInput>> entry : grouped.entrySet()) {
            nodes.put(entry.getKey(), builder.buildNode(entry.getKey(), entry.getValue()));
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

    private static double distanceToInterval(double value, double min, double max) {
        return value < min ? min - value : value > max ? value - max : 0.0;
    }

    private static void selectCandidates(SectionInput section, Bounds bounds,
                                         int binsPerAxis,
                                         SectionInput[] selectedSections, int[] selectedTriangles,
                                         float[] selectedAreas, long[] selectedTies,
                                         java.util.function.BooleanSupplier cancelled) {
        float[] inputVertices = section.verticesView();
        float[] inputMaterials = section.materialDataView();
        double cellSize = (bounds.maxX() - bounds.minX()) / binsPerAxis;
        double epsilon = cellSize * 1.0e-5;
        double[] voxelTriangle = new double[9];
        double[] localTriangle = new double[9];
        for (int triangle = 0; triangle < inputVertices.length / 9; triangle++) {
            if ((triangle & 255) == 0 && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                throw new java.util.concurrent.CancellationException("LOD worker stopped");
            }
            int vo = triangle * 9, mo = triangle * MATERIAL_FLOATS_PER_TRIANGLE;
            boolean finite = true;
            for (int i = 0; i < 9; i++) {
                int coordinate = i % 3;
                int origin = coordinate == 0 ? section.originX()
                    : coordinate == 1 ? section.originY() : section.originZ();
                finite &= Float.isFinite(inputVertices[vo + i] + origin);
            }
            for (int i = 0; i < MATERIAL_FLOATS_PER_TRIANGLE; i++) {
                finite &= Float.isFinite(inputMaterials[mo + i]);
            }
            if (!finite || !opaque(inputMaterials, mo)) continue;
            // Compute edges in section-local space, before adding large world origins.
            double ax = (double)inputVertices[vo + 3] - inputVertices[vo];
            double ay = (double)inputVertices[vo + 4] - inputVertices[vo + 1];
            double az = (double)inputVertices[vo + 5] - inputVertices[vo + 2];
            double bx = (double)inputVertices[vo + 6] - inputVertices[vo];
            double by = (double)inputVertices[vo + 7] - inputVertices[vo + 1];
            double bz = (double)inputVertices[vo + 8] - inputVertices[vo + 2];
            double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float area2 = (float)(nx * nx + ny * ny + nz * nz);
            if (!(area2 > AREA_EPSILON) || !Float.isFinite(area2)) continue;
            // Move an outward surface just inside its solid side. A face on a grid
            // boundary must occupy the solid voxel, not the air voxel beside it.
            double scale = epsilon / Math.sqrt(area2);
            for (int vertex = 0; vertex < 3; vertex++) {
                int offset = vertex * 3;
                voxelTriangle[offset] = (double)inputVertices[vo + offset] + section.originX() - nx * scale;
                voxelTriangle[offset + 1] = (double)inputVertices[vo + offset + 1] + section.originY() - ny * scale;
                voxelTriangle[offset + 2] = (double)inputVertices[vo + offset + 2] + section.originZ() - nz * scale;
            }
            // Captured faces can lie exactly on a node border. Keep their tiny inward
            // displacement inside this node; neighboring input snapshots are not available.
            for (int vertex = 0; vertex < 3; vertex++) {
                int offset = vertex * 3;
                voxelTriangle[offset] = clampBorder(voxelTriangle[offset], bounds.minX(), bounds.maxX(), epsilon);
                voxelTriangle[offset + 1] = clampBorder(voxelTriangle[offset + 1], bounds.minY(), bounds.maxY(), epsilon);
                voxelTriangle[offset + 2] = clampBorder(voxelTriangle[offset + 2], bounds.minZ(), bounds.maxZ(), epsilon);
            }
            int minX = voxelRange(voxelTriangle, 0, bounds.minX(), cellSize, false, epsilon);
            int minY = voxelRange(voxelTriangle, 1, bounds.minY(), cellSize, false, epsilon);
            int minZ = voxelRange(voxelTriangle, 2, bounds.minZ(), cellSize, false, epsilon);
            int maxX = voxelRange(voxelTriangle, 0, bounds.minX(), cellSize, true, epsilon);
            int maxY = voxelRange(voxelTriangle, 1, bounds.minY(), cellSize, true, epsilon);
            int maxZ = voxelRange(voxelTriangle, 2, bounds.minZ(), cellSize, true, epsilon);
            int axis = dominantAxis(nx, ny, nz);
            double component = axis == 0 ? nx : axis == 1 ? ny : nz;
            int face = axis * 2 + (component > 0.0F ? 1 : 0);
            long tie = stableTriangleKey(inputVertices, vo, section, inputMaterials, mo);
            for (int iy = Math.max(0, minY); iy <= Math.min(binsPerAxis - 1, maxY); iy++) {
                for (int iz = Math.max(0, minZ); iz <= Math.min(binsPerAxis - 1, maxZ); iz++) {
                    for (int ix = Math.max(0, minX); ix <= Math.min(binsPerAxis - 1, maxX); ix++) {
                        double cx = bounds.minX() + (ix + 0.5) * cellSize;
                        double cy = bounds.minY() + (iy + 0.5) * cellSize;
                        double cz = bounds.minZ() + (iz + 0.5) * cellSize;
                        for (int vertex = 0; vertex < 3; vertex++) {
                            int offset = vertex * 3;
                            localTriangle[offset] = voxelTriangle[offset] - cx;
                            localTriangle[offset + 1] = voxelTriangle[offset + 1] - cy;
                            localTriangle[offset + 2] = voxelTriangle[offset + 2] - cz;
                        }
                        if (!triangleIntersectsVoxel(localTriangle, cellSize * 0.5, nx, ny, nz)) continue;
                        int bin = (iy * binsPerAxis + iz) * binsPerAxis + ix;
                        selectSample(bin * 7 + face, section, triangle, area2, tie,
                            selectedSections, selectedTriangles, selectedAreas, selectedTies);
                        selectSample(bin * 7 + 6, section, triangle, area2, tie,
                            selectedSections, selectedTriangles, selectedAreas, selectedTies);
                    }
                }
            }
        }
    }

    private static void selectSample(int sample, SectionInput section, int triangle,
                                     float area, long tie, SectionInput[] sections,
                                     int[] triangles, float[] areas, long[] ties) {
        if (area > areas[sample] || (area == areas[sample] && tie < ties[sample])) {
            sections[sample] = section;
            triangles[sample] = triangle;
            areas[sample] = area;
            ties[sample] = tie;
        }
    }

    private static double clampBorder(double value, double min, double max, double epsilon) {
        if (value < min && value >= min - epsilon * 2.0) return min;
        if (value >= max && value <= max + epsilon * 2.0) return max - epsilon;
        return value;
    }

    private static int voxelRange(double[] triangle, int axis, double origin,
                                  double cellSize, boolean maximum, double epsilon) {
        double min = Math.min(triangle[axis], Math.min(triangle[axis + 3], triangle[axis + 6]));
        double max = Math.max(triangle[axis], Math.max(triangle[axis + 3], triangle[axis + 6]));
        // Half-open tangential extents avoid inflating a face by an extra voxel row.
        double value = maximum ? max - (max - min > epsilon ? epsilon : 0.0) : min;
        return (int)Math.floor((value - origin) / cellSize);
    }

    /** Separating-axis triangle/box test; rejects empty corners of the triangle's AABB. */
    private static boolean triangleIntersectsVoxel(double[] triangle, double halfSize,
                                                   double nx, double ny, double nz) {
        if (separated(triangle, halfSize, nx, ny, nz)) return false;
        for (int axis = 0; axis < 3; axis++) {
            double min = Math.min(triangle[axis], Math.min(triangle[axis + 3], triangle[axis + 6]));
            double max = Math.max(triangle[axis], Math.max(triangle[axis + 3], triangle[axis + 6]));
            if (min > halfSize || max < -halfSize) return false;
        }
        for (int edge = 0; edge < 3; edge++) {
            int a = edge * 3, b = ((edge + 1) % 3) * 3;
            double ex = triangle[b] - triangle[a];
            double ey = triangle[b + 1] - triangle[a + 1];
            double ez = triangle[b + 2] - triangle[a + 2];
            if (separated(triangle, halfSize, 0, ez, -ey)
                    || separated(triangle, halfSize, -ez, 0, ex)
                    || separated(triangle, halfSize, ey, -ex, 0)) return false;
        }
        return true;
    }

    private static boolean separated(double[] triangle, double halfSize,
                                     double x, double y, double z) {
        double p0 = triangle[0] * x + triangle[1] * y + triangle[2] * z;
        double p1 = triangle[3] * x + triangle[4] * y + triangle[5] * z;
        double p2 = triangle[6] * x + triangle[7] * y + triangle[8] * z;
        double radius = halfSize * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        return Math.min(p0, Math.min(p1, p2)) > radius
            || Math.max(p0, Math.max(p1, p2)) < -radius;
    }

    private static boolean exposedFace(int bin, int face, int size, SectionInput[] sections) {
        int x = bin % size, z = bin / size % size, y = bin / (size * size);
        int axis = face / 2;
        int coordinate = axis == 0 ? x : axis == 1 ? y : z;
        int step = (face & 1) == 0 ? -1 : 1;
        int neighborCoordinate = coordinate + step;
        if (neighborCoordinate < 0 || neighborCoordinate >= size) return true;
        int stride = axis == 0 ? 1 : axis == 1 ? size * size : size;
        return sections[(bin + step * stride) * 7 + 6] == null;
    }

    private static boolean opaque(float[] m, int offset) {
        return m[offset + 3] >= 0.999F && m[offset + 22] <= 1.0e-6F
                && Math.abs(m[offset + 24]) <= 1.0e-6F && Math.abs(m[offset + 25]) <= 1.0e-6F
                && Math.abs(m[offset + 26]) <= 1.0e-6F && Math.abs(m[offset + 27] - 1.0F) <= 1.0e-4F;
    }

    private static int binsPerAxis(NodeKey key) {
        return key.level() >= 2 ? LEVEL_TWO_BINS_PER_AXIS : LEVEL_ONE_BINS_PER_AXIS;
    }

    private static long fingerprint(float[] vertices, float[] materials) {
        long hash = 0xcbf29ce484222325L;
        for (float value : vertices) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        for (float value : materials) hash = (hash ^ Float.floatToIntBits(value)) * 0x100000001b3L;
        return hash;
    }

    private static long stableTriangleKey(float[] vertices, int vertexOffset, SectionInput section,
                                          float[] material, int materialOffset) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < 9; index++) {
            int coordinate = index % 3;
            int origin = coordinate == 0 ? section.originX()
                : coordinate == 1 ? section.originY() : section.originZ();
            double value = (double)vertices[vertexOffset + index] + origin;
            hash = (hash ^ Double.doubleToLongBits(value)) * 0x100000001b3L;
        }
        for (int index = 0; index < MATERIAL_FLOATS_PER_TRIANGLE; index++) {
            hash = (hash ^ Float.floatToIntBits(material[materialOffset + index])) * 0x100000001b3L;
        }
        return hash;
    }
}
