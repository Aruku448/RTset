package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Compensation ordering and fence/TLAS retirement policy; no claim of native GPU validation. */
public final class ResourceLifetimeMathTest {
    public static void main(String[] args) throws Exception {
        String pass = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
        int wait = pass.indexOf("private void waitForPreviousFrame");
        String noFence = pass.substring(pass.indexOf("if (fence == null)", wait),
            pass.indexOf("long fenceWaitStart", wait));
        if (noFence.contains("retireCompleted")) {
            throw new AssertionError("absence of a submission fence cannot retire old TLAS BLAS references");
        }
        int restore = pass.indexOf("rollback.restore(throwable);");
        if (restore < 0 || restore > pass.indexOf("nextTopLevel::close")) {
            throw new AssertionError("publication bindings must be restored before candidate destruction");
        }
        if (!pass.contains("this.dynamicBlasRetirement.completed()")
                || !pass.contains("this.dynamicBlasRetirement.submitted(holder.tlasBuildRecorded)")) {
            throw new AssertionError("dynamic retirement must follow the fence of an actually recorded TLAS build");
        }
        StringBuilder order = new StringBuilder();
        RuntimeException primary = new RuntimeException("publication failed");
        RtResourceRollback rollback = new RtResourceRollback();
        rollback.before(() -> order.append('A'));
        rollback.before(() -> { order.append('B'); throw new IllegalStateException("restore failed"); });
        rollback.restore(primary);
        RtResourceRollback.attempt(primary, () -> { order.append('C'); throw new IllegalStateException("destroy failed"); });
        RtResourceRollback.attempt(primary, () -> { throw primary; });
        if (!order.toString().equals("BAC") || primary.getSuppressed().length != 2) {
            throw new AssertionError("rollback must continue after failure and preserve original error");
        }
        rollback.restore(primary);
        if (!order.toString().equals("BAC")) throw new AssertionError("rollback repeats compensation");

        RtBlasRetirement retirement = new RtBlasRetirement();
        if (retirement.completed()) throw new AssertionError("initial references retired without TLAS fence");
        retirement.submitted(false);
        if (retirement.completed()) throw new AssertionError("copy/BLAS-only fence retired TLAS references");
        retirement.submitted(true);
        // An incomplete fence must not call completed(); the production timeout keeps it attached.
        if (!retirement.completed() || retirement.completed()) throw new AssertionError("TLAS retirement epoch");
        retirement.submitted(false);
        if (retirement.completed()) throw new AssertionError("stale TLAS epoch reused for presentation copy");
        System.out.println("Resource rollback/retirement math passed (native failure injection and GPU validation not performed)");
    }
}
