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
            mat4 current; mat4 previous; vec4 worldOrigin; vec4 lighting; vec4 sun; vec4 sunColor;
        } camera;
        layout(set=0,binding=5,std430) readonly buffer PreviousPositions { vec4 vertices[]; } previousPositions;
        layout(location=0) out vec2 uv;
        layout(location=1) out vec3 position;
        layout(location=2) out vec4 previousClip;
        layout(location=3) flat out uint materialIndex;
        void main() {
            vec4 vertex=positions.vertices[gl_VertexIndex];
            materialIndex=floatBitsToUint(vertex.w)*7u;
            vec4 uv01=materials.entries[materialIndex+2u];
            vec4 uv2=materials.entries[materialIndex+3u];
            uint corner=uint(gl_VertexIndex)%3u;
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
            """+field+contributions+"""
            layout(set=0,binding=2,std140) uniform Camera {
                mat4 current; mat4 previous; vec4 worldOrigin; vec4 lighting; vec4 sun; vec4 sunColor;
            } camera;
            layout(set=0,binding=4) uniform sampler2D atlas;
            layout(set=0,binding=7) uniform sampler2D staticSunDepth;
            layout(set=0,binding=8) uniform sampler2D dynamicSunDepth;
            layout(set=0,binding=9,std140) uniform SunShadow { mat4 lightMatrix; vec4 params; } shadow;
            // Compare at the shadow texel center, not the display fragment position.
            // A constant world-space epsilon cannot cover nearest-texel error on slopes.
            float sunVisibility(sampler2D depthMap,vec2 coord,float depth,vec2 gradient) {
                ivec2 size=textureSize(depthMap,0);
                ivec2 cell=clamp(ivec2(floor(coord*vec2(size))),ivec2(0),size-1);
                vec2 center=(vec2(cell)+0.5)/vec2(size);
                float receiverDepth=depth+dot(gradient,center-coord);
                return receiverDepth-shadow.params.y<=texelFetch(depthMap,cell,0).r?1.0:0.0;
            }
            WorldDirectSample sunSample(vec3 point) {
                WorldDirectSample sampleValue;
                sampleValue.sourceWeight=camera.sunColor.rgb*camera.sun.w;
                sampleValue.staticVisibility=vec3(1);sampleValue.fullVisibility=vec3(1);
                if(shadow.params.x<0.5)return sampleValue;
                vec4 clip=shadow.lightMatrix*vec4(point,1);
                vec3 p=clip.xyz/max(clip.w,0.0001);
                vec2 coord=p.xy*0.5+0.5;
                vec2 dx=dFdx(coord),dy=dFdy(coord);
                float zx=dFdx(p.z),zy=dFdy(p.z);
                float determinant=dx.x*dy.y-dx.y*dy.x;
                vec2 gradient=abs(determinant)>1e-20?
                    vec2(zx*dy.y-zy*dx.y,zy*dx.x-zx*dy.x)/determinant:vec2(0);
                if(any(lessThan(p.xy,vec2(-1)))||any(greaterThan(p.xy,vec2(1)))||p.z<0||p.z>1)return sampleValue;
                float staticV=sunVisibility(staticSunDepth,coord,p.z,gradient);
                float dynamicV=sunVisibility(dynamicSunDepth,coord,p.z,gradient);
                // Joint same-point-source visibility. GI/sky are deliberately untouched.
                sampleValue.staticVisibility=vec3(staticV);
                sampleValue.fullVisibility=vec3(staticV*dynamicV);
                return sampleValue;
            }
            layout(location=0) in vec2 uv;
            layout(location=1) in vec3 position;
            layout(location=2) in vec4 previousClip;
            layout(location=3) flat in uint materialIndex;
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
                vec3 irradiance; float confidence;
                wifQuery(position+camera.worldOrigin.xyz,n,irradiance,confidence);
                // Explicit unshadowed preview: no hidden RT fallback on immature cells.
                vec3 fallback=vec3(0.28)+vec3(0.72)*max(n.y,0.0);
                vec3 diffuse=worldLambertIrradiance(mix(fallback,irradiance,confidence),albedo);
                float sunLen2=dot(camera.sun.xyz,camera.sun.xyz);
                vec3 sunDir=sunLen2>1e-12?camera.sun.xyz*inversesqrt(sunLen2):vec3(0,1,0);
                // Current material resolves static direct and signed entity occlusion separately.
                DisplayDirectContribution solar=worldDirectAtView(sunSample(position),
                    albedo*max(dot(n,sunDir),0.0)/3.14159265359);
                diffuse+=worldDirectTotal(solar);
                sceneColor=vec4(diffuse*(1.0-clamp(surface.y,0.0,1.0))+albedo*max(surface.z,0.0),1);
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
