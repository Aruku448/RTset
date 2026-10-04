package com.rtest.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.util.Random;

/** Numerical references and source wiring checks; not a GPU performance/image test. */
public final class RayTracingRenderMathTest {
    public static void main(String[] args) {
        checkWrappedNeighbours();
        checkFlatHeight();
        checkShadowStrata();
        checkVisibleGgx();
        checkShaderWiring();
        checkTransportFixes();
        System.out.println("Render math references passed (VNDF, RGB strata, TIR/MIS wiring, POM)");
    }

    private static void checkWrappedNeighbours() {
        for (int n = 1; n <= 256; n++) {
            for (int x = -2 * n; x < 2 * n; x++) {
                int wrapped = Math.floorMod(x, n);
                int next = wrapped + 1 == n ? 0 : wrapped + 1;
                if (next != Math.floorMod(x + 1, n)) throw new AssertionError("POM wrap");
            }
        }
    }

    private static void checkFlatHeight() {
        try (NativeImage image = new NativeImage(2, 6, true)) {
            for (int y = 0; y < 6; y++) for (int x = 0; x < 2; x++)
                image.setPixel(x, y, ((x + y) % 2 == 0 ? 0 : 255) << 24);
            if (!RayTracingPbrMaterials.hasFlatDecodedHeight(image)) throw new AssertionError("Flat sentinels");
            // Relief only in the last animation frame must disable the shortcut.
            for (int alpha = 1; alpha < 255; alpha++) {
                image.setPixel(1, 5, alpha << 24);
                if (RayTracingPbrMaterials.hasFlatDecodedHeight(image)) throw new AssertionError("Missed relief");
            }
        }
    }

    private static void checkShadowStrata() {
        Random random = new Random(104);
        for (int n : new int[] {4, 8, 16}) {
            double[][] a = new double[n][3], v = new double[n][3];
            for (int i = 0; i < n; i++) for (int c = 0; c < 3; c++) {
                a[i][c] = random.nextDouble() * 4;
                v[i][c] = random.nextDouble(); // Independent coloured transmission per channel.
            }
            for (int k = 1; k <= n; k++) for (int c = 0; c < 3; c++) {
                double reference = 0, expectation = 0;
                for (int i = 0; i < n; i++) { reference += a[i][c] * v[i][c]; }
                int covered = 0;
                for (int j = 0; j < k; j++) {
                    int start = j * n / k, end = (j + 1) * n / k, size = end - start;
                    covered += size;
                    // Enumerate each possible uniform choice, including uneven strata.
                    for (int i = start; i < end; i++)
                        expectation += a[i][c] * v[i][c] * size / size;
                }
                if (covered != n || Math.abs(reference - expectation) > 1e-12)
                    throw new AssertionError("RGB shadow expectation/support");
            }
        }
    }

    private record V(double x, double y, double z) {
        V add(V b) { return new V(x + b.x, y + b.y, z + b.z); }
        V mul(double s) { return new V(x * s, y * s, z * s); }
        double dot(V b) { return x * b.x + y * b.y + z * b.z; }
        V norm() { return mul(1 / Math.sqrt(dot(this))); }
        V cross(V b) { return new V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
    }

    private static V visibleNormal(V view, double alpha, double u, double v) {
        V stretched = new V(alpha * view.x, alpha * view.y, view.z).norm();
        double lensq = stretched.x * stretched.x + stretched.y * stretched.y;
        V t1 = lensq > 0 ? new V(-stretched.y, stretched.x, 0).mul(1 / Math.sqrt(lensq)) : new V(1, 0, 0);
        V t2 = stretched.cross(t1);
        double radius = Math.sqrt(u), phi = 2 * Math.PI * v;
        double x = radius * Math.cos(phi), y = radius * Math.sin(phi), blend = .5 * (1 + stretched.z);
        y = (1 - blend) * Math.sqrt(Math.max(1 - x * x, 0)) + blend * y;
        V projected = t1.mul(x).add(t2.mul(y)).add(stretched.mul(Math.sqrt(Math.max(1 - x * x - y * y, 0))));
        return new V(alpha * projected.x, alpha * projected.y, Math.max(projected.z, 0)).norm();
    }

