#!/usr/bin/env python3
"""Short-word CTC investigation harness (`ad` -> `as`, `wet` -> `we`), 2026-10-07.

Mirrors the SHIPPED English CTC decode in Python so thousands of real traces can be replayed
and re-ranked cheaply:

  CtcFeaturizer (60 Hz + 64-point resample)            -> featurize()
  OnnxCtcEmissionModel (shipped ctc_swipe_encoder.onnx) -> Encoder
  CtcLexiconTrie EN_JSON strip loader + contraction
  alias keys at CtcContractionKeys.derivedFloor          -> load_lexicon()
  CtcBeamDecoder at presetFor("en") (γ .9, λ 4, β .25,
  γp .25, βp .9882, beam 100)                           -> beam_decode()

Parity: on the canonical straight-line traces the slates equal the Kotlin replay
(`CtcReplayEngine`) word-for-word, softmax scores within ±1/1000 (rounding / EP).

Sub-commands (all write/read JSONL in --work, default ./build/short-word-eval):

  dump CORPUS OUT [--sample N] [--emu raw|noise|smooth|fix]
      Decode a corpus; store every complete word of the FINAL beam with its raw CTC score.
      Because the prune key has no frequency term, any final-score re-ranking (λ, an
      endpoint term) of the stored beam is EXACT, with no re-decode.
      --emu emulates the app's touch-path pre-processing (ImprovedSwipeGestureRecognizer):
        noise  drop samples < 1.26 px from the last kept sample (SWIPE_NOISE_THRESHOLD)
        smooth noise + trailing 3-point moving average (SWIPE_SMOOTHING_WINDOW) — what the
               engines receive today via SwipeResult.path
        fix    noise, NO smoothing, plus a terminal sample at lift time (proposed)
  export-rows CORPUS OUT [--sample N]
      Write the usable rows ({"k","w","pts"}) for an external pre-processor: the Kotlin replay
      `CtcRawTraceReplayExport` runs the SHIPPED recognizer over them (see §7 of the eval note).
  dump-trace FILE OUT  decode rows that were pre-processed elsewhere, keeping their keys
  compare A B          paired top-1 comparison of two dumps of the same corpus (sign test)
  endpoint DUMP        isotropic endpoint-likelihood re-rank grid
  lambda DUMP          exact λ sweep over the stored beams
  sweep                encoder last-frame posterior as the endpoint slides along a row
  traces FILE          per-trace greedy / emission peaks / forced scores (targets)

Corpus rows: {"word", "pts": [[x, y, t_ms], ...]} in the letter-box frame, or HF FUTO rows
{"word", "data": [{"x","y","t"}]}. Deterministic samples: SHA-256(seed + json(pts)).
"""
from __future__ import annotations

import argparse
import bisect
import gzip
import hashlib
import json
import math
import os
import sys
import time
from math import comb
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

import numpy as np

APP = Path(__file__).resolve().parent.parent
MODEL = APP / "src/main/assets/models/ctc_swipe_encoder.onnx"
GOLDEN = APP / "src/test/resources/ctc/ctc_golden.json"
LEXICON = APP / "src/main/assets/dictionaries/en_enhanced.json"
ALIAS_FILES = ("contractions_en.json", "contraction_pairings.json")
LETTERS = "abcdefghijklmnopqrstuvwxyz"
L2I = {c: i for i, c in enumerate(LETTERS)}
BLANK = 26
SEED = "20261007"
# Golden-layout key pitch in the letter-box frame: 10 keys per row, 3 rows.
PITCH_X, PITCH_Y = 0.1, 1.0 / 3.0

Trace = Tuple[List[float], List[float], List[float]]


# ── featurization (CtcFeaturizer port) ────────────────────────────────────────────

def _lerp(a: float, b: float, f: float) -> float:
    return a + f * (b - a)


