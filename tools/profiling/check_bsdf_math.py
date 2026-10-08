#!/usr/bin/env python3
"""Numerically compare the shader's regrouped reference fit with its bivariate form."""
from pathlib import Path
import re
import struct
import math
import json

source = Path('src/main/java/com/rtest/client/RayTracingShaderRaygen.java').read_text()
def vectors(function):
    body = source.split('float ' + function + '(', 1)[1].split('return 0.04', 1)[0]
    return [[float(v.strip()) for v in args.split(',')] for args in re.findall(r'vec4\(([^)]+)\)', body)]
def f(value):
    return struct.unpack('f', struct.pack('f', value))[0]
def add(a, b): return f(a + b)
def mul(a, b): return f(a * b)
def clamp(a): return min(1.0, max(0.0, a))
c = vectors('primeDefaultReflectiveDirectionalEnergyFit')
p = vectors('primeReferenceReflectiveDirectionalEnergyFit')
assert len(c) == len(p) == 9
assert p == [c[i] for i in [0, 2, 5, 1, 3, 7, 4, 6, 8]], 'Shader coefficient grouping changed'
assert 'constantTerm + linearTerm * x + quadraticTerm * (x * x)' in source
assert 'square * square * value' in source
assert 'float specPdf = distribution * incidentMasking / (4.0 * nDotI);' in source
c = [[f(v) for v in row] for row in c]
y = f(.64); y2 = mul(y, y)
constant = [add(add(c[0][i], mul(c[2][i],y)),mul(c[5][i],y2)) for i in range(4)]
linear = [add(add(c[1][i],mul(c[3][i],y)),mul(c[7][i],y2)) for i in range(4)]
quadratic = [add(add(c[4][i],mul(c[6][i],y)),mul(c[8][i],y2)) for i in range(4)]
def energy(v): return add(mul(f(.04),clamp(f(v[0]/v[2]))),clamp(f(v[1]/v[3])))
max_fit_error = max_power_error = 0.0
for j in range(100001):
    x=f(j/100000); x2=mul(x,x)
    old=[]
    for i in range(4):
        value=c[0][i]
        # Original GLSL left-associated operations; preserve each multiplication rounding.
        for index, factors in [(1,[x]),(2,[y]),(3,[x,y]),(4,[x2]),(5,[y2]),(6,[x2,y]),(7,[x,y2]),(8,[x2,y2])]:
            term=c[index][i]
            for factor in factors: term=mul(term,factor)
            value=add(value,term)
        old.append(value)
    new=[add(add(constant[i],mul(linear[i],x)),mul(quadratic[i],x2)) for i in range(4)]
    max_fit_error=max(max_fit_error,abs(energy(old)-energy(new)))
    square=mul(x,x); value=mul(mul(square,square),x)
    max_power_error=max(max_power_error,abs(value-math.pow(x,5)))
assert max_fit_error < 1e-5, max_fit_error
assert max_power_error < 2e-7, max_power_error
report={'samples':100001,'reference_fit_max_absolute_float_error':max_fit_error,'power5_max_absolute_error_vs_double':max_power_error,'note':'Float simulation, not GPU execution; hardware FMA/pow lowering can differ. Algebra preserves the estimator, not bitwise identity.'}
Path('docs/profiling/2026-10-07-bsdf-math-numerical.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