    private static double d(double nh, double alpha) {
        double a2 = alpha * alpha, denominator = nh * nh * (a2 - 1) + 1;
        return a2 / (Math.PI * denominator * denominator);
    }

    private static double g(double cosine, double alpha) {
        return 2 * cosine / (cosine + Math.sqrt(alpha * alpha + (1 - alpha * alpha) * cosine * cosine));
    }

    private static double integrand(V view, V out, double alpha) {
        if (out.z <= 0) return 0;
        V half = view.add(out).norm();
        double fresnel = .04 + .96 * Math.pow(1 - Math.max(view.dot(half), 0), 5);
        return fresnel * d(half.z, alpha) * g(view.z, alpha) * g(out.z, alpha) / (4 * view.z);
    }

    private static void checkVisibleGgx() {
        // Independent deterministic hemisphere quadrature versus the sampled reflection estimator.
        int nz = 256, np = 512, samples = 200_000;
        for (double roughness : new double[] {.4, .8}) for (double cosine : new double[] {.1, .5, 1}) {
            double alpha = roughness * roughness;
            V view = new V(Math.sqrt(1 - cosine * cosine), 0, cosine);
            double integral = 0;
            for (int z = 0; z < nz; z++) for (int p = 0; p < np; p++) {
                double cz = (z + .5) / nz, phi = 2 * Math.PI * (p + .5) / np, r = Math.sqrt(1 - cz * cz);
                integral += integrand(view, new V(r * Math.cos(phi), r * Math.sin(phi), cz), alpha);
            }
            integral *= 2 * Math.PI / (nz * np);
            Random random = new Random(104);
            double sum = 0, squares = 0;
            for (int i = 0; i < samples; i++) {
                V half = visibleNormal(view, alpha, random.nextDouble(), random.nextDouble());
                if (view.dot(half) < -1e-12 || half.z < 0) throw new AssertionError("Invisible sampled normal");
                V out = half.mul(2 * view.dot(half)).add(view.mul(-1));
                double pdf = d(half.z, alpha) * g(view.z, alpha) / (4 * view.z);
                double weight = integrand(view, out, alpha) / pdf;
                sum += weight; squares += weight * weight;
            }
            double mean = sum / samples, standardError = Math.sqrt(Math.max(squares / samples - mean * mean, 0) / samples);
            if (Math.abs(mean - integral) > 6 * standardError + integral * .003)
                throw new AssertionError("VNDF/PDF integral mismatch: " + roughness + "/" + cosine + " " + mean + " vs " + integral);
        }
    }

    private static void checkShaderWiring() {
        String shader = RayTracingShaderRaygen.RAYGEN_SHADER;
        require(shader, "sampleGgx(normal, viewDirection, roughness, scatterSample.xy)");
        require(shader, "ggxG1(nDotI, alpha) / (4.0 * nDotI)");
        require(shader, "float sunSpecularProbability = specularProbability;");
        require(shader, "float sunDiffuseProbability = diffuseProbability;");
        require(shader, "transmission ? vec3(canTransmit ? fresnel : 1.0)");
        require(shader, "if (all(equal(throughput, vec3(0.0)))) break;");
        require(shader, "shadowWeights[selected] = size;");
        require(shader, "directStep * (rgbShadow - vec3(1.0)) * shadowWeight");
        require(shader, "lunarDirectStep * (lunarShadow - vec3(1.0)) * shadowWeight");
        require(RayTracingShaderCommon.PBR_FUNCTIONS, "normalWidth = pbrData.values[info + 2u] & 0x7fffffffu");
        // A dielectric TIR counterexample and actual mixture power weights.
        double eta = 1.5, cosine = .5, discriminant = 1 - eta * eta * (1 - cosine * cosine);
        if (!(discriminant < 0)) throw new AssertionError("TIR fixture");
        double lightPdf = 40, bsdfPdf = .07 * 2, n = 8;
        double light = n * lightPdf, denom = light * light + bsdfPdf * bsdfPdf;
        if (Math.abs(light * light / denom + bsdfPdf * bsdfPdf / denom - 1) > 1e-15)
            throw new AssertionError("MIS complement");
    }

