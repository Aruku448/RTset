# RTest DLSS bridge

Streamline 2.14.1 / DLSS 310.9.1, Windows x86_64. Only Super Resolution and Ray
Reconstruction are loaded; the host owns Vulkan and presents through the common
plugin's manual before/after hooks. No frame generation or swapchain replacement.

`scripts/build-dlss-windows.ps1 -Compiler <LLVM-MinGW clang++.exe>` builds the bridge
with a static C++ runtime. The checked-in headers are the NVIDIA SDK and Khronos
Vulkan-Headers 1.4.350. Runtime hashes and the official archive URL are pinned in
`third_party/streamline/sdk-lock.json`. Use `-SdkArchive <zip>` to restore official
runtime files; the archive and every extracted runtime are verified before use.

`-GpuTest` requires `VK_LAYER_KHRONOS_validation` (set `VK_LAYER_PATH` to its SDK
directory). It creates a Vulkan 1.2 device with Minecraft-compatible standalone
private-data/synchronization2 feature nodes, runs both features in five quality
modes, submits and waits, and reads the FP16 output. Nonfinite, unwritten pixels,
failed evaluation/submission/completion, or any validation error fail the test.

On RTX 5070 Ti / driver 616.92, all ten configurations produce fully finite,
nonzero RGB output. Strict synchronization validation reports first-frame clear
hazards inside the NVIDIA implementation. The default test deliberately retains
that failure. `-CoreValidationOnly` explicitly omits synchronization validation
and passes with zero core validation errors. It is not a substitute for the
strict test or game image-quality testing. See the integration audit.

ABI 1 uses fixed-width C types. Frame size is 856 bytes, images start at byte 472,
each image is 48 bytes. Matrices use row vectors and row-major storage; all image
handles are borrowed until host GPU completion. Failed evaluations must never be
submitted or presented. Owner destruction requires completion before releasing
SDK resources. SDK shutdown precedes host device destruction.

The isolated Vulkan image/readback harness in `rtest_dlss_gpu_test.cpp` is adapted
from Prime dev commit `e1917423d0c35f57742a2ca08e0f3258d0ff23c0`, GPL-3.0-only;
see `third_party/PRIME-LICENSE.txt`. The bridge itself is RTest code. NVIDIA runtime
binaries retain their separate NVIDIA SDK license; do not describe those binaries
as covered by this repository's source license.
