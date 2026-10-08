# Armourer's Workshop 26.2 上游资源与 GUI 核查

日期：2026-10-06

## 上游来源

- 已获取 Armourer's Workshop `develop` 源码归档到 `/home/aruku/桌面/Minecraft开发与配置/Armourers-Workshop-upstream-src`。
- 该上游分支的 `gradle.properties` 声明 Minecraft `21.1` / API `1.21.1`，所以不能把它直接当成 Minecraft 26.2 源码重建并替换当前 NeoForge JAR。
- 上游文件：[gradle.properties](https://github.com/Armourers-Workshop/Armourers-Workshop/blob/develop/gradle.properties)、[ClipManagerImpl.java](https://github.com/Armourers-Workshop/Armourers-Workshop/blob/develop/common/src/main/java/com/apple/library/impl/ClipManagerImpl.java)。NeoForge 26.2 端口流程：[PORTING.md](https://github.com/neoforged/NeoForge/blob/26.2.x/docs/PORTING.md)。

## GUI 与渲染

- 活动 JAR `armourersworkshop-neoforge-26.2-2.0.0-homebaked.jar` 含主要 GUI 窗口类（Armourer、Outfit Maker、Skinning Table、Wardrobe）和 27 张 GUI PNG；因此目前没有证据表明整套 GUI 代码或纹理未打包。
- 上游 `ClipManagerImpl` 的裁剪/遮罩仍直接调用 OpenGL GL20 scissor、GL30 framebuffer/stencil/multisample。现有 Vulkan 保护只绕开这些旧 GL 路径，不是 Vulkan GUI 裁剪实现；这仍可能造成窗口遮罩、圆角裁切和预览区域表现异常。
- 2026-10-06 22:19：RTest 已将矩形裁剪改接 Minecraft 26.2 的 GPU `ScissorState`，按 AW 的 GUI 缩放/坐标换算矩形区域；构建并安装成功。安装备份位于 `/home/aruku/.minecraft/versions/RTest/mod-backups/rtest-0.1.0.jar.pre-aw-native-scissor-20261006-221942`。
- Vulkan 上的 `OffscreenRenderer.addMask/removeMask` 仍被保护性跳过。该旧实现要在 Vulkan 纹理/RenderPass 上重写圆角遮罩保存与恢复；矩形 scissor 改动本身不会恢复半透明角部遮罩。
- 用户给的截图显示工作台和模型，没有打开 GUI 窗口，无法据此确认具体哪一个屏幕或交互逻辑没有显示。

## 已修复的数据资源

- 当前游戏日志显示 AW 配方输入 JSON 使用旧 `{ "item": ... }` / `{ "tag": ... }` 结构，而 Minecraft 26.2 的 Ingredient codec 拒绝它们；38 个配方文件中的 129 个输入定义因此解析失败。
- 已把活动 JAR 中这些输入改为 26.2 接受的字符串形式：物品 ID `namespace:item`，标签 `#namespace:tag`。38 个配方文件均已改写。
- 上游 Forge 源码比对发现的 17 个 `data/c/tags/item` 文件，在活动 AW JAR 中没有，但已安装的 NeoForge 26.2.0.88 universal JAR 已提供全部同名 Common tags。没有把旧版 Forge 源文件复制进来，以免其 `#forge:*` 指向与 NeoForge 26.2 不匹配的过时标签。
- 修改前 JAR 备份：`/home/aruku/.minecraft/versions/RTest/mod-backups/armourersworkshop-neoforge-26.2-2.0.0-homebaked.jar.pre-26.2-recipes-20261006-213053`。

## 验证与限制

- 已验证输出 JAR CRC 正常、38 个配方 JSON 都能解析、旧 `{item}/{tag}` 输入结构已清零、27 张 GUI 纹理仍在。
- Minecraft 进程在修复时仍运行；当前会话已加载旧 JAR。需完整退出并重新启动 RTest，配方资源修复才会进入下一次数据加载。本轮未执行游戏启动验证。
- GUI 圆角透明遮罩的 Vulkan 原生替代仍需后续迁移；本轮安装了 GPU 原生矩形 scissor，但尚未完成圆角遮罩，也尚未用重新启动后的游戏画面确认效果。

## Native Vulkan migration continuation

The subsequent actual Vulkan run reproduced a native JVM abort in `AbstractGLIndexBuffer` → `GL15.glGenBuffers`, reached through `ConcurrentBufferCompiler` when compiling skin geometry. The previous global scissor change did not address this path.

The maintained overlay is now in `tools/armourers_workshop_port/`, with a README, source, geometry checks and an idempotent packaging script. It replaces the old GL clip manager and skin VBO callback path, routes all GUI elements to native PiP submission, saves deferred masks and text poses, releases native batch owners, preserves GUI draw order, and corrects outline state and missing/duplicate resource IDs.

The native skin submission revision was launched explicitly with `--graphicsBackend vulkan`; it loaded 1623 recipes, entered the existing world, and remained alive without the earlier native abort. No missing AW model-texture warnings remained in that run. The final slot IDs, outline and draw-order changes require a restart and further visual checks. This is not a claim that every AW GUI/material combination has been verified.

Installed AW SHA256: `7ed6e6a5fdde7cddd777b1c3c79a943cca62579c8821f61a03a7477af0163cd2`.
Installed RTest SHA256: `74615033f25d539600b32bc8bb7c91fc453c5d6a649701a6c083146b1caa11be`.

### Resource library transparency follow-up

The 23:20 screenshot and repeated missing dynamic PNG warnings required additional fixes. Reproductions went red for forward-Z LESS_EQUAL inherited into a reversed-Z target, and for registered memory PNGs being loaded through a disk-only ResourceManager. Both checks now pass. Native target ownership was also corrected: background and foreground have separate PiP renderers/texture views, so deferred blits no longer share a target that gets cleared again. Actual submitted dynamic textures remain retained through native preparation and draw; memory PNG metadata is decoded alongside the pixels.

Five focused checks pass (clip geometry, actual native buffer submission, native depth, memory PNG/metadata, deferred layer ownership). Installed AW SHA256 is now `ca9055d1ec96dd6a95cbb6ed273310127d4e609e6c50625df4c4028444c204dc`. In-game pixel verification is pending restart; the current 23:14 process predates these changes. The PNG-loader and depth red results are specific reproduction signals; entering a world alone did not catch these UI failures.
