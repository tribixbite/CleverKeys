# Dropped doubled letter, frequency-margin rule (`helo`, without hijacking `bery`)

Date: 2026-10-11. Scope: English, QWERTY, tap-typing space-bar autocorrect
(`WordPredictor.autoCorrect`), shipped defaults. Base: origin/main ef3702c5.

Follow-up to [`2026-10-11-dropped-double-autocorrect.md`](2026-10-11-dropped-double-autocorrect.md).
There, an unconditional "re-insert a dropped doubled letter" rule fixed 611 distinct real
misspellings against 27 breaks, but failed G3. It took over one-key slips of very common words:
`bery` gave berry instead of very, `grom` groom instead of from, `ghe` ghee instead of the, `sed`
seed instead of see, `kne` knee instead of one, and `nany` nanny instead of many. That doc
also had no keystroke-slip corpus. The GitHub Typo Corpus has since been recovered.

> **Superseded in part (2026-10-12).** §2–§4 ran on a `255 − byte` map of `en_enhanced.json`.
> That is the swipe lexicon's scale, not the `en_enhanced.bin` that the device's tap predictor
> loads. §5 repeats the tuning and the gate on the device dictionary. It ships M = 64, and with
> it `helo` → hello on phones.

## 1. Pre-registration (written before any DEV or TEST outcome was computed)

When this section was committed, only the GTC extraction counts in §1.2 had been computed. No
autocorrect output on any set had been run or looked at.

### 1.1 Rule DD-M (one parameter, M)

The insertion rule, its gates, and its placement are the same as **variant A** of the previous
doc. The step runs after the collapse step (1.4) and before the morphology guard. For each letter
of the typed word, duplicate it in place. Keep the results that are dictionary words, are not
disabled, and clear the frequency floor; custom words are exempt from the floor. The most
frequent result is the insertion target **D**. Gates: `typed.length > autocorrect_min_word_length`
and `autocorrect_max_length_diff >= 1`. Alias keys are re-routed through `contractionAliases`
with the C-5 custom-word guard, as in step 1.4.

What changes is precedence. D no longer wins outright. Let **W** be the word the normal sweep
(step 4) would commit for the same input, with the morphology freeze ignored, after the
frequency floor and step-5 custom-word exemption.

- If there is no W (the sweep commits nothing), **D wins**.
- If W is a custom/user word, **W wins**. A word the user added outranks a structural guess.
- Otherwise **D wins iff `freq(D) ≥ freq(W) − M · maxFreq / 255`**. `maxFreq` is the loaded
  dictionary's maximum frequency. On the shipped scale (`1 000 000 − rank·3900`), one unit of M
  is one byte-rank step of the bundled 0–255 frequency.
- If D loses, the input continues down the existing path unchanged: the morphology guard, then
  the sweep's W.

`M = ALWAYS` (+∞) is the failed variant A. `M = NEVER` means D wins only when there is no W.
Negative M requires D to be *more* frequent than W.

### 1.2 Data (local evaluation only, never committed or packaged)

All files are in `~/.cache/cleverkeys-test/misspell/`.

| set | source | licence / terms | role |
|---|---|---|---|
| GTC | GitHub Typo Corpus v1.0.0 (Hagiwara & Mita, LREC 2020, github.com/mhagiwara/github-typo-corpus), `github-typo-corpus.v1.0.0.jsonl.gz`, 43,769,081 B, sha256 `98219d275a49ad28fca832891876c255581b890d893b6d9c974ceb454ba2909d`, recovered from https://web.archive.org/web/20260217103607id_/https://github-typo-corpus.s3.amazonaws.com/data/github-typo-corpus.v1.0.0.jsonl.gz (the S3 original no longer resolves) | Per the README "Terms": the copyright and licence of each commit and text follow its source repository. The corpus is published under GitHub's Acceptable Use Policies (scraping/API) | **primary**: typos fixed in real commits, i.e. typed text |
| R-typed, R-hand | Birkbeck sets, as in the previous doc §1.2 (same SHA-256) | as there | secondary real data (gate T2) |
| S-adj | synthetic one-QWERTY-neighbour slips of the top 10,000 words, as in the previous doc | n/a | adjacent-slip control |
| C-oov | `words_alpha.txt` valid words missing from the dictionary, as in the previous doc | public domain | valid-word control |

