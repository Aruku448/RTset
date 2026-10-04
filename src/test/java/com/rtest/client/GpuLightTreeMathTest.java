package com.rtest.client;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.lwjgl.util.shaderc.Shaderc;

/** Checks heap geometry, every emitter's PDF support, and shader compilation without a device. */
public final class GpuLightTreeMathTest {
    public static void main(String[] args) throws Exception {
        byte[] spirv = compileShader();
        if (args.length > 0 && args[0].equals("dump")) {
            var sections = List.of(section(args.length > 2 ? Integer.parseInt(args[2]) : 5003));
            var seed = RayTracingLightTree.build(sections,0,0,0,RayTracingMaterialBuffer.Layout.compact(sections),true);
            java.nio.file.Files.write(java.nio.file.Path.of(args[1]+".spv"),spirv);
            dump(java.nio.file.Path.of(args[1]+".seed"),seed.words());
            dump(java.nio.file.Path.of(args[1]+".expected"),seed.completeOnCpu());
            return;
        }
        if (args.length > 0 && args[0].equals("bench")) { benchmark(); return; }
        for (int n : new int[]{1, 31, 1023}) {
            var sections = List.of(section(n));
            var layout = RayTracingMaterialBuffer.Layout.compact(sections);
            var cpu = RayTracingLightTree.build(sections,0,0,0,layout,false);
            var small = RayTracingLightTree.build(sections,0,0,0,layout,true);
            if (small.gpuBuild() || !Arrays.equals(cpu.words(),small.words()))
                throw new AssertionError("small-tree CPU fallback changed ABI");
        }
        for (int n : new int[]{1024, 1025, 1537, 2047, 2048, 2049, 5003}) {
            var section = section(n);
            var sections = List.of(section);
            var layout = RayTracingMaterialBuffer.Layout.compact(sections);
            var seed = RayTracingLightTree.build(sections, 0, 0, 0, layout, true);
            if (!seed.gpuBuild()) throw new AssertionError("GPU seed missing");
            int[] w = seed.completeOnCpu();
            var upload = MappedUploadMathTest.view(w.length * 4);
            int sentinel = 0x12345678;
            for (int i = 0; i < w.length; i++) upload.buffer().putInt(i*4, sentinel);
            RayTracingMaterialBuffer.writeLightData(upload, seed);
            int[] raw = seed.words();
            for (int i = 0; i < w.length; i++) {
                boolean uploaded = i < 8 || (i >= raw[4] && i < raw[7]);
                if (upload.buffer().getInt(i*4) != (uploaded ? raw[i] : sentinel))
                    throw new AssertionError("GPU-generated light range uploaded at word " + i);
                if (MappedUploadMathTest.covered(upload, i*4L) != uploaded)
                    throw new AssertionError("light seed flush range mismatch at word " + i);
            }
            if (w[0] != 2*n-1 || w[w[3]] != -1) throw new AssertionError("heap root ABI");
            boolean[] ready = new boolean[w[0]];
            for (int node = n-1; node < w[0]; node++) ready[node] = true;
            for (int first = Integer.highestOneBit(n-1)-1; first >= 255; first = (first-1)/2) {
                int count = Math.min(first+1, n-1-first);
                for (int node = first; node < first+count; node++) {
                    if (!ready[2*node+1] || !ready[2*node+2]) throw new AssertionError("dispatch reads unfinished child");
                    ready[node] = true;
                }
            }
            for (int first=127; first>=0; first=first==0?-1:(first-1)/2) {
                for (int lane=0; lane<=first; lane++) {
                    int node=first+lane;
                    if (!ready[2*node+1] || !ready[2*node+2]) throw new AssertionError("fused level dependency");
                    ready[node]=true;
                }
            }
            for (boolean r : ready) if (!r) throw new AssertionError("unwritten node");
            checkBounds(w, 0);
            double[] probabilities = new double[n];
            forwardPdf(w, 0, 1, probabilities);
            double total = 0;
            boolean[] mapped = new boolean[n];
            for (int m = 0; m < n; m++) {
                int packed = w[w[6]+m];
                if (packed < 0 || packed >= n || mapped[packed]) throw new AssertionError("material map permutation");
                mapped[packed] = true;
                if (w[w[4]+16*packed+15] != m) throw new AssertionError("emitter material round trip");
                int node = w[w[7]+packed];
                if (w[w[2]+node] != (packed | Integer.MIN_VALUE)) throw new AssertionError("reverse leaf map");
                double reverse = 1;
                while (node != 0) {
                    int parent = w[w[3]+node], left = w[w[2]+parent];
                    double p = probability(w, left, left+1);
                    reverse *= node == left ? p : 1-p;
                    node = parent;
                }
                if (!(reverse > 0) || Math.abs(reverse-probabilities[packed]) > 1e-12) throw new AssertionError("forward/reverse PDF mismatch");
                total += reverse;
            }
            if (Math.abs(total-1) > 1e-10) throw new AssertionError("selection probabilities do not sum to one");
            var resized = seed.withMaterialMapLength(n+31);
            int[] r = resized.completeOnCpu();
            if (!resized.gpuBuild() || !Arrays.equals(Arrays.copyOfRange(w,8,w[6]),Arrays.copyOfRange(r,8,r[6])))
                throw new AssertionError("lookup growth altered GPU hierarchy");
        }
        System.out.println("GPU light-tree heap, dispatch dependencies, bounds, maps, MIS PDFs and SPIR-V passed; GPU execution not measured");
    }
    private static float f(int[] w, int node, int a) { return Float.intBitsToFloat(w[8+8*node+a]); }
    private static void checkBounds(int[] w, int node) {
        int link = w[w[2]+node];
        if (link < 0) {
            int e = w[4]+16*(link & Integer.MAX_VALUE);
            for (int a=0; a<3; a++) {
                float p=Float.intBitsToFloat(w[e+a]), q=p+Float.intBitsToFloat(w[e+4+a]), r=p+Float.intBitsToFloat(w[e+8+a]);
                if (f(w,node,a)!=Math.min(p,Math.min(q,r)) || f(w,node,4+a)!=Math.max(p,Math.max(q,r))) throw new AssertionError("leaf bounds");
            }
            if (f(w,node,3)!=Float.intBitsToFloat(w[e+11])) throw new AssertionError("leaf power");
        } else {
            checkBounds(w,link); checkBounds(w,link+1);
            for (int a=0; a<3; a++) if (f(w,node,a)!=Math.min(f(w,link,a),f(w,link+1,a))
                || f(w,node,4+a)!=Math.max(f(w,link,4+a),f(w,link+1,4+a))) throw new AssertionError("aggregate bounds");
            if (f(w,node,3)!=f(w,link,3)+f(w,link+1,3)) throw new AssertionError("aggregate power");
        }
        if (f(w,node,7)!=.25F) throw new AssertionError("softening ABI");
    }
    private static double distance(int[] w, int node) {
        double d=.25;
        double[] point={-123.5, 17.3, 20.9};
        for(int a=0;a<3;a++) { double delta=point[a]-Math.max(f(w,node,a),Math.min(f(w,node,a+4),point[a])); d+=delta*delta; }
        return d;
    }
    private static double probability(int[] w, int l, int r) {
        double a=f(w,l,3)*distance(w,r), b=f(w,r,3)*distance(w,l);
        return Math.max(.000001,Math.min(.999999,a/(a+b)));
    }
    private static void forwardPdf(int[] w,int node,double p,double[] out) {
        int link=w[w[2]+node];
        if(link<0) { out[link & Integer.MAX_VALUE]=p; return; }
        double a=probability(w,link,link+1);
        forwardPdf(w,link,p*a,out); forwardPdf(w,link+1,p*(1-a),out);
    }
    private static RayTracingScene.SceneGeometry.SectionGeometry section(int n) throws Exception {
        Constructor<RayTracingScene.SceneGeometry.SectionGeometry> c=RayTracingScene.SceneGeometry.SectionGeometry.class
            .getDeclaredConstructor(int.class,int.class,int.class,float[].class,float[].class);
        c.setAccessible(true);
        float[] v=new float[n*9], m=new float[n*28]; Random random=new Random(993);
        for(int i=0;i<n;i++) {
            float x=random.nextFloat()*1000-500,y=random.nextFloat()*100,z=random.nextFloat()*1000-500;
            int b=i*9; v[b]=x;v[b+1]=y;v[b+2]=z;v[b+3]=x+1;v[b+4]=y;v[b+5]=z;v[b+6]=x;v[b+7]=y;v[b+8]=z+1;
            b=i*28;m[b]=m[b+1]=m[b+2]=1;m[b+5]=1;m[b+22]=.1F+random.nextFloat()*3;
        }
        return c.newInstance(0,0,0,v,m);
    }
    private static void dump(java.nio.file.Path path,int[] words) throws Exception {
        var buffer=java.nio.ByteBuffer.allocate(words.length*4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for(int word:words)buffer.putInt(word);
        java.nio.file.Files.write(path,buffer.array());
    }
    private static void benchmark() throws Exception {
        var sections=List.of(section(80000));var layout=RayTracingMaterialBuffer.Layout.compact(sections);
        double[] cpu=new double[9],seed=new double[9];
        for(int i=-4;i<9;i++) {
            long t=System.nanoTime();var a=RayTracingLightTree.build(sections,0,0,0,layout,false);long d=System.nanoTime()-t;
            t=System.nanoTime();var b=RayTracingLightTree.build(sections,0,0,0,layout,true);long e=System.nanoTime()-t;
            if(a.emitterCount()!=b.emitterCount())throw new AssertionError();
            if(i>=0){cpu[i]=d/1e6;seed[i]=e/1e6;}
        }
        Arrays.sort(cpu);Arrays.sort(seed);
        System.out.printf(java.util.Locale.ROOT,"{\"emitters\":80000,\"cpu_tree_median_ms\":%.3f,\"gpu_seed_cpu_median_ms\":%.3f,\"gpu_execution_measured\":false}%n",cpu[4],seed[4]);
    }
    private static byte[] compileShader() throws Exception {
        String source;
        try(var stream=GpuLightTreeMathTest.class.getClassLoader().getResourceAsStream("rtest/shaders/light_tree_build.comp")) {
            source=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        long compiler=Shaderc.shaderc_compiler_initialize(), options=Shaderc.shaderc_compile_options_initialize(), result=0;
        var bytes=org.lwjgl.system.MemoryUtil.memUTF8(source,false);
        var name=org.lwjgl.system.MemoryUtil.memASCII("light_tree_build.comp",true);
        var entry=org.lwjgl.system.MemoryUtil.memASCII("main",true);
        try {
            Shaderc.shaderc_compile_options_set_source_language(options, Shaderc.shaderc_source_language_glsl);
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,bytes,Shaderc.shaderc_glsl_compute_shader,name,entry,options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            var binary = Shaderc.shaderc_result_get_bytes(result);
            byte[] spirv = new byte[binary.remaining()]; binary.get(spirv); return spirv;
        } finally {
            if(result!=0)Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);
            org.lwjgl.system.MemoryUtil.memFree(bytes);org.lwjgl.system.MemoryUtil.memFree(name);org.lwjgl.system.MemoryUtil.memFree(entry);
        }
    }
}
