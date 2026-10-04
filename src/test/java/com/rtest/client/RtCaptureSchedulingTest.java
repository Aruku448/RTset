package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Drives the same capture routing used by Probe, including an aging dirty batch. */
public final class RtCaptureSchedulingTest {
    private RtCaptureSchedulingTest() { }

    public static void main(String[] args) throws Exception {
        String probe = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingProbe.java"));
        require(probe, "RtCaptureSchedule captureSchedule = RtCaptureSchedule.select(");
        require(probe, "if (captureSchedule == RtCaptureSchedule.DIRTY)");
        require(probe, "else if (captureSchedule == RtCaptureSchedule.FULL)");
        // A streaming window/block notification starts the batching timer, not a full rebuild.
        int fullStarts = 0, dirtyStarts = 0;
        for (int frame = 0; frame < 20; frame++) {
            RtCaptureSchedule decision = select(true, false, true, frame == 19);
            if (decision == RtCaptureSchedule.FULL) fullStarts++;
            if (decision == RtCaptureSchedule.DIRTY) dirtyStarts++;
            if (frame < 19 && decision != RtCaptureSchedule.NONE) {
                throw new AssertionError("young dirty batch started whole-scene capture at frame " + frame);
            }
        }
        if (fullStarts != 0 || dirtyStarts != 1) throw new AssertionError("incremental batching was bypassed");
        for (boolean ready : new boolean[] {false, true}) {
            requireDecision(false, false, true, ready, RtCaptureSchedule.FULL);
            requireDecision(true, true, true, ready, RtCaptureSchedule.FULL);
            requireDecision(true, false, false, ready, RtCaptureSchedule.NONE);
        }
        requireDecision(true, false, true, false, RtCaptureSchedule.NONE);
        requireDecision(true, false, true, true, RtCaptureSchedule.DIRTY);
        // Hard invalidation compatibility checks/worker lifetime must stay in Probe.
        require(probe, "capturedResourceGeneration != resourceGeneration");
        require(probe, "completedBuild.level() == capturedLevel");
        require(probe, "completedBuild.geometry().renderDistanceChunks == renderDistanceChunks");
        require(probe, "awaitGeometryFuture(fullBuild);");
        require(probe, "if (sceneDirty || fullCaptureRequested || pendingFullGeometryBuild != null");
        System.out.println("RT dirty-batch scheduling regression passed (not live capture-latency measurement)");
    }

    private static RtCaptureSchedule select(boolean snapshot, boolean full, boolean dirty, boolean ready) {
        return RtCaptureSchedule.select(snapshot, full, dirty, ready);
    }

    private static void requireDecision(boolean snapshot, boolean full, boolean dirty, boolean ready,
                                        RtCaptureSchedule expected) {
        RtCaptureSchedule actual = select(snapshot, full, dirty, ready);
        if (actual != expected) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) throw new AssertionError("missing scheduling integration: " + fragment);
    }
}
