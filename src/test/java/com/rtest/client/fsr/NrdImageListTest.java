package com.rtest.client.fsr;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/** Checks real Images list construction and aliases; creates no Vulkan allocations. */
public final class NrdImageListTest {
    private NrdImageListTest() { }

    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName(NrdDenoiser.class.getName() + "$Images");
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        for (boolean aliases : new boolean[] {false, true}) {
            Object[] values = new Object[constructor.getParameterCount()];
            RtestVulkanImage first = image(1), pooled = image(100);
            for (int i = 0; i < values.length - 2; i++) {
                values[i] = aliases && i % 3 == 0 ? first : image(i + 2);
            }
            values[values.length - 2] = new RtestVulkanImage[] {first, pooled};
            values[values.length - 1] = new RtestVulkanImage[] {pooled, image(101)};
            Object images = constructor.newInstance(values);
            Method guides = type.getDeclaredMethod("rayTraceImages");
            Method all = type.getDeclaredMethod("allImages");
            guides.setAccessible(true);
            all.setAccessible(true);
            RtestVulkanImage[] guideList = (RtestVulkanImage[]) guides.invoke(images);
            RtestVulkanImage[] owned = (RtestVulkanImage[]) all.invoke(images);
            if (guides.invoke(images) != guideList || all.invoke(images) != owned) {
                throw new AssertionError("fixed image lists must not allocate every frame");
            }
            String[] guideFields = {"noisyDiffuse", "noisySpecular", "normalRoughness", "viewZ",
                "motion", "material", "primaryPosition", "specularMaterial", "directDiffuse",
                "indirectDiffuse", "emission"};
            if (guideList.length != guideFields.length) throw new AssertionError("guide count changed");
            for (int i = 0; i < guideFields.length; i++) {
                var field = type.getDeclaredField(guideFields[i]);
                field.setAccessible(true);
                if (guideList[i] != field.get(images)) throw new AssertionError("guide order changed");
            }
            List<RtestVulkanImage> expected = new ArrayList<>();
            Set<RtestVulkanImage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            String[] fixed = {"noisyDiffuse", "noisySpecular", "normalRoughness", "viewZ", "motion",
                "material", "directDiffuse", "indirectDiffuse", "emission", "specularMaterial",
                "denoisedDiffuse", "denoisedSpecular", "primaryPosition", "reprojectionError",
                "validation", "fsrDepth", "fsrReactiveMask", "fsrTransparencyCompositionMask",
                "transparentThroughput"};
            for (String name : fixed) {
                var field = type.getDeclaredField(name);
                field.setAccessible(true);
                RtestVulkanImage image = (RtestVulkanImage) field.get(images);
                if (seen.add(image)) expected.add(image);
            }
            for (int i = values.length - 2; i < values.length; i++) {
                for (RtestVulkanImage image : (RtestVulkanImage[]) values[i]) {
                    if (seen.add(image)) expected.add(image);
                }
            }
            if (owned.length != expected.size()) throw new AssertionError("owned image dedup changed");
            for (int i = 0; i < owned.length; i++) {
                if (owned[i] != expected.get(i)) throw new AssertionError("owned image order changed");
            }
        }
        System.out.println("NRD fixed image lists and alias ownership passed (no Vulkan allocations)");
    }

    private static RtestVulkanImage image(long id) {
        // The constructor only stores metadata. Never close these non-owning test containers.
        return new RtestVulkanImage(0L, null, id, 0L, id, new long[] {id}, 0, 1, 1);
    }
}
