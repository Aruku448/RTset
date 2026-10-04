package com.rtest.client;

import com.rtest.client.RayTracingScene.SceneGeometry;
import com.rtest.client.RayTracingScene.SceneGeometry.SectionGeometry;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Actual immutable scene merge, packed-word differential, and optional repeated-edit benchmark. */
public final class LightTreeReuseMathTest {
    private static final Constructor<SectionGeometry> SECTION;
    private static final Constructor<SceneGeometry> SCENE;
    static {
        try {
            SECTION = SectionGeometry.class.getDeclaredConstructor(int.class, int.class, int.class, float[].class, float[].class);
            SECTION.setAccessible(true);
            SCENE = SceneGeometry.class.getDeclaredConstructor(List.class, float[].class, float[].class, int[].class,
                int.class, int.class, double.class, double.class, double.class);
            SCENE.setAccessible(true);
        } catch (ReflectiveOperationException exception) { throw new ExceptionInInitializerError(exception); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args[0].equals("bench")) { benchmark(); return; }
        SectionGeometry lamp = section(0, 2, 1), dark = section(16, 1, 0);
        SceneGeometry first = scene(List.of(lamp, dark));
        SceneGeometry next = merge(first, dark, section(16, 1, 0));
        exact(next);
        if (next.lightTree != first.lightTree) throw new AssertionError("non-emissive edit rebuilt identical light tree");
        SceneGeometry equivalent = merge(next, lamp, section(0, 2, 1));
        exact(equivalent);
        if (equivalent.lightTree != next.lightTree) throw new AssertionError("equivalent emissive payload rebuilt identical light tree");
        SceneGeometry changed = merge(equivalent, equivalent.sections.getLast(), section(0, 2, 2));
        exact(changed);
        if (changed.lightTree == equivalent.lightTree) throw new AssertionError("changed emission reused old light PDF");
        SceneGeometry resized = merge(first, dark, section(16, 4, 0));
        exact(resized);
        if (resized.lightTree == first.lightTree) throw new AssertionError("changed material-map extent reused old words");
        // Replacing the first of two equal-power lamps changes source ordering/tie stability.
        SceneGeometry tied = scene(List.of(section(0, 2, 1), section(16, 2, 1)));
        SceneGeometry reordered = merge(tied, tied.sections.getFirst(), section(0, 2, 1));
        exact(reordered);
        if (reordered.lightTree == tied.lightTree) throw new AssertionError("emitter source order ignored");
        guardCoverage();
        java.util.Random random = new java.util.Random(974);
        SceneGeometry state = scene(List.of(section(0, 3, 1), section(16, 3, 0), section(32, 2, 2)));
        for (int i = 0; i < 150; i++) {
            SectionGeometry old = state.sections.get(random.nextInt(state.sections.size()));
            state = merge(state, old, section(old.originX, 1 + random.nextInt(6), random.nextBoolean() ? 0 : 1 + random.nextInt(3)));
            exact(state);
        }
        System.out.println("Light-tree immutable reuse/packed-word differential passed (not GPU or live timing validation)");
    }

    private static void guardCoverage() throws Exception {
        SectionGeometry light = section(0, 128, 1), dark = section(16, 1, 0);
        SceneGeometry old = scene(List.of(light, dark));
        for (int field : new int[] {0, 1, 2, 4, 5, 6, 15, 22}) {
            float[] materials = light.materialData.clone();
            materials[field] += 0.25F;
            SectionGeometry changed = SECTION.newInstance(0, 0, 0, light.vertices.clone(), materials);
            check(old, List.of(changed, dark), old.materialLayout, 0, false);
        }
        for (int v = 0; v < 9; v++) {
            float[] vertices = light.vertices.clone();
            vertices[v] += 0.25F;
            SectionGeometry changed = SECTION.newInstance(0, 0, 0, vertices, light.materialData.clone());
            check(old, List.of(changed, dark), old.materialLayout, 0, false);
        }
        float[] uvOnly = light.materialData.clone();
        uvOnly[7] = 0.3F;
        check(old, List.of(SECTION.newInstance(0, 0, 0, light.vertices.clone(), uvOnly), dark), old.materialLayout, 0, true);
        check(old, old.sections, old.materialLayout, 1, false);
        check(old, old.sections, old.materialLayout, -0.0, false);
        // Same emitting section object is insufficient when compaction changed its base.
        check(old, List.of(dark, light), RayTracingMaterialBuffer.Layout.compact(List.of(dark, light)), 0, false);
        for (int bits : new int[] {0x80000000, 0x7fc00001, 0x7fc00002}) {
            float[] materials = light.materialData.clone();
            materials[4] = Float.intBitsToFloat(bits);
            check(old, List.of(SECTION.newInstance(0, 0, 0, light.vertices.clone(), materials), dark), old.materialLayout, 0, false);
        }
        var partialConstructor = SceneGeometry.class.getDeclaredConstructor(List.class, float[].class, float[].class,
            int[].class, int.class, int.class, double.class, double.class, double.class,
            boolean.class, long.class, boolean.class, boolean.class);
        partialConstructor.setAccessible(true);
        SceneGeometry partial = partialConstructor.newInstance(old.sections, new float[0], new float[0], new int[0],
            129, 2, 0, 0, 0, true, 42L, false, false);
        check(partial, partial.sections, partial.materialLayout, 0, false);
        SceneGeometry composed = SceneGeometry.compose(List.of(light, dark), List.of(), old, 2, old);
        exact(composed);
        if (composed.lightTree != old.lightTree) throw new AssertionError("compose failed to reuse identical ordered inputs");
        SceneGeometry unlit = scene(List.of(dark));
        check(unlit, unlit.sections, unlit.materialLayout, 0, true);
    }

