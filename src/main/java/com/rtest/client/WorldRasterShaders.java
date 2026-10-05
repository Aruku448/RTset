package com.rtest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Vertex pulling keeps the display pipeline independent of acceleration structures. */
final class WorldRasterShaders {
    static final String VERTEX = """
        #version 450
        layout(set=0,binding=0,std430) readonly buffer Positions { vec4 vertices[]; } positions;
        layout(set=0,binding=1,std430) readonly buffer Materials { vec4 entries[]; } materials;
        layout(set=0,binding=2,std140) uniform Camera {
            mat4 current; mat4 previous; vec4 worldOrigin; vec4 lighting; vec4 sun; vec4 sunColor; vec4 eye;
        } camera;
        layout(set=0,binding=5,std430) readonly buffer PreviousPositions { vec4 vertices[]; } previousPositions;
        layout(push_constant) uniform SurfaceDraw {uint base;} surfaceDraw;
        layout(location=0) out vec2 uv;
        layout(location=1) out vec3 position;
        layout(location=2) out vec4 previousClip;
        layout(location=3) flat out uint materialIndex;
        layout(location=4) out vec3 barycentric;
        layout(location=5) flat out uint surfaceTriangle;
        void main() {
            vec4 vertex=positions.vertices[gl_VertexIndex];
            materialIndex=floatBitsToUint(vertex.w)*7u;
            vec4 uv01=materials.entries[materialIndex+2u];
            vec4 uv2=materials.entries[materialIndex+3u];
            uint corner=uint(gl_VertexIndex)%3u;
            barycentric=corner==0u?vec3(1,0,0):(corner==1u?vec3(0,1,0):vec3(0,0,1));
            surfaceTriangle=surfaceDraw.base==0xffffffffu?0xffffffffu:(surfaceDraw.base+uint(gl_VertexIndex))/3u;
            uv=corner==0u?uv01.xy:(corner==1u?uv01.zw:uv2.xy);
            position=vertex.xyz;
            previousClip=camera.previous*vec4(previousPositions.vertices[gl_VertexIndex].xyz,1.0);
            gl_Position=camera.current*vec4(position,1.0);
        }
        """;

