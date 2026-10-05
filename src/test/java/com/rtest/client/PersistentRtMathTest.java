package com.rtest.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.util.shaderc.Shaderc;

/** GPU fixture uses the actual runtime cache functions, not a duplicate implementation. */
public final class PersistentRtMathTest {
    static final int READ = 64;
    public static void main(String[] args) throws Exception {
        var b = ByteBuffer.allocate(304).order(ByteOrder.nativeOrder());
        b.putFloat(96, 16);
        long identity = PersistentRtPolicy.lightSignature(b);
        for (int offset : new int[]{0,4,8,16,20,24,32,36,40,48,52,56,128,144,224}) b.putFloat(offset, 123);
        if (identity != PersistentRtPolicy.lightSignature(b)) throw new AssertionError("camera motion invalidates world samples");
        b.putFloat(96, 16.001f);
        if (identity != PersistentRtPolicy.lightSignature(b)) throw new AssertionError("sub-bin intensity invalidates samples");
        b.putFloat(96, 17);
        if (identity == PersistentRtPolicy.lightSignature(b)) throw new AssertionError("light changes retain stale samples");
        if (!PersistentRtPolicy.fresh(100, -100, 200) || PersistentRtPolicy.fresh(100,-101,200)
                || PersistentRtPolicy.fresh(100,101,200)) throw new AssertionError("unsigned age / wrap");
        if (PersistentRtPolicy.mode("current_direct") != 1 || PersistentRtPolicy.mode("current_visibility") != 2
                || PersistentRtPolicy.mode("full") != 0) throw new AssertionError("mode ABI");
        String ray = RayTracingShaders.RAYGEN_SHADER;
        if (!ray.contains("emissionRadiance += cachedDiffuse + cachedSpecular;")
                || !ray.contains("pathPayload.staticBoundary = 0u;")) throw new AssertionError("composition / boundary reset");
        String payload = payload(ray);
        for (String stage : new String[]{RayTracingShaders.MISS_SHADER, RayTracingShaders.CLOSEST_HIT_SHADER, RayTracingShaders.ANY_HIT_SHADER})
            if (!payload.equals(payload(stage))) throw new AssertionError("radiance payload ABI differs across stages");
        if (!RayTracingShaders.ANY_HIT_SHADER.contains("pathPayload.staticBoundary != 0u"))
            throw new AssertionError("static training cannot exclude opaque dynamic geometry");
        String scheduler=Files.readString(Path.of("src/main/java/com/rtest/client/PersistentRtLighting.java"));
        if (!scheduler.contains("if (enabled && refresh) {")
                || !scheduler.contains("if (enabled && refresh) { readBank = 1 - readBank;"))
            throw new AssertionError("read snapshot must stay published between training batches");
        int reserve=ray.indexOf("persistentTraining = (!cacheHit || forceRefresh) && prtReserve(persistentKey);");
        int sample=ray.indexOf("rayDirection = sampleCosineHemisphere(normal, scatterSample.xy);",reserve);
        if(reserve<0 || sample<reserve || !ray.contains("if (cacheHit && !persistentTraining)"))
            throw new AssertionError("reserve before tracing, reuse hit when budget is exhausted");
        byte[] spv = compileShader();
        if (args.length > 0) {
            dump(args[0], spv, 65536, false, 256);
            dump(args[0]+"-large", spv, 65536, false, 65536);
            dump(args[0]+"-budget", spv, 2, true, 256);
            dump(args[0]+"-zero", spv, 0, true, 256);
        }
        System.out.println("Persistent RT policy, shader compilation and GPU fixtures passed; device readback is a separate test");
    }
    static String payload(String shader) {
        int start=shader.indexOf("struct PathPayload"),end=shader.indexOf("};",start);
        if(start<0 || end<0)throw new AssertionError("missing PathPayload");
        return shader.substring(start,end).replaceAll("(?m)//.*$", "").replaceAll("\\s+", " ");
    }
    static int[] key(int tag) { return new int[]{tag, -1, 2, 3, 4, 5, 6, 7, 8, 9}; }
    static int row(int[] key, int bank, int mask) { return bank + PersistentRtPolicy.hash(key,mask) * 24; }
    static void dump(String prefix, byte[] spv, int budget, boolean serial, int slots) throws Exception {
        int mask=slots-1, bank=slots*24, read=READ, write=read+bank;
        int trainCount = 69, queryCount = 7, train = write + bank, query = train + trainCount*16, out = query + queryCount*10;
        int[] seed = new int[out + queryCount*7];
        seed[0]=1; seed[1]=read; seed[2]=write; seed[3]=100; seed[4]=1; seed[5]=100; seed[6]=200; seed[7]=3; seed[8]=mask; seed[10]=budget;
        seed[32]=train; seed[33]=trainCount; seed[34]=query; seed[35]=queryCount; seed[36]=out; seed[37]=serial?1:0;
        int[][] keys = new int[7][]; boolean[] used = new boolean[slots]; int tag=0;
        for(int i=0;i<keys.length;i++) { while(used[PersistentRtPolicy.hash(key(tag),mask)])tag++; keys[i]=key(tag++); used[PersistentRtPolicy.hash(keys[i],mask)]=true; }
        for(int i=0;i<7;i++) {
            int r=row(keys[i],read,mask); seed[r]=99; System.arraycopy(keys[i],0,seed,r+1,10); seed[r+11]=3; seed[r+19]=-100;
            for(int c=0;c<6;c++) seed[r+12+c]=Float.floatToIntBits(i==1?0:2);
            if(i==2)seed[r+11]=2; // too few samples
            if(i==3)seed[r+19]=-101; // age exceeds threshold across uint wrap
            if(i==5)seed[r+1]++; // same slot, different full key
            if(i==6)seed[r+11]=0; // vacant slot
            System.arraycopy(keys[i],0,seed,query+i*10,10);
        }
        System.arraycopy(seed,read,seed,write,bank);
        for(int i=0;i<trainCount;i++) {
            int which=i<64?0:i-63; int p=train+i*16; System.arraycopy(keys[which],0,seed,p,10);
            for(int c=0;c<6;c++)seed[p+10+c]=Float.floatToIntBits(4);
            if(which==4)seed[p+10]=Float.floatToIntBits(Float.NaN);
        }
        int[] expected=seed.clone(); expected[30]=6;
        for(int i=0;i<6;i++) {
            int r=row(keys[i],write,mask); expected[r]=100;
            if(i==4 || i>=budget)continue;
            int n=i==3||i==5?0:i==2?2:3;
            expected[r]=100; System.arraycopy(keys[i],0,expected,r+1,10); expected[r+11]=n+1; expected[r+19]=100;
            for(int c=0;c<6;c++)expected[r+12+c]=Float.floatToIntBits(((i==1?0:2)*n+4f)/(n+1));
        }
        for(int i=0;i<7;i++) {
            boolean valid=i==0||i==1||i==4;
            expected[out+i*7]=valid?1:0;
            for(int c=0;c<6;c++)expected[out+i*7+1+c]=Float.floatToIntBits(valid&&i!=1?2:0);
        }
        Files.write(Path.of(prefix+".spv"),spv); write(prefix+".seed",seed); write(prefix+".expected",expected);
    }
    static void write(String path,int[] words) throws Exception {
        var b=ByteBuffer.allocate(words.length*4).order(ByteOrder.LITTLE_ENDIAN); b.asIntBuffer().put(words); Files.write(Path.of(path),b.array());
    }
    static byte[] compileShader() {
        String source="#version 460\n"+PersistentRtShader.GLSL.replace("binding = 41", "binding = 0")+"""
            layout(local_size_x=128) in;
            layout(push_constant) uniform TestPhase { uint unused; uint count; uint phase; } test;
            void train(uint i) {
                uint key[10]; uint p=prt.words[32]+i*16u;
                for(uint j=0u;j<10u;j++)key[j]=prt.words[p+j];
                vec3 d=vec3(uintBitsToFloat(prt.words[p+10u]),uintBitsToFloat(prt.words[p+11u]),uintBitsToFloat(prt.words[p+12u]));
                vec3 s=vec3(uintBitsToFloat(prt.words[p+13u]),uintBitsToFloat(prt.words[p+14u]),uintBitsToFloat(prt.words[p+15u]));
                prtTrain(key,d,s);
            }
            void main() {
                uint i=gl_GlobalInvocationID.x; if(i>=test.count)return;
                if(test.phase==0u) {
                    if(prt.words[37]!=0u) {if(i==0u)for(uint k=0u;k<test.count;k++)train(k);}
                    else train(i);
                } else {
                    uint key[10]; uint p=prt.words[34]+i*10u;
                    for(uint j=0u;j<10u;j++)key[j]=prt.words[p+j];
                    vec3 d,s; bool valid=prtLookup(key,d,s); uint o=prt.words[36]+i*7u; prt.words[o]=valid?1u:0u;
                    for(uint j=0u;j<3u;j++){prt.words[o+1u+j]=floatBitsToUint(d[j]);prt.words[o+4u+j]=floatBitsToUint(s[j]);}
                }
            }
            """;
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize(),result=0;
        var bytes=org.lwjgl.system.MemoryUtil.memUTF8(source,false);
        var name=org.lwjgl.system.MemoryUtil.memASCII("persistent-cache-test.comp",true);
        var entry=org.lwjgl.system.MemoryUtil.memASCII("main",true);
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            result=Shaderc.shaderc_compile_into_spv(compiler,bytes,Shaderc.shaderc_glsl_compute_shader,name,entry,options);
            if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            var binary=Shaderc.shaderc_result_get_bytes(result); byte[] spv=new byte[binary.remaining()];binary.get(spv);return spv;
        } finally {
            if(result!=0)Shaderc.shaderc_result_release(result);Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);
            org.lwjgl.system.MemoryUtil.memFree(bytes);org.lwjgl.system.MemoryUtil.memFree(name);org.lwjgl.system.MemoryUtil.memFree(entry);
        }
    }
}
