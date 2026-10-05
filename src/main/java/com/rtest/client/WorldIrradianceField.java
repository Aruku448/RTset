package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ABI and CPU reference for a world-space incident-radiance field. No camera state is stored.
 * GPU ownership: one worker invocation per probe, with immutable published read snapshots.
 * Sample Li must exclude the receiver's BSDF/albedo and obey the supplied sphere PDF.
 */
final class WorldIrradianceField {
    static final int HEADER_WORDS = 16, ROW_WORDS = 320;
    static final int SH_OFFSET = 4, DISTANCE_OFFSET = 31, DISTANCE_COUNT_OFFSET = 43;
    static final String GLSL;
    static {
        try (var stream = WorldIrradianceField.class.getResourceAsStream("/rtest/shaders/world_irradiance_field.glsl")) {
            if (stream == null) throw new IllegalStateException("Missing world irradiance shader");
            GLSL = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    record Grid(float x, float y, float z, float spacing, int nx, int ny, int nz,
                int generation, int minSamples, int maxAgeMs, float maxTraceDistance) {
        Grid {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(spacing) || spacing <= 0 || nx < 2 || ny < 2 || nz < 2
                    || generation == 0 || minSamples < 1 || maxAgeMs < 0
                    || !Float.isFinite(maxTraceDistance) || maxTraceDistance <= 0)
                throw new IllegalArgumentException("Invalid world irradiance grid");
            long count = (long) nx * ny * nz;
            if (count > 1_048_576L) throw new IllegalArgumentException("World irradiance grid exceeds memory budget");
        }
        int count() { return nx * ny * nz; }
        int bytes() { return Math.multiplyExact(HEADER_WORDS + Math.multiplyExact(count(), ROW_WORDS * 2), 4); }
        int index(int ix, int iy, int iz) {
            if (ix < 0 || ix >= nx || iy < 0 || iy >= ny || iz < 0 || iz >= nz)
                throw new IndexOutOfBoundsException("Probe coordinate outside grid");
            return ix + nx * (iy + ny * iz);
        }
        float[] position(int index) {
            if(index < 0 || index >= count()) throw new IndexOutOfBoundsException("Probe index");
            return new float[]{x + (index % nx) * spacing, y + ((index / nx) % ny) * spacing,
                    z + (index / (nx * ny)) * spacing};
        }
        /** Fully zero-initialized payload: no synthetic lighting is published as an RT observation. */
        ByteBuffer initialize(int clockMs) {
            ByteBuffer words = ByteBuffer.allocateDirect(bytes()).order(ByteOrder.nativeOrder());
            words.put(0,initializeHeader(clockMs),0,HEADER_WORDS*4);
            return words;
        }
        ByteBuffer initializeHeader(int clockMs) {
            ByteBuffer words = ByteBuffer.allocate(HEADER_WORDS*4).order(ByteOrder.nativeOrder());
            words.putFloat(0,x).putFloat(4,y).putFloat(8,z).putFloat(12,spacing);
            words.putInt(16,nx).putInt(20,ny).putInt(24,nz).putInt(28,generation);
            words.putInt(32,minSamples).putInt(36,maxAgeMs).putInt(40,clockMs);
            words.putFloat(44,maxTraceDistance).putInt(48,1);
            words.putInt(52,HEADER_WORDS).putInt(56,HEADER_WORDS + count()*ROW_WORDS).putInt(60,0);
            return words;
        }
    }
    static double[] basis(double x, double y, double z) {
        return new double[]{0.28209479177387814, 0.4886025119029199*y,
            0.4886025119029199*z, 0.4886025119029199*x,
            1.0925484305920792*x*y, 1.0925484305920792*y*z,
            0.31539156525252005*(3*z*z-1), 1.0925484305920792*x*z,
            0.5462742152960396*(x*x-y*y)};
    }
    static double convolution(int bandIndex) {
        return bandIndex == 0 ? Math.PI : bandIndex < 4 ? 2*Math.PI/3 : Math.PI/4;
    }
    /** Reference projection; GPU worker uses a running mean, not a sum of already weighted surface colors. */
    static double[][] project(double[][] directions, double[][] radiance, double[] pdfs) {
        if (directions.length == 0 || radiance.length != directions.length || pdfs.length != directions.length)
            throw new IllegalArgumentException("Mismatched samples");
        double[][] sh = new double[9][3];
        for(int i=0;i<directions.length;i++) {
            double[] d=directions[i], l=radiance[i];
            if(d.length!=3 || l.length!=3 || !Double.isFinite(pdfs[i]) || pdfs[i]<=0)
                throw new IllegalArgumentException("Invalid directional sample");
            double norm=d[0]*d[0]+d[1]*d[1]+d[2]*d[2];
            if(!Double.isFinite(norm) || Math.abs(norm-1)>1e-5) throw new IllegalArgumentException("Direction must be unit length");
            double[] y=basis(d[0],d[1],d[2]);
            for(int c=0;c<3;c++) {
                if(!Double.isFinite(l[c]) || l[c]<0) throw new IllegalArgumentException("Invalid incident radiance");
                for(int k=0;k<9;k++) sh[k][c]+=l[c]*y[k]/pdfs[i]/directions.length;
            }
        }
        return sh;
    }
    static double[] irradiance(double[][] sh, double[] normal) {
        double[] y=basis(normal[0],normal[1],normal[2]), e=new double[3];
        for(int k=0;k<9;k++) for(int c=0;c<3;c++) e[c]+=sh[k][c]*y[k]*convolution(k);
        for(int c=0;c<3;c++) e[c]=Math.max(0,e[c]); // SH truncation may ring below zero.
        return e;
    }
    static double visibility(double distance, double mean, double secondMoment) {
        if(distance <= mean) return 1;
        double variance=Math.max(secondMoment-mean*mean,1e-4);
        double p=variance/(variance+(distance-mean)*(distance-mean));
        return p*p*p;
    }
    private WorldIrradianceField() { }
}
