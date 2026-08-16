#!/usr/bin/env python3
"""Per-language lambda sweep for the CTC swipe engine against the APP's own dictionaries.

Purpose (2026-08-15)
--------------------
The ship preset `CtcScoringParams.tunedV2` (gamma 0.9, **lambda 4.0**, beta 0.25,
gammaPrune 0.25, betaPrune 0.9882, beam 100) was fitted on the en app trie built from
`dictionaries/en_enhanced.json`, whose byte scores span the COMPRESSED 134..255 scale
(`ln f` in [4.898, 5.541]). The app's OTHER bundled dictionaries are CKDT v2 `.bin`
files whose per-word byte is a RANK (0 = most frequent); the like-for-like frequency
the app-side CTC wiring would feed the trie is `freq = max(1, 255 - rank)`, i.e. an
inverted scale spanning the FULL 1..255 range (`ln f` in [0, 5.54]) — ~8x more
log-frequency spread, so the same lambda buys ~8x more ranking signal. The campaign's
ru sweep (CleverKeys-ML `ctc/RESULTS.md` "Cyrillic — ... under-tuned", PHASE_J.md
S6.9) measured lambda~=2.0 optimal on that scale (+1.2 t1 over lambda 1.1; lambda 4.0
was WORSE on ru). This script measures the per-language lambda optimum for fr/de/es
against the app's own CKDT dictionaries, with dvorak/en (app en_enhanced.json trie,
the scale lambda 4.0 was fitted to) as the harness-validation control.

Provenance / reuse (nothing re-derived)
---------------------------------------
- Model: `src/main/assets/models/ctc_swipe_encoder.onnx` — the SHIP model
  (CleverKeys-ML Phase M finalist `phaseM_kd_fresh_w1`, fp16w), inputs
  features[1,2,64] / layout_keys[1,64,2] / layout_mask[1,64], output
  log_emissions[1,32,65] (verified on-device 2026-08-15).
- Decode math: imported UNCHANGED from the training repo
  `~/git/swype/CleverKeys-ML/ctc/` (read-only): `futo_decoder_ceiling.futo_viterbi_beam`
  via `eval_altlayout.decode_corpus` (beam + tally + OOV protocol),
  `futo_decoder_eval.featurize` (60 Hz resample -> 64-pt fixed resample),
  `eval_altlayout.Layout` (az26 slot contract), `eval_altlayout.ckdt_trie`
  (CKDT v2 reader, freq = max(1, 255 - rank), NFD a-z projection) and
  `lexicon.load_flat_json_vocab_stripping` (the app's en json-strip trie —
  `CtcLexiconTrie.loadStrippingNonAlphabet` policy, APP_INTEGRATION_PLAN D4).
- Corpora: `~/.cache/cleverkeys-test/futo_swipe5_<layout>.jsonl.gz` — real human
  swipes from futo-org/swipe.futo.org config swipe-5 (MIT), already filtered to
  single-finger + language-matched rows with pts normalized over the [0,1]^2
  letter area by `scripts/fetch_futo_multilayout_sample.mjs`.
- Layout geometries: `src/test/resources/layouts/futo_<layout>.json` (byte-identical
  to the ML repo's `ctc/layouts/` copies, checked 2026-08-15).

Method (mirrors the ru sweep's discipline, PHASE_J S6.9)
--------------------------------------------------------
Each corpus is split in half by row order: TUNE = rows[:N/2], CONFIRM = rows[N/2:]
(the confirm half is never used to pick lambda). All other tunedV2 constants stay
fixed; lambda sweeps {1.1, 2.0, 3.0, 4.0} (+ optional refinement values), beam 100,
topK 8, arm az26 (identity slots — the training regime, same as every campaign
alt-layout number). Accuracy is in-dict top-1/3/5: OOV and untypeable rows are
excluded from the denominator and reported, matching the geometric engine's
in-dict replay protocol.

Outputs (LOCAL only — not committed)
------------------------------------
- Per-trace dumps: `~/.cache/cleverkeys-test/ctc_lambda_<lang>_<corpus>_<half>_lam<L>.jsonl`
- Resumable summary: `~/.cache/cleverkeys-test/ctc_lambda_summary.json`
  (one record per (corpus, half, lambda); a completed record is never re-decoded).

Usage
-----
  python3 scripts/ctc_lang_lambda_sweep.py --sanity          # frame check, no decode
  python3 scripts/ctc_lang_lambda_sweep.py --smoke           # 20-trace gate per corpus
  python3 scripts/ctc_lang_lambda_sweep.py                   # full sweep, all corpora
  python3 scripts/ctc_lang_lambda_sweep.py --corpus german --lams 1.5,2.5   # refinement
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

# ── training-repo imports (read-only reuse; sys.path, no copies) ────────────────
ML_CTC = Path.home() / "git" / "swype" / "CleverKeys-ML" / "ctc"
if not ML_CTC.is_dir():
    raise SystemExit(f"training repo not found at {ML_CTC}")
sys.path.insert(0, str(ML_CTC))

from eval_altlayout import (Layout, OnnxEncoder, ckdt_trie, decode_corpus,  # noqa: E402
                            endpoint_proximity, load_corpus, project_az)
from lexicon import load_flat_json_vocab_stripping  # noqa: E402

APP_REPO = Path(__file__).resolve().parent.parent
MODEL = APP_REPO / "src" / "main" / "assets" / "models" / "ctc_swipe_encoder.onnx"
DICT_DIR = APP_REPO / "src" / "main" / "assets" / "dictionaries"
LAYOUT_DIR = APP_REPO / "src" / "test" / "resources" / "layouts"
CACHE = Path(os.environ.get("CLEVERKEYS_TEST_CACHE",
                            str(Path.home() / ".cache" / "cleverkeys-test")))
SUMMARY = CACHE / "ctc_lambda_summary.json"

#: tunedV2 with lambda as the free variable: (gamma, LAMBDA, beta, gammaPrune, betaPrune)
TUNEDV2_FIXED = (0.9, 0.25, 0.25, 0.9882)  # gamma, beta, gammaPrune, betaPrune


def preset_for(lam: float):
    g, b, gp, bp = TUNEDV2_FIXED
    return (g, lam, b, gp, bp)


#: corpus name -> (language, lexicon kind). Lexicon kinds:
#:   en-json  = app en_enhanced.json via the app's json-strip policy (134..255 scale)
#:   ckdt:<l> = app <l>_enhanced.bin, freq = max(1, 255 - rank)  (1..255 scale)
CORPORA = {
    "dvorak":  ("en", "en-json"),
    "azerty":  ("fr", "ckdt:fr"),
    "qwertz":  ("de", "ckdt:de"),
    "german":  ("de", "ckdt:de"),
    "spanish": ("es", "ckdt:es"),
}

DEFAULT_LAMS = (1.1, 2.0, 3.0, 4.0)
ROW_CAP = 3000          # per-corpus cap from the plan budget (no corpus exceeds it)


def load_lexicon(kind: str):
    """-> (LexTrie, stats dict). Stats include the accent-strip collision count."""
    if kind == "en-json":
        trie = load_flat_json_vocab_stripping(DICT_DIR / "en_enhanced.json")
        records = len(json.loads((DICT_DIR / "en_enhanced.json").read_text()))
        return trie, {"records": records, "untypeable": 0, "kept": records,
                      "distinct": trie.num_words,
                      "collisions": records - trie.num_words}
    lang = kind.split(":", 1)[1]
    trie, st = ckdt_trie(DICT_DIR / f"{lang}_enhanced.bin")
    st["collisions"] = st["kept"] - st["distinct"]
    return trie, st


def load_summary() -> dict:
    if SUMMARY.exists():
        return json.loads(SUMMARY.read_text())
    return {}


def save_summary(s: dict) -> None:
    tmp = SUMMARY.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(s, indent=2, sort_keys=True))
    tmp.replace(SUMMARY)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", action="append", default=[],
                    help="corpus name(s) to run (default: all of "
                         f"{','.join(CORPORA)})")
    ap.add_argument("--lams", default="",
                    help="comma list of lambda values (default 1.1,2.0,3.0,4.0)")
    ap.add_argument("--beam-width", type=int, default=100, dest="beam_width")
    ap.add_argument("--top-k", type=int, default=8, dest="top_k")
    ap.add_argument("--sanity", action="store_true",
                    help="endpoint-proximity frame check only (incl. the "
                         "wrong-geometry falsification control)")
    ap.add_argument("--smoke", action="store_true",
                    help="20-trace decode gate per corpus at lambda 4.0 and 2.0; "
                         "prints target vs top-1 so a geometry/lexicon mismatch is "
                         "visible as gibberish before any full sweep")
    ap.add_argument("--progress", type=int, default=250)
    args = ap.parse_args()

    names = args.corpus or list(CORPORA)
    lams = ([float(v) for v in args.lams.split(",") if v.strip()]
            if args.lams else list(DEFAULT_LAMS))
    for n in names:
        if n not in CORPORA:
            raise SystemExit(f"unknown corpus {n!r}; expected one of {list(CORPORA)}")

    layouts = {n: Layout(LAYOUT_DIR / f"futo_{n}.json") for n in names}
    corpora = {}
    for n in names:
        rows = load_corpus(CACHE / f"futo_swipe5_{n}.jsonl.gz")
        if len(rows) > ROW_CAP:
            print(f"[cap] {n}: {len(rows)} rows capped to {ROW_CAP}")
            rows = rows[:ROW_CAP]
        corpora[n] = rows

    # ── frame-mapping sanity (always printed; --sanity stops here) ──────────────
    qwerty_centers = Layout(LAYOUT_DIR / "futo_qwerty.json").az_centers
    print(f"{'corpus':<8} {'geometry':<9} {'n':>5} {'start-hit':>9} {'end-hit':>8}")
    for n, rows in corpora.items():
        s = endpoint_proximity(rows, layouts[n].az_centers)
        w = endpoint_proximity(rows, qwerty_centers)
        print(f"{n:<8} {n:<9} {s['n']:>5} {s['start_hit']:>9.3f} {s['end_hit']:>8.3f}")
        if n != "qwerty":
            print(f"{'':<8} {'qwerty*':<9} {w['n']:>5} {w['start_hit']:>9.3f} "
                  f"{w['end_hit']:>8.3f}   (* wrong-geometry control)")
        if s["start_hit"] < 0.5 or s["end_hit"] < 0.5:
            raise SystemExit(f"{n}: frame check FAILED (hit rates {s})")
    if args.sanity:
        return 0

    # ── lexicons ────────────────────────────────────────────────────────────────
    tries, lex_stats = {}, {}
    for n in names:
        kind = CORPORA[n][1]
        if kind not in tries:
            tries[kind], lex_stats[kind] = load_lexicon(kind)
            st = lex_stats[kind]
            print(f"lexicon {kind:<8} records={st['records']} "
                  f"untypeable={st['untypeable']} distinct={st['distinct']} "
                  f"strip-collisions={st['collisions']}")

    enc = OnnxEncoder([MODEL])

    # ── smoke gate: 20 traces, real-word outputs, before any full sweep ─────────
    if args.smoke:
        for n in names:
            lang, kind = CORPORA[n]
            keys, mask, cols = layouts[n].slots("az26")
            rows = corpora[n][:20]
            for lam in (4.0, 2.0):
                dump = CACHE / f"ctc_lambda_{lang}_{n}_smoke_lam{lam}.jsonl"
                with open(dump, "w") as f:
                    r = decode_corpus(enc, rows, keys, mask, cols, tries[kind],
                                      preset_for(lam), args.beam_width, args.top_k,
                                      0, f"{n}/smoke", f)
                print(f"[smoke] {n}/{lang} lam={lam} decoded={r['decoded']} "
                      f"oov={r['oov']} t1={r['t1']:.1f} "
                      f"({r['decoded']/max(r['seconds'],1e-9):.1f} tr/s)")
                for line in dump.read_text().splitlines()[:6]:
                    o = json.loads(line)
                    print(f"    {o['target']:<14} -> {o['topk'][:3]}")
        return 0

    # ── full sweep, resumable ───────────────────────────────────────────────────
    summary = load_summary()
    for n in names:
        lang, kind = CORPORA[n]
        keys, mask, cols = layouts[n].slots("az26")
        half = len(corpora[n]) // 2
        halves = {"tune": corpora[n][:half], "confirm": corpora[n][half:]}
        for which, rows in halves.items():
            for lam in lams:
                key = f"{n}|{which}|lam{lam}"
                if key in summary:
                    print(f"[skip] {key} (done: t1={summary[key]['t1']:.2f})")
                    continue
                dump = CACHE / f"ctc_lambda_{lang}_{n}_{which}_lam{lam}.jsonl"
                t0 = time.time()
                with open(dump, "w") as f:
                    r = decode_corpus(enc, rows, keys, mask, cols, tries[kind],
                                      preset_for(lam), args.beam_width, args.top_k,
                                      args.progress, key, f)
                r.update({"corpus": n, "lang": lang, "lexicon": kind,
                          "half": which, "lambda": lam,
                          "beam_width": args.beam_width, "top_k": args.top_k,
                          "model": str(MODEL), "dump": str(dump)})
                summary[key] = r
                save_summary(summary)
                print(f"[done] {key} n={r['decoded']} oov={r['oov']} "
                      f"untypeable={r['untypeable']} t1={r['t1']:.2f} "
                      f"t3={r['t3']:.2f} t5={r['t5']:.2f} "
                      f"({time.time()-t0:.0f}s)")

    # ── table ───────────────────────────────────────────────────────────────────
    print("\n" + "=" * 76)
    print(f"{'corpus':<8} {'half':<8} {'lam':>4} {'n':>5} {'OOV':>5} "
          f"{'t1':>7} {'t3':>7} {'t5':>7}")
    print("=" * 76)
    for key in sorted(summary):
        r = summary[key]
        if r["corpus"] not in names:
            continue
        print(f"{r['corpus']:<8} {r['half']:<8} {r['lambda']:>4} {r['decoded']:>5} "
              f"{r['oov']:>5} {r['t1']:>7.2f} {r['t3']:>7.2f} {r['t5']:>7.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
