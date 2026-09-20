package com.rtest.client;

/** Dependency-free contract test for the renderer-independent fluid seam. */
public final class FluidGeometryCaptureTest {
    public static void main(String[] args) {
        var cell = new FluidGeometryCapture.Cell(1.0F, 0.75F, 0.5F, 0.25F,
            true, false, new boolean[] {true, false, true, false});
        if (FluidGeometryCapture.triangleCount(cell) != 6) {
            throw new AssertionError("top plus two sides must emit six triangles");
        }
        if (!FluidGeometryCapture.sameFluid("minecraft:water", "minecraft:water")
            || FluidGeometryCapture.sameFluid("minecraft:water", "minecraft:lava")) {
            throw new AssertionError("fluid boundary identity is not stable");
        }
        var lava = FluidGeometryCapture.surface("minecraft:lava");
        var water = FluidGeometryCapture.surface("minecraft:water");
        var modded = FluidGeometryCapture.surface("example:molten_sap");
        if (lava.emission() <= water.emission() || lava.absorptionR() <= water.absorptionR()
            || !(water.opacity() > 0.0F && water.opacity() < 1.0F)
            || !(water.absorptionR() > water.absorptionG()
                && water.absorptionG() > water.absorptionB())
            || !(lava.opacity() > 0.0F && lava.opacity() <= 1.0F)
            || modded.ior() < 1.0F || !FluidGeometryCapture.isTranslucent()) {
            throw new AssertionError("fluid optical policy is invalid");
        }
    }
}
