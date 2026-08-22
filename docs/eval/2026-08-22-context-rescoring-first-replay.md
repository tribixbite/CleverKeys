# Context rescoring — first replay results

**Date**: 2026-08-22 · **Harness**: `ContextRescoringReplayTest` (`-PgeoFull=true`)
**Engine**: geometric · **Language**: en · **Corpus**: Ubuntu Dialogue derived bigrams

> **This document was rewritten after an audit. Two earlier sets of numbers published here were
> WRONG and are retracted — see §5.** Do not cite "29 fixed / 19% fix rate / inert on 97%" from any
> earlier revision.

**Verdict: benefit looks real WHERE THE FEATURE FIRES. The activation rate is NOT measured, and
safety remains underpowered. Not sufficient to flip the default.**

---

## 1. Numbers

```
corpus     : 175,092 rows; 56,923 above the store's frequency floor; 22,586 promotable
             STORED=10,000  <-- the store cap discarded 46,923 usable rows
pairable   : 27,970 bigrams have a trace for their second word
queryable  :    225 of those survived seeding and can actually be looked up
sampled    :    225 at random (seed 20260822), NOT the frequency-sorted head

decoded    : 918 distinct (context, trace, arm) cases   [NOT "swipes" — see §3]
exposure   : favourable=243  adversarial=15   <- the only cases where rescoring could act

favourable : n=340  fixed=51  broken=0  wash=0   Δtop1=+0.1500
adversarial: n=578  fixed=0   broken=0  wash=13  Δtop1=+0.0000
COMBINED   : n=918  fixed=51  broken=0  wash=13  Δtop1=+0.0556  errRatio=0.000
```

## 2. What these mean

**Given that it fires, the effect is substantial: 51 fixes in 243 exposed favourable cases ≈ 21%,
with zero regressions.** That is the headline and it is genuinely encouraging.

**The activation rate is NOT measured and must not be inferred from this run.** Only 225 of 27,970
pairable bigrams were queryable, but that ratio is a HARNESS ARTEFACT, not a device property: the
corpus has 175,092 rows and `BigramStore` caps a language at `MAX_TOTAL_BIGRAMS = 10,000`, pruning
by probability. Seeding 17.5x the capacity discards most of it. On a real device the 10,000 stored
bigrams are the user's OWN most-probable pairs — exactly the ones they type — so their hit rate
would be far higher. **Nothing here measures that number, and it is the one that converts "21% when
it fires" into a real-world expectation.**

**Safety is still underpowered.** The adversarial arm exposed only 15 cases. Zero breakages across
15 exposures cannot bound a <20% breakage rate. The 13 `wash` outcomes do show the rescorer acting
adversarially — changing top-1 where both answers were wrong — so the arm is not inert; it is just
small.

**The store cap is a real product fact worth carrying elsewhere.** A user's context model holds at
most 10,000 bigrams per language and keeps the highest-probability ones. The feature can only ever
fire on that set.

## 3. Units — read this before quoting any figure

`n` counts **(context, trace, arm) cases**, not swipes. The same physical trace appears under
several different preceding words, and those are separate experiments because the context is the
independent variable. A denominator described as "swipes" would be wrong, and an earlier revision
of this document made exactly that error.

## 4. Limitations that must travel with these numbers

1. **Geometric engine only.** A CTC arm is owed before any default flip: CTC is the DEFAULT engine,
   its slate is a softmax scaled 0..1000, and the rank-1 guard is a ratio of exactly those scores.
   This is now possible in pure JVM — `extractOrtNative` supplies the bionic ONNX natives — and is
   the single highest-value next measurement.
2. **The favourable/adversarial ratio is a sampling choice**, not a measured fact about real typing.
3. **Bigrams only.** Trigrams are excluded from the export and not derived from the corpus, so the
   sharper trigram branch was never exercised. Measured gain is a floor.
4. **Corpus register.** Ubuntu Dialogue is typed IRC chat — real typos and shorthand, tech-support
   topic. Its top bigrams match the maintainer's own device data (`in→the`, `i→don't`, `want→to`),
   which is why it is usable at the head of the distribution.
5. **`WEIGHT` untuned** — the design's starting 0.5, no tune/confirm split.
6. **One corpus, one language, one engine.**

## 5. Retractions — what was published here and why it was wrong

An adversarial audit found four biases, all of which flattered the feature. Each was fixed and the
numbers changed materially. Recorded because the same mistakes are easy to repeat.

| # | Error | Effect | Fix |
|---|---|---|---|
| H1 | `take(1500)` applied to a **frequency-sorted** corpus file — a size cap that was silently a *selection* of the strongest pairs. A `Random(SEED)` sat unused three lines away, with a KDoc claiming it made runs reproducible. | measured only the extreme head | shuffle with the seed, actually use it |
| H2 | Hapax rows dropped **before** seeding, shrinking the denominator `BigramStore` divides by. Verified against `word1Frequencies`: the device counts every observation. | probabilities roughly doubled (`i→don't` 0.084 vs 0.046 true) | emit the tail; the store's floor ignores it for scoring but counts it in p(w2\|w1) |
| H3 | Denominator labelled "swipes" when it was context-trace cases | wrong unit | label correctly (see §3) |
| H4 | One global `withEvidence` counter | safety denominator had to be inferred across two runs | per-arm counters |

**Two further errors were mine, made while fixing those**, and are worth recording separately:

- **Over-correcting H3**: I first deduped by *trace*, which deleted the context dimension — the
  independent variable. Distinct contexts on the same trace are distinct experiments.
- **Missing the store cap entirely** until the corrected numbers became implausible (2 activations
  in 725 cases). A real effect does not vanish that completely; the implausibility was the signal
  that the instrument, not the feature, was broken.

Sequence of published headline numbers: **29 fixes (head-biased) → 2 fixes (over-deduped and
mostly evicted) → 51 fixes (this document)**. The first two are retracted.

## 6. What would make this decisive

- **A CTC arm** — now unblocked, highest value.
- **A device-corpus arm** (the maintainer's 642 usable pairs) — small enough to fit under the 10k
  cap with no eviction, so its survival rate would be ~100% and its activation rate meaningful.
- **A larger adversarial sample selected for exposure** — 15 exposed cases is not a safety result.
- **A `WEIGHT` tune/confirm split** once the signal is stable.

## 7. Reproduce

```sh
python3 scripts/build_ubuntu_bigrams.py \
  --archive ~/.cache/cleverkeys-corpora/ubuntu_dialogs.tgz \
  --out ~/.cache/cleverkeys-corpora/ubuntu_bigrams.json
sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest -PgeoFull=true
```

Neither corpus is committed: one is a person's typing record, the other a 552 MB third-party corpus
with no stated licence.
