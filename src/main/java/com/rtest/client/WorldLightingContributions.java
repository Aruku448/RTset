package com.rtest.client;

/** Numeric oracle for the world/source versus current-view shader contract. */
final class WorldLightingContributions {
    private WorldLightingContributions() { }
    record Rgb(double r, double g, double b) {
        Rgb add(Rgb x) { return new Rgb(r + x.r, g + x.g, b + x.b); }
        Rgb subtract(Rgb x) { return new Rgb(r - x.r, g - x.g, b - x.b); }
        Rgb multiply(Rgb x) { return new Rgb(r * x.r, g * x.g, b * x.b); }
        Rgb scale(double x) { return new Rgb(r * x, g * x, b * x); }
    }
    record SourceSample(Rgb sourceWeight, Rgb staticVisibility, Rgb fullVisibility) { }
    record AtView(Rgb staticRadiance, Rgb entityDelta) {
        Rgb total() { return staticRadiance.add(entityDelta); }
    }
    static AtView atView(SourceSample source, Rgb currentBsdfCos) {
        Rgb unoccluded = source.sourceWeight.multiply(currentBsdfCos);
        return new AtView(unoccluded.multiply(source.staticVisibility),
            unoccluded.multiply(source.fullVisibility.subtract(source.staticVisibility)));
    }
    static Rgb lambert(Rgb irradiance, Rgb diffuseAlbedo) {
        return irradiance.multiply(diffuseAlbedo).scale(1.0 / Math.PI);
    }
    static Rgb compose(Rgb direct, Rgb diffuseIndirect, Rgb specularIndirect, Rgb emission) {
        return direct.add(diffuseIndirect).add(specularIndirect).add(emission);
    }
}
