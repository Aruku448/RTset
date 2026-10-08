#!/usr/bin/env python3
"""Run production samplePbr GLSL on Vulkan against the CPU normal-strength convention."""
from pathlib import Path
import struct, subprocess
root=Path(__file__).resolve().parents[2]
out=root/'tmp/chest-shadow-20261008/pbr-normal-probe'; out.mkdir(parents=True,exist_ok=True)
source=(root/'src/main/java/com/rtest/client/RayTracingShaderCommon.java').read_text()
body=source.split('static final String PBR_FUNCTIONS = """',1)[1].split('""";',1)[0]
shader='#version 460\nlayout(local_size_x=1) in;\nlayout(binding=0,std430) buffer Data {uint values[];} pbrData;\nstruct Camera {vec4 pbrSettings;vec4 pbrParallaxSettings;vec4 random;};\nCamera camera;\n'+body+'\nvoid main() {\n if(gl_GlobalInvocationID.x!=0u) return;\n uint failures=0u;\n uint saved=pbrData.values[11];\n for(uint r=0u;r<=255u;r+=17u) for(uint g=0u;g<=255u;g+=17u) {\n  pbrData.values[11]=0xff0000ffu|(r<<16u)|(g<<8u);\n  for(int s=0;s<4;s++) {\n   float strength=s==0?0.0:s==1?0.5:s==2?1.0:2.0;\n   camera.pbrSettings=vec4(0.0,strength,0.0,0.0);\n   camera.pbrParallaxSettings=vec4(0.0);camera.random=vec4(0.0);\n   vec3 actual=vec3(0,0,1);float roughness=1,metallic=0,f0=.04,emission=0,porosity,ao;\n   bool authored,normal,specular;\n   samplePbr(1u,vec2(.5),actual,roughness,metallic,f0,emission,porosity,ao,authored,normal,specular);\n   vec2 xy=(vec2(r,g)/127.5-1.0)*strength;\n   vec3 expected=normalize(vec3(xy,sqrt(max(1.0-dot(xy,xy),0.0))));\n   if(any(isnan(actual))||any(isinf(actual))||any(greaterThan(abs(actual-expected),vec3(1e-5)))) failures++;\n  }\n }\n pbrData.values[11]=saved;pbrData.values[12]=failures;\n}\n'
raygen=(root/'src/main/java/com/rtest/client/RayTracingShaderRaygen.java').read_text()
if 'pbrOrientSurfaceNormal(' in body:
    orientation='vec3 actual=pbrOrientSurfaceNormal(face,mapped,ray);'
else:
    start=raygen.index('vec3 geometricNormal = normalize(pathNormal.xyz);')
    end=raygen.index('// The ray state',start)
    orientation='vec4 pathNormal=vec4(mapped,0); vec3 rayDirection=ray;'+raygen[start:end]+'vec3 actual=normal;'
check='for(int side=0;side<2;side++) {vec3 face=vec3(0,0,1),mapped=vec3(.8,0,.6);vec3 ray=normalize(vec3(side==0?-.99:.99,0,-.1));'+orientation+'if(actual.z<0.0) failures++;}'
shader=shader.replace('pbrData.values[11]=saved;',check+'pbrData.values[11]=saved;')
(out/'probe.comp').write_text(shader)
# Reuse the reviewed standalone Vulkan buffer/dispatch/readback setup, with one invocation.
c=(root/'tools/profiling/instance_write_probe.c').read_text()
start=c.index('    memset(mapped,0xcd,seed_bytes);'); end=c.index('    VkShaderModuleCreateInfo',start)
c=c[:start]+'    memcpy(mapped,seed,seed_bytes);\n'+c[end:]
start=c.index('    uint32_t n=seed[1];dispatch(cmd,layout,n-1,n,0);'); end=c.index('    barrier.dstAccessMask=VK_ACCESS_HOST_READ_BIT;',start)
c=c[:start]+'    uint32_t n=1;dispatch(cmd,layout,0,1,0);\n    barrier.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;\n'+c[end:]
c=c.replace('seed_bytes<32 || seed[1]<1024','seed_bytes<52')
c=c.replace('for(int iteration=-5;iteration<31;iteration++)','for(int iteration=0;iteration<31;iteration++)')
c=c.replace('GPU light tree on %s: emitters=%u nodes=%u compared_words=%zu mismatches=%zu', 'PBR normal/face orientation on %s: invocations=%u fixtures=%u compared_words=%zu mismatches=%zu')
(out/'probe.c').write_text(c)
words=[1,11,0xffffffff,1,1,0,0,0,0x3f800000,0,0x3f800000,0xff8080ff,0]
for name in ['seed','expected']: (out/name).write_bytes(struct.pack('<13I',*words))
subprocess.run(['glslangValidator','-V','--target-env','vulkan1.2',str(out/'probe.comp'),'-o',str(out/'probe.spv')],check=True)
subprocess.run(['cc','-O2',str(out/'probe.c'),'-o',str(out/'probe'),'-lvulkan'],check=True)
result=subprocess.run([str(out/'probe'),str(out/'probe.spv'),str(out/'seed'),str(out/'expected')])
raise SystemExit(result.returncode)
