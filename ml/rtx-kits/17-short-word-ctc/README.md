# Kit 17: short-word CTC fine-tune on the raw-path input contract

Todo item 17 (`memory/todo.md` "Open now"). The goal is that dwell-less `ad`/`wet`-class swipes
decode correctly, which the app-side fix of 2026-10-07 did not achieve. The recipe comes from
`docs/eval/2026-10-07-short-word-ctc.md` §6 and `docs/eval/2026-10-07-final-letter-drops.md` §6.
Everything up to the training step was smoke-tested on the phone with tiny inputs (§7). Nothing
was trained there, and the gate has not been read on any candidate.

## 1. What the kit does

1. **Contract rewrite.** Every training tier is passed through the app's 2026-10-07 CTC input:
   the 1.26 px noise drop, no smoothing, and one lift sample (`app_emulate(..., "fix")`).
   Canonical val/test traces are dropped before the rewrite (`contract_rows.py`).
2. **Targeted synthetic rows.** The selection is a geometry rule plus a training-count cap, with
   no word list. The generator is CleverKeys-ML's audited `english_synth` transplant, and half
   the rows get a final-stop variant (`targeted_synth.py`).
3. **Word-balanced sampling.** Per-word exposure is ∝ count^τ. Rows whose final stroke is at
   most 1.1 key get a 2× weight, and the synthetic mass is capped at 8 % (`word_balance.py` and
   `train_py_word_balance.patch`; all flags default to off).
4. **Fine-tune from the shipped run `v2kd-fresh-w1`.** Its own recorded args are reused
   (`finetune_cmd.py`), with init and KD from the shipped checkpoint, `--short-loss-weight 1.5`
   and T_out 32.
5. **The pre-registered gate** (`gate_eval.py`): shipped and candidate are compared through the
   app-mirror Python decoder, on the same rows, paired per trace.

## 2. Files

| file | role |
|---|---|
| `select_target_class.py` | geometry rule and gate-row extraction from FUTO swipe-1 validation |
| `class_words.tsv` | the pre-registered class, committed: 379 words; 377 have gate rows (sha256 `05991866…5d23`) |
| `contract_rows.py` | tier JSONL → raw-path contract JSONL, with hold-out exclusion |
| `targeted_synth.py` | geometry-selected synthetic npz (needs CleverKeys-ML `ctc/`) |
| `word_balance.py` | `WeightedRandomSampler` builder; copy it next to `train.py` |
| `train_py_word_balance.patch` | adds 4 flags to CleverKeys-ML `ctc/train.py`; checked with `git apply --check` against ML `8778fef` |
| `finetune_cmd.py` | prints the exact `train.py` command from the shipped checkpoint's args |
| `gate_eval.py` | the gate; it reuses `scripts/short_word_ctc_eval.py` as-is |
| `DATA_MANIFEST.md` | every input: location, size, sha256, licence |

## 3. Where held-out human swipe data stands (finding, 2026-11-04)

All of this was measured on the complete `session` columns, using the HF parquet of
`futo-org/swipe.futo.org` revision `d71bf5fd`.

| FUTO swipe-1 split | rows | sessions | shared sessions with train | with test | with validation |
|---|---|---|---|---|---|
| train | 939,550 | 10,889 | — | 0 | 0 |
| test | 49,970 | 697 | 0 | — | 0 |
| validation (`dev.jsonl`) | 54,269 | 692 | 0 | 0 | — |

* **The shipped encoder's training data:** all of swipe-1 train (T3, no session exclusion),
  How-We-Swipe ×3, swipe-2/3/4, and swipe-5 qwerty-en. Sources: CleverKeys-ML
  `PHASE_L.md` §3 and `DATA_TIERS.md`. Swipe-1 test and validation are in no training tier.
  CleverKeys-ML fetched `dev.jsonl` only for its session-leak scan.
* **Swipe-1 test is spent as a gate.** It is the October 7 note's held-out (the 4,000-row
  sample), and `GeoRealCorpusReplayTest` uses it. It stays in this kit as the
  **non-regression** set only.
