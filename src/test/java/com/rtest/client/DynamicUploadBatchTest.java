package com.rtest.client;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;

/** Exercise packing and incremental publication against independent full-write references. */
public final class DynamicUploadBatchTest {
    public static void main(String[] args) {
        Random random = new Random(20261007);
        for (int trial = 0; trial < 200; trial++) {
            long[] sizes = new long[random.nextInt(100)];
            for (int i = 0; i < sizes.length; i++) sizes[i] = 1 + random.nextInt(3 * 1024 * 1024);
            checkPlan(sizes, 1L << random.nextInt(13));
        }
        checkPlan(new long[] {BlasBuildBatch.SCRATCH_BUDGET + 1, 17, 19}, 256);
        checkPlan(new long[0], 256);
        rejects(() -> BlasBuildBatch.plan(new long[] {1}, 3));
        rejects(() -> BlasBuildBatch.plan(new long[] {0}, 256));
        rejects(() -> BlasBuildBatch.plan(new long[] {Long.MAX_VALUE, 1}, 256));
        for (int trial = 0; trial < 250; trial++) {
            int stride = RayTracingDynamicInstances.MATERIAL_FLOATS_PER_TRIANGLE;
            int length = (1 + random.nextInt(40)) * stride;
            float[] old = new float[length];
            for (int i = 0; i < length; i++) old[i] = random.nextFloat();
            float[] next = old.clone();
            for (int i = 0; i < 20; i++) next[random.nextInt(length)] = random.nextFloat();
            checkDelta(old, next, false);
            checkDelta(old, old, false);
            checkDelta(old, next, true);
            checkDelta(null, next, false);
            checkDelta(new float[28], next, false);
        }
        float[] zero = new float[28], negativeZero = zero.clone();
        negativeZero[4] = -0.0f;
        checkDelta(zero, negativeZero, false);
        checkSnapshotReuse();
        System.out.println("Dynamic BLAS packing and material delta reference tests passed");
    }

    private static void checkPlan(long[] sizes, long alignment) {
        var plan = BlasBuildBatch.plan(sizes, alignment);
        int covered = 0;
        long maximum = 0;
        for (var batch : plan.batches()) {
            require(batch.first() == covered && batch.count() > 0 && batch.count() <= 32, "coverage");
            long previousEnd = 0;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(batch.count(), stack);
                for (int i = 0; i < batch.count(); i++) {
                    long offset = batch.offsets()[i];
                    require(offset % alignment == 0 && offset >= previousEnd, "overlap/alignment");
                    previousEnd = offset + sizes[covered + i];
                    // Same struct-copy operation used by the command recorder.
                    long address = 0x100000L + offset;
                    infos.get(i).set(AccelerationStructure.buildInfo(stack, 100 + covered + i,
                        0x200000L, address, 12, false, false).get(0));
                    require(infos.get(i).scratchData().deviceAddress() == address, "native scratch address");
                    require(infos.get(i).pGeometries().get(0).geometry().triangles()
                        .vertexData().deviceAddress() == 0x200000L, "geometry pointer lifetime");
                }
            }
            require(previousEnd == batch.bytes(), "arena bounds");
            require(batch.count() == 1 || batch.bytes() <= BlasBuildBatch.SCRATCH_BUDGET, "memory bound");
            maximum = Math.max(maximum, batch.bytes());
            covered += batch.count();
        }
        require(covered == sizes.length && maximum == plan.bytes(), "all builds retained");
    }

    private static void checkDelta(float[] previous, float[] current, boolean force) {
        int base = 2, stride = 28, prefix = base * stride;
        float[] actual = new float[prefix + current.length + 28];
        Arrays.fill(actual, 123.0f);
        if (previous != null && previous.length == current.length)
            System.arraycopy(previous, 0, actual, prefix, previous.length);
        float[] reference = actual.clone();
        System.arraycopy(current, 0, reference, prefix, current.length);
        List<long[]> ranges = new ArrayList<>();
        long bytes = DynamicMaterialDelta.write(FloatBuffer.wrap(actual), base, previous, current,
            force, (offset, size) -> ranges.add(new long[] {offset, size}));
        require(Arrays.equals(actual, reference), "diff must equal full upload including guards");
        long sum = 0, end = (long)prefix * 4;
        for (long[] range : ranges) {
            require(range[0] >= end && range[1] > 0 && range[0] + range[1] <= (long)(prefix + current.length) * 4,
                "dirty range bounds");
            end = range[0] + range[1];
            sum += range[1];
        }
        require(sum == bytes, "byte accounting");
        if (!force && previous != null && Arrays.equals(previous, current)) require(bytes == 0, "unchanged skip");
        if (force || previous == null || previous.length != current.length)
            require(bytes == (long)current.length * 4, "forced/full publication");
    }
    private static void checkSnapshotReuse() {
        float[] snapshot = null;
        float[] current = new float[28 * 100];
        FloatBuffer target = FloatBuffer.allocate(current.length);
        for (int frame = 0; frame < 200; frame++) {
            current = current.clone();
            current[(frame % 100) * 28] = frame + 1;
            float[] oldSnapshot = snapshot;
            List<long[]> ranges = new ArrayList<>();
            var upload = DynamicMaterialDelta.writeAndRemember(target, 0, snapshot, current,
                false, (offset, bytes) -> ranges.add(new long[] {offset, bytes}));
            snapshot = upload.snapshot();
            require(snapshot != current, "snapshot must own storage independent from mutable capture");
            require(Arrays.equals(snapshot, current) && Arrays.equals(target.array(), current), "snapshot/target publication");
            if (frame > 0) {
                require(snapshot == oldSnapshot, "stable mesh must reuse snapshot allocation");
                require(upload.bytes() == 28 * 4, "single changed row must not copy whole mesh");
                require(ranges.size() == 1, "one row flush interval");
            }
            float saved = snapshot[1];
            current[1] = 999;
            require(snapshot[1] == saved, "capture mutation must not invalidate snapshot");
            current[1] = saved;
        }
        float[] resized = new float[56];
        var replacement = DynamicMaterialDelta.writeAndRemember(FloatBuffer.allocate(56), 0,
            snapshot, resized, false, (offset, bytes) -> { });
        require(replacement.snapshot() != snapshot && replacement.snapshot() != resized
            && replacement.bytes() == 224, "topology change owns a complete replacement snapshot");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Invalid packing input accepted");
    }
}
