package com.rtest.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/** Exercises the deferred world custom-geometry seam used by AW skins. */
public final class CustomEntityGeometryTest {
    public static void main(String[] args) throws Exception {
        check(PrimitiveTopology.TRIANGLES, false, 3, 1);
        check(PrimitiveTopology.QUADS, false, 4, 2);
        check(PrimitiveTopology.QUADS, true, 4, 2);
        checkEmission();
        System.out.println("PASS: deferred AW triangles/quads and alpha layers enter the owning entity mesh");
    }
    static void checkEmission() throws Exception {
        var pipeline = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("armourers_workshop", "pipeline/aw_mesh_face_emissive"))
            .withShaderDefine("EMISSIVE")
            .withVertexShader("core/entity").withFragmentShader("core/entity")
            .withVertexBinding(0,DefaultVertexFormat.ENTITY).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withColorTargetState(ColorTargetState.DEFAULT).build();
        var ctor=RenderType.class.getDeclaredConstructor(String.class,RenderSetup.class);ctor.setAccessible(true);
        var type=ctor.newInstance("aw-emissive",RenderSetup.builder(pipeline).createRenderSetup());
        if(!LivingEntityGeometryAdapter.isEmissive(type)) throw new AssertionError("AW EMISSIVE define unrecognized");
        var consumer=new PlayerModelGeometryAdapter.Capture(null,0,0,0,null,null,null,1,PrimitiveTopology.TRIANGLES)
            .emissiveOffset(LivingEntityGeometryAdapter.emissiveOffsetFor(type));
        consumer.addVertex(0,0,0).setNormal(0,0,2);
        consumer.addVertex(1,0,0).setNormal(0,0,2);
        consumer.addVertex(0,1,0).setNormal(0,0,2);
        var mesh=consumer.finish();
        for(int i=2;i<mesh.vertices().length;i+=3)
            if(Math.abs(mesh.vertices()[i])>1e-6) throw new AssertionError("AW glow layer moved despite zero offset");
        if(mesh.materialData()[22]<=0 || mesh.materialData()[26]!=1)
            throw new AssertionError("AW glow lost radiance marker");
    }

    static void check(PrimitiveTopology topology, boolean blend, int vertexCount, int expected) throws Exception {
        var builder = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("armourers_workshop", "pipeline/test"))
            .withVertexShader("core/entity").withFragmentShader("core/entity")
            .withVertexBinding(0, DefaultVertexFormat.ENTITY).withPrimitiveTopology(topology);
        builder.withColorTargetState(blend ? new ColorTargetState(BlendFunction.TRANSLUCENT) : ColorTargetState.DEFAULT);
        var ctor = RenderType.class.getDeclaredConstructor(String.class, RenderSetup.class);
        ctor.setAccessible(true);
        var type = ctor.newInstance("armourers_workshop:rendertype/test", RenderSetup.builder(builder.build()).createRenderSetup());
        var state = new EntityRenderState(); state.x=10; state.y=20; state.z=30;
        LivingEntityGeometryAdapter.clear();
        LivingEntityGeometryAdapter.registerState(state, 17);
        LivingEntityGeometryAdapter.beginEntity(state, new Vec3(11,22,33));
        SubmitNodeCollector.CustomGeometryRenderer draw = (pose, buffer) -> {
            float[][] positions={{0,0,0},{1,0,0},{1,1,0},{0,1,0}};
            for(int i=0;i<vertexCount;i++) buffer.addVertex(positions[i][0],positions[i][1],positions[i][2])
                .setColor(255,255,255,128).setUv(i%2, i/2).setNormal(0,0,1);
        };
        var wrapped = LivingEntityGeometryAdapter.wrapCustom(type, draw);
        LivingEntityGeometryAdapter.endEntity();
        if (wrapped == draw) throw new AssertionError("Custom layer excluded: " + topology + " blend=" + blend);
        wrapped.render(new PoseStack().last(), null);
        var captured = LivingEntityGeometryAdapter.drain().get(17);
        if(captured==null || captured.mesh().triangleCount()!=expected)
            throw new AssertionError(topology+" blend="+blend+" expected="+expected+" captured="+(captured==null?0:captured.mesh().triangleCount()));
        if (Math.abs(captured.mesh().materialData()[3] - 128/255.0F) > 1e-6)
            throw new AssertionError("Custom vertex alpha lost");
        if (blend && (captured.mesh().materialData()[14] != 0 || captured.mesh().materialData()[23] <= 1))
            throw new AssertionError("Alpha layer not marked transmissive");
        var customOnly = DynamicEntityGeometry.playerWithCustomLayers(null, captured);
        var emptyBody = new PlayerModelGeometryAdapter.Snapshot(10,20,30,null,
            new PlayerModelGeometryAdapter.Mesh(new float[0], new float[0]));
        var hiddenBody = DynamicEntityGeometry.playerWithCustomLayers(emptyBody, captured);
        if (customOnly.mesh().triangleCount()!=expected || hiddenBody.mesh().triangleCount()!=expected
            || customOnly.x()!=10 || hiddenBody.x()!=10)
            throw new AssertionError("Custom avatar discarded when vanilla body is absent/hidden");
        if (DynamicEntityGeometry.DYNAMIC_MODEL_TRIANGLE_CAPACITY < 8192)
            throw new AssertionError("Custom model budget reverted to vanilla-only capacity");
        if (LivingEntityGeometryAdapter.wrapCustom(type, draw)!=draw)
            throw new AssertionError("Unowned GUI model entered world capture");
        if(captured.mesh().vertices()[0]!=1 || captured.mesh().vertices()[1]!=2)
            throw new AssertionError("Lost owner transform after deferred callback");
    }
}
