# User swipe priority — measurement and design

Date: 2026-10-08. Scope: English, QWERTY. The CTC engine is the default; the geometric engine is
measured in §6. This note follows `docs/eval/2026-10-07-short-word-ctc.md` and
`docs/eval/2026-10-07-final-letter-drops.md` and uses the same harnesses.

**The request.** The maintainer asked for a fix based on a frequency setting: users should be
able to raise a word's swipe priority. The reported words are `ad` (read as `as`), `wet` (`we`),
`adb` (`an`) and `somethings` (`something`).

**Result in one paragraph.** The per-word frequency cannot be the lever. Its default, 255, is
already the top of the scale, and a personal-dictionary word already enters both swipe lexicons
there. What ships is a separate, bounded **swipe priority** for personal-dictionary words:
Normal / High / Highest. Each level adds a fixed bonus to the word's final score in each
engine. The bonus applies only to candidates the engine already kept: words in the CTC beam,
and words that pass the geometric pruner. On the shipped CTC stack, **Normal** is enough for
`somethings`. **High** (+2.0 nats) makes `ad` and `wet` win all six synthetic shapes. Only
**Highest** (+4.0 nats) makes `adb` win the straight shapes. The cost falls on the boosted
word's nearest rival, and it depends heavily on the pair. Raising `ad` to High takes 12 of the
30 `as` swipes that dev and held-out decoded correctly, and 349 of 877 in the focused corpus.
Raising `wet` to High takes 2 of 12 `we` swipes, and no rival swipes at all in the focused
corpus. Raising `adb` to Highest takes 1 of 31 `an` swipes, and 20 of 462 in the focused
corpus. On the geometric engine all four words already win as plain personal-dictionary
words. The same two bonuses take a similar share of correct swipes there as on CTC: about 0.02 %
at High and 0.06 % at Highest for a typical raised word (§6). Words the user never raises
decode exactly as before: the decoder skips every priority lookup, and a test pins the result
as byte-identical.

## 1. Why frequency cannot do it

Checked against the code (`UserWordFrequency`, `CtcLexiconMerge`, `GeometricUserWordMerge`):

- `UserWordFrequency.DEFAULT` is the scale maximum (255). `CtcLexiconMerge` maps a stored 255
  onto the base lexicon's own ceiling (255; the en floor is 134). The stored frequency can
  therefore only lower a user word, never raise it.
- Raising λ, or allowing frequencies above 255, would break three things. The en preset's
  λ = 4.0 is fitted to the 134..255 scale. `ContractionOverlay`'s real-word guard reads
  frequency ordinals (`REAL_WORD_ORDINAL_MAX`), and custom words already rank 0. And
  `PairingBaseFrequencies` compares a base word's frequency with its contraction's: an `id`
  raised past 255 would stop `i'd` from being promoted. The priority bonus touches none of
  these. It is a separate final-score term, and the merged frequencies, ordinals and pairing
  frequencies are byte-identical with or without it.
- The engines read the stored frequency differently. CTC calibrates it onto the base scale.
  Geometric uses it only to order the user words among themselves, ahead of the base words.
  Tap prediction uses `255 − stored` as a rank. A level therefore has to be defined per engine
  (§6, §7) rather than as one number.
- `wet` is already in the base lexicon at 195. As a personal-dictionary word it shadows the
  base entry and gains λ·ln(255/195) = 1.07 nats, which is the "Normal" row below. `adb` is in
  no lexicon. As a personal-dictionary word it reaches the beam at the cap and still trails `an`
  by 3.4–3.6 nats on straight traces. Frequency cannot close that gap.

## 2. Targets on the shipped CTC stack (Kotlin replay)

