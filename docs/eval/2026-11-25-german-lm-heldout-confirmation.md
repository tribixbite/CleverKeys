# German next-word model: held-out confirmation (pre-registration, 2026-11-25)

Written and committed before any evaluation, tokenization or leakage count of the corpus below.
Known about it at this point: its download URL, byte size, sha256 and that it holds 10,000
sentences. Nothing of this section is edited after the first run; results are appended under
"Result".

## Why

`de.cklm` was rebuilt with the ß lexicon (`159be8291`,
`docs/eval/2026-11-25-german-sharp-s.md`). Its pre-registered gate G4 (tap eval end to end, every
`lm_static` cell ≥ shipped − 0.10 pt) failed as written: the new lexicon can score ß targets the
old one could not, so the two rates were over different positions. The model shipped on a
measurement made after the gate, on the positions both lexicons share
(`STATIC_LM_EVAL_TARGETS_IN`). That measurement was chosen after seeing the failure, on data
already used. This confirmation repeats it, fixed in advance, on data no decision has used.

## Data

Used by an earlier German decision, so not eligible: Leipzig `deu-de_web_2021_300K` and Tatoeba
`deu` (build inputs), UD German-GSD dev/test and `heldout_de.txt` (gates of 2026-09-29 and
2026-11-25), UD German-PUD and Leipzig `deu-at_web_2019_10K` (FLOOR_ONE, 2026-10-25).

Confirmation corpus: Leipzig `deu_news_2023_10K`
(`https://downloads.wortschatz-leipzig.de/corpora/deu_news_2023_10K.tar.gz`, 2,340,260 B, sha256
`166d8ff8b4e6c30d38c6fdf9cbe847f7ba6ca18da15633799a72dc11cfee7380`, CC BY 4.0). News, 2023: a
different genre and year from every build input. Eval only, never shipped, not in git.

Population `confirm` of `scripts/build_static_lm_fresh_eval.py`: the builder's own segmentation
and 2..30-token bounds, and a sentence is dropped when its `sentence_key` occurs in any German
build input, in `static-lm-eval/{ood_dev,ood_test,heldout}_de.txt`, in the 2026-10-25 fresh
populations (`static-lm-fresh/{ud,web}/ood_dev_de.txt`) or earlier in the same file. Output:
`~/.cache/cleverkeys-corpora/static-lm-fresh/confirm/ood_dev_de.txt`. Validity: at least 5,000
sentences must remain, otherwise the confirmation is void (not passed) and a new corpus is
needed.

## Pairs

| pair | tree | `de_enhanced.bin` | `de.cklm` |
|---|---|---|---|
| old | `159be8291^` | as committed there | sha256 `63892b2c…`, 438,403 B |
| new | the commit that adds the `confirm` population | as committed there | sha256 `ee6a7333…`, 445,202 B |

The old tree gets the two harness files of the new tree
(`StaticLmTapEvalTest.kt`, `StaticLmLanguageData.kt`: `STATIC_LM_EVAL_TARGETS_IN` and the hit-count
lines did not exist before `159be8291`); everything else in it is `159be8291^`. Both asset hashes
are checked in each tree before each run. The new pair runs in a tree whose Kotlin sources equal
that commit (a clean worktree if the shared checkout has uncommitted Kotlin edits).

Run in each tree (`STATIC_LM_EVAL_SPLIT=dev` reads `ood_dev_de.txt` and has no in-domain part):

```
STATIC_LM_EVAL_LANGS=de STATIC_LM_EVAL_SPLIT=dev \
STATIC_LM_EVAL_DIR=~/.cache/cleverkeys-corpora/static-lm-fresh/confirm \
[STATIC_LM_EVAL_TARGETS_IN=<the other tree's de_enhanced.bin>] \
scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
```

## Cells

The eight `lm_static` cells of the OOD report: next-word top-1 and top-3, prefix re-rank at prefix
1, 2 and 3 top-1 and top-3. Rates are computed from the printed hit counts and n (unrounded), not
from the two-decimal percentages.

## Gates

- **C1 (shared positions).** Both runs with `STATIC_LM_EVAL_TARGETS_IN` set to the other pair's
  lexicon. Every one of the eight cells: new rate ≥ old rate − 0.10 pt. The two runs' n per cell
  are reported; they are expected to be equal or to differ only by contraction display forms.
- **C2 (hits, full population).** Both runs without `STATIC_LM_EVAL_TARGETS_IN`. Every one of the
  eight cells: new hit count ≥ old hit count.
- **C3 (reported, not gated).** The positions only the new lexicon scores (new full-population n
  and hits minus new shared-position n and hits, per cell): how often a ß target is predicted.

Confirmed = C1 and C2 both pass on a valid population. No cell is dropped, no threshold moved, no
second corpus tried after a failure.

## If it fails

G4 then stands failed without a remedy. The ß lexicon stays (it is a spelling fix with its own
tests). Attribution run on the same population: old model against new model, both under the NEW
lexicon (`STATIC_LM_MODEL_DIR` holding the old `de.cklm`, in the new tree), same eight cells.

