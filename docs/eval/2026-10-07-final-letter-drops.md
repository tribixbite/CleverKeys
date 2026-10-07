# Final-letter drops (`adb` → `an`, `somethings` → `something`) — investigation

Date: 2026-10-07. Scope: English, QWERTY, default CTC engine, input as shipped after 745d1ca3
(unsmoothed samples + one finger-lift sample). Companion to
`docs/eval/2026-10-07-short-word-ctc.md` (same harness, same dev/held-out split).

Maintainer report: "I can never swipe `adb` — I get `an` — and when I swipe `somethings` I
get `something` even though it should be super obvious I swiped all the way back to the s."

**Result in one paragraph.** The two words fail for different reasons, and neither is a
decoder defect in our code. `adb` is in no English swipe lexicon and has no training traces; a
personal-dictionary entry does reach the CTC trie (prior at the scale cap), but the encoder
emits nothing for `d` and reads the stop on `b` as `n`, so `adb` stays at rank 3–8 on smooth traces. It reaches rank 1 only when trace wobble supplies a turn at `d`. `somethings`
is in the lexicon (byte 168 against `something` 219); the encoder does emit the final `s`
(posterior ≥ .96), and the word wins a straight synthetic trace by under 0.2 final-score points
— a near-tie that a slower ending loses. On 1,364 real s-plural traces the decoder does not
systematically prefer the stem: of 37 real prefix drops, none had an encoder score that
favoured the longer target. No decoder change met the pre-registered bar; nothing ships in the
app. The tests and harness additions below record the measurement, and the training recipe is
in §6.

## 1. Pre-registration (written before any held-out class was read)

Written 2026-10-07 14:26 local, after the dev and held-out dumps were started and before
either was analysed.

H3: final-letter drops on long s-final words come from the final-score form
`ctc/len^γ + β·len + λ·ln f`. The raw CTC evidence for the extra letter is divided by
`len^0.9`, while the frequency gap is not divided.

Candidates: global (γ, β, λ) re-weighting, chosen on **dev only** (3,000 hwsfuto val rows,
shipped input). No per-word or per-suffix rules.

Held-out bar (all 4,000 FUTO swipe-1 test rows, shipped input). All four must hold:
1. Overall top-1 change ≥ 0.0 pt, and the loss/gain split is not significantly worse
   (one-sided sign test p < 0.05 fails).
2. s-plural class (target = stem + `s`, stem in lexicon, stem more frequent): top-1 +5 pt or
   more, and fewer stem wins.
3. Stem class (targets whose `+s` form is in the lexicon): top-1 change ≥ −1 pt.
4. ≤3-letter stratum top-1 change ≥ −0.5 pt. Overall top-3 change ≥ −0.3 pt.

Distinct traces and distinct words are reported for every class.

## 2. Vocabulary and the user-dictionary path

| word | `en_enhanced.json` (CTC) | `en_enhanced.bin` (geometric) | hwsfuto train traces (110,876 rows) |
|---|---|---|---|
| `adb` | absent | absent | 0 |
| `an` / `ab` / `ad` / `and` | 233 / present / 199 / 249 | present | 240 / 4 / 5 / 1,183 |
| `somethings` | 168 | present | 0 |
| `something` | 219 | present | 9 |
| things/thing, others/other, words/word, keyboards/keyboard | 218/217, 212/226, 209/209, 173/187 | present | 12/11, 40/73, 12/14, 3/3 |

`somethings` has λ·Δln f = 4·ln(219/168) = 1.06 against its stem. That puts it in the top 2 %
of the lexicon's 16,341 s-plural/stem pairs: 337 pairs have a gap ≥ 1.0, and the largest are
`ands` 1.92 and `whens` 1.79.

