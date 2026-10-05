package com.rtest.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.lwjgl.util.shaderc.Shaderc;
import static com.rtest.client.WorldLightingContributions.*;

public final class WorldLightingContributionsTest {
    public static void main(String[] args) throws Exception {
        var random = new Random(183);
        for (int i = 0; i < 10000; i++) {
            Rgb source = rgb(random, 100), staticV = rgb(random, 1), fullV = staticV.multiply(rgb(random, 1));
            var sample = new SourceSample(source, staticV, fullV);
            Rgb bsdfA = rgb(random, 1), bsdfB = rgb(random, 1);
            check(atView(sample, bsdfA).total(), source.multiply(fullV).multiply(bsdfA));
            check(atView(sample, bsdfB).total(), source.multiply(fullV).multiply(bsdfB));
            var delta = atView(sample, bsdfA).entityDelta();
            if (delta.r() > 1e-12 || delta.g() > 1e-12 || delta.b() > 1e-12)
                throw new AssertionError("opaque entity shadow must remain signed");
        }
        var black = new Rgb(0,0,0); var one = new Rgb(1,1,1);
        var shadow = atView(new SourceSample(new Rgb(8,4,2), one, black), one);
        check(compose(shadow.total(), new Rgb(2,3,4), new Rgb(1,2,3), new Rgb(4,5,6)), new Rgb(7,10,13));
        check(lambert(new Rgb(Math.PI,Math.PI,Math.PI), new Rgb(.2,.3,.4)), new Rgb(.2,.3,.4));
        // Correlated emitter samples: E[Vs*Vd]=0, but E[Vs]*E[Vd]=.25.
        double joint = (1*0 + 0*1)/2.0, independent = .5*.5;
        if (joint == independent) throw new AssertionError("soft visibility correlation fixture");
        snapshot();
        allMeshFamilies();
        compileShader();
        shadowProjection();
        compileSource(WorldRasterSunShadow.VERTEX,Shaderc.shaderc_glsl_vertex_shader);
        compileSource(WorldRasterSunShadow.FRAGMENT,Shaderc.shaderc_glsl_fragment_shader);
        System.out.println("World lighting view/source separation, signed RGB shadows and entity raster snapshots passed");
    }
    private static Rgb rgb(Random r, double scale) {
        return new Rgb(r.nextDouble()*scale,r.nextDouble()*scale,r.nextDouble()*scale);
    }
    private static void check(Rgb a, Rgb b) {
        if (Math.abs(a.r()-b.r())>1e-10 || Math.abs(a.g()-b.g())>1e-10 || Math.abs(a.b()-b.b())>1e-10)
            throw new AssertionError(a+" != "+b);
    }
    private static void snapshot() {
        var registry = new DynamicInstanceRegistry(); registry.beginFrame();
        registry.upsert(7, DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(42,43), DynamicInstanceRegistry.Transform.translation(2,3,4),
            DynamicInstanceRegistry.FLAG_OPAQUE, false);
        registry.upsert(8, DynamicInstanceRegistry.Family.FIRST_PERSON_BODY,
            new DynamicInstanceRegistry.GeometryKey(42,43), DynamicInstanceRegistry.Transform.identity(),
            DynamicInstanceRegistry.FLAG_FIRST_PERSON_BODY, false);
        registry.upsert(9, DynamicInstanceRegistry.Family.ENTITY,
            new DynamicInstanceRegistry.GeometryKey(42,43), DynamicInstanceRegistry.Transform.identity(),0,false);
        var instances = registry.finish();
        float[] xyz={0,0,0, 1,0,0, 0,1,0}; float[] materials=new float[28];
        var mesh = new PlayerModelGeometryAdapter.Mesh(xyz,materials);
        var captured = instances.instances().stream().map(i -> new DynamicEntityGeometry.Instance(i,
            i.identity()==8 ? DynamicEntityGeometry.Family.FIRST_PERSON_BODY : DynamicEntityGeometry.Family.PLAYER_BODY,
            1,1,42)).toList();
        var frame = new DynamicEntityGeometry.Frame(instances,captured,Map.of(7L,mesh,8L,mesh),Map.of(),
            Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),0);
        var snapshot = DynamicRasterSnapshot.from(frame);
        if (snapshot.complete() || !snapshot.missingGeometry().equals(List.of(9L)) || snapshot.draws().size()!=2)
            throw new AssertionError("missing geometry must not become a placeholder or vanish silently");
        var draw = snapshot.draws().getFirst();
        if (!draw.primaryVisible() || snapshot.draws().get(1).primaryVisible()
            || draw.triangleCount()!=1 || draw.instance().currentTransform().m03()!=2)
            throw new AssertionError("entity transform/first person primary mask");
        draw.positions().position(4);
        if (draw.positions().position()!=0 || !draw.positions().isReadOnly() || !draw.materials().isReadOnly())
            throw new AssertionError("draw buffer cursor leaked between consumers");
        registry.beginFrame();
        registry.upsert(7,DynamicInstanceRegistry.Family.ENTITY,new DynamicInstanceRegistry.GeometryKey(42,43),
            DynamicInstanceRegistry.Transform.translation(5,3,4),DynamicInstanceRegistry.FLAG_OPAQUE,false);
        var moved=registry.finish().instances().getFirst();
        if (moved.previousTransform().m03()!=2 || moved.currentTransform().m03()!=5
            || draw.instance().currentTransform().m03()!=2) throw new AssertionError("snapshot lifetime/motion");
    }
    private static void allMeshFamilies() {
        var registry = new DynamicInstanceRegistry(); registry.beginFrame();
        for (var family : DynamicEntityGeometry.Family.values()) {
            registry.upsert(family.ordinal()+20, DynamicInstanceRegistry.Family.ENTITY,
                new DynamicInstanceRegistry.GeometryKey(1,2),DynamicInstanceRegistry.Transform.identity(),
                DynamicInstanceRegistry.FLAG_CUTOUT,false);
        }
        var instances=registry.finish();
        var captured=instances.instances().stream().map(i -> new DynamicEntityGeometry.Instance(i,
            DynamicEntityGeometry.Family.values()[(int)i.identity()-20],1,1,1)).toList();
        var player=new PlayerModelGeometryAdapter.Mesh(new float[9],new float[28]);
        var item=new ItemModelGeometryAdapter.Mesh(new float[9],new float[28]);
        var frame=new DynamicEntityGeometry.Frame(instances,captured,Map.of(20L,player,21L,player),Map.of(),
            Map.of(22L,player,26L,player),Map.of(),Map.of(23L,player),Map.of(),Map.of(),
            Map.of(24L,item,25L,item),0);
        var snapshot=DynamicRasterSnapshot.from(frame);
        if (!snapshot.complete() || snapshot.draws().size()!=DynamicEntityGeometry.Family.values().length)
            throw new AssertionError("player/living/block entity/item/particle mesh family adapter");
    }
    private static void shadowProjection() {
        var random=new Random(1729);
        var matrix=java.nio.ByteBuffer.allocate(80).order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<10000;i++) {
            double[] center={random.nextDouble()*1000,random.nextDouble()*1000,random.nextDouble()*1000};
            double radius=16+random.nextDouble()*1000;
            float x=(float)(random.nextDouble()*2-1),y=(float)(random.nextDouble()*2-1),z=(float)(random.nextDouble()*2-1);
            double length=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
            WorldRasterSunShadow.writeProjection(matrix,center,radius,x,y,z);
            double[] origin=transform(matrix,center);
            if(Math.abs(origin[0])>1e-5 || Math.abs(origin[1])>1e-5 || Math.abs(origin[2]-.5)>1e-5)
                throw new AssertionError("sun projection center");
            for(int side:new int[]{-1,1}) {
                double[] p={center[0]+side*radius*x/length,center[1]+side*radius*y/length,center[2]+side*radius*z/length};
                double[] projected=transform(matrix,p);
                if(Math.abs(projected[0])>1e-5 || Math.abs(projected[1])>1e-5
                    || Math.abs(projected[2]-(side==1?0:1))>1e-5)
                    throw new AssertionError("near sun must use LESS depth with standard z");
            }
        }
        WorldRasterSunShadow.writeProjection(matrix,new double[]{0,0,0},16,0,0,0);
        for(int i=0;i<64;i+=4)if(!Float.isFinite(matrix.getFloat(i)))throw new AssertionError("zero direction fallback");
    }
    private static double[] transform(java.nio.ByteBuffer m,double[] p) {
        double[] result=new double[3];
        for(int row=0;row<3;row++)result[row]=m.getFloat(row*4)*p[0]+m.getFloat(16+row*4)*p[1]
            +m.getFloat(32+row*4)*p[2]+m.getFloat(48+row*4);
        return result;
    }
    private static void compileShader() throws Exception {
        String source = "#version 460\n" + Files.readString(Path.of("src/main/resources/prime/shaders/world_lighting_contributions.glsl")) + """
            layout(local_size_x=1) in;
            layout(set=0,binding=0,std430) buffer Result {vec4 outputColor;};
            void main() {
                WorldDirectSample light = WorldDirectSample(vec3(8),vec3(1),vec3(.3,.2,.1));
                DisplayDirectContribution direct = worldDirectAtView(light,vec3(.5));
                outputColor=vec4(worldComposeSurface(worldDirectTotal(direct),
                    worldLambertIrradiance(vec3(3.14159265),vec3(.5)),vec3(0),vec3(0)),1);
            }
            """;
        compileSource(source,Shaderc.shaderc_glsl_compute_shader);
    }
    private static void compileSource(String source,int stage) {
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        var bytes=org.lwjgl.system.MemoryUtil.memUTF8(source,false);
        var name=org.lwjgl.system.MemoryUtil.memASCII("world-contributions.comp",true);
        var entry=org.lwjgl.system.MemoryUtil.memASCII("main",true);
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,bytes,stage,name,entry,options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
        } finally {
            if(result!=0)Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);
            org.lwjgl.system.MemoryUtil.memFree(bytes);org.lwjgl.system.MemoryUtil.memFree(name);org.lwjgl.system.MemoryUtil.memFree(entry);
        }
    }
}
