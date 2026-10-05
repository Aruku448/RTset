// Immutable read bank + single-writer-per-slot write bank. Host copies read -> write before RT.
// Header: enable, read base, write base, invocation epoch, refresh, clock ms, max age,
// minimum samples, slot mask, work mode, training budget, reserved, statistics gate, reserved[3].
// 16..29: sampled invocation counters; 30: exact unique reservation attempts; 31: reserved. Rows: owner epoch, exact key[10], sample count,
// diffuse RGB, specular RGB, reserved, timestamp ms, reserved[4]. All offsets are uint words.
layout(set = 0, binding = 41, std430) buffer PersistentIndirect { uint words[]; } prt;
const uint PRT_ROW_WORDS = 24u;
bool prtSampledInvocation = false;
void prtCount(uint counter) {
    if (prtSampledInvocation && prt.words[12] != 0u) atomicAdd(prt.words[16u + counter], 1u);
}
uint prtHash(uint key[10]) {
    uint h = 2166136261u;
    for (uint j = 0u; j < 10u; j++) h = (h ^ key[j]) * 16777619u;
    h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
    return h & prt.words[8];
}
uint prtDirection(vec3 v, float quantization) {
    uvec3 q = uvec3(floor(clamp(v * 0.5 + 0.5, vec3(0.0), vec3(1.0)) * quantization + 0.5));
    return q.x | (q.y << 10u) | (q.z << 20u);
}
void prtKey(vec3 position, vec3 normal, vec3 view, vec3 color, float roughness,
            float reflectivity, out uint key[10]) {
    ivec3 cell = ivec3(floor(position * 2.0));
    key[0] = uint(cell.x); key[1] = uint(cell.y); key[2] = uint(cell.z);
    key[3] = prtDirection(normal, 255.0); key[4] = prtDirection(view, 31.0);
    key[5] = floatBitsToUint(color.r); key[6] = floatBitsToUint(color.g);
    key[7] = floatBitsToUint(color.b); key[8] = floatBitsToUint(roughness);
    key[9] = floatBitsToUint(reflectivity);
}
bool prtSameKey(uint row, uint key[10]) {
    for (uint j = 0u; j < 10u; j++) if (prt.words[row + 1u + j] != key[j]) return false;
    return true;
}
bool prtFresh(uint row) {
    return prt.words[row + 11u] != 0u && prt.words[5] - prt.words[row + 19u] <= prt.words[6];
}
bool prtLookup(uint key[10], out vec3 diffuse, out vec3 specular) {
    diffuse = vec3(0.0); specular = vec3(0.0);
    if (prt.words[0] == 0u) return false;
    prtCount(0u);
    uint row = prt.words[1] + prtHash(key) * PRT_ROW_WORDS;
    if (!prtFresh(row) || prt.words[row + 11u] < prt.words[7] || !prtSameKey(row, key)) return false;
    diffuse = vec3(uintBitsToFloat(prt.words[row + 12u]), uintBitsToFloat(prt.words[row + 13u]), uintBitsToFloat(prt.words[row + 14u]));
    specular = vec3(uintBitsToFloat(prt.words[row + 15u]), uintBitsToFloat(prt.words[row + 16u]), uintBitsToFloat(prt.words[row + 17u]));
    prtCount(1u);
    return true;
}
// Reserve BEFORE tracing: a losing invocation must not perform redundant static training.
bool prtReserve(uint key[10]) {
    if (prt.words[0] == 0u || prt.words[4] == 0u) return false;
    uint row = prt.words[2] + prtHash(key) * PRT_ROW_WORDS;
    uint previousOwner = atomicAdd(prt.words[row], 0u);
    if (previousOwner == prt.words[3] || atomicCompSwap(prt.words[row], previousOwner, prt.words[3]) != previousOwner) {
        prtCount(7u); return false;
    }
    // At most slotCount atomic additions per refresh; a rejected slot keeps its old values.
    uint ticket = atomicAdd(prt.words[30], 1u);
    if (ticket >= prt.words[10]) { prtCount(11u); return false; }
    prtCount(10u); return true;
}
void prtStoreReserved(uint key[10], vec3 diffuse, vec3 specular) {
    // Called only by the invocation holding this slot's reservation.
    if (any(isnan(diffuse)) || any(isinf(diffuse)) || any(lessThan(diffuse, vec3(0.0)))
        || any(greaterThan(diffuse, vec3(8192.0))) || any(isnan(specular)) || any(isinf(specular))
        || any(lessThan(specular, vec3(0.0))) || any(greaterThan(specular, vec3(8192.0)))) {
        prtCount(8u); return;
    }
    uint slot = prtHash(key), row = prt.words[2] + slot * PRT_ROW_WORDS;
    uint readRow = prt.words[1] + slot * PRT_ROW_WORDS;
    uint n = prtFresh(readRow) && prtSameKey(readRow, key) ? min(prt.words[readRow + 11u], 15u) : 0u;
    vec3 oldDiffuse = vec3(0.0), oldSpecular = vec3(0.0);
    if (n > 0u) {
        oldDiffuse = vec3(uintBitsToFloat(prt.words[readRow + 12u]), uintBitsToFloat(prt.words[readRow + 13u]), uintBitsToFloat(prt.words[readRow + 14u]));
        oldSpecular = vec3(uintBitsToFloat(prt.words[readRow + 15u]), uintBitsToFloat(prt.words[readRow + 16u]), uintBitsToFloat(prt.words[readRow + 17u]));
    }
    diffuse = (oldDiffuse * float(n) + diffuse) / float(n + 1u);
    specular = (oldSpecular * float(n) + specular) / float(n + 1u);
    for (uint j = 0u; j < 10u; j++) prt.words[row + 1u + j] = key[j];
    for (uint j = 0u; j < 3u; j++) {
        prt.words[row + 12u + j] = floatBitsToUint(diffuse[j]);
        prt.words[row + 15u + j] = floatBitsToUint(specular[j]);
    }
    prt.words[row + 19u] = prt.words[5];
    prt.words[row + 11u] = n + 1u;
    prtCount(6u);
}

// Test harness and optional already-available samples use the same reservation protocol.
void prtTrain(uint key[10], vec3 diffuse, vec3 specular) {
    if (prtReserve(key)) prtStoreReserved(key, diffuse, specular);
}