    private static void check(SceneGeometry old, List<SectionGeometry> sections,
                              RayTracingMaterialBuffer.Layout layout, double originX, boolean reuse) {
        var result = RayTracingLightTree.buildOrReuse(old, sections, originX, 0, 0, layout);
        var fresh = RayTracingLightTree.build(sections, originX, 0, 0, layout);
        if (!Arrays.equals(result.words(), fresh.words())) throw new AssertionError("guard differs from full packed build");
        if ((result == old.lightTree) != reuse) throw new AssertionError("incorrect reuse eligibility");
    }

    private static SceneGeometry merge(SceneGeometry source, SectionGeometry old, SectionGeometry replacement) throws Exception {
        var key = SceneGeometry.class.getDeclaredMethod("sectionOriginKey", SectionGeometry.class);
        key.setAccessible(true);
        return source.replaceSections(List.of((Long)key.invoke(null, old)), scene(List.of(replacement)), Vec3.ZERO);
    }

    private static void exact(SceneGeometry scene) {
        int[] fresh = RayTracingLightTree.build(scene.sections, scene.originX, scene.originY, scene.originZ, scene.materialLayout).words();
        if (!Arrays.equals(scene.lightTree.words(), fresh)) throw new AssertionError("reused light-tree words differ from full build");
    }

    private static SceneGeometry scene(List<SectionGeometry> sections) throws Exception {
        return SCENE.newInstance(sections, new float[0], new float[0], new int[0],
            sections.stream().mapToInt(SectionGeometry::triangleCount).sum(), 2, 0, 0, 0);
    }

    private static SectionGeometry section(int x, int triangles, float emission) throws Exception {
        float[] vertices = new float[triangles * 9], materials = new float[triangles * 28];
        for (int i = 0; i < triangles; i++) {
            System.arraycopy(new float[] {i,1,0,i+1,1,0,i+1,1,1}, 0, vertices, i * 9, 9);
            int m = i * 28;
            materials[m] = materials[m+1] = materials[m+2] = materials[m+3] = 1;
            materials[m+5] = 1; materials[m+22] = emission;
        }
        return SECTION.newInstance(x, 0, 0, vertices, materials);
    }

    private static volatile int sink;
    private static void benchmark() throws Exception {
        ArrayList<SectionGeometry> sections = new ArrayList<>();
        for (int i = 0; i < 4000; i++) sections.add(section(i * 16, 20, 1));
        SectionGeometry dark = section(64000, 1, 0);
        sections.add(dark);
        SceneGeometry initial = scene(sections), delta = scene(List.of(section(64000, 1, 0)));
        var key = SceneGeometry.class.getDeclaredMethod("sectionOriginKey", SectionGeometry.class);
        key.setAccessible(true);
        List<Long> keys = List.of((Long)key.invoke(null, dark));
        long[] times = new long[9];
        for (int i = -5; i < times.length; i++) {
            long start = System.nanoTime();
            SceneGeometry result = initial.replaceSections(keys, delta, Vec3.ZERO);
            long nanos = System.nanoTime() - start;
            sink = Arrays.hashCode(result.lightTree.words());
            if (i >= 0) times[i] = nanos;
        }
        Arrays.sort(times);
        System.out.printf(java.util.Locale.ROOT, "LIGHT_REUSE_BENCH sections=4001 emitters=80000 median_ms=%.3f min_ms=%.3f max_ms=%.3f words_hash=%d%n",
            times[4]/1e6, times[0]/1e6, times[8]/1e6, sink);
    }
}