    static String fragment() {
        String field;
        try (var stream=WorldRasterShaders.class.getResourceAsStream("/rtest/shaders/world_irradiance_field.glsl")) {
            if(stream==null) throw new IllegalStateException("Missing world irradiance shader");
            field=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException error) { throw new IllegalStateException(error); }
        String contributions;
        try (var stream=WorldRasterShaders.class.getResourceAsStream("/prime/shaders/world_lighting_contributions.glsl")) {
            if(stream==null)throw new IllegalStateException("Missing world lighting contributions");
            contributions=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException error) { throw new IllegalStateException(error); }
        return """
            #version 450
            #define WORLD_IRRADIANCE_BINDING 3
            """+field+contributions+"\n#define WORLD_SURFACE_BINDING 6\n"+WorldSurfaceRadiance.GLSL+"""
            layout(set=0,binding=2,std140) uniform Camera {
                mat4 current; mat4 previous; vec4 worldOrigin; vec4 lighting; vec4 sun; vec4 sunColor; vec4 eye;
            } camera;
            layout(set=0,binding=4) uniform sampler2D atlas;
            layout(location=0) in vec2 uv;
            layout(location=1) in vec3 position;
            layout(location=2) in vec4 previousClip;
            layout(location=3) flat in uint materialIndex;
            layout(location=4) in vec3 barycentric;
            layout(location=5) flat in uint surfaceTriangle;
            vec3 fresnelSchlick(vec3 f0,float cosine){return f0+(1.0-f0)*pow(1.0-clamp(cosine,0.0,1.0),5.0);}
            vec3 directSpecular(vec3 n,vec3 v,vec3 l,vec3 f0,float roughness){
                float nv=max(dot(n,v),0.0),nl=max(dot(n,l),0.0);if(nv<=0.0||nl<=0.0)return vec3(0);
                vec3 h=normalize(v+l);float nh=max(dot(n,h),0.0),vh=max(dot(v,h),0.0);
                float a=max(roughness*roughness,max(0.002,sin(camera.sunColor.w)*0.5)),a2=a*a;
                float denom=nh*nh*(a2-1.0)+1.0;
                float d=a2/(3.14159265359*denom*denom);
                float gv=2.0*nv/(nv+sqrt(a2+(1.0-a2)*nv*nv));
                float gl=2.0*nl/(nl+sqrt(a2+(1.0-a2)*nl*nl));
                return fresnelSchlick(f0,vh)*(d*gv*gl/max(4.0*nv,1e-6));
            }
            layout(set=0,binding=1,std430) readonly buffer Materials { vec4 entries[]; } materials;
            layout(location=0) out vec4 sceneColor;
            layout(location=1) out float reversedDepth;
            layout(location=2) out vec2 motion;
            layout(location=3) out vec4 normalRoughness;
            layout(location=4) out vec4 albedoMetallic;
            layout(location=5) out vec4 primaryPosition;
            vec3 linearColor(vec3 value) {
                return mix(value/12.92,pow((value+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),value));
            }
            void main() {
                vec4 tint=materials.entries[materialIndex];
                vec4 uvInfo=materials.entries[materialIndex+3u];
                vec4 surface=materials.entries[materialIndex+5u];
                vec4 texel=uvInfo.w>0.5?(uvInfo.z>0.5?texelFetch(atlas,clamp(ivec2(uv*textureSize(atlas,0)),ivec2(0),textureSize(atlas,0)-1),0):texture(atlas,uv)):vec4(1.0);
                if(uvInfo.z>0.5 && texel.a<0.5) discard;
                // Transmission is intentionally a solid preview in this first visibility pass.
                vec3 albedo=uvInfo.w>0.5?linearColor(texel.rgb)*linearColor(tint.rgb):tint.rgb;
                // Match the full RT working gamut: linear sRGB -> Rec.2020.
                if(uvInfo.w>0.5)albedo=mat3(0.627404,0.069097,0.016391, 0.329283,0.919540,0.088013, 0.043313,0.011362,0.895595)*albedo;
                vec3 n=materials.entries[materialIndex+1u].xyz;
                float len2=dot(n,n);
                n=len2>1e-12?n*inversesqrt(len2):vec3(0,1,0);
                if(!gl_FrontFacing)n=-n;
                vec3 v=normalize(camera.eye.xyz-position);
                vec3 irradiance,reflected;float confidence;
                wifQuerySurface(position+camera.worldOrigin.xyz,n,reflect(-v,n),clamp(surface.x,0.0,1.0),irradiance,reflected,confidence);
                // Explicit unshadowed preview: no hidden RT fallback on immature cells.
                vec3 fallback=vec3(0.28)+vec3(0.72)*max(n.y,0.0);
                vec3 diffuse=worldLambertIrradiance(mix(fallback,irradiance,confidence),albedo);
                float sunLen2=dot(camera.sun.xyz,camera.sun.xyz);
                vec3 sunDir=sunLen2>1e-12?camera.sun.xyz*inversesqrt(sunLen2):vec3(0,1,0);
                // Current material resolves static direct and signed entity occlusion separately.
                float surfaceConfidence;vec3 directLight=wsrSun(surfaceTriangle,barycentric,surfaceConfidence);
                // Untrained surfaces receive no invented direct visibility; world work fills them.
                float metallic=clamp(surface.y,0.0,1.0),roughness=clamp(surface.x,0.0,1.0);
                vec3 f0=mix(vec3(clamp(surface.w,0.04,1.0)),albedo,metallic);
                vec3 f=fresnelSchlick(f0,max(dot(n,v),0.0));
                vec3 diffuseEnergy=(1.0-f)*(1.0-metallic);
                vec3 diffuseDirect=albedo*max(dot(n,sunDir),0.0)/3.14159265359;
                vec3 glossy=directSpecular(n,v,sunDir,f0,roughness)*directLight;
                glossy+=f*reflected*confidence;
                sceneColor=vec4(diffuseEnergy*(diffuse+diffuseDirect*directLight)+glossy+albedo*max(surface.z,0.0),1);
                reversedDepth=gl_FragCoord.z;
                vec2 currentUv=(gl_FragCoord.xy+camera.lighting.zw)/camera.lighting.xy;
                vec2 previousUv=previousClip.xy/max(previousClip.w,0.0001)*vec2(0.5,0.5)+0.5;
                motion=previousClip.w>0.05?previousUv-currentUv:vec2(0);
                normalRoughness=vec4(n,clamp(surface.x,0.0,1.0));
                albedoMetallic=vec4(albedo,clamp(surface.y,0.0,1.0));
                primaryPosition=vec4(position,1);
            }
            """;
    }
}