**User words reach the CTC lexicon.** `CtcEngineAdapter.lexiconFor` merges the
`custom_words_<lang>` preference with the platform user dictionary
(`UserDictionarySnapshot.mergeWithCustom`, ARC-081). `CtcLexiconMerge.merge` then maps the
stored frequency onto the base scale: the default 255 maps to 255, the cap. Both inputs are part
of the memo key, so a new word is picked up at the next decode. The test
`CtcFinalLetterReplayTest.customAdbReachesTheFinalBeam` shows this end to end. Words that
personalization learned automatically (`UserVocabulary`) are not added to any swipe lexicon,
by design (see the 2026-09-26 learned-prior notes). The supported route for a swipe word is the
"Prefer … when swiping?" offer, which writes a personal-dictionary entry at 255.

## 3. Synthetic traces through the shipped path (stage by stage)

Traces are on the golden layout, built by `straight()` (constant speed) or an eased variant
that slows to zero at every key. "Lift" adds the ACTION_UP sample. Each score is broken down as
normalized CTC + β·len + λ·ln f. The Python mirror was used here, and the Kotlin replay
reproduces it (§7).

**`adb` (`adb` injected at 255):**

| shape | greedy | last frame | `adb` rank | `adb` raw CTC / final | `an` raw CTC / final | `ab` final |
|---|---|---|---|---|---|---|
| straight | an | n .86, b .10 | 7 | −11.63 / 18.59 | −0.17 / 22.21 | 20.18 |
| straight + lift | an | n .59, b .31 | 8 | −11.92 / 18.48 | −0.54 / 22.01 | 20.77 |
| straight + 200 ms stop + lift | an | n .47, b .43 | 5 | −10.40 / 19.04 | −0.79 / 21.88 | 20.93 |
| eased (stops at d) + lift | ab | b .63, n .32 | 3 | −4.98 / 21.06 | −1.18 / 21.67 | 21.13 |

- **Lexicon:** this is a coverage failure without the user entry. With the entry, the prior
  favours `adb` (λ·ln f 22.17 against `an` 21.80).
- **Emissions — the binding stage.** Frame 0 emits `a`, frame 31 emits the end letter, and
  nothing is emitted in between, even when the trace stops at `d`. In pixel space the
  a→d→b path bends by only about 28° at `d` (the letter box is 1000 × 470 px), and the encoder
  emits interior letters only at clear turns. A trace ending on the centre of `b` is read as
  `n` at .86: the word prior (`an` 240 training traces, `ab` 4) acts on the end letter. This is
  the same mechanism as `ad` → `as` in the short-word note.
- **Beam / normalization / λ / overlays:** none of these is the cause. `adb` is in the final
  beam, its prior is the highest of the cohort, and no post-decode step touches it. The gap is
  raw CTC (≥ 4 nats normalized on constant-speed traces).

**`somethings` against `something` (shipped lexicon):**

| shape | last frame | raw CTC gap (plural − stem) | normalized gap | β gap | λ gap | margin |
|---|---|---|---|---|---|---|
| straight | s .97 | +6.82 | +0.97 | +0.25 | −1.06 | **+0.15** |
| straight + lift | s .99 | +6.88 | +0.97 | +0.25 | −1.06 | **+0.15** |
| straight + 200 ms stop + lift | s .98 | +5.44 | +0.78 | +0.25 | −1.06 | **−0.04** (stem wins) |
| eased + lift | s .99 | +5.96 | +0.84 | +0.25 | −1.06 | **+0.03** |

The encoder does its part: frame 27 emits `g`, frame 31 emits `s`, and the long return stroke
is read correctly. The old smoothed input path gives the same margins (±0.1), so 745d1ca3 is
neither the cause nor the fix. What decides is λ·Δln f (1.06) against an extra-letter
evidence of about 6–7 raw nats, which length normalization shrinks to about 0.8–1.0 for a
10-letter word. The result is a coin flip decided by trace noise. Common plurals with small
prior gaps keep clear margins on the same shapes: `things` +1.7 to +2.1, `words` +1.8 to +2.2,
`others` +1.1 to +1.35, `keyboards` +0.6 to +0.8.

With `somethings` added to the personal dictionary at 255, the margin becomes about +1.8. It
is rank 1 on the straight and wobbled traces (pinned by
`personalDictionarySomethingsWinsItsTrace`).

### 3.1 Kotlin replay of the shipped stack (`CtcFinalLetterReplayTest.finalLetterDiagnostic`)

