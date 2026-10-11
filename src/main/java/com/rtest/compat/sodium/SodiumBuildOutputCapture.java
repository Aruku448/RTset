package com.rtest.compat.sodium;

import com.rtest.client.SodiumTerrainMeshCache;

/** A sidecar owned by one Sodium build output, cleared when that output is destroyed. */
public interface SodiumBuildOutputCapture {
    void rtest$setTerrainCapture(SodiumTerrainMeshCache.Pending pending);
    SodiumTerrainMeshCache.Pending rtest$getTerrainCapture();
}