* **Swipe-1 validation is the clean gate.** It is session-disjoint from everything trained. Only
  2 of its rows were looked at before: `Ad` (1 point, unusable) and one `wet`, both in §2 of the
  October 7 note. `select_target_class.py` drops both words as development cases.
* **Literal `ad`/`wet` traces barely exist anywhere.** Validation has `ad` 1, `wet` 1 and `add` 2;
  test has 1, 1 and 2; train has 15, 25 and 22. A literal-word gate is impossible on any public
  data. The bar is therefore applied to the **`ad`/`wet` CLASS**, defined by geometry (§4). On
  validation that class holds **567 traces of 377 words from 271 sessions** (M1 462, M2 99, both
  6).
* **Residual risk:** FUTO session ids are anonymous and per-run. One volunteer may have joined
  under two ids, and that person-level overlap cannot be measured.
* **To collect fresh writer-separated data**, use the app's own capture: the Swipe Playground
  Export, or `privacy_collect_swipe` rows. Since 2026-10-08 these carry `raw_trace_points`, the
  CTC contract. An export file has no writer id, so one export must be one writer. Coordinates
  are px with `key_geometry`, so converting to the letter-box frame needs a converter, which
  this kit does not include (TODO). A literal `ad`/`wet` gate at ±5 pt would take about 100
  traces per word. Spread over at least 10 writers, that is roughly 10 prompted traces per word
  per writer.

## 4. Pre-registration (fixed 2026-11-04, before any candidate exists)

**The class (`select_target_class.py`, golden layout in a 1000 × 470 px box).** A word qualifies
if it has 2..5 letters a–z, is in `en_enhanced`, has an HF swipe-1 train count ≤ 50, and meets
one of these:

* **M1:** the path passes straight through an interior key, with a turn of less than 35°.
* **M2:** the final stroke passes within 0.5 key of another key c, and word[:-1]+c is a more
  frequent lexicon word.

> Disclosure: the cap of 50 was chosen after this agent saw the class **counts** at caps 20, 50
> and 100: 352, 567 and 785 validation rows. A smoke test also decoded the first 20 sorted class
> rows with the SHIPPED model, which got 15/20 top-1. No candidate exists, and no recipe choice
> depends on these numbers. But the 15/20 suggests the shipped model may already be above 50 %
> on this class, which is why bar C2 exists.

**Bars.** These are evaluated by `gate_eval.py` at the app preset (γ 0.9, λ 4.0, β 0.25, beam
100) on the fix contract. Every one must PASS on **each of the 3 seeds** of the chosen τ, or
nothing ships:

| id | bar | source |
|---|---|---|
| C1 | class top-1 ≥ 50 %; distinct words and sessions reported | short-word §6.4 ("≥ 50 % top-1 for `ad`/`wet`-class words") |
| C2 | class: gains > losses over shipped, one-sided exact sign test p < 0.05 | kit addition: the class baseline is not the 0/7 the 50 % bar was written against |
| K1 | controls (`we as wt at set sad add ads`, validation rows) pooled top-1 Δ ≥ −2 pt | short-word §1 bar 4 |
| K2 | no control with ≥ 10 traces loses more than 5 pt | short-word §1 bar 4 |
| H1/H2 | held-out (FUTO test sample) top-1 and top-3 Δ ≥ −0.3 pt | short-word §1 bars 2–3 ("FUTO test sample non-regression", §6.4) |
| H3 | held-out ≤3-letter top-1 Δ ≥ −0.5 pt | short-word §1 bar 3 |
| H4 | held-out paired losses not significantly more than gains (p ≥ 0.05) | short-word §1 bar 2 |
| V1 | val_hwsfuto top-1 ≥ shipped − 0.2 | short-word §6.4 |
| V2 | val_hwsfuto ≤3-letter top-1 ≥ shipped | short-word §6.4 |
| F1 | held-out s-plural top-1 ≥ shipped; short-hook stem wins ≤ 3 (was 7) | final-letter-drops §6.5. Printed by `gate_eval.py`; read by hand, because the stem-win count needs the §6.5 class definition |

