package com.rtest.client;

import java.util.List;

public final class WorldCacheMigrationTest {
    public static void main(String[] args) throws Exception {
        var ctor=RayTracingScene.SceneGeometry.SectionGeometry.class.getDeclaredConstructor(int.class,int.class,int.class,float[].class,float[].class);
        ctor.setAccessible(true);
        float[] triangle={0,0,0,1,0,0,0,0,1};
        var a=ctor.newInstance(0,0,0,triangle,new float[28]);
        var b=ctor.newInstance(16,0,0,triangle,new float[28]);
        var c=ctor.newInstance(32,0,0,triangle,new float[28]);
        var sceneCtor=RayTracingScene.SceneGeometry.class.getDeclaredConstructor(List.class,float[].class,float[].class,int[].class,int.class,int.class,double.class,double.class,double.class,boolean.class);
        sceneCtor.setAccessible(true);
        var old=sceneCtor.newInstance(List.of(a,b),new float[0],new float[0],new int[0],2,8,0d,0d,0d,false);
        var loaded=sceneCtor.newInstance(List.of(c,b,a),new float[0],new float[0],new int[0],3,8,16d,0d,0d,false);
        var moves=WorldCacheMigration.surfaces(old,loaded);
        if(!moves.equals(List.of(new WorldCacheMigration.Copy(3,3,3),new WorldCacheMigration.Copy(0,6,3))))
            throw new AssertionError("loading/reordering/rebasing discarded unchanged surfaces: "+moves);
        triangle[3]=2;
        var changed=ctor.newInstance(0,0,0,triangle,new float[28]);
        var edited=sceneCtor.newInstance(List.of(changed,b),new float[0],new float[0],new int[0],2,8,0d,0d,0d,false);
        if(!WorldCacheMigration.surfaces(old,edited).equals(List.of(new WorldCacheMigration.Copy(3,3,3))))
            throw new AssertionError("block edit must only discard the changed section's receiver rows");
        var grid=new WorldIrradianceField.Grid(-64,-32,-64,32,5,4,5,1,4,0,256);
        for(int dx=-6;dx<=6;dx++)for(int dy=-5;dy<=5;dy++)for(int dz=-6;dz<=6;dz++) {
            var next=new WorldIrradianceField.Grid(grid.x()+dx*32,grid.y()+dy*32,grid.z()+dz*32,32,6,5,6,1,4,0,256);
            boolean[] copied=new boolean[next.count()];
            for(var span:WorldCacheMigration.probes(grid,next)) for(int k=0;k<span.count();k++) {
                int source=span.source()+k,target=span.destination()+k;
                if(copied[target]||!java.util.Arrays.equals(grid.position(source),next.position(target)))
                    throw new AssertionError("probe migration reused another world position");
                copied[target]=true;
            }
            for(int i=0;i<next.count();i++) {
                var p=next.position(i);
                boolean overlaps=p[0]>=grid.x()&&p[0]<=grid.x()+128&&p[1]>=grid.y()&&p[1]<=grid.y()+96&&p[2]>=grid.z()&&p[2]<=grid.z()+128;
                if(copied[i]!=overlaps)throw new AssertionError("overlap lost while extending/recentering grid");
            }
        }
        var initial=WorldIrradianceGpu.sceneGrid(old,1,4,0,256);
        var expanded=WorldIrradianceGpu.sceneGrid(loaded,1,4,0,256,initial);
        if(initial.spacing()!=expanded.spacing()||WorldCacheMigration.probes(initial,expanded).isEmpty())
            throw new AssertionError("progressive chunk load changed grid spacing and discarded history");
        System.out.println("World cache migration: unchanged sections, block edits, origin shifts, 1859 overlapping grids and persistent lifetime passed");
    }
}
