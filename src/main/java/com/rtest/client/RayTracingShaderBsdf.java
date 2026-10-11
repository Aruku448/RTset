package com.rtest.client;

/** Surface BSDF evaluation, directional energy and GGX sampling source. */
final class RayTracingShaderBsdf {
    private RayTracingShaderBsdf() {
    }

    static final String GLSL = """
            struct BsdfValue { vec3 f; vec3 diffuse; float pdf; };
            // Exact per-channel partition of the full mixture estimator, not its chosen proposal.
            // Zero-energy channels carry no diffuse signal; avoid epsilon bias for dark textures.
            vec3 bsdfDiffuseShare(BsdfValue value) {
                return vec3(
                    value.f.r > 0.0 ? value.diffuse.r / value.f.r : 0.0,
                    value.f.g > 0.0 ? value.diffuse.g / value.f.g : 0.0,
                    value.f.b > 0.0 ? value.diffuse.b / value.f.b : 0.0);
            }
            float ggxD(float nDotH, float alpha) {
                float a2 = alpha * alpha;
                float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
                return a2 / max(BSDF_PI * d * d, 1.0e-7);
            }
            float ggxG1(float nDotV, float alpha) {
                float a2 = alpha * alpha;
                return 2.0 * nDotV / max(nDotV + sqrt(a2 + (1.0 - a2) * nDotV * nDotV), 1.0e-6);
            }
            // Exact integer exponent on the nonnegative Schlick/Burley domain.
            float power5(float value) {
                float square = value * value;
                return square * square * value;
            }
            vec3 schlickFresnel(vec3 f0, float vDotH) {
                float x = power5(clamp(1.0 - vDotH, 0.0, 1.0));
                return f0 + (1.0 - f0) * x;
            }
            // Prime's default closure restores the energy lost by single-scattering GGX. Keep the
            // calibrated table and its alpha fit in the RT shader so rough PBR surfaces use the same
            // directional-energy partition instead of a second ad-hoc normalization heuristic.
            const float PRIME_BSDF_EPSILON = 1.0e-6;
            const float PRIME_DEFAULT_GGX_ALPHA = 0.64;
            const vec2 PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[32] = vec2[](
                vec2(0.105050949, 0.141424098), vec2(0.101618740, 0.177912561),
                vec2(0.092052501, 0.255356359), vec2(0.082015169, 0.326194899),
                vec2(0.075255580, 0.385283190), vec2(0.069562661, 0.435654352),
                vec2(0.065026574, 0.481349955), vec2(0.060138182, 0.516639700),
                vec2(0.055822648, 0.546962264), vec2(0.052415524, 0.571843207),
                vec2(0.048774748, 0.594311533), vec2(0.046079235, 0.613289194),
                vec2(0.043347252, 0.628604169), vec2(0.041271370, 0.642250667),
                vec2(0.038961432, 0.654930494), vec2(0.036981937, 0.664647269),
                vec2(0.035516171, 0.674041851), vec2(0.033806834, 0.681722658),
                vec2(0.032365771, 0.688765066), vec2(0.031207238, 0.694330635),
                vec2(0.029979644, 0.699656529), vec2(0.028958354, 0.703829794),
                vec2(0.027843981, 0.707299607), vec2(0.027112505, 0.710047934),
                vec2(0.026286324, 0.712231314), vec2(0.025693271, 0.713452229),
                vec2(0.025029263, 0.713788850), vec2(0.024538412, 0.713297400),
                vec2(0.024024045, 0.711565628), vec2(0.023714662, 0.708079364),
                vec2(0.023220258, 0.701350782), vec2(0.022881333, 0.677331905));
            float primeDefaultReflectiveDirectionalEnergyFit(float cosineView, float ggxAlpha) {
                float x = clamp(cosineView, 0.0, 1.0);
                float y = clamp(ggxAlpha, 0.0, 1.0);
                float x2 = x * x;
                float y2 = y * y;
                vec4 fit = vec4(0.1003, 0.9345, 1.0, 1.0)
                    + vec4(-0.6303, -2.323, -1.765, 0.2281) * x
                    + vec4(9.748, 2.229, 8.263, 15.94) * y
                    + vec4(-2.038, -3.748, 11.53, -55.83) * x * y
                    + vec4(29.34, 1.424, 28.96, 13.08) * x2
                    + vec4(-8.245, -0.7684, -7.507, 41.26) * y2
                    + vec4(-26.44, 1.436, -36.11, 54.9) * x2 * y
                    + vec4(19.99, 0.2913, 15.86, 300.2) * x * y2
                    + vec4(-5.448, 0.6286, 33.37, -285.1) * x2 * y2;
                vec2 coefficients = clamp(fit.xy / fit.zw, 0.0, 1.0);
                return 0.04 * coefficients.x + coefficients.y;
            }
            // At fixed reference alpha, collect the bivariate fit into a quadratic in x.
            // Constant vectors are folded at shader compilation; no extra LUT/buffer traffic.
            float primeReferenceReflectiveDirectionalEnergyFit(float cosineView) {
                const float y = PRIME_DEFAULT_GGX_ALPHA;
                const float y2 = y * y;
                const vec4 constantTerm = vec4(0.1003, 0.9345, 1.0, 1.0)
                    + vec4(9.748, 2.229, 8.263, 15.94) * y
                    + vec4(-8.245, -0.7684, -7.507, 41.26) * y2;
                const vec4 linearTerm = vec4(-0.6303, -2.323, -1.765, 0.2281)
                    + vec4(-2.038, -3.748, 11.53, -55.83) * y
                    + vec4(19.99, 0.2913, 15.86, 300.2) * y2;
                const vec4 quadraticTerm = vec4(29.34, 1.424, 28.96, 13.08)
                    + vec4(-26.44, 1.436, -36.11, 54.9) * y
                    + vec4(-5.448, 0.6286, 33.37, -285.1) * y2;
                float x = clamp(cosineView, 0.0, 1.0);
                vec4 fit = constantTerm + linearTerm * x + quadraticTerm * (x * x);
                vec2 coefficients = clamp(fit.xy / fit.zw, 0.0, 1.0);
                return 0.04 * coefficients.x + coefficients.y;
            }
            vec2 primeDefaultGgxDirectionalEnergy(float cosineView, float ggxAlpha) {
                float coordinate = clamp(cosineView, 0.0, 1.0) * 31.0;
                int lowerIndex = int(floor(coordinate));
                int upperIndex = min(lowerIndex + 1, 31);
                vec2 calibrated = mix(PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[lowerIndex],
                    PRIME_DEFAULT_GGX_DIRECTIONAL_ENERGY[upperIndex], coordinate - float(lowerIndex));
                float reflectionDelta = primeDefaultReflectiveDirectionalEnergyFit(cosineView, ggxAlpha)
                    - primeReferenceReflectiveDirectionalEnergyFit(cosineView);
                float totalResolvedEnergy = calibrated.x + calibrated.y;
                float reflectedEnergy = clamp(calibrated.x + reflectionDelta, 0.0, totalResolvedEnergy);
                return vec2(reflectedEnergy, totalResolvedEnergy - reflectedEnergy);
            }
            vec2 prepareBsdfDirectionalEnergy(vec3 normal, vec3 viewDirection,
                    float roughness, float metallic) {
                if (metallic >= 0.999 || roughness == 0.0) return vec2(1.0, 0.0);
                return primeDefaultGgxDirectionalEnergy(max(dot(normal, viewDirection), 0.0),
                    max(roughness * roughness, 0.025));
            }
            float preparedSpecularSampleProbability(vec2 energy, float roughness, float metallic) {
                if (metallic >= 0.999 || roughness == 0.0) return 1.0;
                return clamp(energy.x / max(energy.x + energy.y, PRIME_BSDF_EPSILON), 0.05, 0.95);
            }
            float primeDefaultSpecularSampleProbability(vec3 viewDirection,
                    vec3 normal, float roughness, float metallic) {
                return preparedSpecularSampleProbability(
                    prepareBsdfDirectionalEnergy(normal, viewDirection, roughness, metallic), roughness, metallic);
            }
            BsdfValue evaluateBsdf(vec3 normal, vec3 wi, vec3 wo, vec3 baseColor,
                    float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,
                    float diffuseSamplingProbability, float specularSamplingProbability, vec2 directionalEnergy) {
                BsdfValue result;
                result.f = vec3(0.0);
                result.diffuse = vec3(0.0);
                result.pdf = 0.0;
                float nDotI = max(dot(normal, wi), 0.0);
                float nDotO = max(dot(normal, wo), 0.0);
                if (nDotI <= 0.0 || nDotO <= 0.0) return result;
                vec3 h = normalize(wi + wo);
                float nDotH = max(dot(normal, h), 0.0);
                float iDotH = max(dot(wi, h), 1.0e-6);
                float alpha = max(roughness * roughness, 0.025);
                vec3 f0 = mix(vec3(reflectivity), baseColor, metallic);
                vec3 F = schlickFresnel(f0, iDotH);
                float distribution = ggxD(nDotH, alpha);
                float incidentMasking = ggxG1(nDotI, alpha);
                float specular = distribution * incidentMasking * ggxG1(nDotO, alpha)
                    / max(4.0 * nDotI * nDotO, 1.0e-6);
                // Visible-normal proposal: D(h) G1(wi) |wi.h| / n.wi,
                // followed by the reflection Jacobian 1 / (4 |wi.h|).
                float specPdf = distribution * incidentMasking / (4.0 * nDotI);
                // A perfect mirror is a delta distribution, not a broadened GGX lobe.
                // Its energy is sampled explicitly by the continuation branch, never by NEE.
                if (roughness == 0.0) {
                    specular = 0.0;
                    specPdf = 0.0;
                }
                // Both lobes and all lights share two prepared receiver-dependent values.
                // Their lifetime ends at this vertex; directional Fresnel/PDF remain per light.
                float diffuseEnergy = 1.0;
                float specularEnergy = 1.0;
                if (!(metallic >= 0.999 || roughness == 0.0)) {
                    float resolvedEnergy = max(directionalEnergy.x + directionalEnergy.y,
                        PRIME_BSDF_EPSILON);
                    diffuseEnergy = directionalEnergy.y / resolvedEnergy;
                    specularEnergy = 1.0 / resolvedEnergy;
                }
                result.diffuse = diffuseMaterialWeight * baseColor * (vec3(1.0) - F)
                    * diffuseEnergy / BSDF_PI;
                result.f = result.diffuse + F * specular * specularEnergy;
                result.pdf = diffuseSamplingProbability * nDotO / BSDF_PI + specularSamplingProbability * specPdf;
                return result;
            }
            // Isolated diagnostic receivers still prepare their own context.
            BsdfValue evaluateBsdf(vec3 normal, vec3 wi, vec3 wo, vec3 baseColor,
                    float roughness, float metallic, float reflectivity, float diffuseMaterialWeight,
                    float diffuseSamplingProbability, float specularSamplingProbability) {
                return evaluateBsdf(normal, wi, wo, baseColor, roughness, metallic, reflectivity,
                    diffuseMaterialWeight, diffuseSamplingProbability, specularSamplingProbability,
                    prepareBsdfDirectionalEnergy(normal, wi, roughness, metallic));
            }
            // iterationRP-inspired artistic thin-foliage sunlight, not volumetric SSS.
            float vegetationSunResponse(vec3 normal, vec3 viewDirection, vec3 lightDirection,
                    float roughness, float kind) {
                vec3 shadingNormal = kind == 1.0
                    ? normalize(mix(normal, vec3(0.0, 1.0, 0.0), 0.49)) : normal;
                vec3 halfVector = viewDirection + lightDirection;
                float halfLength2 = dot(halfVector, halfVector);
                float lightHalf = halfLength2 > 1.0e-8
                    ? clamp(dot(lightDirection, halfVector * inversesqrt(halfLength2)), 0.0, 1.0) : 0.0;
                float nl = clamp(dot(shadingNormal, lightDirection), 0.0, 1.0);
                float nv = clamp(dot(shadingNormal, viewDirection), 0.0, 1.0);
                float grazing = 0.5 + 2.0 * roughness * lightHalf * lightHalf;
                float burley = nl * (1.0 + (grazing - 1.0) * power5(1.0 - nl))
                    * (1.0 + (grazing - 1.0) * power5(1.0 - nv)) / BSDF_PI;
                // ITRP blends a BRDF response (already / pi) toward 0.6.
                return mix(burley, 0.6, kind == 1.0 ? 0.20 : 0.25);
            }
            // Heitz visible GGX normals: stretch view, sample projected disk, unstretch.
            vec3 sampleGgx(vec3 normal, vec3 viewDirection, float roughness, vec2 sampleValue) {
                float alpha = max(roughness * roughness, 0.025);
                vec3 tangent = normalize(abs(normal.y) < 0.999 ? cross(normal, vec3(0.0, 1.0, 0.0))
                    : cross(normal, vec3(1.0, 0.0, 0.0)));
                vec3 bitangent = cross(normal, tangent);
                vec3 view = vec3(dot(viewDirection, tangent), dot(viewDirection, bitangent),
                    max(dot(viewDirection, normal), 0.0));
                vec3 stretched = normalize(vec3(alpha * view.xy, view.z));
                float lensq = dot(stretched.xy, stretched.xy);
                vec3 t1 = lensq > 0.0 ? vec3(-stretched.y, stretched.x, 0.0) * inversesqrt(lensq)
                    : vec3(1.0, 0.0, 0.0);
                vec3 t2 = cross(stretched, t1);
                float radius = sqrt(sampleValue.x);
                float phi = 2.0 * BSDF_PI * sampleValue.y;
                float x = radius * cos(phi);
                float y = radius * sin(phi);
                float blend = 0.5 * (1.0 + stretched.z);
                y = (1.0 - blend) * sqrt(max(1.0 - x * x, 0.0)) + blend * y;
                vec3 projected = x * t1 + y * t2
                    + sqrt(max(1.0 - x * x - y * y, 0.0)) * stretched;
                vec3 halfLocal = normalize(vec3(alpha * projected.xy, max(projected.z, 0.0)));
                return normalize(tangent * halfLocal.x + bitangent * halfLocal.y + normal * halfLocal.z);
            }
            """;
}
