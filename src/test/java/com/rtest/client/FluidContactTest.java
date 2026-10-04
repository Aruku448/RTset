package com.rtest.client;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Actual voxel-shape seam plus CPU plane replay of production GLSL spawn; not a GPU/world test. */
public final class FluidContactTest {
    private static Method occlusion;
    private static final String SHADER = RayTracingShaderRaygen.RAYGEN_SHADER;
    private static final String SCENE;
    static {
        try {
            SCENE = Files.readString(Path.of("src/main/java/com/rtest/client/RayTracingScene.java"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void main(String[] args) throws Exception {
        occlusion = RayTracingScene.SceneGeometry.class.getDeclaredMethod(
            "fluidFaceOccludedByNeighbor", Direction.class, float.class, VoxelShape.class);
        occlusion.setAccessible(true);
        if (args.length == 0 || !args[0].equals("bias")) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                assertContactRetainsOpaque(side, Shapes.block(), 0.875F, 0);
            }
        }
        // A high fluid side is visible above a bottom slab, but its lower half still borders
        // the opaque slab wall. Culling ALL sides near a partial solid would lose real fluid.
        VoxelShape slab = Shapes.box(0, 0, 0, 1, 0.5, 1);
        for (int base : new int[] {-1024, -16, 0, 16, 1023}) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                assertContactRetainsOpaque(side, slab, 0.875F, base);
            }
        }
        assertCapSpacing();
        assertShapeCoverage(slab);
        require(SCENE, "fluidFaceOccludedByNeighbor(direction, Math.max(h0, h1),");
        require(SCENE, "neighborState.getFaceOcclusionShape(direction.getOpposite())");
        require(SCENE, "FluidRenderer.shouldRenderFace(fluidState, blockState, direction, neighborState)");
        System.out.println("Fluid/opaque contact plane replay and voxel coverage passed (not GPU screenshot validation)");
    }

    private static boolean occluded(Direction direction, float height, VoxelShape neighbor) throws Exception {
        return (boolean) occlusion.invoke(null, direction, height, neighbor.getFaceShape(direction.getOpposite()));
    }

    private static void assertContactRetainsOpaque(Direction side, VoxelShape neighbor,
                                                    float height, int base) throws Exception {
        if (occluded(side, height, neighbor)) return; // Opaque is first hit: no false dielectric wall.
        if (!(neighbor.min(Direction.Axis.Y) <= 0.25 && neighbor.max(Direction.Axis.Y) >= 0.25)) {
            throw new AssertionError("contact fixture must contain an opaque wall at sample Y=0.25");
        }
        boolean positive = side == Direction.EAST || side == Direction.SOUTH;
        // Pull the actual emitted side coordinate from the production adapter.
        int switchCase = SCENE.indexOf("case " + side.name() + " -> {",
            SCENE.indexOf("private static void addFluidGeometry("));
        var vertex = Pattern.compile("v0 = new FluidVertex\\(([^,]+), h0, ([^,]+),")
            .matcher(SCENE.substring(switchCase));
        if (!vertex.find()) throw new AssertionError("missing fluid vertex for " + side);
        float inset = Float.parseFloat(vertex.group(side.getAxis() == Direction.Axis.X ? 1 : 2).trim().replace("F", ""));
        float wall = base + (positive ? 1.0F : 0.0F);
        float interfacePoint = base + inset;
        float spawn = interfacePoint + (positive ? bias(interfacePoint) : -bias(interfacePoint));
        float distance = positive ? wall - spawn : spawn - wall;
        if (!(distance >= traceMin())) {
            throw new AssertionError("water/opaque contact loses block face: " + side
                + " water=" + interfacePoint + " wall=" + wall + " spawn=" + spawn
                + " nextHitT=" + distance + " traceMin=" + traceMin());
        }
    }

