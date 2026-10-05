package com.rtest.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Maps immutable observations by world identity, never by current draw-buffer offset. */
final class WorldCacheMigration {
    record Copy(int source, int destination, int count) { }

    static List<Copy> surfaces(RayTracingScene.SceneGeometry old, RayTracingScene.SceneGeometry next) {
        if (old == null) return List.of();
        if (old.sections.isEmpty() || next.sections.isEmpty()) {
            return PersistentRtPolicy.sameStaticScene(old, next)
                ? List.of(new Copy(0, 0, old.vertices.length / 3)) : List.of();
        }
        var previous = new HashMap<RayTracingTerrainLod.NodeKey, Integer>();
        var sections = new HashMap<RayTracingTerrainLod.NodeKey, RayTracingScene.SceneGeometry.SectionGeometry>();
        int offset = 0;
        for (var section : old.sections) {
            previous.put(section.terrainNodeKey, offset);
            sections.put(section.terrainNodeKey, section);
            offset += section.triangleCount * 3;
        }
        var result = new ArrayList<Copy>();
        offset = 0;
        for (var section : next.sections) {
            var before = sections.get(section.terrainNodeKey);
            if (before != null && PersistentRtPolicy.sameSections(List.of(before), List.of(section)))
                result.add(new Copy(previous.get(section.terrainNodeKey), offset, section.triangleCount * 3));
            offset += section.triangleCount * 3;
        }
        return result;
    }

    static List<Copy> probes(WorldIrradianceField.Grid old, WorldIrradianceField.Grid next) {
        if (old == null || old.generation() != next.generation() || old.spacing() != next.spacing()) return List.of();
        double dx = (next.x()-old.x()) / old.spacing(), dy = (next.y()-old.y()) / old.spacing(), dz = (next.z()-old.z()) / old.spacing();
        if (dx != Math.rint(dx) || dy != Math.rint(dy) || dz != Math.rint(dz)) return List.of();
        int ox=(int)dx, oy=(int)dy, oz=(int)dz;
        var result = new ArrayList<Copy>();
        for (int z=0;z<next.nz();z++) for (int y=0;y<next.ny();y++) {
            int x=Math.max(0,-ox), end=Math.min(next.nx(),old.nx()-ox);
            if (y+oy>=0 && y+oy<old.ny() && z+oz>=0 && z+oz<old.nz() && end>x)
                result.add(new Copy(old.index(x+ox,y+oy,z+oz),next.index(x,y,z),end-x));
        }
        return result;
    }
    private WorldCacheMigration() { }
}
