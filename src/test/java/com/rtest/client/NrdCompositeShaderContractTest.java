package com.rtest.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Regression contract: NRD must never replace an unoccluded sky pixel with denoised history. */
public final class NrdCompositeShaderContractTest {
    private NrdCompositeShaderContractTest() {
    }

    public static void main(String[] args) throws IOException {
        String shader;
        try (InputStream input = NrdCompositeShaderContractTest.class
                .getResourceAsStream("/prime/shaders/nrd_composite_simple.comp")) {
            if (input == null) {
                throw new AssertionError("NRD composite shader resource is missing");
            }
            shader = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(shader, "layout(set = 0, binding = 3, r32f) uniform readonly image2D viewZ;");
        require(shader, "if (viewZValue >= 65503.0)");
        require(shader, "layout(set = 0, binding = 4, rgba16f) uniform readonly image2D material;");
        require(shader, "layout(set = 0, binding = 6, rgba16f) uniform readonly image2D emission;");
        require(shader, "imageLoad(emission, pixel).rgb");
        require(shader, "vec3 filteredSurface = mix(rawSurface, diffuse + specular, strength);");
        require(shader, "vec4 directSample = imageLoad(directDiffuse, pixel);");
        require(shader, "if (directSample.a > 0.5)");
        require(shader, "imageStore(outputColor, pixel, rawValue);");
        require(shader, "imageStore(outputColor, pixel, rawValue);");
        System.out.println("NRD composite sky contract passed");
    }

    private static void require(String shader, String fragment) {
        if (!shader.contains(fragment)) {
            throw new AssertionError("NRD composite sky contract is missing: " + fragment);
        }
    }

}
