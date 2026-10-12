# Dropped doubled letter, frequency-margin rule (`helo`, without hijacking `bery`)

Date: 2026-10-11. Scope: English, QWERTY, tap-typing space-bar autocorrect
(`WordPredictor.autoCorrect`), shipped defaults. Base: origin/main ef3702c5.

Follow-up to [`2026-10-11-dropped-double-autocorrect.md`](2026-10-11-dropped-double-autocorrect.md).
There, an unconditional "re-insert a dropped doubled letter" rule fixed 611 distinct real
misspellings against 27 breaks, but failed G3. It took over one-key slips of very common words:
`bery` gave berry instead of very, `grom` groom instead of from, `ghe` ghee instead of the, `sed`
seed instead of see, `kne` knee instead of one, and `nany` nanny instead of many. That doc
also had no keystroke-slip corpus. The GitHub Typo Corpus has since been recovered.

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
