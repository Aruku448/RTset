// Independent incident-radiance field. One exclusive worker per probe; never write the display snapshot.
// All points are absolute world coordinates in the same origin convention as the grid header.
#ifndef WORLD_IRRADIANCE_BINDING
#define WORLD_IRRADIANCE_BINDING 42
#endif
layout(set=0,binding=WORLD_IRRADIANCE_BINDING,std430) buffer WorldIrradianceBuffer { uint words[]; } wif;
const uint WIF_HEADER_WORDS=16u, WIF_ROW_WORDS=320u;
void wifBasis(vec3 d, out float y[9]) {
    y[0]=0.2820947918; y[1]=0.4886025119*d.y; y[2]=0.4886025119*d.z; y[3]=0.4886025119*d.x;
    y[4]=1.0925484306*d.x*d.y; y[5]=1.0925484306*d.y*d.z;
    y[6]=0.3153915653*(3.0*d.z*d.z-1.0); y[7]=1.0925484306*d.x*d.z;
    y[8]=0.5462742153*(d.x*d.x-d.y*d.y);
}
uvec3 wifDimensions() { return uvec3(wif.words[4],wif.words[5],wif.words[6]); }
uint wifProbeCount() { uvec3 n=wifDimensions(); return n.x*n.y*n.z; }
uint wifIndex(uvec3 p) { uvec3 n=wifDimensions(); return p.x+n.x*(p.y+n.y*p.z); }
vec3 wifOrigin() { return vec3(uintBitsToFloat(wif.words[0]),uintBitsToFloat(wif.words[1]),uintBitsToFloat(wif.words[2])); }
vec3 wifProbePosition(uint id) {
    uvec3 n=wifDimensions();
    return wifOrigin()+vec3(id%n.x,(id/n.x)%n.y,id/(n.x*n.y))*uintBitsToFloat(wif.words[3]);
}
uint wifDirectionBin(vec3 d){
    d/=abs(d.x)+abs(d.y)+abs(d.z);vec2 p=d.xy;
    if(d.z<0.0)p=(1.0-abs(p.yx))*mix(vec2(-1),vec2(1),greaterThanEqual(p,vec2(0)));
    uvec2 cell=uvec2(clamp(floor((p*.5+.5)*8.0),vec2(0),vec2(7)));
    return cell.x+8u*cell.y;
}
uint wifDistanceBin(vec3 d) {
    vec3 a=abs(d); uint axis=a.x>=a.y && a.x>=a.z ? 0u : (a.y>=a.z ? 1u : 2u);
    return 2u*axis+(d[int(axis)]<0.0 ? 1u : 0u);
}
// Monotonic sequence is separate from the bounded averaging count; never freeze sampling at 4096.
uint wifSampleIndex(uint id) {
    uint row=wif.words[13]+id*WIF_ROW_WORDS;
    return wif.words[row]==wif.words[7] ? wif.words[row+49u] : 0u;
}
vec3 wifUniformSphere(vec2 u) {
    float z=1.0-2.0*u.x, r=sqrt(max(0.0,1.0-z*z)), p=6.28318530718*u.y;
    return vec3(r*cos(p),r*sin(p),z);
}
// Call only with raw Li from a probe-origin path, never albedo*f/pdf weighted receiver output.
// Li can represent a named contribution (GI/sky/static light) if each contribution has its own field.
// Misses are VALID Li observations; pass maxTraceDistance as their distance, not zero.
// Distance moments currently require uniform-sphere sampling; arbitrary PDFs only correct the SH projection.
bool wifAccumulate(uint id, vec3 direction, vec3 incoming, float pdf, float firstDistance) {
    if(wif.words[12]==0u || id>=wifProbeCount() || pdf<=0.0 || isnan(pdf) || isinf(pdf)
       || any(isnan(incoming)) || any(isinf(incoming)) || any(lessThan(incoming,vec3(0)))
       || any(isnan(direction)) || any(isinf(direction)) || abs(dot(direction,direction)-1.0)>0.001
       || isnan(firstDistance) || isinf(firstDistance) || firstDistance<0.0) return false;
    float y[9]; wifBasis(direction,y);
    for(int k=0;k<9;k++) {
        vec3 sampleValue=incoming*(y[k]/pdf);
        if(any(isnan(sampleValue)) || any(isinf(sampleValue))) return false;
    }
    uint row=wif.words[14]+id*WIF_ROW_WORDS;
    if(wif.words[row]!=wif.words[7]) {
        for(uint j=0u;j<WIF_ROW_WORDS;j++) wif.words[row+j]=0u;
        wif.words[row]=wif.words[7];
    }
    uint count=wif.words[row+1u];
    // Bounded EMA after 4096 samples avoids overflow and allows slow lighting changes within a generation.
    float rate=1.0/float(min(count,4095u)+1u);
    for(uint k=0u;k<9u;k++) for(uint c=0u;c<3u;c++) {
        uint at=row+4u+3u*k+c;
        float old=uintBitsToFloat(wif.words[at]);
        wif.words[at]=floatBitsToUint(mix(old,incoming[int(c)]*y[int(k)]/pdf,rate));
    }
    uint directional=row+64u+4u*wifDirectionBin(direction),directions=wif.words[directional+3u];
    float directionRate=1.0/float(min(directions,63u)+1u);
    for(uint c=0u;c<3u;c++)wif.words[directional+c]=floatBitsToUint(mix(uintBitsToFloat(wif.words[directional+c]),incoming[int(c)],directionRate));
    wif.words[directional+3u]=min(directions+1u,64u);
    uint bin=wifDistanceBin(direction), dc=row+43u+bin, dm=row+31u+2u*bin;
    float dr=1.0/float(min(wif.words[dc],4095u)+1u);
    float distance=min(firstDistance,uintBitsToFloat(wif.words[11]));
    wif.words[dm]=floatBitsToUint(mix(uintBitsToFloat(wif.words[dm]),distance,dr));
    wif.words[dm+1u]=floatBitsToUint(mix(uintBitsToFloat(wif.words[dm+1u]),distance*distance,dr));
    wif.words[dc]=min(wif.words[dc]+1u,4096u);
    wif.words[row+2u]=wif.words[10];
    wif.words[row+49u]+=1u;
    memoryBarrierBuffer();
    wif.words[row+1u]=min(count+1u,4096u);
    return true;
}
vec3 wifEvaluate(uint row, vec3 normal) {
    float y[9]; wifBasis(normal,y); vec3 e=vec3(0);
    for(uint k=0u;k<9u;k++) {
        float a=k==0u ? 3.14159265359 : (k<4u ? 2.09439510239 : 0.78539816339);
        uint at=row+4u+3u*k;
        e+=vec3(uintBitsToFloat(wif.words[at]),uintBitsToFloat(wif.words[at+1u]),uintBitsToFloat(wif.words[at+2u]))*(a*y[int(k)]);
    }
    return max(e,vec3(0));
}
float wifVisibility(uint row, vec3 offset) {
    float distance=length(offset); if(distance<0.0001) return 1.0;
    uint bin=wifDistanceBin(offset); if(wif.words[row+43u+bin]<2u) return 0.0;
    uint at=row+31u+2u*bin;
    float mean=uintBitsToFloat(wif.words[at]), moment=uintBitsToFloat(wif.words[at+1u]);
    if(distance<=mean) return 1.0;
    float variance=max(moment-mean*mean,0.0001), delta=distance-mean;
    float p=variance/(variance+delta*delta); return p*p*p;
}
// Returns a confidence-weighted irradiance. Caller blends with its explicitly named cheap fallback:
// E = mix(fallbackE, irradiance, confidence); Lambert = albedo * E / PI.
// Six directional moments reduce leakage, but are not an exact visibility test or a thin-wall guarantee.
bool wifQuery(vec3 position, vec3 normal, out vec3 irradiance, out float confidence) {
    irradiance=vec3(0); confidence=0.0;
    if(wif.words[12]==0u || any(isnan(position)) || any(isinf(position))
       || any(isnan(normal)) || any(isinf(normal)) || dot(normal,normal)<0.0001) return false;
    vec3 grid=(position-wifOrigin())/uintBitsToFloat(wif.words[3]); uvec3 dims=wifDimensions();
    if(any(lessThan(grid,vec3(0))) || any(greaterThan(grid,vec3(dims)-vec3(1)))) return false;
    uvec3 cell=min(uvec3(floor(grid)),dims-uvec3(2)); vec3 fraction=grid-vec3(cell);
    normal=normalize(normal);
    float total=0.0;
    for(uint corner=0u;corner<8u;corner++) {
        uvec3 bits=uvec3(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u), coord=cell+bits;
        uint id=wifIndex(coord), row=wif.words[13]+id*WIF_ROW_WORDS;
        uint count=wif.words[row+1u];
        if(wif.words[row]!=wif.words[7] || count<wif.words[8]
           || (wif.words[10]-wif.words[row+2u])>wif.words[9]) continue;
        vec3 axes=mix(vec3(1)-fraction,fraction,vec3(bits));
        float weight=axes.x*axes.y*axes.z;
        weight*=wifVisibility(row,position-wifProbePosition(id));
        float maturity=min(float(count)/float(max(1u,wif.words[8])*4u),1.0);
        weight*=maturity;
        irradiance+=weight*wifEvaluate(row,normal); total+=weight;
    }
    if(total<=0.00001) return false;
    irradiance/=total; confidence=clamp(total,0.0,1.0); return true;
}

