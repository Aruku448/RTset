package com.rtest.client;

/** Source contracts and CPU mathematical references, not GPU pixel-equivalence measurements. */
public final class RayTracingParallaxOptimizationTest {
    private RayTracingParallaxOptimizationTest() {
    }

    public static void main(String[] args) {
        assertMathematicalReferences();
        assertShaderContract();
        System.out.println("Parallax optimization contracts passed (not GPU pixel-equivalence measurements)");
    }

    private static void assertShaderContract() {
        String shader = RayTracingShaderCommon.PBR_FUNCTIONS
            .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//[^\\r\\n]*", "");
        require(shader, "const int PBR_PARALLAX_STEPS = 32;");
        require(shader, "const int PBR_PARALLAX_REFINEMENTS = 5;");
        require(functionBody(shader, "vec2 pbrWrapCoord("), "return fract(coord);");
        String body = functionBody(shader, "vec2 pbrParallaxUv(");
        int wrappedCoord = require(body,
            "vec2 coord = pbrWrapCoord((atlasUv - vec2(u0, v0)) / span);");
        int heightGuard = require(body,
            "if (startHeight <= 0.0 || startHeight >= 0.999) return atlasUv;");
        int viewGuard = require(body, "if (viewTangent.z < 0.001) return atlasUv;");
        String deltaDeclaration = "vec2 parallaxDelta = parallaxDirection\n"
            + "    * clamp(camera.pbrParallaxSettings.x, 0.0, 4.0)\n"
            + "    * 0.2 / float(PBR_PARALLAX_STEPS);";
        int delta = require(body, "vec2 parallaxDelta = parallaxDirection");
        String earlyReturn = "if (all(equal(parallaxDelta, vec2(0.0)))) {\n"
            + "    return vec2(u0, v0) + coord * span;\n}";
        // Normalize indentation only: keep the exact equality and the complete branch together.
        String normalized = body.replaceAll("(?m)^[ \\t]+", "");
        String normalizedReturn = earlyReturn.replaceAll("(?m)^[ \\t]+", "");
        int deltaEnd = require(normalized, deltaDeclaration.replaceAll("(?m)^[ \\t]+", ""))
            + deltaDeclaration.replaceAll("(?m)^[ \\t]+", "").length();
        int early = require(normalized, normalizedReturn);
        int guard = require(body, "if (all(equal(parallaxDelta, vec2(0.0)))) {");
        int setup = require(body, "float rayDepthDelta = 1.0 / float(PBR_PARALLAX_STEPS);");
        int search = require(body,
            "for (int stepIndex = 0; stepIndex < PBR_PARALLAX_STEPS; stepIndex++) {");
        int miss = require(body, "if (!intersectionFound) return atlasUv;");
        int refinement = require(body,
            "for (int refinement = 0; refinement < PBR_PARALLAX_REFINEMENTS; refinement++) {");
        if (!(wrappedCoord < heightGuard && heightGuard < viewGuard && viewGuard < delta
                && delta < guard && guard < setup && setup < search && search < miss
                && miss < refinement && deltaEnd <= early)) {
            throw new AssertionError("Exact-zero return must follow valid height/view guards and precede marching");
        }
        if (braceDepth(body, guard) != 0) {
            throw new AssertionError("Exact-zero branch must be at function scope, not nested in another branch/loop");
        }
        String deltaToSearch = body.substring(delta, search);
        if (deltaToSearch.indexOf("if (") != guard - delta
                || deltaToSearch.contains("for (") || deltaToSearch.contains("while (")) {
            throw new AssertionError("Exact-zero return must precede any post-delta branch or loop");
        }
    }

    private static void assertMathematicalReferences() {
        // Sprite bounds [0.25, 0.5) x [0.5, 0.625); dyadic inputs have exact known answers.
        assertZeroDisplacement("zero configured depth", 0.3125, 0.5625, 0.75, -0.5, 1.0, 0.0,
            0.3125, 0.5625);
        assertZeroDisplacement("front-on view", 0.375, 0.53125, 0.0, 0.0, 1.0, 4.0,
            0.375, 0.53125);
        assertZeroDisplacement("upper tile boundary wraps to origin", 0.5, 0.625, 0.0, 0.0, 1.0, 1.0,
            0.25, 0.5);
        assertZeroDisplacement("negative tile coordinates wrap", 0.1875, 0.46875, 0.5, 0.25, 1.0, 0.0,
            0.4375, 0.59375);
        assertZeroDisplacement("lower tile boundary stays at origin", 0.25, 0.5, 0.0, -0.0, 1.0, 2.0,
            0.25, 0.5);
        // Existing invalid-height/grazing-view guards intentionally return the original atlas UV.
        assertUv("flat height guard", reference(0.5, 0.625, 1.0, 0.0, 0.0, 1.0, 0.0, true),
            0.5, 0.625);
        assertUv("zero height guard", reference(0.5, 0.625, 0.0, 0.0, 0.0, 1.0, 0.0, true),
            0.5, 0.625);
        assertUv("grazing view guard", reference(0.5, 0.625, 0.5, 0.0, 0.0, 0.0, 0.0, true),
            0.5, 0.625);
        if (!exactZero(0.0, -0.0) || exactZero(1.0e-12, 0.0) || exactZero(0.0, -1.0e-12)) {
            throw new AssertionError("Only an exactly zero vector qualifies, not a small displacement or one zero axis");
        }
    }

