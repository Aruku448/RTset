package com.rtest.client;

import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.system.MemoryUtil;

/** Compiles the real query seam and actual raygen; this is not GPU/numerical validation. */
public final class RayTracingAtmosphereShaderTest {
    private RayTracingAtmosphereShaderTest() {
    }

    // Can also be dispatched by a Vulkan numerical harness with the real LUTs bound at 27-29.
    // Output pairs are sky and directional T. Cases exercise each chart, poles, horizon,
    // disk-edge directions, non-unit input, zero and NaN. Compilation alone proves no values.
    static final String QUERY_COMPUTE = """
            #version 460
            """ + RayTracingAtmosphereShader.GLSL + """
            layout(local_size_x = 1) in;
            layout(set = 0, binding = 30, std430) buffer QueryResults { vec4 values[]; } results;
            void main() {
                uint index = gl_GlobalInvocationID.x;
                vec3 direction;
                switch (index) {
                    case 0u: direction = vec3(0.0, 1.0, 0.0); break;
                    case 1u: direction = vec3(0.0, -1.0, 0.0); break;
                    case 2u: direction = vec3(1.0, 0.0, 0.0); break;
                    case 3u: direction = vec3(1.0, -0.001, 0.0); break;
                    case 4u: direction = vec3(1.0, -0.02, 0.0); break;
                    case 5u: direction = vec3(-1.0, 0.5, 0.0); break;
                    case 6u: direction = vec3(2.0, 1.0, 0.0); break;
                    case 7u: direction = vec3(0.0); break;
                    case 8u: direction = vec3(uintBitsToFloat(0x7fc00000u), 1.0, 0.0); break;
                    case 9u: direction = vec3(1.0, -0.00971, 0.0); break;
                    default: direction = vec3(1.0, -0.00029, 0.0); break;
                }
                vec3 sun = normalize(vec3(1.0, -0.005, 0.0));
                results.values[index * 2u] = vec4(physicalAtmosphereSky(direction, sun),
                    physicalAtmosphereEnabled() ? 1.0 : 0.0);
                results.values[index * 2u + 1u] = vec4(physicalAtmosphereSunTransmittance(direction), 1.0);
            }
            """;

    public static void main(String[] args) {
        verifyMoonContract();
        verifySharedAtmospherePreparation();
        verifySkyboxOpacityCurve();
        verifySkyboxBlendContract();
        verifySkyShadowTransport();
        java.nio.file.Path output = args.length == 0 ? null : java.nio.file.Path.of(args[0]);
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) {
            throw new AssertionError("Cannot initialize shaderc");
        }
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            for (boolean physical : new boolean[] {false, true}) {
                String suffix = physical ? "physical" : "legacy";
                compile(compiler, options, "atmosphere query " + suffix,
                    variant(QUERY_COMPUTE, physical), Shaderc.shaderc_glsl_compute_shader, output);
                compile(compiler, options, "actual raygen " + suffix,
                    variant(RayTracingShaderRaygen.RAYGEN_SHADER, physical), Shaderc.shaderc_glsl_raygen_shader, output);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("Atmosphere query + actual raygen: both variants compile (not GPU validation)");
    }

    private static void verifySharedAtmospherePreparation() {
        String source = RayTracingShaderRaygen.RAYGEN_SHADER;
        int function = source.indexOf("void integratePhysicalAtmosphereSegment(");
        int loop = source.indexOf("for (uint i = 1u; i <= count; i++)", function);
        int phase = source.indexOf("float solarPhaseCoordinate = physicalAtmPhaseCoord(nu);", function);
        if (!(phase > function && phase < loop)) {
            throw new AssertionError("Solar phase mapping must be prepared before integration steps");
        }
        int prepare = source.indexOf("PhysicalAtmIndirectHeight indirectHeight = physicalAtmCoherentIndirectHeight(hp, previousHeight);", loop);
        int lunar = source.indexOf("lunarMultipleStep = physicalAtmRadiance(", prepare);
        int advance = source.indexOf("spectralTransmittance *= trans;", prepare);
        int visibility = source.indexOf("traceRayEXT(", prepare);
        if (!(prepare > loop && lunar > prepare && advance > lunar && visibility > advance)) {
            throw new AssertionError("Resolve both spectral sources before visibility without advancing T early");
        }
    }