def resample_to_60hz(x: Sequence[float], y: Sequence[float], t: Sequence[float]) -> Tuple[List[float], List[float]]:
    """Stage 1 of CtcFeaturizer: time-uniform ~60 Hz resample (round-half-even count)."""
    n = len(x)
    if n == 1:
        return [x[0], x[0]], [y[0], y[0]]
    t0, dur = t[0], t[-1] - t[0]
    if dur <= 1e-12:
        return [x[0], x[-1]], [y[0], y[-1]]
    m = max(2, int(round(dur / (1000.0 / 60.0))) + 1)
    step = dur / (m - 1)
    ox: List[float] = []
    oy: List[float] = []
    for i in range(m):
        tt = min(t0 + i * step, t[-1])
        j = bisect.bisect_left(t, tt)
        if j == 0:
            ox.append(x[0]); oy.append(y[0])
        elif j >= n:
            ox.append(x[-1]); oy.append(y[-1])
        else:
            seg = t[j] - t[j - 1]
            f = (tt - t[j - 1]) / seg if seg > 1e-12 else 0.0
            ox.append(_lerp(x[j - 1], x[j], f)); oy.append(_lerp(y[j - 1], y[j], f))
    return ox, oy


def featurize(x: Sequence[float], y: Sequence[float], t: Sequence[float], length: int = 64) -> np.ndarray:
    """Full CtcFeaturizer.featurize: [2, 64] float32 (index-uniform resample, clip [0,1])."""
    hx, hy = resample_to_60hz(x, y, t)
    n = len(hx)
    out = np.zeros((2, length), np.float32)
    for i in range(length):
        idx = i / (length - 1) * (n - 1)
        i0 = int(idx); i1 = min(i0 + 1, n - 1); f = idx - i0
        out[0, i] = min(1.0, max(0.0, _lerp(hx[i0], hx[i1], f)))
        out[1, i] = min(1.0, max(0.0, _lerp(hy[i0], hy[i1], f)))
    return out


def golden_layout() -> Tuple[np.ndarray, np.ndarray]:
    g = json.loads(GOLDEN.read_text())["layout"]
    return np.array(g["cx"], np.float32), np.array(g["cy"], np.float32)


class Encoder:
    """The shipped ONNX encoder on the golden layout; emissions sliced like CtcEmissions."""

    def __init__(self, path: Path = MODEL) -> None:
        import onnxruntime as ort  # local import: only the decode sub-commands need ORT
        so = ort.SessionOptions()
        so.intra_op_num_threads = 2
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.session = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        cx, cy = golden_layout()
        self.keys = np.zeros((1, 64, 2), np.float32)
        self.keys[0, :26, 0] = cx
        self.keys[0, :26, 1] = cy
        self.mask = np.zeros((1, 64), bool)
        self.mask[0, :26] = True

    def emit(self, x: Sequence[float], y: Sequence[float], t: Sequence[float]) -> np.ndarray:
        out = self.session.run(["log_emissions"], {
            "features": featurize(x, y, t)[None], "layout_keys": self.keys, "layout_mask": self.mask,
        })[0][0]
        return np.concatenate([out[:, :26], out[:, 64:65]], axis=1).astype(np.float32)


# ── lexicon + beam (CtcLexiconTrie / CtcBeamDecoder ports) ────────────────────────

class Node:
    __slots__ = ("ch", "children", "is_word", "logf", "depth", "word")

    def __init__(self, ch: int, depth: int) -> None:
        self.ch = ch; self.children: Dict[int, "Node"] = {}; self.is_word = False
        self.logf = 0.0; self.depth = depth; self.word: Optional[str] = None


class Trie:
    def __init__(self) -> None:
        self.root = Node(-1, 0)

    def insert(self, w: str, freq: float) -> None:
        n = self.root
        for c in w:
            nx = n.children.get(L2I[c])
            if nx is None:
                nx = Node(L2I[c], n.depth + 1); n.children[L2I[c]] = nx
            n = nx
        n.is_word = True; n.word = w
        lf = math.log(freq + 1e-10)
        if lf > n.logf or n.logf == 0.0:
            n.logf = lf

    def node(self, w: str) -> Optional[Node]:
        n = self.root
        for c in w:
            n = n.children.get(L2I[c])
            if n is None:
                return None
        return n

    def contains(self, w: str) -> bool:
        n = self.node(w)
        return n is not None and n.is_word


