# AW server crash during block removal

Crash archive: minecraft-exported-crash-info-2026-10-07T02-05-08.zip. Server crashed ticking AW skinnable block at (24,-59,22), test world. SkinnableBlockEntity.childTick -> kill -> setBlockAndUpdate -> affectNeighborsAfterRemoval -> abi$onRemove dereferenced a null replacement BlockState.

Compiled original AbstractBlockImpl.affectNeighborsAfterRemoval passed null and false to the legacy onRemove hook. Minecraft 26.2 LevelChunk calls this callback after installing the replacement state. The compatibility bridge now supplies level.getBlockState(pos) and forwards movedByPiston. This repairs the common bridge, including the previously observed SkinLibraryBlock removal exception. No save files were edited.

Recovered the original class with Vineflower into the persistent AW overlay and changed only the removal bridge. compileArmourersPortJava passed. check_removal_contract.py failed on original installed jar (null replacement state), passed on compiled classes and final packaged jar. This bytecode contract validates bridge arguments, not an integrated-server world replay. In-game removal/child cleanup remains pending restart and repeat of the original action.