Real `CtcFeaturizer` + ONNX session (XNNPACK, 2 threads) + `CtcBeamDecoder`. Shapes come from
`CtcTraceShapes`. The straight rows match the Python mirror (`adb` −3.62 against −3.62;
`somethings` +0.156 against +0.15).

| target (rival) | straight | wobble .02 | wobble .035 |
|---|---|---|---|
| `adb`, custom 255 (`an`) | rank 7, −3.62 | rank 3, −0.93 | **rank 1, +0.91** |
| `somethings` (`something`) | rank 1, +0.16 | rank 1, +0.16 | **rank 2, −0.007** |
| `things` / `others` / `words` | +1.88 / +1.12 / +2.10 | +1.73 / +1.19 / +2.10 | +1.92 / +1.36 / +2.24 |
| `keyboards` / `emails` / `tests` / `apps` | +0.80 / +1.57 / +1.81 / +2.39 | +0.82 / +1.56 / +1.90 / +2.38 | +0.81 / +1.57 / +1.71 / +2.38 |

The wobbles say the same thing from both sides. A shape that adds curvature lets the encoder
emit `d` (`adb` then wins), and ordinary trace noise flips the `somethings` near-tie. The
ordinary plurals hold rank 1 on every shape.

## 4. Real human traces

Input is shipped (`--emu fix`). Targets are in-lexicon, since an out-of-lexicon target can
never be top-1. Dev = 3,000 val rows. Held-out = 4,000 FUTO test rows. Secondary = every
s-plural row of the local proshian corpus (614 usable), which is not a gate.

| corpus / class | traces | words | top-1 | stem wins (prefix drop) |
|---|---|---|---|---|
| dev, all | 2,919 | 1,506 | 91.37 | 10 |
| dev, s-plural | 307 | 208 | 86.97 | 3 |
| dev, s-plural, stem more frequent | 207 | 160 | 87.44 | 0 |
| held-out, all | 3,913 | 1,668 | 94.17 | 16 |
| held-out, s-plural | 443 | 255 | 90.74 | 2 |
| held-out, s-plural, stem more frequent | 255 | 203 | 88.63 | 2 |
| held-out, stem class | 1,725 | 695 | 93.97 | 5 |
| secondary, s-plural | 614 | 389 | 84.53 | 11 |

When both forms survive to the final beam, the stem scores below the plural in 286/291 dev
and 433/437 held-out s-plural traces. The median margin is +1.51 / +1.69, and the median
λ·Δln f is only 0.12 / 0.08, because most plurals are not `somethings`.

**Anatomy of every real prefix drop** (37 traces, about 34 distinct words, across the three
corpora). In 35 of them both words are in the beam. In all 35 the raw CTC score of the
**longer target is ≤ the shorter top-1** (one tie, `too`/`to`). So the encoder itself
preferred the shorter word, and the final-score form never overturned an encoder preference
for the longer word. In 29 of the 37, the greedy path already lacks the final letter (`users`
→ `user`, `tasks` → `task`, `islands` → `island`). The rest are double letters (`too`, `off`,
`ill`, `dcc`, `triplett`), where CTC needs a blank between repeats.

**Final stroke length** (distance from the second-to-last key to `s`, in key pitches):

| corpus | ≤ 1.1 key (`ds`, `as`, `es`, `ws`, …) | > 1.1 key |
|---|---|---|
| dev | 54 traces / 21 words, top-1 83.33, 1 stem win | 253 / 187, 87.75, 2 |
| held-out | 117 / 23, 96.58, 0 | 326 / 232, 88.65, 2 |
| secondary | 101 / 43, 78.22, 7 (6.9 %) | 513 / 346, 85.77, 4 (0.8 %) |

The short-hook endings (`stands`, `sounds`, `words`, `birds`, `islands`) carry most of the
secondary corpus's stem wins. The held-out short-stroke class is concentrated (117 traces
over 23 words) and shows none. This is the `ad`/`as` encoder silence in reverse, and it is
model-side.

## 5. Decoder candidates (H3) — rejected on dev, held-out never read for a candidate