def load_lexicon() -> Trie:
    """EN_JSON strip loader + contraction alias keys at the derived floor (shipped en trie)."""
    data = json.loads(LEXICON.read_text())
    trie = Trie(); freqs: List[float] = []
    for raw, f in data.items():
        s = "".join(c for c in raw.lower() if c in L2I)
        if s:
            ff = float(f) if f > 0 else 1.0
            trie.insert(s, ff); freqs.append(ff)
    floor = max(1.0, min(freqs) - 1.0)
    for name in ALIAS_FILES:
        for k in json.loads((APP / "src/main/assets/dictionaries" / name).read_text()):
            kl = k.lower()
            if kl and all(c in L2I for c in kl) and not trie.contains(kl):
                trie.insert(kl, floor)
    return trie


GAMMA, LAMBDA, BETA, GAMMA_PRUNE, BETA_PRUNE, BEAM = 0.9, 4.0, 0.25, 0.25, 0.9882, 100


def beam_decode(E: np.ndarray, trie: Trie) -> List[Tuple[str, float, float]]:
    """CtcBeamDecoder.decode at presetFor("en"); returns EVERY final-beam word as
    (word, finalScore, rawCtc), best first. Truncate to 8 for the displayed slate."""
    hyps: List[list] = [[0.0, trie.root, False]]
    for t in range(E.shape[0]):
        row = E[t]; bl = float(row[BLANK]); nxt: Dict[Tuple[int, int], list] = {}
        for score, node, be in hyps:
            k = (id(node), 1); s = score + bl; cur = nxt.get(k)
            if cur is None:
                nxt[k] = [s, node, True]
            elif cur[0] < s:
                cur[0] = s
            for ci, ch in node.children.items():
                s = score + float(row[ci]); k = (id(ch), 0); cur = nxt.get(k)
                if cur is None:
                    nxt[k] = [s, ch, False]
                elif cur[0] < s:
                    cur[0] = s
            if not be and node.depth > 0:
                s = score + float(row[node.ch]); k = (id(node), 0); cur = nxt.get(k)
                if cur is None:
                    nxt[k] = [s, node, False]
                elif cur[0] < s:
                    cur[0] = s
        cand = list(nxt.values())
        if len(cand) > BEAM:
            cand.sort(key=lambda h: h[0] / (max(h[1].depth, 1) ** GAMMA_PRUNE) + BETA_PRUNE * h[1].depth,
                      reverse=True)
            cand = cand[:BEAM]
        hyps = cand
    best: Dict[str, Tuple[str, float, float]] = {}
    for score, node, _ in hyps:
        if node.is_word and node.depth > 0:
            fin = score / (node.depth ** GAMMA) + BETA * node.depth + LAMBDA * node.logf
            if node.word not in best or best[node.word][1] < fin:
                best[node.word] = (node.word, fin, score)
    return sorted(best.values(), key=lambda r: -r[1])


def greedy(E: np.ndarray) -> str:
    out: List[str] = []; prev = -1
    for t in range(E.shape[0]):
        b = int(np.argmax(E[t]))
        if b != prev and b != BLANK:
            out.append(LETTERS[b])
        prev = b
    return "".join(out)


def forced_ctc(E: np.ndarray, word: str) -> float:
    """Best-path score of `word` under the beam's own topology (blank-stay, advance, repeat)."""
    n = len(word); cur = {(0, False): 0.0}
    for t in range(E.shape[0]):
        row = E[t]; nx: Dict[Tuple[int, bool], float] = {}

        def put(k: Tuple[int, bool], v: float) -> None:
            if nx.get(k, -1e30) < v:
                nx[k] = v
        for (p, be), s in cur.items():
            put((p, True), s + float(row[BLANK]))
            if p < n:
                put((p + 1, False), s + float(row[L2I[word[p]]]))
            if not be and p > 0:
                put((p, False), s + float(row[L2I[word[p - 1]]]))
        cur = nx
    return max(cur.get((n, False), -1e30), cur.get((n, True), -1e30))


