package com.rtest.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.logging.LogUtils;

@FunctionalInterface
interface RayTracingPbrSampler {
    RayTracingPbrMaterials.Sample sample(TextureAtlasSprite sprite, long packedUv0, long packedUv1, long packedUv2);
}

/** Loads optional LabPBR companion textures and publishes stable GPU slots for them. */
final class RayTracingPbrMaterials implements RayTracingPbrSampler, AutoCloseable {
    private static final int INFO_WORDS = 10;
    // P1 deliberately uses one fixed host-visible SSBO. The metadata region is reserved up front
    // so appending a map never changes an existing pixel offset or requires a descriptor rewrite.
    private static final int MAX_GPU_MAPS = 8192;
    private static final int PIXEL_DATA_START_WORD = 1 + MAX_GPU_MAPS * INFO_WORDS;
    private static final int GPU_BUFFER_BYTES = 64 * 1024 * 1024;
    private static final int GPU_BUFFER_WORDS = GPU_BUFFER_BYTES / Integer.BYTES;
    private static final Sample DEFAULT = new Sample(0.04F, 0.0F, 0.88F, 0.0F, 0.0F, 1.0F,
        false, false, 0.0F, false, 0.0F, 1.0F, 0);
    private final ResourceManager resourceManager;
    private final Map<Identifier, Optional<PbrMap>> cache = new HashMap<>();
    private final Map<Identifier, Integer> slotByIdentifier = new HashMap<>();
    private final List<PbrMap> maps = new ArrayList<>();
    private int nextPixelWord = PIXEL_DATA_START_WORD;
    private int[] packedSnapshot;
    private boolean closed;
    private boolean reportedCapacityFallback;