    private static void assertZeroDisplacement(String label, double u, double v,
                                               double viewX, double viewY, double viewZ, double depth,
                                               double expectedU, double expectedV) {
        // Constant authored relief heights exercise both early and late layer intersections.
        for (double height : new double[] {0.25, 0.5, 0.75}) {
            assertUv(label + " legacy", reference(u, v, height, viewX, viewY, viewZ, depth, false),
                expectedU, expectedV);
            assertUv(label + " optimized", reference(u, v, height, viewX, viewY, viewZ, depth, true),
                expectedU, expectedV);
        }
    }

    private static double[] reference(double u, double v, double height,
                                      double viewX, double viewY, double viewZ, double depth,
                                      boolean optimized) {
        double x = fract((u - 0.25) / 0.25);
        double y = fract((v - 0.5) / 0.125);
        if (height <= 0.0 || height >= 0.999 || viewZ < 0.001) return new double[] {u, v};
        double scale = Math.max(0.0, Math.min(depth, 4.0)) * 0.2 / 32.0;
        double dx = viewX / viewZ * scale;
        double dy = viewY / viewZ * 2.0 * scale;
        if (optimized && exactZero(dx, dy)) return atlas(x, y);
        double previousX = x;
        double previousY = y;
        double previousDepth = 1.0;
        double rayDepth = 1.0;
        boolean found = false;
        for (int step = 0; step < 32; step++) {
            previousX = x;
            previousY = y;
            previousDepth = rayDepth;
            x -= dx;
            y -= dy;
            rayDepth -= 1.0 / 32.0;
            if (height > rayDepth) {
                found = true;
                break;
            }
        }
        if (!found) return new double[] {u, v};
        for (int refinement = 0; refinement < 5; refinement++) {
            double midpointX = (previousX + x) * 0.5;
            double midpointY = (previousY + y) * 0.5;
            double midpointDepth = (previousDepth + rayDepth) * 0.5;
            if (height > midpointDepth) {
                x = midpointX;
                y = midpointY;
                rayDepth = midpointDepth;
            } else {
                previousX = midpointX;
                previousY = midpointY;
                previousDepth = midpointDepth;
            }
        }
        double previousDelta = height - previousDepth;
        double currentDelta = height - rayDepth;
        double weight = Math.max(0.0, Math.min(1.0,
            -previousDelta / Math.max(currentDelta - previousDelta, 0.000001)));
        return atlas(previousX * (1.0 - weight) + x * weight,
            previousY * (1.0 - weight) + y * weight);
    }

    private static boolean exactZero(double x, double y) {
        return x == 0.0 && y == 0.0;
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    private static double[] atlas(double x, double y) {
        return new double[] {0.25 + fract(x) * 0.25, 0.5 + fract(y) * 0.125};
    }

    private static void assertUv(String label, double[] actual, double u, double v) {
        if (actual[0] != u || actual[1] != v) {
            throw new AssertionError(label + ": expected (" + u + ", " + v + "), got ("
                + actual[0] + ", " + actual[1] + ")");
        }
    }

    private static String functionBody(String shader, String signature) {
        int start = shader.indexOf('{', require(shader, signature));
        int depth = 1;
        for (int i = start + 1; i < shader.length(); i++) {
            if (shader.charAt(i) == '{') depth++;
            if (shader.charAt(i) == '}' && --depth == 0) return shader.substring(start + 1, i);
        }
        throw new AssertionError("Unclosed GLSL function: " + signature);
    }

    private static int braceDepth(String source, int end) {
        int depth = 0;
        for (int i = 0; i < end; i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}') depth--;
        }
        return depth;
    }

    private static int require(String source, String token) {
        int index = source.indexOf(token);
        if (index < 0) throw new AssertionError("Missing GLSL contract: " + token);
        return index;
    }
}
