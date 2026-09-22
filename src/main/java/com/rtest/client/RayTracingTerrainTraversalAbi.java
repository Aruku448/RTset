package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renderer-independent ABI for GPU terrain hierarchy traversal.
 *
 * <p>This is deliberately separate from {@link RayTracingVulkanPass}. The node SSBO and the
 * output TLAS instance SSBO have two different, fixed layouts. In particular, a TLAS record is
 * always the Vulkan {@code VkAccelerationStructureInstanceKHR} 64-byte record; its
 * {@code instanceCustomIndex} is only the low 24 bits of word 12 and contains a triangle
 * material base. Material records never occupy the remaining TLAS words.</p>
 */
public final class RayTracingTerrainTraversalAbi {
    public static final int NODE_METADATA_BYTES = 64;
    public static final int TLAS_INSTANCE_BYTES = 64;
    public static final int NODE_METADATA_WORDS = NODE_METADATA_BYTES / Integer.BYTES;
    public static final int TLAS_INSTANCE_WORDS = TLAS_INSTANCE_BYTES / Integer.BYTES;
    public static final int INVALID_INDEX = 0xffff_ffff;

    public static final int NODE_FLAG_READY = 1;
    public static final int NODE_FLAG_RENDERABLE = 1 << 1;
    public static final int NODE_FLAG_HAS_BLAS = 1 << 2;

    public static final int INSTANCE_CUSTOM_INDEX_OFFSET = 48;
    public static final int INSTANCE_SBT_OFFSET_FLAGS_OFFSET = 52;
    public static final int INSTANCE_ADDRESS_OFFSET = 56;
    public static final int INSTANCE_CUSTOM_INDEX_MASK = 0x00ff_ffff;
    public static final int INSTANCE_SBT_OFFSET_MASK = 0x00ff_ffff;
    public static final int INSTANCE_FACING_CULL_DISABLE = 1;

    /** Terrain is visible to both the primary (0x7f) and secondary (0xfe) ray masks. */
    public static final int TERRAIN_INSTANCE_MASK = 0xff;

    private static final float DEPTH_EPSILON = 1.0e-4F;
    private static final int MAX_PARENT_DEPTH = 64;

    public enum DepthConvention {
        /** Hi-Z stores the farthest forward-Z surface in each texel (max reduction). */
        FORWARD_Z,
        /** Hi-Z stores the farthest reversed-Z surface in each texel (min reduction). */
        REVERSED_Z
    }

