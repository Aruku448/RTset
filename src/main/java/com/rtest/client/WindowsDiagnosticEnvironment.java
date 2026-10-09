package com.rtest.client;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test-instance opt-in, applied before Vulkan enumerates driver capabilities. */
public final class WindowsDiagnosticEnvironment {
    private WindowsDiagnosticEnvironment() {}

    public static boolean prepare() {
        boolean requested = "1".equals(System.getenv("NV_ALLOW_RAYTRACING_VALIDATION"));
        boolean nvidiaMarker = Files.isRegularFile(Path.of("rtest-nvidia-validation.flag"));
        Path validationMarker = Path.of("rtest-vulkan-validation.flag");
        boolean khronosMarker = Files.isRegularFile(validationMarker);
        if (!System.getProperty("os.name", "").startsWith("Windows")) return requested;
        if (!nvidiaMarker && !khronosMarker) return requested;
        try (Arena arena = Arena.ofConfined()) {
            var kernel = SymbolLookup.libraryLookup("Kernel32.dll", arena);
            var setEnvironment = Linker.nativeLinker().downcallHandle(
                kernel.find("SetEnvironmentVariableW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            if (nvidiaMarker) {
                set(arena, setEnvironment, "NV_ALLOW_RAYTRACING_VALIDATION", "1");
                requested = true;
                System.err.println("RTest NVIDIA validation environment prepared before Vulkan (instance marker)");
            }
            if (khronosMarker) {
                Path layerPath = Path.of(Files.readString(validationMarker).trim()).toAbsolutePath();
                if (!Files.isRegularFile(layerPath.resolve("VkLayer_khronos_validation.json"))
                        || !Files.isRegularFile(layerPath.resolve("VkLayer_khronos_validation.dll")))
                    throw new IllegalStateException("Khronos validation marker must name the directory containing the layer DLL and manifest");
                set(arena, setEnvironment, "VK_LAYER_PATH", layerPath.toString());
                String layers = System.getenv("VK_INSTANCE_LAYERS");
                String validation = "VK_LAYER_KHRONOS_validation";
                if (layers == null || layers.isBlank()) layers = validation;
                else if (!java.util.Arrays.asList(layers.split(";")).contains(validation)) layers += ";" + validation;
                set(arena, setEnvironment, "VK_INSTANCE_LAYERS", layers);
                System.err.println("RTest Khronos Vulkan validation prepared before Vulkan (instance marker)");
            }
            return requested;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot prepare Vulkan validation environment from instance marker", failure);
        }
    }

    private static void set(Arena arena, java.lang.invoke.MethodHandle setter, String key, String text) throws Throwable {
        var name = arena.allocateFrom(ValueLayout.JAVA_CHAR, (key + "\0").toCharArray());
        var value = arena.allocateFrom(ValueLayout.JAVA_CHAR, (text + "\0").toCharArray());
        if ((int)setter.invokeExact(name, value) == 0)
            throw new IllegalStateException("SetEnvironmentVariableW failed for " + key);
    }
}
