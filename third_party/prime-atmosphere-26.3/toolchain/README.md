# Prime 精确 Slang 编译工具（本机可复现记录）

此目录仅保存构建/依赖/hash记录，不分发编译器二进制。`build-record.sh` 是实际构建命令记录，含本机绝对路径；异机复现需按下面命令获取同一 commit/submodules，再对应调整 source/build/output 路径。`LOCAL-SHA256SUMS` 是本机工具 hashes，不要求不同宿主编译器生成相同 ELF；shader 锁定输出见 `LOCKED-ARTIFACTS.json`。工具原目录的 `build.sh` 对应这里的 `build-record.sh`。

状态：`bin/slangc -version` 为 `2026.13.1-1-g84792eb15`；已补齐精确源码的 glslang/SPIRV-Tools wrapper，RT 七入口实际编译和验证全部通过。

## 来源与锁定依赖

- 官方源码：https://github.com/shader-slang/slang.git
- commit：`84792eb15f9d9284ca451e435fdb3ca1d66393c2`
- 独立源码：`/tmp/prime-slang-exact-84792eb15-source`，保留 Git 历史、tags、.git；构建后 `git status --short` 为空。
- `git describe --tags --match 'v*'`：`v2026.13.1-1-g84792eb15`；未修改版本。
- `external/glslang`：`d1f52c8993a501bd52d4fbd044bfeb9ecdceb9f4`
- `external/spirv-tools`：`b707790a898e44038547df54580022fc1cf89c3d`
- 所有子模块状态见 `submodules.txt`；上述两项检出主仓库 gitlink 指定的 commit，不使用候选工具的库。

## 构建

依据该 commit 的 `docs/building.md`、`external/CMakeLists.txt` 和 `source/slang-glslang/CMakeLists.txt`；工具根 `build.sh` 包含完整配置和构建命令。

```bash
git clone --no-checkout --filter=blob:none https://github.com/shader-slang/slang.git /tmp/prime-slang-exact-84792eb15-source
git -C /tmp/prime-slang-exact-84792eb15-source checkout --detach 84792eb15f9d9284ca451e435fdb3ca1d66393c2
git -C /tmp/prime-slang-exact-84792eb15-source submodule update --init --depth 1 --jobs 4 external/unordered_dense external/miniz external/lz4 external/cmark external/vulkan external/spirv-headers external/fast_float external/lua external/glslang external/spirv-tools
bash /tmp/prime-slang-exact-84792eb15/build.sh
```

使用现有 CMake 4.4.3、GCC/G++ 16.2.1、Unix Makefiles，未安装系统依赖。`SLANG_ENABLE_SLANG_GLSLANG=ON`、`SLANG_ENABLE_SPIRV_TOOLS_MIMALLOC=OFF`，不下载 mimalloc；tests/examples/BUILD_TESTING 全部 OFF，最大并行 4。准确目标为 `slang-glslang`，连同 `slangc` 构建。构建记录为 `build-glslang.log`。其他可选后端仍保持最小配置关闭。

原 slangc/compiler/glsl-module 保留；新增 `build/Release/lib/libslang-glslang-2026.13.1.so` 复制至工具 `lib/`。不需要全局 PATH 或 LD_LIBRARY_PATH。`SHA256SUMS` 包含全部运行产物（symlink 记录目标 hash）。

## 验证

```bash
/tmp/prime-slang-exact-84792eb15/bin/slangc -version
ldd /tmp/prime-slang-exact-84792eb15/lib/libslang-glslang-2026.13.1.so
python3 -c 'import ctypes; ctypes.CDLL("/tmp/prime-slang-exact-84792eb15/lib/libslang-glslang-2026.13.1.so"); print("ctypes.CDLL: OK")'
sha256sum -c /tmp/prime-slang-exact-84792eb15/SHA256SUMS
python3 /home/aruku/桌面/Minecraft开发与配置/RT/scripts/compile_prime_atmosphere.py --slangc /tmp/prime-slang-exact-84792eb15/bin/slangc
```

动态加载成功，ldd 无缺失依赖。未修改项目 script/source 或已有上游工作树；项目验证只生成现有 `build/prime-atmosphere/locked` artifacts，保留现有 `-O2 -emit-spirv-directly`。七入口结果（编译并验证，字节数）：

| 入口 | 结果 | 字节 |
|---|---|---:|
| transmittance | 通过 | 3952 |
| directions | 通过 | 4008 |
| incident | 通过 | 25380 |
| moments | 通过 | 5316 |
| multi_scattering | 通过 | 7908 |
| ground | 通过 | 4756 |
| sky | 通过 | 33480 |

manifest：`/home/aruku/桌面/Minecraft开发与配置/RT/build/prime-atmosphere/locked/manifest.json`。
