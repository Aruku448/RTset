package com.rtest.client.fsr;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import com.mojang.logging.LogUtils;
import com.rtest.client.RayTracingClientConfig;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/** Process-owned Streamline binding, initialized before the game's Vulkan instance. */
public final class DlssRuntime {
    private static final String PREFIX = "/rtest/natives/windows-x86_64/streamline/";
    private static final String[] FILES = {"rtest_dlss.dll", "sl.interposer.dll", "sl.common.dll",
        "sl.dlss.dll", "sl.dlss_d.dll", "nvngx_dlss.dll", "nvngx_dlssd.dll", "NvLowLatencyVk.dll", "nvngx_dlss.license.txt"};
    private static final Map<String, MethodHandle> FUNCTIONS = new HashMap<>();
    private static SymbolLookup library;
    private static boolean attempted, ready, deviceRequirementsEnabled;
    private static boolean nativeBootstrapped;
    private static VkDevice attachedDevice;
    private static int capabilities;
    private static int graphicsQueueIndex;
    private static String reason = "DLSS has not been initialized";
    private static List<String> requirements = List.of();
    private static RtestUpscalerMode lastRequested;
    private static String extentKey;
    private static RtestFsrQualityMode.Extent cachedExtent;
    private static final VulkanFeature PRIVATE_DATA = new VulkanFeature(new VulkanPNextStruct(
        VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PRIVATE_DATA_FEATURES, VkPhysicalDevicePrivateDataFeatures.SIZEOF),
        "privateData", VkPhysicalDevicePrivateDataFeatures.PRIVATEDATA);
    private DlssRuntime() {}

