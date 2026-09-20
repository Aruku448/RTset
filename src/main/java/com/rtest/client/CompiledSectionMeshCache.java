package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.renderer.chunk.SectionCompiler.Results;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/**
 * Copies vanilla's already compiled terrain mesh before the renderer uploads/releases it.
 *
 * <p>The RT path consumes a compact CPU snapshot, not Minecraft's transient GPU uber-buffer.
 * This avoids a second BlockState/quad walk when a compiled section is available. The snapshot
 * intentionally uses conservative default PBR values because vanilla terrain meshes do not carry
 * sprite/material IDs; the existing CPU path remains the fallback for sections not compiled yet
 * and for semantic materials (such as glass) whose optical metadata cannot be recovered here.
 * The bounded LRU avoids retaining every compiled section in a large view distance.
 */
public final class CompiledSectionMeshCache {
    private static final int MAX_ENTRIES = 2048;
    // Entry count alone is unsafe: one compiled section can contain a large model and retain
    // several megabytes of primitive arrays. Bound the actual Java heap footprint as well.
    private static final long MAX_BYTES = 256L * 1024L * 1024L;
    private static final Map<Long, CompiledMesh> MESHES = new LinkedHashMap<>(256, 0.75f, true);
    private static long cachedBytes;

    private CompiledSectionMeshCache() {
    }

    public static void publish(SectionPos sectionPos, Results results) {
        CompiledMesh mesh = CompiledMesh.from(results);
        long key = sectionPos.asLong();
        synchronized (CompiledSectionMeshCache.class) {
            CompiledMesh previous = MESHES.remove(key);
            if (previous != null) {
                cachedBytes -= previous.byteSize();
            }
            if (mesh != null) {
                MESHES.put(key, mesh);
                cachedBytes += mesh.byteSize();
                while (MESHES.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
                    var eldest = MESHES.entrySet().iterator().next();
                    cachedBytes -= eldest.getValue().byteSize();
                    MESHES.remove(eldest.getKey());
                }
            }
        }
    }

    static synchronized CompiledMesh get(BlockPos origin) {
        return MESHES.get(SectionPos.asLong(origin));
    }

    static synchronized void invalidate(BlockPos origin) {
        remove(SectionPos.asLong(origin));
    }

