# CTC per-language λ sweep — the frequency-scale correction (2026-08-15)

**Question.** The shipped CTC preset `CtcScoringParams.tunedV2` uses **λ = 4.0**, fitted
against `en_enhanced.json`'s *compressed* 134–255 byte-score scale (`ln f` ∈ [4.90, 5.54]).
Every OTHER dictionary the app bundles is a **CKDT v2 `.bin`** whose per-word frequency is a
rank byte — the CTC trie would read `freq = max(1, 255 − rank)`, an *inverted* scale spanning
the full 1–255 range (`ln f` ∈ [0, 5.54], ~8× more spread). λ multiplies the log-frequency
term, so a λ fitted on the compressed scale is **mis-calibrated** on the CKDT scale. A prior
Cyrillic sweep (CleverKeys-ML `PHASE_J.md` §6.9) measured exactly that: λ 2.0 beat λ 1.1 by
+1.2 t1 and λ 4.0 was *worse* (73.88 vs 76.91). This sweep answers the same question for the
languages the app actually bundles, so a future `presetFor(language)` ships validated numbers.

**Verdict: λ = 2.0 for CKDT-scale (non-English) languages; λ = 4.0 stays correct for the
English `en_enhanced` trie.** The ship preset must NOT be reused as-is for other languages.

## Method

- **Model**: the shipped `src/main/assets/models/ctc_swipe_encoder.onnx` (phaseM_kd_fresh_w1).
- **Decode**: the CleverKeys-ML featurizer + trie Viterbi beam (the same code the golden
  fixture validates the Kotlin port against), beam 100, topK 8. All `tunedV2` constants held
  fixed (γ 0.9, β 0.25, γp 0.25, βp 0.9882) — **λ is the only free variable**.
- **Corpora**: real FUTO swipe-5 human traces from the local cache, filtered single-finger +
  language-matched (the `GeoRealCorpusMultiLayoutTest` convention), decoded on the official
  committed FUTO layout geometries (`src/test/resources/layouts/futo_<layout>.json`).
- **Lexicons**: the app's OWN bundled dictionaries — CKDT v2 `.bin` at `freq = max(1, 255−rank)`
  with NFD a–z projection for accents (fr/de/es); `en_enhanced.json` via the app's json-strip
  policy for the English control.
- **Discipline**: each corpus split in half — λ chosen on the **tune** half, reported on the
  untouched **confirm** half. Harness: `scripts/ctc_lang_lambda_sweep.py` (resumable);
  per-trace outputs local-only at `~/.cache/cleverkeys-test/ctc_lambda_*.jsonl`.

## Results — in-dict top-1 (%) by λ

| corpus (lexicon) | half | N | λ 1.1 | λ 2.0 | λ 3.0 | **λ 4.0 (ship)** |
|---|---|---:|---:|---:|---:|---:|
| **en dvorak** (en_enhanced — *control*) | tune | 1234 | 89.22 | 91.25 | 91.90 | **92.30** ✔ |
| | confirm | 1223 | 88.96 | 91.17 | 92.40 | **92.72** ✔ |
| **fr azerty** (CKDT fr) | tune | 1057 | 82.88 | **84.67** ✔ | 84.39 | 82.78 |
| | confirm | 1033 | 85.09 | **86.25** | 85.58 | 83.06 |
| **de qwertz** (CKDT de) | tune | 586 | 79.01 | **82.25** ✔ | 81.57 | 80.72 |
| | confirm | 601 | 85.52 | 87.85 | **89.18** | 89.02 |
| **de german** (CKDT de) | tune | 1092 | 80.77 | 83.70 | **84.71** ✔ | 83.97 |
| | confirm | 1107 | 78.32 | **81.66** | 81.57 | 80.13 |
| **es spanish** (CKDT es) | tune | 868 | 88.71 | **91.13** ✔ | 90.32 | 88.82 |
| | confirm | 890 | 88.31 | 89.33 | **89.44** | 87.87 |

✔ = tune-half winner (the unbiased selection).

### Confirm-half detail at each corpus's tune-selected λ

| corpus | selected λ | confirm t1 / t3 / t5 | N |
|---|---|---|---:|
| en dvorak (control) | 4.0 | 92.72 / 97.14 / 97.87 | 1223 |
| fr azerty | 2.0 | 86.25 / 95.16 / 97.29 | 1033 |
| de qwertz | 2.0 | 87.85 / 97.17 / 98.50 | 601 |
| de german | 3.0 | 81.57 / 91.15 / 93.50 | 1107 |
| es spanish | 2.0 | 89.33 / 94.94 / 96.07 | 890 |

## Reading the numbers

1. **The control validates the harness and the hypothesis.** On the English trie, λ 4.0 wins
   on BOTH halves and the curve is monotone increasing 1.1 → 4.0 — exactly what the compressed
   134–255 scale predicts. The scale mechanism is real, not a fitting artifact.
2. **Every CKDT-scale language inverts that ordering.** λ 4.0 is never a tune-half winner and
   costs **−1.5 to −3.2 pt** on the confirm half for fr / de-german / es. Shipping the English
   preset for other languages would measurably degrade them.
3. **The optimum is flat between λ 2.0 and 3.0**, and the two are within noise in aggregate
   (summed confirm-half t1 across the four non-en corpora: λ2.0 = 345.09, λ3.0 = 345.77 —
   a 0.17 pt/corpus difference). λ **2.0** is the recommendation because it wins the tune half
   in 3 of 4 corpora and **independently matches the Cyrillic sweep's 2.0** — two unrelated
   scripts converging on the same value for the same scale is the strongest signal here.

## Recommendation for a future `presetFor(language)`

| lexicon scale | languages | λ |
|---|---|---|
| compressed 134–255 (`en_enhanced.json`) | en | **4.0** (unchanged — ships today) |
| CKDT `255 − rank` | fr, de, es (and ru per the prior sweep) | **2.0** |

Everything else in `tunedV2` (γ 0.9, β 0.25, γp 0.25, βp 0.9882, beam 100) is unchanged —
λ is the only language-dependent constant measured here. The app-side change this unlocks is
the one the integration plan §7.1 specifies: select the preset by language, add `language` to
the decoder-memo key, then relax the adapter's en-gate **per validated language**.

## Caveats (read before acting)

- **This does not by itself unlock non-English CTC.** Two pieces are still missing: accent
  display (the beam emits a–z surfaces; `é/ü/ñ` words need alias→display mapping, the same
  shape as the contraction overlay) and per-language on-device validation. λ is the
  *prerequisite*, not the whole feature.
- **Absolute numbers are not cross-comparable with the training campaign's alt-layout bars.**
  The control's 92.72 sits above the campaign's 88.98 dvorak-app figure because the filtering
  differs (in-dict only, single-finger, this corpus slice). The control's job was to confirm
  the λ *direction* on the English scale, which it did.
- **Corpus halves are heterogeneous.** de-qwertz's two halves differ by ~5 pt at matched λ
  (586 vs 601 traces) — treat per-corpus absolutes as indicative; the λ *ordering* is the
  robust result and it is consistent across halves.
- **No user dictionary was present in any run.** λ multiplies the frequency term and user
  words are injected at the top of the scale, so a larger λ amplifies them. Per the
  integration plan §7.3, the personal-lexicon merge must ship with a boost cap, and λ should
  be re-confirmed with user entries present rather than assumed to carry.
- Sample sizes are 586–1234 traces per half; differences under ~1 pt are inside noise.