`grid` was run on dev: 252 (γ, β, λ) combinations with γ ∈ [0, 1.1], β ∈ [−0.5, 1], λ ∈ [1, 8].

| form | dev top-1 (all 3,000 rows) | dev s-plural, stem more frequent (207) |
|---|---|---|
| shipped (0.9, 0.25, 4) | 88.90 | 87.44 |
| dev-best overall (1.0, 0.25, 3) | 89.17 | 86.96 |
| any form with s-plural (307) ≥ +2 pt and overall ≥ −0.2 pt | none | — |

No candidate improves the s-plural class without costing overall accuracy, and the dev-best
overall form makes that class worse. Bar 2 cannot be met, so no candidate was taken to
held-out. The shipped preset stays. `λ` remains the measured optimum from the short-word note.

## 6. Training recipe additions (CleverKeys-ML `ctc/`; extends short-word note §6)

These do not hack ranks and add no per-word lists. Selection is by geometry and corpus count.

1. **Word-balanced sampling** (short-word §6.1, τ ∈ {0.5, 0.7}). This targets the end-letter
   prior that reads a stop on `b` as `n` and a stop on `d` as `s`.
2. **Interior-turn coverage.** Add synthetic rows (SYNTH_V2) for lexicon words with fewer than
   3 training traces whose path turns by less than 35° in pixel space at an interior key
   (`adb`-class), plus a stop variant at that key. Report the forced-alignment emission of the
   interior letter as the success signal, not top-1 alone.
3. **Short final hooks.** Add synthetic s-plurals (and `-ed`/`-er`) whose last stroke is ≤ 1.1
   key, with the end offset drawn from the real along-track distribution (short-word §3), and
   both precise-stop and overshoot variants. Mix ≤ 10 % of steps with item 2.
4. **End-of-trace emphasis.** Oversample rows whose last stroke is short (≤ 1.1 key) by 2×
   inside the balanced sampler, rather than reweighting CTC frames, which the loss cannot
   localize.
5. **Bars (pre-register):** the short-word §6.4 bars, plus held-out s-plural top-1 ≥ shipped
   and the secondary short-hook class stem wins ≤ half of shipped (7 → ≤ 3), reported as
   distinct words and traces. After the model changes, re-sweep λ on dev (it is expected to
   fall as the encoder's internal prior shrinks) and regenerate the golden fixture at the ship
   preset.

The large-prior-gap plurals (`somethings`) are not a model problem. The prior reflects real
usage, and the remedy for one user is the personal dictionary (§3).

## 7. Tests and reproduction

- `src/test/kotlin/tribixbite/cleverkeys/swipe/CtcFinalLetterReplayTest.kt` (runPureTests,
  ORT-gated) contains the diagnostic table plus four pins:
  - lexicon coverage (`adb` absent, `somethings` < `something`);
  - a custom `adb` reaches the final beam;
  - a personal-dictionary `somethings` wins by more than 1.0;
  - ordinary plurals keep their `s`.

  It does not pin the `adb` loss.
- `CtcReplayEngine.decoderWithLexicon(merged, topK)` gains a `topK` parameter so the whole
  final beam can be read.

```bash
S=build/final-letter; mkdir -p $S
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/val_ordered.jsonl.gz $S/dev.jsonl --sample 3000 --emu fix
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz $S/held.jsonl --emu fix
python3 scripts/short_word_ctc_eval.py classes $S/dev.jsonl $S/held.jsonl
python3 scripts/short_word_ctc_eval.py grid $S/dev.jsonl
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.CtcFinalLetterReplayTest
```

## 8. Device check

1. Swipe `somethings` (stop cleanly on `s`). Expect `somethings` or `something` at rank 1, with
   the other one slot away. Accept "Prefer … when swiping?" when offered, or add `somethings`
   under Settings → Languages → Personal dictionary. After that it should win each time.
2. Add `adb` to the personal dictionary, then swipe a→d→b. Expect `adb` in the slate's lower
   slots on a quick swipe, and closer (rank about 3) when you pause on `d` and on `b`. It will
   not reliably be rank 1 until the encoder is retrained (§6.2).
