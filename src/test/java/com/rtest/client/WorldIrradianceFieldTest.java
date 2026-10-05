package com.rtest.client;

import org.lwjgl.util.shaderc.Shaderc;

/** Numerical transport checks: constant skies, directional energy, PDF weighting and visibility. */
public final class WorldIrradianceFieldTest {
    public static void main(String[] args) throws Exception {
        var grid=new WorldIrradianceField.Grid(-8,-16,24,8,3,4,5,2,4,2000,256);
        if(grid.count()!=60 || grid.bytes()!=64+60*WorldIrradianceField.ROW_WORDS*4*2) fail("buffer ABI");
        var b=grid.initialize(-100);
        if(b.getInt(28)!=2 || b.getInt(52)!=16 || b.getInt(56)!=16+60*WorldIrradianceField.ROW_WORDS || b.getInt(40)!=-100) fail("header ABI");
        for(int i=64;i<b.capacity();i++) if(b.get(i)!=0) fail("unobserved field contains lighting");
        for(int iz=0;iz<5;iz++)for(int iy=0;iy<4;iy++)for(int ix=0;ix<3;ix++) {
            float[] p=grid.position(grid.index(ix,iy,iz));
            if(p[0]!=-8+ix*8 || p[1]!=-16+iy*8 || p[2]!=24+iz*8) fail("grid coordinate round trip");
        }
        int count=32768;
        double[][] directions=new double[count][3], radiance=new double[count][3];double[] pdfs=new double[count];
        for(int i=0;i<count;i++) {
            double z=1-2*(i+0.5)/count, phi=i*2.399963229728653, r=Math.sqrt(1-z*z);
            directions[i]=new double[]{r*Math.cos(phi),r*Math.sin(phi),z};
            radiance[i]=new double[]{2,3,4}; pdfs[i]=1/(4*Math.PI);
        }
        double[][] sh=WorldIrradianceField.project(directions,radiance,pdfs);
        for(double[] n:new double[][]{{1,0,0},{0,1,0},{0,0,1},{0,0,-1},{0.6,0.8,0}}) {
            double[] e=WorldIrradianceField.irradiance(sh,n);
            for(int c=0;c<3;c++) near(e[c],(2+c)*Math.PI,1e-5,"constant incident radiance must integrate to PI*L");
        }
        for(int i=0;i<count;i++)radiance[i]=new double[]{2+directions[i][2],0,0};
        sh=WorldIrradianceField.project(directions,radiance,pdfs);
        for(double[] n:new double[][]{{0,0,1},{0,0,-1},{1,0,0}}) {
            double[] e=WorldIrradianceField.irradiance(sh,n);
            near(e[0],2*Math.PI+2*Math.PI*n[2]/3,1e-5,"directional cosine convolution");
            near(e[1],0,1e-10,"no RGB cross-channel energy");
        }
        // View and material are intentionally absent; receiver albedo is applied ONCE by the display.
        double[] e=WorldIrradianceField.irradiance(sh,new double[]{0,0,1});
        double expected=0.25*(2+2.0/3);
        near(0.25*e[0]/Math.PI,expected,1e-5,"Lambert energy factor");
        double[][] sample={{0,0,1}}, value={{1,2,3}};
        double[][] a=WorldIrradianceField.project(sample,value,new double[]{0.25});
        double[][] half=WorldIrradianceField.project(sample,value,new double[]{0.5});
        for(int k=0;k<9;k++)for(int c=0;c<3;c++)near(a[k][c],2*half[k][c],1e-12,"Li/pdf projection");
        near(WorldIrradianceField.visibility(2,4,16),1,0,"visible probe neighborhood");
        if(WorldIrradianceField.visibility(8,4,16)>1e-10)fail("bounded-distance occlusion rejection");
        if(WorldIrradianceField.visibility(8,4,32)<=WorldIrradianceField.visibility(8,4,16))fail("variance visibility");
        boolean rejected=false;
        try{WorldIrradianceField.project(sample,new double[][]{{Double.NaN,0,0}},new double[]{0.25});}
        catch(IllegalArgumentException expectedFailure){rejected=true;}
        if(!rejected)fail("invalid radiance accepted");
        if(!WorldIrradianceField.GLSL.contains("wif.words[row+49u]+=1u;") || !WorldIrradianceField.GLSL.contains("uint wifSampleIndex(uint id)")) fail("unbounded sampling sequence");
        compile(false);compile(true);
        if(args.length>0) dumpGpuFixture(args[0]);
        System.out.println("World irradiance ABI, SH energy, PDF, distance moments and GPU shader compilation passed");
    }
    static void compile(boolean fragment) {
        String source="#version 460\n#define WORLD_IRRADIANCE_BINDING 0\n"+WorldIrradianceField.GLSL;
        source+=fragment ? "\nlayout(location=0) out vec4 color;void main(){vec3 e;float confidence;wifQuery(vec3(0),vec3(0,1,0),e,confidence);color=vec4(e,confidence);}\n"
            : "\nlayout(local_size_x=1) in;void main(){uint id=gl_GlobalInvocationID.x;if(id>=wifProbeCount())return;vec3 d=wifUniformSphere(vec2(0.25,0.75));wifAccumulate(id,d,vec3(2),0.07957747155,256);}\n";
        long compiler=Shaderc.shaderc_compiler_initialize(), options=Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            long result=Shaderc.shaderc_compile_into_spv(compiler,source,fragment?Shaderc.shaderc_glsl_fragment_shader:Shaderc.shaderc_glsl_compute_shader,"world_irradiance","main",options);
            try{if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)
                throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));}
            finally{Shaderc.shaderc_result_release(result);}
        } finally {Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
    }
    static void dumpGpuFixture(String prefix) throws Exception {
        var grid=new WorldIrradianceField.Grid(0,0,0,8,2,2,2,7,4,30000,256);
        var init=grid.initialize(100);
        int output=init.capacity()/4;
        var seed=java.nio.ByteBuffer.allocate(init.capacity()+48).order(java.nio.ByteOrder.nativeOrder());
        seed.put(init); seed.position(0);
        var expected=java.nio.ByteBuffer.allocate(seed.capacity()).order(java.nio.ByteOrder.nativeOrder());
        expected.put(seed.duplicate()); expected.putInt(output*4,1).putInt((output+1)*4,1);
        expected.putFloat((output+2)*4,(float)(2*Math.PI)).putFloat((output+3)*4,(float)(3*Math.PI)).putFloat((output+4)*4,(float)(4*Math.PI));
        for(int i=5;i<12;i++) expected.putInt((output+i)*4,1);
        String source="#version 460\n#define WORLD_IRRADIANCE_BINDING 0\n"+WorldIrradianceField.GLSL+"\nlayout(local_size_x=128) in;layout(push_constant) uniform Work{uint first;uint count;uint phase;}work;\n"
            + "const uint OUTPUT="+output+"u;void main(){uint id=gl_GlobalInvocationID.x;if(work.phase==0u){if(id>=8u)return;for(uint k=0u;k<96u;k++){uint axis=k%6u;vec3 d=vec3(0);d[int(axis/2u)]=(axis%2u==0u)?1.0:-1.0;wifAccumulate(id,d,vec3(2,3,4),0.07957747155,10.0);}return;}if(id!=0u)return;vec3 e;float confidence;if(work.phase==1u){wif.words[OUTPUT]=wifQuery(vec3(0),vec3(0,1,0),e,confidence)?0u:1u;return;}uint old=wif.words[13];wif.words[13]=wif.words[14];bool ok=wifQuery(vec3(0),vec3(0,1,0),e,confidence);wif.words[OUTPUT+1u]=(ok&&confidence>0.999)?1u:0u;wif.words[OUTPUT+2u]=floatBitsToUint(e.x);wif.words[OUTPUT+3u]=floatBitsToUint(e.y);wif.words[OUTPUT+4u]=floatBitsToUint(e.z);uint row=wif.words[14];wif.words[OUTPUT+5u]=wifSampleIndex(0u)==96u?1u:0u;wif.words[row+1u]=4096u;wif.words[row+49u]=5000u;wifAccumulate(0u,vec3(1,0,0),vec3(2,3,4),0.07957747155,10.0);wif.words[OUTPUT+6u]=(wif.words[row+1u]==4096u&&wifSampleIndex(0u)==5001u)?1u:0u;uint sequence=wif.words[row+49u];bool rejected=!wifAccumulate(0u,vec3(1,0,0),vec3(uintBitsToFloat(0x7fc00000u)),0.07957747155,10.0);wif.words[OUTPUT+7u]=(rejected&&sequence==wif.words[row+49u])?1u:0u;wif.words[OUTPUT+8u]=!wifQuery(vec3(-1,0,0),vec3(0,1,0),e,confidence)?1u:0u;wif.words[10]=30101u;bool expired=!wifQuery(vec3(0),vec3(0,1,0),e,confidence);wif.words[9]=0u;wif.words[OUTPUT+9u]=(expired&&wifQuery(vec3(0),vec3(0,1,0),e,confidence))?1u:0u;wif.words[9]=30000u;wif.words[10]=100u;wif.words[7]=8u;wif.words[OUTPUT+10u]=!wifQuery(vec3(0),vec3(0,1,0),e,confidence)?1u:0u;wif.words[7]=7u;for(uint q=0u;q<8u;q++)for(uint bin=0u;bin<6u;bin++)wif.words[wif.words[14]+q*WIF_ROW_WORDS+43u+bin]=0u;wif.words[OUTPUT+11u]=!wifQuery(vec3(0.5,0,0),vec3(0,1,0),e,confidence)?1u:0u;vec3 positive=wifEvaluateRadiance(row,vec3(1,0,0),0.0);bool constantLi=distance(positive,vec3(2,3,4))<0.001;uint hot=row+64u+4u*wifDirectionBin(vec3(1,0,0)),cold=row+64u+4u*wifDirectionBin(vec3(-1,0,0));wif.words[hot]=floatBitsToUint(4.0);wif.words[hot+1u]=0u;wif.words[hot+2u]=0u;wif.words[hot+3u]=4u;wif.words[cold]=0u;wif.words[cold+1u]=0u;wif.words[cold+2u]=floatBitsToUint(8.0);wif.words[cold+3u]=4u;bool directional=distance(wifEvaluateRadiance(row,vec3(1,0,0),0.0),vec3(4,0,0))<0.001&&distance(wifEvaluateRadiance(row,vec3(-1,0,0),0.0),vec3(0,0,8))<0.001;vec3 combinedE,combinedLi;float combinedConfidence;bool combined=wifQuerySurface(vec3(0),vec3(0,1,0),vec3(1,0,0),0.0,combinedE,combinedLi,combinedConfidence)&&distance(combinedE,vec3(2,3,4)*3.14159265359*(1.0-0.625/4096.0))<0.001&&distance(combinedLi,vec3(4,0,0))<0.001&&combinedConfidence>0.99;wif.words[OUTPUT+11u]&=(constantLi&&directional&&combined)?1u:0u;wif.words[13]=old;}";
        long compiler=Shaderc.shaderc_compiler_initialize(),options=Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,Shaderc.shaderc_target_env_vulkan,Shaderc.shaderc_env_version_vulkan_1_2);
            long result=Shaderc.shaderc_compile_into_spv(compiler,source,Shaderc.shaderc_glsl_compute_shader,"world_irradiance_gpu","main",options);
            try {
                if(Shaderc.shaderc_result_get_compilation_status(result)!=Shaderc.shaderc_compilation_status_success)throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
                var code=Shaderc.shaderc_result_get_bytes(result);byte[] bytes=new byte[code.remaining()];code.get(bytes);
                java.nio.file.Files.write(java.nio.file.Path.of(prefix+".spv"),bytes);
            } finally {Shaderc.shaderc_result_release(result);}
        } finally {Shaderc.shaderc_compile_options_release(options);Shaderc.shaderc_compiler_release(compiler);}
        java.nio.file.Files.write(java.nio.file.Path.of(prefix+".seed"),seed.array());
        java.nio.file.Files.write(java.nio.file.Path.of(prefix+".expected"),expected.array());
    }
    static void near(double actual,double expected,double epsilon,String message){if(Math.abs(actual-expected)>epsilon)throw new AssertionError(message+": "+actual+" != "+expected);}
    static void fail(String message){throw new AssertionError(message);}
}