**GTC extraction** is done by `scripts/gtc_extract_pairs.py`. An edit is kept when it has
`is_typo = true` and `lang = eng` on both sides, and both lines have the same whitespace token
count. After stripping surrounding punctuation, exactly one token may differ. Both differing
tokens must be ASCII letters only and must not be camelCase. Both are lowercased. The harness
then applies the previous doc's dictionary filter: the target is in `en_enhanced`, and the typo
is not in the dictionary and is not an alias key. A (typo, target) pair counts once per split.
Its **occurrences** are the distinct commit hashes it appears in, so forks count once.

Extraction counts, from before the dictionary filter: 203,270 commit records, 353,055 edits,
339,430 English, 255,056 `is_typo`. Of these, 130,496 are single-word edits.

**Split, fixed before measurement.** A GTC pair goes to DEV when the last hex digit of its
commit SHA is even and to TEST when it is odd. DEV has 25,309 pairs (8,991 targets, 46,141
occurrences) and TEST has 25,166 pairs (9,053 targets, 45,758 occurrences), both before the
dictionary filter. The same typo string can occur in both halves through different commits. That
overlap is reported. S-adj is split by its base word, DEV when the FNV-1a hash is even.
The Birkbeck sets and C-oov are used only in TEST. Neither was used to pick M.

**Typo-class shares** are reported for GTC after the dictionary filter. This is descriptive
only. The classes are: dropped-double, extra-double, adjacent substitution
(`KeyAdjacency.areNeighbors`), non-adjacent substitution, transposition, other single
deletion or insertion, and multi-edit.

### 1.3 Harness

`src/test/kotlin/tribixbite/cleverkeys/autocorrect/DroppedDoubleMarginEval.kt` is a MockK-runner
class with no `Test` suffix, so CI never runs it. Its setup is the same as the previous harness:
the real 98k dictionary on the device scale, real aliases, shipped defaults, and no
custom/disabled words. W is the real production `autoCorrect` output. For the inputs the
morphology guard would freeze, W is that output with `Morphology.inflectionStems` mocked
empty, which is the same production sweep with the freeze skipped. Fix, break and swap are
defined as in the previous doc. They are reported as raw pairs, distinct target words and (GTC)
commit occurrences. If implemented, production must equal a dump of the simulation on every
eligible input (`dumpSimulation` / `productionMatchesSimulation`), or the eval is void.

### 1.4 Tuning on DEV (the only parameter is M)

Grid: `M ∈ {NEVER, −20, −18, …, 58, 60, ALWAYS}`. A value is **admissible** on DEV when all of
the following hold:

1. GTC-DEV distinct net fixes > 0, and distinct breaks ≤ 0.10 × distinct fixes.
2. S-adj-DEV breaks ≤ **0.8 %** of pairs, in both raw pairs and distinct base words. This is
   stricter than the 1.0 % TEST gate, to leave headroom.
3. All six named cases keep their baseline target: `bery`→very, `grom`→from, `ghe`→the,
   `sed`→see, `kne`→one, `nany`→many.

The chosen M is the admissible value with the most GTC-DEV distinct net fixes. On a tie, the
smallest M wins. If no value is admissible, nothing ships and TEST is not used for selection.

### 1.5 Ship gate on TEST (one evaluation at the chosen M; all must hold)

- **T1. GTC.** Net distinct fixes > 0 on GTC-TEST, and distinct breaks ≤ 0.10 × distinct fixes.
- **T2. Birkbeck (old G1/G2).** Net distinct fixes > 0 on R-typed and on R-hand, and R-union
  distinct breaks ≤ 0.10 × distinct fixes.
