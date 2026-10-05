package com.rtest.client;

final class RayTracingShaderStages {
    static final String MISS_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            struct PathPayload {
                vec4 position;
                vec4 normal;
                vec4 baseColorRoughness;
                vec4 material;
                vec4 opticalLighting;
                vec4 localPosition;
                uint dynamicSlot;
                uint emitterIndex;
                uint staticBoundary;
            };
            layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
            #define pathPosition pathPayload.position
            #define pathLocalPosition pathPayload.localPosition
                #define pathDynamicSlot pathPayload.dynamicSlot
            #define pathEmitterIndex pathPayload.emitterIndex
            void main() {
                pathPosition = vec4(0.0);
                pathLocalPosition = vec4(0.0);
                pathDynamicSlot = 0xffffffffu;
                pathEmitterIndex = 0xffffffffu;
            }
            """;
    static final String CLOSEST_HIT_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
    """ + RayTracingShaderCommon.PLAYER_SKIN_FUNCTIONS + """
            layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
            layout(set = 0, binding = 2, std140) uniform Camera {
                vec4 origin;
                vec4 forward;
                vec4 right;
                vec4 up;
                vec4 parameters;
                vec4 sun;
                vec4 settings;
                vec4 environment;
                vec4 random;
                vec4 previousOrigin;
                vec4 previousForward;
                vec4 previousRight;
                vec4 previousUp;
                vec4 previousParameters;
                vec4 jitter;
                vec4 environmentState;
                vec4 dynamicParameters;
                vec4 pbrSettings;
                vec4 pbrParallaxSettings;
            } camera;
            layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
            layout(set = 0, binding = 5, std430) readonly buffer PbrData { uint values[]; } pbrData;
            layout(set = 0, binding = 19, std430) readonly buffer DynamicMotionMetadata { vec4 values[]; } dynamicMotion;
            layout(set = 0, binding = 26, std430) readonly buffer LightData { uint values[]; } lightData;
    """ + RayTracingShaderCommon.PBR_FUNCTIONS + """
            float materialDecodeSrgbChannel(float encoded) {
                return encoded <= 0.04045
                    ? encoded / 12.92
                    : pow((encoded + 0.055) / 1.055, 2.4);
            }
            vec3 materialDecodeSrgb(vec3 encoded) {
                return vec3(
                    materialDecodeSrgbChannel(encoded.r),
                    materialDecodeSrgbChannel(encoded.g),
                    materialDecodeSrgbChannel(encoded.b));
            }
            vec3 materialLinearSrgbToWorking(vec3 color) {
                return vec3(
                    dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
                    dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
                    dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
            }
            hitAttributeEXT vec2 barycentrics;
            struct PathPayload {
                vec4 position;
                vec4 normal;
                vec4 baseColorRoughness;
                vec4 material;
                vec4 opticalLighting;
                vec4 localPosition;
                uint dynamicSlot;
                uint emitterIndex;
                uint staticBoundary;
            };
            layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
            #define pathPosition pathPayload.position
            #define pathNormal pathPayload.normal
            #define pathBaseColorRoughness pathPayload.baseColorRoughness
            #define pathMaterial pathPayload.material
            #define pathOpticalLighting pathPayload.opticalLighting
            #define pathLocalPosition pathPayload.localPosition
            #define pathDynamicSlot pathPayload.dynamicSlot
            #define pathEmitterIndex pathPayload.emitterIndex
            void main() {
                uint triangleIndex = uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID);
                uint materialIndex = triangleIndex * 7u;
                vec4 tint = materials.entries[materialIndex];
                vec3 rawGeometricNormal = materials.entries[materialIndex + 1u].xyz;
                float geometricNormalLength2 = dot(rawGeometricNormal, rawGeometricNormal);
                vec3 geometricNormal = geometricNormalLength2 > 1.0e-12
                    && !any(isnan(rawGeometricNormal)) && !any(isinf(rawGeometricNormal))
                    ? rawGeometricNormal * inversesqrt(geometricNormalLength2)
                    : -normalize(gl_WorldRayDirectionEXT);
                vec3 normal = geometricNormal;
                vec4 uv01 = materials.entries[materialIndex + 2u];
                vec4 uv2 = materials.entries[materialIndex + 3u];
                vec4 lighting = materials.entries[materialIndex + 4u];
                vec4 surface = materials.entries[materialIndex + 5u];
                vec4 optical = materials.entries[materialIndex + 6u];
                vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                    + uv01.zw * barycentrics.x
                    + uv2.xy * barycentrics.y;
                uint pbrMapIndex = uint(max(lighting.w, 0.0) + 0.5);
                uv = pbrParallaxUv(pbrMapIndex, uv, normal, lighting.x, lighting.z,
                    gl_WorldRayDirectionEXT, uv2.w > 1.5);
                vec3 tangentNormal = vec3(0.0, 0.0, 1.0);
                float pbrRoughness = surface.x;
                float pbrMetallic = surface.y;
                float pbrReflectivity = surface.w > 1.0 ? surface.w - 1.0 : surface.w;
                float pbrEmission = surface.z;
                bool pipelineEmissive = optical.z > 0.5;
                float pbrPorosity;
                float pbrTextureAo;
                bool pbrHasAuthoredEmission;
                bool pbrHasNormal;
                bool pbrHasSpecular;
                samplePbr(pbrMapIndex, uv, tangentNormal, pbrRoughness, pbrMetallic,
                    pbrReflectivity, pbrEmission, pbrPorosity, pbrTextureAo, pbrHasAuthoredEmission,
                    pbrHasNormal, pbrHasSpecular);
                if (pbrHasNormal) {
                    normal = pbrWorldNormal(normal, tangentNormal, lighting.x, lighting.z);
                }
                if (pbrHasSpecular) {
                    surface.x = pbrRoughness;
                    surface.y = pbrMetallic;
                    surface.w = (surface.w > 1.0 ? 1.0 : 0.0) + pbrReflectivity;
                    surface.z = pbrHasAuthoredEmission ? pbrEmission * camera.pbrSettings.z : surface.z;
                    // Vanilla EYES / ENTITY_TRANSLUCENT_EMISSIVE is ITRP's dedicated emissive
                    // pass. Preserve that contract even when an entity also has a PBR companion
                    // map whose emission channel is absent or zero.
                    if (pipelineEmissive) {
                        surface.z = max(surface.z, max(camera.pbrSettings.z, camera.pbrParallaxSettings.y));
                    }
                    uint pbrMode = uint(max(camera.pbrSettings.x, 0.0) + 0.5);
                    if ((pbrMode & 0x200u) != 0u) {
                        surface.x *= 1.0 - clamp(camera.environmentState.y * pbrPorosity
                            * camera.pbrSettings.w, 0.0, 1.0);
                    }
                }
                vec4 textureSample = uv2.w > 1.5
                    ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                        : samplePlayerSkin(uv, optical.x))
                    : (uv2.z > 0.5
                        ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                        : textureLod(blockAtlas, uv, 0.0));
                vec3 baseColor = uv2.w > 0.5
                    ? materialLinearSrgbToWorking(materialDecodeSrgb(textureSample.rgb)
                        * materialDecodeSrgb(tint.rgb))
                    : tint.rgb;
                if (pbrHasNormal && pbrTextureAo < 0.9999) {
                    baseColor *= pow(max(pbrTextureAo, 0.0), 1.0 / 2.2);
                }
                // Keep albedo unmodified; RayGen owns direct NEE, continuation, and emission.
                pathPosition = vec4(gl_WorldRayOriginEXT + gl_HitTEXT * gl_WorldRayDirectionEXT, 1.0);
                // localPosition.w carries atlas coverage/opacity as transmission opacity; xyz remains
                // object-space position.
                // Match the shadow any-hit expression exactly. The previous form multiplied tint.a
                // twice for non-textured materials (uv2.w <= 0.5), so a transmissive surface and its
                // own shadow disagreed about the same opacity.
                float surfaceOpacity = clamp(
                    (uv2.w > 0.5 ? textureSample.a : 1.0) * tint.a, 0.0, 1.0);
                pathLocalPosition = vec4(gl_ObjectRayOriginEXT + gl_HitTEXT * gl_ObjectRayDirectionEXT,
                    surfaceOpacity);
                uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
                uint dynamicStride = uint(max(camera.dynamicParameters.y, 0.0) + 0.5);
                uint dynamicCapacity = uint(max(camera.dynamicParameters.z, 0.0) + 0.5);
                pathDynamicSlot = 0xffffffffu;
                if (dynamicStride > 0u && gl_InstanceCustomIndexEXT >= dynamicStart) {
                    uint relative = gl_InstanceCustomIndexEXT - dynamicStart;
                    uint slot = relative / dynamicStride;
                    if (slot < dynamicCapacity && relative < dynamicCapacity * dynamicStride) {
                        pathDynamicSlot = slot;
                    }
                }
                // The normal payload's otherwise-unused w component carries the material's optical
                // dispersion scale without changing the established seven-vec4 material ABI.
                pathNormal = vec4(normal, clamp(lighting.y, 0.0, 1.0));
                pathBaseColorRoughness = vec4(baseColor, clamp(surface.x, 0.0, 1.0));
                bool transmissiveMaterial = surface.w > 1.0 || optical.w > 1.001;
                float materialF0 = surface.w > 1.0 ? surface.w - 1.0 : max(surface.w, 0.04);
                float transmissionSide = dot(gl_WorldRayDirectionEXT, geometricNormal) < 0.0 ? 1.0 : 2.0;
                pathMaterial = vec4(surface.y, surface.z, materialF0,
                    // Negative opaque values carry vegetation; positive values remain dielectric sides.
                    transmissiveMaterial ? transmissionSide : -materials.entries[materialIndex + 1u].w);
                // x = IOR; yzw = per-channel Beer-Lambert absorption coefficients.
                pathOpticalLighting = vec4(optical.w, optical.xyz);
                pathEmitterIndex = triangleIndex < lightData.values[5]
                    ? uint(lightData.values[lightData.values[6] + triangleIndex]) : 0xffffffffu;
            }
            """;
    static final String SHADOW_MISS_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            struct ShadowPayload {
                vec3 transmittance;
                uint dynamicOccluder;
                uint excludeDynamic;
            };
            layout(location = 1) rayPayloadInEXT ShadowPayload shadowPayload;
            #define shadowTransmittance shadowPayload.transmittance
            #define shadowDynamicOccluder shadowPayload.dynamicOccluder
            #define shadowExcludeDynamic shadowPayload.excludeDynamic
            void main() {
                // The raygen stage initializes this payload before every shadow query. Do not reset
                // it here: ignored transparent any-hit intersections have already accumulated their
                // RGB filters, and the miss shader must preserve that result.
            }
            """;
    static final String SHADOW_CLOSEST_HIT_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
            struct ShadowPayload {
                vec3 transmittance;
                uint dynamicOccluder;
                uint excludeDynamic;
            };
            layout(location = 1) rayPayloadInEXT ShadowPayload shadowPayload;
            #define shadowTransmittance shadowPayload.transmittance
            #define shadowDynamicOccluder shadowPayload.dynamicOccluder
            #define shadowExcludeDynamic shadowPayload.excludeDynamic
            void main() { shadowTransmittance = vec3(0.0); }
            """;
    static final String ANY_HIT_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
    """ + RayTracingShaderCommon.PLAYER_SKIN_FUNCTIONS + """
            layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
            layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
            // Primary rays only need the alpha test; their Closest Hit shader handles
            // transmission and refraction normally.
            struct PathPayload {
                vec4 position;
                vec4 normal;
                vec4 baseColorRoughness;
                vec4 material;
                vec4 opticalLighting;
                vec4 localPosition;
                uint dynamicSlot;
                uint emitterIndex;
                uint staticBoundary;
            };
            layout(location = 0) rayPayloadInEXT PathPayload pathPayload;
            hitAttributeEXT vec2 barycentrics;
            const float ALPHA_CUTOFF = 0.5;
            void main() {
                if (pathPayload.staticBoundary != 0u && uint(gl_InstanceCustomIndexEXT) >= pathPayload.staticBoundary) {
                    ignoreIntersectionEXT;
                    return;
                }
                uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
                vec4 uv2 = materials.entries[materialIndex + 3u];
                // Ordinary opaque/transmissive hits have no alpha coverage work here.
                // Closest-hit owns their shading; preserve cutout point sampling below.
                if (uv2.z <= 0.5) return;
                vec4 uv01 = materials.entries[materialIndex + 2u];
                vec4 optical = materials.entries[materialIndex + 6u];
                vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                    + uv01.zw * barycentrics.x
                    + uv2.xy * barycentrics.y;
                vec4 textureSample = uv2.w > 1.5
                    ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                        : samplePlayerSkin(uv, optical.x))
                    : (uv2.z > 0.5
                        ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                        : textureLod(blockAtlas, uv, 0.0));
                if (uv2.z > 0.5 && textureSample.a < ALPHA_CUTOFF) {
                    ignoreIntersectionEXT;
                }
            }
            """;
    static final String SHADOW_ANY_HIT_SHADER = """
            #version 460
            #extension GL_EXT_ray_tracing : require
    """ + RayTracingShaderCommon.PLAYER_SKIN_FUNCTIONS + """
            layout(set = 0, binding = 2, std140) uniform Camera {
                vec4 origin;
                vec4 forward;
                vec4 right;
                vec4 up;
                vec4 parameters;
                vec4 sun;
                vec4 settings;
                vec4 environment;
                vec4 random;
                vec4 previousOrigin;
                vec4 previousForward;
                vec4 previousRight;
                vec4 previousUp;
                vec4 previousParameters;
                vec4 jitter;
                vec4 environmentState;
                vec4 dynamicParameters;
                vec4 pbrSettings;
                vec4 pbrParallaxSettings;
            } camera;
            layout(set = 0, binding = 3, std430) readonly buffer Materials { vec4 entries[]; } materials;
            layout(set = 0, binding = 4) uniform sampler2D blockAtlas;
            hitAttributeEXT vec2 barycentrics;
            struct ShadowPayload {
                vec3 transmittance;
                uint dynamicOccluder;
                uint excludeDynamic;
            };
            layout(location = 1) rayPayloadInEXT ShadowPayload shadowPayload;
            #define shadowTransmittance shadowPayload.transmittance
            #define shadowDynamicOccluder shadowPayload.dynamicOccluder
            #define shadowExcludeDynamic shadowPayload.excludeDynamic
            const float ALPHA_CUTOFF = 0.5;
            float materialDecodeSrgbChannel(float encoded) {
                return encoded <= 0.04045
                    ? encoded / 12.92
                    : pow((encoded + 0.055) / 1.055, 2.4);
            }
            vec3 materialDecodeSrgb(vec3 encoded) {
                return vec3(
                    materialDecodeSrgbChannel(encoded.r),
                    materialDecodeSrgbChannel(encoded.g),
                    materialDecodeSrgbChannel(encoded.b));
            }
            vec3 materialLinearSrgbToWorking(vec3 color) {
                return vec3(
                    dot(vec3(0.6274039, 0.3292830, 0.0433131), color),
                    dot(vec3(0.0690973, 0.9195404, 0.0113623), color),
                    dot(vec3(0.0163914, 0.0880133, 0.8955953), color));
            }
            vec3 materialTransmissionColor(vec3 baseColor, float opacity, float ior) {
                if (ior < 1.4) return mix(vec3(1.0), baseColor, opacity);
                float peak = max(baseColor.r, max(baseColor.g, baseColor.b));
                vec3 filterColor = peak > 1.0e-6 ? baseColor / peak : vec3(1.0);
                return mix(vec3(1.0), filterColor, mix(0.75, 1.0, opacity));
            }
            void main() {
                uint dynamicBoundary = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
                if (shadowExcludeDynamic != 0u && uint(gl_InstanceCustomIndexEXT) >= dynamicBoundary) {
                    ignoreIntersectionEXT;
                    return;
                }
                uint materialIndex = (uint(gl_InstanceCustomIndexEXT) + uint(gl_PrimitiveID)) * 7u;
                vec4 uv2 = materials.entries[materialIndex + 3u];
                vec4 surface = materials.entries[materialIndex + 5u];
                vec4 optical = materials.entries[materialIndex + 6u];
                bool transmissive = surface.w > 1.0 || optical.w > 1.001;
                if (uv2.z <= 0.5 && !transmissive) {
                    // Accept without UV reconstruction/texture reads. Shadow closest-hit zeros T.
                    uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
                    if (uint(gl_InstanceCustomIndexEXT) >= dynamicStart) shadowDynamicOccluder = 1u;
                    return;
                }
                vec4 tint = materials.entries[materialIndex];
                vec4 uv01 = materials.entries[materialIndex + 2u];
                vec2 uv = uv01.xy * (1.0 - barycentrics.x - barycentrics.y)
                    + uv01.zw * barycentrics.x
                    + uv2.xy * barycentrics.y;
                vec4 textureSample = uv2.w > 1.5
                    ? (uv2.z > 0.5 ? samplePlayerSkinCutout(uv, optical.x)
                        : samplePlayerSkin(uv, optical.x))
                    : (uv2.z > 0.5
                        ? texelFetch(blockAtlas, materialCutoutTexel(uv, textureSize(blockAtlas, 0)), 0)
                        : textureLod(blockAtlas, uv, 0.0));
                if (uv2.z > 0.5 && textureSample.a < ALPHA_CUTOFF) {
                    ignoreIntersectionEXT;
                }

                uint dynamicStart = uint(max(camera.dynamicParameters.x, 0.0) + 0.5);
                if (uint(gl_InstanceCustomIndexEXT) >= dynamicStart) {
                    shadowDynamicOccluder = 1u;
                }

                // Continue through transmissive surfaces while carrying their RGB filter. An
                // opaque hit is accepted by Shadow Closest Hit, which writes vec3(0). Applying
                // half the absorption per interface approximates the two faces of a thin pane.
                if (transmissive) {
                    vec3 baseColor = uv2.w > 0.5
                        ? materialLinearSrgbToWorking(materialDecodeSrgb(textureSample.rgb)
                            * materialDecodeSrgb(tint.rgb))
                        : tint.rgb;
                    float transmissionOpacity = clamp(
                        (uv2.w > 0.5 ? textureSample.a : 1.0) * tint.a, 0.0, 1.0);
                    vec3 transmissionFilter = materialTransmissionColor(
                        baseColor, transmissionOpacity, optical.w);
                    transmissionFilter = clamp(transmissionFilter, vec3(0.01), vec3(1.0));
                    transmissionFilter *= exp(-max(optical.xyz, vec3(0.0)) * 0.5);
                    shadowTransmittance *= transmissionFilter;
                    ignoreIntersectionEXT;
                }
            }
            """;
}
