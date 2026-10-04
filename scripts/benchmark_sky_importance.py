#!/usr/bin/env python3
"""Approximate quadrature on packaged skybox; no occlusion, BSDF specular, NRD or GPU timings."""
import json
from pathlib import Path
import numpy as np
from PIL import Image

root = Path(__file__).resolve().parents[1]
n, side = 128, 32
uv = (np.arange(n) + .5) * 2 / n - 1
u, v = np.meshgrid(uv, uv)
one = np.ones_like(u)
area = (1 + u*u + v*v)**-1.5 * 4 / n**2
parts = [(one,-v,-u),(-one,-v,u),(u,one,v),(u,-one,-v),(u,-v,one),(-u,-v,-one)]
lum, directions = [], []
for face, xyz in zip(['right','left','up','down','back','front'], parts):
    # Reduced image keeps this diagnostic inexpensive; radiance figures are approximate.
    image = Image.open(root / 'src/main/resources/assets/rtest/skybox' / (face + '.png'))
    c = np.asarray(image.resize((n,n), Image.Resampling.BOX), dtype=float)[...,:3] / 255
    c = np.where(c <= .04045, c / 12.92, ((c + .055) / 1.055)**2.4)
    lum.append(c @ np.array([.2126,.7152,.0722]))
    d = np.stack(xyz, axis=-1)
    directions.append(d / np.linalg.norm(d, axis=-1)[...,None])
lum = np.stack(lum)
directions = np.stack(directions)
cell_weights = (lum * area).reshape(6,side,n//side,side,n//side).sum(axis=(2,4))
uc, vc = np.meshgrid((np.arange(side)+.5)*2/side-1, (np.arange(side)+.5)*2/side-1)
o = np.ones_like(uc)
cell_parts = [(o,-vc,-uc),(-o,-vc,uc),(uc,o,vc),(uc,-o,-vc),(uc,-vc,o),(-uc,-vc,-o)]
dc = np.stack([np.stack(x,axis=-1) for x in cell_parts])
dc /= np.linalg.norm(dc,axis=-1)[...,None]
results = []
for axis in range(6):
    normal = np.zeros(3)
    normal[axis//2] = 1 if axis%2 == 0 else -1
    w = cell_weights * np.maximum(dc @ normal, 0)
    pmf = w / w.sum()
    expanded = pmf.repeat(n//side,axis=1).repeat(n//side,axis=2)
    proposal = expanded * side**2 / 4 * (1+u*u+v*v)**1.5
    cosine = np.maximum(directions @ normal, 0)
    f = lum * cosine
    baseline_pdf = cosine / np.pi
    integral = np.sum(f * area)
    def variance(pdf):
        return float(np.sum(np.divide(f*f,pdf,out=np.zeros_like(f),where=pdf>0)*area) - integral**2)
    baseline = variance(baseline_pdf)
    mixed = variance(.5*baseline_pdf + .5*proposal)
    results.append({'normal':normal.tolist(), 'baseline_variance':baseline, 'new_variance':mixed,
                    'variance_ratio':mixed/baseline})
report = {'method':'approximate unoccluded diffuse cubemap quadrature; no GPU measurement',
          'mixture':.5, 'proposal_side':side, 'evaluation_side':n, 'results':results}
out = root / 'docs/profiling/2026-10-04-sky-importance-reference.json'
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
