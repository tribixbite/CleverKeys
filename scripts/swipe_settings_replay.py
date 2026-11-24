#!/usr/bin/env python3
"""Swipe pre-processing settings replay (2026-11-23): default vs maintainer swipe settings.

Ports, at the S25U's pixel scale, the parts of the shipped gesture layer that the four
settings act on, and decodes the resulting CTC input with the existing harness
(`scripts/short_word_ctc_eval.py`: CtcFeaturizer + shipped encoder + CtcBeamDecoder ports,
parity-checked there against the Kotlin replay).

Ported verbatim (file:line at HEAD 4112648ab):
  ImprovedSwipeGestureRecognizer.addPoint (gesture/ImprovedSwipeGestureRecognizer.kt:126-184)
    dt<=0 skip; dt>500 re-anchor+skip; noise drop (distance from last KEPT sample < threshold);
    velocity = dist/dt*1000; smoothing (190-209); isSwipeTyping promotion BEFORE key
    registration (176-183); promoteWordCandidacy (484-489); ctcTrace = raw kept samples + one
    lift sample clamped to 500 ms (458, GestureRecognizerTypes.kt:70-87, ContinuousSwipe.kt:39)
  SwipeKeyRegistrar.offer (gesture/SwipeKeyRegistrar.kt:80-109): same-key, dwell/velocity,
    recent-5 duplicate, min travel from last registration point
  KeyboardGeometry.keyAt (a11y/KeyboardGeometry.kt:84-136): gap before a key belongs to it,
    past the last key -> last key, left of 'a' row -> 'a'
  Pointers routing (Pointers.kt): hasLeftStartingKey (1219-1233), FLAG_P_SWIPE_TYPING latch
    (1339-1344), touch-up classifier (481-545, GestureClassifier.kt:49-61), short-gesture
    branch with word-candidacy exact-direction rule (571-713), return-trip rescue (730-741),
    Keyboard2View.onSwipeEnd's isSwipeTyping() gate WITHOUT promotion (Keyboard2View.kt:1045)

Assumptions (stated in the report): portrait S25U FHD+ 1080 x 2340, keyboard_height 27 %,
row_height = 2340*0.27/3.95 = 159.95 px (Theme.kt:629), 3 letter rows of height 1.0 -> letter
area 1080 x 479.85 px, no side margins (key width 108 px); FUTO normalized letter-box coords map
linearly onto it. QWERTY (US) from src/main/layouts/latn_qwerty_us.xml, `loc` symbols absent.
FUTO sample timestamps stand in for the recognizer's wall clock (one sample per dispatch).
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

REPO = Path(__file__).resolve().parent.parent  # the repo root (this file lives in scripts/)
sys.path.insert(0, str(REPO / "scripts"))
import short_word_ctc_eval as H  # noqa: E402  (the existing, parity-checked decode harness)

# ── S25U geometry ─────────────────────────────────────────────────────────────────
SCREEN_H = 2340.0
KB_W = 1080.0
ROW_H = SCREEN_H * 27 / 100 / 3.95          # Theme.kt:629 -> 159.95 px
LETTER_H = 3 * ROW_H                        # 479.85 px
KEY_W = KB_W / 10.0                         # 108 px
KEY_HYP = math.hypot(KEY_W, ROW_H)          # Keyboard2View.getKeyHypotenuse (letter keys)
SHORT_MAX = KEY_HYP * 141 / 100             # Defaults.SHORT_GESTURE_MAX_DISTANCE (% of diag)
SWIPE_DIST_PX = 23 / 25 * (min(KB_W, SCREEN_H) / 10 * 1.0)   # Config.kt:1305-1307, dpi ratio ~1
SHORT_MIN = min(KEY_HYP * 28 / 100, SWIPE_DIST_PX * 0.8)      # ConfigSnapshot.kt:167-171
MIN_SWIPE_DISTANCE = 72.0                   # Defaults.SWIPE_MIN_DISTANCE (px)
MIN_KEY_DISTANCE = 15.0                     # Defaults.SWIPE_MIN_KEY_DISTANCE (px)
TAP_DURATION = 150                          # Defaults.TAP_DURATION_THRESHOLD (ms)
MAX_POINT_INTERVAL_MS = 500
MAX_LIFT_GAP_MS = 500
DUP_WINDOW = 5

# QWERTY (US) rows: (shift, width, centre letter or None for a non-letter, subkeys by position)
# Position indices follow KeyboardData: 0 c, 1 nw, 2 ne, 3 sw, 4 se, 5 w, 6 e, 7 n, 8 s.
_SUB = {
    "q": {2: "1"}, "w": {1: "~", 2: "2", 3: "@"}, "e": {1: "!", 2: "3", 3: "#"}, "r": {2: "4", 3: "$"},
    "t": {2: "5", 3: "%"}, "y": {2: "6", 3: "^"}, "u": {2: "7", 3: "&"}, "i": {2: "8", 3: "*"},
    "o": {2: "9", 3: "(", 4: ")"}, "p": {2: "0"},
    "a": {2: "`"}, "s": {}, "d": {}, "f": {}, "g": {2: "-", 3: "_"}, "h": {2: "=", 3: "+"},
    "j": {4: "}", 3: "{"}, "k": {2: "}", 1: "{", 3: "[", 4: "]"}, "l": {1: "(", 2: ")", 4: "\\", 3: "/"},
    "z": {}, "x": {}, "c": {3: "."}, "v": {3: ","}, "b": {4: ">", 3: "<"}, "n": {4: ":", 3: ";"},
    "m": {4: "!", 2: '"', 1: "?", 3: "'"},
}
ROWS: List[List[Tuple[float, float, Optional[str]]]] = [
    [(0, 1, c) for c in "qwertyuiop"],
    [(0.5, 1, "a")] + [(0, 1, c) for c in "sdfghjkl"],
    [(0, 1.5, None)] + [(0, 1, c) for c in "zxcvbnm"] + [(0, 1.5, None)],
]
DIRECTION_TO_INDEX = [7, 2, 2, 6, 6, 4, 4, 8, 8, 3, 3, 5, 5, 1, 1, 7]  # Pointers.kt:2751


def key_at(px: float, py: float) -> Optional[str]:
    """KeyboardGeometry.keyAt over the letter rows. Returns a letter, '#' for a non-letter key
    (shift/backspace/bottom row), or None outside the keyboard."""
    if py < 0:
        return None
    r = int(py // ROW_H)
    if r >= 3:
        return "#"   # bottom row (space etc.): a key, never alphabetic
    row = ROWS[r]
    x = 0.0
    if px < x:
        return "a" if r == 1 else None
    for shift, width, c in row:
        right = x + shift * KEY_W + width * KEY_W
        if px < right:
            return c if c else "#"
        x = right
    c = row[-1][2]
    return c if c else "#"


@dataclass(frozen=True)
class Cfg:
    name: str
    noise: float = 1.26
    dwell: int = 7
    vthr: float = 1000.0
    smooth: int = 3


CFGS = {
    "D": Cfg("D default", 1.26, 7, 1000.0, 3),
    "M": Cfg("M maintainer", 1.45, 9, 957.9, 1),
    "N": Cfg("N noise 1.45", 1.45, 7, 1000.0, 3),
    "W": Cfg("W dwell 9", 1.26, 9, 1000.0, 3),
    "V": Cfg("V vel 957.9", 1.26, 7, 957.9, 3),
    "S": Cfg("S smooth 1", 1.26, 7, 1000.0, 1),
    "WV": Cfg("WV dwell 9 + vel 957.9", 1.26, 9, 957.9, 3),
    # Sensitivity probes (slider extremes / intermediate), not candidate settings: they show
    # where each knob starts to move outcomes, i.e. that the replay can detect a change.
    "N3": Cfg("N3 noise 3", 3.0, 7, 1000.0, 3),
    "N5": Cfg("N5 noise 5", 5.0, 7, 1000.0, 3),
    "N10": Cfg("N10 noise 10", 10.0, 7, 1000.0, 3),
    "W20": Cfg("W20 dwell 20", 1.26, 20, 1000.0, 3),
    "W50V200": Cfg("W50V200 dwell 50 vel 200", 1.26, 50, 200.0, 3),
}


@dataclass
class Replay:
    raw: List[Tuple[float, float]] = field(default_factory=list)
    times: List[int] = field(default_factory=list)
    smooth: List[Tuple[float, float]] = field(default_factory=list)
    keys: List[str] = field(default_factory=list)
    swipe_typing: bool = False
    total: float = 0.0
    latched: bool = False
    left_start: bool = False
    blocked_by_dwell: int = 0      # offers rejected by gate 2 (diagnostic)


def replay(pts: Sequence[Sequence[float]], cfg: Cfg) -> Tuple[Replay, str, list]:
    """Drive the ported recognizer + Pointers routing over one trace (device px).

    Returns (state, route, ctc_trace) where route is one of
      latched        FLAG_P_SWIPE_TYPING set mid-gesture -> word decode
      short_word     short-gesture branch, word candidate, no exact-direction subkey -> word
      return_trip    return-trip rescue -> word
      short_subkey   short-gesture branch, word candidate, exact-direction subkey emitted
      dropped        classifier SWIPE but isSwipeTyping() false in onSwipeEnd -> nothing typed
      nonword        not a word candidate at touch-up (tap / fuzzed subkey)
    """
    X = [p[0] * KB_W for p in pts]
    Y = [p[1] * LETTER_H for p in pts]
    T = [int(p[2]) for p in pts]
    st = Replay()
    recent: List[str] = []
    last_key: Optional[str] = None
    lrx = lry = 0.0

    def register(k: str, x: float, y: float) -> None:
        nonlocal last_key, lrx, lry
        st.keys.append(k); last_key = k; lrx, lry = x, y
        recent.append(k)
        if len(recent) > DUP_WINDOW:
            recent.pop(0)

    def should_consider() -> bool:
        return len(st.keys) >= 2 and st.total >= MIN_SWIPE_DISTANCE  # all keys alphabetic by construction

    # startSwipe (Pointers.kt:907 at touch-down on a letter)
    st.raw.append((X[0], Y[0])); st.smooth.append((X[0], Y[0])); st.times.append(T[0])
    last_t = T[0]
    k0 = key_at(X[0], Y[0])
    if k0 and k0 != "#":
        register(k0, X[0], Y[0])
    down_x, down_y = X[0], Y[0]

    for i in range(1, len(X)):
        x, y, t = X[i], Y[i], T[i]
        # Pointers.onTouchMove: hasLeftStartingKey is updated before path collection.
        if not st.latched and not st.left_start and math.hypot(x - down_x, y - down_y) > SHORT_MAX:
            st.left_start = True
        # ImprovedSwipeGestureRecognizer.addPoint (runs on every move, latched or not)
        dt = t - last_t
        if dt <= 0:
            pass                                   # duplicate/backward: skip, anchor kept
        elif dt > MAX_POINT_INTERVAL_MS:
            last_t = t                             # long pause: re-anchor, skip this sample
        else:
            lx, ly = st.raw[-1]
            dist = math.hypot(x - lx, y - ly)
            if dist >= cfg.noise:                  # noise drop: dist < threshold -> skip
                vel = dist / dt * 1000.0
                st.raw.append((x, y)); st.times.append(t); last_t = t; st.total += dist
                if len(st.raw) < cfg.smooth:
                    st.smooth.append((x, y))
                else:
                    w = st.raw[-cfg.smooth:]
                    st.smooth.append((sum(p[0] for p in w) / len(w), sum(p[1] for p in w) / len(w)))
                if not st.swipe_typing and st.total > MIN_SWIPE_DISTANCE:
                    st.swipe_typing = should_consider()
                k = key_at(x, y)
                if k and k != "#":
                    # SwipeKeyRegistrar.offer, gates in order
                    if k == last_key:
                        pass
                    elif dt < cfg.dwell and vel > cfg.vthr:
                        st.blocked_by_dwell += 1
                    elif k in recent:
                        pass
                    elif last_key is not None and math.hypot(x - lrx, y - lry) < MIN_KEY_DISTANCE:
                        pass
                    else:
                        register(k, x, y)
        # FLAG latch check (after onSwipeMove, same move event)
        if not st.latched and st.swipe_typing and st.left_start:
            st.latched = True

    # CTC trace: raw kept samples + lift at the final position/time (Pointers.onTouchUp)
    trace = [(st.raw[j][0], st.raw[j][1], st.times[j]) for j in range(len(st.raw))]
    lift_x, lift_y, lift_t = X[-1], Y[-1], T[-1]
    if lift_t > st.times[-1]:
        lift_t = min(lift_t, st.times[-1] + MAX_LIFT_GAP_MS)
        trace.append((lift_x, lift_y, lift_t))
    ctc = [(px / KB_W, py / LETTER_H, pt) for px, py, pt in trace]

    # Touch-up routing
    if st.latched:
        return st, "latched", ctc
    promote = (not st.swipe_typing and len(st.keys) >= 2 and st.total > MIN_SWIPE_DISTANCE and should_consider()) \
        or st.swipe_typing
    elapsed = T[-1] - T[0]
    if st.left_start:
        # GestureClassifier: SWIPE if left && (smoothed length >= keyWidth/2 || time > tap)
        slen = sum(math.hypot(st.smooth[j][0] - st.smooth[j - 1][0], st.smooth[j][1] - st.smooth[j - 1][1])
                   for j in range(1, len(st.smooth)))
        if slen >= KEY_W / 2 or elapsed > TAP_DURATION:
            # endWordSwipe -> Keyboard2View.onSwipeEnd checks isSwipeTyping() (no promotion)
            return st, ("latched" if st.swipe_typing else "dropped"), ctc
        return st, "nonword", ctc
    dx, dy = X[-1] - down_x, Y[-1] - down_y
    disp = math.hypot(dx, dy)
    if disp > 0 and SHORT_MIN <= disp <= SHORT_MAX:
        a = math.atan2(dy, dx) + math.pi
        direction = (int(a * 8 / math.pi) + 12) % 16
        if promote:
            start = k0 if k0 and k0 != "#" else ""
            sub = _SUB.get(start, {}).get(DIRECTION_TO_INDEX[direction])
            return st, ("short_subkey" if sub else "short_word"), ctc
        # non-candidate: fuzzed subkey or tap; return-trip rescue needs candidacy anyway
        return st, "nonword", ctc
    if promote and elapsed > TAP_DURATION and disp < st.total / 2:
        return st, "return_trip", ctc
    return st, "nonword", ctc


WORD_ROUTES = {"latched", "short_word", "return_trip"}


def trace_key(ctc: list) -> str:
    return hashlib.sha1(json.dumps([[round(a, 7), round(b, 7), c] for a, b, c in ctc]).encode()).hexdigest()


VSYNC_MS = 1000.0 / 120.0


def vsync_resample(pts: list) -> list:
    """Re-time a trace onto a 120 Hz display's dispatch clock: one sample per vsync at
    t0 + floor(k * 8.333) ms (System.currentTimeMillis deltas 8, 8, 9), position linearly
    interpolated in time between the recorded samples, ending at the recorded last sample."""
    t0, tn = pts[0][2], pts[-1][2]
    out = [list(pts[0])]
    j = 1
    k = 1
    while True:
        tk = t0 + math.floor(k * VSYNC_MS)
        if tk >= tn:
            break
        while j < len(pts) and pts[j][2] < tk:
            j += 1
        a, b = pts[j - 1], pts[j]
        seg = b[2] - a[2]
        f = (tk - a[2]) / seg if seg > 0 else 1.0
        out.append([a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]), tk])
        k += 1
    out.append(list(pts[-1]))
    return out


def load_rows(path: str, upsample: bool, vsync: bool = False) -> List[Tuple[str, str, list, float]]:
    """(key, word, pts, median dt) for the harness's usable rows (median dt of the RECORDED
    trace). Optional re-timing: `upsample` adds midpoints to rows whose median dt > 12 ms (a
    stress case: 7-8 ms gaps); `vsync` re-times every row with vsync_resample()."""
    out = []
    for k, w, pts in H.read_rows(path):
        dts = [pts[i][2] - pts[i - 1][2] for i in range(1, len(pts))]
        mdt = statistics.median(dts) if dts else 0.0
        if vsync:
            pts = vsync_resample(pts)
        elif upsample and mdt > 12:
            up = [pts[0]]
            for i in range(1, len(pts)):
                a, b = pts[i - 1], pts[i]
                tm = math.floor((a[2] + b[2]) / 2)
                if a[2] < tm < b[2]:
                    up.append([(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, tm])
                up.append(b)
            pts = up
        out.append((k, w, pts, mdt))
    return out


def cmd_run(a: argparse.Namespace) -> None:
    rows = load_rows(a.corpus, a.upsample, a.vsync)
    if a.sample:
        rows = sorted(rows)[:a.sample]
    cfgs = [CFGS[c] for c in a.cfgs.split(",")]
    cache: Dict[str, list] = {}
    cache_path = Path(a.cache)
    if cache_path.exists():
        for line in open(cache_path):
            r = json.loads(line); cache[r["h"]] = r["c"]
    enc = trie = None
    out = open(a.out, "w")
    newc = open(cache_path, "a")
    t0 = time.time(); decoded = 0
    for n, (k, w, pts, mdt) in enumerate(rows):
        rec = {"k": k, "w": w, "mdt": mdt, "n": len(pts), "cfg": {}}
        for c in cfgs:
            st, route, ctc = replay(pts, c)
            h = trace_key(ctc)
            if h not in cache:
                if enc is None:
                    enc, trie = H.Encoder(), H.load_lexicon()
                E = enc.emit([p[0] for p in ctc], [p[1] for p in ctc], [p[2] for p in ctc])
                cache[h] = [cw for cw, _, _ in H.beam_decode(E, trie)[:8]]
                newc.write(json.dumps({"h": h, "c": cache[h]}) + "\n"); decoded += 1
            rec["cfg"][c.name.split()[0]] = {
                "route": route, "keys": "".join(st.keys), "nraw": len(st.raw), "h": h,
                "top": cache[h][:3], "blk": st.blocked_by_dwell,
            }
        out.write(json.dumps(rec) + "\n")
        if n % 500 == 0:
            print(f"{n}/{len(rows)} decoded={decoded} {time.time() - t0:.0f}s", flush=True)
    out.close(); newc.close()
    print(f"done rows={len(rows)} new decodes={decoded} {time.time() - t0:.0f}s")


def cmd_routes(a: argparse.Namespace) -> None:
    """Routing only (no CTC decode): dwell/velocity act on the outcome only through the route,
    so a large corpus gives a precise recognition rate cheaply. Writes per-config route counts
    and every trace whose route differs from the first config."""
    rows = load_rows(a.corpus, a.upsample, a.vsync)
    if a.sample:
        rows = sorted(rows)[:a.sample]
    names = a.cfgs.split(",")
    counts = {c: {} for c in names}
    changed = []
    t0 = time.time()
    for n, (k, w, pts, mdt) in enumerate(rows):
        routes = {}
        for c in names:
            st, route, _ = replay(pts, CFGS[c])
            routes[c] = (route, "".join(st.keys))
            counts[c][route] = counts[c].get(route, 0) + 1
        if any(routes[c][0] != routes[names[0]][0] for c in names):
            changed.append({"k": k, "w": w, "mdt": mdt, "n": len(pts), "routes": routes})
        if n % 20000 == 0:
            print(f"{n}/{len(rows)} {time.time() - t0:.0f}s", flush=True)
    json.dump({"rows": len(rows), "words": len({r[1] for r in rows}),
               "rows_120hz": sum(1 for r in rows if r[3] <= 10), "counts": counts, "changed": changed},
              open(a.out, "w"), indent=1)
    print(f"done rows={len(rows)} changed={len(changed)} {time.time() - t0:.0f}s")


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run"); p.add_argument("corpus"); p.add_argument("out")
    p.add_argument("--cfgs", default="D,M,N,W,V,S,WV"); p.add_argument("--sample", type=int, default=0)
    p.add_argument("--cache", required=True); p.add_argument("--upsample", action="store_true")
    p.add_argument("--vsync", action="store_true")
    p.set_defaults(fn=cmd_run)
    p = sub.add_parser("routes"); p.add_argument("corpus"); p.add_argument("out")
    p.add_argument("--cfgs", default="D,M,W,V,WV,N"); p.add_argument("--sample", type=int, default=0)
    p.add_argument("--upsample", action="store_true"); p.add_argument("--vsync", action="store_true")
    p.set_defaults(fn=cmd_routes)
    a = ap.parse_args(); a.fn(a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
