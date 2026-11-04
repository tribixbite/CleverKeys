#!/usr/bin/env python3
"""Pre-registered selection of the `ad`/`wet`-class gate words and rows (RTX kit 17).

The short-word bar (docs/eval/2026-10-07-short-word-ctc.md §6.4) asks for "real target traces
from fresh writers/sessions >= 50 % top-1 for ad/wet-class words, distinct words and writers
reported". The class is chosen here by a GEOMETRY RULE over the golden key layout plus a
training-count cap, never by a hand-written word list (bar 5 of §1: no per-word rules).

A word is in the class when, after lower-casing, it is 2..5 letters a-z, its HF swipe-1 TRAIN
count (case-folded) is <= --max-train-count, and at least one mechanism applies on the
golden layout (`src/test/resources/ctc/ctc_golden.json`, letter-box frame scaled to the
1000 x 470 px box that `scripts/short_word_ctc_eval.py` uses for its noise threshold):

  M1 collinear pass-through (the `wet` mechanism, §3 "second mechanism"): with consecutive
     repeated letters collapsed, some interior key has a turn angle < 35 degrees between its
     incoming and outgoing segments (same threshold as final-letter-drops §6.2).
  M2 end-overshoot competitor (the `ad` mechanism, §3 "encoder emissions"): the final
     collapsed segment passes within 0.5 key pitch of another key centre c strictly between
     its endpoints, and word[:-1] + c is a lexicon word with a HIGHER en_enhanced frequency.

Rows: every row of the gate split whose lower-cased word is in the class. The gate split is
FUTO swipe-1 VALIDATION (HF `dev.jsonl` / parquet `validation`), which is session-disjoint
from swipe-1 train and test (measured 2026-11-04: 0 of 692 sessions shared) and is in no
training tier of the shipped encoder (T3 = swipe-1 train; HWS; swipe-2/3/4; swipe-5 qwerty).
Words whose validation rows were already inspected by the October 7 investigation (`ad`,
`wet`, §2 table) are dropped from the gate and reported as development cases.

Usage:
  python select_target_class.py --train TRAIN.jsonl[.gz] --gate DEV.jsonl[.gz] --out DIR
Writes DIR/class_words.tsv (word, train_count, mechanisms, competitor) and
DIR/gate_rows.jsonl ({"id","session","word","mech","pts"} in the short_word_ctc_eval row format) and
DIR/controls_rows.jsonl (the §1 bar-4 control words from the same split, same format).
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
from collections import Counter
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

APP = Path(__file__).resolve().parents[3]
GOLDEN = APP / "src/test/resources/ctc/ctc_golden.json"
LEXICON = APP / "src/main/assets/dictionaries/en_enhanced.json"
LETTERS = "abcdefghijklmnopqrstuvwxyz"
#: Letter box in device px (same constants as scripts/short_word_ctc_eval.py BOX_W_PX/BOX_H_PX).
BOX_W, BOX_H = 1000.0, 470.0
#: Golden-layout key pitch in px (10 keys per row) — the unit for M2's 0.5-pitch corridor.
PITCH_PX = 0.1 * BOX_W
TURN_DEG = 35.0
#: Words already inspected as development cases in the 2026-10-07 note (§2 table).
DEV_CASES = frozenset({"ad", "wet"})
#: Control words of short-word §1 bar 4 (an EVAL list; nothing in production reads it).
CONTROLS = frozenset({"we", "as", "wt", "at", "set", "sad", "add", "ads"})


def key_px() -> Dict[str, Tuple[float, float]]:
    """Golden-layout key centres in px."""
    g = json.loads(GOLDEN.read_text())["layout"]
    return {c: (x * BOX_W, y * BOX_H) for c, x, y in zip(g["letters"], g["cx"], g["cy"])}


def lexicon() -> Dict[str, int]:
    """en_enhanced.json stripped to a-z keys (the EN_JSON strip loader), max freq per key."""
    out: Dict[str, int] = {}
    for raw, f in json.loads(LEXICON.read_text()).items():
        s = "".join(c for c in raw.lower() if c in LETTERS)
        if s:
            out[s] = max(out.get(s, 0), int(f))
    return out


def collapse(word: str) -> str:
    """Collapse consecutive repeats (`add` -> `ad`): a repeated key adds no path segment."""
    return "".join(c for i, c in enumerate(word) if i == 0 or c != word[i - 1])


def turn_deg(a: Tuple[float, float], b: Tuple[float, float], c: Tuple[float, float]) -> float:
    """Turn angle at b between a->b and b->c (0 = straight on, 180 = reversal)."""
    v1 = (b[0] - a[0], b[1] - a[1]); v2 = (c[0] - b[0], c[1] - b[1])
    n1, n2 = math.hypot(*v1), math.hypot(*v2)
    if n1 == 0 or n2 == 0:
        return 180.0
    cos = max(-1.0, min(1.0, (v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2)))
    return math.degrees(math.acos(cos))


def keys_on_segment(p: Tuple[float, float], q: Tuple[float, float], keys: Dict[str, Tuple[float, float]],
                    exclude: Iterable[str]) -> List[str]:
    """Keys whose centre lies within 0.5 pitch of segment p-q, strictly between its ends."""
    dx, dy = q[0] - p[0], q[1] - p[1]; L2 = dx * dx + dy * dy; ex = set(exclude); out: List[str] = []
    if L2 == 0:
        return out
    for c, (x, y) in keys.items():
        if c in ex:
            continue
        u = ((x - p[0]) * dx + (y - p[1]) * dy) / L2
        if 0.0 < u < 1.0 and math.hypot(x - (p[0] + u * dx), y - (p[1] + u * dy)) <= 0.5 * PITCH_PX:
            out.append(c)
    return out


def classify(word: str, keys: Dict[str, Tuple[float, float]], lex: Dict[str, int]) -> Tuple[List[str], Optional[str]]:
    """Return (mechanisms, M2 competitor) for [word]; empty mechanisms = not in the class."""
    k = collapse(word); mech: List[str] = []; comp: Optional[str] = None
    pts = [keys[c] for c in k]
    if any(turn_deg(pts[i - 1], pts[i], pts[i + 1]) < TURN_DEG for i in range(1, len(pts) - 1)):
        mech.append("M1")
    if len(k) >= 2:
        f = lex.get(word, 0)
        for c in keys_on_segment(pts[-2], pts[-1], keys, (k[-2], k[-1])):
            alt = word[:-1] + c
            if lex.get(alt, 0) > f and (comp is None or lex[alt] > lex[comp]):
                comp = alt
        if comp is not None:
            mech.append("M2")
    return mech, comp


def read_jsonl(path: Path) -> Iterator[dict]:
    op = gzip.open if path.suffix == ".gz" else open
    with op(path, "rt") as fh:
        for line in fh:
            if line.strip():
                yield json.loads(line)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--train", type=Path, required=True, help="HF swipe-1 train.jsonl (word counts only)")
    ap.add_argument("--gate", type=Path, required=True, help="HF swipe-1 dev.jsonl (the gate split)")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-train-count", type=int, default=50,
                    help="pre-registered cap: words the encoder saw at most this often (default 50)")
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    keys, lex = key_px(), lexicon()
    train = Counter(str(r.get("word", "")).lower() for r in read_jsonl(a.train))
    cls: Dict[str, Tuple[List[str], Optional[str]]] = {}
    gate_rows: List[dict] = []; dev_rows = 0
    controls: List[dict] = []
    for r in read_jsonl(a.gate):
        w = str(r.get("word", "")).lower()
        if w in CONTROLS:
            controls.append({"id": r.get("id"), "session": r.get("session"), "word": w,
                             "pts": [[p["x"], p["y"], p["t"]] for p in r.get("data", [])]})
            continue
        if not (2 <= len(w) <= 5 and w.isascii() and w.isalpha()) or w not in lex:
            continue
        if train[w] > a.max_train_count:
            continue
        if w not in cls:
            cls[w] = classify(w, keys, lex)
        if not cls[w][0]:
            continue
        if w in DEV_CASES:
            dev_rows += 1
            continue
        pts = [[p["x"], p["y"], p["t"]] for p in r.get("data", [])]
        gate_rows.append({"id": r.get("id"), "session": r.get("session"), "word": w,
                          "mech": "+".join(cls[w][0]), "pts": pts})
    with open(a.out / "class_words.tsv", "w") as fo:
        fo.write("word\ttrain_count\tmechanisms\tm2_competitor\tgate_rows\n")
        per = Counter(g["word"] for g in gate_rows)
        for w in sorted(w for w, (m, _) in cls.items() if m):
            fo.write(f"{w}\t{train[w]}\t{'+'.join(cls[w][0])}\t{cls[w][1] or ''}\t{per[w]}\n")
    with open(a.out / "gate_rows.jsonl", "w") as fo:
        for g in gate_rows:
            fo.write(json.dumps(g) + "\n")
    with open(a.out / "controls_rows.jsonl", "w") as fo:
        for g in controls:
            fo.write(json.dumps(g) + "\n")
    print(f"controls rows={len(controls)} {dict(Counter(c['word'] for c in controls))}")
    print(f"class words with gate rows={len({g['word'] for g in gate_rows})} rows={len(gate_rows)} "
          f"sessions={len({g['session'] for g in gate_rows})} dev-case rows dropped={dev_rows}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
