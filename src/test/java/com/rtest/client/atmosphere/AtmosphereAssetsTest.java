package com.rtest.client.atmosphere;

import com.rtest.client.RayTracingAtmosphere;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Reads actual classpath assets and their SPIR-V descriptor ABI; no GPU execution claimed. */
public final class AtmosphereAssetsTest {
    public static void main(String[] args) throws Exception {
        byte[] medium = AtmosphereMedium.load(100);
        check(medium.length == 199_584, "physical medium byte size");
        check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(medium))
            .equals("be1ae66c600c6df21ea730cd24b10bb88b9f6dfa00200539a4cc65420c7aefb5"), "pinned physical medium bytes");
        ByteBuffer clear = ByteBuffer.wrap(AtmosphereMedium.load(0)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dense = ByteBuffer.wrap(AtmosphereMedium.load(200)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer baseline = ByteBuffer.wrap(medium).order(ByteOrder.LITTLE_ENDIAN);
        // Pinned exported asset ABI: profiles start at vec4 40, seven vec4s per height;
        // source samples start at vec4 12234, six per height. Rayleigh is never scaled.
        for (int height = 0; height < 50; height++) {
            int profile = (40 + height * 7) * 16;
            for (int lane = 0; lane < 4; lane++) {
                check(clear.getInt(profile + 32 + lane * 4) == baseline.getInt(profile + 32 + lane * 4),
                    "Rayleigh profile unchanged by aerosol density");
            }
            for (int component = 0; component < 16; component++) {
                int address = profile + 48 + component * 4;
                check(clear.getFloat(address) == 0.0F, "zero-density aerosol profile");
                check(dense.getFloat(address) == baseline.getFloat(address) * 2.0F,
                    "twofold aerosol profile density");
            }
        }
        for (int height = 0; height < 40; height++) {
            int source = (12234 + height * 6) * 16;
            for (int lane = 0; lane < 4; lane++) {
                check(clear.getInt(source + lane * 4) == baseline.getInt(source + lane * 4),
                    "Rayleigh source unchanged by aerosol density");
                check(clear.getInt(source + 80 + lane * 4) == clear.getInt(source + lane * 4),
                    "zero-density summed source is pure Rayleigh");
            }
        }
        ByteBuffer coefficients = ByteBuffer.wrap(medium).order(ByteOrder.LITTLE_ENDIAN);
        while (coefficients.hasRemaining()) check(Float.isFinite(coefficients.getFloat()), "finite coefficients");
        var plan = AtmospherePrecomputation.plan();
        check(plan.size() == 290 && AtmospherePrecomputation.finalBank() == 0, "full solver plan");
        Set<Integer> unused = Set.of(5, 6, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
        for (String kernel : new String[] {"transmittance", "directions", "incident", "moments", "multi_scattering", "ground", "sky"}) {
            ByteBuffer spv = ByteBuffer.wrap(RayTracingAtmosphere.readKernel(kernel)).order(ByteOrder.LITTLE_ENDIAN);
            Map<Integer, Integer> bindingById = new HashMap<>();
            Map<Integer, Integer> setById = new HashMap<>();
            for (int word = 5; word < spv.capacity() / 4;) {
                int header = spv.getInt(word * 4), count = header >>> 16, opcode = header & 0xffff;
                check(count > 0 && word + count <= spv.capacity() / 4, "valid instruction boundary");
                if (opcode == 71 && count >= 4) { // OpDecorate: DescriptorSet=34, Binding=33.
                    int id = spv.getInt((word + 1) * 4), decoration = spv.getInt((word + 2) * 4);
                    if (decoration == 33) bindingById.put(id, spv.getInt((word + 3) * 4));
                    if (decoration == 34) setById.put(id, spv.getInt((word + 3) * 4));
                }
                word += count;
            }
            for (var entry : bindingById.entrySet()) {
                int binding = entry.getValue();
                check(setById.getOrDefault(entry.getKey(), -1) == 0, "descriptor set zero");
                check(binding >= 0 && binding < 33 && !unused.contains(binding), "omitted binding is absent in " + kernel);
                if (kernel.equals("sky")) check(binding < 25, "final set must not reference solver scratch/outputs");
            }
            check(!bindingById.isEmpty(), "kernel descriptor reflection");
        }
        boolean rejected = false;
        try { RayTracingAtmosphere.readKernel("aerial"); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "only seven supported kernels");
        System.out.println("Atmosphere classpath assets and ABI passed (CPU only)");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