Reported only: per-mechanism class top-1 (M1, M2, M1+M2) and word-averaged class top-1.

**Selection.** Two τ arms (0.5, 0.7) are trained at seed 1234. `train.py`'s own checkpoint
selection on `val_hwsfuto` picks each arm's `best.pt`. The arm with the higher val beam top-1
wins, and a tie goes to τ 0.7, the milder one. Its seeds 4321 and 7777 are then trained. The
gate is run once per seed after all three exist, with no re-runs except after a crash, and any
re-run is reported. λ stays 4.0. A λ re-sweep on dev (`short_word_ctc_eval.py lambda`) is
reported for information; any λ change is its own later round.

## 5. Prerequisites on the RTX box

```bash
APP=/home/will/git/swype/CleverKeys          # this repo, at the commit that added this kit
CTC=/home/will/git/CleverKeys-ML/ctc         # CleverKeys-ML at 8778fef or later
WD=/home/will/ctc-train                      # the campaign workdir
KIT=$APP/ml/rtx-kits/17-short-word-ctc
```

* Python with torch, numpy and onnxruntime: the same environment the campaign trains in.
* `$WD/ckpt/v2kd-fresh-w1/best.pt`: the shipped run's checkpoint. Its fp16w export must have
  sha256 `84718e6e…88e5`, which equals `src/main/assets/models/ctc_swipe_encoder.onnx`.
* `$WD/data/tier_t3.jsonl`, `tier_t3hws.jsonl`, `tier_sw234.jsonl`, `tier_sw5q.jsonl`, plus the
  canonical `{train,val,test}_hwsfuto.jsonl` and `futo_en_wordlist.combined`.
* `$WD/data/hf/train.jsonl` and `dev.jsonl`, from `python3 $CTC/fetch_hf_swipe1.py --workdir $WD`
  (5.16 GB + 0.30 GB; sha256 in `DATA_MANIFEST.md`).
* `$WD/data/futo_swipe1_test_sample.jsonl.gz`, copied from the phone's `~/.cache/cleverkeys-test/`
  (sha256 `03118771…e258`).

## 6. Commands, in order