    /** Bounds are world-space and half-open. The projection treats them as a closed AABB. */
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Bounds {
            if (!finite(minX) || !finite(minY) || !finite(minZ)
                    || !finite(maxX) || !finite(maxY) || !finite(maxZ)
                    || !(maxX > minX) || !(maxY > minY) || !(maxZ > minZ)) {
                throw new IllegalArgumentException("invalid terrain node bounds");
            }
        }
    }

    /**
     * Exactly four vec4 values in std430:
     * <pre>
     *   0  vec4 boundsMinAndLodError (xyz, pixels)
     *  16  vec4 boundsMaxAndLod      (xyz, uint bits)
     *  32  uvec4 parent, firstChild, childMask, reserved
     *  48  uvec4 instance, materialBase, triangleCount, flags
     * </pre>
     */
    public record NodeMetadata(Bounds bounds, int parentIndex, int firstChildIndex, int childMask,
                               int lod, int instanceIndex, int materialBase, int triangleCount,
                               int flags, float lodErrorPixels) {
        public NodeMetadata {
            if (bounds == null) throw new NullPointerException("bounds");
            if (parentIndex < 0 && parentIndex != INVALID_INDEX) {
                throw new IllegalArgumentException("parentIndex must be non-negative or INVALID_INDEX");
            }
            if (firstChildIndex < 0 && firstChildIndex != INVALID_INDEX) {
                throw new IllegalArgumentException("firstChildIndex must be non-negative or INVALID_INDEX");
            }
            if ((childMask & ~0xff) != 0) throw new IllegalArgumentException("childMask must fit 8 bits");
            if (lod < 0) throw new IllegalArgumentException("lod must be non-negative");
            if (instanceIndex < 0) throw new IllegalArgumentException("instanceIndex must be non-negative");
            if (materialBase < 0 || materialBase > INSTANCE_CUSTOM_INDEX_MASK) {
                throw new IllegalArgumentException("materialBase must fit Vk instanceCustomIndex (24 bits)");
            }
            if (triangleCount < 0 || !finite(lodErrorPixels) || lodErrorPixels < 0.0F) {
                throw new IllegalArgumentException("invalid node draw metadata");
            }
        }

        public boolean ready() { return (flags & NODE_FLAG_READY) != 0; }
        public boolean renderable() { return (flags & NODE_FLAG_RENDERABLE) != 0; }
        public boolean hasBlas() { return (flags & NODE_FLAG_HAS_BLAS) != 0; }
    }

    /** The conservative projected AABB rectangle, in normalized top-left screen coordinates. */
    public record ScreenRect(float minX, float minY, float maxX, float maxY,
                             float depthNear, float depthFar, boolean intersectsViewport) {
        public ScreenRect {
            if (!finite(minX) || !finite(minY) || !finite(maxX) || !finite(maxY)
                    || !finite(depthNear) || !finite(depthFar)
                    || maxX < minX || maxY < minY) {
                throw new IllegalArgumentException("invalid projected screen rectangle");
            }
        }

        public float width() { return Math.max(0.0F, maxX - minX); }
        public float height() { return Math.max(0.0F, maxY - minY); }
    }

    /** A Hi-Z source whose texels contain farthest-surface depth for their covered region. */
    public interface HiZ {
        int maxMipLevel();
        int width(int mipLevel);
        int height(int mipLevel);
        float sample(int mipLevel, int x, int y);
    }

    /** Small CPU reference pyramid used by contract tests and offline validation. */
    public static final class HiZPyramid implements HiZ {
        private final int[] widths;
        private final int[] heights;
        private final float[][] levels;

        public HiZPyramid(int width, int height, float[] nearestDepth, DepthConvention convention) {
            if (width <= 0 || height <= 0 || nearestDepth == null || nearestDepth.length != width * height
                    || convention == null) throw new IllegalArgumentException("invalid Hi-Z base level");
            ArrayList<Integer> ws = new ArrayList<>();
            ArrayList<Integer> hs = new ArrayList<>();
            ArrayList<float[]> data = new ArrayList<>();
            int w = width;
            int h = height;
            float[] level = nearestDepth.clone();
            ws.add(w); hs.add(h); data.add(level);
            while (w > 1 || h > 1) {
                int nextW = Math.max(1, (w + 1) / 2);
                int nextH = Math.max(1, (h + 1) / 2);
                float[] next = new float[nextW * nextH];
                for (int y = 0; y < nextH; y++) {
                    for (int x = 0; x < nextW; x++) {
                        float aggregate = convention == DepthConvention.FORWARD_Z ? 0.0F : 1.0F;
                        for (int oy = 0; oy < 2; oy++) {
                            for (int ox = 0; ox < 2; ox++) {
                                int sx = Math.min(w - 1, x * 2 + ox);
                                int sy = Math.min(h - 1, y * 2 + oy);
                                float value = level[sy * w + sx];
                                aggregate = convention == DepthConvention.FORWARD_Z
                                        ? Math.max(aggregate, value) : Math.min(aggregate, value);
                            }
                        }
                        next[y * nextW + x] = aggregate;
                    }
                }
                w = nextW;
                h = nextH;
                level = next;
                ws.add(w); hs.add(h); data.add(level);
            }
            this.widths = ws.stream().mapToInt(Integer::intValue).toArray();
            this.heights = hs.stream().mapToInt(Integer::intValue).toArray();
            this.levels = data.toArray(float[][]::new);
        }

        @Override public int maxMipLevel() { return levels.length - 1; }
        @Override public int width(int mipLevel) { return widths[mipLevel]; }
        @Override public int height(int mipLevel) { return heights[mipLevel]; }
        @Override public float sample(int mipLevel, int x, int y) {
            return levels[mipLevel][y * widths[mipLevel] + x];
        }
    }

    /** A selected leaf/current fallback and its conservative screen rectangle. */
    public record Selection(NodeMetadata node, ScreenRect screenRect) { }

    /** Fixed output entry used by {@link #writeTlasInstances}. */
    public record InstanceInput(NodeMetadata node, long blasAddress, boolean selected) {
        public InstanceInput {
            if (node == null) throw new NullPointerException("node");
            if (blasAddress < 0) throw new IllegalArgumentException("BLAS address must be non-negative");
        }
    }

    private RayTracingTerrainTraversalAbi() { }

    public static void writeNodeMetadata(ByteBuffer destination, List<NodeMetadata> nodes) {
        if (nodes == null) throw new NullPointerException("nodes");
        requireRemaining(destination, (long) nodes.size() * NODE_METADATA_BYTES, "node metadata");
        destination.order(ByteOrder.nativeOrder());
        for (NodeMetadata node : nodes) {
            int offset = destination.position();
            Bounds b = node.bounds();
            destination.putFloat(offset, b.minX()).putFloat(offset + 4, b.minY())
                    .putFloat(offset + 8, b.minZ()).putFloat(offset + 12, node.lodErrorPixels());
            destination.putFloat(offset + 16, b.maxX()).putFloat(offset + 20, b.maxY())
                    .putFloat(offset + 24, b.maxZ()).putInt(offset + 28, node.lod());
            destination.putInt(offset + 32, node.parentIndex()).putInt(offset + 36, node.firstChildIndex())
                    .putInt(offset + 40, node.childMask()).putInt(offset + 44, 0);
            destination.putInt(offset + 48, node.instanceIndex()).putInt(offset + 52, node.materialBase())
                    .putInt(offset + 56, node.triangleCount()).putInt(offset + 60, node.flags());
            destination.position(offset + NODE_METADATA_BYTES);
        }
    }

    public static ByteBuffer nodeMetadataBuffer(List<NodeMetadata> nodes) {
        ByteBuffer result = ByteBuffer.allocate(nodes.size() * NODE_METADATA_BYTES).order(ByteOrder.nativeOrder());
        writeNodeMetadata(result, nodes);
        return (ByteBuffer) result.flip();
    }

    /**
     * Writes a fixed-capacity array of Vulkan instance records. Slots not selected use a valid
     * dummy address and mask zero. The buffer position advances by {@code capacity * 64} bytes.
     */
    public static void writeTlasInstances(ByteBuffer destination, int capacity,
                                          List<InstanceInput> inputs, long dummyBlasAddress) {
        writeTlasInstances(destination, capacity, inputs, dummyBlasAddress, TERRAIN_INSTANCE_MASK);
    }

    public static void writeTlasInstances(ByteBuffer destination, int capacity,
                                          List<InstanceInput> inputs, long dummyBlasAddress, int instanceMask) {
        if (capacity < 0 || inputs == null || dummyBlasAddress < 0 || (instanceMask & ~0xff) != 0) {
            throw new IllegalArgumentException("invalid TLAS instance output arguments");
        }
        requireRemaining(destination, (long) capacity * TLAS_INSTANCE_BYTES, "TLAS instances");
        destination.order(ByteOrder.nativeOrder());
        InstanceInput[] bySlot = new InstanceInput[capacity];
        for (InstanceInput input : inputs) {
            int slot = input.node().instanceIndex();
            if (slot >= capacity) throw new IllegalArgumentException("node instance slot exceeds capacity");
            if (bySlot[slot] != null) throw new IllegalArgumentException("duplicate instance slot " + slot);
            bySlot[slot] = input;
        }
        for (int slot = 0; slot < capacity; slot++) {
            int offset = destination.position();
            InstanceInput input = bySlot[slot];
            NodeMetadata node = input == null ? null : input.node();
            boolean active = input != null && input.selected() && input.blasAddress() != 0L
                    && node.hasBlas() && node.triangleCount() > 0;
            putIdentityTransform(destination, offset);
            destination.putInt(offset + INSTANCE_CUSTOM_INDEX_OFFSET,
                    active ? node.materialBase() : 0);
            destination.putInt(offset + INSTANCE_SBT_OFFSET_FLAGS_OFFSET,
                    INSTANCE_FACING_CULL_DISABLE << 24);
            destination.putLong(offset + INSTANCE_ADDRESS_OFFSET,
                    active ? input.blasAddress() : dummyBlasAddress);
            destination.putInt(offset + INSTANCE_CUSTOM_INDEX_OFFSET,
                    (active ? node.materialBase() : 0) | (active ? instanceMask << 24 : 0));
            destination.position(offset + TLAS_INSTANCE_BYTES);
        }
    }

    /** Projects a closed world-space AABB using a column-major Vulkan clip matrix. */
    public static ScreenRect projectBounds(Bounds bounds, float[] viewProjection) {
        if (bounds == null || viewProjection == null || viewProjection.length != 16) {
            throw new IllegalArgumentException("viewProjection must contain 16 floats");
        }
        float minX = 1.0F, minY = 1.0F, maxX = 0.0F, maxY = 0.0F;
        float depthNear = 1.0F, depthFar = 0.0F;
        boolean behindNearPlane = false;
        for (int i = 0; i < 8; i++) {
            float x = ((i & 1) == 0) ? bounds.minX() : bounds.maxX();
            float y = ((i & 2) == 0) ? bounds.minY() : bounds.maxY();
            float z = ((i & 4) == 0) ? bounds.minZ() : bounds.maxZ();
            float clipX = viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12];
            float clipY = viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13];
            float clipZ = viewProjection[2] * x + viewProjection[6] * y + viewProjection[10] * z + viewProjection[14];
            float clipW = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15];
            if (!(clipW > 0.0F) || !finite(clipW)) {
                behindNearPlane = true;
                continue;
            }
            float ndcX = clipX / clipW;
            float ndcY = clipY / clipW;
            float ndcZ = clipZ / clipW;
            minX = Math.min(minX, ndcX * 0.5F + 0.5F);
            maxX = Math.max(maxX, ndcX * 0.5F + 0.5F);
            minY = Math.min(minY, 0.5F - ndcY * 0.5F);
            maxY = Math.max(maxY, 0.5F - ndcY * 0.5F);
            depthNear = Math.min(depthNear, ndcZ);
            depthFar = Math.max(depthFar, ndcZ);
        }
        if (behindNearPlane) {
            return new ScreenRect(0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, true);
        }
        boolean intersects = maxX >= 0.0F && minX <= 1.0F && maxY >= 0.0F && minY <= 1.0F
                && depthFar >= 0.0F && depthNear <= 1.0F;
        return new ScreenRect(Math.max(0.0F, minX), Math.max(0.0F, minY),
                Math.min(1.0F, maxX), Math.min(1.0F, maxY),
                Math.max(0.0F, depthNear), Math.min(1.0F, depthFar), intersects);
    }

    /**
     * Conservative Hi-Z test. It only rejects when every Hi-Z texel touched by the projected
     * rectangle says that the node's nearest depth is behind a farthest occluder. A missing or
     * non-finite sample is visible, never occluded.
     */
    public static boolean isHiZOccluded(ScreenRect rect, HiZ hiz, int screenWidth, int screenHeight,
                                        DepthConvention convention) {
        if (rect == null || hiz == null || screenWidth <= 0 || screenHeight <= 0 || convention == null
                || !rect.intersectsViewport() || rect.width() <= 0.0F || rect.height() <= 0.0F) return false;
        int pixelWidth = Math.max(1, (int) Math.ceil(rect.width() * screenWidth));
        int pixelHeight = Math.max(1, (int) Math.ceil(rect.height() * screenHeight));
        int mip = Math.min(hiz.maxMipLevel(), Math.max(0,
                32 - Integer.numberOfLeadingZeros(Math.max(pixelWidth, pixelHeight) - 1)));
        int width = hiz.width(mip), height = hiz.height(mip);
        int x0 = clamp((int) Math.floor(rect.minX() * width), 0, width - 1);
        int y0 = clamp((int) Math.floor(rect.minY() * height), 0, height - 1);
        int x1 = clamp((int) Math.ceil(rect.maxX() * width) - 1, 0, width - 1);
        int y1 = clamp((int) Math.ceil(rect.maxY() * height) - 1, 0, height - 1);
        float aggregate = convention == DepthConvention.FORWARD_Z ? 0.0F : 1.0F;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                float sample = hiz.sample(mip, x, y);
                if (!finite(sample)) return false;
                aggregate = convention == DepthConvention.FORWARD_Z
                        ? Math.max(aggregate, sample) : Math.min(aggregate, sample);
            }
        }
        return convention == DepthConvention.FORWARD_Z
                ? aggregate < rect.depthNear() - DEPTH_EPSILON
                : aggregate > rect.depthNear() + DEPTH_EPSILON;
    }

    /** CPU reference of the shader's parent fallback and LOD selection policy. */
    public static List<Selection> selectNodes(List<NodeMetadata> nodes, float[] viewProjection,
                                               float cameraX, float cameraZ, int screenWidth, int screenHeight,
                                               float renderDistance, HiZ hiz, DepthConvention convention) {
        if (nodes == null || viewProjection == null || !finite(cameraX) || !finite(cameraZ)
                || screenWidth <= 0 || screenHeight <= 0 || !finite(renderDistance) || renderDistance < 0.0F) {
            throw new IllegalArgumentException("invalid traversal inputs");
        }
        ScreenRect[] rectangles = new ScreenRect[nodes.size()];
        boolean[] visible = new boolean[nodes.size()];
        boolean[] descend = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            NodeMetadata node = nodes.get(i);
            rectangles[i] = projectBounds(node.bounds(), viewProjection);
            float distance = distanceToBounds(cameraX, cameraZ, node.bounds());
            visible[i] = node.ready() && rectangles[i].intersectsViewport() && distance <= renderDistance
                    && !isHiZOccluded(rectangles[i], hiz, screenWidth, screenHeight, convention);
            descend[i] = visible[i] && hasCompleteChildren(nodes, node)
                    && Math.max(rectangles[i].width() * screenWidth, rectangles[i].height() * screenHeight)
                        > node.lodErrorPixels();
        }
        ArrayList<Selection> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeMetadata node = nodes.get(i);
            if (!visible[i] || !node.renderable() || !node.hasBlas() || node.triangleCount() == 0 || descend[i]) continue;
            int ancestor = node.parentIndex();
            boolean path = true;
            int guard = 0;
            while (ancestor != INVALID_INDEX) {
                if (++guard > MAX_PARENT_DEPTH || ancestor < 0 || ancestor >= nodes.size() || !descend[ancestor]) {
                    path = false;
                    break;
                }
                ancestor = nodes.get(ancestor).parentIndex();
            }
            if (path) result.add(new Selection(node, rectangles[i]));
        }
        result.sort(Comparator.comparingInt(selection -> selection.node().instanceIndex()));
        return List.copyOf(result);
    }

    private static boolean hasCompleteChildren(List<NodeMetadata> nodes, NodeMetadata node) {
        if (node.childMask() == 0 || node.firstChildIndex() == INVALID_INDEX) return false;
        for (int bit = 0; bit < 8; bit++) {
            if ((node.childMask() & (1 << bit)) == 0) continue;
            int child = node.firstChildIndex() + bit;
            if (child < 0 || child >= nodes.size()) return false;
            NodeMetadata childNode = nodes.get(child);
            if (!childNode.ready() || !childNode.renderable() || childNode.triangleCount() == 0) return false;
        }
        return true;
    }

    private static float distanceToBounds(float x, float z, Bounds bounds) {
        float dx = x < bounds.minX() ? bounds.minX() - x : x > bounds.maxX() ? x - bounds.maxX() : 0.0F;
        float dz = z < bounds.minZ() ? bounds.minZ() - z : z > bounds.maxZ() ? z - bounds.maxZ() : 0.0F;
        return (float) Math.hypot(dx, dz);
    }

    private static void putIdentityTransform(ByteBuffer buffer, int offset) {
        buffer.putFloat(offset, 1.0F).putFloat(offset + 4, 0.0F).putFloat(offset + 8, 0.0F).putFloat(offset + 12, 0.0F);
        buffer.putFloat(offset + 16, 0.0F).putFloat(offset + 20, 1.0F).putFloat(offset + 24, 0.0F).putFloat(offset + 28, 0.0F);
        buffer.putFloat(offset + 32, 0.0F).putFloat(offset + 36, 0.0F).putFloat(offset + 40, 1.0F).putFloat(offset + 44, 0.0F);
    }

    private static void requireRemaining(ByteBuffer buffer, long bytes, String what) {
        if (buffer == null || bytes > Integer.MAX_VALUE || buffer.remaining() < bytes) {
            throw new IllegalArgumentException(what + " buffer is too small");
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static boolean finite(float value) { return Float.isFinite(value); }
}
