package com.rtest.client;

/** Explicit output-contract policy for HDR/SDR target and overlay composition. */
public final class HdrFrameGraphPolicy {
    public record Contract(HdrSupport.OutputMode mode, boolean sceneLinear, boolean sRgbEncoded,
                           boolean toneMapRequired, long generation) { }

    private HdrFrameGraphPolicy() {
    }

    public static Contract contract(HdrSupport.OutputMode mode, long generation) {
        if (mode == null) throw new NullPointerException("mode");
        boolean hdr = mode != HdrSupport.OutputMode.SDR;
        return new Contract(mode, hdr, !hdr, !hdr, generation);
    }

    public static boolean requiresRebuild(Contract previous, Contract current) {
        return previous == null || current == null
            || previous.mode() != current.mode()
            || previous.generation() != current.generation();
    }
}