```bash
cd $CTC
# 1. patch the trainer (dry-run first; the tree must be clean)
git status --short && git apply --check $KIT/train_py_word_balance.patch \
  && git apply $KIT/train_py_word_balance.patch && cp $KIT/word_balance.py .

# 2. the gate rows. class_words.tsv must come out byte-identical to the committed one
python3 $KIT/select_target_class.py --train $WD/data/hf/train.jsonl \
  --gate $WD/data/hf/dev.jsonl --out $WD/gate17
sha256sum $WD/gate17/*      # expect class_words 05991866…, gate_rows 5a906469…, controls 0197362a…
diff $WD/gate17/class_words.tsv $KIT/class_words.tsv && echo CLASS_OK

# 3. contract-rewrite every real tier, then featurize
for t in tier_t3 tier_t3hws tier_sw234 tier_sw5q; do
  python3 $KIT/contract_rows.py --app $APP --ctc . --workdir $WD \
    --in data/$t.jsonl --out data/${t}_fix.jsonl
done
python3 prepare_data.py --workdir $WD --extra-train data/tier_t3_fix.jsonl    --out-name train_t3_fix    --jobs 16
python3 prepare_data.py --workdir $WD --extra-train data/tier_t3hws_fix.jsonl --out-name train_t3hws_fix --jobs 16
python3 prepare_data.py --workdir $WD --extra-train data/tier_sw234_fix.jsonl --out-name tier_sw234_fix  --jobs 16
python3 prepare_data.py --workdir $WD --extra-train data/tier_sw5q_fix.jsonl  --out-name tier_sw5q_fix   --jobs 16

# 4. targeted synthetic rows (exit 2 = an acceptance gate failed: do NOT train on it;
#    the one pre-registered fallback is --no-m3; if that fails too, stop and report)
python3 $KIT/targeted_synth.py --app $APP --ctc . --workdir $WD --rows 120000 \
  --donors train_t3_fix.npz,train_t3hws_fix.npz,tier_sw234_fix.npz,tier_sw5q_fix.npz

# 5. tau selection at seed 1234 (print, check, run; the two can run concurrently)
for tau in 0.5 0.7; do
  python3 $KIT/finetune_cmd.py --workdir $WD --tau $tau --seed 1234 --steps 60000 \
    --run-name sw17-t${tau/./}-s1234 | tee $WD/sw17-t${tau/./}-s1234.cmd
done
bash $WD/sw17-t05-s1234.cmd & bash $WD/sw17-t07-s1234.cmd & wait
# winner = higher "best val_beam_t1" in ckpt/<run>/metrics.jsonl (tie -> 0.7)

# 6. the winner's other two seeds (TAU = 0.5 or 0.7)
for s in 4321 7777; do
  python3 $KIT/finetune_cmd.py --workdir $WD --tau $TAU --seed $s --steps 60000 \
    --run-name sw17-t${TAU/./}-s$s > $WD/sw17-t${TAU/./}-s$s.cmd
done
bash $WD/sw17-t${TAU/./}-s4321.cmd & bash $WD/sw17-t${TAU/./}-s7777.cmd & wait

# 7. export + fp16w (the artifact that would ship) for each of the 3 seeds
for r in sw17-t${TAU/./}-s1234 sw17-t${TAU/./}-s4321 sw17-t${TAU/./}-s7777; do
  python3 export_onnx.py --workdir $WD --ckpt ckpt/$r/best.pt --out ckpt/$r/ctc_swipe_encoder.onnx
  python3 quantize_onnx.py --workdir $WD --onnx ckpt/$r/ctc_swipe_encoder.onnx \
    --out ckpt/$r/ctc_swipe_encoder_fp16w.onnx --mode fp16w
done

# 8. smoke the gate harness on NON-gate rows first (controls stand in for class), then the gate
python3 $KIT/gate_eval.py --candidate $WD/ckpt/<run>/ctc_swipe_encoder_fp16w.onnx \
  --class-rows $WD/gate17/controls_rows.jsonl --controls-rows $WD/gate17/controls_rows.jsonl \
  --heldout $WD/data/futo_swipe1_test_sample.jsonl.gz --val $WD/data/val_hwsfuto.jsonl \
  --out $WD/gate17/smoke --limit 10
for r in sw17-t${TAU/./}-s1234 sw17-t${TAU/./}-s4321 sw17-t${TAU/./}-s7777; do
  python3 $KIT/gate_eval.py --candidate $WD/ckpt/$r/ctc_swipe_encoder_fp16w.onnx \
    --class-rows $WD/gate17/gate_rows.jsonl --controls-rows $WD/gate17/controls_rows.jsonl \
    --heldout $WD/data/futo_swipe1_test_sample.jsonl.gz --val $WD/data/val_hwsfuto.jsonl \
    --out $WD/gate17/$r | tee $WD/gate17/$r.log
done
```

## 7. Runtime estimates and what was smoke-tested here

* **Step 3:** `contract_rows.py` handled 3,000 rows in about 1.5 s on the phone, so about 1.3 M
  rows should take about 10–15 min single-process on the PC. `prepare_data` ran at 2,988 rows
  in 1.4 s.
* **Step 4:** a few minutes. Smoke on a 3 k donor pool: all three gates ran. `ru_precedent`
  failed on that tiny pool, and so did upstream `english_synth --mode tail` on the same pool.
  Expect the real pool to decide it.
* **Steps 5–6:** the shipped recipe ran at 11.8 steps/s alone and 18.4 with three arms
  (`PHASE_L.md` §3), so 60 k steps is about 1.5–2.5 h per run, 4 runs total. The 60 k step count
  is this kit's choice; the notes do not fix it.
* **Step 8:** the Python beam took about 0.15 s per decode on the phone. A full gate is about
  15.2 k rows × 2 models, so expect about 30–60 min per seed on a desktop CPU.
