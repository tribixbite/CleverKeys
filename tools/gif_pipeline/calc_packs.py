#!/usr/bin/env python3
import os, sys
d = os.path.join(os.path.dirname(__file__), "processed", "optimized")
fs = [f for f in os.listdir(d) if os.path.isfile(os.path.join(d, f))]
ss = sorted([os.path.getsize(os.path.join(d, f)) for f in fs])
t = sum(ss)
z = sum(1 for s in ss if s == 0)
print(f"{len(ss)} files, {z} zeros, {t/1073741824:.2f}GB, {t//max(len(ss),1)//1024}KB avg")
for mb in [25, 50, 100]:
    lim = mb * 1024 * 1024
    c = a = 0
    for s in ss:
        if a + s > lim: break
        a += s; c += 1
    print(f"  {mb}MB pack: ~{c} GIFs")
n = a = 0
for s in ss:
    if a + s > 50*1024*1024: n += 1; a = s
    else: a += s
if a > 0: n += 1
print(f"  Total 50MB packs needed: {n}")
print(f"  P50: {ss[len(ss)//2]//1024}KB  P95: {ss[int(len(ss)*0.95)]//1024}KB  Max: {ss[-1]//1024}KB")
