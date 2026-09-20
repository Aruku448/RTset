package com.rtest.client;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.PoseStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

/** Verifies the RT item material snapshot and its separation from vanilla lighting inputs. */
public final class ItemModelGeometryAdapterTest {
    public static void main(String[] args) throws IOException {
        assertDynamicMaterialSourcesDoNotReadVanillaLight();
        ItemModelGeometryAdapter.setPbrSampler(null);
        ItemModelGeometryAdapter.beginWorldDraw(new net.minecraft.world.phys.Vec3(10.0, 20.0, 30.0));
        ItemModelGeometryAdapter.beginItem(7, 12.0, 22.0, 33.0);

        BakedQuad quad = new BakedQuad(
            new Vector3f(0.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f(1.0F, 1.0F, 0.0F),
            new Vector3f(0.0F, 1.0F, 0.0F),
            UVPair.pack(0.0F, 0.0F), UVPair.pack(1.0F, 0.0F),
            UVPair.pack(1.0F, 1.0F), UVPair.pack(0.0F, 1.0F),
            Direction.SOUTH,
            new BakedQuad.MaterialInfo(null, ChunkSectionLayer.CUTOUT, Sheets.cutoutItemSheet(), -1, true, 0),
            BakedNormals.UNSPECIFIED,
            BakedColors.DEFAULT
        );
        QuadInstance instance = new QuadInstance();
        instance.setColor(0xFF804020);

        PoseStack first = new PoseStack();
        first.translate(2.0F, 2.0F, 3.0F);
        ItemModelGeometryAdapter.captureQuad(first.last(), quad, instance);
        PoseStack second = new PoseStack();
        second.translate(2.0F, 2.0F, 3.5F);
        ItemModelGeometryAdapter.captureQuad(second.last(), quad, instance);
        ItemModelGeometryAdapter.endItem();

        var mesh = ItemModelGeometryAdapter.drain().get(7);
        if (mesh == null || mesh.triangleCount() != 4) {
            throw new AssertionError("Item quad capture must preserve every submitted quad");
        }
        var sameMaterial = new ItemModelGeometryAdapter.Mesh(
            mesh.vertices().clone(), mesh.materialData().clone());
        float[] changedMaterialData = mesh.materialData().clone();
        changedMaterialData[8] += 0.125F;
        var changedMaterial = new ItemModelGeometryAdapter.Mesh(
            mesh.vertices().clone(), changedMaterialData);
        if (mesh.materialRevision() != sameMaterial.materialRevision()
            || mesh.materialRevision() == changedMaterial.materialRevision()) {
            throw new AssertionError("Item material revision must be stable and detect UV/PBR changes");
        }
        if (Math.abs(mesh.vertices()[0]) > 0.0001F || Math.abs(mesh.vertices()[1]) > 0.0001F
            || Math.abs(mesh.vertices()[2]) > 0.0001F) {
            throw new AssertionError("Entity-relative item transform was not removed");
        }
        if (Math.abs(mesh.vertices()[18 + 2] - 0.5F) > 0.0001F) {
            throw new AssertionError("Per-submit item transform was not preserved");
        }
        float[] material = mesh.materialData();
        if (material[0] != 128.0F / 255.0F || material[1] != 64.0F / 255.0F
            || material[2] != 32.0F / 255.0F) {
            throw new AssertionError("Item tint was not copied from QuadInstance");
        }
        if (material[14] != 1.0F || material[15] != 0.0F
            || Math.abs(Math.abs(material[16]) - (float)Math.PI) > 1.0E-5F
            || material[17] != 0.0F || material[18] != 1.0F) {
            throw new AssertionError("Item material flags/UV tangent layout mismatch");
        }
        if (material[8] != 0.0F || material[9] != 0.0F || material[10] != 1.0F || material[11] != 0.0F) {
            throw new AssertionError("Item UVs were not preserved");
        }
        if (ItemModelGeometryAdapter.textureSelector(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_ITEMS) != 3
            || ItemModelGeometryAdapter.textureSelector(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS) != 1
            || ItemModelGeometryAdapter.textureSelector(null) != 0) {
            throw new AssertionError("Item/block atlas selector contract changed");
        }

        ItemModelGeometryAdapter.setPbrSampler((sprite, uv0, uv1, uv2) ->
            new RayTracingPbrMaterials.Sample(0.91F, 0.73F, 0.21F, 0.0F, 0.0F, 1.0F,
                true, true, 0.63F, true, 7));
        ItemModelGeometryAdapter.beginItem(8, 12.0, 22.0, 33.0);
        PoseStack pbrPose = new PoseStack();
        pbrPose.translate(2.0F, 2.0F, 3.0F);
        ItemModelGeometryAdapter.captureQuad(pbrPose.last(), quad, instance);
        ItemModelGeometryAdapter.endItem();
        var pbrMesh = ItemModelGeometryAdapter.drain().get(8);
        if (pbrMesh == null || pbrMesh.materialData()[19] != 7.0F
            || pbrMesh.materialData()[20] != 0.21F || pbrMesh.materialData()[21] != 0.73F
            || pbrMesh.materialData()[22] != 0.63F || pbrMesh.materialData()[23] != 0.91F) {
            throw new AssertionError("Item PBR sample was not encoded in the material ABI");
        }
        ItemModelGeometryAdapter.setPbrSampler(null);

        // Entity submission occurs before LevelRenderer's prepareFrame hook. A later world-draw
        // begin must not erase the completed item capture before DynamicEntityGeometry drains it.
        ItemModelGeometryAdapter.beginItem(9, 12.0, 22.0, 33.0);
        PoseStack submitted = new PoseStack();
        submitted.translate(2.0F, 2.0F, 3.0F);
        ItemModelGeometryAdapter.captureSubmit(submitted, 0x00F000F0, new int[0], List.of(quad));
        ItemModelGeometryAdapter.endItem();
        ItemModelGeometryAdapter.beginWorldDraw(new net.minecraft.world.phys.Vec3(11.0, 21.0, 31.0));
        var submittedMesh = ItemModelGeometryAdapter.drain().get(9);
        if (submittedMesh == null || submittedMesh.triangleCount() != 2) {
            throw new AssertionError("Deferred item submit was erased before drain");
        }
        ItemModelGeometryAdapter.endWorldDraw();

        // Player entity submission uses the same final submitItem tap as dropped items. The
        // captured hand mesh is drained separately so it can be appended to the player body
        // without confusing it with an ItemEntity snapshot.
        net.minecraft.client.renderer.entity.state.AvatarRenderState playerState =
            new net.minecraft.client.renderer.entity.state.AvatarRenderState();
        playerState.x = 12.0;
        playerState.y = 22.0;
        playerState.z = 33.0;
        // EntityRenderDispatcher.submit happens before LevelRenderer.prepareFrame(), so the
        // player capture must work without relying on the later world-camera hook.
        ItemModelGeometryAdapter.endWorldDraw();
        ItemModelGeometryAdapter.registerState(playerState, 42);
        ItemModelGeometryAdapter.beginPlayer(playerState, new net.minecraft.world.phys.Vec3(10.0, 20.0, 30.0));
        ItemModelGeometryAdapter.captureQuad(first.last(), quad, instance);
        ItemModelGeometryAdapter.endPlayer();
        var heldMesh = ItemModelGeometryAdapter.drainPlayerItems().get(42);
        if (heldMesh == null || heldMesh.triangleCount() != 2) {
            throw new AssertionError("Player hand item submit was not captured");
        }
        float[] bodyVertices = new float[9];
        float[] bodyMaterials = new float[28];
        bodyMaterials[15] = 2.0F;
        var combined = PlayerModelGeometryAdapter.append(
            new PlayerModelGeometryAdapter.Mesh(bodyVertices, bodyMaterials), heldMesh);
        if (combined.triangleCount() != 3 || combined.materialData()[15 + 28] == 2.0F) {
            throw new AssertionError("Player body and hand item meshes were not appended independently");
        }
        ItemModelGeometryAdapter.endWorldDraw();
        System.out.println("Item model geometry adapter contract passed");
    }

    private static void assertDynamicMaterialSourcesDoNotReadVanillaLight() throws IOException {
        String blockEntitySource = Files.readString(Path.of(
            "src/main/java/com/rtest/client/DynamicBlockEntityGeometry.java"));
        String itemSource = Files.readString(Path.of(
            "src/main/java/com/rtest/client/ItemModelGeometryAdapter.java"));
        String playerSource = Files.readString(Path.of(
            "src/main/java/com/rtest/client/PlayerModelGeometryAdapter.java"));
        if (blockEntitySource.contains("LightLayer") || blockEntitySource.contains("getBrightness")
            || itemSource.contains("LightCoordsUtil") || itemSource.contains("getLightCoords")
            || itemSource.contains("setLightCoords")
            || playerSource.contains("quad[count - 1][12]") || playerSource.contains("quad[count - 1][13]")) {
            throw new AssertionError("Dynamic RT material sources must not read Minecraft light coordinates");
        }
    }
}