- **T3. Adjacent slips.** S-adj-TEST breaks ≤ 1.0 % in raw pairs **and** in distinct base
  words.
- **T4. Valid words.** C-oov new changes ≤ 0.5 %.
- **T5. Named hijack cases.** The six cases above keep their targets.
- Reported, not gated: whether `helo` → `hello`.

If T1–T5 all pass, DD-M ships at the chosen M, implemented in `WordPredictor.autoCorrect` with
fail-first unit tests. Otherwise nothing ships, and the numbers are committed here. These
thresholds are fixed now.

## 2. DEV tuning (run 2026-10-11, harness at eb7ef2b3; no TEST outcome computed)

Command: `CK_DD_PHASE=dev GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests
-PtestClass=autocorrect.DroppedDoubleMarginEval`. The test phase took 48 s and the whole
build 5 min.

### 2.1 GTC-DEV after the dictionary filter, by typo class (descriptive)

16,358 pairs, 5,845 distinct targets, 16,095 distinct typos, 27,231 commit occurrences.

| class | pairs | share | distinct targets | occurrences | share |
|---|---|---|---|---|---|
| other deletion (letter missing, not of a pair) | 4,607 | 28.2 % | 2,806 | 8,505 | 31.2 % |
| other insertion (extra letter, not a doubled one) | 2,977 | 18.2 % | 2,010 | 4,092 | 15.0 % |
| transposition | 2,523 | 15.4 % | 1,815 | 4,734 | 17.4 % |
| multi-edit | 2,055 | 12.6 % | 1,430 | 2,527 | 9.3 % |
| non-adjacent substitution | 1,565 | 9.6 % | 1,295 | 2,624 | 9.6 % |
| adjacent substitution | 1,098 | 6.7 % | 854 | 1,364 | 5.0 % |
| extra double (handled by step 1.4) | 1,057 | 6.5 % | 926 | 1,815 | 6.7 % |
| **dropped double** | **476** | **2.9 %** | 451 | **1,570** | **5.8 %** |

In typed GitHub text, a dropped doubled letter is about as common as an adjacent-key
substitution. By commit occurrences it is slightly more common (5.8 % vs 5.0 %), and by
distinct pairs less common (2.9 % vs 6.7 %). The repeated misspellings `occured`,
`overriden` and `succesful` concentrate its occurrences. GTC is typed on physical keyboards, so
these are not phone-keyboard rates, and adjacent slips that produce a real word are excluded by
the filter.

### 2.2 Grid (distinct target / base words; raw pairs in parentheses)

S-adj-DEV: 137,881 pairs, 4,924 base words. Admissibility limit (0.8 %): 1,103 raw and
39 distinct.

| M | GTC-DEV fixes | breaks | net | S-adj-DEV breaks | 6 named kept | `helo` |
|---|---|---|---|---|---|---|
| NEVER | 0 | 0 | 0 | 0 | yes | help |
| −20 | 16 | 0 | 16 | 1 (1) | yes | help |
| 0 | 84 | 1 | 83 | 5 (5) | yes | help |
| 10 | 169 | 1 | 168 | 9 (9) | yes | help |
| 18 | 231 | 3 | 228 | 17 (17) | yes | help |
| 20 | 242 | 4 | 238 | 22 (22) | yes | **hello** |
| 26 | 282 | 6 | 276 | 27 (27) | yes | hello |
| 30 | 295 | 6 | 289 | 36 (37) | yes | hello |
| **32** | **302** | **6** | **296** | **38 (39)** | **yes** | **hello** |
| 34 | 310 | 9 | 301 | 44 (46) | **no** | hello |
| 40 | 324 | 9 | 315 | 56 (60) | no | hello |
| 60 | 337 | 15 | 322 | 91 (97) | no | hello |
| ALWAYS (old A) | 339 | 16 | 323 | 99 (106) | no | hello |