// Directional incident Li, distinct from cosine-convolved irradiance. Never multiply by albedo here.
vec3 wifEvaluateRadiance(uint row,vec3 direction,float roughness){
    float y[9];wifBasis(direction,y);vec3 li=vec3(0);float lowBand=exp(-2.0*roughness*roughness),highBand=exp(-6.0*roughness*roughness);
    for(uint k=0u;k<9u;k++){uint at=row+4u+3u*k;float bandWeight=(k==0u?1.0:(k<4u?lowBand:highBand));
        li+=vec3(uintBitsToFloat(wif.words[at]),uintBitsToFloat(wif.words[at+1u]),uintBitsToFloat(wif.words[at+2u]))*y[int(k)]*bandWeight;}
    uint at=row+64u+4u*wifDirectionBin(direction);uint count=wif.words[at+3u];
    vec3 bin=vec3(uintBitsToFloat(wif.words[at]),uintBitsToFloat(wif.words[at+1u]),uintBitsToFloat(wif.words[at+2u]));
    return max(mix(li,bin,(1.0-roughness)*min(float(count)/4.0,1.0)),vec3(0));
}
bool wifQueryRadiance(vec3 position,vec3 direction,float roughness,out vec3 radiance,out float confidence){
    radiance=vec3(0);confidence=0.0;if(wif.words[12]==0u)return false;
    vec3 grid=(position-wifOrigin())/uintBitsToFloat(wif.words[3]);uvec3 dims=wifDimensions();
    if(any(lessThan(grid,vec3(0)))||any(greaterThan(grid,vec3(dims)-1.0)))return false;
    uvec3 cell=min(uvec3(floor(grid)),dims-uvec3(2));vec3 fraction=grid-vec3(cell);direction=normalize(direction);
    float total=0.0;
    for(uint corner=0u;corner<8u;corner++){
        uvec3 bits=uvec3(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);uint id=wifIndex(cell+bits),row=wif.words[13]+id*WIF_ROW_WORDS;
        uint count=wif.words[row+1u];if(wif.words[row]!=wif.words[7]||count<wif.words[8]||(wif.words[10]-wif.words[row+2u])>wif.words[9])continue;
        vec3 axes=mix(1.0-fraction,fraction,vec3(bits));float weight=axes.x*axes.y*axes.z*wifVisibility(row,position-wifProbePosition(id))*min(float(count)/float(max(1u,wif.words[8])*4u),1.0);
        radiance+=weight*wifEvaluateRadiance(row,direction,roughness);total+=weight;
    }
    if(total<=1e-5)return false;radiance/=total;confidence=clamp(total,0.0,1.0);return true;
}