def peaks(E: np.ndarray) -> str:
    """Frames whose blank posterior is below 0.9, with their top two classes."""
    out = []
    for t in range(E.shape[0]):
        o = np.argsort(-E[t])[:2]
        if o[0] != BLANK or math.exp(E[t, BLANK]) < 0.9:
            out.append(f"{t}:" + "/".join(f"{'_' if i == BLANK else LETTERS[i]}{math.exp(E[t, i]):.2f}" for i in o))
    return " ".join(out)


# ── app touch-path emulation (ImprovedSwipeGestureRecognizer) ─────────────────────

#: Letter box in DEVICE px for the 1.26 px noise threshold (~400 css px canvas at ~2.6x).
BOX_W_PX, BOX_H_PX = 1000.0, 470.0
NOISE_PX, SMOOTH_W = 1.26, 3


def app_emulate(x: List[float], y: List[float], t: List[float], mode: str) -> Trace:
    if mode == "raw":
        return x, y, t
    kx, ky, kt = [x[0]], [y[0]], [t[0]]
    for i in range(1, len(x)):
        if t[i] - kt[-1] <= 0:
            continue
        if math.hypot((x[i] - kx[-1]) * BOX_W_PX, (y[i] - ky[-1]) * BOX_H_PX) < NOISE_PX:
            continue
        kx.append(x[i]); ky.append(y[i]); kt.append(t[i])
    if mode == "fix":
        if kt[-1] < t[-1]:  # terminal sample at lift time keeps a final dwell's duration
            kx.append(kx[-1]); ky.append(ky[-1]); kt.append(t[-1])
        return kx, ky, kt
    if mode == "noise" or len(kx) < 2:
        return kx, ky, kt
    sx, sy = [kx[0]], [ky[0]]
    for i in range(1, len(kx)):
        if i + 1 < SMOOTH_W:
            sx.append(kx[i]); sy.append(ky[i])
        else:
            sx.append(sum(kx[i - SMOOTH_W + 1:i + 1]) / SMOOTH_W)
            sy.append(sum(ky[i - SMOOTH_W + 1:i + 1]) / SMOOTH_W)
    return sx, sy, kt


# ── corpora ───────────────────────────────────────────────────────────────────────

def read_rows(path: str) -> Iterable[Tuple[str, str, list]]:
    """Yield (sha-key, word, pts) for usable rows: a–z label, ≥2 points, monotonic time."""
    op = gzip.open if path.endswith(".gz") else open
    with op(path, "rt") as fh:
        for line in fh:
            if not line.strip():
                continue
            r = json.loads(line)
            pts = r["pts"] if "pts" in r else [[p["x"], p["y"], p["t"]] for p in r.get("data", [])]
            w = str(r.get("word", "")).lower()
            if not w.isascii() or not w.isalpha() or len(pts) < 2:
                continue
            ts = [p[2] for p in pts]
            if any(ts[i] < ts[i - 1] for i in range(1, len(ts))):
                continue
            yield hashlib.sha256((SEED + json.dumps(pts)).encode()).hexdigest()[:16], w, pts


def load_dump(path: str) -> Dict[str, dict]:
    return {r["k"]: r for r in map(json.loads, open(path))}


def top1(r: dict) -> bool:
    return bool(r["c"]) and r["c"][0][0] == r["w"]


def sign_p(g: int, l: int) -> float:
    """One-sided exact sign test P(X <= min) for g gains vs l losses."""
    m = g + l
    return 1.0 if m == 0 else sum(comb(m, i) for i in range(0, min(g, l) + 1)) / 2 ** m


# ── sub-commands ──────────────────────────────────────────────────────────────────

