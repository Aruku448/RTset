package com.rtest.client.fsr;

import java.util.Arrays;
import java.util.Optional;

/** Developer-facing FSR visualization modes exposed through Minecraft's video settings. */
public enum RtestFsrDebugView {
    OFF("off"),
    OVERVIEW("overview");

    private final String id;

    RtestFsrDebugView(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<RtestFsrDebugView> findById(String id) {
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static RtestFsrDebugView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
