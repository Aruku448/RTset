package com.rtest.client;

import com.rtest.client.fsr.NrdDenoiser;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real sun-discontinuity predicate plus source integration contracts; no Vulkan device. */
public final class DenoiserHistoryQualityTest {
    private DenoiserHistoryQualityTest() { }

    public static void main(String[] args) throws Exception {
        Method sunCut = NrdDenoiser.class.getDeclaredMethod("sunDirectionDiscontinuous",
            float.class, float.class, float.class, float.class, float.class, float.class);
        sunCut.setAccessible(true);
        for (double degrees : new double[] {0.0, 0.01, 0.5, 0.99, 1.01, 2.0, 90.0}) {
            double radians = Math.toRadians(degrees);
            boolean cut = (boolean) sunCut.invoke(null,
                (float) Math.sin(radians), (float) Math.cos(radians), 0.0F, 0.0F, 1.0F, 0.0F);
            if (cut != (degrees > 1.0)) {
                throw new AssertionError("unexpected NRD history cut at " + degrees + " degrees");
            }
        }
        String source = Files.readString(Path.of("src/main/java/com/rtest/client/fsr/RtestFsr3.java"));
        require(source, "boolean forceRestart = token.reset() || token.cameraCut();");
        require(source, "forceRestart || !this.nrdWasEnabled, nrdStrength");
        require(source, "if (nextDenoiserMode != this.frameDenoiserMode)");
        int begin = source.indexOf("public RtestFsr3Upscaler.FrameToken beginFrame(");
        int reset = source.indexOf("this.upscaler.requestReset();", begin);
        int consume = source.indexOf("return this.upscaler.beginFrame(", begin);
        if (reset < begin || reset > consume) {
            throw new AssertionError("mode changes must invalidate FSR before it snapshots frame reset");
        }
        String nrd = Files.readString(Path.of("src/main/java/com/rtest/client/fsr/NrdDenoiser.java"));
        require(nrd, "this.height, compositeStrength, denoisingRange);");
        require(nrd, "pushConstants.putFloat(denoisingRange);");
        require(nrd, "private static final int COMPOSITE_BINDING_COUNT = 10;");
        require(nrd, "images.directDiffuse,\n                    images.noisyDiffuse,\n                    images.noisySpecular\n                };");
        System.out.println("Denoiser history integration contracts passed (not rendered-image comparisons)");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) throw new AssertionError("missing history contract: " + fragment);
    }
}