`NEVER` never fires, because the sweep commits *some* W for every DEV input that has an
insertion target. Every value from −20 to 32 is admissible. Above 32, constraint 3 fails
first: some of the six named cases flip. At 34, S-adj-DEV is also over the 0.8 % limit
(44 > 39).

**Chosen: M = 32** (most GTC-DEV net fixes among admissible values). S-adj-DEV sits at 38
distinct breaks (0.77 %), close to its limit. The TEST gate is 1.0 %.

Examples of the 38 S-adj-DEV breaks: `dors` → doors (does), `spon` → spoon (soon),
`bef` → beef (bed), `fel` → feel (tel), `cel` → cell (ceo), `succeds` → succeeds (success), and
`helo` → hello (help). The last is the reported case seen from the other direction, which the
control counts as a break by construction. The 6 GTC-DEV breaks are mostly label noise in the
corpus, for example `additionaly` → additionally (labelled additional) and `comute` → commute
(labelled compute).

## 3. TEST (one evaluation at M = 32, run 2026-10-12, harness at 6a8f7eb3)

Command: `CK_DD_PHASE=test CK_DD_MARGIN=32 CK_DD_DUMP=32 GRADLE_GUARD_XMX=1536m
scripts/gradle-guard.sh runMockTests -PtestClass=autocorrect.DroppedDoubleMarginEval`
(3 min 19 s).

GTC-TEST after the filter: 16,210 pairs, 5,845 targets, 27,190 occurrences. Its class shares
match DEV to within 0.4 points; dropped-double is 2.9 % of pairs and 6.0 % of occurrences.
5,493 TEST pairs have a typo string that also occurs in DEV, through different commits. M was
tuned on a single integer, so the overlap gives little room to overfit, but the two halves
are not independent at the string level. Baseline accuracy on the GTC-TEST dropped-double
class is 129 / 471 (27.4 %).

### 3.1 Outcomes (raw pairs; distinct target / base words in parentheses)

| set | pairs | eligible | fixes | breaks | swaps | net distinct |
|---|---|---|---|---|---|---|
| GTC-TEST | 16,210 | 487 | 294 (284; 1,030 occ) | 4 (4; 4 occ) | 54 (51) | **+280** |
| R-typed | 2,780 | 292 | 176 (159) | 3 (3) | 13 (13) | +156 |
| R-hand | 30,545 | 753 | 436 (415) | 15 (15) | 149 (132) | +400 |
| R-union | | | 524 distinct | 17 distinct | | +507 |
| S-adj-TEST | 140,750 (5,076 words) | 166 | 0 | **39 (36)** | 127 (115) | −36 |
| C-oov | 292,027 words | 357 | | 104 new changes (0.036 %) | 202 already changed | |

### 3.2 Gate

| gate | result |
|---|---|
| T1. GTC net > 0, breaks ≤ 0.10 × fixes | pass: +280; 4 ≤ 28.4 |
| T2. Birkbeck net > 0 on each set; union breaks ≤ 0.10 × fixes | pass: +156 / +400; 17 ≤ 52.4 |
| T3. S-adj-TEST breaks ≤ 1.0 %, raw and distinct | pass: raw 39 / 140,750 = 0.028 %; distinct 36 / 5,076 = **0.71 %** |
| T4. C-oov new changes ≤ 0.5 % | pass: 0.036 % |
| T5. Six named cases kept | pass: very, from, the, see, one, many |

**Decision: DD-M passes all pre-registered gates and ships at M = 32.** Reported, not gated:
**`helo` → `hello`**. Other named examples: `tomorow` → tomorrow (was comoros),
`ocasion` → occasion (was location), `begining` → beginning (was frozen), `adress` → address
(was across), and `sucess` → success (was access).

### 3.3 What still breaks

