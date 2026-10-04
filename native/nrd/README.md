# RTest NRD native bridge

RTest ships a small versioned C ABI around NRD Core (ABI 8). The bridge is intentionally
API-agnostic: it returns SPIR-V, texture descriptions and per-frame dispatch descriptions while
RTest retains ownership of every Vulkan object and synchronization point. ABI 8 also accepts a
fixed-width REBLUR tuning block so Java can change denoiser settings without exposing NRD C++
structures across the boundary.

The checked-in release library is rebuilt only when this bridge or the pinned NRD version changes.
From the repository root on Linux:

```bash
cmake -S native/nrd -B build/native/nrd -DNRD_SOURCE_DIR=/path/to/NRD -DCMAKE_BUILD_TYPE=Release
cmake --build build/native/nrd --parallel
```

The bridge is pinned to NRD 4.17.3, SPIR-V only, with no NRI or quad-intrinsics extension. It
can create `REBLUR_DIFFUSE_SPECULAR` instances for opaque, transparent reflection, and transparent
transmission profiles. The Java denoiser also has branch APIs, but `RtestFsr3` currently creates
and records only the regular opaque NRD instance; transparent branch support is not wired into the
runtime frame graph.
Copy the resulting `build/native/nrd/bin/libprime_nrd.so` to
`src/main/resources/rtest/natives/linux-x86_64/libprime_nrd.so` and run the full Gradle build.

On Windows x64, use the same bridge and the pinned NRD source with a Visual Studio developer
prompt (or clang-cl):

```powershell
cmake -S native/nrd -B build/native/nrd-windows `
  -DNRD_SOURCE_DIR=C:/path/to/NRD `
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/native/nrd-windows --config Release --parallel
```

Copy `build/native/nrd-windows/bin/Release/prime_nrd.dll` to
`src/main/resources/rtest/natives/windows-x86_64/prime_nrd.dll` before building the mod. The
Java bridge selects this resource only on Windows x86-64; do not rename the Linux `.so` or use a
DX12/Proton DLL as a substitute.

The exposed REBLUR controls include hit-distance reconstruction, diffuse/specular pre-pass radii,
minimum hit-distance weight, spatial radii, lobe/roughness/plane rejection, history lengths and
history-fix stride, anti-firefly, disocclusion threshold, convergence controls, and denoising range.
All values are clamped again in the bridge before calling `SetDenoiserSettings`.

NRD remains subject to the NVIDIA RTX SDKs License. Its source is not part of this repository; obtain the pinned official NRD source locally before rebuilding.
