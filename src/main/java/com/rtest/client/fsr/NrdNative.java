package com.rtest.client.fsr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Stable Java binding for Prime's narrow NRD Core bridge.
 *
 * <p>The bundled native library contains NRD's API-independent scheduler and SPIR-V only. It never
 * receives a Vulkan handle: resource ownership, command recording and synchronization remain on
 * the Java side. The private ABI consists exclusively of fixed-width integers, pointers and byte
 * arrays and is versioned independently from NRD's C++ structures.
 */
public final class NrdNative {
    static final int ABI_VERSION = 8;
    static final int EXPECTED_NRD_VERSION = 4 << 24 | 17 << 16 | 3;

    public static final int DESCRIPTOR_TEXTURE = 0;
    public static final int DESCRIPTOR_STORAGE_TEXTURE = 1;

    public static final int RESOURCE_IN_MV = 0;
    public static final int RESOURCE_IN_NORMAL_ROUGHNESS = 1;
    public static final int RESOURCE_IN_VIEWZ = 2;
    public static final int RESOURCE_IN_DIFF_RADIANCE_HITDIST = 6;
    public static final int RESOURCE_IN_SPEC_RADIANCE_HITDIST = 7;
    public static final int RESOURCE_OUT_DIFF_RADIANCE_HITDIST = 18;
    public static final int RESOURCE_OUT_SPEC_RADIANCE_HITDIST = 19;
    public static final int RESOURCE_OUT_VALIDATION = 29;
    public static final int RESOURCE_TRANSIENT_POOL = 30;
    public static final int RESOURCE_PERMANENT_POOL = 31;

    private static final int CREATE_DESCRIPTION_SIZE = 12;
    private static final int DESCRIPTION_SIZE = 136;
    private static final int PIPELINE_SIZE = 288;
    private static final int PIPELINE_RANGE_SIZE = 8;
    private static final int TEXTURE_INFO_SIZE = 8;
    private static final int FRAME_SETTINGS_SIZE = 308;
    private static final int TUNING_SIZE = 84;
    private static final int DISPATCH_SIZE = 48;
    private static final int RESOURCE_SIZE = 16;
    private static final int DISPATCH_LIST_SIZE = 16;
    private final SharedLibrary library;
    private final long createFunction;
    private final long getDescriptionFunction;
    private final long setFrameSettingsFunction;
    private final long setDenoiserSettingsFunction;
    private final long getDispatchesFunction;
    private final long destroyFunction;

    private NrdNative() {
        this.library = loadLibrary();
        try {
            long getAbiVersionFunction = requireFunction(this.library, "primeNrdGetAbiVersion");
            this.createFunction = requireFunction(this.library, "primeNrdCreate");
            this.getDescriptionFunction = requireFunction(this.library, "primeNrdGetDescription");
            this.setFrameSettingsFunction = requireFunction(this.library, "primeNrdSetFrameSettings");
            this.setDenoiserSettingsFunction = requireFunction(this.library, "primeNrdSetDenoiserSettings");
            this.getDispatchesFunction = requireFunction(this.library, "primeNrdGetDispatches");
            this.destroyFunction = requireFunction(this.library, "primeNrdDestroy");
            int abiVersion = JNI.invokeI(getAbiVersionFunction);
            if (abiVersion != ABI_VERSION) {
                throw new IllegalStateException(
                        "Prime NRD bridge ABI mismatch: expected " + ABI_VERSION + ", found " + abiVersion);
            }
        } catch (RuntimeException | Error exception) {
            // A malformed/incompatible library must not remain dlopen'ed after Holder fails.
            this.library.close();
            throw exception;
        }
    }

    public static Instance create(int width, int height) {
        return create(width, height, DenoiserKind.DIFFUSE_SPECULAR);
    }

