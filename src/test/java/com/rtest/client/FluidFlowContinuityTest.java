package com.rtest.client;

/** Replays the actual capture helper: a stacked fluid column must reach the next cell. */
public final class FluidFlowContinuityTest {
    public static void main(String[] args) throws Exception {
        var average=RayTracingScene.SceneGeometry.class.getDeclaredMethod("averageFluidHeight",
            net.minecraft.client.multiplayer.ClientLevel.class,net.minecraft.world.level.material.Fluid.class,
            float.class,float.class,float.class,net.minecraft.core.BlockPos.class);
        average.setAccessible(true);
        // Self already reports fluid above; the world must not be queried or averaged with air.
        float result=(float)average.invoke(null,null,null,1.0F,0.0F,0.0F,null);
        if(result!=1.0F)throw new AssertionError("stacked flow corner shrank: "+result);
        float isolated=(float)average.invoke(null,null,null,0.9F,0.0F,0.0F,null);
        near(isolated,0.75F,"weighted isolated source rim");
        near(FluidGeometryCapture.cornerHeight(0.9F,0.9F,0,-1),18F/21F,"two high neighbors");
        near(FluidGeometryCapture.cornerHeight(0.5F,-1,-1,-1),0.5F,"solid neighbors excluded");
        for(int i=0;i<360;i++) {
            double angle=i*Math.PI/180;
            var uv=FluidGeometryCapture.topUv(Math.cos(angle),Math.sin(angle));
            for(float value:uv)if(value<0 || value>1)throw new AssertionError("flow UV outside sprite");
            float area=0;
            for(int k=0;k<4;k++){int n=(k+1)%4;area+=uv[k*2]*uv[n*2+1]-uv[n*2]*uv[k*2+1];}
            near(Math.abs(area)*0.5F,0.25F,"flow UV square area");
        }
        assertQuad();
        System.out.println("Production stacked-fluid corner continuity passed");
    }
    private static Object construct(String name,Object... values) throws Exception {
        var type=Class.forName("com.rtest.client.RayTracingScene$SceneGeometry$"+name);
        var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
    private static void assertQuad() throws Exception {
        var v=Class.forName("com.rtest.client.RayTracingScene$SceneGeometry$FluidVertex");
        var accum=Class.forName("com.rtest.client.RayTracingScene$SceneGeometry$FloatAccumulator");
        var props=Class.forName("com.rtest.client.RayTracingScene$SceneGeometry$MaterialProperties");
        var positions=construct("FloatAccumulator");var materials=construct("FloatAccumulator");
        var optical=construct("OpticalProperties",1.333F,0.13F,0.108F,0.021F,0.68F,0F);
        var properties=construct("MaterialProperties",0.12F,0F,0F,0.04F,0F,optical);
        var quad=RayTracingScene.SceneGeometry.class.getDeclaredMethod("addFluidQuad",accum,accum,
            net.minecraft.core.BlockPos.class,net.minecraft.core.BlockPos.class,v,v,v,v,int.class,float.class,float.class,float.class,props);
        quad.setAccessible(true);
        var origin=new net.minecraft.core.BlockPos(0,0,0);
        quad.invoke(null,positions,materials,origin,origin,
            construct("FluidVertex",0F,1F,0F,0.2F,0.3F),construct("FluidVertex",1F,1F,0F,0.4F,0.3F),
            construct("FluidVertex",1F,0F,0F,0.4F,0.5F),construct("FluidVertex",0F,0F,0F,0.2F,0.5F),
            0xFFFFFF,0F,0F,-1F,properties);
        var out=accum.getDeclaredMethod("toArray");out.setAccessible(true);
        float[] xyz=(float[])out.invoke(positions),mat=(float[])out.invoke(materials);
        if(xyz.length!=18 || mat.length!=56)throw new AssertionError("quad geometry/material stride mismatch");
        var sectionCtor=RayTracingScene.SceneGeometry.SectionGeometry.class.getDeclaredConstructor(int.class,int.class,int.class,float[].class,float[].class);
        sectionCtor.setAccessible(true);
        var section=sectionCtor.newInstance(0,0,0,xyz,mat);
        xyz=section.vertices;mat=section.materialData;
        for(int t=0;t<2;t++){
            if(mat[t*28+14]!=0 || mat[t*28+15]!=1)throw new AssertionError("fluid accidentally marked cutout");
            near(mat[t*28+3],0.68F,"opacity");near(mat[t*28+27],1.333F,"IOR");
            for(int k=0;k<3;k++){
                float x=xyz[t*9+k*3],y=xyz[t*9+k*3+1];
                near(mat[t*28+8+k*2],0.2F+x*0.2F,"UV association after winding normalization");
                near(mat[t*28+9+k*2],0.5F-y*0.2F,"UV association after winding normalization");
            }
        }
        for(int i=0;i<61;i++)for(int j=0;j<61;j++){
            float x=i/60F,y=j/60F;boolean covered=false;
            for(int t=0;t<2;t++){
                int o=t*9;float ax=xyz[o],ay=xyz[o+1],bx=xyz[o+3],by=xyz[o+4],cx=xyz[o+6],cy=xyz[o+7];
                float det=(bx-ax)*(cy-ay)-(by-ay)*(cx-ax);
                float u=((x-ax)*(cy-ay)-(y-ay)*(cx-ax))/det;
                float w=((bx-ax)*(y-ay)-(by-ay)*(x-ax))/det;
                covered|=u>=-1e-5F && w>=-1e-5F && u+w<=1.00001F;
            }
            if(!covered)throw new AssertionError("hole in production fluid quad at "+x+","+y);
        }
    }
    private static void near(float a,float b,String name){if(Math.abs(a-b)>1e-5F)throw new AssertionError(name+": "+a+" != "+b);}
}