// One spatial gather for diffuse E and directional Li: shared coefficients/visibility,
// with SH bases and roughness band weights evaluated once per displayed fragment.
bool wifQuerySurface(vec3 position,vec3 normal,vec3 direction,float roughness,out vec3 irradiance,out vec3 radiance,out float confidence){
    irradiance=vec3(0);radiance=vec3(0);confidence=0.0;
    if(wif.words[12]==0u||any(isnan(position))||any(isinf(position))||dot(normal,normal)<1e-8||dot(direction,direction)<1e-8)return false;
    vec3 grid=(position-wifOrigin())/uintBitsToFloat(wif.words[3]);uvec3 dims=wifDimensions();
    if(any(lessThan(grid,vec3(0)))||any(greaterThan(grid,vec3(dims)-1.0)))return false;
    uvec3 cell=min(uvec3(floor(grid)),dims-uvec3(2));vec3 fraction=grid-vec3(cell);
    float yn[9],yr[9];wifBasis(normalize(normal),yn);wifBasis(normalize(direction),yr);
    float lowBand=exp(-2.0*roughness*roughness),highBand=exp(-6.0*roughness*roughness);
    for(uint k=0u;k<9u;k++){yn[k]*=k==0u?3.14159265359:(k<4u?2.09439510239:0.78539816339);yr[k]*=k==0u?1.0:(k<4u?lowBand:highBand);}
    uint directionBin=wifDirectionBin(normalize(direction));float total=0.0;
    for(uint corner=0u;corner<8u;corner++){
        uvec3 bits=uvec3(corner&1u,(corner>>1u)&1u,(corner>>2u)&1u);uint id=wifIndex(cell+bits),row=wif.words[13]+id*WIF_ROW_WORDS;
        uint count=wif.words[row+1u];if(wif.words[row]!=wif.words[7]||count<wif.words[8]||(wif.words[10]-wif.words[row+2u])>wif.words[9])continue;
        vec3 axes=mix(1.0-fraction,fraction,vec3(bits));float weight=axes.x*axes.y*axes.z*wifVisibility(row,position-wifProbePosition(id))*min(float(count)/float(max(1u,wif.words[8])*4u),1.0);
        vec3 e=vec3(0),li=vec3(0);
        for(uint k=0u;k<9u;k++){uint at=row+4u+3u*k;vec3 coefficient=vec3(uintBitsToFloat(wif.words[at]),uintBitsToFloat(wif.words[at+1u]),uintBitsToFloat(wif.words[at+2u]));e+=coefficient*yn[k];li+=coefficient*yr[k];}
        uint at=row+64u+4u*directionBin;vec3 bin=vec3(uintBitsToFloat(wif.words[at]),uintBitsToFloat(wif.words[at+1u]),uintBitsToFloat(wif.words[at+2u]));
        li=mix(li,bin,(1.0-roughness)*min(float(wif.words[at+3u])/4.0,1.0));
        irradiance+=weight*max(e,vec3(0));radiance+=weight*max(li,vec3(0));total+=weight;
    }
    if(total<=1e-5)return false;irradiance/=total;radiance/=total;confidence=clamp(total,0.0,1.0);return true;
}