    /** Invalidates one chunk without throwing away compiled meshes from unrelated chunks. */
    static synchronized void invalidateChunk(ChunkPos chunkPos, int minSectionY, int maxSectionY) {
        for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
            remove(SectionPos.asLong(chunkPos.x(), sectionY, chunkPos.z()));
        }
    }

    public static synchronized void invalidateAll() {
        MESHES.clear();
        cachedBytes = 0L;
    }

    private static void remove(long key) {
        CompiledMesh removed = MESHES.remove(key);
        if (removed != null) {
            cachedBytes -= removed.byteSize();
        }
    }

    static final class CompiledMesh {
        final float[] vertices;
        final float[] materialData;

        private CompiledMesh(float[] vertices, float[] materialData) {
            this.vertices = vertices;
            this.materialData = materialData;
        }

        private long byteSize() {
            return ((long)this.vertices.length + this.materialData.length) * Float.BYTES;
        }

        private static CompiledMesh from(Results results) {
            FloatBuilder vertices = new FloatBuilder();
            FloatBuilder materials = new FloatBuilder();
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                MeshData mesh = results.renderedLayers.get(layer);
                if (mesh == null) {
                    continue;
                }
                appendLayer(mesh, layer, vertices, materials);
            }
            return vertices.size == 0 ? null : new CompiledMesh(vertices.toArray(), materials.toArray());
        }

        private static void appendLayer(
            MeshData mesh,
            ChunkSectionLayer layer,
            FloatBuilder vertices,
            FloatBuilder materials
        ) {
            MeshData.DrawState drawState = mesh.drawState();
            if (drawState.primitiveTopology() != com.mojang.blaze3d.PrimitiveTopology.QUADS) {
                return;
            }
            VertexFormat format = drawState.format();
            VertexFormatElement position = format.getElement("Position");
            VertexFormatElement uv = format.getElement("UV0");
            if (position == null || uv == null
                    || position.format() != GpuFormat.RGB32_FLOAT
                    || uv.format() != GpuFormat.RG32_FLOAT) {
                return;
            }
            VertexFormatElement normal = format.getElement("Normal");
            int stride = format.getVertexSize();
            int vertexCount = drawState.vertexCount();
            ByteBuffer data = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int availableVertices = Math.min(vertexCount, data.remaining() / stride);
            // The output size is known: reserve once instead of repeatedly copying growing arrays.
            int triangles = (availableVertices / 4) * 2;
            vertices.reserve(triangles * 9);
            materials.reserve(triangles * 28);
            for (int baseVertex = 0; baseVertex + 3 < availableVertices; baseVertex += 4) {
                appendTriangle(data, baseVertex * stride, (baseVertex + 1) * stride, (baseVertex + 2) * stride, layer,
                    position, uv, normal, vertices, materials);
                appendTriangle(data, baseVertex * stride, (baseVertex + 2) * stride, (baseVertex + 3) * stride, layer,
                    position, uv, normal, vertices, materials);
            }
        }

        private static void appendTriangle(
            ByteBuffer data,
            int offset0,
            int offset1,
            int offset2,
            ChunkSectionLayer layer,
            VertexFormatElement position,
            VertexFormatElement uv,
            VertexFormatElement normal,
            FloatBuilder vertices,
            FloatBuilder materials
        ) {
            int positionOffset = position.offset();
            float x0 = data.getFloat(offset0 + positionOffset);
            float y0 = data.getFloat(offset0 + positionOffset + 4);
            float z0 = data.getFloat(offset0 + positionOffset + 8);
            float x1 = data.getFloat(offset1 + positionOffset);
            float y1 = data.getFloat(offset1 + positionOffset + 4);
            float z1 = data.getFloat(offset1 + positionOffset + 8);
            float x2 = data.getFloat(offset2 + positionOffset);
            float y2 = data.getFloat(offset2 + positionOffset + 4);
            float z2 = data.getFloat(offset2 + positionOffset + 8);
            float ax = x1 - x0;
            float ay = y1 - y0;
            float az = z1 - z0;
            float bx = x2 - x0;
            float by = y2 - y0;
            float bz = z2 - z0;
            float faceX = ay * bz - az * by;
            float faceY = az * bx - ax * bz;
            float faceZ = ax * by - ay * bx;
            float length = (float)Math.sqrt(faceX * faceX + faceY * faceY + faceZ * faceZ);
            if (length > 1.0e-5f) {
                faceX /= length;
                faceY /= length;
                faceZ /= length;
            }
            vertices.add(x0); vertices.add(y0); vertices.add(z0);
            vertices.add(x1); vertices.add(y1); vertices.add(z1);
            vertices.add(x2); vertices.add(y2); vertices.add(z2);
            // Vanilla's compiled Color contains face/cardinal shade and AO. It is not
            // recoverable as albedo here, so never feed it into RT radiance. The shader
            // samples the atlas from UV0; biome tint remains on the CPU quad fallback.
            float averageR = 1.0f;
            float averageG = 1.0f;
            float averageB = 1.0f;
            float averageNx = 0.0f;
            float averageNy = 0.0f;
            float averageNz = 0.0f;
            if (normal != null && normal.format() == GpuFormat.RGBA8_SNORM) {
                int n0 = offset0 + normal.offset();
                int n1 = offset1 + normal.offset();
                int n2 = offset2 + normal.offset();
                averageNx = (data.get(n0) / 127.0f + data.get(n1) / 127.0f + data.get(n2) / 127.0f) / 3.0f;
                averageNy = (data.get(n0 + 1) / 127.0f + data.get(n1 + 1) / 127.0f + data.get(n2 + 1) / 127.0f) / 3.0f;
                averageNz = (data.get(n0 + 2) / 127.0f + data.get(n1 + 2) / 127.0f + data.get(n2 + 2) / 127.0f) / 3.0f;
            }
            float normalLength = (float)Math.sqrt(averageNx * averageNx + averageNy * averageNy + averageNz * averageNz);
            if (normalLength < 1.0e-5f) {
                averageNx = faceX;
                averageNy = faceY;
                averageNz = faceZ;
            } else {
                averageNx /= normalLength;
                averageNy /= normalLength;
                averageNz /= normalLength;
            }
            boolean translucent = layer.translucent();
            materials.add(averageR); materials.add(averageG); materials.add(averageB); materials.add(1.0f);
            materials.add(averageNx); materials.add(averageNy); materials.add(averageNz); materials.add(0.0f);
            int uvOffset = uv.offset();
            materials.add(data.getFloat(offset0 + uvOffset)); materials.add(data.getFloat(offset0 + uvOffset + 4));
            materials.add(data.getFloat(offset1 + uvOffset)); materials.add(data.getFloat(offset1 + uvOffset + 4));
            materials.add(data.getFloat(offset2 + uvOffset)); materials.add(data.getFloat(offset2 + uvOffset + 4));
            materials.add(translucent ? 0.0f : 1.0f);
            // Preserve the seven-vec4 ABI; UV2.w stays the texture-kind flag and lighting is neutral.
            materials.add(1.0f); materials.add(0.0f); materials.add(0.0f); materials.add(0.0f); materials.add(0.0f);
            materials.add(0.88f); materials.add(0.0f); materials.add(0.0f); materials.add(translucent ? 1.04f : 0.04f);
            materials.add(translucent ? 0.02f : 0.0f);
            materials.add(translucent ? 0.02f : 0.0f);
            materials.add(translucent ? 0.02f : 0.0f);
            materials.add(translucent ? 1.5f : 1.0f);
        }
    }

    private static final class FloatBuilder {
        private float[] values = new float[1024];
        private int size;

        void reserve(int additional) {
            int required = Math.addExact(this.size, additional);
            if (required > this.values.length) {
                this.values = java.util.Arrays.copyOf(this.values, Math.max(required, this.values.length * 2));
            }
        }

        void add(float value) {
            if (this.size == this.values.length) {
                this.values = java.util.Arrays.copyOf(this.values, this.values.length * 2);
            }
            this.values[this.size++] = value;
        }

        float[] toArray() {
            return this.size == this.values.length ? this.values : java.util.Arrays.copyOf(this.values, this.size);
        }
    }
}
