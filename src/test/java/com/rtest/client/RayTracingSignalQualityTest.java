package com.rtest.client;

/** CPU mathematics and actual generated GLSL contracts; not a GPU pixel/quality test. */
public final class RayTracingSignalQualityTest {
    public static void main(String[] args) {
        assertShaderContract();
        assertKnownTransportExamples();
        System.out.println("Ray-tracing signal quality contracts passed (non-GPU)");
    }

    private static void assertShaderContract() {
        String shader = RayTracingShaderRaygen.RAYGEN_SHADER;
        require(shader, "struct BsdfValue { vec3 f; vec3 diffuse; float pdf; };");
        require(shader, "result.diffuse = vec3(0.0);");
        require(shader, "result.diffuse = diffuseMaterialWeight * baseColor * (vec3(1.0) - F)");
        require(shader, "result.f = result.diffuse + F * specular * specularEnergy;");
        String share = function(shader, "vec3 bsdfDiffuseShare(");
        for (String channel : new String[] {"r", "g", "b"}) {
            require(share, "value.f." + channel + " > 0.0 ? value.diffuse." + channel
                + " / value.f." + channel + " : 0.0");
        }
        reject(share, "epsilon");
        reject(share, "max(");
        require(shader, "vec3 primaryDiffuseShare = vec3(0.0);");
        String scatter = shader.substring(shader.indexOf("if (canTransmit && choice"),
            shader.indexOf("// RR follows emission"));
        equal(count(scatter, "if (bounce == 0) primaryDiffuseShare = bsdfDiffuseShare(sampled);"), 2);
        equal(count(scatter, "throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);"), 2);
        require(scatter, "throughput *= transmissionColor;");
        require(scatter, "throughput *= mirrorF / max(specularProbability, 1.0e-6);");
        reject(scatter, "primaryDiffuseShare = vec3(1.0)");
        require(shader, "transmissionRadiance += skyRadiance;");
        require(shader, "transmissionRadiance += localRadiance;");
        for (String contribution : new String[] {"skyRadiance", "localRadiance"}) {
            require(shader, "diffuseRadiance += " + contribution + " * primaryDiffuseShare;");
            require(shader, "indirectDiffuseRadiance += " + contribution + " * primaryDiffuseShare;");
            require(shader, "specularRadiance += " + contribution + " * (vec3(1.0) - primaryDiffuseShare);");
        }
        reject(shader, "else if (primarySpecularPath)");
        reject(shader, "if (primarySpecularPath)");
        equal(count(shader, "dot(throughput * primaryDiffuseShare, vec3(1.0)) > 0.0"), 2);
        equal(count(shader, "dot(throughput * (vec3(1.0) - primaryDiffuseShare), vec3(1.0)) > 0.0"), 2);
        require(shader, "diffuseHitDistance = firstBounceDistance;");
        require(shader, "specularHitDistance = firstBounceDistance;");
        require(shader, "float firstBounceDistance = length(pathPosition.xyz - rayOrigin);");
        require(shader, "const float nrdHitDistanceScale = 64.0;");
        require(shader, "specularHitDistance = nrdHitDistanceScale;");
        require(shader, "diffuseHitDistance = nrdHitDistanceScale;");
        require(shader, "float diffuseSignalDistance = diffuseHitDistance;");
        require(shader, "float specularSignalDistance = specularHitDistance;");
        reject(shader, "primaryHitDistance > 0.0 ? primaryHitDistance");
        require(shader, "sampleBase.sampleIndex = floatBitsToUint(camera.random.x);");
        reject(shader, "floatBitsToUint(camera.random.x) * 4u");
        require(shader, "sampleBase.vertexIndex = uint(bounce);");
        require(function(shader, "uint primeSampleBaseSeed("), "return primeHashCombine(seed, base.vertexIndex);");
        require(function(shader, "uint primeEffectSeed("), "primeHashCombine(primeSampleBaseSeed(base), effect)");
        for (String dimensions : new String[] {"1D", "2D", "3D"}) {
            String sampler = function(shader, "primeSobolSample" + dimensions + "(");
            require(sampler, "primeEffectSeed(base, effect) ^ primeHighQualityHash(dimension");
            require(sampler, "bitfieldReverse(base.sampleIndex)");
            require(sampler, "& PRIME_SOBOL_INDEX_MASK");
        }
        require(shader, "throughput /= survivalProbability;");
        require(shader, "float selectionPdf;");
        require(shader, "result.selectionPdf = selectionPdf;");
        require(shader, "return sampleAreaLightAtIndex(surfacePosition, emitterIndex, treePdf, sampleValue.yz);");
        String proposal = function(shader, "AreaLightSample sampleVolumeAreaLight(");
        require(proposal, "pickLightLeaf(volumePosition, sampleValue.x * 2.0, treePdf)");
        require(proposal, "emitterSelectionPdf(volumePosition, emitterIndex)");
        require(proposal, "0.5 * (treePdf + 1.0 / float(emitterCount))");
        String volume = function(shader, "vec3 samplePhysicalVolumeEmitter(");
        require(volume, "PRIME_SAMPLE_EFFECT_VOLUME_DISTANCE");
        require(volume, "PRIME_SAMPLE_EFFECT_VOLUME_EMITTER");
        require(volume, "sampleVolumeAreaLight(volumePosition");
        require(volume, "volumePosition + light.direction * 0.002");
        require(volume, "vec3 visibility = mix(vec3(1.0), shadowTransmittance, camera.settings.y);");
        require(volume, "dynamicOccluder = dynamicOccluder || sampleDynamic;");
        require(volume, "physicalAtmLocalSpectralPhaseScattering(");
        require(volume, "vec4 viewT = physicalAtmLocalSpectralTransmittance(");
        require(volume, "vec4 sourceT = physicalAtmLocalSpectralTransmittance(");
        require(volume, "sourceRadiance * visibility");
        require(volume, "/ light.pdf");
        require(volume, "segmentKm / float(count)");
        reject(volume, "primaryAreaVisibility");
        reject(volume, "fogWeight");
        require(function(shader, "vec4 physicalAtmLocalSpectralTransmittance("),
            "physicalAtmMedium(h).extinction * stepKm");
        require(shader, "primaryDynamicShadow = primaryDynamicShadow || volumeDynamicOccluder;");
    }

