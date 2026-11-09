"""Port of AuroraRaster.render (LookBackdrop.kt) to tune ribbon parameters; mock keys on top."""
# Tuning port of AuroraRaster (theme/LookBackdrop.kt) — keep the two in step. Usage: aurora_proto.py CONFIG.json
# (W, H, density, gap, topFade, out, ribbons[{edgeY, amp, waves, phase, slope, height, edge, top (ARGB hex),
#  rays, rc, seed, x0, x1}], glows[{cx, cy, rx, ry, rot, c}]); writes a mock keyboard and the bare backdrop.
import math, random, sys, json
import numpy as np
from PIL import Image, ImageDraw

def rays(rb, w, density):
    out = np.zeros(w, np.float32)
    count = min(600, int(w / density / 100 * rb['rays']))
    rng = random.Random(rb['seed'] ^ (w << 16))
    xs = np.arange(w) + 0.5
    for _ in range(count):
        cx = rng.random() * w; sig = (1 + 6 * rng.random() * rng.random()) * density; st = 0.35 + 0.65 * rng.random()
        out += st * np.exp(-((xs - cx) / sig) ** 2)
    return np.minimum(0.15 + out, 1.0)

def ch(c, sh): return ((c >> sh) & 0xFF) * (((c >> 24) & 0xFF) / 255)

def render(base, ribbons, w, h, density):
    acc = np.zeros((h, w, 3), np.float32)
    ys = np.arange(h)[:, None] + 0.5
    for rb in ribbons:
        ry = rays(rb, w, density)
        xs = np.arange(w) + 0.5
        x0, x1 = rb.get('x0', 0) * w, rb.get('x1', 1) * w
        ramp = max((x1 - x0) * 0.1, 1)
        env = np.clip(np.minimum((xs - x0) / ramp, (x1 - xs) / ramp), 0, 1)
        u = xs / w
        edge = h * (rb['edgeY'] - rb['slope'] * (u - 0.5) + rb['amp'] * (0.7 * np.sin(2 * math.pi * (rb['waves'] * u + rb['phase'])) + 0.3 * np.sin(2 * math.pi * (0.5 * u + rb['phase'] * 0.37))))
        col = env * ((1 - rb['rc']) + rb['rc'] * ry)
        reach = np.maximum(rb['height'] * h * (0.55 + 0.45 * ry), 1)
        soft = max(0.035 * h, 1)
        t = (edge[None, :] - ys) / reach[None, :]
        above = (t >= 0) & (t <= 1)
        tq = np.clip(np.floor(t * 64) / 64, 0, 1)
        f_above = (0.55 * np.exp(-7 * tq) + 0.45 * np.exp(-1.8 * tq)) * (1 - tq * tq)
        d = (ys - edge[None, :]) / soft
        f_below = np.where((ys > edge[None, :]) & (d <= 3), np.exp(-d * d), 0)
        for k, sh in enumerate((16, 8, 0)):
            ce, ct = ch(rb['edge'], sh), ch(rb['top'], sh)
            colr = np.where(above, (ce + (ct - ce) * tq) * f_above, 0) + ce * f_below
            acc[:, :, k] += colr * col[None, :]
    b = np.array([(base >> 16) & 255, (base >> 8) & 255, base & 255], np.float32)
    return np.clip(acc + b, 0, 255).astype(np.uint8)

def glow(img, g, W, H):
    # radial glow like AuroraBackdropDrawable
    yy, xx = np.mgrid[0:H, 0:W] + 0.5
    a = math.radians(-g['rot']); rx = g['rx'] * W; ry = g['ry'] * H
    dx = xx - g['cx'] * W; dy = yy - g['cy'] * H
    uu = dx * math.cos(a) - dy * math.sin(a); vv = (dx * math.sin(a) + dy * math.cos(a)) * rx / ry
    t = np.hypot(uu, vv) / rx
    f = np.where(t <= .5, 1 - .55 * t / .5, np.where(t <= 1, .45 * (1 - (t - .5) / .5), 0))
    al = ((g['c'] >> 24) & 255) / 255 * f
    rgb = np.array([(g['c'] >> 16) & 255, (g['c'] >> 8) & 255, g['c'] & 255], np.float32)
    return img * (1 - al[..., None]) + rgb * al[..., None]

cfg = json.load(open(sys.argv[1]))
for rb in cfg['ribbons']:
    rb['edge']=int(rb['edge'],16); rb['top']=int(rb['top'],16)
for g in cfg.get('glows',[]): g['c']=int(g['c'],16)
W, H, dens = cfg['W'], cfg['H'], cfg['density']
base = 0x0A080E
img = render(base, cfg['ribbons'], W // 2, H // 2, dens / 2)
im = Image.fromarray(img).resize((W, H), Image.BILINEAR)
arr = np.asarray(im).astype(np.float32)
for g in cfg.get('glows', []): arr = glow(arr, g, W, H)
fade = cfg.get('topFade', .18) * H
yy = (np.arange(H) + .5)[:, None]
fa = np.clip(1 - yy / fade, 0, 1)
b = np.array([(base >> 16) & 255, (base >> 8) & 255, base & 255], np.float32)
arr = arr * (1 - fa[..., None]) + b * fa[..., None]
lum = lambda a: (0.2126 * (a[..., 0] / 255) ** 2.2 + 0.7152 * (a[..., 1] / 255) ** 2.2 + 0.0722 * (a[..., 2] / 255) ** 2.2)
print('mean lum', lum(arr).mean(), 'key lum', lum(np.array([[[0x1D, 0x19, 0x24]]], np.float32)).mean(), 'max px', arr.max(axis=(0, 1)))
im = Image.fromarray(arr.astype(np.uint8)); d = ImageDraw.Draw(im)
rows = 4; gap = cfg['gap']; kh = (H - gap * (rows + 1)) / rows
for r in range(rows):
    n = 10 if r == 0 else 9 if r == 1 else 9 if r == 2 else 5
    kw = (W - gap * 11) / 10
    off = 0 if r != 1 else kw / 2
    for i in range(n):
        x = gap + off + i * (kw + gap); y = gap + r * (kh + gap)
        d.rounded_rectangle([x, y, x + kw, y + kh], radius=8 * dens / 2.6, fill=(0x1D, 0x19, 0x24), outline=(0x33, 0x2B, 0x3D))
im.save(cfg['out']); Image.fromarray(img).resize((W, H)).save(cfg['out'].replace('.png', '-bg.png'))