`UserSwipePriorityReplayTest.prioritySweepDiagnostic`: the real `CtcFeaturizer`, ONNX encoder
(XNNPACK, 2 threads) and `CtcBeamDecoder`, with the target merged as a personal-dictionary word
at 255 (`CtcLexiconMerge`) and the full final beam read. Each cell is the bonus the target needs
to reach rank 1 (top-1 final score minus the target's). A cell of 0 means it already wins.
Shapes come from `CtcTraceShapes`: `+lift` is the finger-lift sample the shipped recognizer
appends, `stop200` is a 200 ms rest on the last key, and `eased` slows to a stop on every key.

| target (rival) | straight | straight+lift | straight+stop200+lift | eased+lift | wobble .02 | wobble .035 | max |
|---|---|---|---|---|---|---|---|
| `ad` (`as`) | 0.47 | 0.17 | 0 | 0.15 | 0 | 0 | **0.47** |
| `wet` (`we`) | 1.62 | 1.38 | 0.13 | 1.02 | 0 | 0 | **1.62** |
| `adb` (`an`) | 3.62 | 3.51 | 3.38 | 0.47 | 0.93 | 0 | **3.62** |
| `somethings` (`something`) | 0 | 0 | 0 | 0 | 0 | 0 | **0** |

`adb` is in the final beam on every shape (rank 2–8), so the bonus can reach it. Its deficit is
the encoder's silence on `d` (2026-10-07 final-letter note §3), which is why it needs Highest.

## 3. What a raised word takes (collateral)

Two measurements.

**Synthetic rivals (Kotlin, shipped stack).** The same six shapes are decoded for each
target's rival words, with the target raised. Each cell counts rival traces whose top-1 becomes
the target, out of all rival traces:

| raised | rivals (6 shapes each) | Normal (0) | 1.0 | 2.0 (High) | 3.0 | 4.0 (Highest) |
|---|---|---|---|---|---|---|
| `ad` | as an and at add sad | 6/36 (all `add`) | 6/36 | 11/36 | 21/36 | 31/36 |
| `wet` | we wt were west yet set | 2/36 (`wt`) | 5/36 | 8/36 | 12/36 | 19/36 |
| `adb` | an and ab am sad | 0/30 | 1/30 | 1/30 | 3/30 | 8/30 |
| `somethings` | something things | 0/12 | 1/12 | 6/12 | 6/12 | 6/12 |

At Normal, `add` is lost to a raised `ad` on every shape. That cost already ships today with
any personal-dictionary `ad`; the CTC beam cannot tell the doubled `d` apart. At High, `as` is
lost on one shape (stop200). At Highest it is lost on all six.

**Real traces (Python mirror of the shipped stack, `short_word_ctc_eval.py priority`).** These
are the same final-beam dumps the 2026-10-07 notes used, re-dumped with the shipped input path
(`--emu fix`) and with `adb` merged at 255, because a new word adds trie paths. The prune key
carries no frequency, so re-ranking a stored beam under a bonus is exact. A *break* is a trace
that the shipped stack decodes correctly and whose top-1 becomes the raised word. Each count is
shown as breaks / that rival's correctly-decoded traces.

- **dev + held-out**: 7,000 traces over 2,921 words. Dev is 3,000 hwsfuto val rows; held-out
  is 4,000 FUTO swipe-1 test rows. Neither contains a target word.
- **focus**: every FUTO train-100k row whose label is one of 15 confusable words: as, an, and,
  at, we, were, was, something, somethings, add, set, ad, wet, ads, ab. That is 7,414 traces
  over 12 words. The rows are from the training split, so the encoder may have seen them. That
  makes it more confident on them than on new traces, so read these counts as a lower bound.

| raised | level (bonus) | dev + held-out breaks | focus breaks | real target traces fixed |
|---|---|---|---|---|
| `ad` | Normal (0) | 0 | 12 (as 11/877, at 1/534) | focus `ad` 0/1 |
| `ad` | 1.0 | 6 traces / 2 words (and 3/147, as 3/30) | 126 (as 110/877, and 14/2846, …) | 1/1 |
| `ad` | **High (2.0)** | **19 / 4 words (as 12/30, and 4/147, a 2/35, he 1/68)** | **449 (as 349/877, and 83/2846, an 8/462, at 7/534, was 2/1993)** | 1/1 |
| `ad` | 3.0 | 55 / 7 words (as 24/30, a 13/35, and 10/147, …) | 994 | 1/1 |
| `ad` | **Highest (4.0)** | **126 / 8 words (as 30/30, a 34/35, and 33/147, an 22/31, …)** | **1,762 (as 877/877, and 455/2846, an 340/462, …)** | 1/1 |
| `wet` | Normal (0) | 0 | 0 | focus `wet` 3/5 |
| `wet` | 1.5 | 2 / 1 word (we 2/12) | 0 | 5/5 |
| `wet` | **High (2.0)** | **2 / 1 word (we 2/12)** | **0** | 5/5 |
| `wet` | 3.0 | 5 / 4 words | 19 (were 7/531, we 6/46, set 4/35, …) | 5/5 |
| `wet` | **Highest (4.0)** | **16 / 6 words (we 11/12, …)** | **73 (we 41/46, were 25/531, set 5/35, …)** | 5/5 |
| `adb` | Normal → **High (2.0)** | **0** | **0** | — |
| `adb` | 3.0 | 0 | 3 (an 2/462, as 1/877) | — |
| `adb` | **Highest (4.0)** | **1 / 1 word (an 1/31)** | **22 (an 20/462, as 2/877)** | — |
| `somethings` | Normal (0) | 0 | 0 | — |
| `somethings` | 1.0 | 0 | 4 (something 4/11) | — |
| `somethings` | **High (2.0)** | **0** | **11 (something 11/11)** | — |

Concentration: every break of a raised `ad` falls on 2–8 distinct rival words, and `as` alone
is 12 of the 19 dev + held-out breaks at High. The counts measure one pair repeated, not
damage spread across the lexicon.

**Generic per-word cost** (dev + held-out, 6,352 correct traces). This is the cost of raising
an arbitrary word rather than one of the four targets. Every word that appears below top-1 in
some correct trace's beam is raised in turn, and the traces it would take are counted.

| bonus | mean traces taken per raised word | p90 | words that take ≥ 1 | worst words |
|---|---|---|---|---|
| Normal (0, user word at 255) | 0.11 | 0 | 3,968 / 41,752 | too 43, haas 18 |
| 1.0 | 0.46 | 1 | 13,381 | thee 221, too 99 |
| **2.0 (High)** | **1.17** | **3** | 24,924 | thee 523, too 135, inn 118 |
| 3.0 | 2.17 | 5 | 33,504 | thee 574, three 207 |
| **4.0 (Highest)** | **3.36** | **6** | 38,066 | thee 602, tho 569, three 524 |

A typical raised word costs about 1 of 6,352 swipes at High and 3 at Highest. The tail is
the near-homograph of a very common word (`thee`/`the`, `too`/`to`, `ad`/`as`). The Dictionary
Manager help text names that case, and the bar never offers Highest (§5).

## 4. Chosen CTC levels

| level | CTC bonus | what it fixes on the shipped stack | typical cost |
|---|---|---|---|
| Normal | 0 | the frequency-tie class (`somethings`; `git`, from the 2026-09-26 correction note) | none beyond adding the word |
| High | **2.0** | narrow encoder-prior losses up to ~2 nats (`ad` and `wet` on all six shapes; real `wet` 5/5) | ~1 swipe in 6,000; much more for an `ad`/`as`-type pair |
| Highest | **4.0** | interior-letter misses (`adb` on all six shapes, max deficit 3.62) | ~3 swipes in 6,000; most of a near-homograph rival's swipes |

The bonus is clamped to `CtcPriorityBonus.MAX_BONUS` = 4.0, so a damaged store cannot exceed
Highest. Both values sit on the measured deficits with some margin (High 2.0 against `wet`'s
1.62; Highest 4.0 against `adb`'s 3.62). Synthetic shapes are cleaner than human traces, so a
level set exactly at the deficit would fail on ordinary noise. The levels were chosen from
these measurements. They were not pre-registered.

## 5. Design

- **Storage.** `swipe_priority_<lang>` holds a JSON object `{word: 1|2}` with raised words only
  (`SwipePriority`, `LanguagePreferenceKeys.swipePriorityKey`). `custom_words_<lang>` and its
  readers are unchanged. An absent key means every word is Normal, so existing users see no
  migration and no behaviour change. A level only acts for a word that is currently a
  personal-dictionary word (`SwipePriority.forUserWords`). Deleting a word removes its level;
  renaming carries it. Platform `UserDictionary` rows have no update path in this app (the
  Dictionary Manager's User tab only toggles them), so in practice only Custom-tab words carry a
  level. Words learned automatically (`UserVocabulary`) still never reach a swipe lexicon.
- **CTC.** `CtcEngineAdapter.lexiconFor` folds the raw priority JSON into
  `LexiconContentVersion`; an empty value hashes nothing, so the version is unchanged for users
  who raise nothing. It builds a surface → bonus table (`UserSwipePriorityBonus.ctcBySurface`)
  keyed the way the trie stores the word: the a–z strip for en, the projection for CKDT and
  script languages. `CtcBeamDecoder` adds the bonus to the final score only, through the same
  seam as `CtcLearnedPrior`. The prune key has no bonus, so the surviving beam is unchanged and
  only words already in it can move (`CtcPriorityBonusTest.aWordOutsideTheFinalBeamIsNeverPulledIn`).
- **Geometric.** `GeometricUserWordMerge` attaches a per-ordinal bonus array to the merged
  dictionary for raised user words (`GeometricDictionary.swipeBonus`). `GeometricSwipeEngine`
  adds it to `S(w)` for pruner survivors only. The pruner requires matching start and end keys
  and a passing template prefilter, so it serves as the geometric distance threshold. With
  nothing raised there is no bonus array, and the merge and the decode are unchanged.
- **Tap prediction: not applied.** Tap completion already ranks a personal-dictionary word at
  the top of its scale, and it ranks by prefix, not by the encoder's end-of-trace reading. A
  swipe bonus there would make `a` complete to `adb` ahead of `and`, a change the user never
  asked for. `UserSwipePriorityBonusTest.tapPredictionDoesNotReadThePriorityStore` pins this.
- **Dictionary Manager.** The Add and Edit dialogs gain a Normal / High / Highest selector and
  a help text that explains what frequency does in each engine, why 255 is already the top,
  and the collateral cost. The row shows "Frequency: N · Swipe priority: High" for a raised
  word. The selector uses radio buttons rather than a slider because each level is a discrete,
  measured bonus. The store is written once on Save, so lexicon rebuilds (which the memo key
  triggers, as for any word edit) need no debouncing beyond `SwipeRewarmScheduler`.
- **"Prefer … when swiping?" offer** (`SwipeCorrectionPolicy.offerLevel`). For a word that is
  not yet a personal-dictionary word, accepting adds it at Normal (unchanged). For a Normal
  personal-dictionary word that the user still keeps correcting to, after two fresh
  corrections, accepting raises it to High; tapping the confirmation twice returns it to
  Normal. At High or Highest, nothing is offered: the bar never sets Highest, because §3 shows
  its cost on near-homographs.
- **Backups.** The dictionaries export carries `swipe_priority_by_language`. On import, a
  level is applied only to a word that is a personal-dictionary word once the import lands
  and has no level on this device yet: an import adds and never lowers. Settings import treats
  `swipe_priority_<lang>` like the other dictionary keys (separate flow), and a settings reset
  keeps it.

## 6. Geometric engine

`GeoUserSwipePriorityTest.geoPrioritySweepDiagnostic` (`-PgeoFull=true`) runs the shipped
geometric engine against the full en CKDT dictionary, with all four targets prepended as
personal-dictionary words. The layout is the local corpus's `qwerty_english` geometry. Real
traces come from the local combined-English corpus: 8,501 non-target traces decode, and 4,704
of them are correct at top-1. Scores are the engine's own `S(w)` over pruner survivors, so a
bonus re-rank is exact.

**Targets.** For one ideal trace and ten TYPICAL synthesized traces per word, the geometric
engine already ranks every target first as a plain personal-dictionary word. Its winning
margins: `ad` 0.80, `wet` 0.61–1.01, `adb` 0.59–1.19, `somethings` 1.77–2.24. This engine has no
encoder word prior at the end of the trace, and a user word sits at rank 0, its strongest
prior. A level therefore only adds margin here.

**Collateral on real traces** (correct traces whose top-1 becomes the raised word):

| raised | Normal (0) | 1.0 | High (2.0) | Highest (4.0) |
|---|---|---|---|---|
| `ad` | 0 | 0 | 0 | 0 |
| `wet` | 0 | 0 | 0 | 0 |
| `adb` | 0 | 1 trace / 1 word (san) | 1 / 1 | 1 / 1 |
| `somethings` | 0 | 1 / 1 (donations) | 2 / 2 | 6 / 5 (simpsons 2, dozens, donations, sometimes, assumptions) |
| all four | 0 | 2 / 2 | 3 / 3 | 7 / 6 |

**Generic per-word cost.** Every word among the 100 best survivors below top-1 of a correct
trace is raised in turn, mirroring the CTC beam width:

| bonus | mean traces taken per raised word (of 4,704) | p90 | CTC, same bonus (of 6,352) |
|---|---|---|---|
| 1.0 | 0.20 | 1 | 0.46 |
| **2.0 (High)** | **0.78** | 2 | 1.17 |
| 3.0 | 1.74 | 5 | 2.17 |
| **4.0 (Highest)** | **2.81** | 7 | 3.36 |

Per correct trace, High takes 0.017 % (geometric) against 0.018 % (CTC), and Highest takes
0.060 % against 0.053 %. **Chosen geometric levels: High 2.0, Highest 4.0**, numerically equal
to CTC. Both engines turn scores into the bar's confidence with a temperature-1 softmax, so an
equal additive bonus multiplies a word's odds by the same factor (e² ≈ 7.4, e⁴ ≈ 55) in either
engine. The measured per-trace costs above agree within a few thousandths of a percent.

## 7. Tests and reproduction

- `swipe/UserSwipePriorityReplayTest` (runPureTests, ORT-gated): the §2/§3 instrument plus the
  level pins on the shipped stack.
- `swipe/ctc/CtcPriorityBonusTest`: the decoder seam. No priority gives a byte-identical
  decode; the bonus is exact; a word outside the beam is never pulled in; values are clamped.
- `swipe/UserSwipePriorityBonusTest`: the level table, CTC surface keying (en strip, joiner
  words, CKDT projection), the geometric table, the memo version, and the adapter wiring.
- `swipe/geometric/GeoUserSwipePriorityTest`: merge and engine pins; the §6 sweep with
  `-PgeoFull=true`.
- `SwipePriorityTest`: the stored form, editing helpers, user-word restriction and backup
  section. `SwipeCorrectionPolicyTest` covers the offer ladder; `SwipeCorrectionOfferTest`
  (mock) covers the raise and its undo; `DictionaryManagerTest` (mock) covers set/read/cleanup
  and the Dictionary Manager rows; `backup/DictImportPlanApplyTest` (mock) covers the backup
  round trip and the import rules.

```bash
S=build/user-priority; mkdir -p $S
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/val_ordered.jsonl.gz $S/dev.jsonl --sample 3000 --emu fix --add adb
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz $S/held.jsonl --emu fix --add adb
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/futo_train100k.jsonl.gz $S/focus.jsonl --emu fix --add adb \
  --words as,an,and,at,we,were,was,something,somethings,add,set,ad,wet,ads,ab
python3 scripts/short_word_ctc_eval.py priority $S/held.jsonl $S/dev.jsonl
python3 scripts/short_word_ctc_eval.py priority $S/focus.jsonl
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.UserSwipePriorityReplayTest
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.geometric.GeoUserSwipePriorityTest -PgeoFull=true
```

## 8. Device check

1. Settings → Activities → Dictionary Manager → Custom → add `adb` with Swipe priority
   **Highest**, and `wet` with **High**. The rows read "Frequency: 255 · Swipe priority: …".
2. Swipe a→d→b quickly. Expect `adb` first. At Normal (edit the word back) it is expected at
   rank 3–8 behind `an`.
3. Swipe `wet` with a fast, straight motion. Expect `wet` first.
4. Collateral: swipe `an`, `we`, `as`, `were` several times each. With the levels above, an
   occasional `we` → `wet` or `an` → `adb` is the expected price (§3). If `ad` is also raised to
   High, expect a large share of `as` swipes to become `ad`.
5. Put both words back to Normal. `an` and `we` should decode exactly as before.
6. Backup & Restore → export Dictionaries, delete `adb`, import the file. `adb` returns with
   Highest.

## 9. Device report, 2026-10-10: `adb` at Highest still committed `an`

**Report.** On the Seeker, with release build 0e1d3c46, `adb` was already in the English
custom words at Normal. The tester raised it to Highest in Dictionary Manager → Custom (Edit).
Two synthetic a→d→b swipes in a Chrome textarea at sentence start both committed "An", and
"Adb" appeared lower in the bar on one of them. The swipes were `input motionevent` chains
through key centres, 8 steps per segment. A new custom `wet` added at High won both of its
swipes.

**Wiring: no defect found in the code.** The Edit dialog's Save calls
`CustomDictionarySource.updateWord(old, new, freq, priority)`. That writes `swipe_priority_en`
to the same DirectBootAware preferences the IME reads, in the same process.
`CtcEngineAdapter.lexiconFor` reads the value on every decode and hashes it into
`LexiconContentVersion`. Raising an existing word's level therefore rebuilds the trie exactly
as adding a word does. The bonus is keyed on the lowercase a–z strip (`adb`). Autocap is
applied after the decode, to the decoded slate (`SuggestionHandler.applyShiftTransformation`).
Nothing between the decoder and the bar reorders the slate: context rescoring is off by
default, and the possessive augment only appends. The add path and the edit path differ in
one input only: **the stored frequency**. Add prefills 255. Edit prefills the word's existing
stored value, which may be below 255. Before wave U2 the dialog default was 100, so a word
added in that era keeps 100 when only its level is changed.

**Replay of the device recipe**
(`UserSwipePriorityReplayTest.deviceRecipeAdbDiagnostic`; same stack and golden QWERTY
geometry as §2. The golden geometry equals `latn_qwerty_us`'s letter box: a 0.10, d 0.30,
b 0.60.) The shapes are 8 steps per segment, with and without the lift sample, a 500 ms
(clamped) lift, and six seeded jitters of the event spacing (20–100 ms). Each cell is the bonus
`adb` needs (nats), followed by its rank at Highest (+4.0):

| stored freq | eval straight+lift (12) | 8-step | 8-step+lift | 8-step+lift500 | jitter s1–s6 (needs) | rank 1 at Highest |
|---|---|---|---|---|---|---|
| 255 | 3.51 | 3.55 | 3.29 | 2.96 | 1.09–2.24 | 10/10 |
| 200 | 3.94 | 3.99 | 3.72 | 3.39 | 1.52–2.68 | 10/10 |
| 150 | 4.38 | 4.42 | 4.16 | 3.83 | 1.96–3.12 | 7/10 (rank 2 on the three uniform shapes) |
| 100 | 4.87 | 4.92 | 4.65 | 4.33 | 2.46–3.61 | 6/10 (rank 2 on all four uniform shapes) |
| 50 | 5.44 | 5.48 | 5.22 | 4.89 | 3.02–4.18 | 3/10 |

`wet` at stored 255 needs 0.07–1.62 on the same shapes, under its High +2.0 on every one.

**Reading.** At stored 255 the device recipe gives `adb` rank 1 at Highest on every shape,
with a margin of 0.45–2.9 nats. The lift clamp (f135b2b7) cannot cause the loss: it landed
before this measurement, it only shortens holds longer than 500 ms, and a longer lift
*helps* `adb`. The replay reproduces the device symptom only when the stored frequency is
about 150 or below. Each step down in frequency costs λ·ln of the calibrated ratio: −0.44 nats
at 200, −0.87 at 150, −1.37 at 100. In that case `adb` lands at rank 2 behind `an`, which
matches "Adb lower in the bar". The working hypothesis is therefore that the device's `adb`
carries a stored frequency of about 150 or below. **This is not yet confirmed on the device.**
The Dictionary Manager row shows the value ("Frequency: N · Swipe priority: Highest").

The other live explanation is that the device swipes were not centred: for example, a y
offset from the tester's key-centre coordinates, which the replay does not model. The device
checks below separate the two.

**Not changed.** The levels are unchanged. Whether raising a word's priority should also
lift its stored frequency to 255, or whether the Edit dialog should warn that a frequency
below 255 offsets the level, is a maintainer decision.

**Device check.** (1) Read the `adb` row's "Frequency: N". (2) If N < 255, edit it to 255 at
Highest and swipe a→d→b twice with the same recipe. The replay predicts `adb` first.
(3) If N is already 255 and `an` still wins, the cause is not the frequency. In that case,
capture the swipe's raw trace (Swipe Playground with debug mode on) and replay it.