- The new model is worse than the old one under the new lexicon in a failing cell (rate below
  old − 0.10 pt for C1, fewer hits for C2): the model is to blame; restoring the old model
  (sha256 `63892b2c…`) is proposed to the maintainer.
- Otherwise the loss comes from the lexicon's frequencies (the split and respelled words), and a
  lexicon-frequency finding is opened instead; the model stays.

In both cases the failure is reported and no asset is swapped by the agent running this.

## Result (2026-11-25, appended; nothing above was changed)

**Confirmed: C1 and C2 pass in all eight cells.** No attribution run was needed.

Population: 10,436 sentences (10,425 distinct), 142,261 tokens, after 17 dropped as build-input
or spent-eval overlap and 2 duplicates; valid (≥ 5,000). File sha256 `27382f79…`. 131,827
positions with context. Harness and population commit: `db43eda81`.

Trees: old `52c5a17d9` (`159be8291^`) with the two harness files of `db43eda81`; new a clean
worktree at `db43eda81`. Asset hashes checked before each run: old `de.cklm` `63892b2c…`,
`de_enhanced.bin` `eee28a47…`; new `ee6a7333…`, `fa7a4b0b…`. The new pair was first run in the
shared checkout while another session had uncommitted Kotlin edits there, so it was run again in
the clean worktree as registered; the two gave identical hit counts in every cell.

C1, positions both lexicons score (`lm_static`; the n is the same in both runs):

| cell | n | old hits | new hits | old % | new % | Δ pt |
|---|---|---|---|---|---|---|
| next-word top-1 | 116,154 | 9,091 | 9,208 | 7.827 | 7.927 | +0.101 |
| next-word top-3 | 116,154 | 17,159 | 17,314 | 14.773 | 14.906 | +0.133 |
| prefix 1 top-1 | 116,154 | 27,084 | 27,155 | 23.317 | 23.378 | +0.061 |
| prefix 1 top-3 | 116,154 | 45,920 | 45,966 | 39.534 | 39.573 | +0.040 |
| prefix 2 top-1 | 106,682 | 22,052 | 22,237 | 20.671 | 20.844 | +0.173 |
| prefix 2 top-3 | 106,682 | 53,384 | 53,400 | 50.040 | 50.055 | +0.015 |
| prefix 3 top-1 | 74,238 | 23,854 | 23,917 | 32.132 | 32.217 | +0.085 |
| prefix 3 top-3 | 74,238 | 41,864 | 41,988 | 56.392 | 56.559 | +0.167 |

Every cell is above the old pair, so the −0.10 pt allowance was not used.

C2, each pair's full population (`lm_static` hits):

| cell | old n | old hits | new n | new hits | Δ hits |
|---|---|---|---|---|---|
| next-word top-1 | 116,235 | 9,091 | 117,102 | 9,212 | +121 |
| next-word top-3 | 116,235 | 17,159 | 117,102 | 17,336 | +177 |
| prefix 1 top-1 | 116,225 | 27,084 | 117,092 | 27,192 | +108 |
| prefix 1 top-3 | 116,225 | 45,920 | 117,092 | 46,034 | +114 |
| prefix 2 top-1 | 106,753 | 22,052 | 107,620 | 22,306 | +254 |
| prefix 2 top-3 | 106,753 | 53,396 | 107,620 | 53,628 | +232 |
| prefix 3 top-1 | 74,309 | 23,865 | 75,158 | 24,087 | +222 |
| prefix 3 top-3 | 74,309 | 41,895 | 75,158 | 42,362 | +467 |

The full-population RATES repeat what failed G4: five cells are lower (prefix 1 top-3 −0.195 pt,
prefix 2 top-3 −0.187, prefix 1 top-1 −0.080, prefix 3 top-1 −0.067, prefix 3 top-3 −0.016),
because 867 more positions are scored and the added ones are hit less often than average. A rate
over a population the change itself enlarges is not the question this gate asks.

C3, positions only the new lexicon scores (not gated; `none` = no context model):

| cell | n | `lm_static` top-1 | `lm_static` top-3 | `none` top-1 | `none` top-3 |
|---|---|---|---|---|---|
| next-word | 948 | 4 (0.42%) | 22 (2.32%) | 0 | 0 |
| prefix 1 | 938 | 37 (3.94%) | 68 (7.25%) | 0 | 0 |
| prefix 2 | 938 | 69 (7.36%) | 228 (24.31%) | 3 | 184 |
| prefix 3 | 920 | 170 (18.48%) | 374 (40.65%) | 132 | 348 |

These are words the old pair could never offer. They are harder than average (rarer words), and
the model adds to the lexicon alone at every prefix length.

Limits: one corpus, one genre (news); no interval was registered, and the smallest C1 margin over
the old pair is 16 hits of 106,682 (prefix 2 top-3). 81 old-lexicon positions are outside the
shared set (words the new lexicon respelled or dropped at the 40,000 cap); the old pair's hits
on them count in C2, which still passes. The logs are local (scratch), not in git.
