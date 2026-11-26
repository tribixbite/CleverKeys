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