- **S-adj-TEST (36 words).** The breaks are slips where the doubled form is a real word within
  32 rank steps of the intended one: `fes` → fees (few), `cuf` → cuff (cut), `pil` → pill
  (oil), `woried` → worried (worked), `trafic` → traffic (tragic), `slepy` → sleepy (slept),
  `aply` → apply (ally). For several of these the doubled reading is at least as plausible as
  the control's label (`trafic`, `woried`, `aply`).
- **GTC-TEST (4).** `comute` → commute (labelled compute), `globaly` → globally (global),
  `keps` → keeps (kept), `revaluated` → reevaluated (evaluated).
- **Birkbeck (17).** These are mostly British/American or label-noise pairs (`jewelery`,
  `milenium`, `personel`), plus `gril` → grill (girl) and `gose` → goose (goes).
- **C-oov (104).** These are mostly single-consonant variant spellings, in the dictionary's
  doubled form: `canvased` → canvassed, `controled` → controlled, `chaneled` → channeled,
  `diagramed` → diagrammed.

The measure is again per distinct word, not per occurrence in running text. The slips of very
frequent words that the earlier rule hijacked are now kept, because their doubled forms are
more than 32 steps rarer: very/berry 39, from/groom 57, the/ghee 95, see/seed 33, one/knee 41,
many/nanny 44. That margin is narrow for `see`/`seed` at 33.

### 3.4 Caveats

- GTC was typed on physical keyboards, by developers, mostly in documentation and comments.
  The rates of dropped-double and adjacent slips on a phone keyboard remain unmeasured.
- The real-pair filter drops typos that are dictionary words. `occured` and `succesful` are
  both in the bundled dictionary as corpus noise, so neither is corrected by this or any later
  step (`occured` stays `occured`).
- `productionMatchesSimulation` must pass against the M = 32 dump (2,894 rule-decided inputs)
  for this result to apply to the shipped code. See §4.

## 4. Implementation (2026-10-12)

`WordPredictor.autoCorrect` now has step 1.45. It uses `droppedDoubleTarget` and
`DROPPED_DOUBLE_MARGIN_STEPS = 32`. The sweep (steps 3–4) was extracted into
`autocorrectSweep` and is run once and reused by steps 1.45 and 5. Steps 1.4, 1.45 and 5 now
share the alias re-route and C-5 guard (`finalizeCorrection`) and the step-5 commit test
(`isCommittable`).

- **Fail-first tests**, in `AutoCorrectEndToEndTest` (`droppedDouble_*`). `helo`/`Helo` →
  hello/Hello and the long-word fixes failed before the change and pass after it. Five guards
  passed before and after: the six named cases, valid words untouched, a disabled `hello` never
  offered, a custom sweep winner `help` kept, and `autocorrect_max_length_diff = 0` keeping
  `help`.
- **Simulation parity.** `productionMatchesSimulation` ran with `CK_DD_VERIFY=32` against
  the M = 32 dump made at the pre-implementation commit: 2,894 checked, 0 mismatches.
- **Visible on the bar.** Since ebe46e19, the bar highlights exactly what space commits
  (`SpaceCommitPreview` calls `autoCorrect`), so `helo` now highlights `hello` too.
- **Scope.** The step applies to whichever tap dictionary is loaded, but it was measured
  only for English QWERTY. Other languages use the same 0–255 rank scale and are unmeasured.

## 5. Device-dictionary re-measurement (2026-10-12)

### 5.1 The harness did not run on the device's dictionary

§2–§4 were measured on the wrong frequency scale. The tap predictor on the device
(`WordPredictor`: autocorrect, the suggestion bar, `SpaceCommitResolver`) loads
`dictionaries/en_enhanced.bin` (`AsyncDictionaryLoader`, and `WordPredictor.loadDictionary`).
`BinaryDictionaryLoader` reads its V2 uint8 rank as `freq = 1 000 000 − rank · 3 900`. The JSON
is only a fallback. The JVM harnesses instead read `en_enhanced.json` and used `rank = 255 − b`
for each byte `b`, and `AutoCorrectEndToEndTest`'s header claimed this equals the device path.
It does not. The JSON is the CTC swipe lexicon's compressed 134..255 scale. That is correct for
swipe, but it is not the `.bin`'s rank. Decoding the `.bin` shows that 98,139 of the 98,140 words
have a different value. Both files hold the same 98,140 words, so the corpus filters and splits
are unchanged; only the frequencies differ.