    RayTracingPbrMaterials(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    @Override
    public synchronized Sample sample(TextureAtlasSprite sprite, long packedUv0, long packedUv1, long packedUv2) {
        if (this.closed || sprite == null) {
            return DEFAULT;
        }
        Identifier identifier = sprite.contents().name();
        PbrMap map = cache.computeIfAbsent(
            identifier,
            id -> Optional.ofNullable(load(id, sprite))
        ).orElse(null);
        if (map == null) {
            return DEFAULT;
        }
        float u = (localCoordinate(UVPair.unpackU(packedUv0), sprite.getU0(), sprite.getU1())
            + localCoordinate(UVPair.unpackU(packedUv1), sprite.getU0(), sprite.getU1())
            + localCoordinate(UVPair.unpackU(packedUv2), sprite.getU0(), sprite.getU1())) / 3.0F;
        float v = (localCoordinate(UVPair.unpackV(packedUv0), sprite.getV0(), sprite.getV1())
            + localCoordinate(UVPair.unpackV(packedUv1), sprite.getV0(), sprite.getV1())
            + localCoordinate(UVPair.unpackV(packedUv2), sprite.getV0(), sprite.getV1())) / 3.0F;
        Sample centroid = map.sample(u, v);
        // The light tree consumes one constant emission per triangle, so a centroid texel that
        // falls outside a small emissive region would silently remove the emitter and leave the
        // block glowing without lighting its surroundings. Keep the strongest authored emission
        // over the triangle corners as well.
        Sample corner = sampleCorner(map, sprite, packedUv0);
        if (corner.hasEmission()
            && (!centroid.hasEmission() || corner.emission() > centroid.emission())) {
            centroid = withEmission(centroid, corner.emission());
        }
        corner = sampleCorner(map, sprite, packedUv1);
        if (corner.hasEmission()
            && (!centroid.hasEmission() || corner.emission() > centroid.emission())) {
            centroid = withEmission(centroid, corner.emission());
        }
        corner = sampleCorner(map, sprite, packedUv2);
        if (corner.hasEmission()
            && (!centroid.hasEmission() || corner.emission() > centroid.emission())) {
            centroid = withEmission(centroid, corner.emission());
        }
        return centroid;
    }

    private Sample sampleCorner(PbrMap map, TextureAtlasSprite sprite, long packedUv) {
        return map.sample(
            localCoordinate(UVPair.unpackU(packedUv), sprite.getU0(), sprite.getU1()),
            localCoordinate(UVPair.unpackV(packedUv), sprite.getV0(), sprite.getV1()));
    }

    private static Sample withEmission(Sample sample, float emission) {
        return new Sample(sample.reflectivity(), sample.metallic(), sample.roughness(),
            sample.normalX(), sample.normalY(), sample.normalZ(), sample.hasNormal(),
            sample.hasSpecular(), emission, true, sample.porosity(), sample.textureAo(),
            sample.mapIndex());
    }

    static Sample defaultSample() {
        return DEFAULT;
    }

    static float resolveEmission(float fallbackEmission, float pbrEmission,
                                 boolean pbrControlsEmission) {
        return pbrControlsEmission ? Math.max(pbrEmission, 0.0F) : fallbackEmission;
    }

    /**
     * Stores the radiance scale used by the light tree. An authored emission texture is a
     * per-texel mask; it must not replace a block light's calibrated radiance with a 0..1 mask
     * value or the CPU tree will almost never select torches and lanterns among other lights.
     */
    static float resolveLightTreeEmission(float blockEmission, float pbrEmission,
                                          boolean pbrControlsEmission) {
        if (blockEmission > 0.0F) {
            return blockEmission;
        }
        return pbrControlsEmission ? Math.max(pbrEmission, 0.0F) : blockEmission;
    }

    /** The fixed allocation size used by the Vulkan pass; binding 5 remains unchanged. */
    int gpuBufferSizeBytes() {
        return GPU_BUFFER_BYTES;
    }

    static int metadataOffsetForSlot(int mapIndex) {
        if (mapIndex < 1 || mapIndex > MAX_GPU_MAPS) {
            throw new IllegalArgumentException("PBR map slot is outside the fixed GPU range: " + mapIndex);
        }
        return 1 + (mapIndex - 1) * INFO_WORDS;
    }

    static int pixelDataStartWord() {
        return PIXEL_DATA_START_WORD;
    }

    synchronized int loadedMapCount() {
        return this.maps.size();
    }

    /**
     * Returns only newly appended map writes. Existing maps are never repacked. The render pass
     * applies these writes to the already-bound host-visible SSBO and publishes the header last.
     */
    synchronized List<GpuUpdate> gpuUpdatesAfter(int currentMapCount) {
        if (currentMapCount < 0 || currentMapCount > this.maps.size()) {
            throw new IllegalArgumentException("Invalid uploaded PBR map count: " + currentMapCount);
        }
        if (currentMapCount == this.maps.size()) {
            return List.of();
        }
        List<GpuUpdate> updates = new ArrayList<>(this.maps.size() - currentMapCount);
        for (int index = currentMapCount; index < this.maps.size(); index++) {
            updates.add(this.maps.get(index).gpuUpdate());
        }
        return List.copyOf(updates);
    }

    /** Packs the initial scene payload once. Dynamic updates use {@link #gpuUpdatesAfter(int)}. */
    synchronized int[] packedData() {
        if (this.packedSnapshot != null) {
            return this.packedSnapshot;
        }
        int[] packed = new int[this.nextPixelWord];
        packed[0] = this.maps.size();
        for (PbrMap map : this.maps) {
            int info = metadataOffsetForSlot(map.index);
            packed[info] = map.normalOffset;
            packed[info + 1] = map.specularOffset;
            packed[info + 2] = map.normal == null ? 0 : map.normal.getWidth();
            packed[info + 3] = map.normal == null ? 0 : map.normal.getHeight();
            packed[info + 4] = map.specular == null ? 0 : map.specular.getWidth();
            packed[info + 5] = map.specular == null ? 0 : map.specular.getHeight();
            packed[info + 6] = Float.floatToIntBits(map.atlasU0);
            packed[info + 7] = Float.floatToIntBits(map.atlasU1);
            packed[info + 8] = Float.floatToIntBits(map.atlasV0);
            packed[info + 9] = Float.floatToIntBits(map.atlasV1);
            copyImage(packed, map.normalOffset, map.normal);
            copyImage(packed, map.specularOffset, map.specular);
        }
        this.packedSnapshot = packed;
        return packed;
    }

    private static void copyImage(int[] destination, int offset, NativeImage image) {
        if (image == null) {
            return;
        }
        int cursor = offset;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                destination[cursor++] = image.getPixel(x, y);
            }
        }
    }

    private static int imageEnd(int offset, NativeImage image) {
        if (image == null) {
            return offset;
        }
        long words = (long)image.getWidth() * image.getHeight();
        long end = (long)offset + words;
        if (end > GPU_BUFFER_WORDS) {
            throw new IllegalStateException("PBR companion textures exceed the fixed GPU buffer");
        }
        return (int)end;
    }

    private static int[] imagePixels(NativeImage image) {
        if (image == null) {
            return null;
        }
        int[] pixels = new int[Math.multiplyExact(image.getWidth(), image.getHeight())];
        int index = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[index++] = image.getPixel(x, y);
            }
        }
        return pixels;
    }

    private static float localCoordinate(float coordinate, float min, float max) {
        if (max <= min) {
            return 0.0F;
        }
        return Math.clamp((coordinate - min) / (max - min), 0.0F, 1.0F);
    }

    private PbrMap load(Identifier baseName, TextureAtlasSprite sprite) {
        if (this.closed) {
            return null;
        }
        Integer existingSlot = this.slotByIdentifier.get(baseName);
        if (existingSlot != null) {
            return this.maps.get(existingSlot - 1);
        }
        NativeImage normal = null;
        NativeImage specular = null;
        PbrMap map = null;
        boolean published = false;
        int previousPixelWord = this.nextPixelWord;
        try {
            normal = loadImage(companion(baseName, "_n"));
            specular = loadImage(companion(baseName, "_s"));
            if (normal == null && specular == null) {
                return null;
            }
            int index = this.maps.size() + 1;
            if (index > MAX_GPU_MAPS) {
                return rejectCapacity(baseName, normal, specular,
                    "PBR companion texture slot capacity was exhausted");
            }
            int normalOffset = normal == null ? -1 : this.nextPixelWord;
            int next;
            try {
                next = imageEnd(this.nextPixelWord, normal);
                int specularOffset = specular == null ? -1 : next;
                next = imageEnd(next, specular);
                map = new PbrMap(
                    index,
                    normal,
                    specular,
                    normalOffset,
                    specularOffset,
                    sprite.getU0(),
                    sprite.getU1(),
                    sprite.getV0(),
                    sprite.getV1()
                );
            } catch (IllegalStateException capacityFailure) {
                return rejectCapacity(baseName, normal, specular,
                    "PBR companion texture byte capacity was exhausted");
            }
            this.nextPixelWord = next;
            this.maps.add(map);
            this.slotByIdentifier.put(baseName, index);
            published = true;
            this.packedSnapshot = null;
            return map;
        } catch (Throwable throwable) {
            this.nextPixelWord = previousPixelWord;
            // A failed image read or list growth must not strand the NativeImage pair outside the
            // cache. Remove a partially published map before releasing its images.
            if (published) {
                this.maps.remove(map);
                this.slotByIdentifier.remove(baseName);
            }
            if (map != null) {
                map.close();
            } else {
                if (normal != null) {
                    normal.close();
                }
                if (specular != null) {
                    specular.close();
                }
            }
            throw throwable;
        }
    }

    private PbrMap rejectCapacity(Identifier baseName, NativeImage normal, NativeImage specular,
                                   String reason) {
        if (!this.reportedCapacityFallback) {
            this.reportedCapacityFallback = true;
            LogUtils.getLogger().warn("RTest using default PBR for {}: {}", baseName, reason);
        }
        if (normal != null) normal.close();
        if (specular != null) specular.close();
        return null;
    }

    private Identifier companion(Identifier baseName, String suffix) {
        return Identifier.fromNamespaceAndPath(
            baseName.getNamespace(),
            "textures/" + baseName.getPath() + suffix + ".png"
        );
    }

    private NativeImage loadImage(Identifier location) {
        try {
            Optional<Resource> resource = resourceManager.getResource(location);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream input = resource.get().open()) {
                return NativeImage.read(input);
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (PbrMap map : this.maps) {
            try {
                map.close();
            } catch (RuntimeException ignored) {
                // Continue releasing the remaining NativeImage pairs during world teardown.
            }
        }
        this.maps.clear();
        this.cache.clear();
        this.slotByIdentifier.clear();
        this.nextPixelWord = PIXEL_DATA_START_WORD;
        this.packedSnapshot = null;
    }

    record GpuUpdate(
        int mapIndex,
        int metadataOffset,
        int[] metadata,
        int normalOffset,
        int[] normalPixels,
        int specularOffset,
        int[] specularPixels
    ) {}

    record Sample(
        float reflectivity,
        float metallic,
        float roughness,
        float normalX,
        float normalY,
        float normalZ,
        boolean hasNormal,
        boolean hasSpecular,
        float emission,
        boolean hasEmission,
        float porosity,
        float textureAo,
        int mapIndex
    ) {
        Sample(float reflectivity, float metallic, float roughness,
               float normalX, float normalY, float normalZ,
               boolean hasNormal, boolean hasSpecular,
               float emission, boolean hasEmission, int mapIndex) {
            this(reflectivity, metallic, roughness, normalX, normalY, normalZ,
                hasNormal, hasSpecular, emission, hasEmission, 0.0F, 1.0F, mapIndex);
        }

        // Keep source compatibility for callers that only supplied the pre-emission fields.
        Sample(float reflectivity, float metallic, float roughness,
               float normalX, float normalY, float normalZ,
               boolean hasNormal, boolean hasSpecular, int mapIndex) {
            this(reflectivity, metallic, roughness, normalX, normalY, normalZ,
                hasNormal, hasSpecular, 0.0F, false, 0.0F, 1.0F, mapIndex);
        }
    }

    private static final class PbrMap implements AutoCloseable {
        private final int index;
        private final NativeImage normal;
        private final NativeImage specular;
        private final int normalOffset;
        private final int specularOffset;
        private final float atlasU0;
        private final float atlasU1;
        private final float atlasV0;
        private final float atlasV1;

        private PbrMap(int index, NativeImage normal, NativeImage specular,
                       int normalOffset, int specularOffset,
                       float atlasU0, float atlasU1, float atlasV0, float atlasV1) {
            this.index = index;
            this.normal = normal;
            this.specular = specular;
            this.normalOffset = normalOffset;
            this.specularOffset = specularOffset;
            this.atlasU0 = atlasU0;
            this.atlasU1 = atlasU1;
            this.atlasV0 = atlasV0;
            this.atlasV1 = atlasV1;
        }

        private GpuUpdate gpuUpdate() {
            int info = metadataOffsetForSlot(this.index);
            return new GpuUpdate(
                this.index,
                info,
                new int[] {
                    this.normalOffset,
                    this.specularOffset,
                    this.normal == null ? 0 : this.normal.getWidth(),
                    this.normal == null ? 0 : this.normal.getHeight(),
                    this.specular == null ? 0 : this.specular.getWidth(),
                    this.specular == null ? 0 : this.specular.getHeight(),
                    Float.floatToIntBits(this.atlasU0),
                    Float.floatToIntBits(this.atlasU1),
                    Float.floatToIntBits(this.atlasV0),
                    Float.floatToIntBits(this.atlasV1)
                },
                this.normalOffset,
                imagePixels(this.normal),
                this.specularOffset,
                imagePixels(this.specular)
            );
        }

        private Sample sample(float u, float v) {
            float reflectivity = DEFAULT.reflectivity();
            float metallic = DEFAULT.metallic();
            float roughness = DEFAULT.roughness();
            float normalX = DEFAULT.normalX();
            float normalY = DEFAULT.normalY();
            float normalZ = DEFAULT.normalZ();
            boolean hasNormal = this.normal != null;
            boolean hasSpecular = this.specular != null;
            boolean hasEmission = false;
            float emission = 0.0F;
            float porosity = 0.0F;
            float textureAo = 1.0F;
            if (this.specular != null) {
                int pixel = pixel(this.specular, u, v);
                int redByte = (pixel >> 16) & 0xff;
                int greenByte = (pixel >> 8) & 0xff;
                int blueByte = pixel & 0xff;
                int alpha = (pixel >>> 24) & 0xff;
                int format = RayTracingClientConfig.INSTANCE.pbrFormatCode();
                if (format == 2) {
                    roughness = blueByte / 255.0F;
                    metallic = redByte / 255.0F;
                    emission = greenByte / 255.0F;
                    hasEmission = authoredEmissionEnabled();
                } else {
                    roughness = 1.0F - redByte / 255.0F;
                    metallic = greenByte / 255.0F;
                    if (format == 0) {
                        porosity = blueByte / 255.0F;
                        emission = decodeLabPbrEmission(alpha);
                        // A LabPBR _s texture is a complete per-texel emission mask: 0 and 255
                        // are non-emissive, while 1..254 carry positive emission.  Keep the
                        // control flag true even when this texel decodes to zero so the block's
                        // material-level fallback cannot light its metal frame or housing.
                        hasEmission = authoredEmissionEnabled();
                    } else {
                        emission = blueByte / 255.0F;
                        hasEmission = authoredEmissionEnabled();
                    }
                }
                reflectivity = predefinedReflectivity(greenByte, metallic);
            }
            if (this.normal != null) {
                int pixel = pixel(this.normal, u, v);
                normalX = ((pixel >> 16) & 0xff) / 127.5F - 1.0F;
                normalY = ((pixel >> 8) & 0xff) / 127.5F - 1.0F;
                normalZ = isLabPbr()
                    ? (float)Math.sqrt(Math.max(1.0F - normalX * normalX - normalY * normalY, 0.0F))
                    : (pixel & 0xff) / 127.5F - 1.0F;
                if (isLabPbr() && RayTracingClientConfig.INSTANCE.pbrTextureAoEnabled.get()) {
                    textureAo = (pixel & 0xff) / 255.0F;
                }
                float normalStrength = RayTracingClientConfig.INSTANCE.pbrNormalStrength.get().floatValue();
                normalX *= normalStrength;
                normalY *= normalStrength;
                if (isLabPbr()) {
                    normalZ = (float)Math.sqrt(Math.max(1.0F - normalX * normalX - normalY * normalY, 0.0F));
                }
                float length = (float)Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                if (length > 1.0E-5F) {
                    normalX /= length;
                    normalY /= length;
                    normalZ /= length;
                }
            }
            return new Sample(reflectivity, metallic, roughness, normalX, normalY, normalZ,
                hasNormal, hasSpecular, emission, hasEmission, porosity, textureAo, this.index);
        }

        private static boolean isLabPbr() {
            return RayTracingClientConfig.INSTANCE.pbrFormatCode() == 0;
        }

        private static boolean authoredEmissionEnabled() {
            return (RayTracingClientConfig.INSTANCE.pbrFeatureMask() & 8) != 0;
        }

        private static float predefinedReflectivity(int metalByte, float metallic) {
            if (isLabPbr() && RayTracingClientConfig.INSTANCE.pbrPredefinedMetalsEnabled.get()
                && metalByte >= 230 && metalByte <= 237) {
                return switch (metalByte) {
                    case 230 -> 0.576F;
                    case 231 -> 0.731F;
                    case 232 -> 0.819F;
                    case 233 -> 0.492F;
                    case 234 -> 0.591F;
                    case 235 -> 0.838F;
                    case 236 -> 0.631F;
                    case 237 -> 0.894F;
                    default -> 0.04F + 0.96F * metallic;
                };
            }
            return 0.04F + 0.96F * metallic;
        }

        private static int pixel(NativeImage image, float u, float v) {
            int x = Math.clamp((int)(u * (image.getWidth() - 1) + 0.5F), 0, image.getWidth() - 1);
            int y = Math.clamp((int)(v * (image.getHeight() - 1) + 0.5F), 0, image.getHeight() - 1);
            return image.getPixel(x, y);
        }

        @Override
        public void close() {
            try {
                if (this.normal != null) {
                    this.normal.close();
                }
            } finally {
                if (this.specular != null) {
                    this.specular.close();
                }
            }
        }
    }

    /** Decodes the LabPBR _s alpha byte; 255 means that emission was not authored. */
    static float decodeLabPbrEmission(int alpha) {
        if (alpha < 0 || alpha > 255) {
            throw new IllegalArgumentException("LabPBR emission must be an unsigned byte");
        }
        return alpha < 255 ? alpha / 254.0F : 0.0F;
    }
}
