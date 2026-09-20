// Adapted from Prime 26.3 OfflineSession/PrimeRuntime screenshot-mode contracts.
// Prime licensing and additional permissions are preserved in third_party/.
package com.rtest.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.rtest.client.fsr.RtestFsrCamera;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/** Owns the frozen camera and sample identity for GPU offline running-mean accumulation. */
public final class OfflineRenderController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean active;
    private static RtestFsrCamera frozenCamera;
    private static long frozenSceneRevision = Long.MIN_VALUE;
    private static Environment frozenEnvironment;
    private static long sampleCount;

    private OfflineRenderController() {
    }

    public static synchronized boolean handleShortcut(Minecraft minecraft, InputConstants.Key key) {
        if (key.getValue() != InputConstants.KEY_F2
                || !InputConstants.isKeyDown(minecraft.getWindow(), InputConstants.KEY_RALT)
                || minecraft.level == null) {
            return false;
        }
        toggle(minecraft);
        return true;
    }

    /** Shared by the F9 control and Right Alt + F2. */
    public static synchronized boolean toggle(Minecraft minecraft) {
        if (minecraft.level == null && !active) {
            return false;
        }
        active = !active;
        frozenCamera = null;
        frozenEnvironment = null;
        frozenSceneRevision = Long.MIN_VALUE;
        sampleCount = 0L;
        LOGGER.info("RTest Prime offline accumulation {}", active ? "started" : "stopped");
        return active;
    }

    public static synchronized boolean active() {
        return active;
    }

    /** Freezes one coherent camera/scene identity, cancelling when the resident scene changes. */
    public static synchronized RtestFsrCamera camera(RtestFsrCamera current, long sceneRevision) {
        if (!active) {
            return current;
        }
        if (frozenCamera == null) {
            frozenCamera = current;
            frozenSceneRevision = sceneRevision;
            sampleCount = 0L;
        } else if (frozenSceneRevision != sceneRevision) {
            active = false;
            frozenCamera = null;
            frozenEnvironment = null;
            sampleCount = 0L;
            LOGGER.info("RTest Prime offline accumulation stopped after scene revision changed");
            return current;
        }
        return frozenCamera;
    }

    /** Keeps astronomical and weather lighting coherent with the frozen offline camera. */
    public static synchronized Environment environment(long clockTicks, float rain, float thunder) {
        Environment current = new Environment(clockTicks, rain, thunder);
        if (!active) {
            frozenEnvironment = null;
            return current;
        }
        if (frozenEnvironment == null) {
            frozenEnvironment = current;
        }
        return frozenEnvironment;
    }

    /** Returns the zero-based sample index consumed by Prime's running-mean recurrence. */
    public static synchronized long claimSample() {
        if (!active) {
            return -1L;
        }
        long index = sampleCount;
        sampleCount = Math.incrementExact(sampleCount);
        if (sampleCount > 0L && (sampleCount & (sampleCount - 1L)) == 0L) {
            LOGGER.info("RTest Prime offline accumulation reached {} samples", sampleCount);
        }
        return index;
    }

    public static synchronized long sampleCount() {
        return sampleCount;
    }

    public record Environment(long clockTicks, float rain, float thunder) {
    }
}