Rank gaps (in steps; one step = `maxFreq / 255` ≈ one `.bin` rank) between the word a slip
means and the doubled-letter word:

| pair | `.bin` (device) | JSON-derived (§2–§4) |
|---|---|---|
| help / hello | 39 | 20 |
| see / seed | 66 | 33 |
| very / berry | 77 | 39 |
| one / knee | 81 | 41 |
| many / nanny | 88 | 44 |
| from / groom | 114 | 57 |
| the / ghee | 191 | 95 |

So the shipped M = 32 gives `helo` → `help` on the phone, which is what the Pixel showed
(2026-10-12, release dc7b3b2f). The M = 32 tuning, the TEST gate result in §3 and the
simulation parity in §4 all describe a dictionary the device never loads.

The Pixel also corrected `bery` → berry and `grom` → groom. That device has
`autocorrect_prefix_length = 1`, so the sweep only considers words that start with the typed
first letter. `very` and `from` are excluded, the sweep's W is a b-/g- word, and the
insertion wins against it. The shipped default is 0. This interaction is not a bug in DD-M, but
it is untested: none of the measurements here use a prefix length above 0.

### 5.2 Pre-registration (written before any device-scale DEV or TEST outcome was computed)

When this section was committed, no autocorrect output on the `.bin` had been run on any set.

- **Rule, data, split, filter: unchanged.** The rule is DD-M as in §1.1. M counts steps of
  `maxFreq / 255`; on the `.bin` `maxFreq` is 1,000,000, so one step is 3,921.6, about one
  rank (3,900). The data and DEV/TEST split are §1.2's, and the dictionary filter is the same
  word set.
- **Harness.** `DroppedDoubleMarginEval` now loads `ShippedTapDictionary`: `en_enhanced.bin`
  through the production `BinaryDictionaryParser`, plus the real contraction aliases.
  `ShippedTapDictionaryDriftTest` pins this to the production asset load. DD-M is now in
  production, so the baseline is production with step 1.45 skipped. A margin parameter
  (`autoCorrect(word, null)`) provides this; production always passes
  `DROPPED_DOUBLE_MARGIN_STEPS`. W is that baseline with the morphology freeze bypassed, as in
  §1.3.
- **Grid.** `M ∈ {NEVER, −40, −38, …, 158, 160, ALWAYS}`. This is wider than §1.4 because
  device-scale gaps are about twice the JSON ones.
- **Admissibility and selection.** These are §1.4's three constraints and its selection rule,
  unchanged: the most GTC-DEV distinct net fixes, with the smallest M winning a tie.
- **Ship gate.** T1–T5 of §1.5 with the same thresholds, evaluated once on TEST at the chosen
  M. Whether `helo` → `hello` is reported, not gated.
- **Descriptive extra (not used for selection or the gate).** After the chosen-M TEST result is
  recorded, TEST is also run at the currently shipped M = 32. This describes what phones run
  today. If the chosen M is 32, this is the same run.
- **Decision.** If T1–T5 pass, `DROPPED_DOUBLE_MARGIN_STEPS` becomes the chosen M. Before that,
  `productionMatchesSimulation` must pass at that M, and the unit tests must be updated to the
  device-scale behaviour. If no M is admissible, or TEST fails, this addendum changes no code.
  The numbers are reported, with a recommendation to the maintainer: keep a measured-safe M, or
  revert the step.
