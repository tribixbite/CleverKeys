# Context rescoring — first replay results

**Date**: 2026-08-22 · **Harness**: `ContextRescoringReplayTest` (`-PgeoFull=true`)
**Engine**: geometric · **Language**: en · **Corpus**: Ubuntu Dialogue derived bigrams

**Verdict: encouraging on benefit, UNDERPOWERED on safety. Not sufficient to flip the default.**

---

## Numbers

```
corpus     : ubuntu_bigrams.json — 56,923 usable pairs, 31,941 promotable
trace pool : 4,907 distinct words (combined_english_swipes, 8,607 traces)
pairable   : 27,970 of 56,923 usable bigrams have a trace for their second word
sampled    : 1,500 pairs x 2 traces + 2 confusable decoys each

decoded    : 6,353 traces — but only 193 (3.0%) had ANY context evidence

favourable : n=2,613  fixed=29  broken=0  wash=0  unchanged=2,584   Δtop1=+0.0111
adversarial: n=3,740  fixed=0   broken=0  wash=0  unchanged=3,740   Δtop1=+0.0000
COMBINED   : n=6,353  fixed=29  broken=0  wash=0  unchanged=6,324   Δtop1=+0.0046
```

## What these mean

**The feature is inert on ~97% of swipes.** Only 193 of 6,353 traces had a single slate candidate
with a confident learned continuation. This is the most important number here and it reframes
everything else: both the upside and the risk are confined to a thin slice, so a Δtop-1 averaged
over all traces understates the within-slice effect by roughly 3x. **Always state which
denominator a figure uses.** Within the favourable slice the fix rate is 29/150 ≈ **19%**.

**Benefit is real but small in absolute terms.** 29 decodes that were wrong became right, zero
regressions among them.

**Safety is not established.** Zero breakages sounds decisive and is not:

- The adversarial arm carried only ~43 traces with any context evidence (193 total minus the
  favourable arm's 150). **0 breaks out of ~43 exposed cases cannot bound a <20% breakage rate.**
- Three successive versions of the adversarial arm each produced "0 broken" for a *different*
  reason, and only the third tested anything (see below).

## Why "0 broken" took three attempts to mean anything

| Run | Adversarial sampling | broken | Why zero |
|---|---|---|---|
| 1 | none | 0 | **The sampling never generated the case.** Pairing `(w1, w2)` and swiping `w2` means context always points AT the target, so only fixes are possible. |
| 2 | random decoy words | 0 | **Rescoring reorders, it cannot insert.** A breakage needs the learned continuation to already be in the slate; a slate for "hello" holds words near "hello", so a continuation of an unrelated word is not there to promote. ~0 of 2,012 decoys had evidence. |
| 3 | words confusable with `w2` (same initial, length ±1) | 0 | The first version that reaches the damage surface — evidence exposure rose 150 → 193. Still zero, on a small sample. |

Run 2's finding is worth keeping independently of the result: **the damage surface is
intrinsically narrow**, because context can only reorder candidates the decoder already found
plausible for that exact trace. That is a structural property of where the rescorer sits, not an
artefact of this harness.

## Limitations that must travel with these numbers

1. **Geometric engine only.** The CTC encoder is ONNX and the desktop ONNX runtime cannot load on
   this device (`libonnxruntime.so` needs glibc's `libdl.so.2`; Termux is bionic — probed
   2026-08-22). CTC is the DEFAULT engine and its slate is a softmax scaled to 0..1000, while the
   rank-1 guard is a ratio of exactly those scores. **A CTC arm is owed before any default flip**
   and must be an instrumented run.
2. **The favourable/adversarial ratio is a sampling choice, not a measured fact.** Converting
   these counts into a real-world expectation requires knowing how often a user's next word IS
   their learned continuation. Nothing in this repo measures that.
3. **Bigrams only.** Trigrams are excluded from the app's export and were not derived from the
   corpus, so the sharper trigram branch was never exercised. Measured gain is a floor.
4. **Corpus register.** Ubuntu Dialogue is typed IRC chat — real typos and shorthand, but
   tech-support topic. 20.7% of utterances carry a tech term (filtered out); the surviving
   high-frequency bigrams match the maintainer's own device data on `in→the`, `i→don't`,
   `want→to`, `you→can`, which is why it is usable as a proxy at the head of the distribution.
5. **`WEIGHT` was not tuned.** Everything above uses the design's starting 0.5 with no
   tune/confirm split.

## What would make this decisive

- A CTC arm, instrumented (ew-cli), same two-arm design.
- A larger adversarial sample specifically selected for *evidence exposure* — decode first, keep
  only traces whose slate contains a learned continuation, then measure. That inverts the current
  order and would spend every decode on the damage surface.
- The device-export arm as a second corpus, for register comparison (642 usable pairs).
- A tune/confirm split on `WEIGHT` once the above shows a stable signal.

## Reproduce

```sh
python3 scripts/build_ubuntu_bigrams.py \
  --archive ~/.cache/cleverkeys-corpora/ubuntu_dialogs.tgz \
  --out ~/.cache/cleverkeys-corpora/ubuntu_bigrams.json
sh gradlew runPureTests -PtestClass=swipe.ContextRescoringReplayTest -PgeoFull=true
```

Neither corpus is committed: one is a person's typing record, the other is a 552 MB third-party
corpus with no stated licence.
