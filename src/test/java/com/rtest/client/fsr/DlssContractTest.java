package com.rtest.client.fsr;

import java.lang.foreign.*;
import java.nio.charset.StandardCharsets;
import org.lwjgl.util.shaderc.Shaderc;

/** Executes the packaged ABI and compiles the actual guide conversion shaders. */
public final class DlssContractTest {
    public static void main(String[] args) throws Exception {
        if(RtestUpscalerMode.fromId("DLSS_RR")!=RtestUpscalerMode.DLSS_RR ||
            RtestUpscalerMode.fromId("invalid")!=RtestUpscalerMode.FSR) throw new AssertionError("Backend selection");
        if(!RtestDenoiserMode.DLSS_RR.needsGuides() || RtestDenoiserMode.DLSS_RR.shaderSignal()<=1.5f)
            throw new AssertionError("RR must produce raw guides without selecting NRD");
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            for(String name:new String[]{"dlss_prepare","dlss_finish"}) {
                String source;
                try(var in=DlssContractTest.class.getResourceAsStream("/prime/shaders/"+name+".comp")) {
                    if(in==null) throw new AssertionError("Missing shader "+name);
                    source=new String(in.readAllBytes(),StandardCharsets.UTF_8);
                }
                long result=Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_glsl_compute_shader,name,"main",options);
                try {
                    if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                        throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
                    System.out.println(name+" SPIR-V bytes="+Shaderc.shaderc_result_get_length(result));
                } finally { Shaderc.shaderc_result_release(result); }
            }
        } finally { Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler); }
        if(System.getProperty("os.name").startsWith("Windows")) {
            DlssRuntime.bootstrap();
            if((int)DlssRuntime.invoke("abi")!=1 || (int)DlssRuntime.invoke("capabilities")!=0)
                throw new AssertionError("Packaged native ABI / capability gating failed");
            try(Arena arena=Arena.ofConfined()) {
                var result=arena.allocate(8,8);
                if((int)DlssRuntime.invoke("size",1,1920,1080,1,result)==0)
                    throw new AssertionError("Must reject extent queries before device attachment");
                if((int)DlssRuntime.invoke("create",1,1920,1080,1,1280,720,result)==0 || result.get(ValueLayout.ADDRESS,0).address()!=0)
                    throw new AssertionError("Must not expose a fake DLSS owner before device attachment");
            }
            DlssRuntime.shutdown();
        }
        System.out.println("DLSS packaged native ABI, pre-device rejection, and guide shaders passed");
    }
}
