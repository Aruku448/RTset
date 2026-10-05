#ifndef WORLD_SURFACE_BINDING
#define WORLD_SURFACE_BINDING 43
#endif
layout(set=0,binding=WORLD_SURFACE_BINDING,std430) buffer WorldSurfaceBuffer {uint words[];} wsr;
// Header: vertex count, generation, published bank, training bank, cursor, enabled.
// Eight words per triangle corner: generation/count/sequence, transported sunlight RGB.
const uint WSR_HEADER=16u,WSR_ROW=8u;
uint wsrJob(uint launch){uint dynamics=wsr.words[7];if(launch<dynamics)return wsr.words[6]+launch;return (wsr.words[4]+launch-dynamics)%max(wsr.words[6],1u);}
uint wsrReadRow(uint id){return wsr.words[2]+id*WSR_ROW;}
vec3 wsrCorner(uint id,out float confidence){
    confidence=0.0;if(wsr.words[5]==0u||id>=wsr.words[0])return vec3(0);
    uint row=wsrReadRow(id);if(wsr.words[row]!=wsr.words[1]||(wsr.words[row+1u]&127u)==0u)return vec3(0);
    confidence=1.0;vec3 radiance=vec3(uintBitsToFloat(wsr.words[row+4u]),uintBitsToFloat(wsr.words[row+5u]),uintBitsToFloat(wsr.words[row+6u]));
    if((wsr.words[row+1u]>>7u)==(wsr.words[8]&0x1ffffffu))radiance+=vec3(unpackHalf2x16(wsr.words[row+3u]),unpackHalf2x16(wsr.words[row+7u]).x);
    return max(radiance,vec3(0));
}
vec3 wsrSun(uint triangle,vec3 barycentric,out float confidence){
    float a,b,c;vec3 x=wsrCorner(triangle*3u,a),y=wsrCorner(triangle*3u+1u,b),z=wsrCorner(triangle*3u+2u,c);
    confidence=dot(barycentric,vec3(a,b,c));return x*barycentric.x+y*barycentric.y+z*barycentric.z;
}
uint wsrSequence(uint id){uint row=wsrReadRow(id);return wsr.words[row]==wsr.words[1]?wsr.words[row+2u]:0u;}
void wsrStore(uint id,vec3 sunlight,vec3 fullLight){
    if(any(isnan(fullLight))||any(isinf(fullLight))||any(lessThan(fullLight,vec3(0)))||any(isnan(sunlight))||any(isinf(sunlight))||any(lessThan(sunlight,vec3(0))))return;
    uint row=wsr.words[3]+id*WSR_ROW;uint count=wsr.words[row]==wsr.words[1]?(wsr.words[row+1u]&127u):0u;
    float rate=id>=wsr.words[6]?1.0:1.0/float(min(count,63u)+1u);
    for(uint c=0u;c<3u;c++){float old=count==0u?0.0:uintBitsToFloat(wsr.words[row+4u+c]);wsr.words[row+4u+c]=floatBitsToUint(mix(old,sunlight[int(c)],rate));}
    vec3 delta=clamp(fullLight-sunlight,vec3(-65504),vec3(65504));
    wsr.words[row+3u]=packHalf2x16(delta.xy);wsr.words[row+7u]=packHalf2x16(vec2(delta.z,0));
    wsr.words[row+2u]=wsrSequence(id)+1u;wsr.words[row]=wsr.words[1];memoryBarrierBuffer();wsr.words[row+1u]=((wsr.words[8]&0x1ffffffu)<<7u)|min(count+1u,64u);
}

void wsrStore(uint id,vec3 sunlight){wsrStore(id,sunlight,sunlight);}