- **Known weakness, stated in advance.** TEST is not untouched. Its outcomes at M = 32 on the
  other scale were computed and read on 2026-10-11/12 (§3). The re-tuning uses DEV only. The
  TEST result is still a second look at the same pairs.

### 5.3 DEV tuning on the device dictionary (run 2026-10-12; no TEST outcome computed)

Command: `CK_DD_PHASE=dev GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests
-PtestClass=autocorrect.DroppedDoubleMarginEval` (4 min 26 s, whole build).

GTC-DEV after the filter is unchanged from §2.1: 16,358 pairs, 5,845 targets, 27,231
occurrences, with the same class shares. S-adj-DEV is 137,871 pairs and 4,925 base words.
These differ slightly from §2.2 because the top 10,000 words are now ranked on the `.bin`. The
0.8 % limit is 1,102 raw and 39 distinct.

| M | GTC-DEV fixes | breaks | net | S-adj-DEV breaks | 6 named kept | `helo` |
|---|---|---|---|---|---|---|
| NEVER | 0 | 0 | 0 | 0 | yes | help |
| −40 | 18 | 0 | 18 | 1 (1) | yes | help |
| 0 | 87 | 0 | 87 | 5 (5) | yes | help |
| 32 (shipped) | 221 | 2 | 219 | 13 (13) | yes | **help** |
| 38 | 241 | 2 | 239 | 16 (16) | yes | help |
| 40 | 251 | 3 | 248 | 21 (21) | yes | **hello** |
| 50 | 280 | 5 | 275 | 25 (25) | yes | hello |
| 60 | 297 | 5 | 292 | 36 (37) | yes | hello |
| 62 | 299 | 5 | 294 | 37 (38) | yes | hello |
| **64** | **301** | **5** | **296** | **37 (38)** | **yes** | **hello** |
| 66 | 305 | 6 | 299 | 38 (40) | **no** (`sed` → seed) | hello |
| 80 | 322 | 9 | 313 | 55 (58) | no | hello |
| 160 | 338 | 15 | 323 | 95 (101) | no | hello |
| ALWAYS | 338 | 16 | 322 | 96 (102) | no | hello |

Distinct target/base words; raw pairs in parentheses. Every M from −40 to 64 is admissible.
At 66, constraint 3 fails first, because see/seed are 66 steps apart.

**Chosen: M = 64.** S-adj-DEV is at 37 distinct breaks, against a limit of 39. The nearest
named case, `sed` → see, keeps its target by 2 steps.

The 37 S-adj-DEV breaks include `ses` → sees (see), `helo` → hello (help), `dors` → doors,
`spon` → spoon, `bef` → beef, `hury` → hurry (hurt), `dolar` → dollar (solar), `cel` → cell,
`sel` → sell, `fil` → fill and `fel` → feel. Some are proper nouns, such as `gelong` →
geelong, `lenon` → lennon, `karem` → kareem and `jilian` → jillian. The 5 GTC-DEV breaks are
label noise of the `additionaly` → additionally (labelled additional) kind, plus `esay` →
essay (easy).

### 5.4 TEST on the device dictionary (one evaluation at M = 64, run 2026-10-12)

Command: `CK_DD_PHASE=test CK_DD_MARGIN=64 GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh
runMockTests -PtestClass=autocorrect.DroppedDoubleMarginEval`.

GTC-TEST after the filter is 16,210 pairs, 5,845 targets and 27,190 occurrences, as in §3.
On the device dictionary, baseline accuracy on its dropped-double class is 130 / 471.

| set | pairs | eligible | fixes | breaks | swaps | net distinct |
|---|---|---|---|---|---|---|
| GTC-TEST | 16,210 | 488 | 293 (283; 1,019 occ) | 3 (3; 3 occ) | 56 (53) | **+280** |
| R-typed | 2,780 | 291 | 177 (159) | 3 (3) | 13 (13) | +156 |
| R-hand | 30,545 | 756 | 435 (414) | 15 (15) | 148 (129) | +399 |
| R-union | | | 522 distinct | 17 distinct | | +505 |
| S-adj-TEST | 140,971 (5,075 words) | 163 | 0 | **37 (34)** | 126 (113) | −34 |
| C-oov | 292,027 words | 371 | | 105 new changes (0.036 %) | 214 already changed | |

