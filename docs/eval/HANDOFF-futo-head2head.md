# HANDOFF — FUTO head-to-head eval (for a fresh agent, no prior context needed)

**Written:** 2026-07-24, by the session coordinator, after the executing Fable agent
(~1M tokens deep) died twice on spend limits. Everything durable is on disk or committed
(`02c8eab9`); nothing lives only in that agent's context. This doc is the complete state.

## Mission

Produce the definitive clean-data head-to-head of our two swipe decoders, replacing the
old biased numbers, and compute the fusion go/no-go inputs:

- Engines: shipped ONNX neural transformer (production pipeline: beam 6, GNMT α=1.4,
  vocab-trie masking, rerank 0.8·conf+0.2·0.57·freq) vs the pure-JVM geometric SHARK2
  engine (`swipe.geometric`).
- Corpus: FUTO swipe dataset (arXiv 2606.25247). BOTH local copies are the SAME source:
  `~/storage/shared/swipedata/` = hwsfuto splits (train 110,876 / val 9,918 / test 2,400;
  rows `{word, points:[{t,x,y}], …}`), and `~/.cache/cleverkeys-test/futo_train100k.jsonl.gz`
  = 100k HF-sampled rows of the SAME train split, DIFFERENT row format (positional arrays
  `pts:[[nx,ny,t]]`). Coordinates already [0,1]-normalized over the keyboard; timestamps
  cumulative relative ms (clean, ~60 Hz). OOV vs `en_enhanced.json` (98,140 words): 2.68%.
- Why the redo: the old "neural ~54% top-1" came from an 8.5k corpus with corrupt
  timestamps AND a defective harness. Both problems are now understood/fixed.

## What is DONE (do not redo)

1. **Harness audit + fix (committed).** `docs/eval/2026-07-24-harness-conversion-audit.md`
   has the verdict matrix. `tools/test_cli_predict.py` now has the training-exact path:
   `--frame-remap identity --training-features --production` (raw [0,1] coords straight
   through, authoritative normalized KeyboardGrid nearest-key, >250-pt resample). The OLD
   path (y=(4.5+177·ny)/280 squash + pixel grid) understates top-1 by ~4.5 pts.
2. **Swipedata validation (committed).** `docs/eval/2026-07-24-swipedata-onnx-validation.md`:
   corrected-path paired sanity on 500 val traces (seed 20260724, n=486 in-vocab):
   **76.34% top-1 / 85.39% top-3 / 88.68% top-5** (defective path: 71.81/82.30/85.39);
   strata: ≤3-char 88.33%, 4+ 69.28%. Incl.-OOV ×0.9732.
3. **Geometric decode: COMPLETE.** `~/.cache/cleverkeys-test/geo_futo100k.jsonl` =
   **97,887 rows** `{"idx","word","preds":[top10]}` over the 100k sample (in-dict traces).
   Produced by `GeoFutoTrain100kReplayTest` (registered in build.gradle; run:
   `sh gradlew runPureTests -PtestClass=swipe.geometric.GeoFutoTrain100kReplayTest`).
   Prior geo reference on the same corpus: 75.3% top-1.
4. **Metrics joiner exists**: `scripts/futo100k_metrics.py` joins geo + neural per-trace
   caches (top-1/3/5, strata, micro/macro, UNION ceiling). Read its header for the exact
   input contract (neural line k = k-th in-dict trace — ORDER-SENSITIVE).

## What is NOT trustworthy (verify, likely discard)

- `~/.cache/cleverkeys-test/neural_futo100k.jsonl` (10,000 rows, mtime 23:23 Jul 23) and
  `neural_futo100k.part00–09.jsonl` (part00 mtime 19:16): written BEFORE the corrected
  path was verified (~23:28 smoke_fixed / 23:53 sanity_fixed) → almost certainly
  DEFECTIVE-path decodes. Usable only as before/after evidence, labeled 'defective-path'.
- `neural_futo100k.part10.jsonl` (1,000 rows, mtime 23:59): MAY be corrected-path —
  verify by re-decoding ~20 of its traces with the corrected flags and diffing, or just
  discard and re-run. Do not mix paths in one metric.

## REMAINING WORK (the actual todo)

1. **Corrected-path neural decode** on the 100k sample's in-dict traces, same trace order
   the geo cache used (see futo100k_metrics.py contract + how run_swipedata_20k.sh drives
   chunks; adapt its pattern to the futo_train100k corpus — REMEMBER the positional-array
   row format needs its converter, NOT scripts/convert_swipedata_futo.mjs which parses
   point-objects; check how the prior parts were generated for the right converter/driver,
   `rg -l futo_train100k scripts/`).
   Cost reality: ~2,500 traces/hour single-run on this phone. Target **N ≥ 10,000
   corrected** (≈4–5 h); 20k if the budget allows. Chunk Bash calls ≤ 600000 ms; append
   to `neural_futo100k_fixed.partNN.jsonl` (NEW name — never mix with defective caches);
   resumable via skip counts.
2. **Metrics**: run `scripts/futo100k_metrics.py` on (geo cache × corrected neural cache),
   restricted to the common trace set: top-1/3/5 per stratum (≤3 vs 4+; finer if cheap),
   micro + macro over words with ≥5 examples, **union@1/@3 per stratum** (fusion
   go/no-go: if union headroom over max(engine) < ~2 pts on a stratum → fusion no-go
   there, per docs/history/audits/remediation-plans/hybrid-engine-rank-fusion.md).
3. **Report**: fill `docs/eval/2026-07-23-futo100k-head2head.md` (currently a 1-line
   stub): provenance (dataset, seed, N per engine, corrected-path flags), the metric
   tables, the comparison table old-8.5k (~54% neural, position-only+defective) vs
   FUTO-defective (~71.8–73.3%) vs FUTO-corrected (~76.3% sanity), union ceiling, and the
   fusion verdict (does complementarity survive: neural 88.3% ≤3-char vs geo's long-word
   strength — quantify per-stratum winner + union headroom).
4. Update `memory/todo.md` eval entries; commit report + todo (small conventional commit,
   sign "— <model>").

## Environment gotchas (Termux)

`grep`/`curl` are shell functions injecting `-G` — use `rg` / node fetch. `/tmp` not
writable → `$TMPDIR` or `~/.cache`. `sh gradlew`, not `./gradlew`. Logs/caches:
`~/.cache/cleverkeys-test/` (see `run20k.log`, `sanity_fixed.log` for run patterns).
Never `git add -A`; path-limited commits only. Project memory topic:
`~/.claude/projects/-data-data-com-termux-files-home-git-swype-cleverkeys/memory/geo-engine-datasets.md`.
