package com.rtest.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Immutable, sprite-aware snapshots of Sodium block quads before vertex compression.
 * No Sodium types escape into this cache or the scene consumer. Build tokens are taken
 * when a task is created, and only outputs accepted by Sodium may publish them.
 */
public final class SodiumTerrainMeshCache {
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_GENERATIONS = 8192;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    public static final int MAX_SECTION_QUADS = 32768;
    private static final Map<Long, Mesh> MESHES = new LinkedHashMap<>(256, 0.75F, true);
    private static final Map<Long, Long> GENERATIONS = new java.util.HashMap<>();
    private static final ThreadLocal<Build> ACTIVE = new ThreadLocal<>();
    private static long epoch;
    private static long cachedBytes;
    private static long published, staleResults, evicted, invalidated, hits, misses, generationRollovers;
    public enum Failure { QUAD_LIMIT, MESH_APPENDER, INVALID_QUAD, CAPTURE_EXCEPTION, PARTIAL_EMISSION, TOPOLOGY }
    private static final java.util.concurrent.atomic.LongAdder[] FAILURES =
        java.util.Arrays.stream(Failure.values()).map(x -> new java.util.concurrent.atomic.LongAdder())
            .toArray(java.util.concurrent.atomic.LongAdder[]::new);

    private SodiumTerrainMeshCache() { }

    public record Token(long sectionKey, long epoch, long generation) { }

    public record Quad(BlockState state, BakedQuad geometry, int color0, int color1, int color2, int color3) {
        int triangleColor(int a, int b, int c) {
            return averageColor(color(a), color(b), color(c));
        }

        private int color(int vertex) {
            return switch (vertex) {
                case 0 -> color0; case 1 -> color1; case 2 -> color2; case 3 -> color3;
                default -> throw new IllegalArgumentException("Invalid quad vertex");
            };
        }
    }

    public record Mesh(List<Quad> quads) {
        public Mesh { quads = List.copyOf(quads); }
        // Conservative bound includes the copied positions, material info and quad objects.
        long byteSize() { return 256L + 512L * quads.size(); }
    }

    public record Pending(Token token, Mesh mesh) { }

    public static synchronized Token begin(SectionPos section) {
        long key = section.asLong();
        if (!GENERATIONS.containsKey(key) && GENERATIONS.size() >= MAX_GENERATIONS) {
            epoch++;
            GENERATIONS.clear();
            generationRollovers++;
        }
        GENERATIONS.putIfAbsent(key, 0L);
        return new Token(key, epoch, GENERATIONS.get(key));
    }

    public static Build open(Token token) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested Sodium terrain build");
        Build build = new Build(token);
        ACTIVE.set(build);
        return build;
    }

    public static Build active() { return ACTIVE.get(); }

    public static final class Build implements AutoCloseable {
        private final Token token;
        private final List<Quad> quads = new ArrayList<>();
        private boolean failed;
        private int fluidDepth;
        private long fluidVertices;
        private long submittedVertices;

        private Build(Token token) { this.token = token; }

        public boolean accepting() { return !failed; }

        public void append(Quad quad) {
            if (failed) return;
            if (quads.size() >= MAX_SECTION_QUADS) {
                fail(Failure.QUAD_LIMIT);
                return;
            }
            quads.add(quad);
        }

        public void beginFluid() { fluidDepth++; }

        public void endFluid() { fluidDepth--; }

        public void submitted(int vertices) {
            submittedVertices += vertices;
            if (fluidDepth > 0) fluidVertices += vertices;
        }

        public void fail(Failure reason) {
            if (!failed) FAILURES[reason.ordinal()].increment();
            failed = true;
            quads.clear();
        }

        /** Validate all submitted vertices, while retaining only the complete block component. */
        public Pending finish(long uploadedVertices) {
            if (failed) return null;
            if (fluidDepth != 0 || uploadedVertices != submittedVertices
                || 4L * quads.size() + fluidVertices != submittedVertices) {
                fail(Failure.TOPOLOGY);
                return null;
            }
            return new Pending(token, new Mesh(quads));
        }

        @Override public void close() {
            if (ACTIVE.get() == this) ACTIVE.remove();
            quads.clear();
        }
    }

    public static synchronized boolean publish(Pending pending) {
        if (pending == null) return false;
        Token token = pending.token();
        if (token.epoch() != epoch || token.generation() != GENERATIONS.getOrDefault(token.sectionKey(), 0L)) {
            staleResults++;
            return false;
        }
        published++;
        remove(token.sectionKey());
        Mesh mesh = pending.mesh();
        MESHES.put(token.sectionKey(), mesh);
        cachedBytes += mesh.byteSize();
        while (MESHES.size() > MAX_ENTRIES || cachedBytes > MAX_BYTES) {
            evicted++;
            remove(MESHES.entrySet().iterator().next().getKey());
        }
        return true;
    }

    static synchronized Mesh get(BlockPos origin) {
        Mesh mesh = MESHES.get(SectionPos.asLong(origin));
        if (mesh == null) misses++; else hits++;
        return mesh;
    }

    static synchronized String diagnostics() {
        StringBuilder reasons = new StringBuilder();
        for (Failure reason : Failure.values()) {
            long count = FAILURES[reason.ordinal()].sum();
            if (count > 0) reasons.append(reason).append('=').append(count).append(' ');
        }
        return "cached=" + MESHES.size() + " estimatedMiB=" + cachedBytes / (1024 * 1024)
            + " published=" + published + " stale=" + staleResults + " evicted=" + evicted
            + " invalidated=" + invalidated + " hits=" + hits + " misses=" + misses
            + " generationRollovers=" + generationRollovers + " rejected={" + reasons + "}";
    }

    public static synchronized void invalidate(BlockPos origin) {
        long key = SectionPos.asLong(origin);
        // An unseen section cannot have an in-flight token: begin() registers every task.
        // Avoid filling the generation table with air/unbuilt layers on chunk events.
        if (!GENERATIONS.containsKey(key) && !MESHES.containsKey(key)) return;
        GENERATIONS.put(key, GENERATIONS.getOrDefault(key, 0L) + 1L);
        if (MESHES.containsKey(key)) invalidated++;
        remove(key);
        if (GENERATIONS.size() > MAX_GENERATIONS) {
            epoch++;
            GENERATIONS.clear();
            // Epoch rejects older in-flight tasks. Unrelated published meshes remain valid.
            generationRollovers++;
        }
    }

    static synchronized void invalidateChunk(ChunkPos chunk, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) invalidate(new BlockPos(chunk.x() * 16, y * 16, chunk.z() * 16));
    }

    public static synchronized void invalidateAll() {
        epoch++;
        GENERATIONS.clear();
        MESHES.clear();
        cachedBytes = 0;
    }

    private static void remove(long key) {
        Mesh old = MESHES.remove(key);
        if (old != null) cachedBytes -= old.byteSize();
    }

    static int averageColor(int a, int b, int c) {
        int r = ((a >>> 16 & 255) + (b >>> 16 & 255) + (c >>> 16 & 255)) / 3;
        int g = ((a >>> 8 & 255) + (b >>> 8 & 255) + (c >>> 8 & 255)) / 3;
        int blue = ((a & 255) + (b & 255) + (c & 255)) / 3;
        return r << 16 | g << 8 | blue;
    }
}
