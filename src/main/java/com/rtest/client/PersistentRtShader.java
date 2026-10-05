package com.rtest.client;

final class PersistentRtShader {
    static final String GLSL;
    static {
        try (var stream = PersistentRtShader.class.getResourceAsStream("/rtest/shaders/persistent_indirect_cache.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing persistent RT shader");
            GLSL = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private PersistentRtShader() { }
}