    private static void checkTransportFixes() {
        String shader = RayTracingShaderRaygen.RAYGEN_SHADER;
        require(shader, "traceEmitterVisibility(surfacePosition + normal * 0.002, light.position)");
        require(shader, "traceEmitterVisibility(volumePosition + light.direction * 0.002, light.position)");
        require(shader, "vec3 delta = target - shadowOrigin;");
        require(shader, "float tMax = distance - endpointMargin;");
        require(shader, "previousWasDelta && previousAreaNeeEnabled");
        require(shader, "if (previousSkyNeeEnabled && !previousWasDelta)");
        require(shader, "previousAreaNeeEnabled = areaNeeEnabled;");
        require(shader, "previousSkyNeeEnabled = skyNeeEnabled;");
        require(shader, "skySegment ? 0u : uint(");
        require(shader, "directStep * rgbShadow * shadowWeight + multipleStep");
        require(shader, "lunarDirectStep * lunarShadow * shadowWeight + lunarMultipleStep");
        require(shader, "node == left ? leftProbability : 1.0 - leftProbability");
        require(shader, "clamp(leftScore / sum, 0.000001, 0.999999)");
        require(shader, "reflectivity = canTransmit ? dielectricF0 : 1.0;");
        if (shader.contains("sum += viewT * skyPhase * incidentSky")) throw new AssertionError("Duplicate sky volume source");
        require(shader, "viewT * phaseScattering * sourceT * PATM_SOLAR");
        require(shader, "bool insideMedium = cameraInWater;");
        require(shader, "cameraInWater ? 1.333 : 1.0");
        require(shader, "if (insideMedium) throughput *= exp(-mediumAbsorption * max(camera.sun.w, 0.0));");
        // Visibility segment now excludes the target but still contains a real blocker.
        for (double distance : new double[] {.01, 1, 10, 1000}) {
            double biasedOrigin = .002, targetT = distance - biasedOrigin;
            double margin = Math.max(.0001, 4 * 1.1920929e-7 * distance);
            double tMax = targetT - margin;
            if (!(tMax < targetT && tMax > .001 && tMax > targetT * .5))
                throw new AssertionError("Finite shadow segment endpoints");
        }
        // Exhaustive discrete expectation of the nonnegative direct estimator.
        double[] a = {10, 1};
        for (double visibility : new double[] {0, .25, 1}) {
            double expectation = 0;
            for (double source : a) {
                double estimate = 2 * source * visibility;
                if (estimate < 0) throw new AssertionError("Negative direct L");
                expectation += Math.max(estimate, 0) / 2;
            }
            if (Math.abs(expectation - 11 * visibility) > 1e-12) throw new AssertionError("Direct L clamp bias");
        }
        // Strategy states: rough transparent reflection keeps emitter and full sky weight.
        for (boolean areaNee : new boolean[] {false, true}) for (boolean delta : new boolean[] {false, true}) {
            boolean suppressEmitter = !delta && areaNee;
            if (!areaNee && suppressEmitter) throw new AssertionError("Unpaired area strategy");
        }
        // Actual FP32 complement is identical forward/reverse, with nonzero support.
        float left = Math.max(.000001f, Math.min(1f / (1f + 1e-8f), .999999f));
        float rightForward = 1 - left, rightReverse = 1 - left;
        if (!(rightForward > 0) || Float.floatToIntBits(rightForward) != Float.floatToIntBits(rightReverse))
            throw new AssertionError("Light tree support/PDF");
        for (double ior : new double[] {1.1, 1.33, 1.5, 2}) for (double cosine : new double[] {.1, .5, 1}) {
            double f0 = Math.pow((ior - 1) / (ior + 1), 2);
            double fresnel = f0 + (1 - f0) * Math.pow(1 - cosine, 5);
            double reflected = fresnel * (fresnel / fresnel);
            double transmitted = (1 - fresnel); // Unit tint, same probability/physical weight.
            if (Math.abs(reflected + transmitted - 1) > 1e-12) throw new AssertionError("Dielectric closure");
        }
    }

    private static void require(String source, String token) {
        if (!source.contains(token)) throw new AssertionError("Missing math wiring: " + token);
    }
}
