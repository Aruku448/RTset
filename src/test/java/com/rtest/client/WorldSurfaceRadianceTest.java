package com.rtest.client;

import java.nio.*;
import java.nio.file.*;
import org.lwjgl.util.shaderc.Shaderc;

public final class WorldSurfaceRadianceTest {
    public static void main(String[] args) throws Exception {
        int words=16+6*8*2,output=words;
        var seed=ByteBuffer.allocate((words+12)*4).order(ByteOrder.nativeOrder());
        seed.putInt(0,6).putInt(4,9).putInt(8,16).putInt(12,64).putInt(20,1).putInt(24,6);
        var expected=ByteBuffer.allocate(seed.capacity()).order(ByteOrder.nativeOrder());expected.put(seed.duplicate());
        for(int i=0;i<12;i++)expected.putInt((output+i)*4,1);
        expected.putFloat((output+2)*4,10.5f).putFloat((output+3)*4,4).putFloat((output+4)*4,2);
        String source="#version 460\n#define WORLD_SURFACE_BINDING 0\n"+WorldSurfaceRadiance.GLSL+"\nlayout(local_size_x=128)in;layout(push_constant)uniform Work{uint first;uint count;uint phase;}work;const uint OUTPUT="+output+"u;\n"+"""
            void main(){uint id=gl_GlobalInvocationID.x;if(work.phase==0u){if(id<6u)wsrStore(id,id<3u?vec3(8.0+2.0*float(id),4,2):vec3(0));return;}if(id!=0u)return;
                float confidence;vec3 light;
                if(work.phase==1u){light=wsrSun(0u,vec3(.25,.25,.5),confidence);wsr.words[OUTPUT]=(confidence==0.0&&light==vec3(0))?1u:0u;return;}
                wsr.words[2]=wsr.words[3];light=wsrSun(0u,vec3(.25,.25,.5),confidence);wsr.words[OUTPUT+1u]=confidence==1.0?1u:0u;
                for(uint c=0u;c<3u;c++)wsr.words[OUTPUT+2u+c]=floatBitsToUint(light[int(c)]);
                wsr.words[OUTPUT+5u]=wsrSequence(0u)==1u?1u:0u;
                light=wsrSun(1u,vec3(.2,.3,.5),confidence);wsr.words[OUTPUT+6u]=(confidence==1.0&&light==vec3(0))?1u:0u;
                uint sequence=wsrSequence(0u);wsrStore(0u,vec3(uintBitsToFloat(0x7fc00000u)));wsr.words[OUTPUT+7u]=wsrSequence(0u)==sequence?1u:0u;
                wsrCorner(6u,confidence);wsr.words[OUTPUT+8u]=confidence==0.0?1u:0u;
                wsr.words[1]=10u;wsrCorner(0u,confidence);wsr.words[OUTPUT+9u]=confidence==0.0?1u:0u;wsr.words[1]=9u;
                wsr.words[5]=0u;wsrCorner(0u,confidence);wsr.words[OUTPUT+10u]=confidence==0.0?1u:0u;wsr.words[5]=1u;
                wsr.words[7]=3u;wsr.words[6]=3u;wsr.words[4]=2u;
                bool jobs=wsrJob(0u)==3u&&wsrJob(2u)==5u&&wsrJob(3u)==2u&&wsrJob(4u)==0u;
                wsrStore(0u,vec3(8,4,2),vec3(0));vec3 blocked=wsrCorner(0u,confidence);
                wsr.words[8]=1u;vec3 restored=wsrCorner(0u,confidence);
                wsr.words[OUTPUT+11u]=(jobs&&length(blocked)<0.001&&distance(restored,vec3(8,4,2))<0.001)?1u:0u;
            }
            """;
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize();
        try{Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            long result=Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_glsl_compute_shader,"world-surface","main",options);
            try{if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
                if(args.length>0){var data=Shaderc.shaderc_result_get_bytes(result);byte[] code=new byte[data.remaining()];data.get(code);
                    Files.write(Path.of(args[0]+".spv"),code);Files.write(Path.of(args[0]+".seed"),seed.array());Files.write(Path.of(args[0]+".expected"),expected.array());}}
            finally{Shaderc.shaderc_result_release(result);}
        }finally{Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
        System.out.println("World surface radiance publication/interpolation shader compiled; GPU dump supported");
    }
}
