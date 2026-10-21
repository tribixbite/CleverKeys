# Autocorrect prefix length against the shipped default (2026-10-21)

Todo item 16. **Descriptive only:** every corpus here was already seen in the
2026-10-18 channel-ranking rounds, so nothing is tuned and no default moves. The question is
what a user loses by raising `autocorrect_prefix_length` ("Starting Letter Accuracy") from the
shipped 0.

## Method

`ChannelRankingEval` phase `prefix` (`b5121f77`), run from a separate worktree:

```
CK_CH_PHASE=prefix CK_EVAL_OUT=build/eval/prefix \
  scripts/gradle-guard.sh runMockTests -PtestClass=autocorrect.ChannelRankingEval
```

Two production `WordPredictor`s over the shipped `.bin` dictionary with shipped tuning
(channel ranking): the baseline with prefix 0 and the candidate with prefix 1. A fix means
the baseline is wrong and the candidate is right; a break is the reverse. Counts are raw pairs,
with distinct target words in parentheses.

## Results (prefix 1 vs 0)

| Set | Pairs | Accuracy 0 → 1 | Fixes | Breaks | Net (distinct) |
|-----|-------|----------------|-------|--------|----------------|
| GTC (DEV+TEST, real typos) | 32,568 | 25,745 → 25,349 (79.0 % → 77.8 %) | 377 (305) | 773 (553) | −248 |
| S-adj (one adjacent-key slip anywhere) | 278,842 | 266,063 → 230,735 (95.4 % → 82.7 %) | 636 (567) | 35,964 (9,459) | −8,892 |

The S-adj loss is the slips on the first letter: with prefix 1 the right word is never a
candidate. The fixes are cases where a rarer same-first-letter word was the right one
(`bery` → berry when `very` is excluded, the case that started item 16).

## Not run

- C-oov (valid unlisted words) at prefix 1, and all three sets at prefix 2. The run was
  stopped after the two sets above: it held the device-wide Gradle lock for over three hours
  and blocked other builds. Prefix 2 excludes a superset of prefix 1's candidates, so it can
  only lose more first-letter and second-letter slips. The checkpoint files allow resuming
  (`build/eval/prefix/prefix1-*.tsv`).
- C-oov is where a prefix could help (fewer valid words rewritten); unmeasured.

## Consequence

The shipped 0 stands. The setting's description ("Required matching prefix length (0–4)") does
not say what it costs. A proposed wording, for the maintainer and the native-speaker string
review: "Letters at the start of a word that autocorrect keeps. 0 also fixes a wrong first
letter; 1 or more never does."
