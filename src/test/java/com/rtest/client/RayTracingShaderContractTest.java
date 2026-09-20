package com.rtest.client;

import org.lwjgl.util.shaderc.Shaderc;

/** Compiles the active ray-tracing stages without creating a Vulkan device. */
public final class RayTracingShaderContractTest {
    private RayTracingShaderContractTest() {
    }

    public static void main(String[] args) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            compile(compiler, options, "raygen", RayTracingShaders.RAYGEN_SHADER, Shaderc.shaderc_glsl_raygen_shader);
            compile(compiler, options, "miss", RayTracingShaders.MISS_SHADER, Shaderc.shaderc_glsl_miss_shader);
            compile(compiler, options, "shadow miss", RayTracingShaders.SHADOW_MISS_SHADER, Shaderc.shaderc_glsl_miss_shader);
            compile(compiler, options, "closest hit", RayTracingShaders.CLOSEST_HIT_SHADER,
                Shaderc.shaderc_glsl_closesthit_shader);
            // Legacy reflection/GI/refraction stages are disabled; the active integrator is
            // RAYGEN + MISS + CLOSEST_HIT and owns all physical radiance.
            compile(compiler, options, "any hit", RayTracingShaders.ANY_HIT_SHADER,
                Shaderc.shaderc_glsl_anyhit_shader);
            compile(compiler, options, "shadow any hit", RayTracingShaders.SHADOW_ANY_HIT_SHADER,
                Shaderc.shaderc_glsl_anyhit_shader);
            assertRayBudgetContract();
            assertTemporalEnvironmentContract();
            assertPbrMaterialContract();
            assertPhysicalLightingContract();
            assertCutoutSamplingContract();
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
        System.out.println("Ray-tracing shader contract passed");
    }

    private static void assertCutoutSamplingContract() {
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)");
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "samplePlayerSkinCutout(uv, optical.x)");
        require(RayTracingShaders.ANY_HIT_SHADER,
            "texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER,
            "samplePlayerSkinCutout(uv, optical.x)");
    }

    private static void assertRayBudgetContract() {
        String shader = RayTracingShaders.RAYGEN_SHADER;
        require(shader, "const uint PRIMARY_RAY_MASK = 0x7fu;");
        require(shader, "const uint SECONDARY_RAY_MASK = 0xfeu;");
        require(shader, "(bounce == 0 ? PRIMARY_RAY_MASK : SECONDARY_RAY_MASK)");
        require(shader, "SECONDARY_RAY_MASK,\n");
        require(shader, "int giBounces = clamp(int(camera.parameters.w + 0.5), 1, 4);");
        require(shader, "int maxPathSegments = DIRECT_SUN_ONLY ? 1 : 1 + giBounces;");
        require(shader, "for (int bounce = 0; bounce < 5; bounce++) {");
        require(shader, "floatBitsToUint(camera.random.x)");
        require(shader, "floatBitsToUint(camera.random.y)");
        reject(shader, "gl_LaunchIDEXT.xy, 0u, 0u, 0u, 0u");
        require(shader, "sampleBase.sampleIndex = floatBitsToUint(camera.random.x) * 4u + uint(bounce);");
        reject(shader, "float survivalProbability = clamp(max(max(baseColor.r, baseColor.g), baseColor.b), 0.25, 0.9);");
        require(shader, "float betaLuminance = dot(max(throughput, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722));");
        require(shader, "throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);");
        require(shader, "float survivalProbability = clamp(betaLuminance, 0.05, 0.95);");
        require(shader, "if (bounce > 0) {");
        require(shader, "float probabilitySum = transmissionProbability + specularProbability + diffuseProbability;");
        require(shader, "if (!(betaLuminance > 0.0)) survivalProbability = 0.05;");
        require(shader, "float rouletteSample = primeSobolSample1D(");
        require(shader, "PRIME_SAMPLE_EFFECT_RUSSIAN_ROULETTE");
        require(shader, "if (rouletteSample > survivalProbability)");
        require(shader, "throughput /= survivalProbability;");
        require(shader, "layout(set = 0, binding = 19, std430) readonly buffer DynamicMotionMetadata");
        require(shader, "layout(location = 1) rayPayloadEXT vec3 shadowTransmittance;");
        require(shader, "layout(location = 2) rayPayloadEXT uint shadowDynamicOccluder;");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER,
            "layout(location = 2) rayPayloadInEXT uint shadowDynamicOccluder;");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER,
            "uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER,
            "shadowDynamicOccluder = 1u;");
        require(shader, "primaryDynamicShadow = primaryDynamicShadow || areaDirect.dynamicOccluder;");
        require(shader, "primaryDynamicShadow = primaryDynamicShadow || sunDynamicOccluder;");
        require(shader, "layout(set = 0, binding = 20, rgba16f) uniform writeonly image2D nrdMaterial;");
        require(RayTracingShaders.ANY_HIT_SHADER, "ignoreIntersectionEXT;");
        require(shader, "layout(set = 0, binding = 21, rgba16f) uniform writeonly image2D nrdDirectDiffuse;");
        require(shader, "layout(set = 0, binding = 22, rgba16f) uniform writeonly image2D nrdIndirectDiffuse;");
        require(shader, "layout(set = 0, binding = 23, rgba16f) uniform writeonly image2D nrdEmission;");
        require(shader, "layout(set = 0, binding = 24, rgba32f) uniform writeonly image2D nrdPrimaryPosition;");
        require(shader, "layout(set = 0, binding = 25, rgba16f) uniform writeonly image2D nrdSpecularMaterial;");
        require(shader, "bool nrdSignalMode = camera.dynamicParameters.w > 0.5;");
        require(shader, "vec4(nrdDiffuseRadiance, diffuseGeneratorHitDistance)");
        require(shader, "vec4(nrdSpecularRadiance, specularGeneratorHitDistance)");
        require(shader, "vec3 filteredDiffuseRadiance = directDiffuseRadiance");
        require(shader, "+ indirectDiffuseRadiance + areaDirectDiffuseRadiance;");
        require(shader, "vec3 nrdDiffuseRadiance = filteredDiffuseRadiance;");
        require(shader, "vec3 nrdSpecularRadiance = directSpecularRadiance");
        require(shader, "+ specularRadiance + transmissionRadiance");
        require(shader,
            "vec3 unfilteredRadiance = emissionRadiance;");
        require(shader, "directDiffuseRadiance += throughput * sunDiffuseContribution;");
        require(shader, "directSpecularRadiance += throughput * sunSpecularContribution;");
        require(shader, "areaDirectDiffuseRadiance += throughput * areaDirect.diffuse;");
        require(shader, "areaDirectSpecularRadiance += throughput * areaDirect.specular;");
        require(shader, "vec4(directAovRadiance, primaryDynamicShadow ? 1.0 : 0.0)");
        require(shader, "vec4(primaryBaseColor, primaryHit ? primaryRoughness : -1.0)");
        reject(shader, "preservePerfectSpecular");
        require(shader, "primaryDynamicSlot = pathDynamicSlot;");
        require(shader, "primaryDynamicFlags = floatBitsToUint(dynamicMotion.values[metadataBase + 6u].x);");
        require(shader, "if (bounce + 1 >= maxPathSegments) {");
        require(shader, "float diffuseSignalActive = primaryHit &&");
        require(shader, "vec3 nrdDiffuse = primaryHit ? vec3(");
        require(shader, "vec3 nrdSpecular = primaryHit ? vec3(");
        if (shader.contains("clamp(int(camera.parameters.w + 0.5), 3, 4)")) {
            throw new AssertionError("RayGen still counts primary ray in the giBounces range");
        }
    }

    private static void assertPhysicalLightingContract() {
        String[] activeShaders = {
            RayTracingShaders.RAYGEN_SHADER,
            RayTracingShaders.MISS_SHADER
        };
        for (String shader : activeShaders) {
            reject(shader, "blockLight");
            reject(shader, "skyLight");
            reject(shader, "skyContribution");
            reject(shader, "blockContribution");
            reject(shader, "lighting.z");
            reject(shader, "lighting.x");
            reject(shader, "throughput *= max(camera.parameters.z");
            reject(shader, "receivesLighting ?");
            reject(shader, "authoredEnvironment");
            reject(shader, "specularPower");
            reject(shader, "Blinn");
        }
        // Structural guards supplement compilation; visual sharpness needs GPU validation.
        require(RayTracingShaders.RAYGEN_SHADER, "if (roughness == 0.0) {");
        require(RayTracingShaders.RAYGEN_SHADER, "specular = 0.0;\n        specPdf = 0.0;");
        require(RayTracingShaders.RAYGEN_SHADER, "rayDirection = normalize(reflect(-viewDirection, normal));");
        require(RayTracingShaders.RAYGEN_SHADER, "throughput *= mirrorF / max(specularProbability, 1.0e-6);");
        require(RayTracingShaders.RAYGEN_SHADER, "float ggxD(float nDotH, float alpha)");
        require(RayTracingShaders.RAYGEN_SHADER, "float ggxG1(float nDotV, float alpha)");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 schlickFresnel(vec3 f0, float vDotH)");
        require(RayTracingShaders.RAYGEN_SHADER, "BsdfValue evaluateBsdf");
        require(RayTracingShaders.RAYGEN_SHADER,
            "float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,");
        require(RayTracingShaders.RAYGEN_SHADER,
            "float diffuseSamplingProbability, float specularSamplingProbability)");
        require(RayTracingShaders.RAYGEN_SHADER,
            "result.f = diffuseMaterialWeight * baseColor * (vec3(1.0) - F)");
        require(RayTracingShaders.RAYGEN_SHADER,
            "result.pdf = diffuseSamplingProbability * nDotO / BSDF_PI + specularSamplingProbability * specPdf;");
        reject(RayTracingShaders.RAYGEN_SHADER, "diffuseWeight * baseColor / BSDF_PI");
        reject(RayTracingShaders.RAYGEN_SHADER, "specularWeight * F * specular");
        require(RayTracingShaders.RAYGEN_SHADER, "float diffuseMaterialWeight = transmission ? 0.0 : (1.0 - metallic);");
        require(RayTracingShaders.RAYGEN_SHADER, "primeDefaultSpecularSampleProbability");
        require(RayTracingShaders.RAYGEN_SHADER, "float specularSamplingProbability = canTransmit");
        require(RayTracingShaders.RAYGEN_SHADER, "clamp(fresnel, 0.0, 1.0)");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 halfVector = sampleGgx(");
        require(RayTracingShaders.RAYGEN_SHADER, "scatterSample.xy);");
        require(RayTracingShaders.RAYGEN_SHADER, "throughput *= sampled.f * sampledCosine / max(sampled.pdf, 1.0e-6);");
        int transmissionBranch = RayTracingShaders.RAYGEN_SHADER.indexOf(
            "if (canTransmit && choice < transmissionProbability)");
        int transmissionAovClass = RayTracingShaders.RAYGEN_SHADER.indexOf(
            "selectedSpecularPath = true;", transmissionBranch);
        int specularBranch = RayTracingShaders.RAYGEN_SHADER.indexOf(
            "} else if (choice < transmissionProbability + specularProbability)", transmissionBranch);
        if (transmissionBranch < 0 || transmissionAovClass < transmissionBranch
            || specularBranch < transmissionAovClass) {
            throw new AssertionError("Transmission continuation must use the specular/transmission AOV class");
        }
        reject(RayTracingShaders.RAYGEN_SHADER, "throughput *= sampled.f * sampledCosine / max(sampled.pdf *");
        require(RayTracingShaders.RAYGEN_SHADER,
            "bool transmission = pathMaterial.w > 0.5 || pathOpticalLighting.x > 1.001;");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER,
            "if (surface.w > 1.0 || optical.w > 1.001)");
        require(RayTracingShaders.RAYGEN_SHADER, "bool canTransmit = transmission");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 mediumAbsorption = vec3(0.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "bool enteringMedium = !insideMedium;");
        require(RayTracingShaders.RAYGEN_SHADER, "throughput *= exp(-mediumAbsorption * mediumDistance);");
        require(RayTracingShaders.RAYGEN_SHADER, "float eta = interfaceEntering ? 1.0 / ior : ior;");
        require(RayTracingShaders.RAYGEN_SHADER, "float transmissionOpacity = clamp(pathLocalPosition.w, 0.0, 1.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 shadowFactor = mix(vec3(1.0), shadowTransmittance, camera.settings.y);");
        require(RayTracingShaders.CLOSEST_HIT_SHADER, "pathOpticalLighting = vec4(optical.w, optical.xyz);");
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "pathNormal = vec4(normal, clamp(lighting.y, 0.0, 1.0));");
        require(RayTracingShaders.RAYGEN_SHADER, "float primeRcDispersionIor(float nd, float vd, float scale, float lambda)");
        require(RayTracingShaders.RAYGEN_SHADER, "const vec3 SPECTRAL_WAVELENGTHS_NM = vec3(610.0, 550.0, 450.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "throughput *= spectralHeroWeight(spectralChannel);");
        require(RayTracingShaders.RAYGEN_SHADER, "const float SUN_ANGULAR_RADIUS_RADIANS = 0.00471;");
        require(RayTracingShaders.RAYGEN_SHADER,
            "return 2.0 * BSDF_PI * (1.0 - cos(SUN_ANGULAR_RADIUS_RADIANS));");
        require(RayTracingShaders.RAYGEN_SHADER, "float sunSolidAngle()");
        require(RayTracingShaders.RAYGEN_SHADER, "layout(set = 0, binding = 26, std430) readonly buffer LightData");
        require(RayTracingShaders.RAYGEN_SHADER,
            "layout(set = 0, binding = 5, std430) readonly buffer PbrData");
        require(RayTracingShaders.RAYGEN_SHADER, "uint pickLightLeaf(vec3 point, float seed, out float pdf)");
        require(RayTracingShaders.RAYGEN_SHADER, "float evaluateEmitterEmission(float fallbackEmission");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 evaluateEmitter(uint emitterIndex, vec2 barycentric)");
        require(RayTracingShaders.RAYGEN_SHADER, "evaluateEmitterEmission(");
        require(RayTracingShaders.RAYGEN_SHADER,
            "float emitterStrength = fallbackEmission > 0.0");
        require(RayTracingShaders.RAYGEN_SHADER,
            "? fallbackEmission");
        require(RayTracingShaders.RAYGEN_SHADER,
            ": max(camera.pbrSettings.z, camera.pbrParallaxSettings.y);");
        require(RayTracingShaders.RAYGEN_SHADER,
            "return authoredEmission * max(emitterStrength, 0.0);");
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "pbrEmission * camera.pbrSettings.z");
        require(RayTracingShaders.RAYGEN_SHADER, "float emitterSelectionPdf(vec3 point, uint emitterIndex)");
        require(RayTracingShaders.RAYGEN_SHADER, "float powerHeuristic(float firstPdf, float secondPdf)");
        require(RayTracingShaders.RAYGEN_SHADER, "AREA_SAMPLE_EFFECT");
        require(RayTracingShaders.RAYGEN_SHADER, "evaluateHitEmitter");
        require(RayTracingShaders.RAYGEN_SHADER, "bool sunDiskHit(vec3 rayDirection, vec3 sunDirection)");
        require(RayTracingShaders.RAYGEN_SHADER, "bool sunDiskIsHit = sunIsValid && sunDiskHit(rayDirection, camera.sun.xyz);");
        require(RayTracingShaders.RAYGEN_SHADER, "camera.settings.x / max(sunSolidAngle(), 1.0e-8)");
        require(RayTracingShaders.RAYGEN_SHADER, "skyRadiance += throughput * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))");
        require(RayTracingShaders.RAYGEN_SHADER, "* daylight * weatherVisibility;");
        require(RayTracingShaders.RAYGEN_SHADER, "if (!DIRECT_SUN_ONLY)");
        reject(RayTracingShaders.RAYGEN_SHADER, "emissionRadiance += throughput * vec3(camera.settings.x / max(sunSolidAngle(), 1.0e-8))");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 sampleSunDirection(vec3 sunDirection, vec2 sampleValue)");
        require(RayTracingShaders.RAYGEN_SHADER, "const uint SUN_SAMPLE_EFFECT");
        require(RayTracingShaders.RAYGEN_SHADER, "vec2 sunSample = primeSobolSample2D(");
        require(RayTracingShaders.RAYGEN_SHADER, "PRIME_SAMPLE_EFFECT_DIRECT_SUN");
        require(RayTracingShaders.RAYGEN_SHADER, "const uint PRIME_SOBOL_INDEX_MASK = 0xffff0000u;");
        require(RayTracingShaders.RAYGEN_SHADER, "uint primeReversedBitOwen(uint value, uint seed)");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 primeSobolSample3D(PrimeSampleBase base");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 sampledSunDirection = sampleSunDirection(camera.sun.xyz, sunSample);");
        require(RayTracingShaders.RAYGEN_SHADER, "float directCosine = max(dot(normal, sampledSunDirection), 0.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "sampledSunDirection,\n                camera.sun.w");
        require(RayTracingShaders.RAYGEN_SHADER, "evaluateBsdf(normal, viewDirection, sampledSunDirection, baseColor,");
        reject(RayTracingShaders.RAYGEN_SHADER, "vec3 lightDirection = normalize(camera.sun.xyz);");
        // RR must be structurally after the scatter update, not merely use a renamed beta.
        int scatter = RayTracingShaders.RAYGEN_SHADER.indexOf("throughput *= sampled.f * sampledCosine");
        int roulette = RayTracingShaders.RAYGEN_SHADER.indexOf("float betaLuminance");
        if (scatter < 0 || roulette < scatter) {
            throw new AssertionError("Russian roulette must follow BSDF scatter");
        }
        // Disabled legacy stages are intentionally not part of the active contract.
        require(RayTracingShaders.MISS_SHADER, "pathPosition = vec4(0.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "skyDecodeSrgb(textureLod(skybox, rayDirection, 0.0).rgb)");
        require(RayTracingShaders.RAYGEN_SHADER, "const int ATMOSPHERE_MAX_SEGMENT_SAMPLES = 16;");
        require(RayTracingShaders.RAYGEN_SHADER, "const float ATMOSPHERE_FOG_DENSITY_SCALE = 4.0;");
        require(RayTracingShaders.RAYGEN_SHADER, "float fogDensity = max(camera.environment.x, 0.0);");
        require(RayTracingShaders.RAYGEN_SHADER,
            "segmentLength * ATMOSPHERE_BLOCK_TO_KM");
        require(RayTracingShaders.RAYGEN_SHADER, "* ATMOSPHERE_FOG_DENSITY_SCALE");
        require(RayTracingShaders.RAYGEN_SHADER, "int sampleCount = quality == 1 ? 4 : (quality == 3 ? 16 : 8);");
        require(RayTracingShaders.RAYGEN_SHADER, "? primaryHitDistance");
        require(RayTracingShaders.RAYGEN_SHADER, ": max(camera.sun.w, 1.0);");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 solarScattering = sunValid");
        reject(RayTracingShaders.RAYGEN_SHADER, "shadowPoint + sunDirection * 0.002");
        reject(RayTracingShaders.RAYGEN_SHADER,
            "vec3 volumeSunVisibility = primarySunVisibilityValid");
        require(RayTracingShaders.RAYGEN_SHADER,
            "vec3 volumeSunDirection = camera.sun.xyz;");
        reject(RayTracingShaders.RAYGEN_SHADER,
            "vec3 primarySunDirection =");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 atmosphereVolumePosition = camera.origin.xyz");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 volumeEmitterInscatter = vec3(0.0);");
        require(RayTracingShaders.RAYGEN_SHADER,
            "indirectDiffuseRadiance = indirectDiffuseRadiance * atmosphereTransmittance");
        require(RayTracingShaders.RAYGEN_SHADER, "+ volumeEmitterInscatter;");
        require(RayTracingShaders.RAYGEN_SHADER, "samplePoint + sunDirection * 0.002");
        require(RayTracingShaders.RAYGEN_SHADER,
            "volumeSunVisibility = mix(");
        require(RayTracingShaders.RAYGEN_SHADER, "result.light = light;");
        require(RayTracingShaders.RAYGEN_SHADER, "result.visibility = mix(");
        require(RayTracingShaders.RAYGEN_SHADER, "primaryAreaLight = areaDirect.light;");
        require(RayTracingShaders.RAYGEN_SHADER, "primaryAreaVisibility = areaDirect.visibility;");
        require(RayTracingShaders.RAYGEN_SHADER, "AreaLightSample light, vec3 visibility");
        reject(RayTracingShaders.RAYGEN_SHADER,
            "sampleVolumeEmitter(\n                        atmosphereVolumePosition,\n                        primaryRayDirection,\n                        primeSobolSample3D");
        reject(RayTracingShaders.RAYGEN_SHADER, "float surfaceSunVisibility");
        require(RayTracingShaders.RAYGEN_SHADER,
            "directDiffuseRadiance *= atmosphereTransmittance;");
        require(RayTracingShaders.RAYGEN_SHADER,
            "emissionRadiance = emissionRadiance * atmosphereTransmittance");
        require(RayTracingShaders.RAYGEN_SHADER, "+ atmosphereInscatter;");
        reject(RayTracingShaders.RAYGEN_SHADER,
            "indirectDiffuseRadiance *= atmosphereTransmittance;");
        require(RayTracingShaders.RAYGEN_SHADER,
            "float reactive = (skySunDiskHit || atmosphereApplied");
        require(RayTracingShaders.RAYGEN_SHADER, "void integrateAtmosphereSegment(");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 sunDirectionInput,");
        require(RayTracingShaders.RAYGEN_SHADER, "ATMOSPHERE_RAYLEIGH_SCATTERING");
        require(RayTracingShaders.RAYGEN_SHADER, "ATMOSPHERE_AEROSOL_SCATTERING");
        require(RayTracingShaders.RAYGEN_SHADER, "float phaseCosine = dot(direction, sunDirection);");
        require(RayTracingShaders.RAYGEN_SHADER,
            "outputRadiance = outputRadiance * atmosphereTransmittance");
        require(RayTracingShaders.RAYGEN_SHADER,
            "+ atmosphereInscatter + volumeEmitterInscatter;");
        require(RayTracingShaders.RAYGEN_SHADER, "vec3 localRadiance = throughput * (localDiffuse + localSpecular + emissiveContribution);");
        require(RayTracingShaders.RAYGEN_SHADER, "emissionRadiance += throughput * emissiveContribution;");
        require(RayTracingShaders.RAYGEN_SHADER, "diffuseRadiance += localRadiance;");
        require(RayTracingShaders.RAYGEN_SHADER, "indirectDiffuseRadiance += localRadiance;");
        require(RayTracingShaders.RAYGEN_SHADER, "directSpecularRadiance += throughput * sunSpecularContribution;");
        reject(RayTracingShaders.RAYGEN_SHADER, "diffuseRadiance += throughput * localRadiance;");
        require(RayTracingShaders.RAYGEN_SHADER, "specularRadiance += localRadiance;");
        require(RayTracingShaders.RAYGEN_SHADER,
            "if (bounce == 0) {\n            // Only directly visible emission is deterministic and bypasses NRD.\n            emissionRadiance += throughput * emissiveContribution;");
        require(RayTracingShaders.CLOSEST_HIT_SHADER, "samplePbr(pbrMapIndex, uv, tangentNormal");
        require(RayTracingShaders.CLOSEST_HIT_SHADER, "bool transmissiveMaterial = surface.w > 1.0 || optical.w > 1.001;");
        require(RayTracingShaders.CLOSEST_HIT_SHADER, "transmissiveMaterial ? transmissionSide : 0.0");
        require(RayTracingShaders.CLOSEST_HIT_SHADER, "localPosition.w carries atlas coverage/opacity as transmission opacity");
        require(RayTracingShaders.CLOSEST_HIT_SHADER,
            "(uv2.w > 0.5 ? textureSample.a : 1.0) * tint.a, 0.0, 1.0);");
        reject(RayTracingShaders.CLOSEST_HIT_SHADER,
            "clamp(surfaceOpacity * tint.a, 0.0, 1.0)");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER, "shadowTransmittance *= transmissionFilter");
        require(RayTracingShaders.SHADOW_ANY_HIT_SHADER, "vec3 transmissionFilter = materialTransmissionColor(");
        require(RayTracingShaders.RAYGEN_SHADER, "shadowTransmittance = vec3(1.0);");
        assertEnergyAndMisContract();
        reject(RayTracingShaders.SHADOW_MISS_SHADER, "shadowTransmittance = vec3(1.0);");
        require(RayTracingShaders.SHADOW_MISS_SHADER, "ignored transparent any-hit intersections have already accumulated their");
        require(RayTracingShaders.SHADOW_CLOSEST_HIT_SHADER, "shadowTransmittance = vec3(0.0);");
    }

    private static void assertTemporalEnvironmentContract() {
        String shader = RayTracingShaders.RAYGEN_SHADER;
        require(shader, "bool skySunDiskHit = false;");
        require(shader, "skySunDiskHit = true;");
        require(shader,
            "float reactive = (skySunDiskHit || atmosphereApplied");
    }

    /**
     * Pins the energy/MIS fixes: delta transmission must not divide by its own Fresnel
     * probability, delta lobes must take MIS weight 1 against the sun, emitter radiance must not
     * be counted twice, and the sun's two lobes must share one cosine and one mixture PDF.
     */
    private static void assertEnergyAndMisContract() {
        String raygen = RayTracingShaders.RAYGEN_SHADER;
        reject(raygen, "throughput *= transmissionColor / max(transmissionProbability, 1.0e-6);");
        require(raygen, "throughput *= transmissionColor;");
        require(raygen, "bool previousWasDelta = false;");
        require(raygen, "previousWasDelta = true;");
        require(raygen, "(bounce == 0 || previousWasDelta) ? 1.0 : powerHeuristic(");
        require(raygen, "bool bsdfSampledEmitter = bounce > 0 && !previousWasDelta");
        require(raygen, "&& pathEmitterIndex != LIGHT_NO_EMITTER;");
        require(raygen, "vec3 emissiveContribution = bsdfSampledEmitter ? vec3(0.0) : baseColor * emission;");
        reject(raygen, "* directLight / max(directCosine, 1.0e-6);");
        require(raygen, "float sunDiffuseEnergy = primeDefaultDiffuseEnergy(");
        require(raygen, "vec3 sunDiffuseBrdf = diffuseMaterialWeight * baseColor");
        require(raygen, "vec3 sunSpecularContribution = max(sunBsdf.f - sunDiffuseBrdf, vec3(0.0))");
        require(raygen, "vec3 directDiffuseContribution = sunDiffuseContribution");
        reject(raygen, "float sunSpecularProbability = metallic >= 0.999");
        reject(raygen, "bool applyMis");
        reject(raygen, "localLight");
    }

    private static void assertPbrMaterialContract() {
        String shader = RayTracingShaders.CLOSEST_HIT_SHADER;
        String raygen = RayTracingShaders.RAYGEN_SHADER;
        require(shader, "const uint PBR_FORMAT_LAB = 0u;");
        require(shader, "const uint PBR_FORMAT_CLASSIC = 1u;");
        require(shader, "const uint PBR_FORMAT_BEDROCK = 2u;");
        require(shader, "normalZ = sqrt(max(1.0 - dot(normalXY, normalXY), 0.0));");
        require(shader, "textureAo = float(pixel & 0xffu) / 255.0;");
        require(shader, "porosity = blue;");
        require(shader, "emission = green;");
        require(shader, "pbrPredefinedMetalF0");
        require(shader, "float pbrHeight(uint offset, uint width, uint height, float u, float v)");
        require(shader, "float pbrHeightTexel(uint offset, uint width, uint height, int x, int y)");
        require(shader, "return mix(mix(h00, h10, blend.x), mix(h01, h11, blend.x), blend.y);");
        require(shader, "vec2 pbrWrapCoord(vec2 coord)");
        require(shader, "vec2 coord = pbrWrapCoord((atlasUv - vec2(u0, v0)) / span);");
        require(shader, "vec2 pbrParallaxUv(uint mapIndex, vec2 atlasUv");
        require(shader, "camera.pbrParallaxSettings.x");
        require(shader, "camera.pbrParallaxSettings.w");
        require(shader, "return heightValue <= 0.0 || heightValue >= 0.999 ? 1.0 : heightValue;");
        require(shader, "const int PBR_PARALLAX_STEPS = 32;");
        require(shader, "const int PBR_PARALLAX_REFINEMENTS = 5;");
        require(shader, "vec2 parallaxDirection = viewTangent.xy / viewTangent.z");
        require(shader, "for (int stepIndex = 0; stepIndex < PBR_PARALLAX_STEPS; stepIndex++)");
        require(shader, "sampledHeight = pbrHeight(heightOffset, width, height,");
        require(shader, "currentSampleCoord.x, currentSampleCoord.y);");
        require(shader, "for (int refinement = 0; refinement < PBR_PARALLAX_REFINEMENTS; refinement++)");
        require(shader, "vec3 pbrTangent(vec3 faceNormal, float tangentAngle, float tangentHandedness)");
        require(shader, "lighting.x, lighting.z,");
        require(shader, "parallaxDirection.y *= span.x / span.y;");
        require(shader, "* 0.2 / float(PBR_PARALLAX_STEPS);");
        require(shader, "mix(previousCoord, currentCoord, intersectionWeight)");
        require(shader, "coord = pbrWrapCoord(refinedCoord);");
        reject(shader, "float parallaxScale = entityMaterial ? 0.125 : 0.25;");
        reject(shader, "float viewFade = smoothstep(0.08, 0.35, viewTangent.z);");
        reject(shader, "currentCoord = clamp(currentCoord - parallaxDelta");
        require(shader, "pbrParallaxUv(pbrMapIndex, uv, normal, lighting.x, lighting.z");
        reject(shader, "heightOffsetFromSurface = startHeight - 0.5");
        reject(shader, "abs(dot(-rayDirection, faceNormal))");
        require(shader, "surface.x *= 1.0 - clamp(camera.environmentState.y * pbrPorosity");
        require(shader, "pbrEmission * camera.pbrSettings.z");
        require(shader, "pbrTextureAo, pbrHasAuthoredEmission");
        require(shader, "vec4 pbrSettings;");
        require(shader, "hasAuthoredEmission = true;");
        require(raygen, "authoredEmission = alphaByte < 255u ? float(alphaByte) / 254.0 : 0.0;");
        require(raygen, "return authoredEmission * max(emitterStrength, 0.0);");
        reject(raygen, "authored = authoredEmission > 0.0;");
    }

    private static void require(String source, String fragment) {
        if (!source.contains(fragment)) {
            throw new AssertionError("Ray budget contract is missing: " + fragment);
        }
    }

    private static void reject(String source, String fragment) {
        if (source.contains(fragment)) {
            throw new AssertionError("Ray budget contract still contains forbidden legacy code: " + fragment);
        }
    }

    private static void compile(long compiler, long options, String name, String source, int kind) {
        // The ray-generation stage is larger than LWJGL's 64 KiB MemoryStack, so hand shaderc
        // native buffers instead of the CharSequence overload that copies onto the stack. The
        // source buffer must not be NUL-terminated (LWJGL passes remaining() as the length).
        java.nio.ByteBuffer sourceBytes = org.lwjgl.system.MemoryUtil.memUTF8(source, false);
        java.nio.ByteBuffer fileName = org.lwjgl.system.MemoryUtil.memASCII("rtest_" + name + ".glsl", true);
        java.nio.ByteBuffer entryPoint = org.lwjgl.system.MemoryUtil.memASCII("main", true);
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(
                compiler, sourceBytes, kind, fileName, entryPoint, options);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(sourceBytes);
            org.lwjgl.system.MemoryUtil.memFree(fileName);
            org.lwjgl.system.MemoryUtil.memFree(entryPoint);
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                throw new AssertionError("Shader compilation failed for " + name + ": "
                    + Shaderc.shaderc_result_get_error_message(result));
            }
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }
}