    public static synchronized void bootstrap() {
        if (attempted) return;
        attempted = true;
        if (!System.getProperty("os.name", "").startsWith("Windows") ||
            !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""))) {
            reason = "DLSS requires Windows x86_64"; return;
        }
        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "rtest-streamline-2.14.1");
            Files.createDirectories(directory);
            for (String name : FILES) {
                byte[] bytes;
                try (var in = DlssRuntime.class.getResourceAsStream(PREFIX + name)) {
                    if (in == null) throw new IllegalStateException("Missing bundled DLSS file: " + name);
                    bytes = in.readAllBytes();
                }
                // The bridge digest selects a distinct cache after changes; old loaded DLLs stay intact.
                if (name.equals("rtest_dlss.dll")) {
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    directory = directory.resolve(digest); Files.createDirectories(directory);
                }
                Path output = directory.resolve(name);
                if (!Files.exists(output) || !Arrays.equals(MessageDigest.getInstance("SHA-256").digest(bytes),
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output)))) {
                    Path temporary = Files.createTempFile(directory, name, ".tmp");
                    try { Files.write(temporary, bytes); Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
                    finally { Files.deleteIfExists(temporary); }
                }
            }
            library = SymbolLookup.libraryLookup(directory.resolve("rtest_dlss.dll"), Arena.global());
            bind("abi", ValueLayout.JAVA_INT);
            bind("bootstrap", ValueLayout.JAVA_INT);
            bind("error", ValueLayout.ADDRESS);
            bind("requirements", ValueLayout.ADDRESS);
            bind("attach", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
            bind("capabilities", ValueLayout.JAVA_INT);
            bind("size", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("create", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("evaluate", ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
            bind("destroy", ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
            bind("present", ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);
            bind("shutdown", ValueLayout.JAVA_INT);
            if ((int) invoke("abi") != 1) throw new IllegalStateException("DLSS bridge ABI mismatch");
            check("bootstrap", invoke("bootstrap"));
            nativeBootstrapped = true;
            requirements = Arrays.asList(string((MemorySegment) invoke("requirements")).split("\n"));
            ready = true;
            reason = "The Vulkan device has not been attached";
            LogUtils.getLogger().info("RTest Streamline 2.14.1 initialized before Vulkan; requirements={}", requirements);
        } catch (Throwable failure) { unavailable(failure); }
    }
    private static void bind(String name, MemoryLayout result, MemoryLayout... args) {
        FUNCTIONS.put(name, Linker.nativeLinker().downcallHandle(library.find("rtest_sl_" + name).orElseThrow(),
            FunctionDescriptor.of(result, args)));
    }
    static Object invoke(String name, Object... args) {
        try { return FUNCTIONS.get(name).invokeWithArguments(args); }
        catch (Throwable failure) { throw new IllegalStateException("DLSS native call failed: " + name, failure); }
    }
    static void check(String operation, Object status) {
        if ((int) status != 0) throw new IllegalStateException("DLSS " + operation + ": " + string((MemorySegment) invoke("error")));
    }
    private static String string(MemorySegment pointer) {
        return pointer.address() == 0 ? "unknown" : pointer.reinterpret(65536).getString(0);
    }
    private static void unavailable(Throwable failure) {
        reason = failure.getMessage(); ready = false; capabilities = 0;
        LogUtils.getLogger().warn("RTest DLSS unavailable; FSR fallback: {}", reason);
    }
    public static String unavailableReason() { return reason; }
    public static void addInstanceRequirements(Set<String> extensions) {
        bootstrap();
        if (ready) for (String line : requirements) if (line.startsWith("I:")) extensions.add(line.substring(2));
    }
    public static void addDeviceRequirements(Collection<String> extensions, Set<VulkanFeature> features,
                                              VulkanPhysicalDevice physical) {
        bootstrap(); deviceRequirementsEnabled = false;
        if (!ready) return;
        if (physical.vkPhysicalDeviceProperties().vendorID() != 0x10de) {
            reason = "The selected GPU is not an NVIDIA adapter"; return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> extraExtensions = new LinkedHashSet<>();
            Set<VulkanFeature> extraFeatures = new LinkedHashSet<>();
            for (String line : requirements) {
                if (line.startsWith("D:")) {
                    String name = line.substring(2);
                    // Vulkan 1.2 bufferDeviceAddress replaces the mutually exclusive legacy EXT path.
                    if (name.equals("VK_EXT_buffer_device_address")) continue;
                    if (!physical.hasDeviceExtension(name)) throw new IllegalStateException("Missing DLSS extension " + name);
                    extraExtensions.add(name);
                } else if (line.startsWith("12:") || line.startsWith("13:")) {
                    boolean v12 = line.startsWith("12:"); String name = line.substring(3);
                    Class<?> structure = v12 ? VkPhysicalDeviceVulkan12Features.class : VkPhysicalDeviceVulkan13Features.class;
                    int offset = structure.getField(name.toUpperCase(Locale.ROOT)).getInt(null);
                    VulkanPNextStruct chain = v12 ? VulkanBackend.VK12_FEATURES_STRUCT : new VulkanPNextStruct(
                        VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, VkPhysicalDeviceVulkan13Features.SIZEOF);
                    extraFeatures.add(new VulkanFeature(chain, name, offset));
                }
            }
            // Minecraft requests Vulkan 1.2 and already chains standalone synchronization2.
            // A Vulkan13 aggregate here would conflict with that existing node.
            if (!physical.hasDeviceExtension("VK_EXT_private_data")) throw new IllegalStateException("Missing DLSS private data extension");
            extraExtensions.add("VK_EXT_private_data"); extraFeatures.add(PRIVATE_DATA);
            var query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (var feature : extraFeatures) feature.struct().findOrCreateStructInPNextChain(query, stack);
            VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), query);
            for (var feature : extraFeatures) if (!feature.get(query)) throw new IllegalStateException("Missing DLSS feature " + feature.name());
            for (String extension : extraExtensions) if (!extensions.contains(extension)) extensions.add(extension);
            features.addAll(extraFeatures);
            graphicsQueueIndex = physical.graphicsQueueFamilyAndIndex().rightInt();
            deviceRequirementsEnabled = true;
        } catch (Throwable failure) { unavailable(failure); }
    }
    /** Called on the final actual creation chain, before vkCreateDevice. */
    public static void inspectDeviceFeatures(VkDeviceCreateInfo createInfo) {
        if (!deviceRequirementsEnabled) return;
        if (!PRIVATE_DATA.get(createInfo.address())) {
            deviceRequirementsEnabled = false; unavailable(new IllegalStateException("DLSS privateData was omitted from device creation")); return;
        }
        for (String line : requirements) if (line.startsWith("12:") || line.startsWith("13:")) {
            boolean v12 = line.startsWith("12:"); String name = line.substring(3);
            try {
                Class<?> type = v12 ? VkPhysicalDeviceVulkan12Features.class : VkPhysicalDeviceVulkan13Features.class;
                var chain = new VulkanPNextStruct(v12 ? VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES :
                    VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, v12 ? VkPhysicalDeviceVulkan12Features.SIZEOF : VkPhysicalDeviceVulkan13Features.SIZEOF);
                if (!new VulkanFeature(chain, name, type.getField(name.toUpperCase(Locale.ROOT)).getInt(null)).get(createInfo.address())) {
                    deviceRequirementsEnabled = false; unavailable(new IllegalStateException("DLSS feature omitted from device creation: " + name)); return;
                }
            } catch (ReflectiveOperationException e) { deviceRequirementsEnabled = false; unavailable(e); return; }
        }
    }
    public static void attach(VulkanDevice device) {
        if (!ready || !deviceRequirementsEnabled || attachedDevice != null) return;
        try {
            check("attach", invoke("attach", device.instance().vkInstance().address(), device.vkDevice().getPhysicalDevice().address(),
                device.vkDevice().address(), device.graphicsQueue().queueFamilyIndex(), graphicsQueueIndex));
            capabilities = (int) invoke("capabilities"); attachedDevice = device.vkDevice();
            reason = capabilities == 3 ? "supported" : "The device does not support all DLSS features";
            LogUtils.getLogger().info("RTest DLSS attached to host GPU: SR={}, RR={}", (capabilities & 1) != 0, (capabilities & 2) != 0);
        } catch (Throwable failure) { unavailable(failure); }
    }
    public static RtestUpscalerMode requested() { return RtestUpscalerMode.fromId(RayTracingClientConfig.INSTANCE.upscaler.get()); }
    public static RtestUpscalerMode effective(VulkanDevice device) {
        RtestUpscalerMode requested = requested();
        RtestUpscalerMode effective = device.vkDevice() == attachedDevice && (capabilities & requested.nativeMode) != 0 ? requested : RtestUpscalerMode.FSR;
        if (requested != lastRequested) {
            LogUtils.getLogger().info("RTest temporal reconstruction: requested={}, effective={}, status={}", requested, effective,
                requested == effective ? "supported" : reason); lastRequested = requested;
        }
        return effective;
    }
    public static RtestFsrQualityMode.Extent renderExtent(VulkanDevice device, RtestFsrQualityMode quality, int width, int height) {
        RtestUpscalerMode mode = effective(device);
        if (mode == RtestUpscalerMode.FSR) return quality.renderExtent(width, height);
        String key = mode + ":" + quality + ":" + width + ":" + height;
        if (key.equals(extentKey)) return cachedExtent;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(8, 4);
            check("optimal extent", invoke("size", mode.nativeMode, width, height, RtestUpscalerMode.quality(quality), out));
            cachedExtent = new RtestFsrQualityMode.Extent(out.get(ValueLayout.JAVA_INT, 0), out.get(ValueLayout.JAVA_INT, 4));
            extentKey = key; return cachedExtent;
        }
    }
    public static int present(VkQueue queue, VkPresentInfoKHR info) {
        if (attachedDevice == null) return KHRSwapchain.vkQueuePresentKHR(queue, info);
        return (int) invoke("present", queue.address(), info.address());
    }
    public static void shutdown() {
        if (!nativeBootstrapped) return;
        check("shutdown", invoke("shutdown")); attachedDevice = null; ready = false; capabilities = 0; extentKey = null;
        nativeBootstrapped = false;
    }
}