def _decode_rows(rows: List[Tuple[str, str, Trace]], out: str) -> None:
    """Decode (key, word, trace) rows and write one dump line per row (shared by dump modes)."""
    enc, trie = Encoder(), load_lexicon(); t0 = time.time()
    with open(out, "w") as fo:
        for i, (k, w, (x, y, t)) in enumerate(rows):
            E = enc.emit(x, y, t)
            fo.write(json.dumps({"w": w, "k": k, "x1": x[-1], "y1": y[-1], "g": greedy(E),
                                 "c": [[c[0], round(c[1], 5), round(c[2], 5)] for c in beam_decode(E, trie)]}) + "\n")
            if i % 500 == 0:
                print(f"{i}/{len(rows)} {time.time() - t0:.0f}s", flush=True)


def cmd_dump(a: argparse.Namespace) -> None:
    rows = list(read_rows(a.corpus))
    if a.sample:
        rows = sorted(rows)[:a.sample]
    _decode_rows([(k, w, app_emulate([p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts], a.emu))
                  for k, w, pts in rows], a.out)


def cmd_export_rows(a: argparse.Namespace) -> None:
    """Write the usable rows as {"k","w","pts"} for an EXTERNAL touch-path pre-processor.

    The Kotlin replay (`CtcRawTraceReplayExport`) drives the shipped recognizer over these rows
    and writes the same shape back; `dump-trace` then decodes it under the original keys, so
    `compare` pairs the shipped code path against the emulated ones trace by trace.
    """
    rows = list(read_rows(a.corpus))
    if a.sample:
        rows = sorted(rows)[:a.sample]
    with open(a.out, "w") as fo:
        for k, w, pts in rows:
            fo.write(json.dumps({"k": k, "w": w, "pts": pts}) + "\n")


def cmd_dump_trace(a: argparse.Namespace) -> None:
    """Decode rows whose "pts" were already pre-processed elsewhere, keeping their "k"."""
    rows = []
    for line in open(a.file):
        if line.strip():
            r = json.loads(line); pts = r["pts"]
            rows.append((r["k"], r["w"], ([p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts])))
    _decode_rows(rows, a.out)


def cmd_compare(a: argparse.Namespace) -> None:
    A, B = load_dump(a.a), load_dump(a.b)
    keys = [k for k in A if k in B]
    short = [k for k in keys if len(A[k]["w"]) <= 3]
    g = sum(1 for k in keys if top1(B[k]) and not top1(A[k]))
    l = sum(1 for k in keys if top1(A[k]) and not top1(B[k]))
    pct = lambda D, ks: 100 * sum(top1(D[k]) for k in ks) / max(len(ks), 1)
    print(f"traces={len(keys)} words={len({A[k]['w'] for k in keys})} "
          f"A t1={pct(A, keys):.2f} B t1={pct(B, keys):.2f} B-only={g} A-only={l} "
          f"one-sided p={sign_p(g, l):.4f} | <=3 n={len(short)} A={pct(A, short):.2f} B={pct(B, short):.2f}")


def cmd_endpoint(a: argparse.Namespace) -> None:
    cx, cy = golden_layout(); rows = list(load_dump(a.dump).values())

    def d2(r: dict, w: str) -> float:
        i = L2I[w[-1]]
        return ((r["x1"] - cx[i]) / PITCH_X) ** 2 + ((r["y1"] - cy[i]) / PITCH_Y) ** 2
    for wgt in (0, .05, .1, .2, .3, .5, .75, 1, 1.5, 2):
        ok = sum(1 for r in rows if r["c"] and max(r["c"], key=lambda c: c[1] - wgt * d2(r, c[0]))[0] == r["w"])
        print(f"endpoint weight={wgt:<4} t1={100 * ok / len(rows):.2f}")


def cmd_lambda(a: argparse.Namespace) -> None:
    trie = load_lexicon(); rows = list(load_dump(a.dump).values())
    for lam in (0, 1, 2, 3, 4, 5, 6):
        def fin(c: list) -> float:
            return c[2] / len(c[0]) ** GAMMA + BETA * len(c[0]) + lam * trie.node(c[0]).logf
        ok = sum(1 for r in rows if r["c"] and max(r["c"], key=fin)[0] == r["w"])
        print(f"lambda={lam} t1={100 * ok / len(rows):.2f}")