| gate | result |
|---|---|
| T1. GTC | pass: +280; 3 ≤ 28.3 |
| T2. Birkbeck | pass: +156 / +399; 17 ≤ 52.2 |
| T3. S-adj-TEST ≤ 1.0 % | pass: raw 37 / 140,971 = 0.026 %; distinct 34 / 5,075 = **0.67 %** |
| T4. C-oov ≤ 0.5 % | pass: 0.036 % |
| T5. Six named cases | pass: very, from, the, see, one, many |

**Decision: M = 64 passes every pre-registered gate and replaces M = 32.** Reported, not
gated: **`helo` → `hello`** on the device dictionary. Other examples: `tomorow` → tomorrow
(was follow), `ocasion` → occasion (was location), `begining` → beginning (was left
frozen), `adress` → address (was across) and `sucess` → success (was access).

Breaks:

- **S-adj-TEST (34 words).** `fes` → fees (few), `woried` → worried (worked), `pil` → pill
  (oil), `robery` → robbery (robert), `fision` → fission (vision), `cofers` → coffers
  (covers), `slepy` → sleepy (slept), `aply` → apply (ally), `trafic` → traffic (tragic),
  `payrol` → payroll (patrol), `pufin` → puffin (putin). As in §3.3, several doubled readings
  are at least as plausible as the control's label.
- **GTC-TEST (3).** `globaly` → globally (labelled global), `keps` → keeps (kept),
  `revaluated` → reevaluated (evaluated).
- **Birkbeck (17).** These are mostly the same British/American and label-noise pairs as in
  §3.3: `jewelery`, `milenium`, `personel`, `gril` → grill (girl), `gose` → goose (goes),
  `realy` → really (ready).
- **C-oov (105).** These are single-consonant variant spellings, as before: `canvased`,
  `controled`, `chaneled`.

**Descriptive: the shipped M = 32 on the device dictionary** (run after the M = 64 result
above was recorded; not used for selection). GTC-TEST +202 distinct (203 fixes, 1 break).
Birkbeck +98 / +274, with 9 union breaks. S-adj-TEST 15 / 5,075 distinct (0.30 %), C-oov
0.019 %, six named cases kept, and **`helo` → help**. M = 32 also passes T1–T5 on the
device dictionary, so the shipped build is safe, but it is more conservative than intended
and misses the reported case. Against M = 32, M = 64 adds 78 GTC and 231 Birkbeck distinct
net fixes, and costs 19 more S-adj base words (0.37 points).

### 5.5 Implementation and parity (2026-10-12)

- `DROPPED_DOUBLE_MARGIN_STEPS` = 64, with its KDoc rewritten for the device scale.
- **Parity.** `productionMatchesSimulation` ran with `CK_DD_VERIFY=64`. It compares production
  `autoCorrect(word, 64)` with the simulation on every rule-decided input of every set:
  2,889 checked, 0 mismatches.
- **Unit tests** (`AutoCorrectEndToEndTest`, `SpaceCommitPreviewTest`, now on the device
  dictionary). `helo`/`Helo` give hello/Hello, and the six named cases are kept.
  `droppedDouble_marginSeparatesNamedPairsOnTheDeviceDictionary` pins the gaps (see/seed 66 > 64,
  help/hello 39 ≤ 64). A rebuilt `.bin` that moves a pair across the margin fails there, before
  the behaviour silently changes.
- **Remaining risk.** see/seed sits 2 steps outside the margin. Any dictionary rebuild moves
  ranks; the pin above is the guard. A non-zero `autocorrect_prefix_length` changes which W
  the sweep can return, and is unmeasured (§5.1).
