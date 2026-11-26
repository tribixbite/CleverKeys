# Autocorrect: a secondary-language word bypasses only when it is not far rarer (pre-registration, 2026-11-26)

Written and committed before any run of the evaluation described below. Nothing above "Result" is
edited after the first run; results are appended under "Result".

## Why

`aa9b57ba` (pre-tag review 2026-11-25) made autocorrect's step 1 ("already a word, do not
correct") count the secondary lexicon, as `isInDictionary` does: a correctly typed word of the
user's other language had been corrected into a primary-language word (en+es `pero` → `per`,
`como` → `come`; de+en `fuss` → `fuß`). The check is all-or-nothing. The German and Spanish lists
are built from web text and carry common English misspellings as rare entries, so in en+de the
typos `thier` and `wich` are now German "words" and are never corrected (2026-11-26 status block,
"the en+de bypass passes typos that are de-list words").

## What was seen before this document (disclosure)

Only the two device reports above (`thier`, `wich` uncorrected in en+de) and the three words of
the original bug (`pero`, `como`, `fuss`). No corpus number, no frequency of any secondary word and
no output of the rule below has been computed.

## The rule under test

`WordPredictor.autoCorrect(T)`, step 1. Let T be the typed word, lowercased, that is NOT a primary
dictionary word (those return as typed, unchanged) and IS an exact, non-disabled word of the
secondary lexicon (`isInSecondaryLexiconExactly` and not `isSecondaryWordDisabled`).

- Run the rest of autocorrect on T as if the secondary bypass did not exist; call its output E.
- If E equals T ignoring case, return T (nothing to compare).
- Otherwise compare frequencies on the byte scale both lists use:
  - `bS = 255 − rank(T)`, the secondary index's 0–255 rank inverted (255 = most common);
  - `bE = freq(E) · 255 / maxFreq`, E's primary-dictionary frequency on the byte scale the
    dropped-double margin (DD-M) already uses; E's apostrophe-free key is looked up when E is a
    contraction display form; an E with no frequency gets `bE = 0`.
- Return E when `bE − bS > M`, else T.

`M = +∞` is the current behaviour (always bypass); `M = −∞` is the pre-`aa9b57ba` behaviour
(never bypass). The rule leaves every other step, the disabled-word handling and the primary
check untouched.

## Data and split