    private static void assertCapSpacing() {
        // A glass neighbor has empty occlusion: the water interface must remain, but it
        // cannot be coplanar with and then offset beyond the glass/block interface.
        int start = SCENE.indexOf("if (!fluid.isSame(belowFluid.getType())");
        if (start < 0) start = SCENE.indexOf("if (renderBottom)");
        var bottomVertex = Pattern.compile("new FluidVertex\\(0\\.0F, ([0-9.]+F|bottomOffset), 0\\.0F")
            .matcher(SCENE.substring(start));
        if (!bottomVertex.find()) throw new AssertionError("missing production fluid bottom vertex");
        float bottom = bottomVertex.group(1).equals("bottomOffset")
            ? sceneFloat("bottomOffset = renderBottom \\? ([0-9.]+)F", 0)
            : Float.parseFloat(bottomVertex.group(1).replace("F", ""));
        float bottomSpawn = bottom - bias(bottom);
        if (!(bottomSpawn >= traceMin())) {
            throw new AssertionError("water bottom skips touching block interface: spawn=" + bottomSpawn);
        }
        float topInset = sceneFloat("northWest -= ([0-9.]+)F;", 0);
        float top = 0.99999F - topInset;
        float topDistance = 1.0F - (top + bias(top));
        if (!(topDistance >= traceMin())) {
            throw new AssertionError("water top skips touching block interface: nextHitT=" + topDistance);
        }
        require(SCENE, "fluidFaceOccludedByNeighbor(Direction.UP,");
        require(SCENE, "fluidFaceOccludedByNeighbor(Direction.DOWN,");
    }

    private static float sceneFloat(String pattern, float fallback) {
        var match = Pattern.compile(pattern).matcher(SCENE);
        return match.find() ? Float.parseFloat(match.group(1)) : fallback;
    }

    private static void assertShapeCoverage(VoxelShape slab) throws Exception {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!occluded(side, 0.875F, Shapes.block()) || occluded(side, 0.875F, Shapes.empty())
                || occluded(side, 0.875F, slab) || !occluded(side, 0.25F, slab)) {
                throw new AssertionError("full/empty/partial neighbor coverage is wrong: " + side);
            }
        }
        if (occluded(Direction.UP, 0.875F, Shapes.block())
            || !occluded(Direction.UP, 1.0F, Shapes.block())
            || !occluded(Direction.DOWN, 0.875F, Shapes.block())
            || occluded(Direction.DOWN, 0.875F, slab)
            || !occluded(Direction.DOWN, 0.875F, Shapes.box(0, 0.5, 0, 1, 1, 1))) {
            throw new AssertionError("top/bottom contact must use face shape and actual fluid height");
        }
    }

    private static float bias(float coordinate) {
        var original = Pattern.compile("rayOrigin = pathPosition\\.xyz \\+ offsetNormal \\* ([0-9.]+);").matcher(SHADER);
        if (original.find()) return Float.parseFloat(original.group(1));
        require(SHADER, "rayOffset = transmission ? dielectricRayOffset(pathPosition.xyz) : 0.003;");
        require(SHADER, "max(DIELECTRIC_RAY_MIN_OFFSET, maxCoord * DIELECTRIC_RAY_ERROR_SCALE)");
        return Math.max(constant("DIELECTRIC_RAY_MIN_OFFSET"), Math.abs(coordinate) * constant("DIELECTRIC_RAY_ERROR_SCALE"));
    }

    private static float traceMin() {
        var original = Pattern.compile("rayOrigin,\\s*([0-9.]+),\\s*rayDirection,").matcher(SHADER);
        if (original.find()) return Float.parseFloat(original.group(1));
        if (!Pattern.compile("rayOrigin,\\s*rayTMin,\\s*rayDirection,").matcher(SHADER).find()) {
            throw new AssertionError("production trace must use continuation-specific tMin");
        }
        require(SHADER, "rayTMin = transmission ? DIELECTRIC_RAY_T_MIN : 0.001;");
        return constant("DIELECTRIC_RAY_T_MIN");
    }

    private static float constant(String name) {
        var match = Pattern.compile("const float " + name + " = ([0-9.eE+-]+);").matcher(SHADER);
        if (!match.find()) throw new AssertionError("missing shader ray-spawn constant: " + name);
        return Float.parseFloat(match.group(1));
    }

    private static void require(String source, String expected) {
        if (!source.contains(expected)) throw new AssertionError("missing production integration: " + expected);
    }
}