    private static void verifySkyShadowTransport() {
        // A signed near-source replacement preserves an unoccluded sky and removes only the
        // shadowed direct scattering. Clamping the residual positive would erase this effect.
        double farAndMultiple = 0.7, nearDirect = 0.2;
        for (double visibility : new double[] {0.0, 0.25, 1.0}) {
            double sky = farAndMultiple + nearDirect;
            double residual = nearDirect * (visibility - 1.0);
            double corrected = sky + residual;
            if (Math.abs(corrected - (farAndMultiple + nearDirect * visibility)) > 1e-12) {
                throw new AssertionError("Sky near scattering must be replaced exactly once");
            }
        }
        String source = RayTracingShaderRaygen.RAYGEN_SHADER;
        if (source.contains("if (physicalAtmosphereEnabled() && primaryHit)")
            || !source.contains("solarResidual = directStep * (rgbShadow - vec3(1.0)) * shadowWeight")
            || !source.contains("lunarResidual = lunarDirectStep * (lunarShadow - vec3(1.0)) * shadowWeight")
            || !source.contains("vec4(atmosphereInscatter, primaryHit ? 0.0 : 1.0)")) {
            throw new AssertionError("Physical sky misses must integrate and publish a signed shadow residual");
        }
        try {
            String composite = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/prime/shaders/aerial_perspective_composite.comp"));
            if (!composite.contains("<= 0.0 && !skyCorrection")
                || !composite.contains("vec3 contribution = inScatter;")) {
                throw new AssertionError("Aerial composite must retain signed sky corrections");
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void verifyMoonContract() {
        // Validate the uploaded spare-lane representation at every phase/intensity extreme.
        for (int phase = 0; phase < 8; phase++) {
            for (float intensity : new float[] {0.0F, 0.06F, 0.5F, 1.0F}) {
                float packed = phase + 1.0F + intensity * 0.25F;
                float decoded = 4.0F * (packed - (float)Math.floor(packed));
                if ((int)(packed + 0.5F) - 1 != phase || Math.abs(decoded - intensity) > 4e-6F) {
                    throw new AssertionError("Lunar irradiance must not corrupt the phase index");
                }
            }
        }
        String source = RayTracingShaderRaygen.RAYGEN_SHADER;
        if (!source.contains(RayTracingMoonShader.GLSL)
            || !source.contains("if (phaseIndex == 4) return vec3(0.0)")
            || !source.contains("vec3(camera.origin.w, camera.environment.w, camera.jitter.z), camera.jitter.w")
            || !source.contains("throughput * moonRadiance * moonTransmittance * weatherVisibility")
            || !source.contains("camera.jitter.xy")
            || source.indexOf("visibleMoonRadiance(rayDirection,") < source.indexOf("skyRadiance = mix(skyRadiance, textureRadiance,")) {
            throw new AssertionError("Moon must share misses after PNG blend, preserve new moon and jitter, and transmit actual ray direction");
        }
        for (String token : new String[] {"const float MOON_ANGULAR_RADIUS = 0.01884",
            "float moonFullIrradiance(float phaseToken) { return 4.0 * fract(phaseToken); }", "PRIME_SAMPLE_EFFECT_DIRECT_MOON = 6u",
            "sampleBase, PRIME_SAMPLE_EFFECT_DIRECT_MOON, uint(bounce)",
            "float moonPdf = 1.0 / max(moonSolidAngle(), 1e-8)",
            "float moonNeeMisWeight = bounce + 1 >= maxPathSegments ? 1.0",
            "powerHeuristic(moonPdf, moonBsdf.pdf)", "moonNeeMisWeight / moonPdf",
            "powerHeuristic(previousBsdfPdf, 1.0 / max(moonSolidAngle(), 1e-8))",
            "moonDiffuseContribution + areaDirectDiffuseContribution",
            "moonSpecularContribution + areaDirectSpecularContribution",
            "primaryDynamicShadow || sunDynamicOccluder || moonDynamicOccluder",
            "physicalAtmIndirect(hp, dot(localUp, ray), lunarMu, lunarNu, c,",
            "lunarDirectStep * lunarShadow * shadowWeight + lunarMultipleStep",
            "lunarIrradiance / PATM_SPACE_SUN", "nightAmbientScattering + lunarScattering"}) {
            if (!source.contains(token)) throw new AssertionError("Missing lunar light/PDF/volume contract: " + token);
        }
        int moonBsdf = source.indexOf("BsdfValue moonBsdf = evaluateBsdf(");
        int moonPdf = source.indexOf("float moonPdf", moonBsdf);
        if (moonBsdf < 0 || moonPdf < moonBsdf
            || !source.substring(moonBsdf, moonPdf).contains("diffuseProbability, specularProbability, receiverEnergy)")
            || source.indexOf("specularProbability /= probabilitySum;") > moonBsdf) {
            throw new AssertionError("Lunar NEE must use the actual normalized continuation PDF, including dielectric Fresnel");
        }
        try {
            String pass = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
            if (pass.indexOf("int nextMoonPhaseToken") > pass.indexOf("RtestFsr3Upscaler.FrameToken fsrToken = this.fsr.beginFrame")) {
                throw new AssertionError("Moon phase changes must reset history before beginFrame snapshots it");
            }
            for (String token : new String[] {"EnvironmentAttributes.MOON_ANGLE", "EnvironmentAttributes.MOON_PHASE",
                "buffer.putFloat(12, moonDirectionX)", ".putFloat(124, moonDirectionY)",
                ".putFloat(232, moonDirectionZ).putFloat(236, moonPhaseToken)",
                "this.lastMoonEnabled != moonEnabled", "moonEnabled && level.dimensionType().hasSkyLight()",
                "+ this.lastMoonIntensity * 0.25F", "Float.compare(this.lastMoonIntensity, nextMoonIntensity) != 0"}) {
                if (!pass.contains(token)) throw new AssertionError("Missing moon upload/history contract: " + token);
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void verifySkyboxOpacityCurve() {
        if (SkyboxOpacityCurve.resolveOpacity(true, .99F, 4, 0) != .15F
                || SkyboxOpacityCurve.resolveOpacity(false, .99F, 4, 0) != .99F)
            throw new AssertionError("Curve must override manual opacity only when enabled");
        float previousSun = -1;
        for (int i = 0; i <= 110; i++) {
            float value = SkyboxOpacityCurve.resolveSunIntensity(true, 8, 12, 4 + i * .1F);
            if (value < previousSun || value < 3 || value > 12)
                throw new AssertionError("Solar daylight curve must be bounded and monotonic");
            previousSun = value;
        }
        if (SkyboxOpacityCurve.resolveSunIntensity(true, 8, 12, 4) != 3
                || SkyboxOpacityCurve.resolveSunIntensity(true, 8, 12, 15) != 12
                || SkyboxOpacityCurve.resolveSunIntensity(true, 8, 12, 9.5F) != 7.5F
                || SkyboxOpacityCurve.resolveSunIntensity(false, 8, 12, 4) != 8
                || SkyboxOpacityCurve.resolveSunIntensity(true, 8, 12, Float.NaN) != 3)
            throw new AssertionError("Solar daylight endpoints/manual/invalid behavior");
        if (SkyboxOpacityCurve.fromSkyLightLevel(8, 23000) != .09F)
            throw new AssertionError("Pre-sunrise sky light level 8 must have 9% opacity");
        float[][] expected = {
            {0, .15F, 1, .10F, 2, .08F, (8.0F - 4.0F) * (15.0F / 11.0F), .09F, 6, .13F, 8, .20F, 9, .35F, 10, .60F, 11, .70F, 15, .90F},
            {0, .15F, 1, .20F, 2, .30F, 10, .40F, 13, .70F, 15, .90F}
        };
        for (int branch = 0; branch < expected.length; branch++) {
            boolean afternoon = branch == 1;
            float[] points = expected[branch];
            for (int i = 0; i < points.length; i += 2) {
                if (SkyboxOpacityCurve.fromBrightness(points[i], afternoon) != points[i + 1]) {
                    throw new AssertionError("Authored skybox control point changed: branch=" + branch + " brightness=" + points[i]);
                }
                if (i == 0) continue;
                float previous = points[i - 1];
                boolean increasing = points[i + 1] >= previous;
                for (int sample = 1; sample <= 100; sample++) {
                    float b = points[i - 2] + (points[i] - points[i - 2]) * sample / 100.0F;
                    float value = SkyboxOpacityCurve.fromBrightness(b, afternoon);
                    if (value < Math.min(points[i - 1], points[i + 1]) - 1e-6F
                        || value > Math.max(points[i - 1], points[i + 1]) + 1e-6F
                        || (increasing ? value < previous - 1e-6F : value > previous + 1e-6F)) {
                        throw new AssertionError("Opacity segment overshoots or reverses direction");
                    }
                    previous = value;
                }
                float mid = (points[i] + points[i - 2]) * .5F;
                if (Math.abs(SkyboxOpacityCurve.fromBrightness(mid, afternoon)
                        - (points[i + 1] + points[i - 1]) * .5F) > 1e-6F) {
                    throw new AssertionError("Half-cosine interpolation midpoint changed");
                }
            }
            for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1}) {
                if (SkyboxOpacityCurve.fromBrightness(invalid, afternoon) != .15F) {
                    throw new AssertionError("Invalid brightness must have neutral night opacity");
                }
            }
            if (SkyboxOpacityCurve.fromBrightness(16, afternoon) != .90F) throw new AssertionError("Upper clamp");
        }
        for (long time : new long[] {-1, 0, 5999, 6000, 12000, 17999, 18000, 23999, 24000}) {
            if (SkyboxOpacityCurve.fromSkyLightLevel(4, time) != .15F
                || SkyboxOpacityCurve.fromSkyLightLevel(15, time) != .90F) {
                throw new AssertionError("Day/night endpoints and phase seams must agree");
            }
            long wrapped = Math.floorMod(time, 24000L);
            boolean afternoon = wrapped >= 6000 && wrapped < 18000;
            if (Math.abs(SkyboxOpacityCurve.fromSkyLightLevel(4 + 10 * 11 / 15.0F, time)
                    - (afternoon ? .40F : .60F)) > 1e-6F) {
                throw new AssertionError("Game sky-light normalization or noon branch selection changed");
            }
        }
    }

    private static void verifySkyboxBlendContract() {
        String source = RayTracingShaderRaygen.RAYGEN_SHADER;
        int physicalSky = source.indexOf("skyRadiance = throughput * physicalAtmosphereSky(");
        int blend = source.indexOf("skyRadiance = mix(skyRadiance, textureRadiance,");
        int solar = source.indexOf("bool sunIsValid", blend);
        if (physicalSky < 0 || blend < physicalSky || solar < blend
            || !source.contains("clamp(atmosphereCamera.parameters.y, 0.0, 1.0)")
            || !source.contains("camera.pbrParallaxSettings.z > 0.5 && atmosphereCamera.parameters.y > 0.0")
            || !source.contains("vec3 textureRadiance = throughput * skySrgbToWorking(")) {
            throw new AssertionError("Skybox must alpha-blend linear radiance on the shared miss path before the independent solar disk");
        }
        try {
            String pass = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/rtest/client/RayTracingVulkanPass.java"));
            if (!pass.contains(".putFloat(4, effectiveSkyboxTextureOpacity)")
                || !pass.contains("SkyboxOpacityCurve.resolveOpacity(")
                || pass.contains("!physicalSkyEnabled && skyboxDaylightOpacityEnabled")
                || !pass.contains("buffer.putFloat(96, effectiveSunIntensity)")
                || !pass.contains("SkyboxOpacityCurve.resolveSunIntensity(")
                || !pass.contains("EnvironmentAttributes.SKY_LIGHT_LEVEL")
                || !pass.contains("this.lastSkyboxDaylightOpacityEnabled != skyboxDaylightOpacityEnabled")
                || !pass.contains("Float.compare(this.lastSkyboxTextureOpacity, skyboxTextureOpacity) != 0")) {
                throw new AssertionError("Skybox opacity must upload in spare atmosphere lane and invalidate temporal history");
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static String variant(String source, boolean physical) {
        return physical ? source.replace("#version 460\n", "#version 460\n#define RTEST_ATMOSPHERE_LUT 1\n")
            : source;
    }

    private static void compile(long compiler, long options, String name, String source, int stage,
                                java.nio.file.Path output) {
        var sourceBytes = MemoryUtil.memUTF8(source, false);
        var sourceName = MemoryUtil.memUTF8(name, true);
        var entryPoint = MemoryUtil.memASCII("main", true);
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, sourceBytes, stage, sourceName, entryPoint, options);
        } finally {
            MemoryUtil.memFree(entryPoint);
            MemoryUtil.memFree(sourceName);
            MemoryUtil.memFree(sourceBytes);
        }
        if (result == 0) {
            throw new AssertionError(name + ": shaderc returned no result");
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError(name + ": " + Shaderc.shaderc_result_get_error_message(result));
            }
            if (Shaderc.shaderc_result_get_length(result) == 0) {
                throw new AssertionError(name + ": empty SPIR-V");
            }
            if (output != null) {
                var bytes = Shaderc.shaderc_result_get_bytes(result);
                byte[] encoded = new byte[bytes.remaining()];
                bytes.get(encoded);
                try {
                    java.nio.file.Files.createDirectories(output);
                    java.nio.file.Files.write(output.resolve(name.replace(' ', '_') + ".spv"), encoded);
                } catch (java.io.IOException failure) {
                    throw new AssertionError("Cannot retain compiled query artifact", failure);
                }
            }
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }
}
