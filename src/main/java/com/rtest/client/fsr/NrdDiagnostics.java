package com.rtest.client.fsr;

/** Runtime control for the temporary NRD integration diagnostics. */
public final class NrdDiagnostics {
    private static volatile Mode mode = Mode.OFF;

    private NrdDiagnostics() {}

    public static Mode mode() {
        return mode;
    }

    public static Mode cycle() {
        Mode[] modes = Mode.values();
        Mode next = modes[(mode.ordinal() + 1) % modes.length];
        mode = next;
        return next;
    }

    public enum Mode {
        OFF(0, "off"),
        NRD_VALIDATION(1, "NRD validation"),
        REPROJECTION_ERROR(2, "reprojection error"),
        MOTION(3, "motion vectors");

        private final int shaderValue;
        private final String label;

        Mode(int shaderValue, String label) {
            this.shaderValue = shaderValue;
            this.label = label;
        }

        public int shaderValue() {
            return this.shaderValue;
        }

        public String label() {
            return this.label;
        }

        boolean enablesNrdValidation() {
            return this == NRD_VALIDATION;
        }
    }
}
