#!/usr/bin/env python3
"""Pre-registered gate for the short-word CTC fine-tune (RTX kit 17).

Decodes the SHIPPED encoder and one CANDIDATE through the app-mirror Python stack of
`scripts/short_word_ctc_eval.py` (CtcFeaturizer port, golden layout, en_enhanced STRIP trie +
contraction aliases, CtcBeamDecoder at presetFor("en")), every trace first passed through the
app's raw-path contract (`app_emulate(..., "fix")`, the shipped 2026-10-07 input). Same rows,
same decoder, paired per trace. Nothing here is tuned: the thresholds are copied from the
eval notes (see README.md "Gate") and fixed before any candidate exists.

Sets (each decoded once per model):
  class    gate_rows.jsonl from select_target_class.py — real ad/wet-class traces from FUTO
           swipe-1 VALIDATION, session-disjoint from every training tier.
  controls controls_rows.jsonl (same split): we as wt at set sad add ads (short-word §1 bar 4).
  heldout  futo_swipe1_test_sample.jsonl.gz — the October 7 note's held-out (non-regression).
  val      val_hwsfuto.jsonl — the campaign's val (val t1 / <=3 stratum bars).

Usage:
  python3 gate_eval.py --candidate CAND.onnx --class-rows gate_rows.jsonl \
      --controls-rows controls_rows.jsonl --heldout futo_swipe1_test_sample.jsonl.gz \
      --val val_hwsfuto.jsonl --out OUTDIR [--limit N for a smoke run]
Writes OUTDIR/<model>.<set>.jsonl dumps, OUTDIR/gate_report.tsv and prints the verdict.
Exit 0 = every bar PASS, 1 = at least one FAIL.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Tuple

APP = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(APP / "scripts"))
import short_word_ctc_eval as swe  # noqa: E402  the app-mirror decode stack, reused as-is


def normalise_rows(src: Path, dst: Path) -> Path:
    """Copy rows into the {"word","pts"} shape `swe.read_rows` reads (val uses "points")."""
    op = gzip.open if src.suffix == ".gz" else open
    with op(src, "rt") as fh, open(dst, "w") as fo:
        for line in fh:
            if not line.strip():
                continue
            r = json.loads(line)
            if "points" in r and "pts" not in r:
                r = {"word": r["word"], "pts": [[p["x"], p["y"], p["t"]] for p in r["points"]]}
            fo.write(json.dumps(r) + "\n")
    return dst


def row_meta(path: Path) -> Dict[str, dict]:
    """swe row key -> the row's FUTO `session` and class `mech` (class/control sets carry them).

    The key is computed exactly as `swe.read_rows` computes it, so it pairs with the dumps."""
    out: Dict[str, dict] = {}
    for line in open(path):
        if line.strip():
            r = json.loads(line)
            out[hashlib.sha256((swe.SEED + json.dumps(r["pts"])).encode()).hexdigest()[:16]] = \
                {"session": r.get("session") or "?", "mech": r.get("mech") or ""}
    return out


def decode(model: Path, rows_path: Path, out: Path, limit: int, trie: "swe.Trie") -> Dict[str, dict]:
    """Decode every usable row of rows_path with `model` on the fix contract; write a swe dump."""
    enc = swe.Encoder(model)
    rows = sorted(swe.read_rows(str(rows_path)))
    if limit:
        rows = rows[:limit]
    t0 = time.time(); res: Dict[str, dict] = {}
    with open(out, "w") as fo:
        for i, (k, w, pts) in enumerate(rows):
            x, y, t = swe.app_emulate([p[0] for p in pts], [p[1] for p in pts], [p[2] for p in pts], "fix")
            E = enc.emit(x, y, t)
            r = {"w": w, "k": k, "x1": x[-1], "y1": y[-1], "g": swe.greedy(E),
                 "c": [[c[0], round(c[1], 5), round(c[2], 5)] for c in swe.beam_decode(E, trie)[:8]]}
            fo.write(json.dumps(r) + "\n"); res[k] = r
            if i % 500 == 0:
                print(f"  {out.name} {i}/{len(rows)} {time.time() - t0:.0f}s", flush=True)
    return res


def rate(D: Dict[str, dict], keys: List[str], n: int = 1) -> float:
    return 100.0 * sum(1 for k in keys if D[k]["w"] in [c[0] for c in D[k]["c"][:n]]) / max(len(keys), 1)


def paired(A: Dict[str, dict], B: Dict[str, dict], keys: List[str]) -> Tuple[int, int, float]:
    """(B-only wins, A-only wins, one-sided exact sign-test p that B is worse)."""
    g = sum(1 for k in keys if swe.top1(B[k]) and not swe.top1(A[k]))
    l = sum(1 for k in keys if swe.top1(A[k]) and not swe.top1(B[k]))
    return g, l, (swe.sign_p(l, g) if l > g else 1.0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--shipped", type=Path, default=swe.MODEL)
    ap.add_argument("--candidate", type=Path, required=True)
    ap.add_argument("--class-rows", type=Path, required=True, dest="class_rows")
    ap.add_argument("--controls-rows", type=Path, required=True, dest="controls_rows")
    ap.add_argument("--heldout", type=Path, required=True)
    ap.add_argument("--val", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=0, help="smoke runs only: first N rows per set")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    sets = {"class": a.class_rows, "controls": a.controls_rows, "heldout": a.heldout,
            "val": normalise_rows(a.val, a.out / "val_rows.jsonl")}
    meta = {**row_meta(a.class_rows), **row_meta(a.controls_rows)}
    trie = swe.load_lexicon()
    dumps: Dict[Tuple[str, str], Dict[str, dict]] = {}
    for tag, model in (("shipped", a.shipped), ("candidate", a.candidate)):
        for name, path in sets.items():
            dumps[(tag, name)] = decode(model, path, a.out / f"{tag}.{name}.jsonl", a.limit, trie)

    bars: List[Tuple[str, str, str, bool]] = []  # (bar, measured, threshold, pass)

    def both(name: str) -> Tuple[Dict[str, dict], Dict[str, dict], List[str]]:
        S, C = dumps[("shipped", name)], dumps[("candidate", name)]
        return S, C, [k for k in S if k in C]

    # Class gate (short-word §6.4): >= 50 % top-1, distinct words and sessions reported. Kit
    # addition, fixed before any candidate: the gain over shipped must also be real (paired,
    # one-sided exact sign test p < 0.05), because the geometry class's own shipped baseline is
    # not the 0/7 of the hand-picked ad/wet traces the 50 % bar was written against.
    S, C, ks = both("class")
    cw = len({C[k]["w"] for k in ks}); cs = len({meta.get(k, {}).get("session", "?") for k in ks})
    bars.append(("class top-1 >= 50 %", f"shipped {rate(S, ks):.2f} -> cand {rate(C, ks):.2f} "
                 f"(traces {len(ks)}, words {cw}, sessions {cs})", ">= 50.00", rate(C, ks) >= 50.0))
    g, l, _ = paired(S, C, ks)
    p_gain = swe.sign_p(g, l) if g > l else 1.0
    bars.append(("class paired gain over shipped", f"gains {g} losses {l} one-sided p {p_gain:.4f}",
                 "gains > losses, p < 0.05", g > l and p_gain < 0.05))
    for mech in sorted({meta.get(k, {}).get("mech", "") for k in ks}):
        mk = [k for k in ks if meta.get(k, {}).get("mech", "") == mech]
        bars.append((f"class {mech or '?'} top-1 (report)", f"shipped {rate(S, mk):.2f} -> cand {rate(C, mk):.2f} "
                     f"(traces {len(mk)}, words {len({S[k]['w'] for k in mk})})", "report", True))
    per_word = defaultdict(list)
    for k in ks:
        per_word[C[k]["w"]].append(swe.top1(C[k]))
    word_rate = 100.0 * sum(sum(v) / len(v) for v in per_word.values()) / max(len(per_word), 1)
    bars.append(("class top-1, word-averaged (report)", f"cand {word_rate:.2f} over {len(per_word)} words", "report", True))
    # Controls: pooled delta >= -2 pt; no control with >= 10 traces loses > 5 pt.
    S, C, ks = both("controls")
    d = rate(C, ks) - rate(S, ks)
    bars.append(("controls pooled top-1 delta >= -2", f"{d:+.2f} (traces {len(ks)})", ">= -2.00", d >= -2.0))
    by = defaultdict(list)
    for k in ks:
        by[S[k]["w"]].append(k)
    worst: Optional[Tuple[str, float]] = None
    for w, kk in by.items():
        if len(kk) >= 10:
            dw = rate(C, kk) - rate(S, kk)
            if worst is None or dw < worst[1]:
                worst = (w, dw)
    bars.append(("no control (>=10 traces) loses > 5 pt",
                 f"worst {worst[0]} {worst[1]:+.2f}" if worst else "no control with >= 10 traces",
                 ">= -5.00", worst is None or worst[1] >= -5.0))
    # Held-out (Oct 7 note §1 bars 2-3): t1/t3 delta >= -0.3, <=3 delta >= -0.5, sign test.
    S, C, ks = both("heldout")
    short = [k for k in ks if len(S[k]["w"]) <= 3]
    g, l, p = paired(S, C, ks)
    for lab, n, thr in (("heldout top-1 delta", 1, -0.3), ("heldout top-3 delta", 3, -0.3)):
        dd = rate(C, ks, n) - rate(S, ks, n)
        bars.append((f"{lab} >= {thr}", f"{dd:+.2f} (traces {len(ks)})", f">= {thr:+.2f}", dd >= thr))
    dd = rate(C, short) - rate(S, short)
    bars.append(("heldout <=3 top-1 delta >= -0.5", f"{dd:+.2f} (traces {len(short)})", ">= -0.50", dd >= -0.5))
    bars.append(("heldout paired loss/gain not significantly worse", f"gains {g} losses {l} p {p:.4f}",
                 "p >= 0.05", p >= 0.05))
    # Val (short-word §6.4): t1 >= shipped - 0.2, <=3 stratum >= shipped.
    S, C, ks = both("val")
    short = [k for k in ks if len(S[k]["w"]) <= 3]
    dd = rate(C, ks) - rate(S, ks)
    bars.append(("val top-1 >= shipped - 0.2", f"{rate(S, ks):.2f} -> {rate(C, ks):.2f} ({dd:+.2f})", ">= -0.20", dd >= -0.2))
    dd = rate(C, short) - rate(S, short)
    bars.append(("val <=3 top-1 >= shipped", f"{rate(S, short):.2f} -> {rate(C, short):.2f} ({dd:+.2f})", ">= +0.00", dd >= 0.0))

    with open(a.out / "gate_report.tsv", "w") as fo:
        fo.write("bar\tmeasured\tthreshold\tverdict\n")
        for b, m, t, ok in bars:
            fo.write(f"{b}\t{m}\t{t}\t{'PASS' if ok else 'FAIL'}\n")
            print(f"{'PASS' if ok else 'FAIL'}  {b:52} {m}")
    print("\nfinal-letter-drops §6.5 classes (s-plural top-1 >= shipped; short-hook stem wins <= 3):")
    swe.cmd_classes(argparse.Namespace(dumps=[str(a.out / f"{t}.heldout.jsonl") for t in ("shipped", "candidate")]))
    if a.limit:
        print(f"\n--limit {a.limit}: SMOKE RUN, not a gate result")
    return 0 if all(ok for *_, ok in bars) else 1


if __name__ == "__main__":
    raise SystemExit(main())
