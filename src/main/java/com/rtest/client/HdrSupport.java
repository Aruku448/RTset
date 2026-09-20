package com.rtest.client;

import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTSwapchainColorspace;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Negotiates the optional HDR swapchain path.
 *
 * <p>Two float output modes are supported, in preference order:
 *
 * <ul>
 *   <li>{@link OutputMode#HDR_BT2020_LINEAR} — Rec.2020 primaries with a linear transfer. The RT
 *       scene is already scene-referred linear Rec.2020, so this mode keeps the wide gamut all the
 *       way to the compositor.
 *   <li>{@link OutputMode#HDR_SCRGB} — linear scRGB (BT.709 primaries). The display pass narrows
 *       Rec.2020 to BT.709 first, so only the extended range survives.
 * </ul>
 *
 * <p>Both modes use 1.0 = reference white, which is what makes sRGB content composited on top keep
 * its authored appearance while RT radiance above 1.0 extends into HDR.
 */
public final class HdrSupport {
    public enum OutputMode {
        SDR,
        HDR_SCRGB,
        HDR_BT2020_LINEAR
    }

    public static final String SWAPCHAIN_COLORSPACE_EXTENSION =
        EXTSwapchainColorspace.VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME;
    public static final int COLOR_SPACE_SCRGB =
        EXTSwapchainColorspace.VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT;
    public static final int COLOR_SPACE_BT2020_LINEAR =
        EXTSwapchainColorspace.VK_COLOR_SPACE_BT2020_LINEAR_EXT;
    public static final int FORMAT = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile OutputMode mode = OutputMode.SDR;
    private static volatile long modeGeneration;
    private static volatile boolean availabilityLogged;
    private static volatile boolean formatsLogged;

    private HdrSupport() {
    }

    public static boolean requested() {
        return RayTracingClientConfig.INSTANCE.hdrEnabled.get();
    }

    /**
     * Whether the Rec.2020 output path should be attempted. Rec.2020 primaries are only correct when
     * the compositor advertises them, and some compositors describe the mode without mapping
     * reference white the same way scRGB does, so this stays switchable.
     */
    public static boolean wideGamutPreferred() {
        try {
            return RayTracingClientConfig.INSTANCE.hdrWideGamutEnabled.get();
        } catch (RuntimeException notLoaded) {
            return true;
        }
    }

    public static boolean shouldPreferWayland() {
        return requested() && System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** Called before VkInstance creation to decide whether the extension can be enabled. */
    public static boolean canEnableSwapchainColorspace() {
        if (!requested()) {
            return false;
        }
        boolean available = hasInstanceExtension(SWAPCHAIN_COLORSPACE_EXTENSION);
        if (!available && !availabilityLogged) {
            availabilityLogged = true;
            LOGGER.warn("RTest HDR requested, but {} is unavailable; using SDR", SWAPCHAIN_COLORSPACE_EXTENSION);
        }
        return available;
    }

    public static boolean isActive() {
        return mode != OutputMode.SDR;
    }

    public static OutputMode outputMode() {
        return mode;
    }

    /** True when the surface itself carries Rec.2020 primaries, so RT needs no primaries conversion. */
    public static boolean usesRec2020Primaries() {
        return mode == OutputMode.HDR_BT2020_LINEAR;
    }

    /** Changes whenever the surface output contract changes and dependent targets must rebuild. */
    public static long modeGeneration() {
        return modeGeneration;
    }

    public static synchronized void setMode(OutputMode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        modeGeneration++;
        switch (next) {
            case HDR_BT2020_LINEAR -> LOGGER.warn(
                "RTest HDR enabled: Rec.2020 linear swapchain ({} / format {}); 3D keeps the wide gamut, but "
                    + "native content only displays correctly if the compositor converts linear Rec.2020 to the "
                    + "display encoding. If the picture is far too dark, the compositor is passing it through "
                    + "unchanged: set hdrWideGamutEnabled=false to use scRGB instead",
                COLOR_SPACE_BT2020_LINEAR, FORMAT);
            case HDR_SCRGB -> LOGGER.info(
                "RTest HDR enabled: linear scRGB swapchain ({} / format {}); 3D is narrowed to BT.709",
                COLOR_SPACE_SCRGB, FORMAT);
            case SDR -> {
                if (requestedSafely()) {
                    LOGGER.warn("RTest HDR requested, but the surface has no supported float color space; using SDR");
                }
            }
        }
    }

    /**
     * {@code requested()} reads the mod config, which is unavailable outside a loaded game. Only the
     * diagnostic branch needs it, so an unloaded config degrades to "not requested" instead of
     * throwing.
     */
    private static boolean requestedSafely() {
        try {
            return requested();
        } catch (RuntimeException notLoaded) {
            return false;
        }
    }

    /** Logs what the surface actually offers, so an unexpected SDR fallback is diagnosable. */
    public static void logOfferedFormats(org.lwjgl.vulkan.VkSurfaceFormatKHR.Buffer formats) {
        if (formatsLogged || formats == null) {
            return;
        }
        formatsLogged = true;
        StringBuilder offered = new StringBuilder();
        for (int index = 0; index < formats.limit(); index++) {
            var format = formats.get(index);
            if (offered.length() > 0) {
                offered.append(", ");
            }
            offered.append(format.format()).append('/').append(format.colorSpace());
        }
        LOGGER.info("RTest surface offers format/colorSpace pairs: {}", offered);
    }

    private static boolean hasInstanceExtension(String name) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            if (VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null) != VK10.VK_SUCCESS) {
                return false;
            }
            VkExtensionProperties.Buffer properties = VkExtensionProperties.calloc(count.get(0), stack);
            if (VK10.vkEnumerateInstanceExtensionProperties((String) null, count, properties) != VK10.VK_SUCCESS) {
                return false;
            }
            for (int index = 0; index < properties.limit(); index++) {
                if (name.equals(properties.get(index).extensionNameString())) {
                    return true;
                }
            }
            return false;
        }
    }
}
