#!/usr/bin/env python3
"""Build component-isolation jars without changing the installed instance; always restore sources."""
from pathlib import Path
import hashlib, json, shutil, subprocess

root = Path(__file__).resolve().parents[2]
source = root / 'src/main/java/com/rtest/client'
optimized = root / 'tmp/gpu-reset-20261008'
out = root / 'tmp/gpu-isolation-20261008'
out.mkdir(exist_ok=True)
names = ['RayTracingShaderRaygen.java', 'RayTracingVulkanPass.java', 'VulkanAccelerationResources.java']
baseline = {name: (source / name).read_bytes() for name in names}
new = {name: (optimized / name).read_bytes() for name in names}
variants = {}
# Keep the lighting scheduling and sparse diagnostic write, retain original full-size diagnostic allocation/index contract.
lighting = new[names[0]].decode().replace('result.pixels[0] = packUnorm4x8', 'result.pixels[pixelIndex] = packUnorm4x8')
variants['lighting-sparse-legacy-buffer'] = {**baseline, names[0]: lighting.encode()}
variants['unique-any-hit-only'] = {**baseline, names[2]: new[names[2]]}
# Preserve old lighting; isolate the small diagnostic buffer/producer/consumer contract.
shader = baseline[names[0]].decode()
old = '                result.pixels[pixelIndex] = packUnorm4x8(vec4(clamp(outputRadiance, vec3(0.0), vec3(1.0)), 1.0));'
newstore = '''                if (all(equal(gl_LaunchIDEXT.xy, gl_LaunchSizeEXT.xy / 2u))) {
                    result.pixels[0] = packUnorm4x8(vec4(clamp(outputRadiance, vec3(0.0), vec3(1.0)), 1.0));
                }'''
assert old in shader
variants['small-diagnostic-only'] = {**baseline, names[0]: shader.replace(old,newstore).encode(), names[1]: new[names[1]]}
manifest = {'status': 'diagnostic jars only; not installed, not GPU runtime validated', 'variants': {}}
try:
    for label, files in variants.items():
        for name, content in files.items():
            (source / name).write_bytes(content)
        log = out / (label + '-build.log')
        with log.open('w') as stream:
            subprocess.run(['./gradlew','rayTracingShaderContractTest','restirConditionalMathTest','jar','--offline','--console=plain'],cwd=root,stdout=stream,stderr=subprocess.STDOUT,check=True)
        jar = out / (label + '.jar')
        shutil.copy2(root/'build/libs/rtest-0.1.0.jar',jar)
        manifest['variants'][label] = {'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'jar': str(jar)}
        print(label + ' built (not installed)', flush=True)
finally:
    for name, content in baseline.items():
        (source / name).write_bytes(content)
    with (out/'baseline-restore-build.log').open('w') as stream:
        subprocess.run(['./gradlew','rayTracingShaderContractTest','restirConditionalMathTest','jar','--offline','--console=plain'],cwd=root,stdout=stream,stderr=subprocess.STDOUT,check=True)
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
