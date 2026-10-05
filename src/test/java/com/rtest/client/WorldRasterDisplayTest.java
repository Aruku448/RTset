package com.rtest.client;

import com.rtest.client.fsr.RtestFsrCamera;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import org.lwjgl.util.shaderc.Shaderc;

/** Projection is verified against camera-basis equations, not a second matrix implementation. */
public final class WorldRasterDisplayTest {
    public static void main(String[] args) throws Exception {
        var schedule=new WorldDisplaySchedule();
        long start=1_000_000_000L;
        if(!schedule.begin(true,false,start,50))throw new AssertionError("Initial world publication");
        schedule.submitted(start,50);
        for(int frame=1;frame<12;frame++)
            if(schedule.begin(true,false,start+frame*4_166_666L,50))throw new AssertionError("240 Hz display retraces world before deadline");
        if(!schedule.begin(true,false,start+50_000_000L,50))throw new AssertionError("World deadline");
        if(!schedule.begin(true,true,start+1,50))throw new AssertionError("Scene invalidation");
        if(schedule.begin(false,true,start,50))throw new AssertionError("Full RT uses separate schedule");
        if(!schedule.begin(true,false,start+1,50))throw new AssertionError("Mode reentry invalidates world clock");
        var random=new Random(14273);
        // Inverse-transpose normals must stay perpendicular to both transformed tangents.
        var normals=java.nio.FloatBuffer.allocate(3);
        for(int i=0;i<10000;i++) {
            double angle=random.nextDouble()*6.28;
            float c=(float)Math.cos(angle),s=(float)Math.sin(angle);
            float sx=.1f+random.nextFloat()*4,sy=.1f+random.nextFloat()*4,sz=.1f+random.nextFloat()*4;
            var t=new DynamicInstanceRegistry.Transform(c*sx,-s*sy,.3f*sz,0,s*sx,c*sy,.2f*sz,0,0,0,sz,0);
            WorldRasterDisplay.transformNormal(normals,0,t,1,1,1);
            float nx=normals.get(0),ny=normals.get(1),nz=normals.get(2);
            check(nx*nx+ny*ny+nz*nz,1);
            check(nx*(t.m00()-t.m01())+ny*(t.m10()-t.m11())+nz*(t.m20()-t.m21()),0);
            check(nx*(t.m01()-t.m02())+ny*(t.m11()-t.m12())+nz*(t.m21()-t.m22()),0);
        }
        for(int i=0;i<10000;i++) {
            double yaw=random.nextDouble()*Math.PI*2;
            float fx=(float)Math.sin(yaw),fz=(float)-Math.cos(yaw),rx=-fz,rz=fx;
            double ox=30000000+random.nextInt(100),oy=random.nextInt(300),oz=-30000000+random.nextInt(100);
            float cx=random.nextFloat()*16,cy=random.nextFloat()*16,cz=random.nextFloat()*16;
            var camera=new RtestFsrCamera(1.3f,1.7f,ox+cx,oy+cy,oz+cz,fx,0,fz,rx,0,rz,0,1,0);
            var matrix=ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
            WorldRasterDisplay.writeProjection(matrix,0,camera,ox,oy,oz);
            float[] point={cx+fx*20+rx*2,cy+3,cz+fz*20+rz*2,1};
            float[] clip=new float[4];
            for(int row=0;row<4;row++)for(int col=0;col<4;col++)clip[row]+=matrix.getFloat((col*4+row)*4)*point[col];
            check(clip[0],2*1.3);check(clip[1],3*1.7);check(clip[2],.05);check(clip[3],20);
            if(Math.abs(clip[2]/clip[3]-.0025)>1e-6)throw new AssertionError("Reversed Z contract");
            // Positive viewport Y matches RT image rows: a static camera yields zero.
            double rasterY=.5+.5*clip[1]/clip[3],previousY=.5+.5*clip[1]/clip[3];
            if(rasterY!=previousY)throw new AssertionError("UV motion sign");
            // The full RT ray generator maps image Y as +camera.up. The shared
            // FSR/presentation path must receive the same row for this world point.
            double rtImageY=.5+.5*(3*1.7/20);
            if(Math.abs(rasterY-rtImageY)>1e-6)
                throw new AssertionError("Raster world upside down relative to RT presentation: rasterY="+rasterY+", rtY="+rtImageY);
        }
        compile(WorldRasterShaders.VERTEX,Shaderc.shaderc_glsl_vertex_shader,args.length>0?args[0]+".vert.spv":null);
        compile(WorldRasterShaders.fragment(),Shaderc.shaderc_glsl_fragment_shader,args.length>0?args[0]+".frag.spv":null);
        System.out.println("World raster vertex/fragment SPIR-V and 10000 origin-relative projection cases passed");
    }
    private static void check(double actual,double expected){if(Math.abs(actual-expected)>2e-5)throw new AssertionError(actual+" != "+expected);}
    private static void compile(String source,int kind,String path) throws Exception {
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_optimization_level(options,Shaderc.shaderc_optimization_level_performance);
            result=Shaderc.shaderc_compile_into_spv(compiler,source,kind,"world-raster","main",options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            if(path!=null) { var data=Shaderc.shaderc_result_get_bytes(result);byte[] bytes=new byte[data.remaining()];data.get(bytes);java.nio.file.Files.write(java.nio.file.Path.of(path),bytes); }
        } finally {if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
}
