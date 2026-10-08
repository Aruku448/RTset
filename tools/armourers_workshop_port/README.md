# Armourer's Workshop 26.2 NeoForge Vulkan port overlay

The existing homebaked 26.2 jar contains earlier API migration work. This directory keeps the source for the subsequent Vulkan and resource fixes. Packaging overlays those classes and preserves all other entries; it is not a replacement upstream build.

## Build

From the RT workspace:

```bash
./gradlew armourersGuiClipTest
python3 tools/armourers_workshop_port/package_port.py \
  /home/aruku/.minecraft/versions/RTest/mods/armourersworkshop-neoforge-26.2-2.0.0-homebaked.jar \
  build/classes/java/armourersPort \
  build/libs/armourersworkshop-neoforge-26.2-2.0.0-vulkan-port.jar
```

Back up the installed jar before replacing it atomically. Restart Minecraft after installing; an existing process retains its loaded classes and open resource archives.

## Rendering

- AW GUI elements all enter the native PiP render target. There is no abandoned extraction-stage geometry queue.
- Rectangles and rounded masks are immutable snapshots on deferred geometry. Clipping interpolates positions, UV, RGBA, normals, lightmap and overlay values. The inverse PiP transform converts positions back into logical GUI coordinates.
- Connected triangle/line topologies become independent primitives; texture, blend, target, lightmap and overlay bindings remain attached.
- Logical draw order is preserved through ordered native submit collectors and flushes before state changes.
- Text uses native prepared glyphs, its saved pose and the same clipping path.
- Skin faces, paint scheme, part transforms, lighting, overlay and outline are submitted as native feature geometry. Minecraft creates its GPU buffers through the Vulkan backend. No GL VBO name or GL draw callback is involved. This replaces AW's asynchronous GL cache with face emission and native uploads; performance and large skins still need gameplay measurement.
- Normal outlined materials use AFFECTS_OUTLINE, rather than IS_OUTLINE.
- Per-batch ByteBufferBuilder owners are closed after copying deferred vertex data. AbstractBufferBuilder.end returns owned bytes rather than a view into a closed mesh slice.

## Resources

- Preserve the earlier 38 recipe conversions to 26.2 ingredient syntax.
- Outfit-maker: eight undefined #missing faces fixed; wood rails use their valid adjacent oak-log slot. Box22, whose every face lacked a texture, uses oak planks. Its intended artwork cannot be recovered from an undefined upstream reference.
- Skin-cube-marker receives its marker texture as the particle texture.
- The mannequin's existing entity PNG is explicitly registered in the item atlas.
- GUI slot sprites use armourers_workshop:slot/*; item atlas resources keep item/slot/*. The same sprite ID is no longer registered in two atlases.

## Validation and limits

The standalone geometry checks cover deferred snapshots, PiP transforms, UV and alpha interpolation, rounded/nested/empty masks, connected topology conversion, blend state and vertex format preservation. They do not prove screen composition visually.

An actual Vulkan run first reproduced an abort at AbstractGLIndexBuffer -> glGenBuffers after entering the world. The native skin submission revision entered the same RTest world and remained running without that abort; its log had no missing AW model textures. That live process predates the last slot-ID, outline and draw-order changes. Final visual acceptance requires restarting with the installed overlay and opening AW workbench/library/preview screens.

Remaining Minecraft model warnings in the run came from the enabled third-party resource pack, and the offline account produced authentication warnings. Neither is evidence that all AW textures or GUI screens are visually correct.

## 23:20 transparent library and dynamic resource follow-up

The screenshot of the resource library exposed failures the earlier geometry-only checks did not cover:

1. The port builder overwrote native depth state with forward-Z LESS_EQUAL. Actual 26.2 PiP targets clear depth to zero and use native reversed-Z projection. `armourersGuiDepthTest` initially failed with `actual=LESS_THAN_OR_EQUAL`. It now verifies the inherited native depth state, including the real GUI pipeline; explicit legacy depth modes and depth bias signs are translated.
2. A registered memory PNG still went through disk-only `SimpleTexture.loadContents`. `armourersDynamicTextureTest` initially threw `FileNotFoundException: dynamic bytes are not a disk resource`. The texture loader now decodes registered memory bytes and their .mcmeta blur/clamp settings, preserving disk loading for ordinary resources.
3. Skin mesh callbacks retained and released SmartTexture before native render-type preparation bound it. Textures actually submitted for drawing now receive one manager-owned reference until stop. Merely browsing descriptors does not eagerly upload every texture. Stop closes all textures and clears the pin map.
4. Background and foreground shared a single PiP texture view while blits were deferred. The foreground clear could erase the background before its blit ran. Independent layer renderers now own separate native color/depth targets and close them together. The target-ownership replay checks that a transparent foreground cannot clear the background.
5. Vanilla PiP already flips Z; the duplicate flip was removed.

Focused checks:

```bash
./gradlew armourersGuiClipTest armourersGuiSubmissionTest armourersGuiDepthTest armourersDynamicTextureTest armourersGuiLayerTest
```

All five passed. `armourersGuiSubmissionTest` exercises the actual native ByteBufferBuilder, AW batch copying and deferred callback after a mask pop. The layer replay is a target ownership check, not GPU pixel validation.

Final installed SHA256: `ca9055d1ec96dd6a95cbb6ed273310127d4e609e6c50625df4c4028444c204dc`.
The running 23:14 process still has the previous classes. Restart and repeat the library screen before claiming actual Vulkan pixels are correct.