    static Instance create(int width, int height, DenoiserKind denoiserKind) {
        if (width <= 0 || height <= 0 || width > 65_535 || height > 65_535) {
            throw new IllegalArgumentException("NRD dimensions must be in [1, 65535]");
        }
        try {
            return Holder.INSTANCE.createInstance(width, height, denoiserKind);
        } catch (LinkageError error) {
            // Static native loading failures are Errors by default and would bypass Prime's
            // RuntimeException-based vanilla fallback. Normalize them at this private boundary.
            throw new IllegalStateException("Unable to load the bundled NRD native library", error);
        }
    }

    private Instance createInstance(int width, int height, DenoiserKind denoiserKind) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer createDesc = stack.calloc(CREATE_DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
            createDesc.putInt(0, width);
            createDesc.putInt(4, height);
            createDesc.putInt(8, denoiserKind.nativeValue);
            ByteBuffer output = stack.calloc(Long.BYTES).order(ByteOrder.nativeOrder());
            checkResult(
                    JNI.invokePPI(
                            MemoryUtil.memAddress(createDesc),
                            MemoryUtil.memAddress(output),
                            this.createFunction),
                    "create NRD instance");
            long handle = output.getLong(0);
            if (handle == MemoryUtil.NULL) {
                throw new IllegalStateException("NRD returned a null instance");
            }
            try {
                Description description = this.readDescription(stack, handle);
                return new Instance(this, handle, description);
            } catch (RuntimeException | Error exception) {
                JNI.invokePV(handle, this.destroyFunction);
                throw exception;
            }
        }
    }

    enum DenoiserKind {
        DIFFUSE_SPECULAR(0),
        TRANSPARENT_REFLECTION(1),
        TRANSPARENT_TRANSMISSION(2);

        private final int nativeValue;

        DenoiserKind(int nativeValue) {
            this.nativeValue = nativeValue;
        }
    }

    static boolean isSupportedPlatform() {
        return librarySpec(
                System.getProperty("os.name", ""), System.getProperty("os.arch", "")) != null;
    }

    static boolean isSupportedPlatform(String osName, String architecture) {
        return librarySpec(osName, architecture) != null;
    }

    static LibrarySpec librarySpec(String osName, String architecture) {
        String normalizedOsName = osName.toLowerCase(Locale.ROOT);
        String normalizedArchitecture = architecture.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        boolean x64 = normalizedArchitecture.equals("amd64") || normalizedArchitecture.equals("x8664");
        if (!x64) {
            return null;
        }
        if (normalizedOsName.startsWith("linux")) {
            return new LibrarySpec(
                    "linux-x86_64",
                    "/rtest/natives/linux-x86_64/libprime_nrd.so",
                    "libprime_nrd.so");
        }
        if (normalizedOsName.startsWith("windows")) {
            return new LibrarySpec(
                    "windows-x86_64",
                    "/rtest/natives/windows-x86_64/prime_nrd.dll",
                    "prime_nrd.dll");
        }
        return null;
    }

    record LibrarySpec(String platform, String resourcePath, String fileName) { }

    private Description readDescription(MemoryStack stack, long handle) {
        ByteBuffer output = stack.calloc(DESCRIPTION_SIZE).order(ByteOrder.nativeOrder());
        checkResult(
                JNI.invokePPI(handle, MemoryUtil.memAddress(output), this.getDescriptionFunction),
                "read NRD instance description");
        int abiVersion = output.getInt(0);
        int nrdVersion = output.getInt(4);
        if (abiVersion != ABI_VERSION || nrdVersion != EXPECTED_NRD_VERSION) {
            throw new IllegalStateException(
                    "Unsupported NRD native description: ABI "
                            + abiVersion
                            + ", NRD "
                            + formatVersion(nrdVersion));
        }

        int samplersNum = output.getInt(48);
        int pipelinesNum = output.getInt(52);
        int permanentPoolSize = output.getInt(64);
        int transientPoolSize = output.getInt(68);
        List<Integer> samplers = readIntegers(output.getLong(40), samplersNum);
        List<Pipeline> pipelines = readPipelines(output.getLong(56), pipelinesNum);
        List<TextureInfo> permanentPool = readTexturePool(output.getLong(72), permanentPoolSize);
        List<TextureInfo> transientPool = readTexturePool(output.getLong(80), transientPoolSize);
        String entryPoint = readFixedUtf8(MemoryUtil.memAddress(output) + 104L, 32);
        return new Description(
                nrdVersion,
                output.getInt(8),
                output.getInt(12),
                output.getInt(16),
                output.getInt(20),
                output.getInt(24),
                output.getInt(28),
                output.getInt(32),
                output.getInt(36),
                output.getInt(88),
                output.getInt(92),
                output.getInt(96),
                entryPoint,
                samplers,
                pipelines,
                permanentPool,
                transientPool);
    }

    private static List<Integer> readIntegers(long address, int count) {
        requireArray(address, count, Integer.BYTES, "NRD sampler array");
        ArrayList<Integer> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(MemoryUtil.memGetInt(address + (long) index * Integer.BYTES));
        }
        return List.copyOf(values);
    }

    private static List<Pipeline> readPipelines(long address, int count) {
        requireArray(address, count, PIPELINE_SIZE, "NRD pipeline array");
        ArrayList<Pipeline> pipelines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long pipelineAddress = address + (long) index * PIPELINE_SIZE;
            long spirvAddress = MemoryUtil.memGetLong(pipelineAddress);
            long spirvSize = MemoryUtil.memGetLong(pipelineAddress + 8L);
            if (spirvAddress == MemoryUtil.NULL || spirvSize <= 0L || spirvSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("NRD returned invalid SPIR-V bytecode");
            }
            byte[] spirv = new byte[(int) spirvSize];
            MemoryUtil.memByteBuffer(spirvAddress, spirv.length).get(spirv);
            long rangesAddress = MemoryUtil.memGetLong(pipelineAddress + 16L);
            int rangesNum = MemoryUtil.memGetInt(pipelineAddress + 24L);
            requireArray(rangesAddress, rangesNum, PIPELINE_RANGE_SIZE, "NRD pipeline ranges");
            ArrayList<PipelineRange> ranges = new ArrayList<>(rangesNum);
            for (int rangeIndex = 0; rangeIndex < rangesNum; rangeIndex++) {
                long rangeAddress = rangesAddress + (long) rangeIndex * PIPELINE_RANGE_SIZE;
                ranges.add(new PipelineRange(
                        MemoryUtil.memGetInt(rangeAddress),
                        MemoryUtil.memGetInt(rangeAddress + 4L)));
            }
            pipelines.add(new Pipeline(
                    spirv,
                    List.copyOf(ranges),
                    MemoryUtil.memGetInt(pipelineAddress + 28L) != 0,
                    readFixedUtf8(pipelineAddress + 32L, 256)));
        }
        return List.copyOf(pipelines);
    }

    private static List<TextureInfo> readTexturePool(long address, int count) {
        requireArray(address, count, TEXTURE_INFO_SIZE, "NRD texture pool");
        ArrayList<TextureInfo> textures = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long textureAddress = address + (long) index * TEXTURE_INFO_SIZE;
            textures.add(new TextureInfo(
                    MemoryUtil.memGetInt(textureAddress),
                    MemoryUtil.memGetInt(textureAddress + 4L)));
        }
        return List.copyOf(textures);
    }

    private static List<Dispatch> readDispatches(ByteBuffer output) {
        long address = output.getLong(0);
        int count = output.getInt(8);
        requireArray(address, count, DISPATCH_SIZE, "NRD dispatch array");
        ArrayList<Dispatch> dispatches = new ArrayList<>(count);
        for (int dispatchIndex = 0; dispatchIndex < count; dispatchIndex++) {
            long dispatchAddress = address + (long) dispatchIndex * DISPATCH_SIZE;
            long resourcesAddress = MemoryUtil.memGetLong(dispatchAddress + 8L);
            long constantAddress = MemoryUtil.memGetLong(dispatchAddress + 16L);
            int resourcesNum = MemoryUtil.memGetInt(dispatchAddress + 24L);
            int constantSize = MemoryUtil.memGetInt(dispatchAddress + 28L);
            requireArray(resourcesAddress, resourcesNum, RESOURCE_SIZE, "NRD dispatch resources");
            if (constantSize < 0 || (constantSize != 0 && constantAddress == MemoryUtil.NULL)) {
                throw new IllegalStateException("NRD returned invalid constant data");
            }
            ArrayList<Resource> resources = new ArrayList<>(resourcesNum);
            for (int resourceIndex = 0; resourceIndex < resourcesNum; resourceIndex++) {
                long resourceAddress = resourcesAddress + (long) resourceIndex * RESOURCE_SIZE;
                resources.add(new Resource(
                        MemoryUtil.memGetInt(resourceAddress),
                        MemoryUtil.memGetInt(resourceAddress + 4L),
                        MemoryUtil.memGetInt(resourceAddress + 8L)));
            }
            byte[] constants = new byte[constantSize];
            if (constantSize != 0) {
                MemoryUtil.memByteBuffer(constantAddress, constantSize).get(constants);
            }
            dispatches.add(new Dispatch(
                    MemoryUtil.memUTF8Safe(MemoryUtil.memGetLong(dispatchAddress)),
                    List.copyOf(resources),
                    constants,
                    MemoryUtil.memGetInt(dispatchAddress + 32L),
                    MemoryUtil.memGetInt(dispatchAddress + 36L),
                    MemoryUtil.memGetInt(dispatchAddress + 40L)));
        }
        return List.copyOf(dispatches);
    }

    private static void requireArray(long address, int count, int stride, String name) {
        if (count < 0 || count > 65_536 || (count != 0 && address == MemoryUtil.NULL)) {
            throw new IllegalStateException(name + " is invalid");
        }
        Math.multiplyExact(count, stride);
    }

    private static String readFixedUtf8(long address, int maximumLength) {
        int length = 0;
        while (length < maximumLength && MemoryUtil.memGetByte(address + length) != 0) {
            length++;
        }
        return MemoryUtil.memUTF8(address, length);
    }

    private static SharedLibrary loadLibrary() {
        LibrarySpec spec = librarySpec(
                System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        if (spec == null) {
            throw new IllegalStateException("The bundled NRD native library supports Linux or Windows x86-64 only");
        }
        byte[] libraryBytes;
        try (InputStream input = NrdNative.class.getResourceAsStream(spec.resourcePath())) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled NRD native library " + spec.resourcePath());
            }
            libraryBytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the bundled NRD native library", exception);
        }
        String digest = sha256(libraryBytes);
        Path directory = Path.of(System.getProperty("java.io.tmpdir"), "rtest-nrd", spec.platform(), digest);
        Path libraryPath = directory.resolve(spec.fileName());
        try {
            Files.createDirectories(directory);
            if (!Files.exists(libraryPath)) {
                Path temporary = directory.resolve("prime_nrd-" + ProcessHandle.current().pid() + ".tmp");
                try {
                    Files.write(temporary, libraryBytes);
                    try {
                        Files.move(temporary, libraryPath, StandardCopyOption.ATOMIC_MOVE);
                    } catch (FileAlreadyExistsException ignored) {
                        // Another client process published the same content-addressed DLL.
                    }
                } catch (FileAlreadyExistsException ignored) {
                    // Another thread in this process won the extraction race.
                } finally {
                    // Also remove a pid-specific temporary file when the write itself fails.
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to extract the bundled NRD native library", exception);
        }
        return APIUtil.apiCreateLibrary(libraryPath.toAbsolutePath().toString());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
    }

    private static long requireFunction(SharedLibrary library, String name) {
        long address = library.getFunctionAddress(name);
        if (address == MemoryUtil.NULL) {
            throw new IllegalStateException("The NRD native library is missing " + name);
        }
        return address;
    }

    private static void checkResult(int result, String operation) {
        if (result != 0) {
            throw new IllegalStateException(operation + " failed with native result " + result);
        }
    }

    private static String formatVersion(int packedVersion) {
        return ((packedVersion >>> 24) & 0xff)
                + "."
                + ((packedVersion >>> 16) & 0xff)
                + "."
                + (packedVersion & 0xffff);
    }

    public static final class Instance implements AutoCloseable {
        private final NrdNative nativeApi;
        private final Description description;
        private long handle;

        private Instance(NrdNative nativeApi, long handle, Description description) {
            this.nativeApi = nativeApi;
            this.handle = handle;
            this.description = description;
        }

        public Description description() {
            return this.description;
        }

        public void setFrameSettings(FrameSettings settings) {
            long instance = this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer input = stack.calloc(FRAME_SETTINGS_SIZE).order(ByteOrder.nativeOrder());
                putMatrix(input, 0, settings.viewToClip());
                putMatrix(input, 64, settings.viewToClipPrevious());
                putMatrix(input, 128, settings.worldToView());
                putMatrix(input, 192, settings.worldToViewPrevious());
                putVector2(input, 256, settings.cameraJitter());
                putVector2(input, 264, settings.cameraJitterPrevious());
                input.putInt(272, settings.width());
                input.putInt(276, settings.height());
                input.putInt(280, settings.previousWidth());
                input.putInt(284, settings.previousHeight());
                input.putInt(288, settings.frameIndex());
                input.putInt(292, settings.restart() ? 1 : 0);
                input.putFloat(296, settings.timeDeltaMilliseconds());
                input.putFloat(300, settings.denoisingRange());
                input.putInt(304, settings.enableValidation() ? 1 : 0);
                checkResult(
                        JNI.invokePPI(
                                instance,
                                MemoryUtil.memAddress(input),
                                this.nativeApi.setFrameSettingsFunction),
                        "set NRD frame settings");
            }
        }

        public void setDenoiserSettings(Tuning tuning) {
            long instance = this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer input = stack.calloc(TUNING_SIZE).order(ByteOrder.nativeOrder());
                input.putInt(0, tuning.hitDistanceReconstructionMode());
                input.putFloat(4, tuning.diffusePrepassBlurRadius());
                input.putFloat(8, tuning.specularPrepassBlurRadius());
                input.putFloat(12, tuning.minHitDistanceWeight());
                input.putFloat(16, tuning.minBlurRadius());
                input.putFloat(20, tuning.maxBlurRadius());
                input.putFloat(24, tuning.lobeAngleFraction());
                input.putFloat(28, tuning.roughnessFraction());
                input.putFloat(32, tuning.planeDistanceSensitivity());
                input.putFloat(36, tuning.fastHistoryClampingSigmaScale());
                input.putFloat(40, tuning.fireflySuppressorMinRelativeScale());
                input.putFloat(44, tuning.disocclusionThreshold());
                input.putInt(48, tuning.maxAccumulatedFrameNum());
                input.putInt(52, tuning.maxFastAccumulatedFrameNum());
                input.putInt(56, tuning.historyFixFrameNum());
                input.putInt(60, tuning.enableAntiFirefly() ? 1 : 0);
                input.putInt(64, tuning.usePrepassOnlyForSpecularMotionEstimation() ? 1 : 0);
                input.putInt(68, tuning.historyFixPixelStride());
                input.putFloat(72, tuning.convergenceScale());
                input.putFloat(76, tuning.convergenceBase());
                input.putFloat(80, tuning.convergencePercent());
                checkResult(
                        JNI.invokePPI(
                                instance,
                                MemoryUtil.memAddress(input),
                                this.nativeApi.setDenoiserSettingsFunction),
                        "set NRD denoiser settings");
            }
        }

        public List<Dispatch> getDispatches() {
            long instance = this.requireOpen();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer output = stack.calloc(DISPATCH_LIST_SIZE).order(ByteOrder.nativeOrder());
                checkResult(
                        JNI.invokePPI(
                                instance,
                                MemoryUtil.memAddress(output),
                                this.nativeApi.getDispatchesFunction),
                        "get NRD dispatches");
                return readDispatches(output);
            }
        }

        @Override
        public void close() {
            long instance = this.handle;
            if (instance != MemoryUtil.NULL) {
                this.handle = MemoryUtil.NULL;
                JNI.invokePV(instance, this.nativeApi.destroyFunction);
            }
        }

        private long requireOpen() {
            if (this.handle == MemoryUtil.NULL) {
                throw new IllegalStateException("NRD instance is closed");
            }
            return this.handle;
        }

        private static void putMatrix(ByteBuffer target, int offset, float[] matrix) {
            if (matrix.length != 16) {
                throw new IllegalArgumentException("NRD matrices must contain 16 floats");
            }
            for (int index = 0; index < matrix.length; index++) {
                target.putFloat(offset + index * Float.BYTES, matrix[index]);
            }
        }

        private static void putVector2(ByteBuffer target, int offset, float[] vector) {
            if (vector.length != 2) {
                throw new IllegalArgumentException("NRD camera jitter must contain two floats");
            }
            target.putFloat(offset, vector[0]);
            target.putFloat(offset + Float.BYTES, vector[1]);
        }
    }

    /**
     * Advanced REBLUR controls. The native bridge owns the ABI layout; this record keeps the
     * public Java side strongly typed and makes every value visible to the settings screen.
     */
    public record Tuning(
            int hitDistanceReconstructionMode,
            float diffusePrepassBlurRadius,
            float specularPrepassBlurRadius,
            float minHitDistanceWeight,
            float minBlurRadius,
            float maxBlurRadius,
            float lobeAngleFraction,
            float roughnessFraction,
            float planeDistanceSensitivity,
            float fastHistoryClampingSigmaScale,
            float fireflySuppressorMinRelativeScale,
            float disocclusionThreshold,
            int maxAccumulatedFrameNum,
            int maxFastAccumulatedFrameNum,
            int historyFixFrameNum,
            boolean enableAntiFirefly,
            boolean usePrepassOnlyForSpecularMotionEstimation,
            int historyFixPixelStride,
            float convergenceScale,
            float convergenceBase,
            float convergencePercent) {}

    public record Description(
            int nrdVersion,
            int samplerOffset,
            int textureOffset,
            int constantBufferOffset,
            int storageTextureOffset,
            int constantBufferRegisterIndex,
            int samplersBaseRegisterIndex,
            int resourcesBaseRegisterIndex,
            int constantBufferMaxDataSize,
            int setsMaxNum,
            int constantBufferAndSamplersSpaceIndex,
            int resourcesSpaceIndex,
            String shaderEntryPoint,
            List<Integer> samplers,
            List<Pipeline> pipelines,
            List<TextureInfo> permanentPool,
            List<TextureInfo> transientPool) {}

    public record Pipeline(
            byte[] spirv,
            List<PipelineRange> ranges,
            boolean hasConstantData,
            String identifier) {}

    public record PipelineRange(int descriptorType, int descriptorsNum) {}

    public record TextureInfo(int format, int downsampleFactor) {}

    public record Resource(int descriptorType, int resourceType, int indexInPool) {}

    public record Dispatch(
            String name,
            List<Resource> resources,
            byte[] constantData,
            int pipelineIndex,
            int gridWidth,
            int gridHeight) {}

    public record FrameSettings(
            float[] viewToClip,
            float[] viewToClipPrevious,
            float[] worldToView,
            float[] worldToViewPrevious,
            float[] cameraJitter,
            float[] cameraJitterPrevious,
            int width,
            int height,
            int previousWidth,
            int previousHeight,
            int frameIndex,
            boolean restart,
            float timeDeltaMilliseconds,
            float denoisingRange,
            boolean enableValidation) {}

    private static final class Holder {
        private static final NrdNative INSTANCE = new NrdNative();

        private Holder() {}
    }
}
