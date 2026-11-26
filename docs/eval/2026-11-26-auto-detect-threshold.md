# Auto-Detect Language: default sensitivity (pre-registration, 2026-11-26)

Written and committed before any run of the dev or confirm half described below. Nothing in the
sections above "Result" is edited after the first run; results are appended under "Result".

## Why

A Pixel 8 Pro run (release r16, `7732198e1`, English + Spanish, Multi-Language and Auto-Detect on)
typed about 30 Spanish words and never switched. The detection path runs on every committed word
(`SuggestionHandler.updateContext` → `WordPredictor.addWordToContext` → `tryAutoLanguageDetection`
→ `MultiLanguageManager.detectAndSwitch`). The default threshold is the cause: a language's score is
absolute (`LanguageDetector.calculateLanguageScore`: 0.6 × letter-frequency fit + 0.4 × the share
of its 20 common words present), ordinary text in the chosen language scores about 0.45–0.55, and
the default `Defaults.LANGUAGE_DETECTION_SENSITIVITY` is 0.6. `LanguageDetectionEvalTest` at
`7732198e1` (all held-out text): 64–79% of 40-word runs in the secondary language never switch,
median 24–27 words where they do. The slider keeps its absolute meaning; only the default is in
question.

## What was seen before this document (disclosure)

On 2026-11-26, before the split below existed, a Python replica of the scorer was run on the FIRST
20,000 tokens of `heldout_en.txt` and the first 12,000 of `heldout_es.txt` (both halves, en + es
only): English → Spanish false switches 0 / 2.4 / 6.95 per 1,000 words at 0.60 / 0.50 / 0.45;
Spanish 40-word runs never switching 62% / 2% / 0%. Those numbers shaped the expectation that a
threshold near 0.5 is the candidate. They are not used for any decision here.

## Data and split

`~/.cache/cleverkeys-corpora/static-lm-eval/heldout_<lang>.txt` for en, de, fr, es, pt, sv (the
static-LM builder's held-out sentences, local only). A sentence is a line; its id is its 0-based
line index in the file. **Dev = even ids, confirm = odd ids.** Tokens: `[\p{L}']+`, lowercased, in
file order within the half. Limits: sentences of one source document fall into both halves (the
split is by sentence, not by document); the text is edited prose, not typed messages.

## Simulation (production rules)

The real `MultiLanguageManager.detectAndSwitch(window, threshold, {P, S})`, with
`WordPredictor`'s window rules: each word is appended, the window keeps the last 20, detection runs
once it holds 5 or more, and a switch clears it. Pairs (P + S): en+de, en+fr, en+es, en+pt, en+sv,
de+fr, fr+es, es+pt, de+sv. Code: `LanguageDetectionEvalTest.sweepDefaultSensitivity`, run with

```
AUTO_DETECT_EVAL_HALF=dev|confirm [AUTO_DETECT_EVAL_THRESHOLDS=0.40,0.45,...] \
scripts/gradle-guard.sh runMockTests -PtestClass=LanguageDetectionEvalTest -PgeoFull=true
```

Cells per pair and threshold, on one half:

- **FS** false switches: the first 20,000 tokens of P typed in one stream from P; switches to S
  per 1,000 words.
- **never / median**: up to 300 consecutive 40-word runs of S, each starting in P with an empty
  window; the share that never switch to S, and the median words to the switch over ALL runs (a run
  that never switches counts as longer than 40).
- **back**: up to 300 consecutive 40-word runs of P, each starting in S with an empty window (the
  state right after a switch); the share that switch to P within 20 words.

## Selection (dev half only)

Thresholds 0.40, 0.45, 0.50, 0.55, 0.60 (the slider's 0.05 steps). Chosen: the LOWEST threshold
with FS ≤ 3 per 1,000 words AND back ≥ 85% in every pair. If none qualifies, the default stays 0.6
and no confirm run is made.

## Gates (confirm half, the chosen threshold only, every pair)

- G1 FS ≤ 3 per 1,000 words of the primary language (English in the five English pairs);
- G2 never ≤ 15%;
- G3 median ≤ 20 words;
- G4 back ≥ 85% within 20 words.

All four pass in all nine pairs → `Defaults.LANGUAGE_DETECTION_SENSITIVITY` becomes the chosen
threshold (with every table or text that shows the default). Any failure → the default stays 0.6,
recorded here. No second threshold is tried on the confirm half.

## Result

### Dev half (2026-11-26, at `4ebb45e69`)

No threshold meets the selection rule, so **the default stays 0.6 and the confirm half was not
run** (as pre-registered). Worst pair per cell:

| threshold | FS, worst pair (/1,000) | pairs with FS > 3 | back, all pairs | never, worst | median, worst |
|---|---|---|---|---|---|
| 0.40 | 16.45 (fr+es) | 8 of 9 (en+es 8.30) | 93.7–99.0% | 0.3% | 10 |
| 0.45 | 12.40 (fr+es) | 4 of 9 (en+es 5.55) | 86.0–92.3% | 1.3% | 13 |
| 0.50 | 7.05 (fr+es) | 2 of 9 (en pairs ≤ 1.90) | 60.0–74.0% | 7.7% | 19 |
| 0.55 | 2.00 | 0 | 22.7–38.7% | 35.3% | 30 |
| 0.60 | 0.20 | 0 | 4.0–6.7% | 82.3% | > 40 |

The rule's two conditions do not overlap: false switches drop under 3 per 1,000 words only at
0.50 and above, where switching back within 20 words falls to 74% or less. The outcome does not
depend on reading G1 as "every pair" or "the English pairs only": at 0.45 en+es fails it, and at
0.50 every pair fails switch-back. Full log: the run's stdout (`AUTO-DETECT SWEEP  half=dev`).

### What this means

At 0.6, Auto-Detect almost never switches (63–82% of 40-word runs never do), and when it does, it
rarely comes back (back 4–7%). No absolute threshold works for both directions with this scorer:
one language's absolute score sits in a narrow band (about 0.45–0.55 on ordinary text) for both
the right and the wrong language. A fix needs a better scorer (or a margin between the two
chosen languages), which was ruled out of this change (the slider keeps its absolute meaning).
TODO(2.1): redesign the detector score, then repeat this protocol on the confirm half, which is
still unused.