    private static void assertKnownTransportExamples() {
        // Full mixture f/q * downstream = 4; chosen proposal must not label the full f.
        for (boolean chosenSpecular : new boolean[] {false, true}) {
            double[] split = split(0.6, 0.2, 0.4, 2.0, 1.0, chosenSpecular);
            close(split[0], 3.0);
            close(split[1], 1.0);
            close(split[0] + split[1], 4.0);
        }
        // The uniform half keeps an otherwise unreachable emitter in the proposal. Using the
        // full mixture PDF in either branch recovers the sum of both source contributions.
        double[] treePdf = {1.0, 0.0}, source = {2.0, 4.0};
        double[] mixturePdf = {0.5 * (treePdf[0] + 0.5), 0.5 * (treePdf[1] + 0.5)};
        if (!(mixturePdf[1] > 0.0)) throw new AssertionError("Volume proposal loses source support");
        close(mixturePdf[0] * source[0] / mixturePdf[0]
            + mixturePdf[1] * source[1] / mixturePdf[1], 6.0);
        // Constant incident radiance and homogeneous extinction have a closed-form camera
        // integral. The stratified midpoint estimator must converge to that transport term.
        double sigmaT = 0.4, sigmaS = 0.08, lengthKm = 0.7;
        double exact = sigmaS * (1.0 - Math.exp(-sigmaT * lengthKm)) / sigmaT;
        double estimate = 0.0;
        for (int sample = 0; sample < 128; sample++) {
            double d = lengthKm * (sample + 0.5) / 128.0;
            estimate += Math.exp(-sigmaT * d) * sigmaS * lengthKm / 128.0;
        }
        if (Math.abs(estimate - exact) > 1e-7) throw new AssertionError("Volume path integral fixture");
        double[] rr = split(0.6, 0.2, 0.4, 2.0, 0.25, true);
        close(rr[0], 12.0);
        close(rr[1], 4.0);
        close(rr[0] + rr[1], 16.0);
        close(diffuseShare(0.0, 0.0), 0.0);
        close(diffuseShare(0.0, 0.2), 0.0);
        close(diffuseShare(0.6, 0.0), 1.0);
        close(diffuseShare(6e-30, 2e-30), 0.75); // No epsilon bias in a positive RGB component.
        double deltaRadiance = 7.0;
        close(deltaRadiance * 0.0, 0.0);
        close(deltaRadiance * (1.0 - 0.0), 7.0); // Mirror; transmission stays dedicated.
        // Three independent RGB channels, including zero energy and spectral masking.
        double[] fd = {0.6, 0.0, 0.0}, fs = {0.2, 0.0, 0.2}, beta = {4.0, 0.0, 9.0};
        for (int c = 0; c < 3; c++) {
            double share = diffuseShare(fd[c], fs[c]);
            close(beta[c] * share + beta[c] * (1.0 - share), beta[c]);
        }
    }

    private static double[] split(double fd, double fs, double pdf, double downstream,
            double survival, boolean chosenSpecular) {
        // Proposal label deliberately has no role in the estimator.
        double total = (fd + fs) / pdf * downstream / survival;
        double share = diffuseShare(fd, fs);
        return new double[] {total * share, total * (1.0 - share)};
    }

    private static double diffuseShare(double fd, double fs) {
        double total = fd + fs;
        return total > 0.0 ? fd / total : 0.0;
    }

    private static String function(String shader, String signature) {
        int start = shader.indexOf(signature);
        if (start < 0) throw new AssertionError("Missing function: " + signature);
        int open = shader.indexOf('{', start), end = open + 1, depth = 1;
        while (depth > 0 && end < shader.length()) {
            char c = shader.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        if (depth != 0) throw new AssertionError("Unclosed function: " + signature);
        return shader.substring(open, end);
    }

    private static int count(String text, String token) {
        return (text.length() - text.replace(token, "").length()) / token.length();
    }

    private static void require(String text, String token) {
        if (!text.contains(token)) throw new AssertionError("Missing GLSL contract: " + token);
    }

    private static void reject(String text, String token) {
        if (text.contains(token)) throw new AssertionError("Unexpected GLSL contract: " + token);
    }

    private static void equal(int actual, int expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }

    private static void close(double actual, double expected) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-12) {
            throw new AssertionError(actual + " != " + expected);
        }
    }
}
