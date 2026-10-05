#ifndef RTEST_WORLD_LIGHTING_CONTRIBUTIONS_GLSL
#define RTEST_WORLD_LIGHTING_CONTRIBUTIONS_GLSL
// World storage owns Li and visibility, never a view-weighted signed shadow delta.
// A sample is one shared light direction / emitter point with its PDF already
// accounted for in sourceWeight. Do not multiply separately averaged soft masks.
struct WorldDirectSample {
    vec3 sourceWeight;
    vec3 staticVisibility;
    vec3 fullVisibility;
};
struct DisplayDirectContribution {
    vec3 staticRadiance;
    vec3 entityDelta; // signed; must bypass radiance denoising/clamping
};
DisplayDirectContribution worldDirectAtView(WorldDirectSample light, vec3 currentBsdfCos) {
    vec3 unoccluded = light.sourceWeight * currentBsdfCos;
    DisplayDirectContribution result;
    result.staticRadiance = unoccluded * light.staticVisibility;
    result.entityDelta = unoccluded * (light.fullVisibility - light.staticVisibility);
    return result;
}
vec3 worldDirectTotal(DisplayDirectContribution light) {
    return light.staticRadiance + light.entityDelta;
}
// E includes cosine hemisphere integration. Albedo is applied exactly once here.
vec3 worldLambertIrradiance(vec3 irradiance, vec3 diffuseAlbedo) {
    return irradiance * diffuseAlbedo * (1.0 / 3.14159265358979323846);
}
// Entity visibility belongs to the matching direct source, never sky/GI/emission.
vec3 worldComposeSurface(vec3 directTotal, vec3 diffuseIndirect,
                         vec3 specularIndirect, vec3 surfaceEmission) {
    return directTotal + diffuseIndirect + specularIndirect + surfaceEmission;
}
#endif