* **Smoke-tested on the phone, all with tiny inputs:**
  * the patch (`git apply --check`);
  * `train.py` with the patch and every new flag, run for 6 steps on CPU;
  * `finetune_cmd.py` against a stand-in checkpoint;
  * `contract_rows.py`, `prepare_data.py` and `targeted_synth.py`;
  * `gate_eval.py --limit`.
* **Not smoke-tested:** `export_onnx.py`'s BN-fold parity failed on the 6-step toy checkpoint.
  That is expected for an untrained net, and the export itself is CleverKeys-ML's established
  step.

## 7.1 Prep check on the phone (2026-11-05, app `c0466391`, CleverKeys-ML `8778fef` = origin/main)

Nothing here trains or reads a gate row through a candidate. All checks passed:

* **Hashes:** shipped encoder `84718e6e…88e5`; `class_words.tsv` `05991866…5d23`; the local copies of
  `dev.jsonl` (`8d9d34ec…`), `futo_swipe1_test_sample.jsonl.gz` (`03118771…`), `val_hwsfuto`
  (`0ac53f4d…`), `test_hwsfuto` (`e58eeb23…`) and `futo_en_wordlist.combined` (`771d87cd…`) all
  match §DATA_MANIFEST.
* **Step 2 reproduces byte-identically on today's code** (selector, `en_enhanced.json`, QWERTY
  geometry): `class_words` `05991866…`, `gate_rows` `5a906469…`, `controls_rows` `0197362a…`;
  377 words, 567 traces, 271 sessions, 2 development rows dropped. (Train counts from the HF
  `word` column, as on 11-04.)
* **Patch:** `git apply --check` clean against CleverKeys-ML `8778fef`, which is still origin/main.
* **Decoder preset:** `short_word_ctc_eval.py` γ 0.9, λ 4.0, β 0.25, γ_prune 0.25, β_prune 0.9882,
  beam 100 equal the app's English preset (`CtcScoringParams.tunedV2`, `LAMBDA_EN_JSON_SCALE` 4.0).
* **Gate harness smoke** (`--limit 10`, controls standing in for the class, the SHIPPED model as
  the candidate): every Δ is 0.00 and C2 FAILS with 0 gains / 0 losses, i.e. a candidate that
  changes nothing cannot pass. 24 s on the phone.
* **`contract_rows.py` smoke:** 3,000 `train_hwsfuto` rows + 50 planted `val_hwsfuto` rows →
  62 dropped as holdout (the 50 planted + 12 train rows that are bit-exact copies of val/test
  traces), 2,988 kept, 5.6 s. Over all of `train_hwsfuto`: 35 of 2,399 test traces and 182 of
  9,907 val traces occur verbatim in train — the known CleverKeys-ML audit #3 overlap
  (`ctc/README.md`, there 35 / 186), which `prepare_data.py` and this kit already exclude.
* **Types:** all seven scripts compile; mypy clean after a typing-only `cast` in
  `word_balance.py` (the sampler draws identical indices before and after).
* **Not checkable here:** `finetune_cmd.py` and the export need `ckpt/v2kd-fresh-w1/best.pt`, and
  the tiers `tier_t3/t3hws/sw234/sw5q` exist only on the RTX box (record their sha256 before
  step 3).

## 8. What to send back

* `$WD/gate17/<run>/gate_report.tsv` and `<run>.log` for the 3 seeds.
* `ckpt/sw17-*/metrics.jsonl` for all 4 runs, the 4 `.cmd` files, the
  `synth_en_shortword_stats.json`, and the step-2 sha256 lines.
* If and only if every bar passes on every seed: the s1234 `ctc_swipe_encoder_fp16w.onnx` and
  its sha256. The app side then moves the model, the preset and a regenerated golden fixture
  together. The fixture comes from `make_golden.py`, which since ML `7343355` emits a
  basename-only `source_onnx`, so this also closes ARC-061 for `ctc_golden.json`.

If any bar fails, nothing ships. Record the negative result in
`docs/eval/2026-10-07-short-word-ctc.md`.