Primary English: `en_enhanced.bin` through the production parser (`ShippedTapDictionary`, the
device's tap dictionary, with the contraction aliases the device injects). Secondary: the shipped
`de_enhanced.bin` / `es_enhanced.bin` loaded into `NormalizedPrefixIndex` by
`BinaryDictionaryLoader`, as `WordPredictor.buildSecondaryDictionary` does (no custom words, no
Swiss spelling). Autocorrect on, every other setting and tuning at its default.

**Typos (what the rule should fix).** English misspelling → intended-word pairs from
`~/.cache/cleverkeys-test/misspell`: Birkbeck `missp.dat`, `holbrook-missp.dat`, `aspell.dat`,
`wikipedia.dat` and the GitHub Typo Corpus `gtc-pairs.tsv`. A pair is eligible for a pair (en+L)
when the typo is not a primary dictionary word, is an exact word of L's lexicon, is at least the
autocorrect minimum length, differs from the target, and the target is a primary dictionary word.
Split: GTC uses its own DEV/TEST column; a Birkbeck pair is DEV when `typo.hashCode()` is even,
TEST when odd (the DD-M convention). Units: distinct (typo, target) pairs.

**Real secondary words (what the rule must not break).**
`~/.cache/cleverkeys-corpora/static-lm-eval/heldout_<L>.txt` (L = de, es): held-out edited prose,
one sentence per line, **DEV = even line ids, TEST = odd line ids**, tokens `[\p{L}']+`
lowercased. A token is eligible when it is not a primary dictionary word, is an exact word of L's
lexicon, and is at least the autocorrect minimum length — i.e. exactly the tokens the current
bypass protects. A token "breaks" when autocorrect returns anything but the token. Units: tokens
(token-weighted) and distinct words.

**Named words.** Must stay uncorrected (hard): en+es `pero`, `como`, `para`, `pues`, `porque`,
`hasta`, `nada`, `mucho`, `entonces`, `todo`; en+de `und`, `nicht`, `auch`, `sehr`, `doch`, `schon`,
`aber`, `noch`, `wenn`, `oder`; de+en `fuss` (German primary from `de_enhanced.bin` through the
production loader, English secondary from `en_enhanced.bin`). A named word that is a primary word or
not an exact secondary word is reported and skipped (the rule cannot touch it). Must be fixed
(hard): en+de `thier` → `their`, `wich` → `which`.

## Selection (DEV only)

Grid: `M ∈ {−∞} ∪ {−64, −56, …, 248} ∪ {+∞}` (steps of 8). For each pair (en+de, en+es) and M:
DEV typo fixes (output = target), wrong corrections (output ∉ {typo, target}), DEV real-word
breaks (tokens and distinct words). Chosen: the SMALLEST M at which, in BOTH pairs, DEV real-word
token retention is ≥ 99.5% and at most 1 of the 300 most frequent eligible DEV words breaks. If
the chosen M is +∞, the rule fails and current behaviour stays.

## Gate (TEST, run once at the chosen M)

The rule ships only if ALL hold:

- **R1** real-word token retention ≥ 99.5% in en+de and in en+es.
- **R2** at most 1 of the 300 most frequent eligible TEST words breaks, per pair.
- **R3** every named must-keep word stays uncorrected; `thier` → `their` and `wich` → `which`.
- **T1** in en+de and in en+es: distinct typo fixes ≥ 20% of the eligible distinct TEST pairs, and
  wrong corrections ≤ half the fixes.

If any fails, the current behaviour (`M = +∞`) stays and the result is recorded here.

## Code

`src/test/kotlin/tribixbite/cleverkeys/autocorrect/SecondaryBypassEval.kt` (mock tier, on demand,
not a `*Test` class, local corpora only):

```
CK_SB_PHASE=dev  GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m scripts/gradle-guard.sh \
    runMockTests -PtestClass=autocorrect.SecondaryBypassEval
CK_SB_PHASE=test CK_SB_MARGIN=<M> …same…
```

## Result

Runs 2026-11-26 in a worktree of `25234af47` plus the rule (`WordPredictor.autoCorrect(…,
secondaryBypassMargin)`); logs kept in the session scratchpad (`eval-dev.log`, `run4.log`).

**DEV** (typo pairs from 44,285 DEV rows; eligible en+de 271 pairs / 235 typos, real words 20,704 /
274,871 tokens; en+es 203 / 180, 20,990 / 156,445):

| pair | M | fixes | wrong | real-word token retention | top-300 breaks |
|---|---|---|---|---|---|
| en+de | −∞ (pre-`aa9b57ba`) | 100 (36.9%) | 150 | 30.2% | 259 |
| en+de | 0 | 83 (30.6%) | 107 | 90.9% | 22 |
| en+de | 32 | 47 (17.3%) | 71 | 98.2% | 1 |
| en+de | 56 | 31 (11.4%) | 47 | 99.45% | 0 |
| en+de | 64 | 25 (9.2%) | 37 | 99.64% | 0 |
| en+de | +∞ (current) | 0 | 0 | 100% | 0 |
| en+es | −∞ | 77 (37.9%) | 115 | 26.7% | 249 |
| en+es | 0 | 66 (32.5%) | 73 | 87.2% | 20 |
| en+es | 32 | 33 (16.3%) | 52 | 97.6% | 4 |
| en+es | 56 | 23 (11.3%) | 34 | 99.34% | 0 |
| en+es | 64 | 19 (9.4%) | 28 | 99.55% | 0 |
| en+es | +∞ | 0 | 0 | 100% | 0 |

Selection: the smallest M meeting retention ≥ 99.5% and ≤ 1 top-300 break in both pairs is
**M = 64** (en+es sets it; en+de alone would allow 64 as well, 56 fails it at 99.45%).

**TEST at M = 64** (run once; production matched the simulation on every eligible input, 0
mismatches in both pairs):

| check | en+de | en+es | verdict |
|---|---|---|---|
| R1 token retention ≥ 99.5% | 99.667% (916 of 274,670 tokens changed, 254 words) | 99.546% (712 of 156,675, 304 words) | pass |
| R2 ≤ 1 top-300 break | 0 | 0 | pass |
| R3 must-keep words | all kept (`nicht` falls at M ≤ −64, `mucho` at M ≤ 0) | all kept | pass |
| R3 de+en `fuss` | kept (`fuß` wins only at M ≤ 48) | | pass |
| R3 `thier` → `their` | fixed (gap 120) | | pass |
| R3 `wich` → `which` | **fails**: the English correction is `with` at every M (gap 125) | | **fail** |
| T1 fixes ≥ 20% of 260 / 214 eligible pairs | **18 (6.9%)** | **18 (8.4%)** | **fail** |
| T1 wrong ≤ half the fixes | **39 wrong vs 18 fixes** | **42 vs 18** | **fail** |

**Verdict: the rule fails its gate; current behaviour stays (`SECONDARY_BYPASS_MARGIN_STEPS =
Int.MAX_VALUE`).** What the numbers say: the typos that are secondary-list words are mostly short
and ambiguous (`sed`, `anc`, `dn`, `tuch`), so the English correction is as often the wrong word
as the right one, even before the margin; and the margin that protects real German / Spanish text
(≥ 64) leaves under a tenth of them fixed, while still rewriting 254 German and 304 Spanish real
words of the TEST text (`weh` → we, `ehe` → the, `haz` → has, `foro` → for, `oír` → our). `thier`
is fixable on its own (gap 120 > any safe margin), `wich` is not (autocorrect's English answer is
`with`).

Kept in the code: the margin parameter of `WordPredictor.autoCorrect` (default = the shipped
`Int.MAX_VALUE`, so the production path is unchanged) and `SecondaryBypassEval`, so a better
signal can be measured with the same harness. Candidates not tried here (they would need their own
pre-registration): a list of known English misspellings removed from the secondary lists at build
time (`scripts/build_wordlist.py`), or requiring the secondary word to be in the Auto-Detect
language (next-word language) before it bypasses.
