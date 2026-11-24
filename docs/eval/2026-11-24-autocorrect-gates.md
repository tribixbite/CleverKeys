# Autocorrect candidate gates: 0.65 / 2 vs 0.6778 / 3 (2026-11-24)

Maintainer, 2026-11-23, on the S25U's `autocorrect_char_match_threshold` 0.6778 (default 0.65) and
`autocorrect_max_length_diff` 3 (default 2): "do i need to soak test default settings or is that
what was benchmarked as best?" Measured at HEAD `4112648a`, English, DEV sets only.

## Were the gates ever benchmarked?

No. Both came from the upstream configuration: 0.67 and 2 were only checked for parity
(`docs/history/uk-config-todos.md:295-303`). 0.67 became 0.65 in `89d318fc8` (2026-05-19, ledger
`docs/history/task-ledgers/2026-08-23-pre-audit-ledger.md:1404`) as "slightly more permissive", with
no measurement behind the `Config.kt` comment. The channel-ranking eval
(`2026-10-18-channel-autocorrect-ranking.md` §2) held both gates fixed and tuned which candidate
wins; its §16 notes completions beyond the length gate as untested.

## Method

`ChannelRankingEval`, phase `gates` (added with this note). Each input runs once through the
production `autoCorrect` at the loosest setting (0.65 / 3) with the existing probe. Then, per setting,
the production `gatedCandidates` (private, by reflection) and `channelDecide` pick the output. Both
gates are pure filters in that loop, so this equals a full run. Checked two ways:
- the replay at the loosest setting matched the probed output on every input;
- 2,000 seeded inputs × 4 settings through the full `autoCorrect`: 0 mismatches.

Everything else stays at the shipped defaults (channel ranking, Hebrew δ, κ = 3.0, ρ = 1.5). S-adj-DEV
is a fixed quarter: base words with `(fnv(word) ushr 1) % 4 == 0`, 1,284 words and 36,024 of 137,871
pairs. Birkbeck is TEST-only and was not used.

```
CK_CH_PHASE=gates JAVA_TOOL_OPTIONS=-Xmx1100m GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m \
  scripts/gradle-guard.sh runMockTests -PtestClass=autocorrect.ChannelRankingEval   # 1 h 7 min
```
`CK_GATES_SETS` (default `named,dict,gtc,trunc,oov,sadj`) and `CK_GATES_SADJ_MOD` (default 4; 1 runs
all of S-adj-DEV) choose the work.

## Results

| set | 0.65 / 2 (shipped) | 0.6778 / 3 (S25U) | 0.6778 / 2 | 0.65 / 3 |
|---|---|---|---|---|
| GTC-DEV right / wrong / left (16,358 pairs) | 12,980 / 2,429 / 949 | 12,968 / 2,439 / 951 | 12,966 / 2,439 / 953 | 12,982 / 2,429 / 947 |
| GTC-DEV vs shipped: fixes / breaks (distinct targets) | — | 12 (11) / 24 (14), net −3 | 10 (9) / 24 (14), net −5 | 2 (2) / 0, net +2 |
| S-adj-DEV sample right (36,024 pairs) | 34,344 | 34,362 | 34,362 | 34,344 |
| C-oov-DEV valid words changed (36,241) | 15,186 (41.90 %) | 15,143 (41.78 %) | 15,141 | 15,189 |
| T-trunc-DEV completed to the target (6,278 prefixes) | 1,981 | 1,990 | 1,990 | 1,981 |
| T-trunc-DEV prefixes missing 3 letters completed (902) | 0 | 0 | 0 | 0 |
| Named cases (English, `shated`, 12 Hebrew) | all hold | all hold | all hold | all hold |
| Time per autocorrect (GTC sample, load 13–20, relative only) | 125 ms | 178 ms | 124 ms | 182 ms |

Repetition does not inflate these: GTC's 24 breaks are 24 distinct typos of 14 distinct targets.
Macro-averaged per target, GTC right is 76.25 % shipped and 76.22 % for the S25U's pair. Of the top
20,000 dictionary words, 27 are changed under every setting (the contraction-alias rewrite runs
before the gates).

**The threshold.** At 0.6778 two kinds of candidate drop out, both scored 0.667 at 0.65:
- a 3-letter typo reaching a 2-letter word. This costs extra-letter slips (`tio`, `tro` and `tos` →
  to; `bse` → be; `ain` → in) and helps missing-letter slips (`ony` → only, 11 occurrences; `ith` →
  with; `mke` → make; `bdy` → body);
- a 6-letter typo reaching a 4-letter word. This keeps rare valid words (`bemask`, `dupion`) and
  lets a completion win (`databa` → database).

**Length difference 3.** A candidate 3 letters away needs at least 3 insertions or deletions,
normally a cost of 4.5, above κ = 3.0, so it is almost never committed. Prefixes missing 3 letters
were completed 0 times under every setting. Its only effect is on inputs with three doubled-letter
edits, which cost exactly 3.00:
- 2 typos fixed (`rnnneturns` → returns, `successeeded` → succeeded);
- 3 valid words falsely corrected (`crookkneed` → crooned, `kennelling` → keeling, …).

It gates about 13 % more candidates per input (230 vs 204 on GTC) and costs about 45 % more time
per autocorrect on every set.

## Decision

- **Defaults unchanged:** 0.65 / 2.
- **Length difference 3:** no benefit, about 45 % more work per word.
- **Threshold 0.6778:** effect tiny, its sign unclear. It is a fair personal choice if fewer rewrites
  of rare words matter more than short extra-letter slips.
- **Soak testing:** the defaults need no special soak for accuracy.

Caveats: English only (the gates are global; Hebrew is covered only by the 12 named cases);
single-word inputs without context. Rows: the session scratchpad's
`autocorrect-gates/out/rows-gates.tsv` (not in git).