def straight(word: str, steps: int = 12, ms: float = 16.0) -> Trace:
    cx, cy = golden_layout(); xs: List[float] = []; ys: List[float] = []; ts: List[float] = []
    for s in range(len(word) - 1):
        a, b = L2I[word[s]], L2I[word[s + 1]]
        for j in range(steps):
            f = j / steps
            xs.append(float(cx[a] * (1 - f) + cx[b] * f)); ys.append(float(cy[a] * (1 - f) + cy[b] * f))
            ts.append(len(ts) * ms)
    e = L2I[word[-1]]
    xs.append(float(cx[e])); ys.append(float(cy[e])); ts.append(len(ts) * ms)
    return xs, ys, ts


def cmd_sweep(_: argparse.Namespace) -> None:
    enc, trie = Encoder(), load_lexicon(); cx, cy = golden_layout()
    for start, row_y in (("a", 0.5), ("w", 1 / 6)):
        x0, y0 = float(cx[L2I[start]]), float(cy[L2I[start]])
        for ex in np.arange(0.20, 0.625, 0.025):
            xs = [x0 + (ex - x0) * i / 24 for i in range(25)]
            ys = [y0 + (row_y - y0) * i / 24 for i in range(25)]
            E = enc.emit(xs, ys, [i * 16.0 for i in range(25)])
            o = np.argsort(-E[-1])[:3]
            last = " ".join(f"{'_' if i == BLANK else LETTERS[i]}{math.exp(E[-1, i]):.2f}" for i in o)
            print(f"{start}->x={ex:.3f}  last-frame {last:24} top4 {[c[0] for c in beam_decode(E, trie)[:4]]}")


def cmd_traces(a: argparse.Namespace) -> None:
    enc, trie = Encoder(), load_lexicon()
    rivals = {"wet": ["we", "wt", "wet"], "ad": ["as", "ad"]}
    for k, w, pts in read_rows(a.file):
        x, y, t = [p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts]
        for mode in ("raw", "smooth", "fix"):
            xx, yy, tt = app_emulate(x, y, t, mode)
            E = enc.emit(xx, yy, tt); c = beam_decode(E, trie)
            comp = []
            for r in rivals.get(w, [w]):
                n = trie.node(r)
                if n is not None and n.is_word:
                    s = forced_ctc(E, r)
                    comp.append(f"{r}: ctc={s:.2f} final={s / len(r) ** GAMMA + BETA * len(r) + LAMBDA * n.logf:.2f}")
            rank = next((i + 1 for i, q in enumerate(c[:8]) if q[0] == w), 0)
            print(f"{w} {k} n={len(pts)} {mode:6} greedy={greedy(E):6} rank={rank} top3={[q[0] for q in c[:3]]}"
                  f"\n    peaks {peaks(E)}\n    {'; '.join(comp)}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("dump"); p.add_argument("corpus"); p.add_argument("out")
    p.add_argument("--sample", type=int, default=0)
    p.add_argument("--emu", choices=("raw", "noise", "smooth", "fix"), default="raw")
    p.set_defaults(fn=cmd_dump)
    p = sub.add_parser("export-rows"); p.add_argument("corpus"); p.add_argument("out")
    p.add_argument("--sample", type=int, default=0); p.set_defaults(fn=cmd_export_rows)
    p = sub.add_parser("dump-trace"); p.add_argument("file"); p.add_argument("out")
    p.set_defaults(fn=cmd_dump_trace)
    p = sub.add_parser("compare"); p.add_argument("a"); p.add_argument("b"); p.set_defaults(fn=cmd_compare)
    p = sub.add_parser("endpoint"); p.add_argument("dump"); p.set_defaults(fn=cmd_endpoint)
    p = sub.add_parser("lambda"); p.add_argument("dump"); p.set_defaults(fn=cmd_lambda)
    p = sub.add_parser("sweep"); p.set_defaults(fn=cmd_sweep)
    p = sub.add_parser("traces"); p.add_argument("file"); p.set_defaults(fn=cmd_traces)
    a = ap.parse_args()
    a.fn(a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
