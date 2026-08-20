#!/usr/bin/env python3
"""A/B the CONTRACTION-INJECTION FREQUENCY against a real human swipe corpus.

Why this exists
---------------
`CtcContractionKeys.inject` adds contraction alias keys ("dabaissement" ->
"d'abaissement") to the CTC lexicon trie so the display overlay has something to rewrite.
Until 2026-08-20 they went in at the bottom of the frequency scale (1.0). Measurement
showed that over-guarded: the beam scores ``ctc/len^0.9 + beta*len + lambda*ln(freq)``, so
emission evidence is DIVIDED by ``len^0.9`` while the frequency bonus is not. The ~8.5-nat
gap to French's rarest real word (freq 69) therefore demanded ~75 nats of raw emission
evidence to overcome, against the 7-10 the shipped model actually produces. 49% of the fr
alias table was unreachable.

`98307dc2` changed injection to ``minRealFrequency - 1``, derived per lexicon. The
invariant is preserved by construction — every real word still strictly outranks every
pseudo-word on frequency — but the audit that prescribed it called a corpus replay
mandatory, and **the existing harness could not do it**: neither
`scripts/ctc_lang_lambda_sweep.py` nor `eval_altlayout` models injection at all, so both
arms of an A/B would have decoded identically and proved nothing.

This script is that missing harness.

What it measures
----------------
The sensitive metric is NOT aggregate top-1 — at N~1000 the noise floor is about +-1 point,
which would swamp the effect. It is the count of individual traces whose top-1 FLIPS from a
real lexicon word to an injected pseudo-word. That number should be ~0: injection is meant
to make aliases reachable, never to let one steal a real word's own swipe.

The corpus is in-dict real words only (no elided targets), so this measures the REGRESSION
side. The surfacing side cannot come from this corpus and is covered by
`CtcContractionRankingTest` in the app's pure suite.

Usage
-----
    python3 scripts/ctc_injection_ab.py --corpus azerty
    python3 scripts/ctc_injection_ab.py --corpus azerty,qwertz,spanish --rows 400
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DICT_DIR = REPO / "src/main/assets/dictionaries"
MODEL = REPO / "src/main/assets/models/ctc_swipe_encoder.onnx"
CACHE = Path.home() / ".cache" / "cleverkeys-test"

ML_CTC = Path.home() / "git" / "swype" / "CleverKeys-ML" / "ctc"
if not ML_CTC.is_dir():
    raise SystemExit(f"training repo not found at {ML_CTC}")
sys.path.insert(0, str(ML_CTC))

from eval_altlayout import (Layout, OnnxEncoder, ckdt_trie, decode_corpus,  # noqa: E402
                            load_corpus, project_az, read_ckdt_v2)

LAYOUT_DIR = ML_CTC / "layouts"

#: corpus -> (language, layout json stem). Mirrors ctc_lang_lambda_sweep.CORPORA for the
#: CKDT-scale corpora; en-json is excluded because its rarest word is 134, a different
#: regime that deserves its own run if ever needed.
CORPORA = {
    "azerty":  "fr",
    "qwertz":  "de",
    "german":  "de",
    "spanish": "es",
}

#: The app's shipping preset for CKDT-scale languages: gamma, lambda, beta, gammaPrune,
#: betaPrune. Must match CtcScoringParams.presetFor for a CKDT language, or the A/B is run
#: at an operating point the app never uses.
PRESET_CKDT = (0.9, 2.0, 0.25, 0.25, 0.9882)


def alias_keys(lang: str) -> list[str]:
    """Contraction alias keys for `lang`, both REPLACE and APPEND files.

    Parsed with a regex rather than json.load so a key containing an escaped quote cannot
    change the shape of what we inject relative to what the app injects — the app reads the
    top-level keys and so do we.
    """
    keys: list[str] = []
    for name in (f"contractions_{lang}.json", f"contraction_pairs_{lang}.json"):
        p = DICT_DIR / name
        if not p.is_file():
            continue
        keys += re.findall(r'"([^"]+)"\s*:', p.read_text(encoding="utf-8"))
    return keys


def min_real_frequency(lang: str) -> float:
    """The rarest real word's frequency, on the app's own CKDT scale."""
    lo = None
    for _word, rank in read_ckdt_v2(DICT_DIR / f"{lang}_enhanced.bin"):
        f = float(max(1, 255 - rank))
        if lo is None or f < lo:
            lo = f
    return lo if lo is not None else 1.0


def build_trie(lang: str, inject_freq: float):
    """A CKDT trie with alias keys injected at `inject_freq`, plus the injected key set.

    Mirrors `CtcContractionKeys.inject`: a key already present keeps its real frequency
    (so a lexicon-native alias is never demoted), and a key with no a-z projection is
    skipped as uninjectable.
    """
    trie, _stats = ckdt_trie(DICT_DIR / f"{lang}_enhanced.bin")
    injected: set[str] = set()
    for key in alias_keys(lang):
        k = project_az(key)
        if k is None:
            continue
        # Skip keys the lexicon already holds, exactly as CtcContractionKeys.inject does, so
        # a lexicon-native alias keeps its real frequency. (LexTrie.insert also takes the MAX
        # of old and new log-freq, so this would be a no-op anyway — but relying on that
        # would make the harness agree with the app by accident rather than by construction.)
        if trie.contains(k) or k in injected:
            continue
        trie.insert(k, inject_freq)
        injected.add(k)
    return trie, injected


def top1(path: Path) -> dict[str, str]:
    """target -> top-1 prediction, from a decode_corpus per-trace dump."""
    out = {}
    for line in path.read_text().splitlines():
        o = json.loads(line)
        tk = o.get("topk") or []
        if tk:
            out[o["target"]] = tk[0]
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", default="azerty",
                    help="comma-separated: " + ",".join(CORPORA))
    ap.add_argument("--rows", type=int, default=400,
                    help="traces per corpus (default 400; the effect is per-trace, so a "
                         "few hundred is enough to see a flip if one exists)")
    ap.add_argument("--beam-width", type=int, default=100)
    ap.add_argument("--top-k", type=int, default=8)
    args = ap.parse_args()

    names = [n.strip() for n in args.corpus.split(",") if n.strip()]
    for n in names:
        if n not in CORPORA:
            raise SystemExit(f"unknown corpus {n!r}; expected one of {list(CORPORA)}")

    enc = OnnxEncoder([MODEL])
    CACHE.mkdir(parents=True, exist_ok=True)
    worst = 0

    for name in names:
        lang = CORPORA[name]
        layout = Layout(LAYOUT_DIR / f"futo_{name}.json")
        keys, mask, cols = layout.slots("az26")
        rows = load_corpus(CACHE / f"futo_swipe5_{name}.jsonl.gz")[:args.rows]

        floor_lo = 1.0
        floor_hi = max(1.0, min_real_frequency(lang) - 1.0)
        print(f"\n=== {name} / {lang} — {len(rows)} traces ===")
        print(f"rarest real word freq = {min_real_frequency(lang):.0f}  "
              f"-> arms: inject@{floor_lo:.0f} (old) vs inject@{floor_hi:.0f} (shipped)")

        dumps = {}
        for tag, freq in (("old", floor_lo), ("new", floor_hi)):
            trie, injected = build_trie(lang, freq)
            dump = CACHE / f"ctc_inject_{lang}_{name}_{tag}.jsonl"
            with open(dump, "w") as f:
                r = decode_corpus(enc, rows, keys, mask, cols, trie, PRESET_CKDT,
                                  args.beam_width, args.top_k, 0, f"{name}/{tag}", f)
            dumps[tag] = (dump, injected)
            print(f"  inject@{freq:<4.0f} injected={len(injected):<6} "
                  f"decoded={r['decoded']:<5} oov={r['oov']:<4} t1={r['t1']:.2f}")
            if not injected:
                # Expected for de and es, and worth saying so: a zero here means the arms are
                # IDENTICAL, so the corpus can only confirm "no change", never absence of a
                # regression. de's 21 curated clitics are all already native German lexicon
                # surfaces; es ships an empty contraction file (del/al are fused, not elided).
                # If a language with a non-empty, non-native alias set ever prints 0, that is
                # a harness fault, not a result.
                print(f"       (nothing injectable for {lang} — arms are identical; this is "
                      f"a negative control, not evidence about the floor)")

        old_t1, (_, injected) = top1(dumps["old"][0]), dumps["new"]
        new_t1 = top1(dumps["new"][0])

        # THE metric, and getting it right matters: a naive "real word -> injected key" rule
        # is WRONG, because an injected key is often the CORRECT answer. The first run of this
        # harness flagged `laurait` ('laurent' -> 'laurait') as a regression when the target
        # WAS `laurait` — the user swiped the elision `l'aurait`, the old floor returned the
        # wrong real word, and the new floor fixed it. Scoring by shape rather than by
        # correctness would have blocked a change that improves accuracy.
        #
        # So a regression is: the old floor got the target RIGHT and the new floor gets it
        # WRONG. Everything else is an improvement or a wash between two wrong answers.
        regressions, improvements, wash = [], [], []
        for target, new_word in new_t1.items():
            old_word = old_t1.get(target)
            if old_word == new_word:
                continue
            if old_word == target and new_word != target:
                regressions.append((target, old_word, new_word))
            elif new_word == target and old_word != target:
                improvements.append((target, old_word, new_word))
            else:
                wash.append((target, old_word, new_word))

        changed = len(regressions) + len(improvements) + len(wash)
        print(f"  top-1 changed on {changed} traces")
        print(f"  REGRESSIONS (was correct, now wrong): {len(regressions)}")
        for t, o, n_ in regressions[:10]:
            print(f"      {t:<16} {o!r} -> {n_!r}")
        print(f"  improvements (now correct):           {len(improvements)}")
        for t, o, n_ in improvements[:5]:
            print(f"      {t:<16} {o!r} -> {n_!r}")
        if wash:
            print(f"  wash (wrong either way):              {len(wash)}")
        worst = max(worst, len(regressions))

    print(f"\nworst per-corpus regression count: {worst}")
    if worst:
        print("FAIL: raising the injection floor let a pseudo-word steal a real word's swipe.")
        return 1
    print("PASS: no real word lost its own trace to an injected key.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
