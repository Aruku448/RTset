package com.rtest.client;

/** Replays the over-budget AW avatar through CPU admission, material partition and cache keys. */
public final class DynamicModelChunksTest {
    public static void main(String[] args) {
        int count = DynamicEntityGeometry.DYNAMIC_MODEL_TRIANGLE_CAPACITY * 3 + 17;
        float[] vertices = new float[count*9], materials = new float[count*28];
        for(int i=0;i<vertices.length;i++) vertices[i]=i;
        for(int i=0;i<materials.length;i++) materials[i]=i;
        var mesh = new PlayerModelGeometryAdapter.Mesh(vertices,materials);
        var registry = new DynamicInstanceRegistry(2,4);
        var transform = DynamicInstanceRegistry.Transform.translation(12,34,56);
        registry.beginFrame();
        var parts = DynamicModelChunks.publish(registry,17,mesh,DynamicInstanceRegistry.Family.ENTITY,123,transform,0);
        if(parts==null || parts.size()!=4) throw new AssertionError("Complete custom avatar rejected");
        int offset=0;
        var keys=new java.util.HashSet<Long>();
        for(var part:parts) {
            if(DynamicModelChunks.owner(part.identity())!=17) throw new AssertionError("Chunk lost entity owner");
            if(!keys.add(DynamicEntityGeometry.modelBlasCacheKey(part.identity(),0,false)))
                throw new AssertionError("Chunks alias the same Vulkan BLAS cache key");
            for(int i=0;i<part.mesh().vertices().length;i++)
                if(part.mesh().vertices()[i]!=vertices[offset*9+i]) throw new AssertionError("Geometry reordered/dropped");
            for(int i=0;i<part.mesh().materialData().length;i++)
                if(part.mesh().materialData()[i]!=materials[offset*28+i]) throw new AssertionError("Material/UV/alpha rows lost");
            offset+=part.mesh().triangleCount();
        }
        var submitted = registry.finish();
        if(offset!=count || submitted.activeCount()!=4) throw new AssertionError("Partial model admitted");
        var instances = submitted.instances().stream().map(s -> new DynamicEntityGeometry.Instance(s,
            DynamicEntityGeometry.Family.LIVING_BODY,1,2,123)).toList();
        var frame = new DynamicEntityGeometry.Frame(submitted,instances,java.util.Map.of(),java.util.Map.of(),
            java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),java.util.Map.of(),
            java.util.Map.of(),java.util.Map.of(),0);
        if(frame.representedEntityCount()!=1 || !frame.representedEntityIds().equals(java.util.Set.of(17L)))
            throw new AssertionError("Parts reported as different entities");
        if(!submitted.instances().stream().allMatch(s -> s.currentTransform().equals(transform)))
            throw new AssertionError("Parts detached from owning entity transform");
        registry.beginFrame();
        if(DynamicModelChunks.publish(registry,18,mesh,DynamicInstanceRegistry.Family.ENTITY,123,transform,0)!=null)
            throw new AssertionError("Slot overflow must reject the whole avatar");
        if(registry.finish().activeCount()!=0) throw new AssertionError("Overflow left a partial model active");
        System.out.println("PASS: "+count+"-triangle avatar retained in four independent Vulkan geometry slots");
    }
}
